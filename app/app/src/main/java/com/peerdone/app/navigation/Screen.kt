package com.peerdone.app.navigation

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object ProfileSetup : Screen("profile_setup")
    data object PeerDiscovery : Screen("peer_discovery")
    data object Main : Screen("main")
    data object ChatList : Screen("chat_list")
    data object Chat : Screen("chat/{peerId}") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }
    data object Calls : Screen("calls")
    data object ActiveCall : Screen("active_call/{peerId}") {
        fun createRoute(peerId: String) = "active_call/$peerId"
    }
    data object Network : Screen("network")
    data object Settings : Screen("settings")
    data object Profile : Screen("profile")
    data object Data : Screen("data")
}

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: String
) {
    CHATS(Screen.ChatList.route, "Чаты", "chat"),
    CALLS(Screen.Calls.route, "Звонки", "call"),
    NETWORK(Screen.Network.route, "Сеть", "network"),
    SETTINGS(Screen.Settings.route, "Настройки", "settings")
}
