package com.peerdone.app.core.call

import com.peerdone.app.core.message.OutboundContent
import java.util.UUID

enum class CallState {
    IDLE,
    OUTGOING_OFFER_SENT,
    INCOMING_OFFER_RECEIVED,
    CONNECTING,
    ACTIVE,
    ENDED,
}

data class CallSession(
    val callId: String,
    val state: CallState,
)

object CallSignalMachine {
    fun createOutgoingOffer(sdpOffer: String): Pair<CallSession, OutboundContent.CallSignal> {
        val callId = UUID.randomUUID().toString()
        val session = CallSession(callId = callId, state = CallState.OUTGOING_OFFER_SENT)
        val signal = OutboundContent.CallSignal(
            callId = callId,
            phase = "offer",
            sdpOrIce = sdpOffer,
        )
        return session to signal
    }

    fun onIncomingOffer(callId: String, sdpOffer: String): Pair<CallSession, OutboundContent.CallSignal> {
        val session = CallSession(callId = callId, state = CallState.INCOMING_OFFER_RECEIVED)
        val signal = OutboundContent.CallSignal(
            callId = callId,
            phase = "offer",
            sdpOrIce = sdpOffer,
        )
        return session to signal
    }

    fun createAnswer(session: CallSession, sdpAnswer: String): Pair<CallSession, OutboundContent.CallSignal> {
        val next = session.copy(state = CallState.CONNECTING)
        val signal = OutboundContent.CallSignal(
            callId = session.callId,
            phase = "answer",
            sdpOrIce = sdpAnswer,
        )
        return next to signal
    }

    fun createIce(session: CallSession, ice: String): OutboundContent.CallSignal {
        return OutboundContent.CallSignal(
            callId = session.callId,
            phase = "ice",
            sdpOrIce = ice,
        )
    }

    fun markActive(session: CallSession): CallSession = session.copy(state = CallState.ACTIVE)

    fun end(session: CallSession): Pair<CallSession, OutboundContent.CallSignal> {
        val next = session.copy(state = CallState.ENDED)
        val signal = OutboundContent.CallSignal(
            callId = session.callId,
            phase = "end",
            sdpOrIce = "",
        )
        return next to signal
    }
}

