package com.peerdone.app.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportModelsTest {

    @Test
    fun TransportType_enum_values() {
        assertEquals(5, TransportType.entries.size)
        assertEquals(TransportType.NEARBY, TransportType.valueOf("NEARBY"))
        assertEquals(TransportType.LAN_P2P, TransportType.valueOf("LAN_P2P"))
    }

    @Test
    fun DeliveryClass_enum_values() {
        assertEquals(3, DeliveryClass.entries.size)
        assertEquals(DeliveryClass.REALTIME, DeliveryClass.valueOf("REALTIME"))
        assertEquals(DeliveryClass.BULK, DeliveryClass.valueOf("BULK"))
    }

    @Test
    fun TransportHealth_data_holds_values() {
        val h = TransportHealth(
            type = TransportType.WIFI_DIRECT,
            available = true,
            estimatedLatencyMs = 30,
            estimatedBandwidthKbps = 5000,
            estimatedBatteryCost = 7,
            stabilityScore = 6,
        )
        assertEquals(TransportType.WIFI_DIRECT, h.type)
        assertEquals(30, h.estimatedLatencyMs)
        assertEquals(5000, h.estimatedBandwidthKbps)
    }

    @Test
    fun TransportDecision_selected_can_be_null() {
        val d = TransportDecision(
            selected = null,
            ranked = emptyList(),
            reason = "No transport",
        )
        assertNull(d.selected)
        assertEquals("No transport", d.reason)
    }

    @Test
    fun TransportDecision_holds_ranked_list() {
        val ranked = listOf(
            TransportHealth(TransportType.LAN_P2P, true, 10, 2000, 3, 9),
            TransportHealth(TransportType.NEARBY, true, 50, 500, 6, 7),
        )
        val d = TransportDecision(selected = TransportType.LAN_P2P, ranked = ranked, reason = "ok")
        assertEquals(2, d.ranked.size)
        assertEquals(TransportType.LAN_P2P, d.selected)
    }
}
