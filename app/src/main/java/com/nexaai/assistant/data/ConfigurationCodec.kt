package com.nexaai.assistant.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ConfigurationException(message: String) : IllegalArgumentException(message)

object ConfigurationCodec {
    const val MAX_BYTES = 64 * 1024
    private val preRadioCommandIds = setOf(
        CommandId.SET_VOLUME, CommandId.VOLUME_UP, CommandId.VOLUME_DOWN, CommandId.MUTE, CommandId.UNMUTE,
        CommandId.LAUNCH_APP, CommandId.GO_HOME, CommandId.PLAY, CommandId.PAUSE, CommandId.NEXT_TRACK,
        CommandId.PREVIOUS_TRACK, CommandId.TUNE_FM, CommandId.SEEK_NEXT, CommandId.SEEK_PREVIOUS,
        CommandId.START_LISTENING, CommandId.STOP_LISTENING, CommandId.HELP, CommandId.REPEAT_RESPONSE,
        CommandId.CANCEL,
    )
    private val radioCommandIds = setOf(
        CommandId.OPEN_RADIO, CommandId.MUTE_RADIO, CommandId.UNMUTE_RADIO,
        CommandId.AUTO_MEMORY_SCAN, CommandId.RADIO_INTRO_SCAN,
    )
    private val settingKeys = setOf("assistantEnabled", "displayName", "offlineOnly", "listeningMode",
        "wakeWordEnabled", "wakePhrase", "autoStartAfterBoot", "overlayEnabled", "ttsEnabled",
        "confirmationMode", "llmEnabled", "debugLoggingEnabled", "grammarEnabled", "grammarCustomWords",
        "overlaySizeDp", "overlayOpacityPercent", "overlayXPercent", "overlayYPercent")
    private val optionalSettingKeys = setOf("grammarEnabled", "grammarCustomWords",
        "overlaySizeDp", "overlayOpacityPercent", "overlayXPercent", "overlayYPercent")

    fun encode(config: Configuration): String {
        ConfigurationValidator.validate(config)
        val settings = JSONObject().apply {
            with(config.settings) {
                put("assistantEnabled", assistantEnabled); put("displayName", displayName)
                put("offlineOnly", offlineOnly); put("listeningMode", listeningMode)
                put("wakeWordEnabled", wakeWordEnabled); put("wakePhrase", wakePhrase)
                put("autoStartAfterBoot", autoStartAfterBoot); put("overlayEnabled", overlayEnabled)
                put("ttsEnabled", ttsEnabled); put("confirmationMode", confirmationMode)
                put("llmEnabled", llmEnabled); put("debugLoggingEnabled", debugLoggingEnabled)
                put("grammarEnabled", grammarEnabled); put("grammarCustomWords", JSONArray(grammarCustomWords))
                put("overlaySizeDp", overlaySizeDp); put("overlayOpacityPercent", overlayOpacityPercent)
                put("overlayXPercent", overlayXPercent); put("overlayYPercent", overlayYPercent)
            }
        }
        val encoded = JSONObject().put("schemaVersion", config.schemaVersion).put("settings", settings)
            .put("commands", JSONArray().apply {
                config.commands.forEach { command ->
                    put(JSONObject().put("id", command.id.name).put("enabled", command.enabled)
                        .put("aliases", JSONArray(command.aliases)))
                }
            }).toString(2)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 64 KiB" }
        return encoded
    }

