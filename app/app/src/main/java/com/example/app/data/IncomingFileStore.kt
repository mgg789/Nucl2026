package com.example.app.data

import android.content.Context
import com.example.app.core.message.OutboundContent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class PersistedIncomingFile(
    val fileId: String,
    val meta: OutboundContent.FileMeta?,
    val chunkTotal: Int?,
    val chunks: Map<Int, ByteArray>,
)

@OptIn(ExperimentalEncodingApi::class)
class IncomingFileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("incoming_file_store", Context.MODE_PRIVATE)
    private val key = "files"

    fun loadAll(): List<PersistedIncomingFile> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val fileId = obj.getString("fileId")
                    val total = if (obj.has("chunkTotal")) obj.getInt("chunkTotal") else null
                    val metaObj = obj.optJSONObject("meta")
                    val meta = metaObj?.let {
                        OutboundContent.FileMeta(
                            fileId = it.getString("fileId"),
                            fileName = it.getString("fileName"),
                            totalBytes = it.getLong("totalBytes"),
                            mimeType = it.getString("mimeType"),
                            sha256 = it.getString("sha256"),
                        )
                    }
                    val chunksObj = obj.optJSONObject("chunks") ?: JSONObject()
                    val chunks = mutableMapOf<Int, ByteArray>()
                    chunksObj.keys().forEach { idx ->
                        val data = chunksObj.getString(idx)
                        chunks[idx.toInt()] = Base64.decode(data)
                    }
                    add(
                        PersistedIncomingFile(
                            fileId = fileId,
                            meta = meta,
                            chunkTotal = total,
                            chunks = chunks,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveAll(files: List<PersistedIncomingFile>) {
        val arr = JSONArray()
        files.forEach { f ->
            val obj = JSONObject()
                .put("fileId", f.fileId)
                .put("chunkTotal", f.chunkTotal)
            f.meta?.let { m ->
                obj.put(
                    "meta",
                    JSONObject()
                        .put("fileId", m.fileId)
                        .put("fileName", m.fileName)
                        .put("totalBytes", m.totalBytes)
                        .put("mimeType", m.mimeType)
                        .put("sha256", m.sha256),
                )
            }
            val chunksObj = JSONObject()
            f.chunks.forEach { (idx, bytes) ->
                chunksObj.put(idx.toString(), Base64.encode(bytes))
            }
            obj.put("chunks", chunksObj)
            arr.put(obj)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
