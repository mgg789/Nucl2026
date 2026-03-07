package com.peerdone.app.core.transport

enum class TransportType {
    NEARBY,
    BLUETOOTH_LE,
    WIFI_DIRECT,
    LAN_P2P,
    INTERNET_RELAY,
}

enum class DeliveryClass {
    REALTIME,
    INTERACTIVE,
    BULK,
}

data class TransportHealth(
    val type: TransportType,
    val available: Boolean,
    val estimatedLatencyMs: Int,
    val estimatedBandwidthKbps: Int,
    val estimatedBatteryCost: Int, // 1..10
    val stabilityScore: Int, // 1..10
)

data class TransportDecision(
    val selected: TransportType?,
    val ranked: List<TransportHealth>,
    val reason: String,
)