    fun decode(text: String): Configuration {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 64 KiB" }
            val root = JSONObject(Json.parseToJsonElement(text).jsonObject.toString())
            require(root.keys().asSequence().toSet() == setOf("schemaVersion", "settings", "commands")) { "Unknown or missing configuration fields" }
            require(root.get("schemaVersion") is Int && root.getInt("schemaVersion") == 1) { "Unknown schema version; import rejected" }
            val settings = root.getJSONObject("settings")
            val savedSettingKeys = settings.keys().asSequence().toSet()
            require(savedSettingKeys.containsAll(settingKeys - optionalSettingKeys) && settingKeys.containsAll(savedSettingKeys)) { "Unknown or missing settings fields" }
            fun boolean(key: String): Boolean {
                require(settings.get(key) is Boolean) { "$key must be boolean" }
                return settings.getBoolean(key)
            }
            fun string(key: String): String {
                require(settings.get(key) is String) { "$key must be text" }
                return settings.getString(key)
            }
            fun integer(key: String, default: Int): Int {
                if (!settings.has(key)) return default
                require(settings.get(key) is Int) { "$key must be an integer" }
                return settings.getInt(key)
            }
            val commands = root.getJSONArray("commands")
            val savedCommands = (0 until commands.length()).map { index ->
                val command = commands.getJSONObject(index)
                require(command.keys().asSequence().toSet() == setOf("id", "enabled", "aliases")) { "Unknown or missing command fields" }
                require(command.get("id") is String && command.get("enabled") is Boolean) { "Invalid command field type" }
                val aliases = command.getJSONArray("aliases")
                CommandDefinition(CommandId.valueOf(command.getString("id")), command.getBoolean("enabled"),
                    (0 until aliases.length()).map { aliasIndex ->
                        require(aliases.get(aliasIndex) is String) { "Alias must be text" }
                        aliases.getString(aliasIndex)
                    })
            }
            val savedIds = savedCommands.map { it.id }
            val migratedCommands = if (savedIds.size == preRadioCommandIds.size && savedIds.toSet() == preRadioCommandIds) {
                val completed = savedCommands.toMutableList()
                CommandCatalogue.defaults().filter { it.id in radioCommandIds }.forEach { radioCommand ->
                    val aliases = radioCommand.aliases.filter { candidate ->
                        completed.none { existing -> existing.aliases.any { ConfigurationValidator.aliasesConflict(it, candidate) } }
                    }
                    val safeRadioCommand = if (aliases.isNotEmpty()) radioCommand.copy(aliases = aliases) else {
                        val base = "nexa radio ${radioCommand.id.name.lowercase().replace('_', ' ')}"
                        var alias = base
                        var suffix = 2
                        while (completed.any { existing -> existing.aliases.any { ConfigurationValidator.aliasesConflict(it, alias) } }) {
                            alias = "$base $suffix"
                            suffix++
                        }
                        radioCommand.copy(aliases = listOf(alias))
                    }
                    completed += safeRadioCommand
                }
                completed
            } else savedCommands
            val config = Configuration(settings = AssistantSettings(
                boolean("assistantEnabled"), string("displayName"), boolean("offlineOnly"), string("listeningMode"),
                boolean("wakeWordEnabled"), string("wakePhrase"), boolean("autoStartAfterBoot"),
                boolean("overlayEnabled"), boolean("ttsEnabled"), string("confirmationMode"),
                boolean("llmEnabled"), boolean("debugLoggingEnabled"),
                if (settings.has("grammarEnabled")) boolean("grammarEnabled") else false,
                if (settings.has("grammarCustomWords")) {
                    val words = settings.getJSONArray("grammarCustomWords")
                    (0 until words.length()).map { index ->
                        require(words.get(index) is String) { "Custom grammar entry must be text" }
                        words.getString(index)
                    }
                } else emptyList(),
                overlaySizeDp = integer("overlaySizeDp", 64),
                overlayOpacityPercent = integer("overlayOpacityPercent", 90),
                overlayXPercent = integer("overlayXPercent", 100),
                overlayYPercent = integer("overlayYPercent", 50),
            ), commands = migratedCommands)
            ConfigurationValidator.validate(config)
            return config
        } catch (error: SerializationException) {
            throw ConfigurationException("Invalid configuration JSON")
        } catch (error: JSONException) {
            throw ConfigurationException("Invalid configuration JSON")
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: "Invalid configuration")
        }
    }
}