package com.example.app.core.transport

object TransportStrategy {
    fun chooseBest(
        deliveryClass: DeliveryClass,
        healthList: List<TransportHealth>,
    ): TransportDecision {
        val available = healthList.filter { it.available }
        if (available.isEmpty()) {
            return TransportDecision(
                selected = null,
                ranked = healthList.sortedBy { it.type.name },
                reason = "Нет доступного транспорта",
            )
        }

        val ranked = available.sortedByDescending { score(it, deliveryClass) }
        val selected = ranked.first().type
        return TransportDecision(
            selected = selected,
            ranked = ranked,
            reason = "Выбран транспорт с максимальным score под $deliveryClass",
        )
    }

    private fun score(health: TransportHealth, deliveryClass: DeliveryClass): Int {
        return when (deliveryClass) {
            DeliveryClass.REALTIME ->
                // Realtime: latency/stability приоритетнее, battery вторична.
                (1000 - health.estimatedLatencyMs).coerceAtLeast(0) +
                    health.stabilityScore * 40 +
                    health.estimatedBandwidthKbps / 10 -
                    health.estimatedBatteryCost * 15

            DeliveryClass.INTERACTIVE ->
                // Text/chat: баланс latency/battery/stability.
                (800 - health.estimatedLatencyMs).coerceAtLeast(0) +
                    health.stabilityScore * 30 +
                    health.estimatedBandwidthKbps / 20 -
                    health.estimatedBatteryCost * 20

            DeliveryClass.BULK ->
                // Files/media: пропускная способность и батарея.
                health.estimatedBandwidthKbps / 4 +
                    health.stabilityScore * 20 -
                    health.estimatedBatteryCost * 25 -
                    health.estimatedLatencyMs / 4
        }
    }
}

