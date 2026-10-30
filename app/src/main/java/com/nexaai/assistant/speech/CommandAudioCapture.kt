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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface CommandAudioCapture {
    suspend fun capture(onReady: () -> Unit, onFrame: (ShortArray, Int) -> Boolean)
    fun stop()
}

class AndroidCommandAudioCapture(
    private val diagnostics: VoiceDiagnostics,
    private val input: suspend () -> CommandAudioInput,
) : CommandAudioCapture {
    @Volatile private var recorder: AudioRecord? = null
    private val recording = AtomicBoolean(false)

    override suspend fun capture(onReady: () -> Unit, onFrame: (ShortArray, Int) -> Boolean) {
        check(recording.compareAndSet(false, true)) { "A microphone session is already active." }
        var audio: AudioRecord? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        val interrupted = AtomicBoolean(false)
        var started = false
        try {
            val settings = input()
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0 || minimum > 65_536) throw IOException("This microphone does not support 16 kHz mono PCM audio.")
            val bufferBytes = maxOf(minimum * 2, 16_000)
            val source = if (settings.source == "MIC") MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
            audio = AudioRecord(source, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone could not be initialized.")
            recorder = audio
            currentCoroutineContext().ensureActive()
            if (!recording.get()) return
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not enter recording state.")
            started = true
            diagnostics.record(VoiceEvent.ASR_READY)
            onReady()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val listener = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configurations: List<android.media.AudioRecordingConfiguration>) {
                        if (configurations.any { it.clientAudioSessionId == audio.audioSessionId && it.isClientSilenced }) {
                            interrupted.set(true)
                            stop()
                        }
                    }
                }
                callback = listener
                audio.registerAudioRecordingCallback(Executor { task -> Handler(Looper.getMainLooper()).post(task) }, listener)
            }
            val samples = ShortArray(bufferBytes / 2)
            val start = android.os.SystemClock.elapsedRealtime()
            while (recording.get() && android.os.SystemClock.elapsedRealtime() - start < 15_000L) {
                currentCoroutineContext().ensureActive()
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (interrupted.get()) throw IOException("Microphone was silenced; command discarded.")
                if (count < 0) {
                    if (!recording.get()) break
                    throw IOException("Microphone read failed ($count).")
                }
                if (count == 0) continue
                applyCommandInputGain(samples, count, settings.gainPercent)
                if (onFrame(samples, count)) break
            }
            if (interrupted.get()) throw IOException("Microphone was interrupted; command discarded.")
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecurityException) {
            throw IOException("Microphone permission is required to listen.", failure)
        } catch (failure: IllegalStateException) {
            throw IOException("Microphone became unavailable during recognition.", failure)
        } finally {
            recording.set(false)
            recorder = null
            audio?.let { capture ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    callback?.let { runCatching { capture.unregisterAudioRecordingCallback(it) } }
                }
                if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { capture.stop() }
                capture.release()
            }
            if (started) diagnostics.record(VoiceEvent.ASR_CAPTURE_STOP)
        }
    }

    override fun stop() {
        recording.set(false)
        recorder?.let { capture ->
            if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { capture.stop() }
        }
    }
}