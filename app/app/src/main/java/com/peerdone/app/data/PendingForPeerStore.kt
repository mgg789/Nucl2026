package com.peerdone.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Персистентная очередь сообщений для офлайн-пиров.
 * При восстановлении P2P соединения сообщения отправляются.
 */
data class PendingForPeerItem(
    val id: String,
    val peerId: String,
    val contentJson: String,
    val policyJson: String,
    val ttl: Int,
    val previewText: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

class PendingForPeerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pending_for_peer_store", Context.MODE_PRIVATE)
    private val key = "pending_items"

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<PendingForPeerItem>> = _items.asStateFlow()

    fun add(item: PendingForPeerItem) {
        val updated = (_items.value + item).sortedBy { it.createdAtMs }
        _items.value = updated
        persist(updated)
    }

    fun remove(id: String) {
        val updated = _items.value.filter { it.id != id }
        _items.value = updated
        persist(updated)
    }

    fun getForPeer(peerId: String): List<PendingForPeerItem> =
        _items.value.filter { it.peerId == peerId }

    fun removeAllForPeer(peerId: String) {
        val updated = _items.value.filter { it.peerId != peerId }
        _items.value = updated
        persist(updated)
    }

    private fun load(): List<PendingForPeerItem> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        PendingForPeerItem(
                            id = obj.getString("id"),
                            peerId = obj.getString("peerId"),
                            contentJson = obj.getString("contentJson"),
                            policyJson = obj.getString("policyJson"),
                            ttl = obj.optInt("ttl", 3),
                            previewText = obj.getString("previewText"),
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(items: List<PendingForPeerItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("peerId", item.peerId)
                    .put("contentJson", item.contentJson)
                    .put("policyJson", item.policyJson)
                    .put("ttl", item.ttl)
                    .put("previewText", item.previewText)
                    .put("createdAtMs", item.createdAtMs)
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
