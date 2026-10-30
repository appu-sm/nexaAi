package com.nexaai.assistant.speech

import android.content.ContentResolver
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.IOException
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object MoonshineModel {
    const val DIRECTORY = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27"
    val requiredFiles = listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt", "LICENSE", "silero_vad.onnx")

    fun isInstalled(directory: File): Boolean = requiredFiles.all {
        File(directory, it).let { asset -> asset.isFile && asset.length() > 0 }
    }

    fun installer(filesDirectory: File, resolver: ContentResolver): SpeechModelInstaller = VoskModelInstaller(
        filesDirectory = filesDirectory,
        directoryName = DIRECTORY,
        requiredFiles = requiredFiles,
        openArchive = resolver::openInputStream,
    )
}

internal fun moonshineConfig(directory: File) = OfflineRecognizerConfig(
    modelConfig = OfflineModelConfig(
        moonshine = OfflineMoonshineModelConfig(
            encoder = File(directory, "encoder_model.ort").absolutePath,
            mergedDecoder = File(directory, "decoder_model_merged.ort").absolutePath,
        ),
        tokens = File(directory, "tokens.txt").absolutePath,
        numThreads = 1,
        provider = "cpu",
    ),
    decodingMethod = "greedy_search",
)

internal fun normalizeMoonshineTranscript(text: String): String = text.trim().trimEnd('.', '!', '?').trimEnd()

internal interface MoonshineUtterance : Closeable {
    fun accept(samples: FloatArray): Boolean
    suspend fun finish(): String
}

internal interface MoonshineBackend : Closeable {
    fun openUtterance(): MoonshineUtterance
}

private class SherpaMoonshineBackend(private val directory: File) : MoonshineBackend {
    private val recognizer = OfflineRecognizer(config = moonshineConfig(directory))

    override fun openUtterance(): MoonshineUtterance {
        val detector = Vad(config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(directory, "silero_vad.onnx").absolutePath,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.25f,
                maxSpeechDuration = 12f,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
        ))
        return object : MoonshineUtterance {
            override fun accept(samples: FloatArray): Boolean {
                detector.acceptWaveform(samples)
                return !detector.empty()
            }

            override suspend fun finish(): String {
                detector.flush()
                if (detector.empty()) return ""
                val samples = detector.front().samples
                if (samples.isEmpty() || samples.size > 16_000 * 15) return ""
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, 16_000)
                    currentCoroutineContext().ensureActive()
                    recognizer.decode(stream)
                    currentCoroutineContext().ensureActive()
                    return recognizer.getResult(stream).text.trim()
                } finally { stream.release() }
            }

            override fun close() = detector.release()
        }
    }

    override fun close() = recognizer.release()
}

class MoonshineSpeechRecognizerEngine internal constructor(
    private val directory: File,
    private val capture: CommandAudioCapture,
    private val backendFactory: (File) -> MoonshineBackend,
) : SpeechRecognizerEngine {
    constructor(directory: File, capture: CommandAudioCapture) : this(directory, capture, ::SherpaMoonshineBackend)

    private var recognizer: MoonshineBackend? = null
    override val recognitionTimeoutMillis: Long = 45_000L

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext
        if (!MoonshineModel.isInstalled(directory)) throw IOException("Moonshine Base English is missing or incomplete. Import its model ZIP from Models.")
        try {
            recognizer = backendFactory(directory)
        } catch (failure: LinkageError) {
            throw IOException("Moonshine native runtime is incompatible with this device ABI.", failure)
        } catch (failure: OutOfMemoryError) {
            throw IOException("Not enough memory to load Moonshine Base English. Select Vosk instead.", failure)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Moonshine Base English could not be loaded. Reimport the exact 2026-02-27 model.", failure)
        }
    }

    override suspend fun recognize(onPartialResult: (String) -> Unit): String = recognize({}, onPartialResult, {})

    override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit): String =
        recognize(onReady, onPartialResult, {})

    override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit, onProcessing: () -> Unit): String =
        withContext(Dispatchers.IO) {
            val loaded = recognizer ?: throw IOException("Moonshine Base English is not loaded.")
            currentCoroutineContext().ensureActive()
            try {
                loaded.openUtterance().use { utterance ->
                    capture.capture(onReady) { samples, count ->
                        utterance.accept(FloatArray(count) { samples[it] / 32768f })
                    }
                    currentCoroutineContext().ensureActive()
                    onProcessing()
                    val transcript = utterance.finish()
                    currentCoroutineContext().ensureActive()
                    normalizeMoonshineTranscript(transcript)
                }
            } catch (failure: LinkageError) {
                throw IOException("Moonshine native inference is unavailable on this device.", failure)
            } catch (failure: OutOfMemoryError) {
                throw IOException("Moonshine ran out of memory. Stop listening and select Vosk.", failure)
            }
        }

    override fun stop() = capture.stop()

    override fun release() {
        stop()
        recognizer?.close()
        recognizer = null
    }
}