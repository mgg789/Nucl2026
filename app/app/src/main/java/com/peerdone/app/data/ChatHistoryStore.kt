package com.peerdone.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

/** Тип сообщения в истории чата (совместим с отображением). */
enum class StoredMessageType {
    TEXT,
    VOICE,
    FILE,
    VIDEO_NOTE,
}

data class StoredChatMessage(
    val id: String,
    val text: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val type: StoredMessageType = StoredMessageType.TEXT,
    val fileName: String? = null,
    val filePath: String? = null,
    val voiceFileId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("timestampMs", timestampMs)
        put("isOutgoing", isOutgoing)
        put("type", type.name)
        put("fileName", fileName ?: "")
        put("filePath", filePath ?: "")
        put("voiceFileId", voiceFileId ?: "")
    }

    companion object {
        fun fromJson(obj: JSONObject): StoredChatMessage = StoredChatMessage(
            id = obj.optString("id", ""),
            text = obj.optString("text", ""),
            timestampMs = obj.optLong("timestampMs", 0L),
            isOutgoing = obj.optBoolean("isOutgoing", false),
            type = runCatching { StoredMessageType.valueOf(obj.optString("type", "TEXT")) }.getOrElse { StoredMessageType.TEXT },
            fileName = obj.optString("fileName").takeIf { it.isNotEmpty() },
            filePath = obj.optString("filePath").takeIf { it.isNotEmpty() },
            voiceFileId = obj.optString("voiceFileId").takeIf { it.isNotEmpty() },
        )
    }
}

private const val MAX_MESSAGES_PER_PEER = 500
private const val PREFS_NAME = "chat_history_store"
private const val KEY_DATA = "chat_history_json"

/** Интерфейс хранилища истории чатов (реализуют ChatHistoryStore и MergedChatHistoryStore). */
interface ChatHistoryStoreInterface {
    val byPeer: StateFlow<Map<String, List<StoredChatMessage>>>
    fun getMessagesForPeerSync(peerId: String): List<StoredChatMessage>
    fun getMessagesForPeerFlow(peerId: String): Flow<List<StoredChatMessage>>
    fun addMessage(peerId: String, message: StoredChatMessage)
    fun addMessages(peerId: String, messages: List<StoredChatMessage>)
}

/**
 * Хранит историю чатов на устройстве. Сообщения сохраняются при получении и при отправке.
 */
class ChatHistoryStore(context: Context) : ChatHistoryStoreInterface {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _byPeer = MutableStateFlow(loadAll())
    override val byPeer: StateFlow<Map<String, List<StoredChatMessage>>> = _byPeer.asStateFlow()

    override fun getMessagesForPeerSync(peerId: String): List<StoredChatMessage> =
        _byPeer.value[peerId.substringBefore("|").trim()].orEmpty().sortedBy { it.timestampMs }

    override fun getMessagesForPeerFlow(peerId: String): Flow<List<StoredChatMessage>> {
        val norm = peerId.substringBefore("|").trim()
        return _byPeer.map { peers -> (peers[norm].orEmpty()).sortedBy { it.timestampMs } }
    }

    override fun addMessage(peerId: String, message: StoredChatMessage) {
        val norm = peerId.substringBefore("|").trim()
        val updated = _byPeer.value.toMutableMap()
        val list = (updated[norm].orEmpty()).filter { it.id != message.id } + message
        updated[norm] = list.sortedBy { it.timestampMs }.takeLast(MAX_MESSAGES_PER_PEER)
        _byPeer.value = updated
        persist(updated)
    }

    override fun addMessages(peerId: String, messages: List<StoredChatMessage>) {
        if (messages.isEmpty()) return
        val norm = peerId.substringBefore("|").trim()
        val existing = (_byPeer.value[norm].orEmpty()).associateBy { it.id }.toMutableMap()
        messages.forEach { existing[it.id] = it }
        val list = existing.values.sortedBy { it.timestampMs }.takeLast(MAX_MESSAGES_PER_PEER)
        val updated = _byPeer.value.toMutableMap()
        updated[norm] = list
        _byPeer.value = updated
        persist(updated)
    }

    fun clearPeer(peerId: String) {
        val norm = peerId.substringBefore("|").trim()
        val updated = _byPeer.value.toMutableMap()
        updated.remove(norm)
        _byPeer.value = updated
        persist(updated)
    }

    /** Очищает всю локальную историю чатов по всем собеседникам. */
    fun clearAll() {
        _byPeer.value = emptyMap()
        persist(emptyMap())
    }

    private fun loadAll(): Map<String, List<StoredChatMessage>> {
        val raw = prefs.getString(KEY_DATA, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val peers = root.optJSONObject("peers") ?: return@runCatching emptyMap()
            mutableMapOf<String, List<StoredChatMessage>>().apply {
                peers.keys().forEach { key ->
                    val arr = peers.optJSONArray(key) ?: return@forEach
                    put(key, (0 until arr.length()).map { i -> StoredChatMessage.fromJson(arr.getJSONObject(i)) })
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun persist(byPeer: Map<String, List<StoredChatMessage>>) {
        val root = JSONObject()
        val peers = JSONObject()
        byPeer.forEach { (peer, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            peers.put(peer, arr)
        }
        root.put("peers", peers)
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }
}

/**
 * Объединяет несколько ChatHistoryStore в один вид: чтение — слияние и дедуп по id, запись — во все хранилища.
 * Используется в режиме «все протоколы».
 */
class MergedChatHistoryStore(
    private val scope: CoroutineScope,
    private val stores: List<ChatHistoryStore>,
) : ChatHistoryStoreInterface {

    private val _byPeer = MutableStateFlow<Map<String, List<StoredChatMessage>>>(emptyMap())
    override val byPeer: StateFlow<Map<String, List<StoredChatMessage>>> = _byPeer.asStateFlow()

    init {
        if (stores.isNotEmpty()) {
            scope.launch {
                combine(stores.map { it.byPeer }) { maps ->
                    val allPeers = maps.flatMap { it.keys }.toSet()
                    allPeers.associateWith { peerId ->
                        maps.flatMap { it[peerId].orEmpty() }.distinctBy { it.id }.sortedBy { it.timestampMs }
                    }
                }.collect { _byPeer.value = it }
            }
        }
    }

    override fun getMessagesForPeerSync(peerId: String): List<StoredChatMessage> {
        val merged = stores.flatMap { it.getMessagesForPeerSync(peerId) }
        return merged.distinctBy { it.id }.sortedBy { it.timestampMs }
    }

    override fun getMessagesForPeerFlow(peerId: String): Flow<List<StoredChatMessage>> {
        return when (stores.size) {
            0 -> kotlinx.coroutines.flow.flowOf(emptyList())
            1 -> stores.single().getMessagesForPeerFlow(peerId)
            else -> combine(stores.map { it.getMessagesForPeerFlow(peerId) }) { arrays ->
                arrays.flatMap { it.toList() }.distinctBy { it.id }.sortedBy { it.timestampMs }
            }
        }
    }

    override fun addMessage(peerId: String, message: StoredChatMessage) {
        stores.forEach { it.addMessage(peerId, message) }
    }

    override fun addMessages(peerId: String, messages: List<StoredChatMessage>) {
        stores.forEach { it.addMessages(peerId, messages) }
    }
}
