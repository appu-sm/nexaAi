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
    private val settingKeys = setOf("assistantEnabled", "displayName", "offlineOnly", "listeningMode",
        "wakeWordEnabled", "wakePhrase", "autoStartAfterBoot", "overlayEnabled", "ttsEnabled",
        "confirmationMode", "llmEnabled", "debugLoggingEnabled")

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
            require(settings.keys().asSequence().toSet() == settingKeys) { "Unknown or missing settings fields" }
            fun boolean(key: String): Boolean {
                require(settings.get(key) is Boolean) { "$key must be boolean" }
                return settings.getBoolean(key)
            }
            fun string(key: String): String {
                require(settings.get(key) is String) { "$key must be text" }
                return settings.getString(key)
            }
            val commands = root.getJSONArray("commands")
            val config = Configuration(settings = AssistantSettings(
                boolean("assistantEnabled"), string("displayName"), boolean("offlineOnly"), string("listeningMode"),
                boolean("wakeWordEnabled"), string("wakePhrase"), boolean("autoStartAfterBoot"),
                boolean("overlayEnabled"), boolean("ttsEnabled"), string("confirmationMode"),
                boolean("llmEnabled"), boolean("debugLoggingEnabled"),
            ), commands = (0 until commands.length()).map { index ->
                val command = commands.getJSONObject(index)
                require(command.keys().asSequence().toSet() == setOf("id", "enabled", "aliases")) { "Unknown or missing command fields" }
                require(command.get("id") is String && command.get("enabled") is Boolean) { "Invalid command field type" }
                val aliases = command.getJSONArray("aliases")
                CommandDefinition(CommandId.valueOf(command.getString("id")), command.getBoolean("enabled"),
                    (0 until aliases.length()).map { aliasIndex ->
                        require(aliases.get(aliasIndex) is String) { "Alias must be text" }
                        aliases.getString(aliasIndex)
                    })
            })
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