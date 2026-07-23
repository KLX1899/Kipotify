package com.example.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class LanDiscoveryPolicyTest {
    @Test
    fun `endpoint selection prefers the active LAN subnet and filters unusable addresses`() {
        val phone = ipv4("192.168.50.40")
        val selection = selectUsableLanAddresses(
            addresses = listOf(
                ipv4("10.0.0.8"),
                InetAddress.getByName("169.254.4.2"),
                InetAddress.getLoopbackAddress(),
                ipv4("192.168.50.9"),
            ),
            activeSubnets = listOf(subnetOf(phone, 24)),
        )

        assertEquals(listOf("192.168.50.9", "10.0.0.8"), selection.addresses.map { it.hostAddress })
        assertTrue(selection.hasAddressOnActiveSubnet)
    }

    @Test
    fun `network change clears all stale LAN endpoints`() {
        val ledger = LanEndpointLedger()
        ledger.put(LanBackendEndpoint("server", "https://192.168.1.2:8080/", 1))
        ledger.put(LanBackendEndpoint("other", "https://192.168.1.3:8080/", 0))

        assertEquals(NetworkTransition.RESTART, networkTransition("wifi:one", "wifi:two"))
        val stale = ledger.clear()

        assertEquals(2, stale.size)
        assertTrue(ledger.isEmpty())
        assertEquals(NetworkTransition.STOP, networkTransition("wifi:two", null))
        assertEquals(NetworkTransition.NONE, networkTransition(null, null))
    }

    @Test
    fun `retry backoff stays bounded while discovery remains active`() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L), (1..5).map(::retryDelayMillis))
        assertTrue(shouldRetry(4))
        assertTrue(shouldRetry(100))
        assertFalse(shouldRetry(0))
    }

    @Test
    fun `selection reports a different subnet while retaining fallback candidates`() {
        val selection = selectUsableLanAddresses(
            addresses = listOf(ipv4("10.2.0.7")),
            activeSubnets = listOf(subnetOf(ipv4("192.168.1.20"), 24)),
        )

        assertEquals(listOf("10.2.0.7"), selection.addresses.map { it.hostAddress })
        assertTrue(selection.hasActiveSubnet)
        assertFalse(selection.hasAddressOnActiveSubnet)
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
