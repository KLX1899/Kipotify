package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** The DNS-SD service type advertised by a Kipotify backend. */
const val KIPOTIFY_NSD_SERVICE_TYPE = "_kipotify._tcp."

sealed interface BackendConnectionState {
    val message: String

    data object Discovering : BackendConnectionState {
        override val message = "Looking for a Kipotify server on this network…"
    }

    data class Connected(val endpoint: String) : BackendConnectionState {
        override val message = "Connected to local server $endpoint"
    }

    data class Reconnecting(val endpoint: String?) : BackendConnectionState {
        override val message = endpoint?.let { "Reconnecting to local server $it…" }
            ?: "Reconnecting to a local server…"
    }

    data class Fallback(val reason: String) : BackendConnectionState {
        override val message = reason
    }

    data class Unavailable(val reason: String) : BackendConnectionState {
        override val message = reason
    }
}

data class BackendConnectionNotice(
    val id: Long,
    val connection: BackendConnectionState,
)

data class LanBackendEndpoint(
    val id: String,
    val baseUrl: String,
    val priority: Int,
)

/**
 * Thread-safe source of endpoints for Retrofit. A LAN endpoint is used only after a direct,
 * short health check succeeds; the build-time endpoints remain available as a fallback.
 */
class BackendEndpointRegistry(
    private val settingsManager: SettingsManager,
) {
    private val configuredEndpoints: List<String> = buildList {
        add(BuildConfig.KIPOTIFY_BASE_URL)
        addAll(BuildConfig.KIPOTIFY_FALLBACK_BASE_URLS.split(','))
    }.map(String::trim).filter(String::isNotEmpty).distinct()

    private val lanEndpoints = LanEndpointLedger()

    @Volatile
    private var activeEndpoint: String = configuredEndpoints.first()

    private val _connectionState = MutableStateFlow<BackendConnectionState>(BackendConnectionState.Discovering)
    val connectionState: StateFlow<BackendConnectionState> = _connectionState.asStateFlow()
    private var nextConnectionNoticeId = 1L
    private val _connectionNotice = MutableStateFlow(
        BackendConnectionNotice(id = 0L, connection = BackendConnectionState.Discovering)
    )
    val connectionNotice: StateFlow<BackendConnectionNotice> = _connectionNotice.asStateFlow()

    @Synchronized
    fun requestCandidates(): List<String> = buildList {
        add(activeEndpoint)
        settingsManager.getLastLanBackend()?.let(::add)
        addAll(configuredEndpoints)
    }.map(String::trim).filter(String::isNotEmpty).distinct()

    @Synchronized
    fun activeBaseUrl(): String = activeEndpoint

    @Synchronized
    fun activateLan(endpoint: LanBackendEndpoint) {
        lanEndpoints.put(endpoint)
        val preferred = selectPreferredLanEndpoint() ?: endpoint
        activeEndpoint = preferred.baseUrl
        settingsManager.setLastLanBackend(preferred.baseUrl)
        updateConnectionState(BackendConnectionState.Connected(preferred.baseUrl))
    }

    @Synchronized
    fun removeLanEndpoint(id: String) {
        val removed = lanEndpoints.remove(id) ?: return
        if (activeEndpoint == removed.baseUrl) {
            val replacement = selectPreferredLanEndpoint()
            if (replacement != null) {
                activeEndpoint = replacement.baseUrl
                settingsManager.setLastLanBackend(replacement.baseUrl)
                updateConnectionState(BackendConnectionState.Connected(replacement.baseUrl))
            } else {
                activeEndpoint = configuredEndpoints.first()
                settingsManager.clearLastLanBackend()
                updateConnectionState(BackendConnectionState.Reconnecting(removed.baseUrl))
            }
        }
    }

    @Synchronized
    fun networkChanged() {
        val previous = lanEndpoints.clear()
        val staleActive = previous.firstOrNull { it.baseUrl == activeEndpoint }?.baseUrl
        activeEndpoint = configuredEndpoints.first()
        settingsManager.clearLastLanBackend()
        updateConnectionState(BackendConnectionState.Reconnecting(staleActive))
    }

    @Synchronized
    fun discoveryStarted() {
        if (lanEndpoints.isEmpty()) updateConnectionState(BackendConnectionState.Discovering)
    }

    @Synchronized
    fun discoveryUnavailable(reason: String) {
        if (lanEndpoints.isEmpty()) updateConnectionState(BackendConnectionState.Unavailable(reason))
    }

    @Synchronized
    fun discoveryProblem(reason: String) {
        if (lanEndpoints.isEmpty()) updateConnectionState(BackendConnectionState.Unavailable(reason))
    }

    @Synchronized
    internal fun discoveryTimedOut(diagnostics: DiscoveryDiagnostics) {
        if (!lanEndpoints.isEmpty()) return
        updateConnectionState(
            BackendConnectionState.Fallback(
                when {
                    diagnostics.insecureAdvertisementRejected ->
                        "A local server advertised HTTP, but the security policy requires HTTPS. Using the configured server."
                    diagnostics.unusableAddressReceived ->
                        "A local service resolved only to stale or unusable addresses. Using the configured server."
                    diagnostics.differentSubnet ->
                        "The phone and discovered server appear to be on different subnets. Using the configured server."
                    diagnostics.healthCheckFailed ->
                        "A local server was resolved, but its health check failed. Using the configured server."
                    !diagnostics.advertisementReceived ->
                        "No local service advertisement was received; multicast may be blocked by AP isolation or a firewall. Using the configured server."
                    else ->
                        "No healthy local Kipotify server was found. Using the configured server."
                }
            )
        )
    }

    @Synchronized
    fun transportFailed(baseUrl: String) {
        if (activeEndpoint == baseUrl && lanEndpoints.values().any { it.baseUrl == baseUrl }) {
            updateConnectionState(BackendConnectionState.Reconnecting(baseUrl))
        }
    }

    private fun updateConnectionState(connection: BackendConnectionState) {
        _connectionState.value = connection
        _connectionNotice.value = BackendConnectionNotice(
            id = nextConnectionNoticeId++,
            connection = connection,
        )
    }

    private fun selectPreferredLanEndpoint(): LanBackendEndpoint? = lanEndpoints.values().maxWithOrNull(
        compareBy<LanBackendEndpoint> { it.priority }.thenBy { it.id }
    )
}

