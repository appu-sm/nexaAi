package com.nexaai.assistant

import com.nexaai.assistant.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class VoiceDiagnosticsTest {
    @Test fun optionalBoundedOrderedAndResettable() {
        var now = 100L
        val metrics = VoiceDiagnostics(MonotonicClock { now }, 2)
        metrics.record(VoiceEvent.SESSION_START)
        assertTrue(metrics.snapshot().isEmpty())
        metrics.setEnabled(true)
        metrics.record(VoiceEvent.SESSION_START)
        now += 25
        metrics.record(VoiceEvent.DETECTOR_READY)
        now += 50
        metrics.record(VoiceEvent.ERROR, DiagnosticCode.WAKE)
        assertEquals(listOf(25L, 75L), metrics.snapshot().map { it.elapsedMs })
        assertEquals(1L, metrics.count(VoiceEvent.SESSION_START))
        assertEquals(1L, metrics.count(VoiceEvent.ERROR, DiagnosticCode.WAKE))
        assertEquals(0L, metrics.count(VoiceEvent.ERROR, DiagnosticCode.ASR))
        assertTrue(metrics.export().contains("\"droppedEvents\":1"))
        metrics.reset()
        assertTrue(metrics.snapshot().isEmpty())
        assertEquals(0L, metrics.count(VoiceEvent.ERROR, DiagnosticCode.WAKE))
        metrics.record(VoiceEvent.SESSION_START)
        assertEquals(0L, metrics.snapshot().single().elapsedMs)
        assertFalse(metrics.export().contains("transcript\":"))
    }

    @Test fun summariesDoNotInventPercentilesForSmallSamples() {
        assertEquals(LatencySummary(0, null, null), summarizeLatency(emptyList()))
        assertEquals(LatencySummary(2, 15.0, null), summarizeLatency(listOf(20, 10)))
        assertEquals(LatencySummary(20, 10.5, 19), summarizeLatency((1L..20).toList()))
    }

    @Test fun resourceProviderIsInjectableAndUnavailableFieldsAreOmitted() {
        val provider = ResourceMetrics { ResourceReading(10, 4, 1024, null, null, null, null) }
        val json = VoiceDiagnostics().export(provider.read())
        assertTrue(json.contains("\"pssKb\":1024"))
        assertFalse(json.contains("rssKb"))
        assertFalse(json.contains("batteryPercent"))
    }

    @Test fun durationsExcludeFailedCancelledAndManualWakePairs() {
        var now = 0L
        val metrics = VoiceDiagnostics(MonotonicClock { now }).apply { setEnabled(true) }
        fun event(event: VoiceEvent) { metrics.record(event); now += 10 }
        event(VoiceEvent.SERVICE_CREATED)
        event(VoiceEvent.SESSION_START)
        event(VoiceEvent.DETECTOR_READY)
        event(VoiceEvent.WAKE_DETECTED)
        event(VoiceEvent.ASR_ACTIVATION)
        event(VoiceEvent.ASR_READY)
        event(VoiceEvent.FINAL_TRANSCRIPT)
        event(VoiceEvent.COMMAND_RESULT)
        event(VoiceEvent.TTS_START)
        event(VoiceEvent.WAKE_DETECTED)
        event(VoiceEvent.CANCELLED)
        event(VoiceEvent.ASR_READY)
        event(VoiceEvent.WAKE_DETECTED)
        event(VoiceEvent.MANUAL_TRIGGER)
        event(VoiceEvent.ASR_READY)
        event(VoiceEvent.FINAL_TRANSCRIPT)
        event(VoiceEvent.TIMEOUT)
        event(VoiceEvent.COMMAND_RESULT)
        val summaries = voiceLatencies(metrics.snapshot())
        assertEquals(LatencySummary(1, 20.0, null), summaries["serviceCreateToDetectorReady"])
        assertEquals(LatencySummary(1, 20.0, null), summaries["wakeDetectionToAsrReady"])
        assertEquals(LatencySummary(1, 10.0, null), summaries["finalTranscriptToResult"])
        assertEquals(LatencySummary(1, 10.0, null), summaries["resultToTtsStart"])
    }
}