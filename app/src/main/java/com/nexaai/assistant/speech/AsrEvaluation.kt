package com.nexaai.assistant.speech

import com.nexaai.assistant.data.CommandId
import com.nexaai.assistant.data.Configuration
import com.nexaai.assistant.data.ConfigurationCodec
import com.nexaai.assistant.data.ConfigurationSnapshot
import com.nexaai.assistant.diagnostics.ResourceMetrics
import com.nexaai.assistant.domain.AllowedApp
import com.nexaai.assistant.domain.AssistantCommand
import com.nexaai.assistant.domain.ParseResult
import com.nexaai.assistant.domain.RuleBasedCommandParser
import com.nexaai.assistant.execution.CommandValidator
import com.nexaai.assistant.execution.ExecutionConfiguration
import com.nexaai.assistant.execution.FmBand
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*

data class EvaluationSample(
    val id: String,
    val reference: String,
    val pcm: File,
    val expectedCommandId: CommandId?,
    val expectedParameter: String? = null,
)

object EvaluationText {
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}. ]"), " ").trim().trimEnd('.')
        .replace(Regex("\\s+"), " ")

    fun wordErrors(reference: String, actual: String): Int {
        val expected = normalize(reference).split(' ').filter(String::isNotEmpty)
        val observed = normalize(actual).split(' ').filter(String::isNotEmpty)
        var previous = IntArray(observed.size + 1) { it }
        expected.forEachIndexed { index, word ->
            val next = IntArray(observed.size + 1)
            next[0] = index + 1
            observed.forEachIndexed { offset, candidate ->
                next[offset + 1] = minOf(next[offset] + 1, previous[offset + 1] + 1,
                    previous[offset] + if (word == candidate) 0 else 1)
            }
            previous = next
        }
        return previous.last()
    }
}

object EvaluationArchive {
    suspend fun <Result> read(input: InputStream, cache: File, action: suspend (List<EvaluationSample>, String, String) -> Result): Result = withContext(Dispatchers.IO) {
        val directory = File(cache, "asr-evaluation-${System.nanoTime()}")
        check(directory.mkdirs()) { "Could not create evaluation staging directory." }
        try {
            var total = 0L
            val names = mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name.matches(Regex("(?:corpus\\.json|[a-zA-Z0-9_-]{1,60}\\.pcm)")) && names.add(entry.name)) {
                        "Evaluation ZIP must contain only unique root-level corpus.json and PCM files."
                    }
                    require(names.size <= 101) { "At most 100 PCM samples are supported per run." }
                    val limit = if (entry.name == "corpus.json") 65536 else 480000
                    var size = 0
                    File(directory, entry.name).outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            require(size <= limit && total <= 32L * 1024 * 1024) { "Evaluation audio exceeds the 15 second/sample or 32 MiB/run limit." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val root = Json.parseToJsonElement(File(directory, "corpus.json").readText()).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1 &&
                root.getValue("consentConfirmed").jsonPrimitive.boolean &&
                root.getValue("sampleRate").jsonPrimitive.int == 16000 &&
                root.getValue("channels").jsonPrimitive.int == 1 &&
                root.getValue("encoding").jsonPrimitive.content == "PCM_S16LE") { "Confirm recording consent and use 16 kHz mono PCM_S16LE." }
            val provenance = root.getValue("provenance").jsonPrimitive.content
            val model = root.getValue("voskModel").jsonPrimitive.content
            require(provenance.length in 1..300 && model.length in 1..100)
            val samples = root.getValue("samples").jsonArray.map { element ->
                val sample = element.jsonObject
                val id = sample.getValue("id").jsonPrimitive.content
                require(id.matches(Regex("[a-zA-Z0-9_-]{1,60}")))
                val reference = sample.getValue("reference").jsonPrimitive.content
                require(reference.length in 1..300 && EvaluationText.normalize(reference).isNotBlank())
                val file = File(directory, "$id.pcm")
                require(file.isFile && file.length() in 2..480000 && file.length() % 2 == 0L) { "Missing, empty or invalid PCM for $id." }
                val command = sample["expectedCommandId"]?.jsonPrimitive?.contentOrNull?.let(CommandId::valueOf)
                val parameter = sample["expectedParameter"]?.jsonPrimitive?.contentOrNull
                require(parameter == null || parameter.length <= 200)
                EvaluationSample(id, reference, file, command, parameter)
            }
            require(samples.size in 1..100 && samples.map { it.id }.distinct().size == samples.size) { "Use 1-100 unique samples." }
            action(samples, model, provenance)
        } finally { directory.deleteRecursively() }
    }
}

