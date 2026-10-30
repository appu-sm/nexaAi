package com.nexaai.assistant.speech

import android.content.ContentResolver
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

object SherpaAsrModel {
    const val DIRECTORY = "sherpa-asr-english"
    const val VERSION = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    val files = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")

    fun validate(directory: File) {
        if (!files.all { File(directory, it).let { file -> file.isFile && file.length() in 1..(100L * 1024 * 1024) } }) {
            throw RecognitionException(RecognitionError("sherpa_onnx", RecognitionErrorCode.MISSING_MODEL,
                "sherpa-onnx ASR model is missing or incomplete. Import the en-20M-2023-02-17 int8 ZIP from Models (not the wake-word model)."))
        }
    }

    fun installer(directory: File, resolver: ContentResolver): SpeechModelInstaller =
        VoskModelInstaller(directory, DIRECTORY, files, { validate(it) }, resolver::openInputStream)
}

class SherpaSpeechRecognizerEngine(
    private val directory: File,
    capture: CommandAudioCapture,
    input: suspend () -> CommandAudioInput = { CommandAudioInput() },
) : StreamingSpeechEngine(capture, input) {
    override val engineId = "sherpa_onnx"
    override val displayName = "sherpa-onnx - English"
    private var recognizer: OnlineRecognizer? = null

    override fun loadModel() {
        SherpaAsrModel.validate(directory)
        recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    File(directory, SherpaAsrModel.files[0]).path,
                    File(directory, SherpaAsrModel.files[1]).path,
                    File(directory, SherpaAsrModel.files[2]).path,
                ),
                tokens = File(directory, "tokens.txt").path,
                modelType = "zipformer", numThreads = 1, provider = "cpu", debug = false,
            ),
            decodingMethod = "greedy_search", enableEndpoint = true,
        ))
    }

    override suspend fun createSession(): RecognitionSession {
        val decoder = checkNotNull(recognizer)
        val stream = decoder.createStream()
        val start = System.nanoTime() / 1_000_000
        return object : RecognitionSession {
            private fun decode(final: Boolean): RecognitionResult {
                while (decoder.isReady(stream)) decoder.decode(stream)
                return RecognitionResult(engineId, decoder.getResult(stream).text.trim(), final,
                    start, System.nanoTime() / 1_000_000)
            }
            override fun acceptAudioFrame(samples: ShortArray, count: Int): RecognitionResult {
                require(count in 1..samples.size)
                stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000)
                val result = decode(false)
                return result.copy(isFinal = decoder.isEndpoint(stream))
            }
            override fun finish(): RecognitionResult {
                stream.acceptWaveform(FloatArray(4800), 16000)
                stream.inputFinished()
                return decode(true)
            }
            override fun close() { stream.release() }
        }
    }

    override fun unloadModel() { recognizer?.release(); recognizer = null }
}