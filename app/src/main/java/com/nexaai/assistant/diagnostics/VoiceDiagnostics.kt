package com.nexaai.assistant.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

fun interface MonotonicClock { fun millis(): Long }

enum class VoiceEvent {
    SERVICE_CREATED, SERVICE_RECREATED, SERVICE_START, SERVICE_DESTROYED, SESSION_START, SESSION_END,
    DETECTOR_READY, WAKE_CAPTURE_START, WAKE_CAPTURE_STOP, WAKE_DETECTED, MANUAL_TRIGGER,
    LISTENING_RESUMED, ASR_ACTIVATION, ASR_READY, ASR_CAPTURE_STOP, FINAL_TRANSCRIPT,
    COMMAND_DISPATCH, COMMAND_RESULT, TTS_START, TTS_COMPLETE, TTS_STOP,
    CANCELLED, ERROR, TIMEOUT, EMPTY_TRANSCRIPT, DUPLICATE_REQUEST,
}

enum class DiagnosticCode { NONE, WAKE, ASR, TTS, SERVICE, PERMISSION, SUCCESS, REJECTED, FAILED, CANCELLED }

data class VoiceMeasurement(val sequence: Long, val elapsedMs: Long, val event: VoiceEvent, val code: DiagnosticCode)

data class ResourceReading(
    val elapsedMs: Long,
    val processCpuMs: Long,
    val pssKb: Long?,
    val rssKb: Long?,
    val batteryPercent: Int?,
    val batteryTemperatureTenthsC: Int?,
    val thermalStatus: Int?,
)

fun interface ResourceMetrics { fun read(): ResourceReading }

data class LatencySummary(val count: Int, val medianMs: Double?, val p95Ms: Long?)

fun summarizeLatency(samples: List<Long>): LatencySummary {
    require(samples.all { it >= 0 })
    val sorted = samples.sorted()
    val count = sorted.size
    val median = if (count == 0) null else if (count % 2 == 1) sorted[count / 2].toDouble()
        else sorted[count / 2 - 1] / 2.0 + sorted[count / 2] / 2.0
    return LatencySummary(count, median, if (count < 20) null else sorted[(count * 95 + 99) / 100 - 1])
}

fun voiceLatencies(events: List<VoiceMeasurement>): Map<String, LatencySummary> {
    val samples = linkedMapOf<String, MutableList<Long>>()
    val pending = mutableMapOf<VoiceEvent, Long>()
    fun pair(start: VoiceEvent, end: VoiceMeasurement, name: String) {
        pending.remove(start)?.let { time ->
            val duration = end.elapsedMs - time
            if (duration >= 0) samples.getOrPut(name) { mutableListOf() }.add(duration)
        }
    }
    events.forEach { measurement ->
        when (measurement.event) {
            VoiceEvent.SERVICE_CREATED -> pending[VoiceEvent.SERVICE_CREATED] = measurement.elapsedMs
            VoiceEvent.DETECTOR_READY -> pair(VoiceEvent.SERVICE_CREATED, measurement, "serviceCreateToDetectorReady")
            VoiceEvent.WAKE_DETECTED -> pending[VoiceEvent.WAKE_DETECTED] = measurement.elapsedMs
            VoiceEvent.ASR_ACTIVATION -> {
                pending.remove(VoiceEvent.FINAL_TRANSCRIPT)
                pending.remove(VoiceEvent.COMMAND_RESULT)
            }
            VoiceEvent.ASR_READY -> pair(VoiceEvent.WAKE_DETECTED, measurement, "wakeDetectionToAsrReady")
            VoiceEvent.FINAL_TRANSCRIPT -> pending[VoiceEvent.FINAL_TRANSCRIPT] = measurement.elapsedMs
            VoiceEvent.COMMAND_RESULT -> {
                pair(VoiceEvent.FINAL_TRANSCRIPT, measurement, "finalTranscriptToResult")
                pending[VoiceEvent.COMMAND_RESULT] = measurement.elapsedMs
            }
            VoiceEvent.TTS_START -> pair(VoiceEvent.COMMAND_RESULT, measurement, "resultToTtsStart")
            VoiceEvent.SESSION_START -> pending.keys.retainAll(setOf(VoiceEvent.SERVICE_CREATED))
            VoiceEvent.MANUAL_TRIGGER, VoiceEvent.ERROR, VoiceEvent.TIMEOUT, VoiceEvent.CANCELLED,
            VoiceEvent.SESSION_END, VoiceEvent.SERVICE_DESTROYED -> pending.clear()
            else -> Unit
        }
    }
    return samples.mapValues { summarizeLatency(it.value) }
}

class VoiceDiagnostics(
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 512,
) {
    init { require(capacity in 1..4096) }
    @Volatile var enabled: Boolean = false
        private set
    private val events = ArrayDeque<VoiceMeasurement>()
    private val counters = mutableMapOf<Pair<VoiceEvent, DiagnosticCode>, Long>()
    private var sequence = 0L
    private var dropped = 0L
    private var origin = clock.millis()

    @Synchronized fun setEnabled(value: Boolean) { enabled = value }

    @Synchronized fun record(event: VoiceEvent, code: DiagnosticCode = DiagnosticCode.NONE) {
        if (!enabled) return
        val key = event to code
        counters[key] = (counters[key] ?: 0L) + 1
        if (events.size == capacity) { events.removeFirst(); dropped++ }
        events.addLast(VoiceMeasurement(++sequence, clock.millis() - origin, event, code))
    }

    @Synchronized fun snapshot(): List<VoiceMeasurement> = events.toList()
    @Synchronized fun count(event: VoiceEvent, code: DiagnosticCode = DiagnosticCode.NONE): Long = counters[event to code] ?: 0

    @Synchronized fun reset() {
        events.clear()
        counters.clear()
        sequence = 0
        dropped = 0
        origin = clock.millis()
    }

    @Synchronized fun export(resource: ResourceReading? = null): String = buildJsonObject {
        put("schemaVersion", 1)
        put("scope", "application-observed events/process metrics; battery/thermal device-wide; no audio or transcripts")
        put("enabled", enabled)
        put("elapsedMs", clock.millis() - origin)
        put("droppedEvents", dropped)
        put("latencyScope", "retained complete event pairs only; cold start separate; p95 requires >=20; acoustic endpoints unavailable")
        put("latencies", buildJsonObject {
            voiceLatencies(events.toList()).forEach { (name, summary) -> put(name, buildJsonObject {
                put("count", summary.count)
                summary.medianMs?.let { put("medianMs", it) }
                summary.p95Ms?.let { put("p95Ms", it) }
            }) }
        })
        put("events", buildJsonArray {
            events.forEach { measurement -> add(buildJsonObject {
                put("sequence", measurement.sequence)
                put("elapsedMs", measurement.elapsedMs)
                put("event", measurement.event.name)
                put("code", measurement.code.name)
            }) }
        })
        put("counters", buildJsonObject {
            counters.forEach { (key, count) -> put("${key.first.name}:${key.second.name}", count) }
        })
        resource?.let { reading -> put("resourceSnapshot", buildJsonObject {
            put("elapsedRealtimeMs", reading.elapsedMs)
            put("processCpuMs", reading.processCpuMs)
            reading.pssKb?.let { put("pssKb", it) }
            reading.rssKb?.let { put("rssKb", it) }
            reading.batteryPercent?.let { put("batteryPercent", it) }
            reading.batteryTemperatureTenthsC?.let { put("batteryTemperatureTenthsC", it) }
            reading.thermalStatus?.let { put("thermalStatus", it) }
        }) }
    }.toString()
}