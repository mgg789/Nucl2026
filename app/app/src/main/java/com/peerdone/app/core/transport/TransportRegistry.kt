package com.peerdone.app.core.transport

class TransportRegistry(
    private val providers: MutableList<() -> TransportHealth> = mutableListOf(),
) {
    fun register(provider: () -> TransportHealth) {
        providers += provider
    }

    fun snapshot(): List<TransportHealth> = providers.map { it() }
}

