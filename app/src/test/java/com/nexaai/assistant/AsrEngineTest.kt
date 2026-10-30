package com.nexaai.assistant

import android.net.Uri
import com.nexaai.assistant.data.*
import com.nexaai.assistant.speech.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AsrEngineTest {
    private class Installer : SpeechModelInstaller {
        var installed = false
        override fun isInstalled() = installed
        override suspend fun install(uri: Uri) { installed = true }
    }

    private class Engine(override val engineId: String, private val events: MutableList<String>,
        private val transcript: String = "increase volume") : SpeechRecognizerEngine {
        var initializations = 0
        var frames = mutableListOf<Short>()
        var pending: CompletableDeferred<Unit>? = null
        var failure = false
        override val status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Unloaded)
        override suspend fun initialize() {
            if (status.value == RecognitionStatus.Ready) return
            events += "load:$engineId"; initializations++
            if (failure) throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.MISSING_MODEL, "Missing model"))
            status.value = RecognitionStatus.Ready
        }
        override suspend fun recognize(onPartialResult: (String) -> Unit): String {
            onPartialResult("increase")
            pending?.await()
            return transcript
        }
        override suspend fun openSession(): RecognitionSession {
            events += "session:$engineId"
            return object : RecognitionSession {
                override fun acceptAudioFrame(samples: ShortArray, count: Int): RecognitionResult {
                    frames.addAll(samples.take(count))
                    return RecognitionResult(engineId, "increase", false, 1, 2)
                }
                override fun finish() = RecognitionResult(engineId, transcript, true, 1, 3)
                override fun close() { events += "close:$engineId" }
            }
        }
        override fun stop() { events += "stop:$engineId" }
        override fun release() { events += "release:$engineId"; status.value = RecognitionStatus.Unloaded }
    }

    @Test fun defaultsAndOldConfigRemainVoskAndUnknownIdsFailValidation() {
        assertEquals("vosk", AssistantSettings().asrEngineId)
        val saved = org.json.JSONObject(ConfigurationCodec.encode(Configuration()))
        saved.getJSONObject("settings").remove("asrEngineId")
        assertEquals("vosk", ConfigurationCodec.decode(saved.toString()).settings.asrEngineId)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationValidator.validate(Configuration(settings = AssistantSettings(asrEngineId = "cloud")))
        }
        val sherpa = Configuration(settings = AssistantSettings(asrEngineId = "sherpa_onnx"))
        assertEquals(sherpa, ConfigurationCodec.decode(ConfigurationCodec.encode(sherpa)))
    }

    @Test fun onlySelectedModelLoadsAndSwitchReleasesOldBeforeNewAndNeverFallsBack() = runBlocking {
        val events = mutableListOf<String>()
        val vosk = Engine("vosk", events)
        val sherpa = Engine("sherpa_onnx", events)
        var selected = "vosk"
        val engine = SelectedSpeechEngine({ selected }, mapOf("vosk" to { vosk }, "sherpa_onnx" to { sherpa }), emptyMap())
        engine.initialize(); engine.initialize()
        assertEquals(1, vosk.initializations); assertEquals(0, sherpa.initializations)
        selected = "sherpa_onnx"
        engine.initialize()
        assertEquals(listOf("load:vosk", "release:vosk", "load:sherpa_onnx"), events)
        assertEquals("sherpa_onnx", engine.activeEngineId)
        selected = "unknown"
        val failure = runCatching { engine.initialize() }.exceptionOrNull() as RecognitionException
        assertEquals(RecognitionErrorCode.UNKNOWN_ENGINE, failure.error.code)
        assertNull(engine.activeEngineId)
        assertEquals(1, vosk.initializations)
        assertTrue(engine.status.value is RecognitionStatus.Failed)
        engine.release()
    }

    @Test fun switchingAndSecondSessionAreRejectedUntilFirstStreamCloses() = runBlocking {
        val events = mutableListOf<String>()
        var selected = "vosk"
        val engine = SelectedSpeechEngine({ selected }, AsrEngines.labels.keys.associateWith { id -> { Engine(id, events) } }, emptyMap())
        val stream = engine.openSession()
        selected = "sherpa_onnx"
        assertEquals(RecognitionErrorCode.BUSY, (runCatching { engine.initialize() }.exceptionOrNull() as RecognitionException).error.code)
        assertTrue(runCatching { engine.openSession() }.exceptionOrNull() is RecognitionException)
        assertTrue(runCatching { engine.release() }.isFailure)
        assertFalse(events.contains("load:sherpa_onnx"))
        stream.close()
        engine.initialize()
        assertTrue(events.indexOf("close:vosk") < events.indexOf("release:vosk"))
        assertTrue(events.indexOf("release:vosk") < events.indexOf("load:sherpa_onnx"))
        engine.release()
    }

    @Test fun unavailableSelectionReportsErrorAndCanRecoverWithoutLoadingOtherEngine() = runBlocking {
        val events = mutableListOf<String>()
        val sherpa = Engine("sherpa_onnx", events).apply { failure = true }
        val installer = Installer()
        val engine = SelectedSpeechEngine({ "sherpa_onnx" }, mapOf("sherpa_onnx" to { sherpa }), mapOf("sherpa_onnx" to installer))
        assertFalse(engine.isInstalled())
        assertEquals(RecognitionErrorCode.MISSING_MODEL, (runCatching { engine.initialize() }.exceptionOrNull() as RecognitionException).error.code)
        assertNull(engine.activeEngineId)
        engine.install(Uri.parse("content://models/selected.zip"))
        assertTrue(engine.isInstalled())
        sherpa.failure = false
        engine.initialize()
        assertEquals("sherpa_onnx", engine.activeEngineId)
        assertFalse(events.any { it.contains("vosk") })
        engine.release()
    }

    @Test fun bothRealAdaptersImplementStreamingContractAndMissingModelsFailBeforeNativeLoading() = runBlocking {
        val directory = Files.createTempDirectory("nexa-missing").toFile()
        val capture = object : CommandAudioCapture {
            override suspend fun capture(input: CommandAudioInput, onReady: () -> Unit, accept: (ShortArray, Int) -> Boolean) = error("No microphone may open")
            override fun stop() {}
        }
        try {
            val engines: List<SpeechRecognizerEngine> = listOf(
                VoskSpeechRecognizerEngine(File(directory, "missing-vosk"), capture = capture),
                SherpaSpeechRecognizerEngine(File(directory, "missing-sherpa"), capture))
            for (engine in engines) {
                val failure = runCatching { engine.initialize() }.exceptionOrNull() as RecognitionException
                assertEquals(RecognitionErrorCode.MISSING_MODEL, failure.error.code)
                assertEquals(engine.engineId, failure.error.engineId)
                assertTrue(engine.status.value is RecognitionStatus.Failed)
                engine.release()
                assertEquals(RecognitionStatus.Unloaded, engine.status.value)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun replayFeedsIdenticalPcmSequentiallyAndScoresRawTranscriptSeparatelyFromParameters() = runBlocking {
        val directory = Files.createTempDirectory("nexa-replay").toFile()
        try {
            val pcm = ShortArray(3201) { (it - 1000).toShort() }
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
            val file = File(directory, "volume.pcm").apply { writeBytes(bytes) }
            val events = mutableListOf<String>()
            val engines = mutableMapOf<String, Engine>()
            val runner = AsrEvaluationRunner({ id -> Engine(id, events, if (id == "vosk") "set volume to 30 percent" else "set volume to 40 percent").also { engines[id] = it } },
                com.nexaai.assistant.diagnostics.ResourceMetrics { throw SecurityException("Resource unavailable") })
            val report = runner.run(listOf(EvaluationSample("volume", "Set volume to thirty percent.", file, CommandId.SET_VOLUME, "30")),
                Configuration(), emptyList(), "vosk-model-small-en-in-0.4", "Synthetic unit test PCM; not an accuracy benchmark")
            assertEquals(pcm.toList(), engines.getValue("vosk").frames)
            assertEquals(pcm.toList(), engines.getValue("sherpa_onnx").frames)
            assertTrue(events.indexOf("release:vosk") < events.indexOf("load:sherpa_onnx"))
            val reports = Json.parseToJsonElement(report).jsonObject.getValue("engines").jsonArray
            val vosk = reports[0].jsonObject
            val sherpa = reports[1].jsonObject
            assertEquals(0, vosk.getValue("exactCount").jsonPrimitive.int)
            assertEquals(1, vosk.getValue("commandCorrectCount").jsonPrimitive.int)
            assertEquals(1, vosk.getValue("parameterCorrectCount").jsonPrimitive.int)
            assertEquals(1, sherpa.getValue("commandCorrectCount").jsonPrimitive.int)
            assertEquals(0, sherpa.getValue("parameterCorrectCount").jsonPrimitive.int)
            assertTrue(vosk.getValue("wer").jsonPrimitive.content.startsWith("Not measured"))
            assertEquals("Not measured", vosk.getValue("peakLoadMemory").jsonPrimitive.content)
            assertEquals("Not measured", vosk.getValue("processCpuMs").jsonPrimitive.content)
            assertTrue(AsrEvaluationRunner.summary(report).contains("parameters 0/1"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun wordErrorsUseDocumentedNormalizationWithoutNumberRewriting() {
        assertEquals("tune to 98.3 fm", EvaluationText.normalize("Tune to 98.3 FM."))
        assertEquals(0, EvaluationText.wordErrors("Increase volume.", "increase volume"))
        assertEquals(2, EvaluationText.wordErrors("set volume to thirty percent", "volume to 30 percent"))
        assertEquals(3, EvaluationText.wordErrors("seek next station", ""))
    }

    @Test fun sharedCaptureRejectsOverlapAndCancellationClosesDecoderBeforeModelRelease() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val capture = object : CommandAudioCapture {
            override suspend fun capture(input: CommandAudioInput, onReady: () -> Unit, accept: (ShortArray, Int) -> Boolean) {
                events += "microphone open"
                try { onReady(); accept(shortArrayOf(123), 1); started.complete(Unit); awaitCancellation() }
                finally { events += "microphone closed" }
            }
            override fun stop() { events += "stop" }
        }
        val engine = object : StreamingSpeechEngine(capture, { CommandAudioInput() }) {
            override val engineId = "vosk"
            override fun loadModel() { events += "load" }
            override fun unloadModel() { events += "unload" }
            override suspend fun createSession() = object : RecognitionSession {
                override fun acceptAudioFrame(samples: ShortArray, count: Int) = RecognitionResult(engineId, "increase", false, 1, 2)
                override fun finish() = RecognitionResult(engineId, "increase volume", true, 1, 3)
                override fun close() { events += "decoder closed" }
            }
        }
        val job = launch { engine.recognizeResult({}, {}) }
        started.await()
        assertTrue(runCatching { engine.openSession() }.exceptionOrNull() is RecognitionException)
        assertTrue(runCatching { engine.release() }.isFailure)
        job.cancelAndJoin()
        engine.release()
        assertTrue(events.indexOf("microphone closed") < events.indexOf("decoder closed"))
        assertTrue(events.indexOf("decoder closed") < events.indexOf("unload"))
    }

    @Test fun failedInitializationProducesRowsForBothEnginesRatherThanFakeSuccess() = runBlocking {
        val directory = Files.createTempDirectory("nexa-eval-error").toFile()
        try {
            val pcm = File(directory, "sample.pcm").apply { writeBytes(ByteArray(3200)) }
            val events = mutableListOf<String>()
            val report = AsrEvaluationRunner({ id -> Engine(id, events).apply { failure = true } })
                .run(listOf(EvaluationSample("sample", "increase volume", pcm, CommandId.VOLUME_UP)), Configuration(), emptyList(), "declared-model", "unit test")
            val engines = Json.parseToJsonElement(report).jsonObject.getValue("engines").jsonArray
            assertEquals(2, engines.size)
            engines.forEach { element ->
                val engine = element.jsonObject
                assertEquals(1, engine.getValue("errors").jsonPrimitive.int)
                assertEquals(0, engine.getValue("commandCorrectCount").jsonPrimitive.int)
                assertEquals("MISSING_MODEL", engine.getValue("initializationError").jsonPrimitive.content)
                assertEquals("Not measured", engine.getValue("initializationMs").jsonPrimitive.content)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun validArchiveDeliversConsentedPcmAndDeletesStagingAfterReplay() = runBlocking {
        val directory = Files.createTempDirectory("nexa-eval-valid").toFile()
        try {
            val manifest = """{"schemaVersion":1,"consentConfirmed":true,"sampleRate":16000,"channels":1,"encoding":"PCM_S16LE","provenance":"unit test consent fixture","voskModel":"test-model","samples":[{"id":"help","reference":"help","expectedCommandId":"HELP"}]}"""
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    for ((name, data) in mapOf("corpus.json" to manifest.toByteArray(), "help.pcm" to ByteArray(3200))) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                    }
                }
            }.toByteArray()
            EvaluationArchive.read(ByteArrayInputStream(bytes), directory) { samples, model, provenance ->
                assertEquals(1, samples.size)
                assertEquals(3200L, samples.single().pcm.length())
                assertEquals(CommandId.HELP, samples.single().expectedCommandId)
                assertEquals("test-model", model)
                assertTrue(provenance.contains("unit test"))
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun archiveRequiresConsentRejectsTraversalAndDeletesStagingOnFailure() = runBlocking {
        fun archive(name: String, content: String): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }.toByteArray()
        val directory = Files.createTempDirectory("nexa-archive").toFile()
        try {
            for (bytes in listOf(archive("../escape.pcm", "x"), archive("corpus.json", """{"schemaVersion":1,"consentConfirmed":false}"""))) {
                assertTrue(runCatching { EvaluationArchive.read(ByteArrayInputStream(bytes), directory) { _, _, _ -> error("Must reject") } }.isFailure)
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }
}