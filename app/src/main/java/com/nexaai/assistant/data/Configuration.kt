package com.nexaai.assistant.data

import java.util.Locale

data class AssistantSettings(
    val assistantEnabled: Boolean = true,
    val displayName: String = "NexaAI",
    val offlineOnly: Boolean = true,
    val listeningMode: String = "PUSH_TO_TALK",
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = "Hey Nexa",
    val autoStartAfterBoot: Boolean = false,
    val overlayEnabled: Boolean = false,
    val ttsEnabled: Boolean = false,
    val confirmationMode: String = "SHORT",
    val llmEnabled: Boolean = false,
    val debugLoggingEnabled: Boolean = false,
)

enum class CommandId { SET_VOLUME, VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE, LAUNCH_APP,
    GO_HOME, PLAY, PAUSE, NEXT_TRACK, PREVIOUS_TRACK, TUNE_FM, SEEK_NEXT,
    SEEK_PREVIOUS, START_LISTENING, STOP_LISTENING, HELP, REPEAT_RESPONSE, CANCEL }

data class CommandDefinition(
    val id: CommandId,
    val enabled: Boolean = true,
    val aliases: List<String>,
)

data class CommandSpec(
    val id: CommandId,
    val title: String,
    val parameters: String,
    val aliases: List<String>,
    val placeholder: String? = null,
)

object CommandCatalogue {
    val specs = listOf(
        CommandSpec(CommandId.SET_VOLUME, "Set volume", "Integer percent: 0-100; volume capability required", listOf("set volume to {percent} percent"), "{percent}"),
        CommandSpec(CommandId.VOLUME_UP, "Volume up", "Supported audible stream required", listOf("volume up", "increase volume", "louder")),
        CommandSpec(CommandId.VOLUME_DOWN, "Volume down", "Supported audible stream required", listOf("volume down", "decrease volume", "quieter")),
        CommandSpec(CommandId.MUTE, "Mute", "Device mute support required", listOf("mute")),
        CommandSpec(CommandId.UNMUTE, "Unmute", "Device mute support required", listOf("unmute")),
        CommandSpec(CommandId.LAUNCH_APP, "Launch app", "Installed allowlisted friendly app name only", listOf("open {app}", "launch {app}"), "{app}"),
        CommandSpec(CommandId.GO_HOME, "Go home", "Resolvable home action required; not force-stop", listOf("go home")),
        CommandSpec(CommandId.PLAY, "Play", "Compatible media session/action required", listOf("play", "resume music")),
        CommandSpec(CommandId.PAUSE, "Pause", "Compatible media session/action required", listOf("pause", "pause music")),
        CommandSpec(CommandId.NEXT_TRACK, "Next track", "Compatible media session/action required", listOf("next track")),
        CommandSpec(CommandId.PREVIOUS_TRACK, "Previous track", "Compatible media session/action required", listOf("previous track")),
        CommandSpec(CommandId.TUNE_FM, "Tune FM", "Verified radio adapter and measured regional band/step required", listOf("tune fm to {frequency}"), "{frequency}"),
        CommandSpec(CommandId.SEEK_NEXT, "Next station", "Verified radio adapter required", listOf("next station")),
        CommandSpec(CommandId.SEEK_PREVIOUS, "Previous station", "Verified radio adapter required", listOf("previous station")),
        CommandSpec(CommandId.START_LISTENING, "Start listening", "Installed offline engine/model and microphone permission required", listOf("start listening")),
        CommandSpec(CommandId.STOP_LISTENING, "Stop listening", "Real capture lifecycle required", listOf("stop listening")),
        CommandSpec(CommandId.HELP, "Help", "Supported command list; voice response not installed", listOf("help", "list commands")),
        CommandSpec(CommandId.REPEAT_RESPONSE, "Repeat response", "Existing response and offline voice required", listOf("repeat", "repeat last response")),
        CommandSpec(CommandId.CANCEL, "Cancel", "Pending interpretation only", listOf("cancel")),
    )
    fun defaults() = specs.map { CommandDefinition(it.id, aliases = it.aliases) }
    fun spec(id: CommandId) = specs.first { it.id == id }
}

data class Configuration(
    val schemaVersion: Int = 1,
    val settings: AssistantSettings = AssistantSettings(),
    val commands: List<CommandDefinition> = CommandCatalogue.defaults(),
)

fun normalizeAlias(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

object ConfigurationValidator {
    fun validate(config: Configuration) {
        require(config.schemaVersion == 1) { "Unsupported configuration schema" }
        with(config.settings) {
            require(displayName.trim() == displayName && displayName.length in 1..40 && displayName.none { it.isISOControl() }) { "Name must be 1-40 printable characters with no outer spaces" }
            require(offlineOnly && listeningMode == "PUSH_TO_TALK" && !wakeWordEnabled &&
                wakePhrase == "Hey Nexa" && !autoStartAfterBoot && !overlayEnabled && !ttsEnabled &&
                confirmationMode == "SHORT" && !llmEnabled && !debugLoggingEnabled) {
                "Configuration enables an unavailable feature; only the app shell is installed"
            }
        }
        require(config.commands.size == CommandId.entries.size && config.commands.map { it.id }.toSet() == CommandId.entries.toSet()) { "Configuration must contain each known command exactly once" }
        val seen = mutableListOf<String>()
        config.commands.forEach { command ->
            require(command.aliases.size in 1..12) { "Each command needs 1-12 aliases" }
            command.aliases.forEach { alias ->
                require(alias == normalizeAlias(alias) && alias.length in 1..100) { "Aliases must be normalized and 1-100 characters" }
                require(alias.matches(Regex("[\\p{L}\\p{N} '{}-]+"))) { "Aliases contain unsupported characters" }
                val spec = CommandCatalogue.spec(command.id)
                val placeholders = Regex("\\{[^}]*}").findAll(alias).map { it.value }.toList()
                require(placeholders == listOfNotNull(spec.placeholder)) { "Keep the required placeholder: ${spec.placeholder ?: "none"}" }
                require(alias.count { it == '{' } == placeholders.size && alias.count { it == '}' } == placeholders.size) { "Malformed placeholder" }
                if (spec.placeholder != null) require(spec.placeholder in alias.split(" ")) { "Placeholder must be a separate word" }
                require(seen.none { overlaps(it, alias) }) { "Alias collision: $alias" }
                seen += alias
            }
        }
    }

    private fun overlaps(first: String, second: String): Boolean {
        val firstWords = first.split(" ")
        val secondWords = second.split(" ")
        fun transitions(words: List<String>, position: Int): List<Pair<String?, Int>> {
            if (position == words.size) return emptyList()
            return if (words[position].startsWith("{")) listOf(null to position, null to position + 1)
            else listOf(words[position] to position + 1)
        }
        val pending = ArrayDeque<Pair<Int, Int>>()
        val visited = mutableSetOf<Pair<Int, Int>>()
        pending.add(0 to 0)
        while (pending.isNotEmpty()) {
            val state = pending.removeFirst()
            if (!visited.add(state)) continue
            val (left, right) = state
            if (left == firstWords.size && right == secondWords.size) return true
            transitions(firstWords, left).forEach { (leftWord, nextLeft) ->
                transitions(secondWords, right).forEach { (rightWord, nextRight) ->
                    if (leftWord == null || rightWord == null || leftWord == rightWord)
                        pending.add(nextLeft to nextRight)
                }
            }
        }
        return false
    }
}