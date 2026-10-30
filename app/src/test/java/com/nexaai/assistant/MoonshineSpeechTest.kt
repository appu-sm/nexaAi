package com.nexaai.assistant

import android.net.Uri
import com.nexaai.assistant.speech.*
import com.nexaai.assistant.data.*
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoonshineSpeechTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun requiresCompleteBaseEnglishModelIncludingLicense() {
        val directory = temporaryFolder.newFolder()
        assertFalse(MoonshineModel.isInstalled(directory))
        MoonshineModel.requiredFiles.forEach { File(directory, it).writeText("asset") }
        assertTrue(MoonshineModel.isInstalled(directory))
        File(directory, "decoder_model_merged.ort").writeText("")
        assertFalse(MoonshineModel.isInstalled(directory))
    }

    @Test fun exactV2ConfigurationUsesMergedDecoderAndOneCpuThread() {
        val directory = temporaryFolder.newFolder()
        val config = moonshineConfig(directory)
        assertEquals(File(directory, "encoder_model.ort").absolutePath, config.modelConfig.moonshine.encoder)
        assertEquals(File(directory, "decoder_model_merged.ort").absolutePath, config.modelConfig.moonshine.mergedDecoder)
        assertEquals("", config.modelConfig.moonshine.preprocessor)
        assertEquals("", config.modelConfig.moonshine.cachedDecoder)
        assertEquals(1, config.modelConfig.numThreads)
        assertEquals("cpu", config.modelConfig.provider)
    }

    @Test fun sentenceEndingsAreRemovedWithoutRewritingNumbersOrInternalPunctuation() {
        assertEquals("Volume up", normalizeMoonshineTranscript("  Volume up.  "))
        assertEquals("tune fm to 98.3", normalizeMoonshineTranscript("tune fm to 98.3."))
        assertEquals("set volume to fifty percent", normalizeMoonshineTranscript("set volume to fifty percent!"))
        assertEquals("open, clock", normalizeMoonshineTranscript("open, clock?"))
        assertEquals("", normalizeMoonshineTranscript("..."))
    }

    @Test fun configurationMigratesOldFilesAndRejectsUnknownEngines() {
        val config = Configuration(settings = AssistantSettings(asrEngineId = "moonshine_base_en"))
        assertEquals(config, ConfigurationCodec.decode(ConfigurationCodec.encode(config)))
        val old = JSONObject(ConfigurationCodec.encode(Configuration()))
        old.getJSONObject("settings").remove("asrEngineId")
        assertEquals("vosk", ConfigurationCodec.decode(old.toString()).settings.asrEngineId)
        old.getJSONObject("settings").put("asrEngineId", "cloud")
        assertThrows(ConfigurationException::class.java) { ConfigurationCodec.decode(old.toString()) }
    }

    @Test fun selectedEngineLoadsOnlyChosenModelAndReleasesBeforeSwitching() = runBlocking {
        val events = mutableListOf<String>()
        var selected = "moonshine_base_en"
        fun engine(id: String) = object : SpeechRecognizerEngine {
            override val recognitionTimeoutMillis: Long = 45_000
            override suspend fun initialize() { events += "$id initialize" }
            override suspend fun recognize(onPartialResult: (String) -> Unit): String = "volume up"
            override fun stop() {}
            override fun release() { events += "$id release" }
        }
        val wrapper = SelectedSpeechEngine({ selected }, { selected }, mapOf(
            "vosk" to { events += "vosk create"; engine("vosk") },
            "moonshine_base_en" to { events += "moonshine create"; engine("moonshine") },
        ), emptyMap())
        wrapper.initialize()
        wrapper.initialize()
        assertEquals(listOf("moonshine create", "moonshine initialize", "moonshine initialize"), events)
        assertEquals(45_000L, wrapper.recognitionTimeoutMillis)
        selected = "vosk"
        wrapper.initialize()
        assertEquals(listOf("moonshine release", "vosk create", "vosk initialize"), events.takeLast(3))
        wrapper.release()
    }

    @Test fun missingMoonshineFailsBeforeNativeOrMicrophoneAccess() = runBlocking {
        val capture = object : CommandAudioCapture {
            override suspend fun capture(onReady: () -> Unit, onFrame: (ShortArray, Int) -> Boolean) = error("No microphone expected")
            override fun stop() {}
        }
        val engine = MoonshineSpeechRecognizerEngine(temporaryFolder.newFolder(), capture) { error("No native access expected") }
        assertTrue(runCatching { engine.initialize() }.exceptionOrNull() is IOException)
    }

    @Test fun captureClosesBeforeDecodeAndPcmIsNormalizedUsingReadCount() = runBlocking {
        val events = mutableListOf<String>()
        val directory = installedModel()
        val capture = object : CommandAudioCapture {
            override suspend fun capture(onReady: () -> Unit, onFrame: (ShortArray, Int) -> Boolean) {
                onReady()
                assertTrue(onFrame(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE, 123), 3))
                events += "capture closed"
            }
            override fun stop() {}
        }
        val backend = object : MoonshineBackend {
            override fun openUtterance() = object : MoonshineUtterance {
                override fun accept(samples: FloatArray): Boolean {
                    assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768f), samples, 0f)
                    return true
                }
                override suspend fun finish(): String { events += "decode"; return "volume up" }
                override fun close() { events += "utterance closed" }
            }
            override fun close() { events += "model closed" }
        }
        var loads = 0
        val engine = MoonshineSpeechRecognizerEngine(directory, capture) { loads++; backend }
        engine.initialize()
        engine.initialize()
        assertEquals("volume up", engine.recognize({ events += "ready" }, { error("No partial expected") }, { events += "processing" }))
        engine.release()
        assertEquals(1, loads)
        assertEquals(listOf("ready", "capture closed", "processing", "decode", "utterance closed", "model closed"), events)
    }

    @Test fun cancellationDuringCaptureClosesUtteranceWithoutDecoding() = runBlocking {
        val capturing = CompletableDeferred<Unit>()
        var closed = false
        val capture = object : CommandAudioCapture {
            override suspend fun capture(onReady: () -> Unit, onFrame: (ShortArray, Int) -> Boolean) {
                onReady()
                capturing.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            override fun stop() {}
        }
        val backend = object : MoonshineBackend {
            override fun openUtterance() = object : MoonshineUtterance {
                override fun accept(samples: FloatArray) = false
                override suspend fun finish(): String = error("Cancelled capture must not decode")
                override fun close() { closed = true }
            }
            override fun close() {}
        }
        val engine = MoonshineSpeechRecognizerEngine(installedModel(), capture) { backend }
        engine.initialize()
        val job = launch { engine.recognize {} }
        capturing.await()
        job.cancelAndJoin()
        assertTrue(closed)
        engine.release()
    }

    @Test fun moonshineImportRejectsIncompleteAndUnsafeZipWithoutReplacingValidModel() = runBlocking {
        val root = temporaryFolder.newFolder()
        fun archive(files: List<String>): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                files.forEach {
                    zip.putNextEntry(ZipEntry(it))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }
            return bytes.toByteArray()
        }
        fun installer(files: List<String>) = VoskModelInstaller(root, MoonshineModel.DIRECTORY,
            MoonshineModel.requiredFiles, openArchive = { ByteArrayInputStream(archive(files)) })
        val valid = installer(MoonshineModel.requiredFiles.map { "base/$it" })
        valid.install(Uri.parse("content://local/base.zip"))
        assertTrue(valid.isInstalled())
        val incompleteFiles = MoonshineModel.requiredFiles - "silero_vad.onnx"
        for ((files, expectedMessage) in listOf(
            incompleteFiles to "Missing or empty required files: silero_vad.onnx.",
            incompleteFiles.map { "base/$it" } to "Missing or empty required files: silero_vad.onnx.",
            listOf("../escape") to "Model ZIP contains an unsafe path.",
        )) {
            val failure = runCatching { installer(files).install(Uri.parse("content://local/bad.zip")) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(expectedMessage, failure?.message)
            assertTrue(valid.isInstalled())
            assertFalse(root.listFiles()!!.any { it.name.startsWith("model-import-") })
        }
    }

    private fun installedModel(): File = temporaryFolder.newFolder().apply {
        MoonshineModel.requiredFiles.forEach { File(this, it).writeText("asset") }
    }
}