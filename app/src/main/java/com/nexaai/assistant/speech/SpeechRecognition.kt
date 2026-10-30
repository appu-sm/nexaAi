package com.nexaai.assistant.speech

import android.net.Uri
import com.nexaai.assistant.diagnostics.VoiceDiagnostics
import com.nexaai.assistant.diagnostics.VoiceEvent
import com.nexaai.assistant.diagnostics.DiagnosticCode
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

sealed interface VoiceState {
    data class Idle(val notice: String? = null) : VoiceState
    data object Initializing : VoiceState
    data class WaitingForWakeWord(val previous: Completed? = null) : VoiceState
    data object WakeWordDetected : VoiceState
    data object SpeakingResponse : VoiceState
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
    val engineId: String get() = "vosk"
    val displayName: String get() = "Vosk - Indian English"
    val status: StateFlow<RecognitionStatus> get() = MutableStateFlow(RecognitionStatus.Unloaded)
    suspend fun initialize()
    suspend fun openSession(): RecognitionSession = throw UnsupportedOperationException("Audio replay is unavailable for this engine.")
    suspend fun recognizeResult(onReady: () -> Unit, onResult: (RecognitionResult) -> Unit): RecognitionResult {
        val started = System.nanoTime() / 1_000_000
        val transcript = recognize(onReady) { text ->
            onResult(RecognitionResult(engineId, text, false, started, System.nanoTime() / 1_000_000))
        }
        return RecognitionResult(engineId, transcript, true, started, System.nanoTime() / 1_000_000)
    }
    suspend fun recognize(onPartialResult: (String) -> Unit): String
    suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit): String {
        onReady()
        return recognize(onPartialResult)
    }
    fun stop()
    fun release()
}

interface RecognitionSession : Closeable {
    fun acceptAudioFrame(samples: ShortArray, count: Int): RecognitionResult
    fun finish(): RecognitionResult
    fun commandTranscript(result: RecognitionResult): String = result.transcript
}

sealed interface RecognitionStatus {
    data object Unloaded : RecognitionStatus
    data object Loading : RecognitionStatus
    data object Ready : RecognitionStatus
    data class Failed(val error: RecognitionError) : RecognitionStatus
}

enum class RecognitionErrorCode { UNKNOWN_ENGINE, MISSING_MODEL, INITIALIZATION, AUDIO, BUSY, TIMEOUT, EMPTY, MEMORY, NATIVE_INFERENCE }

data class RecognitionError(
    val engineId: String,
    val code: RecognitionErrorCode,
    val detail: String,
    val recoverable: Boolean = true,
)

class RecognitionException(val error: RecognitionError, cause: Throwable? = null) :
    java.io.IOException(error.detail, cause)

data class RecognitionResult(
    val engineId: String,
    val transcript: String,
    val isFinal: Boolean,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val confidence: Double? = null,
)

interface SpeechModelInstaller {
    fun isInstalled(): Boolean
    suspend fun install(uri: Uri)
}

interface SpeechOutput : Closeable {
    val state: StateFlow<SpeechOutputState>
    fun speak(text: String)
    suspend fun speakAndAwait(text: String) { speak(text) }
    suspend fun stopAndAwait() { stop() }
    fun stop()
}

interface WakeWordDetector {
    suspend fun initialize(phrase: String)
    suspend fun awaitDetection()
    fun stop()
    fun release()
}

