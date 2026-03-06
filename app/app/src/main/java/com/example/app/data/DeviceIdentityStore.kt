package com.example.app.data

import android.content.Context
import com.example.app.domain.LocalIdentity
import java.util.UUID

/**
 * Локальная device-identity для демо:
 * один раз создается, затем стабильно используется на устройстве.
 */
class DeviceIdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    fun getOrCreate(): LocalIdentity {
        val userId = prefs.getString(KEY_USER_ID, null)
        val orgId = prefs.getString(KEY_ORG_ID, null)
        val level = prefs.getInt(KEY_LEVEL, -1)
        if (!userId.isNullOrBlank() && !orgId.isNullOrBlank() && level >= 0) {
            // Migration for old demo profiles: keep stable userId, normalize access domain.
            if (orgId != DEFAULT_ORG_ID || level != DEFAULT_LEVEL) {
                prefs.edit()
                    .putString(KEY_ORG_ID, DEFAULT_ORG_ID)
                    .putInt(KEY_LEVEL, DEFAULT_LEVEL)
                    .apply()
            }
            return LocalIdentity(userId = userId, orgId = DEFAULT_ORG_ID, level = DEFAULT_LEVEL)
        }

        // Единая org по умолчанию для бесшовного демо между устройствами.
        val newIdentity = LocalIdentity(
            userId = "node_${UUID.randomUUID().toString().take(6)}",
            orgId = DEFAULT_ORG_ID,
            level = DEFAULT_LEVEL,
        )
        prefs.edit()
            .putString(KEY_USER_ID, newIdentity.userId)
            .putString(KEY_ORG_ID, newIdentity.orgId)
            .putInt(KEY_LEVEL, newIdentity.level)
            .apply()
        return newIdentity
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ORG_ID = "org_id"
        private const val KEY_LEVEL = "level"
        private const val DEFAULT_ORG_ID = "org_city"
        private const val DEFAULT_LEVEL = 2
    }
}
