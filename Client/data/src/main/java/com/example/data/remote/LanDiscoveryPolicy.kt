package com.example.data.remote

import java.net.Inet4Address
import java.net.InetAddress

internal data class LanSubnet(
    val address: Int,
    val prefixLength: Int,
)

internal data class LanAddressSelection(
    val addresses: List<Inet4Address>,
    val hasActiveSubnet: Boolean,
    val hasAddressOnActiveSubnet: Boolean,
)

internal data class DiscoveryDiagnostics(
    @Volatile var advertisementReceived: Boolean = false,
    @Volatile var unusableAddressReceived: Boolean = false,
    @Volatile var healthCheckFailed: Boolean = false,
    @Volatile var insecureAdvertisementRejected: Boolean = false,
    @Volatile var differentSubnet: Boolean = false,
)

internal enum class NetworkTransition {
    NONE,
    RESTART,
    STOP,
}

internal fun networkTransition(previous: String?, next: String?): NetworkTransition = when {
    previous == next -> NetworkTransition.NONE
    next == null -> NetworkTransition.STOP
    else -> NetworkTransition.RESTART
}

internal fun subnetOf(address: Inet4Address, prefixLength: Int): LanSubnet {
    val value = address.address.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xff) }
    return LanSubnet(value, prefixLength.coerceIn(0, 32))
}

internal fun selectUsableLanAddresses(
    addresses: Collection<InetAddress>,
    activeSubnets: Collection<LanSubnet>,
): LanAddressSelection {
    val usable = addresses
        .filterIsInstance<Inet4Address>()
        .filter(Inet4Address::isUsablePrivateLanAddress)
        .distinctBy { it.hostAddress }
    val hasMatchingAddress = usable.any { candidate ->
        activeSubnets.any { subnet -> subnet.contains(candidate) }
    }
    return LanAddressSelection(
        addresses = usable.sortedWith(
            compareByDescending<Inet4Address> { candidate ->
                activeSubnets.any { subnet -> subnet.contains(candidate) }
            }.thenBy { it.hostAddress }
        ),
        hasActiveSubnet = activeSubnets.isNotEmpty(),
        hasAddressOnActiveSubnet = hasMatchingAddress,
    )
}

internal fun Inet4Address.isUsablePrivateLanAddress(): Boolean {
    val bytes = address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val privateAddress = first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168)
    return privateAddress && !isLoopbackAddress && !isLinkLocalAddress &&
        !isAnyLocalAddress && !isMulticastAddress
}

internal fun LanSubnet.contains(candidate: Inet4Address): Boolean {
    if (prefixLength == 0) return true
    val candidateValue = candidate.address.fold(0) { result, byte ->
        (result shl 8) or (byte.toInt() and 0xff)
    }
    val mask = -1 shl (32 - prefixLength)
    return (address and mask) == (candidateValue and mask)
}

internal fun retryDelayMillis(attempt: Int): Long =
    (500L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(4_000L)

internal fun shouldRetry(attempt: Int): Boolean = attempt > 0

internal class LanEndpointLedger {
    private val endpoints = linkedMapOf<String, LanBackendEndpoint>()

    fun put(endpoint: LanBackendEndpoint) {
        endpoints[endpoint.id] = endpoint
    }

    fun remove(id: String): LanBackendEndpoint? = endpoints.remove(id)

    fun clear(): List<LanBackendEndpoint> = endpoints.values.toList().also { endpoints.clear() }

    fun values(): Collection<LanBackendEndpoint> = endpoints.values

    fun isEmpty(): Boolean = endpoints.isEmpty()
}
