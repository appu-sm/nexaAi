package com.nexaai.assistant.speech

import android.content.ContentResolver
import com.nexaai.assistant.diagnostics.VoiceDiagnostics
import com.nexaai.assistant.diagnostics.VoiceEvent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.nexaai.assistant.data.ConfigurationValidator
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface WakeAudioSource : Closeable {
    fun read(samples: ShortArray, offset: Int, count: Int): Int
    fun stop()
}

interface KeywordInference : Closeable {
    fun accept(samples: FloatArray): Boolean
}

object WakeModel {
    const val DIRECTORY = "sherpa-kws-english"
    const val PHRASE = "Hey Nexa"
    val files = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")

    fun validate(directory: File, phrase: String? = null) {
        phrase?.let(ConfigurationValidator::validateWakePhrase)
        require(files.all { File(directory, it).let { file -> file.isFile && file.length() in 1..(40L * 1024 * 1024) } }) {
            "Wake-word model missing or incomplete. Import a keyword model ZIP from Models."
        }
        val keywords = File(directory, "keywords.txt")
        val tokensFile = File(directory, "tokens.txt")
        require(keywords.length() <= 4096 && tokensFile.length() <= 1024 * 1024) { "Keyword metadata is too large." }
        val lines = keywords.readLines().filter(String::isNotBlank)
        require(lines.size == 1) { "Install exactly one keyword phrase." }
        val tokens = lines.single().trim().split(Regex("\\s+"))
        val vocabulary = tokensFile.readLines().map { it.substringBefore(' ') }.toSet()
        require(tokens.all { it in vocabulary && !it.startsWith('<') }) { "Keyword tokens are incompatible with this vocabulary." }
        val decoded = tokens.joinToString("").replace('\u2581', ' ').trim()
        ConfigurationValidator.validateWakePhrase(decoded)
        require(phrase == null || normalizeTranscript(decoded) == normalizeTranscript(phrase)) {
            "Installed keyword tokens spell '$decoded', not '$phrase'. Change the wake phrase in Settings or import matching keyword tokens."
        }
    }

    fun installer(files: File, resolver: ContentResolver): SpeechModelInstaller = VoskModelInstaller(
        files, DIRECTORY, this.files, { validate(it) }, resolver::openInputStream,
    )
}

class SherpaWakeWordDetector(
    private val directory: File,
    private val audioFactory: () -> WakeAudioSource = { AndroidWakeAudioSource() },
    private val inferenceFactory: (File) -> KeywordInference = { SherpaKeywordInference(it) },
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
) : WakeWordDetector {
    private var inference: KeywordInference? = null
    private var capturedBefore = false
    @Volatile private var audio: WakeAudioSource? = null

    override suspend fun initialize(phrase: String) = withContext(Dispatchers.IO) {
        WakeModel.validate(directory, phrase)
        if (inference == null) {
            try { inference = inferenceFactory(directory) }
            catch (failure: LinkageError) { throw IOException("Wake-word native library is incompatible with this ABI.", failure) }
            catch (failure: Exception) { throw IOException("Wake-word model is incompatible or corrupt. Reinstall it.", failure) }
        }
        diagnostics.record(VoiceEvent.DETECTOR_READY)
    }

    override suspend fun awaitDetection() = withContext(Dispatchers.IO) {
        val keyword = inference ?: throw IOException("Wake-word model is not initialized.")
        val capture = audioFactory()
        audio = capture
        diagnostics.record(VoiceEvent.WAKE_CAPTURE_START)
        if (capturedBefore) diagnostics.record(VoiceEvent.LISTENING_RESUMED)
        capturedBefore = true
        try {
            val pcm = ShortArray(1600)
            val normalized = FloatArray(1600)
            while (true) {
                currentCoroutineContext().ensureActive()
                var offset = 0
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val count = capture.read(pcm, offset, pcm.size - offset)
                    if (count <= 0) throw IOException("Wake microphone stopped or became unavailable ($count).")
                    offset += count
                }
                for (index in pcm.indices) normalized[index] = pcm[index] / 32768f
                if (keyword.accept(normalized)) {
                    diagnostics.record(VoiceEvent.WAKE_DETECTED)
                    break
                }
            }
        } finally {
            audio = null
            try { capture.close() } finally { diagnostics.record(VoiceEvent.WAKE_CAPTURE_STOP) }
        }
    }

    override fun stop() { audio?.stop() }
    override fun release() {
        stop()
        inference?.close()
        inference = null
        capturedBefore = false
    }
}

private class SherpaKeywordInference(directory: File) : KeywordInference {
    private val spotter = KeywordSpotter(config = KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                File(directory, "encoder.onnx").path, File(directory, "decoder.onnx").path,
                File(directory, "joiner.onnx").path,
            ),
            tokens = File(directory, "tokens.txt").path, modelType = "zipformer2", numThreads = 1,
        ),
        keywordsFile = File(directory, "keywords.txt").path,
    ))
    private var stream = spotter.createStream()
    override fun accept(samples: FloatArray): Boolean {
        stream.acceptWaveform(samples, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotBlank()) {
                stream.release()
                stream = spotter.createStream()
                return true
            }
        }
        return false
    }
    override fun close() { try { stream.release() } finally { spotter.release() } }
}

private class AndroidWakeAudioSource : WakeAudioSource {
    private val recorder: AudioRecord
    private val silenced = AtomicBoolean(false)
    private var callback: AudioManager.AudioRecordingCallback? = null
    init {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum in 1..65536) { "Microphone does not support 16 kHz mono PCM." }
        recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
        } catch (denied: SecurityException) {
            throw IOException("Microphone permission was denied or revoked. Use Android app settings to grant access.", denied)
        }
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Wake microphone could not initialize." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Wake microphone did not start." }
            if (Build.VERSION.SDK_INT >= 29) {
                val listener = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configurations: List<AudioRecordingConfiguration>) {
                        if (configurations.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }) {
                            silenced.set(true)
                            stop()
                        }
                    }
                }
                callback = listener
                recorder.registerAudioRecordingCallback(Executor { Handler(Looper.getMainLooper()).post(it) }, listener)
            }
        } catch (failure: Exception) { recorder.release(); throw failure }
    }
    override fun read(samples: ShortArray, offset: Int, count: Int): Int {
        val read = recorder.read(samples, offset, count, AudioRecord.READ_BLOCKING)
        if (silenced.get()) throw IOException("Wake microphone was silenced by Android. Stop hands-free and retry, or use push-to-talk.")
        return read
    }
    override fun stop() { runCatching { recorder.stop() } }
    override fun close() {
        if (Build.VERSION.SDK_INT >= 29) callback?.let { runCatching { recorder.unregisterAudioRecordingCallback(it) } }
        stop()
        recorder.release()
    }
}