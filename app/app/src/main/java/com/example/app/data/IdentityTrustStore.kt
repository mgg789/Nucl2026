package com.example.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class TrustedIdentityRecord(
    val userId: String,
    val orgId: String,
    val level: Int,
    val publicKeyFingerprint: String,
    val firstSeenAtMs: Long,
)

/**
 * TOFU (trust on first use) store:
 * - первое корректно подписанное сообщение фиксирует связку userId -> publicKey/org/level;
 * - последующие несовпадения отклоняются.
 *
 * Это не заменяет PKI/CA, но закрывает простую подмену identity в текущей версии продукта.
 */
@OptIn(ExperimentalEncodingApi::class)
class IdentityTrustStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mesh_identity_trust", Context.MODE_PRIVATE)
    private val key = "records"
    private val records = linkedMapOf<String, TrustedIdentityRecord>()

    init {
        load()
    }

    fun validateOrRemember(
        userId: String,
        orgId: String,
        level: Int,
        senderPublicKeyBase64: String,
    ): Boolean {
        val fp = fingerprint(senderPublicKeyBase64)
        val existing = records[userId]
        if (existing == null) {
            records[userId] = TrustedIdentityRecord(
                userId = userId,
                orgId = orgId,
                level = level,
                publicKeyFingerprint = fp,
                firstSeenAtMs = System.currentTimeMillis(),
            )
            persist()
            return true
        }

        return existing.publicKeyFingerprint == fp &&
            existing.orgId == orgId &&
            existing.level == level
    }

    fun dump(): List<TrustedIdentityRecord> = records.values.toList()

    private fun fingerprint(base64PublicKey: String): String {
        val bytes = Base64.decode(base64PublicKey)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encode(hash)
    }

    private fun load() {
        records.clear()
        val raw = prefs.getString(key, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rec = TrustedIdentityRecord(
                    userId = obj.getString("userId"),
                    orgId = obj.getString("orgId"),
                    level = obj.getInt("level"),
                    publicKeyFingerprint = obj.getString("publicKeyFingerprint"),
                    firstSeenAtMs = obj.getLong("firstSeenAtMs"),
                )
                records[rec.userId] = rec
            }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        records.values.forEach { rec ->
            arr.put(
                JSONObject()
                    .put("userId", rec.userId)
                    .put("orgId", rec.orgId)
                    .put("level", rec.level)
                    .put("publicKeyFingerprint", rec.publicKeyFingerprint)
                    .put("firstSeenAtMs", rec.firstSeenAtMs),
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}

