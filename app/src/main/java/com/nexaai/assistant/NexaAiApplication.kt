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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val configurationState by lazy {
        repository.snapshots.stateIn(voiceScope, SharingStarted.Eagerly, com.nexaai.assistant.data.ConfigurationSnapshot())
    }
    private val commandCapture by lazy {
        AndroidCommandAudioCapture(voiceDiagnostics) {
            val snapshot = repository.snapshots.first { it.ready }
            check(snapshot.warning == null) { "Repair configuration before starting recognition." }
            snapshot.configuration.settings.let { CommandAudioInput(it.commandAudioSource, it.commandInputGainPercent) }
        }
    }
    val commandRuntime by lazy {
        CommandRuntime(repository, AppAllowlistRepository(store), AndroidAppDevice(this),
            VerifiedVolumeController(AndroidVolumeDevice(this)), SessionMediaController(AndroidMediaDevice(this)),
            VerifiedRadioController(AndroidRadioDevice(this)))
    }
    private fun createVoskEngine(): SpeechRecognizerEngine =
        VoskSpeechRecognizerEngine(filesDir.resolve("vosk-model-small-en-us-0.15"), voiceDiagnostics, capture = commandCapture) {
            val snapshot = repository.snapshots.first { it.ready }
            check(snapshot.warning == null) { "Repair configuration before starting recognition." }
            if (!snapshot.configuration.settings.grammarEnabled) null
            else VoskGrammar.build(snapshot.configuration, commandRuntime.allowlist.apps())
        }
    val speechEngine by lazy {
        SelectedSpeechEngine(
            selectedId = {
                val snapshot = repository.snapshots.first { it.ready }
                check(snapshot.warning == null) { "Repair configuration before starting recognition." }
                snapshot.configuration.settings.asrEngineId
            },
            currentId = { configurationState.value.configuration.settings.asrEngineId },
            factories = mapOf(
                "vosk" to ::createVoskEngine,
                "moonshine_base_en" to { MoonshineSpeechRecognizerEngine(filesDir.resolve(MoonshineModel.DIRECTORY), commandCapture) },
            ),
            installers = mapOf(
                "vosk" to VoskModelInstaller(filesDir, contentResolver),
                "moonshine_base_en" to MoonshineModel.installer(filesDir, contentResolver),
            ),
        )
    }
    val speechModelInstaller get() = speechEngine
    val speechOutput by lazy { AndroidSpeechOutput(this, voiceDiagnostics) }
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val wakeModelInstaller by lazy { WakeModel.installer(filesDir, contentResolver) }
    val voiceCoordinator by lazy {
        SpeechSessionCoordinator(speechEngine, speechModelInstaller, commandRuntime.dispatcher, speechOutput,
            voiceScope, SherpaWakeWordDetector(filesDir.resolve(WakeModel.DIRECTORY), diagnostics = voiceDiagnostics), voiceDiagnostics) {
            repository.snapshots.first().configuration.settings.ttsEnabled
        }.also { coordinator ->
            voiceScope.launch { configurationState.collect { coordinator.refreshModelStatus() } }
        }
    }
    val handsFreeControl by lazy { HandsFreeControl(this, repository, voiceCoordinator, voiceScope) }
    val overlayControl by lazy { com.nexaai.assistant.overlay.OverlayControl(this, repository, voiceScope) }
}