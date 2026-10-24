package com.nexaai.assistant.domain

import com.nexaai.assistant.data.CommandId
import com.nexaai.assistant.data.Configuration
import com.nexaai.assistant.data.normalizeAlias
import java.math.BigDecimal

sealed interface AssistantCommand {
    val id: CommandId
    data class SetVolume(val percent: Int) : AssistantCommand { override val id = CommandId.SET_VOLUME }
    data class LaunchApp(val appId: String) : AssistantCommand { override val id = CommandId.LAUNCH_APP }
    data class TuneFm(val frequencyMhz: BigDecimal) : AssistantCommand { override val id = CommandId.TUNE_FM }
    data class Action(override val id: CommandId) : AssistantCommand
}

sealed interface CommandResult {
    val message: String
    data class Success(override val message: String) : CommandResult
    data class Unsupported(override val message: String) : CommandResult
    data class PermissionRequired(val permission: String, override val message: String) : CommandResult
    data class InvalidParameters(override val message: String) : CommandResult
    data class Failed(override val message: String, val diagnosticCode: String? = null) : CommandResult
    data object Cancelled : CommandResult { override val message = "Cancelled before execution" }
}

data class AllowedApp(val packageName: String, val aliases: List<String>)

sealed interface ParseResult {
    data class Resolved(val command: AssistantCommand) : ParseResult
    data class Rejected(val result: CommandResult) : ParseResult
}

class RuleBasedCommandParser {
    fun parse(transcript: String, configuration: Configuration, apps: List<AllowedApp>): ParseResult {
        val text = normalizeAlias(transcript)
        if (text.length !in 1..300) return invalid("Command must be 1-300 characters")
        val matches = configuration.commands.flatMap { definition ->
            definition.aliases.mapNotNull { alias ->
                val parts = Regex("\\{[^}]+}").find(alias)
                val pattern = if (parts == null) Regex(Regex.escape(alias)) else Regex(
                    Regex.escape(alias.substring(0, parts.range.first)) + "(.+)" +
                        Regex.escape(alias.substring(parts.range.last + 1)))
                pattern.matchEntire(text)?.let { definition to it.groupValues.getOrNull(1) }
            }
        }.distinct()
        if (matches.isEmpty()) return invalid("Unknown command; use help for configured aliases")
        if (matches.size != 1) return invalid("Ambiguous command; use a more specific alias")
        val (definition, parameter) = matches.single()
        if (!definition.enabled) return invalid("Command is disabled")
        val command = when (definition.id) {
            CommandId.SET_VOLUME -> {
                val percent = parameter?.takeIf { it.matches(Regex("-?[0-9]{1,9}")) }?.toIntOrNull()
                    ?: return invalid("Volume must be an integer from 0 to 100")
                if (percent !in 0..100) return invalid("Volume must be from 0 to 100")
                AssistantCommand.SetVolume(percent)
            }
            CommandId.TUNE_FM -> {
                val frequency = parameter?.takeIf { it.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3})?")) }?.toBigDecimalOrNull()
                    ?: return invalid("FM frequency must be a positive decimal in MHz")
                if (frequency.signum() <= 0) return invalid("FM frequency must be positive")
                AssistantCommand.TuneFm(frequency)
            }
            CommandId.LAUNCH_APP -> {
                val found = apps.filter { app -> app.aliases.any { normalizeAlias(it) == parameter } }
                if (found.size != 1) return invalid("Unknown or ambiguous allowlisted app")
                AssistantCommand.LaunchApp(found.single().packageName)
            }
            else -> AssistantCommand.Action(definition.id)
        }
        return ParseResult.Resolved(command)
    }

    private fun invalid(message: String) = ParseResult.Rejected(CommandResult.InvalidParameters(message))
}