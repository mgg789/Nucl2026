package com.peerdone.app.service

import com.peerdone.app.core.file.FileTransferPlanner
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.DeliveryClass
import com.peerdone.app.core.transport.TransportAdapter
import com.peerdone.app.core.transport.TransportRegistry
import com.peerdone.app.core.transport.TransportStrategy
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.domain.LocalIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class OutboundQueuedMessage(
    val queueId: String = UUID.randomUUID().toString(),
    val sender: LocalIdentity,
    val content: OutboundContent,
    val policy: AccessPolicy,
    val deliveryClass: DeliveryClass,
    val targetPeerId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

data class DispatchOutcome(
    val sent: Boolean,
    val reason: String,
    val transport: TransportType? = null,
)

/**
 * Независимый слой оркестрации:
 * - хранит pending очередь;
 * - выбирает транспорт по strategy;
 * - пытается отправить, иначе держит в очереди.
 */
class SendOrchestrator(
    private val adapters: Map<TransportType, TransportAdapter>,
    private val transportRegistry: TransportRegistry,
    private val preferredTransport: () -> TransportType? = { null },
) {
    private val maxBulkPerFlush = 8
    private val pending = mutableListOf<OutboundQueuedMessage>()
    private val _pendingMessages = MutableStateFlow<List<OutboundQueuedMessage>>(emptyList())
    val pendingMessages: StateFlow<List<OutboundQueuedMessage>> = _pendingMessages.asStateFlow()

    @Synchronized
    fun enqueueAndTrySend(
        sender: LocalIdentity,
        text: String,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
        targetPeerId: String? = null,
    ): DispatchOutcome {
        return enqueueAndTrySend(
            sender = sender,
            content = OutboundContent.Text(text),
            policy = policy,
            deliveryClass = deliveryClass,
            targetPeerId = targetPeerId,
        )
    }

    @Synchronized
    fun enqueueFileTransfer(
        sender: LocalIdentity,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        policy: AccessPolicy,
        targetPeerId: String? = null,
    ): List<DispatchOutcome> {
        val planned = FileTransferPlanner.plan(
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes,
        )
        val outcomes = mutableListOf<DispatchOutcome>()
        outcomes += enqueueAndTrySend(
            sender = sender,
            content = planned.meta,
            policy = policy,
            deliveryClass = DeliveryClass.BULK,
            targetPeerId = targetPeerId,
        )
        planned.chunks.forEach { chunk ->
            enqueueOnly(
                sender = sender,
                content = chunk,
                policy = policy,
                deliveryClass = DeliveryClass.BULK,
                targetPeerId = targetPeerId,
            )
        }
        outcomes += flushAll()
        return outcomes
    }

    @Synchronized
    private fun enqueueOnly(
        sender: LocalIdentity,
        content: OutboundContent,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
        targetPeerId: String? = null,
    ) {
        val msg = OutboundQueuedMessage(
            sender = sender,
            content = content,
            policy = policy,
            deliveryClass = deliveryClass,
            targetPeerId = targetPeerId,
        )
        pending.add(msg)
        syncPendingFlow()
    }

    @Synchronized
    fun enqueueAndTrySend(
        sender: LocalIdentity,
        content: OutboundContent,
        policy: AccessPolicy,
        deliveryClass: DeliveryClass,
        targetPeerId: String? = null,
    ): DispatchOutcome {
        val msg = OutboundQueuedMessage(
            sender = sender,
            content = content,
            policy = policy,
            deliveryClass = deliveryClass,
            targetPeerId = targetPeerId,
        )
        pending.add(msg)
        syncPendingFlow()
        return flushOne(msg.queueId)
    }

    @Synchronized
    fun flushAll(): List<DispatchOutcome> {
        val sorted = pending
            .sortedWith(compareBy({ classPriority(it.deliveryClass) }, { it.createdAtMs }))
        val ids = sorted.map { it.queueId }
        return ids.map { flushOne(it) }
    }

    @Synchronized
    private fun flushOne(queueId: String): DispatchOutcome {
        val idx = pending.indexOfFirst { it.queueId == queueId }
        if (idx < 0) {
            return DispatchOutcome(sent = false, reason = "Сообщение не найдено в очереди")
        }
        val msg = pending.elementAt(idx)

        val decision = TransportStrategy.chooseBest(
            deliveryClass = msg.deliveryClass,
            healthList = transportRegistry.snapshot(),
            preferred = preferredTransport(),
        )
        val selected = decision.selected
        if (selected == null) {
            return DispatchOutcome(
                sent = false,
                reason = "Нет доступного транспорта; сообщение оставлено в очереди",
            )
        }

        val adapter = adapters[selected]
            ?: return DispatchOutcome(
                sent = false,
                reason = "Для выбранного транспорта нет адаптера: $selected",
                transport = selected,
            )
        val sent = adapter.send(
            sender = msg.sender,
            content = msg.content,
            policy = msg.policy,
            deliveryClass = msg.deliveryClass,
            targetPeerId = msg.targetPeerId,
        )

        return if (sent) {
            pending.removeIf { it.queueId == queueId }
            syncPendingFlow()
            DispatchOutcome(
                sent = true,
                reason = "Отправлено через $selected",
                transport = selected,
            )
        } else {
            DispatchOutcome(
                sent = false,
                reason = "Транспорт выбран ($selected), но отправка не удалась; остаётся в очереди",
                transport = selected,
            )
        }
    }

    private fun classPriority(dc: DeliveryClass): Int = when (dc) {
        DeliveryClass.REALTIME -> 0
        DeliveryClass.INTERACTIVE -> 1
        DeliveryClass.BULK -> 2
    }

    @Synchronized
    private fun syncPendingFlow() {
        _pendingMessages.value = pending.toList()
    }
}

