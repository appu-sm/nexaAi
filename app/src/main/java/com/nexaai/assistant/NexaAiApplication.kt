package com.nexaai.assistant

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexaai.assistant.data.ConfigurationRepository
import com.nexaai.assistant.data.LegacySettingsMigration
import com.nexaai.assistant.data.AppAllowlistRepository
import com.nexaai.assistant.execution.*
import com.nexaai.assistant.speech.AndroidSpeechOutput
import com.nexaai.assistant.speech.VoskModelInstaller
import com.nexaai.assistant.speech.VoskSpeechRecognizerEngine
import com.nexaai.assistant.speech.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.nexaai.assistant.data.ConfigurationSnapshot

class NexaAiApplication : Application() {
    val voiceDiagnostics = com.nexaai.assistant.diagnostics.VoiceDiagnostics(
        com.nexaai.assistant.diagnostics.MonotonicClock { android.os.SystemClock.elapsedRealtime() },
    )
    private val store by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler {
                mutablePreferencesOf(stringPreferencesKey("recovery_notice") to
                    "Preferences were corrupt. Safe defaults restored; no device actions were enabled.")
            },
            migrations = listOf(LegacySettingsMigration()),
            produceFile = { filesDir.resolve("nexa_configuration.preferences_pb") },
        )
    }
    val repository by lazy { ConfigurationRepository(store) }
    private val observedConfiguration by lazy {
        repository.snapshots.stateIn(voiceScope, SharingStarted.Eagerly, ConfigurationSnapshot())
    }
    val commandRuntime by lazy {
        CommandRuntime(repository, AppAllowlistRepository(store), AndroidAppDevice(this),
            VerifiedVolumeController(AndroidVolumeDevice(this)), SessionMediaController(AndroidMediaDevice(this)),
            VerifiedRadioController(AndroidRadioDevice(this)))
    }
    private val commandCapture by lazy { AndroidCommandAudioCapture(voiceDiagnostics) }
    private suspend fun commandInput(): CommandAudioInput {
        val snapshot = repository.snapshots.first { it.ready }
        check(snapshot.warning == null) { "Repair configuration before starting recognition." }
        return snapshot.configuration.settings.let { CommandAudioInput(it.commandAudioSource, it.commandInputGainPercent) }
    }
    fun createAsrEngine(id: String, evaluation: Boolean = false): SpeechRecognizerEngine = when (id) {
        AsrEngines.VOSK -> VoskSpeechRecognizerEngine(filesDir.resolve("vosk-model-small-en-us-0.15"), voiceDiagnostics,
            input = { commandInput() }, capture = commandCapture, grammar = {
            val snapshot = repository.snapshots.first { it.ready }
            check(snapshot.warning == null) { "Repair configuration before starting recognition." }
            if (evaluation || !snapshot.configuration.settings.grammarEnabled) null
            else VoskGrammar.build(snapshot.configuration, commandRuntime.allowlist.apps())
        })
        AsrEngines.SHERPA -> SherpaSpeechRecognizerEngine(filesDir.resolve(SherpaAsrModel.DIRECTORY), commandCapture) { commandInput() }
        else -> throw IllegalArgumentException("Unknown ASR engine")
    }
    val speechEngine by lazy {
        SelectedSpeechEngine({ observedConfiguration.value.configuration.settings.asrEngineId },
            AsrEngines.labels.keys.associateWith { id -> { createAsrEngine(id) } },
            mapOf(AsrEngines.VOSK to VoskModelInstaller(filesDir, contentResolver),
                AsrEngines.SHERPA to SherpaAsrModel.installer(filesDir, contentResolver))) {
            val snapshot = observedConfiguration.first { it.ready || it.warning != null }
            check(snapshot.ready && snapshot.warning == null) { "Repair configuration before starting recognition." }
        }
    }
    val speechModelInstaller get() = speechEngine
    suspend fun evaluateAsr(uri: android.net.Uri): String {
        val snapshot = repository.snapshots.first { it.ready }
        check(snapshot.warning == null) { "Repair configuration before evaluation." }
        val apps = commandRuntime.allowlist.apps()
        val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Could not open evaluation ZIP.")
        return input.use { source ->
            EvaluationArchive.read(source, cacheDir) { samples, model, provenance ->
                AsrEvaluationRunner({ createAsrEngine(it, evaluation = true) },
                    com.nexaai.assistant.diagnostics.AndroidResourceMetrics(this))
                    .run(samples, snapshot.configuration, apps, model, provenance)
            }
        }
    }
    val speechOutput by lazy { AndroidSpeechOutput(this, voiceDiagnostics) }
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val wakeModelInstaller by lazy { WakeModel.installer(filesDir, contentResolver) }
    val voiceCoordinator by lazy {
        SpeechSessionCoordinator(speechEngine, speechModelInstaller, commandRuntime.dispatcher, speechOutput,
            voiceScope, SherpaWakeWordDetector(filesDir.resolve(WakeModel.DIRECTORY), diagnostics = voiceDiagnostics), voiceDiagnostics) {
            repository.snapshots.first().configuration.settings.ttsEnabled
        }
    }
    val handsFreeControl by lazy { HandsFreeControl(this, repository, voiceCoordinator, voiceScope) }
    val overlayControl by lazy { com.nexaai.assistant.overlay.OverlayControl(this, repository, voiceScope) }
}