private data class ActiveLanNetwork(
    val network: Network,
    val fingerprint: String,
    val subnets: List<LanSubnet>,
)

private class DiscoverySession(
    val network: ActiveLanNetwork,
    val diagnostics: DiscoveryDiagnostics,
) {
    lateinit var listener: NsdManager.DiscoveryListener
    val discoveredServices = mutableSetOf<String>()
    val pendingResolutions = linkedMapOf<String, Pair<NsdServiceInfo, Int>>()
    val retryJobs = mutableMapOf<String, Job>()
    var activeResolution: String? = null
    var timeoutJob: Job? = null
    var stopFallbackJob: Job? = null
    var stopping: Boolean = false

    fun cancelJobs() {
        timeoutJob?.cancel()
        retryJobs.values.forEach(Job::cancel)
        retryJobs.clear()
    }
}

/**
 * Discovers DNS-SD only on the validated active Wi-Fi/Ethernet network. Each network change
 * invalidates old endpoints, stops the old listener, and starts a fresh network-bound search.
 */
class LanBackendDiscovery(
    context: Context,
    private val endpointRegistry: BackendEndpointRegistry,
) {
    val connectionState: StateFlow<BackendConnectionState> = endpointRegistry.connectionState
    val connectionNotice: StateFlow<BackendConnectionNotice> = endpointRegistry.connectionNotice

    private val applicationContext = context.applicationContext
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var started = false
    private var closed = false
    private var callbackRegistered = false
    private var networkFingerprint: String? = null
    private var activeSession: DiscoverySession? = null
    private var pendingNetwork: ActiveLanNetwork? = null

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = evaluateActiveNetwork()

        override fun onLost(network: Network) = evaluateActiveNetwork()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            evaluateActiveNetwork()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            evaluateActiveNetwork()

        override fun onUnavailable() = applyNetwork(null)
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (started || closed) return
            started = true
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            val unregisterImmediately = synchronized(lifecycleLock) {
                if (closed) {
                    true
                } else {
                    callbackRegistered = true
                    false
                }
            }
            if (unregisterImmediately) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                return
            }
            evaluateActiveNetwork()
        } catch (error: Exception) {
            val unregister = synchronized(lifecycleLock) {
                started = false
                callbackRegistered.also { callbackRegistered = false }
            }
            if (unregister) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (_: Exception) {
                    // Registration may have failed before Android retained the callback.
                }
            }
            endpointRegistry.discoveryUnavailable(
                "Local discovery cannot observe connectivity: ${error.message ?: "unknown error"}"
            )
            Log.w(TAG, "Unable to register connectivity callback", error)
        }
    }

    fun stop() {
        val session: DiscoverySession?
        val unregister: Boolean
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            started = false
            unregister = callbackRegistered
            callbackRegistered = false
            networkFingerprint = null
            pendingNetwork = null
            session = activeSession
            session?.stopping = true
            session?.cancelJobs()
        }
        if (unregister) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // The callback may already have been removed while the process was shutting down.
            }
        }
        session?.let(::stopSession)
        scope.cancel()
    }

    private fun evaluateActiveNetwork() {
        val activeNetwork = connectivityManager.activeNetwork
        val snapshot = activeNetwork?.let(::validatedLanNetwork)
        applyNetwork(snapshot)
    }

    private fun validatedLanNetwork(network: Network): ActiveLanNetwork? {
        if (connectivityManager.activeNetwork != network) return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        val localTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!localTransport || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return null
        }
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        val links = linkProperties.linkAddresses.mapNotNull { link ->
            val address = link.address as? Inet4Address ?: return@mapNotNull null
            if (!address.isUsablePrivateLanAddress()) return@mapNotNull null
            address to link.prefixLength
        }
        if (links.isEmpty()) return null
        val fingerprint = buildString {
            append(network)
            append(':')
            append(links.sortedBy { it.first.hostAddress }.joinToString(",") {
                "${it.first.hostAddress}/${it.second}"
            })
        }
        return ActiveLanNetwork(
            network = network,
            fingerprint = fingerprint,
            subnets = links.map { (address, prefix) -> subnetOf(address, prefix) },
        )
    }

    private fun applyNetwork(next: ActiveLanNetwork?) {
        val oldSession: DiscoverySession?
        val startImmediately: ActiveLanNetwork?
        val stopRequired: Boolean
        synchronized(lifecycleLock) {
            if (!started) return
            when (networkTransition(networkFingerprint, next?.fingerprint)) {
                NetworkTransition.NONE -> return
                NetworkTransition.RESTART, NetworkTransition.STOP -> Unit
            }
            Log.i(TAG, "Active LAN changed: old=$networkFingerprint new=${next?.fingerprint}")
            networkFingerprint = next?.fingerprint
            pendingNetwork = next
            oldSession = activeSession
            if (oldSession != null) {
                stopRequired = !oldSession.stopping
                if (stopRequired) {
                    oldSession.stopping = true
                    oldSession.cancelJobs()
                }
                startImmediately = null
            } else {
                stopRequired = false
                startImmediately = pendingNetwork
                pendingNetwork = null
            }
        }

        endpointRegistry.networkChanged()
        if (next == null) {
            endpointRegistry.discoveryUnavailable(
                "Waiting for a validated Wi-Fi or Ethernet local network."
            )
        }
        if (oldSession != null && stopRequired) {
            stopSession(oldSession)
        } else {
            startImmediately?.let(::beginDiscovery)
        }
    }

    private fun beginDiscovery(network: ActiveLanNetwork) {
        val diagnostics = DiscoveryDiagnostics()
        val session = DiscoverySession(network, diagnostics)
        session.listener = createDiscoveryListener(session)
        synchronized(lifecycleLock) {
            if (!started || networkFingerprint != network.fingerprint || activeSession != null) return
            activeSession = session
        }

        endpointRegistry.discoveryStarted()
        Log.i(TAG, "Starting NSD on ${network.fingerprint}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.discoverServices(
                    KIPOTIFY_NSD_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    network.network,
                    applicationContext.mainExecutor,
                    session.listener,
                )
            } else {
                nsdManager.discoverServices(
                    KIPOTIFY_NSD_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    session.listener,
                )
            }
            session.timeoutJob = scope.launch {
                delay(DISCOVERY_TIMEOUT_MS)
                if (isCurrent(session)) endpointRegistry.discoveryTimedOut(session.diagnostics.copy())
            }
        } catch (error: Exception) {
            synchronized(lifecycleLock) {
                if (activeSession === session) activeSession = null
            }
            endpointRegistry.discoveryUnavailable(
                "Local server discovery could not start: ${error.message ?: "unknown error"}"
            )
            Log.w(TAG, "NSD start failed", error)
        }
    }

    private fun createDiscoveryListener(session: DiscoverySession) =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "NSD discovery started on ${session.network.fingerprint}")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isCurrent(session) || !serviceInfo.isKipotifyService()) return
                session.diagnostics.advertisementReceived = true
                synchronized(session) { session.discoveredServices.add(serviceInfo.serviceName) }
                Log.i(TAG, "NSD advertisement received: service=${serviceInfo.serviceName}")
                resolve(session, serviceInfo, 1)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(session) {
                    session.retryJobs.remove(serviceInfo.serviceName)?.cancel()
                    session.pendingResolutions.remove(serviceInfo.serviceName)
                    session.discoveredServices.remove(serviceInfo.serviceName)
                }
                endpointRegistry.removeLanEndpoint(serviceInfo.serviceName)
                Log.i(TAG, "NSD service lost: service=${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped on ${session.network.fingerprint}")
                finishStop(session)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD start failed: error=$errorCode")
                if (session.stopping) {
                    finishStop(session)
                } else {
                    synchronized(lifecycleLock) {
                        if (activeSession === session) activeSession = null
                    }
                    endpointRegistry.discoveryUnavailable(
                        "Local server discovery could not start (error $errorCode)."
                    )
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stop failed: error=$errorCode")
                finishStop(session)
            }
        }

    private fun stopSession(session: DiscoverySession) {
        try {
            nsdManager.stopServiceDiscovery(session.listener)
            val fallback = scope.launch {
                delay(STOP_FALLBACK_MS)
                finishStop(session)
            }
            synchronized(lifecycleLock) {
                if (activeSession === session) {
                    session.stopFallbackJob?.cancel()
                    session.stopFallbackJob = fallback
                } else {
                    fallback.cancel()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "NSD stop threw; continuing restart", error)
            finishStop(session)
        }
    }

    private fun finishStop(session: DiscoverySession) {
        val next: ActiveLanNetwork?
        synchronized(lifecycleLock) {
            if (activeSession !== session) return
            session.stopFallbackJob?.cancel()
            session.cancelJobs()
            activeSession = null
            next = if (started) pendingNetwork else null
            pendingNetwork = null
        }
        next?.let(::beginDiscovery)
    }

    @Suppress("DEPRECATION")
    private fun resolve(session: DiscoverySession, serviceInfo: NsdServiceInfo, attempt: Int) {
        if (!isCurrent(session)) return
        val serviceName = serviceInfo.serviceName
        synchronized(session) {
            if (serviceName !in session.discoveredServices) return
            if (session.activeResolution != null) {
                if (session.activeResolution != serviceName) {
                    session.pendingResolutions[serviceName] = serviceInfo to attempt
                }
                return
            }
            session.activeResolution = serviceName
            session.retryJobs.remove(serviceName)?.cancel()
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                releaseResolution(session, serviceName)
                session.diagnostics.unusableAddressReceived = true
                endpointRegistry.discoveryProblem(
                    "A local advertisement was received, but service resolution failed (error $errorCode)."
                )
                Log.w(TAG, "NSD resolve failed: service=$serviceName attempt=$attempt error=$errorCode")
                scheduleRetry(session, serviceInfo, attempt)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                releaseResolution(session, serviceName)
                scope.launch { verifyAndActivate(session, serviceInfo, resolved, attempt) }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.resolveService(serviceInfo, applicationContext.mainExecutor, listener)
            } else {
                nsdManager.resolveService(serviceInfo, listener)
            }
        } catch (error: Exception) {
            releaseResolution(session, serviceName)
            Log.w(TAG, "NSD resolve threw: service=$serviceName attempt=$attempt", error)
            scheduleRetry(session, serviceInfo, attempt)
        }
    }

    private fun releaseResolution(session: DiscoverySession, serviceName: String) {
        val next: Pair<NsdServiceInfo, Int>?
        synchronized(session) {
            if (session.activeResolution == serviceName) session.activeResolution = null
            val nextEntry = session.pendingResolutions.entries.firstOrNull()
            next = nextEntry?.value
            if (nextEntry != null) session.pendingResolutions.remove(nextEntry.key)
        }
        if (next != null && isCurrent(session)) {
            scope.launch { resolve(session, next.first, next.second) }
        }
    }

    private fun verifyAndActivate(
        session: DiscoverySession,
        original: NsdServiceInfo,
        resolved: NsdServiceInfo,
        attempt: Int,
    ) {
        if (!isCurrent(session)) return
        synchronized(session) {
            if (resolved.serviceName !in session.discoveredServices) return
        }
        if (resolved.attributes["kipotify-api"]?.decodeToString() != "v1") return

        val scheme = resolved.attributes["scheme"]?.decodeToString()?.lowercase() ?: "https"
        if (scheme !in setOf("http", "https")) return
        if (scheme == "http" && !BuildConfig.KIPOTIFY_ALLOW_INSECURE_LAN) {
            session.diagnostics.insecureAdvertisementRejected = true
            endpointRegistry.discoveryProblem(
                "A local server advertised HTTP, but this build requires HTTPS."
            )
            Log.w(TAG, "Rejected insecure HTTP advertisement: service=${resolved.serviceName}")
            return
        }

        val resolvedAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolved.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(resolved.host)
        }
        val selection = selectUsableLanAddresses(resolvedAddresses, session.network.subnets)
        if (selection.addresses.isEmpty()) {
            session.diagnostics.unusableAddressReceived = true
            endpointRegistry.discoveryProblem(
                "A local service resolved only to stale or unusable IPv4 addresses."
            )
            Log.w(TAG, "No usable IPv4 address: service=${resolved.serviceName} addresses=${resolvedAddresses.safeIpList()}")
            scheduleRetry(session, original, attempt)
            return
        }
        if (selection.hasActiveSubnet && !selection.hasAddressOnActiveSubnet) {
            session.diagnostics.differentSubnet = true
            Log.w(
                TAG,
                "Server is on a different subnet: service=${resolved.serviceName} addresses=${selection.addresses.safeIpList()}"
            )
        }

        val priority = resolved.attributes["priority"]
            ?.decodeToString()
            ?.toIntOrNull()
            ?.coerceIn(0, 1000)
            ?: 0
        for (address in selection.addresses) {
            if (!isCurrent(session)) return
            val endpoint = buildEndpoint(scheme, address, resolved.port) ?: continue
            Log.i(TAG, "Health-checking resolved LAN endpoint: service=${resolved.serviceName} endpoint=$endpoint")
            if (passesHealthCheck(session.network.network, endpoint)) {
                synchronized(session) { session.retryJobs.remove(resolved.serviceName)?.cancel() }
                endpointRegistry.activateLan(
                    LanBackendEndpoint(resolved.serviceName, endpoint, priority)
                )
                Log.i(TAG, "Healthy LAN endpoint activated: service=${resolved.serviceName} endpoint=$endpoint")
                return
            }
            session.diagnostics.healthCheckFailed = true
            Log.w(TAG, "LAN health check failed: service=${resolved.serviceName} endpoint=$endpoint")
        }

        endpointRegistry.discoveryProblem(
            if (session.diagnostics.differentSubnet) {
                "The discovered server is on a different subnet and its health check failed."
            } else {
                "The local server resolved, but its health check failed."
            }
        )
        scheduleRetry(session, original, attempt)
    }

    private fun scheduleRetry(
        session: DiscoverySession,
        serviceInfo: NsdServiceInfo,
        completedAttempt: Int,
    ) {
        if (!isCurrent(session) || !shouldRetry(completedAttempt)) return
        val serviceName = serviceInfo.serviceName
        synchronized(session) {
            if (serviceName !in session.discoveredServices) return
            session.retryJobs.remove(serviceName)?.cancel()
            session.retryJobs[serviceName] = scope.launch {
                val delayMillis = retryDelayMillis(completedAttempt)
                Log.i(TAG, "Retrying NSD service: service=$serviceName in=${delayMillis}ms")
                delay(delayMillis)
                synchronized(session) { session.retryJobs.remove(serviceName) }
                if (isCurrent(session)) resolve(session, serviceInfo, completedAttempt + 1)
            }
        }
    }

    private fun passesHealthCheck(network: Network, baseUrl: String): Boolean {
        val url = "${baseUrl.trimEnd('/')}/healthz".toHttpUrlOrNull() ?: return false
        val request = Request.Builder().url(url).get().build()
        return try {
            healthClient.newBuilder()
                .socketFactory(network.socketFactory)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    response.isSuccessful &&
                        response.body?.string()?.contains("\"status\":\"ok\"") == true
                }
        } catch (_: IOException) {
            false
        }
    }

    private fun buildEndpoint(scheme: String, host: InetAddress, port: Int): String? {
        if (port !in 1..65535) return null
        val hostAddress = host.hostAddress ?: return null
        return okhttp3.HttpUrl.Builder()
            .scheme(scheme)
            .host(hostAddress)
            .port(port)
            .build()
            .toString()
    }

    private fun isCurrent(session: DiscoverySession): Boolean = synchronized(lifecycleLock) {
        started && activeSession === session && !session.stopping &&
            networkFingerprint == session.network.fingerprint
    }

    private fun NsdServiceInfo.isKipotifyService(): Boolean =
        serviceType.trimEnd('.') == KIPOTIFY_NSD_SERVICE_TYPE.trimEnd('.')

    private fun Collection<InetAddress>.safeIpList(): List<String> = mapNotNull(InetAddress::getHostAddress)

    companion object {
        private const val TAG = "KipotifyDiscovery"
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val STOP_FALLBACK_MS = 750L
    }
}
