package com.peerdone.app.core.call

import com.peerdone.app.core.message.OutboundContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CallSignalMachineTest {

    @Test
    fun createOutgoingOffer_returns_session_and_signal_with_offer_phase() {
        val (session, signal) = CallSignalMachine.createOutgoingOffer("sdp-offer-v1")
        assertEquals(CallState.OUTGOING_OFFER_SENT, session.state)
        assertEquals(session.callId, signal.callId)
        assertEquals("offer", signal.phase)
        assertEquals("sdp-offer-v1", signal.sdpOrIce)
    }

    @Test
    fun createOutgoingOffer_callId_is_uuid_format() {
        val (session, _) = CallSignalMachine.createOutgoingOffer("sdp")
        assertEquals(36, session.callId.length)
    }

    @Test
    fun onIncomingOffer_returns_session_in_INCOMING_OFFER_RECEIVED() {
        val (session, signal) = CallSignalMachine.onIncomingOffer("call-123", "sdp-from-remote")
        assertEquals(CallState.INCOMING_OFFER_RECEIVED, session.state)
        assertEquals("call-123", session.callId)
        assertEquals("offer", signal.phase)
    }

    @Test
    fun createAnswer_updates_session_to_CONNECTING() {
        val session = CallSession("call-1", CallState.INCOMING_OFFER_RECEIVED)
        val (next, signal) = CallSignalMachine.createAnswer(session, "sdp-answer")
        assertEquals(CallState.CONNECTING, next.state)
        assertEquals("call-1", next.callId)
        assertEquals("answer", signal.phase)
        assertEquals("sdp-answer", signal.sdpOrIce)
    }

    @Test
    fun createIce_returns_ice_signal() {
        val session = CallSession("call-1", CallState.CONNECTING)
        val signal = CallSignalMachine.createIce(session, "candidate:1")
        assertEquals("call-1", signal.callId)
        assertEquals("ice", signal.phase)
        assertEquals("candidate:1", signal.sdpOrIce)
    }

    @Test
    fun markActive_sets_state_ACTIVE() {
        val session = CallSession("call-1", CallState.CONNECTING)
        val next = CallSignalMachine.markActive(session)
        assertEquals(CallState.ACTIVE, next.state)
        assertEquals("call-1", next.callId)
    }

    @Test
    fun end_returns_session_ENDED_and_end_signal() {
        val session = CallSession("call-1", CallState.ACTIVE)
        val (next, signal) = CallSignalMachine.end(session)
        assertEquals(CallState.ENDED, next.state)
        assertEquals("end", signal.phase)
        assertEquals("", signal.sdpOrIce)
    }

    @Test
    fun call_state_enum_values() {
        assertEquals(6, CallState.entries.size)
        assertEquals(CallState.IDLE, CallState.entries[0])
        assertEquals(CallState.ENDED, CallState.entries[5])
    }
}
