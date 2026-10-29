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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HandsFreeServiceTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun repeatedOverlayTapsAndMissingServiceStartupAreBounded() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences =
                error("A tap must not alter persisted wake settings")
        }
        val repository = ConfigurationRepository(store)
        val voice = voice(backgroundScope)
        var starts = 0
        val control = HandsFreeControl(context, repository, voice, backgroundScope, startCommandService = { starts++ })
        repeat(3) { control.listenOnce() }
        runCurrent()
        assertEquals(1, starts)
        assertTrue(control.commandPending.value)
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(control.commandPending.value)
        assertTrue(voice.state.value is VoiceState.Error)
        advanceTimeBy(60_000)
        assertEquals(1, starts)
        assertFalse(control.enabled.value)
        control.listenOnce()
        runCurrent()
        assertEquals(2, starts)
        control.commandStarted()
        advanceTimeBy(60_000)
        assertEquals(2, starts)
        assertFalse(control.commandPending.value)
    }

    @Test fun overlayCommandPermissionAndStartFailureDoNotEnableWakeWordOrOpenActivity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("overlay-command", ".preferences_pb").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            val voice = voice(scope)
            var starts = 0
            val control = HandsFreeControl(context, repository, voice, scope, startCommandService = {
                starts++
                throw SecurityException("Background microphone denied")
            })
            shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
            control.listenOnce()
            assertEquals(0, starts)
            assertTrue(voice.state.value is VoiceState.Error)
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            control.listenOnce()
            withTimeout(5_000) { control.commandPending.first { !it } }
            assertEquals(1, starts)
            assertFalse(control.enabled.value)
            assertFalse(repository.snapshots.first().configuration.settings.wakeWordEnabled)
            assertNull(shadowOf(context).nextStartedActivity)
            assertTrue(voice.state.value is VoiceState.Error)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }

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