class AsrEvaluationRunner(
    private val factory: (String) -> SpeechRecognizerEngine,
    private val resources: ResourceMetrics? = null,
) {
    suspend fun run(samples: List<EvaluationSample>, configuration: Configuration, apps: List<AllowedApp>,
        voskModel: String, provenance: String): String = withContext(Dispatchers.IO) {
        require(samples.size in 1..100)
        fun readResources() = resources?.let { provider -> runCatching { provider.read() }.getOrNull() }
        val environment = ExecutionConfiguration(ConfigurationSnapshot(configuration, ready = true), apps)
        val parser = RuleBasedCommandParser()
        val validator = CommandValidator()
        val band = FmBand("87.5".toBigDecimal(), "108.0".toBigDecimal(), "0.01".toBigDecimal())
        fun resolve(text: String): AssistantCommand? {
            val parsed = parser.parse(text, configuration, apps) as? ParseResult.Resolved ?: return null
            return parsed.command.takeIf { validator.validate(it, environment, band) == null }
        }
        fun parameter(command: AssistantCommand?): String? = when (command) {
            is AssistantCommand.SetVolume -> command.percent.toString()
            is AssistantCommand.TuneFm -> command.frequencyMhz.stripTrailingZeros().toPlainString()
            is AssistantCommand.LaunchApp -> command.appId
            else -> null
        }
        val reports = buildJsonArray {
            for (id in AsrEngines.labels.keys) {
                currentCoroutineContext().ensureActive()
                val engine = factory(id)
                val before = readResources()
                val initializationStart = System.nanoTime()
                var initializationMs: Long? = null
                var initializationError: String? = null
                try {
                    try {
                        withTimeout(60000) { engine.initialize() }
                        initializationMs = (System.nanoTime() - initializationStart) / 1_000_000
                    } catch (timeout: TimeoutCancellationException) { initializationError = "TIMEOUT" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { initializationError = (failure as? RecognitionException)?.error?.code?.name ?: "INITIALIZATION" }
                    val initializationAttemptMs = (System.nanoTime() - initializationStart) / 1_000_000
                    val loaded = readResources()
                    var exactCount = 0
                    var commandCount = 0
                    var parameterCount = 0
                    var errors = 0
                    var empty = 0
                    var edits = 0
                    var words = 0
                    val rows = buildJsonArray {
                        for (sample in samples) {
                            currentCoroutineContext().ensureActive()
                            val bytes = sample.pcm.readBytes()
                            require(bytes.size in 2..480000 && bytes.size % 2 == 0)
                            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val pcm = ShortArray(buffer.remaining()).also { buffer.get(it) }
                            val started = System.nanoTime()
                            var transcript = ""
                            var error = initializationError
                            var consumed = 0
                            if (error == null) {
                                try {
                                    withTimeout(30000) {
                                        engine.openSession().use { session ->
                                            while (consumed < pcm.size) {
                                                currentCoroutineContext().ensureActive()
                                                val count = minOf(1600, pcm.size - consumed)
                                                val result = session.acceptAudioFrame(pcm.copyOfRange(consumed, consumed + count), count)
                                                consumed += count
                                                if (result.isFinal && result.transcript.isNotBlank()) { transcript = result.transcript; break }
                                            }
                                            if (transcript.isBlank()) transcript = session.finish().transcript
                                        }
                                    }
                                } catch (timeout: TimeoutCancellationException) { error = "TIMEOUT" }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = (failure as? RecognitionException)?.error?.code?.name ?: "DECODE" }
                                catch (failure: LinkageError) { error = "NATIVE_ABI" }
                                catch (failure: OutOfMemoryError) { error = "MEMORY" }
                            }
                            val latency = (System.nanoTime() - started) / 1_000_000
                            val command = if (error == null && transcript.isNotBlank()) resolve(transcript) else null
                            val exact = error == null && EvaluationText.normalize(sample.reference) == EvaluationText.normalize(transcript)
                            val commandCorrect = error == null && transcript.isNotBlank() && command?.id == sample.expectedCommandId
                            val parameterCorrect = commandCorrect && parameter(command) == sample.expectedParameter
                            val wordCount = EvaluationText.normalize(sample.reference).split(' ').size
                            val wordErrors = EvaluationText.wordErrors(sample.reference, transcript)
                            words += wordCount; edits += wordErrors
                            if (exact) exactCount++
                            if (commandCorrect) commandCount++
                            if (sample.expectedParameter != null && parameterCorrect) parameterCount++
                            if (error != null) errors++
                            if (transcript.isBlank()) empty++
                            add(buildJsonObject {
                                put("sampleId", sample.id); put("reference", sample.reference); put("transcript", transcript)
                                put("pcmSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                                put("audioDurationMs", pcm.size * 1000L / 16000); put("consumedAudioMs", consumed * 1000L / 16000)
                                put("processCpuMs", "Not measured: engine-level total only")
                                put("peakMemory", "Not measured"); put("steadyStateMemory", "Not measured")
                                put("recognitionMs", if (initializationError == null) JsonPrimitive(latency) else JsonPrimitive("Not measured"))
                                put("error", error?.let(::JsonPrimitive) ?: JsonNull); put("empty", transcript.isBlank())
                                put("exactTranscript", exact); put("wordErrors", wordErrors); put("referenceWords", wordCount)
                                put("expectedCommandId", sample.expectedCommandId?.name?.let(::JsonPrimitive) ?: JsonNull)
                                put("referencePipelineCommand", resolve(sample.reference)?.id?.name?.let(::JsonPrimitive) ?: JsonNull)
                                put("actualCommandId", command?.id?.name?.let(::JsonPrimitive) ?: JsonNull)
                                put("expectedParameter", sample.expectedParameter?.let(::JsonPrimitive) ?: JsonNull)
                                put("actualParameter", parameter(command)?.let(::JsonPrimitive) ?: JsonNull)
                                put("commandCorrect", commandCorrect)
                                put("parameterCorrect", if (sample.expectedParameter != null) JsonPrimitive(parameterCorrect) else JsonNull)
                            })
                        }
                    }
                    val after = readResources()
                    add(buildJsonObject {
                        put("engineId", id); put("engineVersion", if (id == AsrEngines.VOSK) "0.3.47" else "1.12.14")
                        put("modelVersion", if (id == AsrEngines.VOSK) voskModel else SherpaAsrModel.VERSION)
                        put("modelIdentitySource", if (id == AsrEngines.VOSK) "Operator declared; installed Vosk files unchanged" else "Pinned configuration")
                        put("initializationMs", initializationMs?.let(::JsonPrimitive) ?: JsonPrimitive("Not measured"))
                        put("initializationError", initializationError?.let(::JsonPrimitive) ?: JsonNull)
                        put("initializationAttemptMs", initializationAttemptMs)
                        put("beforeLoadPssKb", before?.pssKb?.let(::JsonPrimitive) ?: JsonPrimitive("Not measured"))
                        put("afterLoadPssKb", loaded?.pssKb?.let(::JsonPrimitive) ?: JsonPrimitive("Not measured"))
                        put("afterRunPssKb", after?.pssKb?.let(::JsonPrimitive) ?: JsonPrimitive("Not measured"))
                        put("afterLoadRssKb", loaded?.rssKb?.let(::JsonPrimitive) ?: JsonPrimitive("Not measured"))
                        put("processCpuMs", if (before != null && after != null) JsonPrimitive(after.processCpuMs - before.processCpuMs) else JsonPrimitive("Not measured"))
                        put("peakLoadMemory", "Not measured"); put("steadyStateMemory", "Not measured")
                        put("sampleCount", samples.size); put("exactCount", exactCount); put("commandCorrectCount", commandCount)
                        put("parameterCorrectCount", parameterCount); put("parameterSampleCount", samples.count { it.expectedParameter != null })
                        put("parameterPercent", samples.count { it.expectedParameter != null }.let { count ->
                            if (count > 0) JsonPrimitive(parameterCount * 100.0 / count) else JsonPrimitive("Not measured: no parameter samples")
                        })
                        put("expectedRejectionCount", samples.count { it.expectedCommandId == null })
                        put("errors", errors); put("emptyOutputs", empty); put("wordErrors", edits); put("referenceWords", words)
                        put("wer", if (initializationError == null && samples.size >= 20 && words >= 100) JsonPrimitive(edits.toDouble() / words) else JsonPrimitive("Not measured: unavailable engine or fewer than 20 samples / 100 words"))
                        put("exactPercent", exactCount * 100.0 / samples.size); put("commandPercent", commandCount * 100.0 / samples.size)
                        put("samples", rows)
                    })
                } finally { engine.release() }
            }
        }
        buildJsonObject {
            put("schemaVersion", 1); put("provenance", provenance); put("audioFormat", "16000 Hz mono PCM_S16LE")
            put("captureTime", "Not measured: prerecorded audio"); put("normalization", "Locale.ROOT lowercase; punctuation except decimal points to spaces; trim trailing full stop; collapse whitespace; no number or alias rewriting")
            put("configurationSha256", MessageDigest.getInstance("SHA-256").digest(ConfigurationCodec.encode(configuration).toByteArray()).joinToString("") { "%02x".format(it) })
            put("grammar", "Disabled for both engines; no hotwords")
            put("limitations", "No commands executed. Counts include explicit expected rejections. Native calls are not preemptible; deadlines checked between frames. PSS snapshots are not peaks. Tiny/nonrepresentative corpora do not establish accent accuracy or target fitness. No engine superiority claim.")
            put("engines", reports)
        }.toString()
    }

    companion object {
        fun summary(report: String): String = Json.parseToJsonElement(report).jsonObject.getValue("engines").jsonArray.joinToString("\n") { value ->
            val engine = value.jsonObject
            "${engine.getValue("engineId").jsonPrimitive.content}: exact ${engine["exactCount"]}/${engine["sampleCount"]}; commands ${engine["commandCorrectCount"]}/${engine["sampleCount"]}; parameters ${engine["parameterCorrectCount"]}/${engine["parameterSampleCount"]}; errors ${engine["errors"]}; empty ${engine["emptyOutputs"]}; WER ${engine.getValue("wer").jsonPrimitive.content}"
        }
    }
}