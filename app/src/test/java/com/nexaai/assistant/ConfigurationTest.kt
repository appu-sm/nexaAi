package com.nexaai.assistant

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nexaai.assistant.data.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigurationTest {
    @Test fun defaultsAreSafeAndRoundTrip() {
        val original = Configuration()
        assertEquals(original, ConfigurationCodec.decode(ConfigurationCodec.encode(original)))
        assertFalse(original.settings.llmEnabled)
        assertFalse(original.settings.wakeWordEnabled)
        assertTrue(original.settings.offlineOnly)
        assertEquals(19, original.commands.size)
    }

    @Test fun offlineSpeechResponsePreferenceCanBePersisted() {
        val enabled = Configuration(settings = AssistantSettings(ttsEnabled = true))
        assertEquals(enabled, ConfigurationCodec.decode(ConfigurationCodec.encode(enabled)))
    }

    @Test fun invalidVersionsFieldsAndFeatureFlagsAreRejected() {
        val json = ConfigurationCodec.encode(Configuration())
        listOf(
            JSONObject(json).put("schemaVersion", 99).toString(),
            JSONObject(json).put("arbitraryIntent", "anything").toString(),
            JSONObject(json).apply { getJSONObject("settings").put("overlayEnabled", true) }.toString(),
            JSONObject(json).apply { getJSONObject("settings").put("assistantEnabled", "true") }.toString(),
            "not json", "$json trailing", "{unquoted: true}", "[]",
            "x".repeat(ConfigurationCodec.MAX_BYTES + 1),
        ).forEach { invalid -> assertThrows(ConfigurationException::class.java) { ConfigurationCodec.decode(invalid) } }
    }

    @Test fun aliasesRejectCollisionsAndInvalidPlaceholders() {
        val config = Configuration()
        val badAliases = listOf(listOf("volume up"), listOf(""), listOf("Open app"), listOf("do {intent}"), listOf("tune fm to 100"))
        badAliases.forEach { aliases ->
            val changed = config.copy(commands = config.commands.map {
                if (it.id == CommandId.PAUSE) it.copy(aliases = aliases) else it
            })
            assertThrows(IllegalArgumentException::class.java) { ConfigurationValidator.validate(changed) }
        }
    }

    @Test fun legacyNameMigratesSafely() = runBlocking {
        val migration = LegacySettingsMigration()
        val legacy = mutablePreferencesOf(legacyNameKey to "  Cabin  ")
        assertTrue(migration.shouldMigrate(legacy))
        val migrated = migration.migrate(legacy)
        assertEquals("Cabin", ConfigurationCodec.decode(migrated[configurationKey]!!).settings.displayName)
        assertNull(migrated[legacyNameKey])
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test fun oversizeUnicodeConfigurationCannotBeSavedOrExported() {
        val config = Configuration(commands = CommandCatalogue.defaults().map { command ->
            command.copy(aliases = (0 until 12).map { index ->
                val prefix = "${command.id.ordinal} $index "
                val suffix = CommandCatalogue.spec(command.id).placeholder?.let { " $it" } ?: ""
                prefix + "\u754c".repeat(100 - prefix.length - suffix.length) + suffix
            })
        })
        assertThrows(IllegalArgumentException::class.java) { ConfigurationCodec.encode(config) }
    }

    @Test fun persistenceSurvivesRepositoryRecreationAndRejectsInvalidEdits() = runBlocking {
        val file = File.createTempFile("nexa-settings", ".preferences_pb").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val repository = ConfigurationRepository(store)
            repository.update { it.copy(settings = it.settings.copy(displayName = "Cabin", assistantEnabled = false),
                commands = it.commands.map { command -> if (command.id == CommandId.VOLUME_UP) command.copy(enabled = false, aliases = listOf("raise volume")) else command }) }
            val restored = ConfigurationRepository(store).snapshots.first().configuration
            assertEquals("Cabin", restored.settings.displayName)
            assertFalse(restored.settings.assistantEnabled)
            assertFalse(restored.commands.first { it.id == CommandId.VOLUME_UP }.enabled)
            try {
                repository.update { it.copy(settings = it.settings.copy(displayName = "")) }
                fail("Invalid settings accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(restored, repository.snapshots.first().configuration)
            store.edit { it[configurationKey] = "corrupt" }
            assertNotNull(repository.snapshots.first().warning)
            repository.replace(Configuration())
            assertEquals(Configuration(), repository.snapshots.first().configuration)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}