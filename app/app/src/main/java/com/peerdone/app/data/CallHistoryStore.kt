package com.peerdone.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.callHistoryDataStore by preferencesDataStore(name = "call_history")

enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED
}

data class CallHistoryItem(
    val id: String,
    val peerId: String,
    val type: CallType,
    val timestampMs: Long,
    val durationSeconds: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("peerId", peerId)
        put("type", type.name)
        put("timestampMs", timestampMs)
        put("durationSeconds", durationSeconds)
    }

    companion object {
        fun fromJson(json: JSONObject): CallHistoryItem = CallHistoryItem(
            id = json.optString("id", ""),
            peerId = json.optString("peerId", ""),
            type = try {
                CallType.valueOf(json.optString("type", "INCOMING"))
            } catch (_: Exception) {
                CallType.INCOMING
            },
            timestampMs = json.optLong("timestampMs", 0L),
            durationSeconds = json.optInt("durationSeconds", 0)
        )
    }
}

class CallHistoryStore(private val context: Context) {
    private val callHistoryKey = stringPreferencesKey("call_history_json")

    val history: Flow<List<CallHistoryItem>> = context.callHistoryDataStore.data.map { prefs ->
        val jsonString = prefs[callHistoryKey] ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            (0 until jsonArray.length()).map { i ->
                CallHistoryItem.fromJson(jsonArray.getJSONObject(i))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addCall(call: CallHistoryItem) {
        context.callHistoryDataStore.edit { prefs ->
            val current = try {
                val jsonString = prefs[callHistoryKey] ?: "[]"
                val jsonArray = JSONArray(jsonString)
                (0 until jsonArray.length()).map { i ->
                    CallHistoryItem.fromJson(jsonArray.getJSONObject(i))
                }
            } catch (_: Exception) {
                emptyList()
            }
            val updated = listOf(call) + current
            val jsonArray = JSONArray()
            updated.take(100).forEach { item ->
                jsonArray.put(item.toJson())
            }
            prefs[callHistoryKey] = jsonArray.toString()
        }
    }

    suspend fun clearHistory() {
        context.callHistoryDataStore.edit { prefs ->
            prefs[callHistoryKey] = "[]"
        }
    }
}
