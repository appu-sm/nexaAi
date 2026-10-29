package com.nexaai.assistant

import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import com.nexaai.assistant.speech.VoskGrammar
import org.junit.Assert.*
import org.junit.Test

class VoskGrammarTest {
    private val enabled = Configuration(settings = AssistantSettings(grammarEnabled = true))

    @Test fun disabledGrammarPreservesUnrestrictedRecognition() {
        assertNull(VoskGrammar.build(Configuration(), emptyList()))
    }

    @Test fun enabledAliasesAndAllowlistedAppNamesAreGeneratedWithoutPlaceholders() {
        val config = enabled.copy(commands = enabled.commands.map {
            if (it.id == CommandId.PAUSE) it.copy(enabled = false) else it
        })
        val apps = listOf(AllowedApp("trusted.maps", listOf("maps", "navigation")), AllowedApp("trusted.clock", listOf("clock")))
        val grammar = VoskGrammar.build(config, apps)!!
        assertTrue("[unk]" in grammar.phrases)
        assertTrue("volume up" in grammar.phrases)
        assertTrue("open maps" in grammar.phrases)
        assertTrue("launch navigation" in grammar.phrases)
        assertTrue("open clock" in grammar.phrases)
        assertTrue(RuleBasedCommandParser().parse("open clock", config, apps) is ParseResult.Resolved)
        assertTrue(RuleBasedCommandParser().parse("clock", config, apps) is ParseResult.Rejected)
        assertFalse("pause" in grammar.phrases)
        assertTrue(grammar.phrases.none { '{' in it || '}' in it })
        assertEquals(grammar.phrases.size, grammar.phrases.distinct().size)
    }

    @Test fun spokenNumericParametersRoundTripThroughTheExistingParser() {
        val grammar = VoskGrammar.build(enabled, emptyList())!!
        val cases = mapOf(
            "set volume to fifty percent" to "set volume to 50 percent",
            "set volume to five zero percent" to "set volume to 50 percent",
            "set volume to one hundred percent" to "set volume to 100 percent",
            "set volume to zero percent" to "set volume to 0 percent",
            "tune fm to ninety eight point three" to "tune fm to 98.3",
            "tune fm to nine eight point three" to "tune fm to 98.3",
            "tune fm to eighty seven point five" to "tune fm to 87.5",
            "tune fm to one hundred eight" to "tune fm to 108",
            "tune fm to one hundred point zero one" to "tune fm to 100.01",
        )
        cases.forEach { (spoken, canonical) ->
            assertTrue(spoken in grammar.phrases)
            assertEquals(canonical, grammar.canonicalTranscript(spoken))
            assertTrue(RuleBasedCommandParser().parse(canonical, enabled, emptyList()) is ParseResult.Resolved)
        }
        assertFalse("set volume to one hundred one percent" in grammar.phrases)
        assertFalse("tune fm to eighty seven point four nine" in grammar.phrases)
    }

    @Test fun customVocabularyDoesNotCreateCommandsOrOverrideGeneratedNumericMappings() {
        val config = enabled.copy(settings = enabled.settings.copy(grammarCustomWords = listOf("headlights", "set volume to fifty percent")))
        val grammar = VoskGrammar.build(config, emptyList())!!
        assertTrue("headlights" in grammar.phrases)
        assertEquals("set volume to 50 percent", grammar.canonicalTranscript("set volume to fifty percent"))
        assertTrue(RuleBasedCommandParser().parse(grammar.canonicalTranscript("headlights"), config, emptyList()) is ParseResult.Rejected)
        assertEquals("[unk]", grammar.canonicalTranscript("volume [unk] up"))
        assertEquals("unrelated speech", grammar.canonicalTranscript("unrelated speech"))
    }

    @Test fun refreshedGrammarUsesAliasAndAppEditsWithoutMutatingTheCurrentSession() {
        val original = VoskGrammar.build(enabled, listOf(AllowedApp("trusted.maps", listOf("maps"))))!!
        val changed = enabled.copy(commands = enabled.commands.map {
            if (it.id == CommandId.VOLUME_UP) it.copy(aliases = listOf("raise sound")) else it
        })
        val refreshed = VoskGrammar.build(changed, emptyList())!!
        assertTrue("open maps" in original.phrases)
        assertFalse("open maps" in refreshed.phrases)
        assertFalse("volume up" in refreshed.phrases)
        assertTrue("raise sound" in refreshed.phrases)
    }

    @Test fun ambiguousSpokenFormsAreRejectedRatherThanSelectingACommand() {
        val config = enabled.copy(commands = enabled.commands.map {
            when (it.id) {
                CommandId.HELP -> it.copy(aliases = listOf("channel 1"))
                CommandId.PAUSE -> it.copy(aliases = listOf("channel one"))
                else -> it
            }
        })
        val grammar = VoskGrammar.build(config, emptyList())!!
        assertEquals("[unk]", grammar.canonicalTranscript("channel one"))
    }

    @Test fun oversizedGeneratedGrammarFailsInsteadOfSilentlyTruncatingCommands() {
        val prefixes = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet", "kilo", "lima")
        val config = enabled.copy(commands = enabled.commands.map {
            if (it.id == CommandId.TUNE_FM) it.copy(aliases = prefixes.map { prefix -> "$prefix ${"station ".repeat(10)}{frequency}" }) else it
        })
        val failure = assertThrows(IllegalArgumentException::class.java) { VoskGrammar.build(config, emptyList()) }
        assertTrue(failure.message!!.contains("too large"))
    }
}