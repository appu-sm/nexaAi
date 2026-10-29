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
    val grammarEnabled: Boolean = false,
    val grammarCustomWords: List<String> = emptyList(),
    val overlaySizeDp: Int = 64,
    val overlayOpacityPercent: Int = 90,
    val overlayXPercent: Int = 100,
    val overlayYPercent: Int = 50,
    val commandAudioSource: String = "VOICE_RECOGNITION",
    val commandInputGainPercent: Int = 100,
)

enum class CommandId { SET_VOLUME, VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE, LAUNCH_APP,
    GO_HOME, PLAY, PAUSE, NEXT_TRACK, PREVIOUS_TRACK, OPEN_RADIO, TUNE_FM, SEEK_NEXT,
    SEEK_PREVIOUS, MUTE_RADIO, UNMUTE_RADIO, AUTO_MEMORY_SCAN, RADIO_INTRO_SCAN,
    START_LISTENING, STOP_LISTENING, HELP, REPEAT_RESPONSE, CANCEL }

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
        CommandSpec(CommandId.OPEN_RADIO, "Open radio", "Installed com.nwd.radio activity required", listOf("open radio", "launch radio", "start radio")),
        CommandSpec(CommandId.TUNE_FM, "Tune FM", "87.5-108.0 MHz; converted to the nearest 10 kHz", listOf("tune fm to {frequency}", "tune to fm {frequency}", "tune to {frequency} fm"), "{frequency}"),
        CommandSpec(CommandId.SEEK_NEXT, "Next station", "Verified search key 5", listOf("next station", "search next station", "find the next fm station", "tune to the next station")),
        CommandSpec(CommandId.SEEK_PREVIOUS, "Previous station", "Verified search key 6", listOf("previous station", "search previous station", "find the previous station")),
        CommandSpec(CommandId.MUTE_RADIO, "Mute radio", "Radio mute broadcast request", listOf("mute radio", "mute the radio", "silence the radio")),
        CommandSpec(CommandId.UNMUTE_RADIO, "Unmute radio", "Radio unmute broadcast request", listOf("unmute radio", "restore radio sound", "turn radio sound back on")),
        CommandSpec(CommandId.AUTO_MEMORY_SCAN, "Automatic memory scan", "Verified radio key 46", listOf("scan and save stations", "automatically store radio stations", "run automatic station memory", "find and save available stations")),
        CommandSpec(CommandId.RADIO_INTRO_SCAN, "Radio intro scan", "Verified radio key 61", listOf("start radio intro scan", "preview radio stations", "start station preview")),
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

internal val commandPlaceholderPattern = Regex("\\{[^{}]+\\}")

object ConfigurationValidator {
    fun validateGrammarWords(words: List<String>) {
        require(words.size <= 100 && words.distinct().size == words.size) { "Use at most 100 distinct custom grammar entries." }
        require(words.all { it == normalizeAlias(it) && it.length in 1..60 && it.matches(Regex("[a-z]+(?:[ '-][a-z]+)*")) }) {
            "Custom grammar entries must be 1-60 lowercase English letters, with single spaces, apostrophes or hyphens between letter groups."
        }
    }

    fun validateWakePhrase(phrase: String) {
        require(phrase.length in 1..80 && phrase.matches(Regex("[A-Za-z]+(?:[ '-][A-Za-z]+)*"))) {
            "Wake phrase must be 1-80 English letters, spaces, apostrophes or hyphens, with no outer or repeated separators."
        }
    }

    fun validate(config: Configuration) {
        require(config.schemaVersion == 1) { "Unsupported configuration schema" }
        with(config.settings) {
            require(displayName.trim() == displayName && displayName.length in 1..40 && displayName.none { it.isISOControl() }) { "Name must be 1-40 printable characters with no outer spaces" }
            validateWakePhrase(wakePhrase)
            validateGrammarWords(grammarCustomWords)
            require(commandAudioSource in setOf("VOICE_RECOGNITION", "MIC") && commandInputGainPercent in 100..300) {
                "Invalid command microphone source or gain (100-300 percent)"
            }
            require(overlaySizeDp in 56..96 && overlayOpacityPercent in 50..100 &&
                overlayXPercent in 0..100 && overlayYPercent in 0..100) { "Invalid overlay size, opacity or position" }
            require(offlineOnly && listeningMode in setOf("PUSH_TO_TALK", "WAKE_WORD") &&
                (wakeWordEnabled == (listeningMode == "WAKE_WORD")) &&
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
                val placeholders = commandPlaceholderPattern.findAll(alias).map { it.value }.toList()
                require(placeholders == listOfNotNull(spec.placeholder)) { "Keep the required placeholder: ${spec.placeholder ?: "none"}" }
                require(alias.count { it == '{' } == placeholders.size && alias.count { it == '}' } == placeholders.size) { "Malformed placeholder" }
                if (spec.placeholder != null) require(spec.placeholder in alias.split(" ")) { "Placeholder must be a separate word" }
                require(seen.none { aliasesConflict(it, alias) }) { "Alias collision: $alias" }
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

    internal fun aliasesConflict(first: String, second: String): Boolean {
        val aliases = setOf(first, second)
        val reservedRadioLaunch = aliases.any { it in setOf("open radio", "launch radio") } &&
            aliases.any { it in setOf("open {app}", "launch {app}") }
        val tuneWordOrders = aliases == setOf("tune to fm {frequency}", "tune to {frequency} fm")
        return !reservedRadioLaunch && !tuneWordOrders && overlaps(first, second)
    }
}