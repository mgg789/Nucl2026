package com.peerdone.app.core.transport

import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity

interface TransportAdapter {
    val type: TransportType
    fun health(): TransportHealth

    /**
     * Возвращает true при успешной отправке.
     * Конкретный адаптер сам решает формат/протокол отправки.
     */
    fun send(
        sender: LocalIdentity,
        content: OutboundContent,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
    ): Boolean
}

class StubTransportAdapter(
    override val type: TransportType,
    private val staticHealth: TransportHealth,
) : TransportAdapter {
    override fun health(): TransportHealth = staticHealth

    override fun send(
        sender: LocalIdentity,
        content: OutboundContent,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
    ): Boolean = false
}

