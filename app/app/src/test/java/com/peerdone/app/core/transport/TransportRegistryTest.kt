package com.peerdone.app.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRegistryTest {

    @Test
    fun snapshot_empty_when_no_providers() {
        val registry = TransportRegistry()
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun snapshot_returns_results_from_registered_providers() {
        val registry = TransportRegistry()
        registry.register { TransportHealth(TransportType.NEARBY, true, 50, 1000, 5, 8) }
        registry.register { TransportHealth(TransportType.LAN_P2P, true, 20, 2000, 3, 9) }
        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.any { it.type == TransportType.NEARBY })
        assertTrue(snapshot.any { it.type == TransportType.LAN_P2P })
    }

    @Test
    fun snapshot_invokes_provider_each_time() {
        var counter = 0
        val registry = TransportRegistry()
        registry.register {
            counter++
            TransportHealth(TransportType.NEARBY, true, counter * 10, 1000, 5, 8)
        }
        val s1 = registry.snapshot().first().estimatedLatencyMs
        val s2 = registry.snapshot().first().estimatedLatencyMs
        assertEquals(10, s1)
        assertEquals(20, s2)
    }

    @Test
    fun register_multiple_same_type_all_returned() {
        val registry = TransportRegistry()
        registry.register { TransportHealth(TransportType.NEARBY, true, 1, 0, 0, 0) }
        registry.register { TransportHealth(TransportType.NEARBY, false, 2, 0, 0, 0) }
        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(1, snapshot.count { it.type == TransportType.NEARBY })
    }
}
