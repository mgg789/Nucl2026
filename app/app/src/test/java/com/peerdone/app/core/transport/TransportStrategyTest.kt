package com.peerdone.app.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStrategyTest {

    private fun health(
        type: TransportType,
        available: Boolean = true,
        latencyMs: Int = 50,
        bandwidthKbps: Int = 1000,
        batteryCost: Int = 5,
        stabilityScore: Int = 8,
    ) = TransportHealth(
        type = type,
        available = available,
        estimatedLatencyMs = latencyMs,
        estimatedBandwidthKbps = bandwidthKbps,
        estimatedBatteryCost = batteryCost,
        stabilityScore = stabilityScore,
    )

    @Test
    fun chooseBest_returns_null_when_no_available_transports() {
        val list = listOf(
            health(TransportType.NEARBY, available = false),
            health(TransportType.LAN_P2P, available = false),
        )
        val decision = TransportStrategy.chooseBest(DeliveryClass.REALTIME, list)
        assertNull(decision.selected)
        assertTrue(decision.reason.contains("Нет доступного"))
    }

    @Test
    fun chooseBest_selects_preferred_when_available() {
        val list = listOf(
            health(TransportType.NEARBY, latencyMs = 100),
            health(TransportType.LAN_P2P, latencyMs = 20),
        )
        val decision = TransportStrategy.chooseBest(
            DeliveryClass.REALTIME,
            list,
            preferred = TransportType.NEARBY,
        )
        assertEquals(TransportType.NEARBY, decision.selected)
    }

    @Test
    fun chooseBest_ignores_preferred_when_not_available_and_picks_best() {
        val list = listOf(
            health(TransportType.NEARBY, available = false),
            health(TransportType.LAN_P2P, latencyMs = 20, stabilityScore = 10),
        )
        val decision = TransportStrategy.chooseBest(
            DeliveryClass.REALTIME,
            list,
            preferred = TransportType.NEARBY,
        )
        assertEquals(TransportType.LAN_P2P, decision.selected)
    }

    @Test
    fun chooseBest_realtime_prefers_low_latency() {
        val lowLatency = health(TransportType.LAN_P2P, latencyMs = 10, stabilityScore = 7)
        val highLatency = health(TransportType.NEARBY, latencyMs = 200, stabilityScore = 7)
        val decision = TransportStrategy.chooseBest(
            DeliveryClass.REALTIME,
            listOf(highLatency, lowLatency),
        )
        assertEquals(TransportType.LAN_P2P, decision.selected)
    }

    @Test
    fun chooseBest_ranked_contains_only_available_when_some_unavailable() {
        val list = listOf(
            health(TransportType.NEARBY, available = false),
            health(TransportType.LAN_P2P, available = true),
        )
        val decision = TransportStrategy.chooseBest(DeliveryClass.INTERACTIVE, list)
        assertEquals(2, decision.ranked.size)
        assertTrue(decision.ranked.all { it.available })
    }

    @Test
    fun chooseBest_single_available_returns_it() {
        val list = listOf(health(TransportType.WIFI_DIRECT))
        val decision = TransportStrategy.chooseBest(DeliveryClass.BULK, list)
        assertEquals(TransportType.WIFI_DIRECT, decision.selected)
    }

    @Test
    fun chooseBest_null_preferred_uses_score_only() {
        val list = listOf(
            health(TransportType.NEARBY, latencyMs = 100, stabilityScore = 5),
            health(TransportType.LAN_P2P, latencyMs = 50, stabilityScore = 9),
        )
        val decision = TransportStrategy.chooseBest(DeliveryClass.REALTIME, list, preferred = null)
        assertEquals(TransportType.LAN_P2P, decision.selected)
    }
}
