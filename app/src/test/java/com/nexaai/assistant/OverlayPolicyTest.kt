package com.nexaai.assistant

import com.nexaai.assistant.data.*
import com.nexaai.assistant.overlay.*
import com.nexaai.assistant.speech.VoiceState
import org.junit.Assert.*
import org.junit.Test

class OverlayPolicyTest {
    @Test fun speechBubbleShowsRealTranscriptAndResultsAndClearsForNewCapture() {
        val heard = overlayPresentation(VoiceState.Listening("volume up"), false, false)
        assertEquals("You: volume up", heard.speech?.text())
        val completed = VoiceState.Completed("volume up", com.nexaai.assistant.domain.CommandResult.Success("Volume increased"))
        val result = overlayPresentation(completed, false, false)
        assertEquals("You: volume up\nNexaAI: Volume increased", result.speech?.text())
        assertEquals(result.speech, overlayPresentation(VoiceState.WaitingForWakeWord(completed), true, true).speech)
        assertEquals(result.speech, overlayPresentation(VoiceState.SpeakingResponse, true, true, result.speech).speech)
        assertNull(overlayPresentation(VoiceState.Listening(), true, true, result.speech).speech)
        assertNull(overlayPresentation(VoiceState.Initializing, false, false, result.speech).speech)
        assertEquals("NexaAI: Microphone denied", overlayPresentation(VoiceState.Error("Microphone denied"), false, false).speech?.text())
    }

    @Test fun startupRequiresEveryGateAndNeverChangesWakePreferences() {
        val settings = AssistantSettings(autoStartAfterBoot = true, overlayEnabled = true)
        val ready = ConfigurationSnapshot(Configuration(settings = settings), ready = true)
        assertTrue(StartupPolicy.shouldStart(ready, true, true))
        assertFalse(settings.wakeWordEnabled)
        for (blocked in listOf(ready.copy(ready = false), ready.copy(warning = "corrupt"),
            ready.copy(configuration = Configuration(settings = settings.copy(autoStartAfterBoot = false))),
            ready.copy(configuration = Configuration(settings = settings.copy(overlayEnabled = false))),
            ready.copy(configuration = Configuration(settings = settings.copy(assistantEnabled = false))))) {
            assertFalse(StartupPolicy.shouldStart(blocked, true, true))
        }
        assertFalse(StartupPolicy.shouldStart(ready, false, true))
        assertFalse(StartupPolicy.shouldStart(ready, true, false))
        assertFalse(StartupPolicy.shouldStart(ConfigurationSnapshot(ready = true), true, true))
    }

    @Test fun permissionDenialRevocationAndDisableAreIdempotentAndDoNotInvokeActions() {
        var adds = 0
        var updates = 0
        var removes = 0
        val controller = OverlayController(object : OverlayWindow {
            override fun add(settings: AssistantSettings, presentation: OverlayPresentation) { adds++ }
            override fun update(settings: AssistantSettings, presentation: OverlayPresentation) { updates++ }
            override fun remove() { removes++ }
        })
        val enabled = AssistantSettings(overlayEnabled = true)
        val idle = overlayPresentation(VoiceState.Idle(), false, false)
        repeat(2) { controller.reconcile(enabled, false, idle) }
        assertEquals(0, adds)
        repeat(2) { controller.reconcile(enabled, true, idle) }
        assertEquals(1, adds)
        assertEquals(1, updates)
        repeat(2) { controller.reconcile(enabled, false, idle) }
        assertEquals(1, removes)
        assertFalse(controller.attached)
        controller.reconcile(enabled, true, idle)
        repeat(2) { controller.reconcile(AssistantSettings(), true, idle) }
        controller.close()
        assertEquals(2, adds)
        assertEquals(2, removes)
    }

    @Test fun failedAddIsNotMarkedAttachedAndDoesNotRetryItself() {
        var attempts = 0
        val controller = OverlayController(object : OverlayWindow {
            override fun add(settings: AssistantSettings, presentation: OverlayPresentation) { attempts++; throw SecurityException() }
            override fun update(settings: AssistantSettings, presentation: OverlayPresentation) { fail("Not attached") }
            override fun remove() { fail("Not attached") }
        })
        assertThrows(SecurityException::class.java) {
            controller.reconcile(AssistantSettings(overlayEnabled = true), true, OverlayPresentation("Off"))
        }
        assertFalse(controller.attached)
        assertEquals(1, attempts)
        controller.close()
    }

    @Test fun statusUsesActualCoordinatorAndActionsOnlyCallInjectedInterfaces() {
        assertEquals("Hands-free off", overlayPresentation(VoiceState.Idle(), false, false).text)
        assertFalse(overlayPresentation(VoiceState.WaitingForWakeWord(), true, false).wakeActive)
        assertFalse(overlayPresentation(VoiceState.Initializing, true, true).wakeActive)
        assertTrue(overlayPresentation(VoiceState.WaitingForWakeWord(), true, true).wakeActive)
        assertTrue(overlayPresentation(VoiceState.Listening(), false, false).canStop)
        assertEquals("Unavailable", overlayPresentation(VoiceState.Error("failure"), false, false).text)
        var opens = 0
        var stops = 0
        var commands = 0
        val actions = OverlayActions({ opens++ }, { stops++ }, { commands++ })
        assertEquals(0, opens)
        assertEquals(0, stops)
        actions.open()
        actions.stop()
        assertEquals(1, opens)
        assertEquals(1, stops)
        actions.listen()
        assertEquals(1, commands)
        assertTrue(overlayPresentation(VoiceState.Listening(), false, false).microphoneActive)
        assertTrue(overlayPresentation(VoiceState.WaitingForWakeWord(), true, true).microphoneActive)
        assertFalse(overlayPresentation(VoiceState.Initializing, true, true).microphoneActive)
        assertFalse(overlayPresentation(VoiceState.SpeakingResponse, true, true).microphoneActive)
    }
}