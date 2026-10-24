package com.nexaai.assistant

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexaai.assistant.data.ConfigurationRepository
import com.nexaai.assistant.data.LegacySettingsMigration
import com.nexaai.assistant.data.AppAllowlistRepository
import com.nexaai.assistant.execution.*

class NexaAiApplication : Application() {
    private val store by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler {
                mutablePreferencesOf(stringPreferencesKey("recovery_notice") to
                    "Preferences were corrupt. Safe defaults restored; no device actions were enabled.")
            },
            migrations = listOf(LegacySettingsMigration()),
            produceFile = { filesDir.resolve("nexa_configuration.preferences_pb") },
        )
    }
    val repository by lazy { ConfigurationRepository(store) }
    val commandRuntime by lazy {
        CommandRuntime(repository, AppAllowlistRepository(store), AndroidAppDevice(this),
            VerifiedVolumeController(AndroidVolumeDevice(this)), SessionMediaController(AndroidMediaDevice(this)))
    }
}