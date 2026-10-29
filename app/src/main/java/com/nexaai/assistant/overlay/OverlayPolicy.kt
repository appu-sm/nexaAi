package com.nexaai.assistant.overlay

import com.nexaai.assistant.data.AssistantSettings
import com.nexaai.assistant.data.ConfigurationSnapshot
import com.nexaai.assistant.speech.VoiceState

object StartupPolicy {
    fun shouldStart(snapshot: ConfigurationSnapshot, unlocked: Boolean, permission: Boolean): Boolean =
        unlocked && snapshot.ready && snapshot.warning == null && snapshot.configuration.settings.let {
            it.assistantEnabled && it.autoStartAfterBoot && it.overlayEnabled && permission
        }

    fun restore(snapshot: ConfigurationSnapshot, unlocked: Boolean, permission: Boolean, startOverlay: () -> Unit): Boolean {
        if (!shouldStart(snapshot, unlocked, permission)) return false
        startOverlay()
        return true
    }
}

data class OverlayPresentation(val text: String, val wakeActive: Boolean = false, val canStop: Boolean = false,
    val microphoneActive: Boolean = false, val notice: String? = null)

fun overlayPresentation(voice: VoiceState, requested: Boolean, handsFree: Boolean): OverlayPresentation = when {
    voice is VoiceState.Error -> OverlayPresentation("Unavailable", canStop = requested || handsFree, notice = voice.message)
    voice == VoiceState.Initializing -> OverlayPresentation("Initializing", canStop = true)
    handsFree && voice is VoiceState.WaitingForWakeWord -> OverlayPresentation("Wake active", true, true, true)
    voice is VoiceState.Listening -> OverlayPresentation("Listening", canStop = true, microphoneActive = true)
    voice == VoiceState.SpeakingResponse -> OverlayPresentation("Speaking", canStop = true)
    voice == VoiceState.Processing || voice is VoiceState.Recognized || voice is VoiceState.Executing ||
        voice == VoiceState.WakeWordDetected -> OverlayPresentation("Processing", canStop = true)
    requested || handsFree -> OverlayPresentation("Starting", canStop = true)
    else -> OverlayPresentation("Hands-free off")
}

interface OverlayWindow {
    fun add(settings: AssistantSettings, presentation: OverlayPresentation)
    fun update(settings: AssistantSettings, presentation: OverlayPresentation)
    fun remove()
}

class OverlayController(private val window: OverlayWindow) {
    var attached: Boolean = false
        private set

    fun reconcile(settings: AssistantSettings, permission: Boolean, presentation: OverlayPresentation) {
        if (!settings.assistantEnabled || !settings.overlayEnabled || !permission) {
            close()
            return
        }
        if (attached) window.update(settings, presentation)
        else {
            window.add(settings, presentation)
            attached = true
        }
    }

    fun close() {
        if (!attached) return
        try { window.remove() } finally { attached = false }
    }
}

class OverlayActions(private val openAssistant: () -> Unit, private val stopListening: () -> Unit,
    private val listenCommand: () -> Unit) {
    fun open() = openAssistant()
    fun stop() = stopListening()
    fun listen() = listenCommand()
}