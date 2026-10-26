package com.nexaai.assistant

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import com.nexaai.assistant.execution.*
import com.nexaai.assistant.speech.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HandsFreeServiceTest {
    private fun voice(scope: CoroutineScope): SpeechSessionCoordinator {
        val controller = object : VolumeController, AppLauncher, MediaController {
            override suspend fun check(command: AssistantCommand): CommandResult? = null
            override suspend fun execute(command: AssistantCommand) = CommandResult.Success("Request sent")
        }
        val dispatcher = CommandDispatcher({ ExecutionConfiguration(ConfigurationSnapshot(ready = true), emptyList()) },
            controller, controller, controller, UnsupportedRadioController())
        val engine = object : SpeechRecognizerEngine {
            override suspend fun initialize() { error("Must not start capture") }
            override suspend fun recognize(onPartialResult: (String) -> Unit): String = error("Must not capture")
            override fun stop() {}
            override fun release() {}
        }
        val installer = object : SpeechModelInstaller {
            override fun isInstalled() = true
            override suspend fun install(uri: Uri) {}
        }
        val output = object : SpeechOutput {
            override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
            override fun speak(text: String) {}
            override fun stop() {}
            override fun close() {}
        }
        return SpeechSessionCoordinator(engine, installer, dispatcher, output, scope) { false }
    }

    @Test fun permissionDenialAndForegroundStartFailureNeverActivateCapture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("hands-free", ".preferences_pb").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            val voice = voice(scope)
            var starts = 0
            val control = HandsFreeControl(context, repository, voice, scope) {
                starts++
                throw SecurityException("Foreground service denied")
            }
            shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
            control.enable()
            assertFalse(control.enabled.value)
            assertEquals(0, starts)
            assertTrue((voice.state.value as VoiceState.Error).message.contains("permission"))

            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            control.enable()
            withTimeout(5000) { control.enabled.first { !it } }
            assertEquals(1, starts)
            assertTrue((voice.state.value as VoiceState.Error).message.contains("Foreground service denied"))
            assertFalse(voice.handsFreeEnabled.value)
            control.disable()
            withTimeout(5000) { repository.snapshots.first { it.ready && !it.configuration.settings.wakeWordEnabled } }
            assertEquals("PUSH_TO_TALK", repository.snapshots.first().configuration.settings.listeningMode)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}