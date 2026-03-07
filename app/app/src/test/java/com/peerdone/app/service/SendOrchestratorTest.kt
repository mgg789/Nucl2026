package com.peerdone.app.service

import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.DeliveryClass
import com.peerdone.app.core.transport.TransportAdapter
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.core.transport.StubTransportAdapter
import com.peerdone.app.core.transport.TransportRegistry
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendOrchestratorTest {

    private val sender = LocalIdentity("u1", "org_city", 1)

    @Test
    fun enqueueAndTrySend_adds_to_pending_when_no_transport() {
        val registry = TransportRegistry()
        val orchestrator = SendOrchestrator(adapters = emptyMap(), transportRegistry = registry)
        val outcome = orchestrator.enqueueAndTrySend(
            sender = sender,
            text = "Hi",
            policy = AccessPolicy(),
            deliveryClass = DeliveryClass.INTERACTIVE,
        )
        assertFalse(outcome.sent)
        assertTrue(outcome.reason.contains("Нет доступного") || outcome.reason.contains("транспорт"))
        assertEquals(1, orchestrator.pendingMessages.value.size)
    }

    @Test
    fun enqueueAndTrySend_sends_when_adapter_returns_true() {
        val registry = TransportRegistry()
        registry.register { TransportHealth(TransportType.NEARBY, true, 50, 1000, 5, 8) }
        val health = TransportHealth(TransportType.NEARBY, true, 50, 1000, 5, 8)
        val adapter = object : TransportAdapter {
            override val type = TransportType.NEARBY
            override fun health() = health
            override fun send(
                sender: LocalIdentity,
                content: OutboundContent,
                policy: AccessPolicy,
                deliveryClass: DeliveryClass,
                targetPeerId: String?,
            ) = true
        }
        val orchestrator = SendOrchestrator(
            adapters = mapOf(TransportType.NEARBY to adapter),
            transportRegistry = registry,
        )
        val outcome = orchestrator.enqueueAndTrySend(
            sender = sender,
            text = "Hi",
            policy = AccessPolicy(),
            deliveryClass = DeliveryClass.INTERACTIVE,
        )
        assertTrue(outcome.sent)
        assertEquals(0, orchestrator.pendingMessages.value.size)
    }

    @Test
    fun enqueueAndTrySend_keeps_in_pending_when_adapter_returns_false() {
        val registry = TransportRegistry()
        registry.register { TransportHealth(TransportType.NEARBY, true, 50, 1000, 5, 8) }
        val adapter = StubTransportAdapter(
            TransportType.NEARBY,
            TransportHealth(TransportType.NEARBY, true, 50, 1000, 5, 8),
        )
        val orchestrator = SendOrchestrator(
            adapters = mapOf(TransportType.NEARBY to adapter),
            transportRegistry = registry,
        )
        val outcome = orchestrator.enqueueAndTrySend(
            sender = sender,
            text = "Hi",
            policy = AccessPolicy(),
            deliveryClass = DeliveryClass.INTERACTIVE,
        )
        assertFalse(outcome.sent)
        assertEquals(1, orchestrator.pendingMessages.value.size)
    }

    @Test
    fun preferred_transport_used_when_available() {
        val registry = TransportRegistry()
        registry.register { TransportHealth(TransportType.NEARBY, true, 100, 500, 6, 7) }
        registry.register { TransportHealth(TransportType.LAN_P2P, true, 20, 2000, 3, 9) }
        var usedType: TransportType? = null
        val nearbyHealth = TransportHealth(TransportType.NEARBY, true, 100, 500, 6, 7)
        val nearbyAdapter = object : TransportAdapter {
            override val type = TransportType.NEARBY
            override fun health() = nearbyHealth
            override fun send(
                sender: LocalIdentity,
                content: OutboundContent,
                policy: AccessPolicy,
                deliveryClass: DeliveryClass,
                targetPeerId: String?,
            ): Boolean {
                usedType = TransportType.NEARBY
                return true
            }
        }
        val lanHealth = TransportHealth(TransportType.LAN_P2P, true, 20, 2000, 3, 9)
        val lanAdapter = object : TransportAdapter {
            override val type = TransportType.LAN_P2P
            override fun health() = lanHealth
            override fun send(
                sender: LocalIdentity,
                content: OutboundContent,
                policy: AccessPolicy,
                deliveryClass: DeliveryClass,
                targetPeerId: String?,
            ) = true
            }
        val orchestrator = SendOrchestrator(
            adapters = mapOf(
                TransportType.NEARBY to nearbyAdapter,
                TransportType.LAN_P2P to lanAdapter,
            ),
            transportRegistry = registry,
            preferredTransport = { TransportType.NEARBY },
        )
        orchestrator.enqueueAndTrySend(sender, "Hi", AccessPolicy(), DeliveryClass.INTERACTIVE)
        assertEquals(TransportType.NEARBY, usedType)
    }

    @Test
    fun DispatchOutcome_data() {
        val o = DispatchOutcome(sent = true, reason = "OK", transport = TransportType.NEARBY)
        assertTrue(o.sent)
        assertEquals(TransportType.NEARBY, o.transport)
    }

    @Test
    fun OutboundQueuedMessage_has_defaults() {
        val msg = OutboundQueuedMessage(
            sender = sender,
            content = OutboundContent.Text("x"),
            policy = AccessPolicy(),
            deliveryClass = DeliveryClass.INTERACTIVE,
        )
        assertTrue(msg.queueId.isNotEmpty())
        assertEquals(0, msg.attempts)
    }
}
