package com.peerdone.app.data

import com.peerdone.app.core.message.ContentCodec
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.DeliveryClass
import com.peerdone.app.core.transport.TransportAdapter
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity

/** Адаптер транспорта для [MeshClientRouter]: тип и здоровье берутся из текущего активного клиента (Nearby или WiFi Direct). */
class RouterTransportAdapter(
    private val router: MeshClientRouter,
) : TransportAdapter {
    override val type: TransportType
        get() = router.transportHealth().type

    override fun health(): TransportHealth = router.transportHealth()

    override fun send(
        sender: LocalIdentity,
        content: OutboundContent,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
        targetPeerId: String?,
    ): Boolean {
        router.rememberSentContent(content)
        val ttl = when (deliveryClass) {
            DeliveryClass.REALTIME -> 2
            DeliveryClass.INTERACTIVE -> 3
            DeliveryClass.BULK -> 4
        }
        if (targetPeerId != null) {
            return router.sendChatToPeer(targetPeerId, sender, content, policy, ttl) > 0
        }
        val env = router.buildChatEnvelope(
            sender = sender,
            messageText = ContentCodec.encode(content),
            policy = policy,
            ttl = ttl,
        ) ?: return false
        val preview = when (content) {
            is OutboundContent.Text -> content.text
            is OutboundContent.FileMeta -> "Файл: ${content.fileName}"
            is OutboundContent.FileChunk -> "Файл"
            is OutboundContent.VoiceNoteMeta -> "Голосовое сообщение"
            is OutboundContent.VideoNoteMeta -> "Видео"
            else -> "[${content.kind}]"
        }
        return router.sendChat(env, previewText = preview) > 0
    }
}
