package com.nexaai.assistant

import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    private val parser = RuleBasedCommandParser()
    private fun parse(text: String, config: Configuration = Configuration(), apps: List<AllowedApp> = emptyList()) =
        parser.parse(text, config, apps)

    @Test fun placeholderPatternEscapesBracesForAndroidIcu() {
        assertEquals("\\{[^{}]+\\}", commandPlaceholderPattern.pattern)
        assertEquals(listOf("{percent}", "{app}", "{frequency}"),
            commandPlaceholderPattern.findAll("{percent} {app} {frequency}").map { it.value }.toList())
        assertFalse(commandPlaceholderPattern.matches("{}"))
        assertFalse(commandPlaceholderPattern.matches("{{app}}"))
        ConfigurationValidator.validate(Configuration())
        assertEquals(ParseResult.Resolved(AssistantCommand.Action(CommandId.HELP)), parse("help"))
    }

    @Test fun exactAliasesAndNormalization() {
        listOf("volume up", "increase volume", " LOUDER  ").forEach {
            assertEquals(ParseResult.Resolved(AssistantCommand.Action(CommandId.VOLUME_UP)), parse(it))
        }
        assertEquals(ParseResult.Resolved(AssistantCommand.SetVolume(30)), parse("Set  volume to 30 percent"))
        listOf("volume", "please volume up", "volume up now", "set volume to 30", "").forEach {
            assertTrue(parse(it) is ParseResult.Rejected)
        }
    }

    @Test fun rejectsInvalidParametersAndUnknownApps() {
        listOf("-1", "101", "30.5", "lots", "9999999999999999999").forEach {
            assertTrue(parse("set volume to $it percent") is ParseResult.Rejected)
        }
        listOf("-1", "NaN", "0", "98.1 extra").forEach {
            assertTrue(parse("tune fm to $it") is ParseResult.Rejected)
        }
        assertTrue(parse("open com.android.settings") is ParseResult.Rejected)
        assertEquals(ParseResult.Resolved(AssistantCommand.LaunchApp("trusted.package")),
            parse("open maps", apps = listOf(AllowedApp("trusted.package", listOf("maps")))))
    }

    @Test fun liveAliasesDisabledAndAmbiguousCommands() {
        val changed = Configuration().copy(commands = Configuration().commands.map {
            if (it.id == CommandId.VOLUME_UP) it.copy(aliases = listOf("turn it up")) else it
        })
        assertTrue(parse("louder", changed) is ParseResult.Rejected)
        assertTrue(parse("turn it up", changed) is ParseResult.Resolved)
        val disabled = changed.copy(commands = changed.commands.map { it.copy(enabled = false) })
        assertTrue(parse("turn it up", disabled) is ParseResult.Rejected)
        val collision = changed.copy(commands = changed.commands.map {
            if (it.id == CommandId.VOLUME_DOWN) it.copy(aliases = listOf("turn it up")) else it
        })
        assertTrue(parse("turn it up", collision) is ParseResult.Rejected)
    }

    @Test fun differentlySizedWildcardTemplatesCannotCollideAtSaveTime() {
        val collision = Configuration(commands = Configuration().commands.map {
            when (it.id) {
                CommandId.LAUNCH_APP -> it.copy(aliases = listOf("please open {app}"))
                CommandId.TUNE_FM -> it.copy(aliases = listOf("{frequency} now"))
                else -> it
            }
        })
        assertThrows(IllegalArgumentException::class.java) { ConfigurationValidator.validate(collision) }
        assertTrue(parse("please open maps now", collision) is ParseResult.Rejected)
    }
}