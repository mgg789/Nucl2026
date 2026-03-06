package com.example.app.data

import com.example.app.core.message.ContentCodec
import com.example.app.core.message.OutboundContent
import com.example.app.core.transport.DeliveryClass
import com.example.app.core.transport.TransportAdapter
import com.example.app.core.transport.TransportHealth
import com.example.app.core.transport.TransportType
import com.example.app.domain.AccessPolicy
import com.example.app.domain.LocalIdentity

class NearbyTransportAdapter(
    private val nearby: NearbyMeshClient,
) : TransportAdapter {
    override val type: TransportType = TransportType.NEARBY

    override fun health(): TransportHealth = nearby.transportHealth()

    override fun send(
        sender: LocalIdentity,
        content: OutboundContent,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
    ): Boolean {
        val ttl = when (deliveryClass) {
            DeliveryClass.REALTIME -> 2
            DeliveryClass.INTERACTIVE -> 3
            DeliveryClass.BULK -> 4
        }
        val env = nearby.buildChatEnvelope(
            sender = sender,
            messageText = ContentCodec.encode(content),
            policy = policy,
            ttl = ttl,
        ) ?: return false
        val preview = when (content) {
            is OutboundContent.Text -> content.text
            else -> "[${content.kind}]"
        }
        return nearby.sendChat(env, previewText = preview) > 0
    }
}

