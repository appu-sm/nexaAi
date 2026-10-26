package com.nexaai.assistant

import com.nexaai.assistant.speech.completedVoskTranscript
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoskResultTest {
    @Test fun completedUtteranceReturnsBeforeAnEmptyTrailingFinalResult() {
        val partials = mutableListOf<String>()
        val completed = completedVoskTranscript(true, """{"text":"volume up"}""", partials::add)
        assertEquals("volume up", completed)
        assertTrue(partials.isEmpty())
        assertNull(completedVoskTranscript(true, """{"text":""}""", partials::add))
    }

    @Test fun partialTranscriptIsDisplayOnlyAndSilenceDoesNotComplete() {
        val partials = mutableListOf<String>()
        assertNull(completedVoskTranscript(false, """{"partial":"volume up"}""", partials::add))
        assertEquals(listOf("volume up"), partials)
        assertNull(completedVoskTranscript(true, """{"text":" "}""", partials::add))
        assertEquals(listOf("volume up"), partials)
    }
}