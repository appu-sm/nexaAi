package com.nexaai.assistant

import com.nexaai.assistant.speech.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeWordDetectorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun model(): File = temporary.newFolder().apply {
        WakeModel.files.forEach { resolve(it).writeText("model") }
        resolve("tokens.txt").writeText("\u2581HEY 1\n\u2581NEXA 2\n")
        resolve("keywords.txt").writeText("\u2581HEY \u2581NEXA\n")
    }

    @Test fun missingMismatchedUnsupportedAndMalformedKeywordModelsFailClosed() = runBlocking {
        assertTrue(runCatching { SherpaWakeWordDetector(temporary.newFolder()).initialize("Hey Nexa") }.isFailure)
        val directory = model()
        assertTrue(runCatching { WakeModel.validate(directory, "Hey Radio") }.isFailure)
        directory.resolve("keywords.txt").writeText("\u2581HEY\n")
        assertTrue(runCatching { WakeModel.validate(directory, "Hey Nexa") }.isFailure)
        directory.resolve("keywords.txt").writeText("\u2581HEY \u2581NEXA\n\u2581HEY\n")
        assertTrue(runCatching { WakeModel.validate(directory) }.isFailure)
        directory.resolve("keywords.txt").writeText("\u2581HEY \u2581NEXA :100\n")
        assertTrue(runCatching { WakeModel.validate(directory) }.isFailure)
    }

    @Test fun customKeywordImportsAndOnlyMatchingConfiguredPhraseCanInitialize() = runBlocking {
        val directory = model()
        directory.resolve("tokens.txt").appendText("\u2581RADIO 3\n")
        directory.resolve("keywords.txt").writeText("\u2581HEY \u2581RADIO\n")
        WakeModel.validate(directory)
        WakeModel.validate(directory, "Hey Radio")
        WakeModel.validate(directory, "hey radio")
        assertTrue(runCatching { WakeModel.validate(directory, "Hey Nexa") }.isFailure)
        directory.resolve("keywords.txt").writeText("\u2581HEY :100\n")
        assertTrue(runCatching { WakeModel.validate(directory) }.isFailure)
    }

    @Test fun modelLoadsOnceAndOrdinaryFramesDoNotTriggerUntilKeywordInferenceMatches() = runBlocking {
        var loads = 0
        var frames = 0
        var captures = 0
        var closes = 0
        var released = 0
        var reusableSamples: FloatArray? = null
        val detector = SherpaWakeWordDetector(model(), {
            captures++
            object : WakeAudioSource {
                override fun read(samples: ShortArray, offset: Int, count: Int): Int {
                    samples.fill(100, offset, offset + count)
                    return count
                }
                override fun stop() {}
                override fun close() { closes++ }
            }
        }, {
            loads++
            object : KeywordInference {
                override fun accept(samples: FloatArray): Boolean {
                    reusableSamples?.let { assertSame(it, samples) }
                    reusableSamples = samples
                    assertEquals(100f / 32768, samples.first())
                    return ++frames == 3
                }
                override fun close() { released++ }
            }
        })
        detector.initialize("Hey Nexa")
        detector.initialize("Hey Nexa")
        assertEquals(1, loads)
        assertEquals(0, captures)
        detector.awaitDetection()
        assertEquals(3, frames)
        assertEquals(1, captures)
        assertEquals(1, closes)
        detector.release()
        detector.release()
        assertEquals(1, released)
    }

    @Test fun incompatibleNativeModelAndAbiBecomeUsefulErrors() = runBlocking {
        for (failure in listOf(IOException("bad model"), UnsatisfiedLinkError("bad ABI"))) {
            val detector = SherpaWakeWordDetector(model(), inferenceFactory = { throw failure })
            val result = runCatching { detector.initialize("Hey Nexa") }.exceptionOrNull()
            assertTrue(result is IOException)
            assertTrue(result!!.message!!.contains("incompatible"))
        }
    }

    @Test fun recorderReadFailureClosesCaptureWithoutReportingDetection() = runBlocking {
        var closed = false
        var inferenceCalls = 0
        val detector = SherpaWakeWordDetector(model(), {
            object : WakeAudioSource {
                override fun read(samples: ShortArray, offset: Int, count: Int) = -3
                override fun stop() {}
                override fun close() { closed = true }
            }
        }, {
            object : KeywordInference {
                override fun accept(samples: FloatArray): Boolean { inferenceCalls++; return true }
                override fun close() {}
            }
        })
        detector.initialize("Hey Nexa")
        assertTrue(runCatching { detector.awaitDetection() }.exceptionOrNull() is IOException)
        assertTrue(closed)
        assertEquals(0, inferenceCalls)
        detector.release()
    }
}