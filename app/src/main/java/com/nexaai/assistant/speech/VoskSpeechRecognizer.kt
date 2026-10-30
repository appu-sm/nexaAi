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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val capture: CommandAudioCapture = AndroidCommandAudioCapture(diagnostics, input),
    private val grammar: suspend () -> VoskGrammar? = { null },
) : SpeechRecognizerEngine {
    @Volatile private var model: Model? = null
    private val sampleRate = 16_000

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (model != null) return@withContext
        if (!modelDirectory.isDirectory) throw IOException("Offline speech model is missing. Install the Vosk English model from Models.")
        try {
            model = Model(modelDirectory.absolutePath)
        } catch (failure: LinkageError) {
            throw IOException("Vosk native libraries are incompatible with this device ABI.", failure)
        } catch (failure: Exception) {
            throw IOException("Offline speech model is corrupt or incompatible. Reinstall the model ZIP.", failure)
        }
    }

    override suspend fun recognize(onPartialResult: (String) -> Unit): String = recognize({}, onPartialResult)

    override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val loadedModel = model ?: throw IOException("Offline speech model is not loaded.")
        val sessionGrammar = grammar()
        val recognizer = try {
            if (sessionGrammar == null) Recognizer(loadedModel, sampleRate.toFloat())
            else Recognizer(loadedModel, sampleRate.toFloat(), JSONArray(sessionGrammar.phrases).toString())
        } catch (failure: Exception) {
            throw IOException("Vosk recognizer initialization failed. Grammar mode requires a model supporting dynamic grammar; use the recommended small English model or turn grammar mode off.", failure)
        }
        recognizer.use { decoder ->
            var completed: String? = null
            capture.capture(onReady) { samples, count ->
                val endpoint = decoder.acceptWaveForm(samples, count)
                val json = if (endpoint) decoder.result else decoder.partialResult
                completed = completedVoskTranscript(endpoint, json, onPartialResult)
                completed != null
            }
            currentCoroutineContext().ensureActive()
            val transcript = completed ?: parseResult(decoder.finalResult, "text")
            sessionGrammar?.canonicalTranscript(transcript) ?: transcript
        }
    }

    override fun stop() = capture.stop()

    override fun release() {
        stop()
        model?.close()
        model = null
    }

    private fun parseResult(json: String, key: String): String = runCatching { JSONObject(json).optString(key).trim() }.getOrDefault("")

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
                        currentCoroutineContext().ensureActive()
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
                                    currentCoroutineContext().ensureActive()
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
                ?: run {
                    val closestRoot = staging.walkTopDown().filter { it.isDirectory }
                        .minByOrNull { missingFiles(it).size } ?: staging
                    throw IOException("Missing or empty required files: ${missingFiles(closestRoot).joinToString(", ")}.")
                }
            validateRoot(modelRoot)
            currentCoroutineContext().ensureActive()
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

    private fun missingFiles(directory: File): List<String> = requiredFiles.filter { relative ->
        File(directory, relative).let { !it.isFile || it.length() == 0L }
    }

    private fun isModelRoot(directory: File): Boolean = directory.isDirectory && missingFiles(directory).isEmpty()

    private companion object {
        const val MAX_ZIP_ENTRIES = 20_000
        const val MAX_EXPANDED_BYTES = 160L * 1024 * 1024
    }
}