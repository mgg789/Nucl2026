package com.peerdone.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "peerdone_prefs")

class PreferencesStore(private val context: Context) {

    companion object {
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val USER_FIRST_NAME = stringPreferencesKey("user_first_name")
        private val USER_LAST_NAME = stringPreferencesKey("user_last_name")
        private val USER_NICKNAME = stringPreferencesKey("user_nickname")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[ONBOARDING_COMPLETED] ?: false }

    fun isOnboardingCompletedSync(): Boolean = runBlocking {
        context.dataStore.data.first()[ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun saveUserProfile(
        firstName: String,
        lastName: String,
        nickname: String
    ) {
        context.dataStore.edit { preferences ->
            preferences[USER_FIRST_NAME] = firstName
            preferences[USER_LAST_NAME] = lastName
            preferences[USER_NICKNAME] = nickname
            preferences[ONBOARDING_COMPLETED] = true
        }
    }

    val userFirstName: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[USER_FIRST_NAME] ?: "" }

    val userLastName: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[USER_LAST_NAME] ?: "" }

    val userNickname: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[USER_NICKNAME] ?: "" }
}
