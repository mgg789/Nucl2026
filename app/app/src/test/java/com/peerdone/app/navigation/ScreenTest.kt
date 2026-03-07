package com.peerdone.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {

    @Test
    fun Screen_routes_are_correct() {
        assertEquals("onboarding", Screen.Onboarding.route)
        assertEquals("main", Screen.Main.route)
        assertEquals("chat_list", Screen.ChatList.route)
        assertEquals("chat/{peerId}", Screen.Chat.route)
        assertEquals("calls", Screen.Calls.route)
        assertEquals("active_call/{peerId}", Screen.ActiveCall.route)
        assertEquals("network", Screen.Network.route)
        assertEquals("settings", Screen.Settings.route)
        assertEquals("logs", Screen.Logs.route)
        assertEquals("data", Screen.Data.route)
    }

    @Test
    fun Chat_createRoute_builds_route_with_peerId() {
        assertEquals("chat/alice", Screen.Chat.createRoute("alice"))
        assertEquals("chat/peer-123", Screen.Chat.createRoute("peer-123"))
    }

    @Test
    fun ActiveCall_createRoute_builds_route_with_peerId() {
        assertEquals("active_call/bob", Screen.ActiveCall.createRoute("bob"))
    }

    @Test
    fun BottomNavItem_values() {
        assertEquals("chat_list", BottomNavItem.CHATS.route)
        assertEquals("Чаты", BottomNavItem.CHATS.label)
        assertEquals("call", BottomNavItem.CALLS.icon)
        assertEquals(4, BottomNavItem.entries.size)
    }
}
