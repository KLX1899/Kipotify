package com.example.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.BuildConfig
import com.example.data.local.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    private val lanEndpoints = linkedMapOf<String, LanBackendEndpoint>()

    @Volatile
    private var activeEndpoint: String = configuredEndpoints.first()

    private val _connectionState = MutableStateFlow<BackendConnectionState>(BackendConnectionState.Discovering)
    val connectionState: StateFlow<BackendConnectionState> = _connectionState.asStateFlow()

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
        lanEndpoints[endpoint.id] = endpoint
        val preferred = selectPreferredLanEndpoint() ?: endpoint
        activeEndpoint = preferred.baseUrl
        settingsManager.setLastLanBackend(preferred.baseUrl)
        _connectionState.value = BackendConnectionState.Connected(preferred.baseUrl)
    }

    @Synchronized
    fun removeLanEndpoint(id: String) {
        val removed = lanEndpoints.remove(id) ?: return
        if (activeEndpoint == removed.baseUrl) {
            val replacement = selectPreferredLanEndpoint()
            if (replacement != null) {
                activeEndpoint = replacement.baseUrl
                settingsManager.setLastLanBackend(replacement.baseUrl)
                _connectionState.value = BackendConnectionState.Connected(replacement.baseUrl)
            } else {
                _connectionState.value = BackendConnectionState.Reconnecting(removed.baseUrl)
            }
        }
    }

    fun discoveryStarted() {
        if (lanEndpoints.isEmpty()) _connectionState.value = BackendConnectionState.Discovering
    }

    fun discoveryUnavailable(reason: String) {
        if (lanEndpoints.isEmpty()) _connectionState.value = BackendConnectionState.Unavailable(reason)
    }

    fun discoveryTimedOut(rejectedInsecureService: Boolean) {
        if (lanEndpoints.isNotEmpty()) return
        _connectionState.value = BackendConnectionState.Fallback(
            if (rejectedInsecureService) {
                "A local Kipotify server was found, but HTTPS is required. Using the configured server."
            } else {
                "No local Kipotify server found. Using the configured server."
            }
        )
    }

    fun transportFailed(baseUrl: String) {
        if (activeEndpoint == baseUrl && lanEndpoints.values.any { it.baseUrl == baseUrl }) {
            _connectionState.value = BackendConnectionState.Reconnecting(baseUrl)
        }
    }

    private fun selectPreferredLanEndpoint(): LanBackendEndpoint? = lanEndpoints.values.maxWithOrNull(
        compareBy<LanBackendEndpoint> { it.priority }.thenBy { it.id }
    )
}

/**
 * Uses Android's DNS-SD/NSD implementation instead of subnet scanning. Only private IPv4
 * addresses are accepted and discovered servers must answer Kipotify's health endpoint.
 */
class LanBackendDiscovery(
    context: Context,
    private val endpointRegistry: BackendEndpointRegistry,
) {
    val connectionState: StateFlow<BackendConnectionState> = endpointRegistry.connectionState

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolvingServices = mutableSetOf<String>()
    private var discoveryRunning = false
    private var timeoutJob: Job? = null
    private var rejectedInsecureService = false

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != KIPOTIFY_NSD_SERVICE_TYPE) return
            resolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            endpointRegistry.removeLanEndpoint(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryRunning = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryRunning = false
            endpointRegistry.discoveryUnavailable("Local server discovery could not start (error $errorCode).")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryRunning = false
        }
    }

    fun start() {
        if (discoveryRunning) return
        discoveryRunning = true
        rejectedInsecureService = false
        endpointRegistry.discoveryStarted()
        try {
            nsdManager.discoverServices(KIPOTIFY_NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(DISCOVERY_TIMEOUT_MS)
                endpointRegistry.discoveryTimedOut(rejectedInsecureService)
            }
        } catch (error: Exception) {
            discoveryRunning = false
            endpointRegistry.discoveryUnavailable("Local server discovery is unavailable: ${error.message ?: "unknown error"}")
        }
    }

    fun stop() {
        timeoutJob?.cancel()
        if (!discoveryRunning) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
            discoveryRunning = false
        }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val serviceName = serviceInfo.serviceName
        synchronized(resolvingServices) {
            if (!resolvingServices.add(serviceName)) return
        }
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(resolvingServices) { resolvingServices.remove(serviceName) }
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                synchronized(resolvingServices) { resolvingServices.remove(serviceName) }
                scope.launch { verifyAndActivate(resolved) }
            }
        })
    }

    private fun verifyAndActivate(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host ?: return
        if (!host.isUsableLanAddress()) return
        if (serviceInfo.attributes["kipotify-api"]?.decodeToString() != "v1") return

        val scheme = serviceInfo.attributes["scheme"]?.decodeToString()?.lowercase() ?: "https"
        if (scheme !in setOf("http", "https")) return
        if (scheme == "http" && !BuildConfig.KIPOTIFY_ALLOW_INSECURE_LAN) {
            rejectedInsecureService = true
            return
        }

        val endpoint = buildEndpoint(scheme, host, serviceInfo.port) ?: return
        if (!passesHealthCheck(endpoint)) return
        val priority = serviceInfo.attributes["priority"]
            ?.decodeToString()
            ?.toIntOrNull()
            ?.coerceIn(0, 1000)
            ?: 0
        endpointRegistry.activateLan(LanBackendEndpoint(serviceInfo.serviceName, endpoint, priority))
    }

    private fun passesHealthCheck(baseUrl: String): Boolean {
        val url = "${baseUrl.trimEnd('/')}/healthz".toHttpUrlOrNull() ?: return false
        val request = Request.Builder().url(url).get().build()
        return try {
            healthClient.newCall(request).execute().use { response ->
                response.isSuccessful && (response.body?.string()?.contains("\"status\":\"ok\"") == true)
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun buildEndpoint(scheme: String, host: InetAddress, port: Int): String? {
        if (port !in 1..65535) return null
        return okhttp3.HttpUrl.Builder()
            .scheme(scheme)
            .host(host.hostAddress)
            .port(port)
            .build()
            .toString()
    }

    private fun InetAddress.isUsableLanAddress(): Boolean =
        this is Inet4Address && !isLoopbackAddress && (isSiteLocalAddress || hostAddress.startsWith("169.254."))

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
