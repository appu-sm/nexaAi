package com.nexaai.assistant.speech

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.nexaai.assistant.diagnostics.VoiceDiagnostics
import com.nexaai.assistant.diagnostics.VoiceEvent
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

interface CommandAudioCapture {
    suspend fun capture(input: CommandAudioInput, onReady: () -> Unit, accept: (ShortArray, Int) -> Boolean)
    fun stop()
}

class AndroidCommandAudioCapture(private val diagnostics: VoiceDiagnostics) : CommandAudioCapture {
    private val recording = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null

    override suspend fun capture(input: CommandAudioInput, onReady: () -> Unit, accept: (ShortArray, Int) -> Boolean) = withContext(Dispatchers.IO) {
        check(recording.compareAndSet(false, true)) { "A command microphone session is already active." }
        var audio: AudioRecord? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        val interrupted = AtomicBoolean(false)
        var started = false
        try {
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0 || minimum > 65536) throw IOException("This microphone does not support 16 kHz mono PCM audio.")
            val bufferBytes = maxOf(minimum * 2, 16000)
            val source = if (input.source == "MIC") MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val capture = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            audio = capture
            if (capture.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone could not be initialized.")
            recorder = capture
            capture.startRecording()
            if (capture.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not enter recording state.")
            started = true
            diagnostics.record(VoiceEvent.ASR_READY)
            onReady()
            if (Build.VERSION.SDK_INT >= 29) {
                val listener = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configurations: List<android.media.AudioRecordingConfiguration>) {
                        if (configurations.any { it.clientAudioSessionId == capture.audioSessionId && it.isClientSilenced }) {
                            interrupted.set(true)
                            stop()
                        }
                    }
                }
                callback = listener
                capture.registerAudioRecordingCallback(Executor { Handler(Looper.getMainLooper()).post(it) }, listener)
            }
            val samples = ShortArray(bufferBytes / 2)
            val start = android.os.SystemClock.elapsedRealtime()
            while (recording.get() && android.os.SystemClock.elapsedRealtime() - start < 15000) {
                currentCoroutineContext().ensureActive()
                val count = capture.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (interrupted.get()) throw IOException("Microphone was silenced by Android. Command discarded; try again.")
                if (count < 0) {
                    if (!recording.get()) break
                    throw IOException("Microphone read failed ($count).")
                }
                if (count == 0) continue
                applyCommandInputGain(samples, count, input.gainPercent)
                if (accept(samples, count)) break
            }
            if (interrupted.get()) throw IOException("Microphone was interrupted; command discarded.")
        } catch (denied: SecurityException) {
            throw IOException("Microphone permission was denied or revoked. Grant access in Android app settings and retry.", denied)
        } finally {
            recording.set(false)
            recorder = null
            audio?.let { capture ->
                if (Build.VERSION.SDK_INT >= 29) callback?.let { runCatching { capture.unregisterAudioRecordingCallback(it) } }
                if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { capture.stop() }
                capture.release()
            }
            if (started) diagnostics.record(VoiceEvent.ASR_CAPTURE_STOP)
        }
    }

    override fun stop() {
        recording.set(false)
        recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { it.stop() } }
    }
}

abstract class StreamingSpeechEngine(
    private val capture: CommandAudioCapture,
    private val input: suspend () -> CommandAudioInput,
) : SpeechRecognizerEngine {
    final override val status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Unloaded)
    private val busy = AtomicBoolean(false)

    protected abstract fun loadModel()
    protected abstract fun unloadModel()
    protected abstract suspend fun createSession(): RecognitionSession

    final override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (status.value == RecognitionStatus.Ready) return@withContext
        status.value = RecognitionStatus.Loading
        try {
            loadModel()
            status.value = RecognitionStatus.Ready
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                unloadModel()
                status.value = RecognitionStatus.Unloaded
                throw failure
            }
            if (failure !is Exception && failure !is LinkageError && failure !is OutOfMemoryError) throw failure
            unloadModel()
            val error = (failure as? RecognitionException)?.error ?: RecognitionError(engineId,
                RecognitionErrorCode.INITIALIZATION,
                "$displayName initialization failed. Check trusted model files, available memory and device ABI.", false)
            status.value = RecognitionStatus.Failed(error)
            throw RecognitionException(error, failure)
        }
    }

    final override suspend fun openSession(): RecognitionSession {
        if (!busy.compareAndSet(false, true)) throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.BUSY, "Recognition session already active."))
        try {
            initialize()
            val delegate = createSession()
            return object : RecognitionSession by delegate {
                override fun close() { try { delegate.close() } finally { busy.set(false) } }
            }
        } catch (failure: Throwable) {
            busy.set(false)
            when (failure) {
                is OutOfMemoryError -> throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.MEMORY, "Insufficient memory for the selected speech session.", false), failure)
                is LinkageError -> throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.NATIVE_INFERENCE, "Speech native library is incompatible with this device.", false), failure)
                else -> throw failure
            }
        }
    }

    final override suspend fun recognize(onPartialResult: (String) -> Unit): String = recognize({}, onPartialResult)

    final override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit): String =
        recognizeResult(onReady) { if (!it.isFinal) onPartialResult(it.transcript) }.transcript

    final override suspend fun recognizeResult(onReady: () -> Unit, onResult: (RecognitionResult) -> Unit): RecognitionResult = withContext(Dispatchers.IO) {
        openSession().use { session ->
            var completed: RecognitionResult? = null
            val context = currentCoroutineContext()
            try {
                capture.capture(input(), onReady) { samples, count ->
                    context.ensureActive()
                    val result = session.acceptAudioFrame(samples, count)
                    if (result.isFinal && result.transcript.isNotBlank()) completed = result
                    else if (!result.isFinal && result.transcript.isNotBlank()) onResult(result)
                    completed != null
                }
                currentCoroutineContext().ensureActive()
                val result = completed ?: session.finish()
                result.copy(transcript = session.commandTranscript(result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SecurityException) {
                throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.AUDIO, "Microphone permission is required."), failure)
            } catch (failure: IllegalStateException) {
                throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.AUDIO, "Microphone or decoder became unavailable."), failure)
            } catch (failure: IOException) {
                if (failure is RecognitionException) throw failure
                throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.AUDIO, failure.message ?: "Command audio capture failed."), failure)
            } catch (failure: OutOfMemoryError) {
                throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.MEMORY, "Insufficient memory for speech inference. Stop listening and retry.", false), failure)
            } catch (failure: LinkageError) {
                throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.NATIVE_INFERENCE, "Speech inference library failed on this device.", false), failure)
            }
        }
    }

    final override fun stop() { capture.stop() }

    final override fun release() {
        check(!busy.get()) { "Stop and join recognition before releasing the model." }
        unloadModel()
        status.value = RecognitionStatus.Unloaded
    }
}