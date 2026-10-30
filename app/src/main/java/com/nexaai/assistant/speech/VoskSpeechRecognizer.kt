package com.nexaai.assistant.speech

import com.nexaai.assistant.diagnostics.VoiceDiagnostics
import android.net.Uri
import android.content.ContentResolver
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer

data class CommandAudioInput(val source: String = "VOICE_RECOGNITION", val gainPercent: Int = 100) {
    init {
        require(source in setOf("VOICE_RECOGNITION", "MIC"))
        require(gainPercent in 100..300)
    }
}

internal fun applyCommandInputGain(samples: ShortArray, count: Int, gainPercent: Int) {
    require(count in 0..samples.size && gainPercent in 100..300)
    if (gainPercent == 100) return
    for (index in 0 until count) {
        samples[index] = (samples[index].toInt() * gainPercent / 100)
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

class VoskSpeechRecognizerEngine(
    private val modelDirectory: File,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val input: suspend () -> CommandAudioInput = { CommandAudioInput() },
    capture: CommandAudioCapture = AndroidCommandAudioCapture(diagnostics),
    private val grammar: suspend () -> VoskGrammar? = { null },
) : StreamingSpeechEngine(capture, input) {
    @Volatile private var model: Model? = null
    override val engineId = "vosk"
    override val displayName = "Vosk - Indian English"

    override fun loadModel() {
        if (!modelDirectory.isDirectory) throw RecognitionException(RecognitionError(engineId,
            RecognitionErrorCode.MISSING_MODEL, "Vosk model is missing. Import the Indian English model ZIP from Models."))
        model = Model(modelDirectory.absolutePath)
    }

    override suspend fun createSession(): RecognitionSession {
        val loaded = checkNotNull(model)
        val sessionGrammar = grammar()
        val recognizer = try {
            if (sessionGrammar == null) Recognizer(loaded, 16000f)
            else Recognizer(loaded, 16000f, JSONArray(sessionGrammar.phrases).toString())
        } catch (failure: Exception) {
            throw IOException("Vosk recognizer initialization failed. Grammar mode requires a dynamic-grammar model; disable grammar or reinstall a compatible small model.", failure)
        }
        val start = System.nanoTime() / 1_000_000
        return object : RecognitionSession {
            private fun result(json: String, final: Boolean) = RecognitionResult(engineId,
                JSONObject(json).optString(if (final) "text" else "partial").trim(), final,
                start, System.nanoTime() / 1_000_000)
            override fun acceptAudioFrame(samples: ShortArray, count: Int): RecognitionResult {
                require(count in 1..samples.size)
                val endpoint = recognizer.acceptWaveForm(samples, count)
                return result(if (endpoint) recognizer.result else recognizer.partialResult, endpoint)
            }
            override fun finish() = result(recognizer.finalResult, true)
            override fun commandTranscript(result: RecognitionResult) = sessionGrammar?.canonicalTranscript(result.transcript) ?: result.transcript
            override fun close() { recognizer.close() }
        }
    }

    override fun unloadModel() {
        model?.close()
        model = null
    }
}

internal fun completedVoskTranscript(
    endpoint: Boolean,
    json: String,
    onPartialResult: (String) -> Unit,
): String? {
    val text = JSONObject(json).optString(if (endpoint) "text" else "partial").trim()
    if (endpoint) return text.takeIf(String::isNotBlank)
    if (text.isNotBlank()) onPartialResult(text)
    return null
}

class VoskModelInstaller(
    private val filesDirectory: File,
    private val directoryName: String = "vosk-model-small-en-us-0.15",
    private val requiredFiles: List<String> = listOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf",
        "graph/HCLr.fst", "graph/Gr.fst", "graph/phones/word_boundary.int"),
    private val validateRoot: (File) -> Unit = {},
    private val openArchive: (Uri) -> InputStream?,
) : SpeechModelInstaller {
    constructor(filesDirectory: File, contentResolver: ContentResolver) : this(filesDirectory, openArchive = contentResolver::openInputStream)

    private val installedDirectory = File(filesDirectory, directoryName)

    override fun isInstalled(): Boolean = isModelRoot(installedDirectory)

    override suspend fun install(uri: Uri) = withContext(Dispatchers.IO) {
        val staging = File(filesDirectory, "model-import-${System.nanoTime()}")
        val backup = File(filesDirectory, "$directoryName.backup")
        try {
            require(uri.scheme == "content") { "Choose a local model ZIP using the file picker." }
            val input = openArchive(uri) ?: throw IOException("The selected model file could not be opened.")
            input.use { source ->
                ZipInputStream(source).use { zip ->
                    var expandedBytes = 0L
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > MAX_ZIP_ENTRIES) throw IOException("Model ZIP contains too many files.")
                        val relative = entry.name.replace('\\', '/')
                        if (relative.startsWith('/') || relative.split('/').any { it == ".." }) {
                            throw IOException("Model ZIP contains an unsafe path.")
                        }
                        val target = File(staging, relative).canonicalFile
                        if (!target.path.startsWith(staging.canonicalPath + File.separator)) throw IOException("Model ZIP contains an unsafe path.")
                        if (entry.isDirectory) {
                            if (!target.mkdirs() && !target.isDirectory) throw IOException("Could not create model directory.")
                        } else {
                            target.parentFile?.let { parent -> if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Could not create model directory.") }
                            target.outputStream().buffered().use { destination ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    expandedBytes += read
                                    if (expandedBytes > MAX_EXPANDED_BYTES) throw IOException("Expanded model exceeds the 160 MB limit.")
                                    destination.write(buffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val modelRoot = staging.walkTopDown().firstOrNull(::isModelRoot)
                ?: throw IOException("The ZIP is incomplete; required model files are missing.")
            validateRoot(modelRoot)
            if (backup.exists()) backup.deleteRecursively()
            if (installedDirectory.exists() && !installedDirectory.renameTo(backup)) throw IOException("Could not replace the existing model safely.")
            if (!modelRoot.renameTo(installedDirectory)) {
                if (backup.exists()) backup.renameTo(installedDirectory)
                throw IOException("Could not install the selected model.")
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        Unit
    }

    private fun isModelRoot(directory: File): Boolean = directory.isDirectory &&
        requiredFiles.all { relative ->
            File(directory, relative).let { it.isFile && it.length() > 0 }
        }

    private companion object {
        const val MAX_ZIP_ENTRIES = 20_000
        const val MAX_EXPANDED_BYTES = 160L * 1024 * 1024
    }
}