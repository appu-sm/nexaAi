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

class NexaAiApplication : Application() {
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
    val commandRuntime by lazy {
        CommandRuntime(repository, AppAllowlistRepository(store), AndroidAppDevice(this),
            VerifiedVolumeController(AndroidVolumeDevice(this)), SessionMediaController(AndroidMediaDevice(this)),
            VerifiedRadioController(AndroidRadioDevice(this)))
    }
    val speechEngine by lazy { VoskSpeechRecognizerEngine(filesDir.resolve("vosk-model-small-en-us-0.15")) }
    val speechModelInstaller by lazy { VoskModelInstaller(filesDir, contentResolver) }
    val speechOutput by lazy { AndroidSpeechOutput(this) }
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val wakeModelInstaller by lazy { WakeModel.installer(filesDir, contentResolver) }
    val voiceCoordinator by lazy {
        SpeechSessionCoordinator(speechEngine, speechModelInstaller, commandRuntime.dispatcher, speechOutput,
            voiceScope, SherpaWakeWordDetector(filesDir.resolve(WakeModel.DIRECTORY))) {
            repository.snapshots.first().configuration.settings.ttsEnabled
        }
    }
    val handsFreeControl by lazy { HandsFreeControl(this, repository, voiceCoordinator, voiceScope) }
}