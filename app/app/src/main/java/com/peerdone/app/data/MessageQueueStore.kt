package com.peerdone.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class DeliveryState {
    QUEUED,
    SENT,
    ACKED,
    FAILED,
}

data class OutboundMessageRecord(
    val id: String,
    val preview: String,
    val state: DeliveryState,
    val retries: Int,
    val updatedAtMs: Long,
)

class MessageQueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mesh_queue_store", Context.MODE_PRIVATE)
    private val key = "outbound_records"

    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<OutboundMessageRecord>> = _records.asStateFlow()

    fun upsertQueued(id: String, preview: String) {
        mutate { list ->
            val now = System.currentTimeMillis()
            val filtered = list.filterNot { it.id == id }
            filtered + OutboundMessageRecord(
                id = id,
                preview = preview,
                state = DeliveryState.QUEUED,
                retries = 0,
                updatedAtMs = now,
            )
        }
    }

    fun markSent(id: String) = markState(id, DeliveryState.SENT)
    fun markAcked(id: String) = markState(id, DeliveryState.ACKED)
    fun markFailed(id: String) = markState(id, DeliveryState.FAILED)

    fun incrementRetries(id: String) {
        mutate { list ->
            list.map {
                if (it.id == id) it.copy(retries = it.retries + 1, updatedAtMs = System.currentTimeMillis()) else it
            }
        }
    }

    private fun markState(id: String, state: DeliveryState) {
        mutate { list ->
            list.map {
                if (it.id == id) it.copy(state = state, updatedAtMs = System.currentTimeMillis()) else it
            }
        }
    }

    private fun mutate(transform: (List<OutboundMessageRecord>) -> List<OutboundMessageRecord>) {
        val updated = transform(_records.value).sortedByDescending { it.updatedAtMs }.take(100)
        _records.value = updated
        persist(updated)
    }

    private fun load(): List<OutboundMessageRecord> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        OutboundMessageRecord(
                            id = obj.getString("id"),
                            preview = obj.getString("preview"),
                            state = DeliveryState.valueOf(obj.getString("state")),
                            retries = obj.optInt("retries", 0),
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(records: List<OutboundMessageRecord>) {
        val arr = JSONArray()
        records.forEach { rec ->
            arr.put(
                JSONObject()
                    .put("id", rec.id)
                    .put("preview", rec.preview)
                    .put("state", rec.state.name)
                    .put("retries", rec.retries)
                    .put("updatedAtMs", rec.updatedAtMs)
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}