class SpeechSessionCoordinator(
    private val engine: SpeechRecognizerEngine,
    private val installer: SpeechModelInstaller,
    private val dispatcher: CommandDispatcher,
    private val output: SpeechOutput,
    private val scope: CoroutineScope,
    private val detector: WakeWordDetector? = null,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val speechEnabled: suspend () -> Boolean,
) {
    private val mutableState = MutableStateFlow<VoiceState>(VoiceState.Idle())
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()
    val outputState: StateFlow<SpeechOutputState> = output.state
    val engineStatus: StateFlow<RecognitionStatus> = engine.status
    val activeEngineId: String? get() = (engine as? SelectedSpeechEngine)?.activeEngineId
    fun selectedModelInstalled(id: String): Boolean = (installer as? SelectedSpeechEngine)?.isInstalled(id) ?: installer.isInstalled()
    val isActive: Boolean get() = active.get()
    val evaluationReport = MutableStateFlow("")
    private val active = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private var session: Job? = null
    private var serviceOwned = false
    private var diagnosticStage = DiagnosticCode.ASR
    private var manualCommand: CompletableDeferred<Unit>? = null
    private val installed = MutableStateFlow(installer.isInstalled())
    val modelInstalled: StateFlow<Boolean> = installed.asStateFlow()
    private val handsFree = MutableStateFlow(false)
    val handsFreeEnabled: StateFlow<Boolean> = handsFree.asStateFlow()

    fun enableHandsFree(phrase: String): Boolean {
        val wake = detector ?: run {
            mutableState.value = VoiceState.Error("Wake-word engine unavailable. Push-to-talk remains available.")
            return false
        }
        if (!active.compareAndSet(false, true)) {
            diagnostics.record(VoiceEvent.DUPLICATE_REQUEST)
            return false
        }
        handsFree.value = true
        diagnosticStage = DiagnosticCode.WAKE
        diagnostics.record(VoiceEvent.SESSION_START, DiagnosticCode.WAKE)
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                output.stopAndAwait()
                wake.initialize(phrase)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val previous = mutableState.value as? VoiceState.Completed
                    val trigger = CompletableDeferred<Unit>()
                    manualCommand = trigger
                    mutableState.value = VoiceState.WaitingForWakeWord(previous)
                    diagnosticStage = DiagnosticCode.WAKE
                    try {
                        coroutineScope {
                            val detection = async { wake.awaitDetection() }
                            try {
                                select<Unit> {
                                    detection.onAwait { }
                                    trigger.onAwait { diagnostics.record(VoiceEvent.MANUAL_TRIGGER) }
                                }
                            } finally {
                                if (detection.isActive) { detection.cancel(); wake.stop() }
                                detection.cancelAndJoin()
                            }
                        }
                    } finally { manualCommand = null }
                    currentCoroutineContext().ensureActive()
                    mutableState.value = VoiceState.WakeWordDetected
                    diagnosticStage = DiagnosticCode.ASR
                    recognizeCommand()
                    delay(350)
                }
            } catch (timeout: TimeoutCancellationException) {
                diagnostics.record(VoiceEvent.TIMEOUT, DiagnosticCode.TTS)
                mutableState.value = VoiceState.Error("Speech playback timed out. Hands-free stopped; use push-to-talk or enable it again.")
            } catch (cancelled: CancellationException) {
                diagnostics.record(VoiceEvent.CANCELLED, diagnosticStage)
                mutableState.value = VoiceState.Idle("Hands-free listening stopped.")
                throw cancelled
            } catch (failure: Exception) {
                diagnostics.record(VoiceEvent.ERROR, diagnosticStage)
                mutableState.value = VoiceState.Error(failure.message ?: "Hands-free listening failed. Use push-to-talk.")
            } finally {
                try {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try { wake.release() } finally { engine.release() }
                    }
                } finally {
                    handsFree.value = false
                    active.set(false)
                    finishing.set(false)
                    diagnostics.record(VoiceEvent.SESSION_END, DiagnosticCode.WAKE)
                }
            }
        }
        return true
    }

    suspend fun stopAndJoin() {
        cancel()
        session?.join()
    }

    fun requestCommand(): Boolean = state.value is VoiceState.WaitingForWakeWord &&
        handsFree.value && manualCommand?.complete(Unit) == true

    suspend fun awaitSessionEnd() { session?.join() }

    fun start(fromService: Boolean = false): Boolean {
        if (!active.compareAndSet(false, true)) {
            diagnostics.record(VoiceEvent.DUPLICATE_REQUEST)
            return false
        }
        diagnostics.record(VoiceEvent.SESSION_START, DiagnosticCode.ASR)
        diagnosticStage = DiagnosticCode.ASR
        serviceOwned = fromService
        finishing.set(false)
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                output.stopAndAwait()
                recognizeCommand()
            } catch (cancelled: CancellationException) {
                diagnostics.record(if (cancelled is TimeoutCancellationException) VoiceEvent.TIMEOUT else VoiceEvent.CANCELLED,
                    diagnosticStage)
                mutableState.value = VoiceState.Idle("Listening cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                diagnostics.record(VoiceEvent.ERROR, diagnosticStage)
                mutableState.value = VoiceState.Error(failure.message ?: "Offline speech recognition failed.")
            } finally {
                serviceOwned = false
                active.set(false)
                finishing.set(false)
                diagnostics.record(VoiceEvent.SESSION_END, DiagnosticCode.ASR)
            }
        }
        return true
    }

    private suspend fun recognizeCommand() {
        finishing.set(false)
        currentCoroutineContext().ensureActive()
        mutableState.value = VoiceState.Initializing
        engine.initialize()
        diagnostics.record(VoiceEvent.ASR_ACTIVATION)
        val finalText = try {
            withTimeoutOrNull(16_000) {
                engine.recognizeResult(onReady = {
                    if (active.get() && !finishing.get()) mutableState.value = VoiceState.Listening()
                }) { partial ->
                    if (!partial.isFinal && active.get() && !finishing.get()) mutableState.value = VoiceState.Listening(partial.transcript.take(300))
                }.takeIf { it.isFinal }?.transcript ?: ""
            } ?: run {
                diagnostics.record(VoiceEvent.TIMEOUT, DiagnosticCode.ASR)
                ""
            }
        } finally { engine.stop() }
        currentCoroutineContext().ensureActive()
        mutableState.value = VoiceState.Processing
        val transcript = normalizeTranscript(finalText)
        if (transcript.isBlank()) {
            diagnostics.record(VoiceEvent.EMPTY_TRANSCRIPT, DiagnosticCode.ASR)
            mutableState.value = VoiceState.Error("No speech was recognized. Try again in a quiet moment.")
            return
        }
        mutableState.value = VoiceState.Recognized(transcript)
        diagnostics.record(VoiceEvent.FINAL_TRANSCRIPT)
        when (val prepared = dispatcher.prepare(transcript)) {
            is Preparation.Rejected -> complete(transcript, prepared.result)
            is Preparation.Ready -> {
                currentCoroutineContext().ensureActive()
                mutableState.value = VoiceState.Executing(transcript)
                diagnostics.record(VoiceEvent.COMMAND_DISPATCH)
                complete(transcript, dispatcher.execute(prepared.pending))
            }
        }
    }

    fun finish() {
        if (state.value !is VoiceState.Listening || !finishing.compareAndSet(false, true)) return
        mutableState.value = VoiceState.Processing
        engine.stop()
    }

    fun cancel() {
        if (!active.get()) return
        finishing.set(true)
        detector?.stop()
        engine.stop()
        output.stop()
        session?.cancel()
        mutableState.value = VoiceState.Idle("Listening cancelled.")
    }

    fun installModel(uri: Uri) {
        if (!active.compareAndSet(false, true)) return
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                withContext(Dispatchers.IO) { engine.release() }
                installer.install(uri)
                installed.value = installer.isInstalled()
                mutableState.value = VoiceState.Idle("Selected offline speech model installed. It will load at the next recognition session.")
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

    fun evaluate(action: suspend () -> String): Boolean {
        if (!active.compareAndSet(false, true)) return false
        evaluationReport.value = ""
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                output.stopAndAwait()
                withContext(Dispatchers.IO) { engine.release(); detector?.release() }
                evaluationReport.value = withContext(Dispatchers.IO) { action() }
                mutableState.value = VoiceState.Idle("ASR evaluation completed. No commands were executed.")
            } catch (cancelled: CancellationException) {
                mutableState.value = VoiceState.Idle("ASR evaluation cancelled; partial report discarded.")
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = VoiceState.Error(failure.message ?: "ASR evaluation failed.")
            } finally { active.set(false) }
        }
        return true
    }

    fun installWakeModel(uri: Uri, installer: SpeechModelInstaller) {
        if (!active.compareAndSet(false, true)) return
        session = scope.launch {
            try {
                mutableState.value = VoiceState.Initializing
                withContext(Dispatchers.IO) { detector?.release() }
                installer.install(uri)
                detector?.initialize("Hey Nexa")
                mutableState.value = VoiceState.Idle("Hey Nexa keyword model installed. Enable hands-free explicitly.")
            } catch (cancelled: CancellationException) {
                mutableState.value = VoiceState.Idle("Wake-word import cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = VoiceState.Error(failure.message ?: "Wake-word model import failed.")
            } finally {
                try { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { detector?.release() } }
                finally { active.set(false) }
            }
        }
    }

    fun reportError(message: String) {
        diagnostics.record(VoiceEvent.ERROR, DiagnosticCode.SERVICE)
        mutableState.value = VoiceState.Error(message)
    }

    fun cancelForLifecycle() {
        if (handsFree.value || serviceOwned) return
        cancel()
        output.stop()
    }

    fun microphonePermissionDenied(permanentlyDenied: Boolean) {
        diagnostics.record(VoiceEvent.ERROR, DiagnosticCode.PERMISSION)
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
        diagnostics.record(VoiceEvent.COMMAND_RESULT, when (result) {
            is CommandResult.Success -> DiagnosticCode.SUCCESS
            is CommandResult.Failed -> DiagnosticCode.FAILED
            CommandResult.Cancelled -> DiagnosticCode.CANCELLED
            else -> DiagnosticCode.REJECTED
        })
        mutableState.value = VoiceState.Completed(transcript, result)
        if (speechEnabled() && output.state.value == SpeechOutputState.Ready) {
            if (handsFree.value || serviceOwned) {
                mutableState.value = VoiceState.SpeakingResponse
                diagnosticStage = DiagnosticCode.TTS
                withTimeout(20_000) { output.speakAndAwait(spokenSummary(result)) }
                mutableState.value = VoiceState.Completed(transcript, result)
            } else output.speak(spokenSummary(result))
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