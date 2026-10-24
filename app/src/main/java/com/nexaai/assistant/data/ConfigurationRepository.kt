package com.nexaai.assistant.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val configurationKey = stringPreferencesKey("configuration_v1")
val legacyNameKey = stringPreferencesKey("display_name")

class LegacySettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[legacyNameKey] != null && currentData[configurationKey] == null
    override suspend fun migrate(currentData: Preferences): Preferences {
        val name = currentData[legacyNameKey]?.trim()?.takeIf { value -> value.length in 1..40 && value.none { it.isISOControl() } } ?: "NexaAI"
        return currentData.toMutablePreferences().apply {
            this[configurationKey] = ConfigurationCodec.encode(Configuration(settings = AssistantSettings(displayName = name)))
            remove(legacyNameKey)
        }
    }
    override suspend fun cleanUp() = Unit
}

data class ConfigurationSnapshot(
    val configuration: Configuration = Configuration(),
    val ready: Boolean = false,
    val warning: String? = null,
)

class ConfigurationRepository(private val store: DataStore<Preferences>) {
    val snapshots: Flow<ConfigurationSnapshot> = store.data.map { preferences ->
        val text = preferences[configurationKey]
        if (text == null) ConfigurationSnapshot(ready = true, warning = preferences[stringPreferencesKey("recovery_notice")])
        else try {
            ConfigurationSnapshot(ConfigurationCodec.decode(text), ready = true)
        } catch (error: ConfigurationException) {
            ConfigurationSnapshot(ready = true, warning = "Stored configuration is invalid or newer than this app. Safe defaults are active; reset or import to replace it.")
        }
    }.catch { error ->
        if (error is IOException) emit(ConfigurationSnapshot(warning = "Settings storage could not be read. Editing is disabled; reopen the app to retry."))
        else throw error
    }

    suspend fun update(transform: (Configuration) -> Configuration) {
        store.edit { preferences ->
            val current = preferences[configurationKey]?.let(ConfigurationCodec::decode) ?: Configuration()
            preferences[configurationKey] = ConfigurationCodec.encode(transform(current))
            preferences.remove(stringPreferencesKey("recovery_notice"))
        }
    }

    suspend fun replace(configuration: Configuration) {
        val encoded = ConfigurationCodec.encode(configuration)
        store.edit {
            it[configurationKey] = encoded
            it.remove(stringPreferencesKey("recovery_notice"))
        }
    }
}