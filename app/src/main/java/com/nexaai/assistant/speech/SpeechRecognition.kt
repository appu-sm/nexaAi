package com.nexaai.assistant.speech

import android.net.Uri
import com.nexaai.assistant.data.normalizeAlias
import com.nexaai.assistant.domain.CommandResult
import com.nexaai.assistant.execution.CommandDispatcher
import com.nexaai.assistant.execution.Preparation
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceState {
    data class Idle(val notice: String? = null) : VoiceState
    data object Initializing : VoiceState
    data class Listening(val partialTranscript: String = "") : VoiceState
    data object Processing : VoiceState
    data class Recognized(val transcript: String) : VoiceState
    data class Executing(val transcript: String) : VoiceState
    data class Completed(val transcript: String, val result: CommandResult) : VoiceState
    data class Error(val message: String) : VoiceState
}

sealed interface SpeechOutputState {
    data object Initializing : SpeechOutputState
    data object Ready : SpeechOutputState
    data class Unavailable(val message: String) : SpeechOutputState
}

interface SpeechRecognizerEngine {
    suspend fun initialize()
    suspend fun recognize(onPartialResult: (String) -> Unit): String
    fun stop()
    fun release()
}

interface SpeechModelInstaller {
    fun isInstalled(): Boolean
    suspend fun install(uri: Uri)
}

interface SpeechOutput : Closeable {
    val state: StateFlow<SpeechOutputState>
    fun speak(text: String)
    fun stop()
}

class SpeechSessionCoordinator(
    private val engine: SpeechRecognizerEngine,
    private val installer: SpeechModelInstaller,
    private val dispatcher: CommandDispatcher,
    private val output: SpeechOutput,
    private val scope: CoroutineScope,
    private val speechEnabled: suspend () -> Boolean,
) {
    private val mutableState = MutableStateFlow<VoiceState>(VoiceState.Idle())
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    val outputState: StateFlow<SpeechOutputState> = output.state
    private val active = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private var session: Job? = null
    private val installed = MutableStateFlow(installer.isInstalled())
    val modelInstalled: StateFlow<Boolean> = installed.asStateFlow()

    fun start(): Boolean {
        if (!active.compareAndSet(false, true)) return false
        finishing.set(false)
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                engine.initialize()
                currentCoroutineContext().ensureActive()
                mutableState.value = VoiceState.Listening()
                val finalText = engine.recognize { partial ->
                    if (active.get() && !finishing.get()) {
                        mutableState.value = VoiceState.Listening(partial.take(300))
                    }
                }
                currentCoroutineContext().ensureActive()
                mutableState.value = VoiceState.Processing
                val transcript = normalizeTranscript(finalText)
                if (transcript.isBlank()) {
                    mutableState.value = VoiceState.Error("No speech was recognized. Try again in a quiet moment.")
                    return@launch
                }
                mutableState.value = VoiceState.Recognized(transcript)
                currentCoroutineContext().ensureActive()
                when (val prepared = dispatcher.prepare(transcript)) {
                    is Preparation.Rejected -> complete(transcript, prepared.result)
                    is Preparation.Ready -> {
                        currentCoroutineContext().ensureActive()
                        mutableState.value = VoiceState.Executing(transcript)
                        complete(transcript, dispatcher.execute(prepared.pending))
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = VoiceState.Idle("Listening cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = VoiceState.Error(failure.message ?: "Offline speech recognition failed.")
            } finally {
                active.set(false)
                finishing.set(false)
            }
        }
        return true
    }

    fun finish() {
        if (!active.get() || !finishing.compareAndSet(false, true)) return
        mutableState.value = VoiceState.Processing
        engine.stop()
    }

    fun cancel() {
        if (!active.get()) return
        finishing.set(true)
        engine.stop()
        session?.cancel()
        mutableState.value = VoiceState.Idle("Listening cancelled.")
    }

    fun installModel(uri: Uri) {
        if (!active.compareAndSet(false, true)) return
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                engine.release()
                installer.install(uri)
                engine.initialize()
                installed.value = true
                mutableState.value = VoiceState.Idle("Offline speech model installed and loaded.")
            } catch (cancelled: CancellationException) {
                installed.value = installer.isInstalled()
                mutableState.value = VoiceState.Idle("Model installation cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                installed.value = installer.isInstalled()
                mutableState.value = VoiceState.Error(failure.message ?: "The selected model could not be installed.")
            } finally {
                active.set(false)
            }
        }
    }

    fun cancelForLifecycle() {
        cancel()
        output.stop()
    }

    fun microphonePermissionDenied(permanentlyDenied: Boolean) {
        mutableState.value = VoiceState.Error(if (permanentlyDenied) {
            "Microphone permission denied. Enable it in Android app settings to use push-to-talk."
        } else {
            "Microphone permission denied. Grant it when you want to use push-to-talk."
        })
    }

    fun close() {
        cancel()
        val runningSession = session
        CoroutineScope(Dispatchers.IO).launch {
            runningSession?.cancelAndJoin()
            engine.release()
        }
        output.stop()
        output.close()
    }

    private suspend fun complete(transcript: String, result: CommandResult) {
        mutableState.value = VoiceState.Completed(transcript, result)
        if (speechEnabled() && output.state.value == SpeechOutputState.Ready) {
            output.speak(spokenSummary(result))
        }
    }

    private fun spokenSummary(result: CommandResult): String = when (result) {
        is CommandResult.Success -> result.message.takeIf { it.length <= 100 && '\n' !in it } ?: "The result is shown on screen."
        is CommandResult.Unsupported -> "That operation is not supported on this device."
        is CommandResult.PermissionRequired -> "A permission is required for that operation."
        is CommandResult.InvalidParameters -> when {
            result.message.contains("unknown", ignoreCase = true) -> "I did not recognize a supported command."
            result.message.contains("ambiguous", ignoreCase = true) -> "That command is ambiguous. Please try again."
            else -> "The command parameters are invalid."
        }
        is CommandResult.Failed -> "The operation failed."
        CommandResult.Cancelled -> "Cancelled."
    }
}

fun normalizeTranscript(transcript: String): String = normalizeAlias(transcript)