package com.nexaai.assistant

import android.net.Uri
import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import com.nexaai.assistant.execution.*
import com.nexaai.assistant.speech.*
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionTest {
    private class FakeEngine(var finalText: String? = null) : SpeechRecognizerEngine {
        var initializeFailure: Exception? = null
        var initializes = 0
        var stopped = false
        var releases = 0
        var partial: ((String) -> Unit)? = null
        val final = CompletableDeferred<String>()
        var completeWhenStopped = false

        override suspend fun initialize() {
            initializes++
            initializeFailure?.let { throw it }
        }

        override suspend fun recognize(onPartialResult: (String) -> Unit): String {
            partial = onPartialResult
            finalText?.let { final.complete(it) }
            return final.await()
        }

        override fun stop() {
            stopped = true
            if (completeWhenStopped && !final.isCompleted) final.complete(finalText.orEmpty())
        }

        override fun release() { releases++ }
    }

    private class FakeInstaller(var installed: Boolean = true) : SpeechModelInstaller {
        var installFailure: Exception? = null
        override fun isInstalled() = installed
        override suspend fun install(uri: Uri) {
            installFailure?.let { throw it }
            installed = true
        }
    }

    private class FakeOutput(initial: SpeechOutputState = SpeechOutputState.Ready) : SpeechOutput {
        override val state = MutableStateFlow(initial)
        val spoken = mutableListOf<String>()
        var stopped = false
        var closed = false
        override fun speak(text: String) { spoken += text }
        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }

    private class Controller : VolumeController, AppLauncher, MediaController, RadioController {
        override var band: FmBand? = null
        var checkResult: CommandResult? = null
        var result: CommandResult = CommandResult.Success("Volume set to 30 percent")
        val commands = mutableListOf<AssistantCommand>()
        override suspend fun check(command: AssistantCommand) = checkResult
        override suspend fun execute(command: AssistantCommand): CommandResult {
            commands += command
            return result
        }
    }

    private fun dispatcher(controller: Controller, configuration: Configuration = Configuration()) = CommandDispatcher(
        configuration = { ExecutionConfiguration(ConfigurationSnapshot(configuration, ready = true), emptyList()) },
        volume = controller, apps = controller, media = controller, radio = controller,
    )

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        engine: FakeEngine,
        controller: Controller,
        output: FakeOutput = FakeOutput(),
        installer: FakeInstaller = FakeInstaller(),
        config: Configuration = Configuration(),
        speak: Boolean = true,
    ) = SpeechSessionCoordinator(engine, installer, dispatcher(controller, config), output, scope) { speak }

    @Test fun partialResultsNeverExecuteAndOnlyOneFinalSubmissionRuns() = runTest {
        val engine = FakeEngine()
        engine.completeWhenStopped = true
        val controller = Controller()
        val output = FakeOutput()
        val voice = coordinator(this, engine, controller, output)

        assertTrue(voice.start())
        runCurrent()
        assertFalse(voice.start())
        engine.finalText = "volume up"
        engine.partial?.invoke("volume up")
        assertTrue(voice.state.value is VoiceState.Listening)
        assertTrue(controller.commands.isEmpty())

        voice.finish()
        runCurrent()
        assertEquals(1, controller.commands.size)
        assertEquals(CommandId.VOLUME_UP, controller.commands.single().id)
        assertEquals("Volume set to 30 percent", output.spoken.single())
        assertTrue(voice.state.value is VoiceState.Completed)
    }

    @Test fun cancellationStopsRecognitionAndPreventsDispatch() = runTest {
        val engine = FakeEngine()
        val controller = Controller()
        val voice = coordinator(this, engine, controller)
        voice.start()
        runCurrent()
        voice.cancel()
        runCurrent()
        assertTrue(engine.stopped)
        assertTrue(controller.commands.isEmpty())
        assertTrue(voice.state.value is VoiceState.Idle)
    }

    @Test fun permissionGrantStartsRecognitionAndDenialDoesNotStartIt() = runTest {
        val deniedEngine = FakeEngine()
        val deniedVoice = coordinator(this, deniedEngine, Controller())
        deniedVoice.microphonePermissionDenied(permanentlyDenied = false)
        assertTrue((deniedVoice.state.value as VoiceState.Error).message.contains("permission denied", true))
        assertEquals(0, deniedEngine.initializes)

        val grantedEngine = FakeEngine("volume up")
        val controller = Controller()
        val grantedVoice = coordinator(this, grantedEngine, controller)
        assertTrue(grantedVoice.start())
        runCurrent()
        assertEquals(1, grantedEngine.initializes)
        assertEquals(1, controller.commands.size)
    }

    @Test fun emptyTranscriptAndInitializationFailureRemainOnScreenWithoutDispatch() = runTest {
        val emptyEngine = FakeEngine("")
        val controller = Controller()
        val emptyVoice = coordinator(this, emptyEngine, controller)
        emptyVoice.start()
        runCurrent()
        assertTrue(emptyVoice.state.value is VoiceState.Error)
        assertTrue(controller.commands.isEmpty())

        val brokenEngine = FakeEngine().apply { initializeFailure = IllegalStateException("Invalid model") }
        val brokenVoice = coordinator(this, brokenEngine, controller)
        brokenVoice.start()
        runCurrent()
        assertTrue(brokenVoice.state.value is VoiceState.Error)
        assertTrue((brokenVoice.state.value as VoiceState.Error).message.contains("Invalid model"))
        assertTrue(controller.commands.isEmpty())
    }

    @Test fun unknownDisabledAmbiguousAndInvalidParametersNeverDispatch() = runTest {
        val controller = Controller()
        val unknownEngine = FakeEngine("turn on the windshield")
        val unknownVoice = coordinator(this, unknownEngine, controller)
        unknownVoice.start()
        runCurrent()
        assertTrue((unknownVoice.state.value as VoiceState.Completed).result is CommandResult.InvalidParameters)
        assertTrue(controller.commands.isEmpty())

        val disabledConfig = Configuration(commands = CommandCatalogue.defaults().map {
            if (it.id == CommandId.VOLUME_UP) it.copy(enabled = false) else it
        })
        val disabledVoice = coordinator(this, FakeEngine("volume up"), controller, config = disabledConfig)
        disabledVoice.start()
        runCurrent()
        assertTrue((disabledVoice.state.value as VoiceState.Completed).result.message.contains("disabled", true))

        val invalidVolume = dispatcher(controller).prepare("set volume to 101 percent") as Preparation.Rejected
        assertTrue(invalidVolume.result is CommandResult.InvalidParameters)
        controller.band = FmBand(BigDecimal("87.5"), BigDecimal("108.0"), BigDecimal("0.1"))
        val invalidRadio = dispatcher(controller).prepare("tune fm to 120.0") as Preparation.Rejected
        assertTrue(invalidRadio.result is CommandResult.InvalidParameters)

        val ambiguousConfig = Configuration(commands = CommandCatalogue.defaults().map {
            if (it.id == CommandId.VOLUME_UP) it.copy(aliases = it.aliases + "go home") else it
        })
        val ambiguous = dispatcher(controller, ambiguousConfig).prepare("go home") as Preparation.Rejected
        assertTrue(ambiguous.result.message.contains("ambiguous", true))
        assertTrue(controller.commands.isEmpty())
    }

    @Test fun verifiedRadioControllerUsesTheExistingDispatcherAndReportsFailureTruthfully() = runTest {
        val controller = Controller().apply {
            band = FmBand(BigDecimal("87.5"), BigDecimal("108.0"), BigDecimal("0.1"))
            result = CommandResult.Success("Station seek request sent")
        }
        val output = FakeOutput()
        val voice = coordinator(this, FakeEngine("next station"), controller, output)
        voice.start()
        runCurrent()
        assertEquals(AssistantCommand.Action(CommandId.SEEK_NEXT), controller.commands.single())
        assertEquals("Station seek request sent", output.spoken.single())

        controller.commands.clear()
        controller.checkResult = CommandResult.Unsupported("Radio unavailable")
        val unsupportedOutput = FakeOutput()
        val unsupported = coordinator(this, FakeEngine("next station"), controller, unsupportedOutput)
        unsupported.start()
        runCurrent()
        assertTrue(controller.commands.isEmpty())
        assertEquals("That operation is not supported on this device.", unsupportedOutput.spoken.single())

        controller.checkResult = null
        controller.result = CommandResult.Failed("Controller rejected request", "RADIO_FAILURE")
        val failedOutput = FakeOutput()
        val failed = coordinator(this, FakeEngine("next station"), controller, failedOutput)
        failed.start()
        runCurrent()
        assertEquals("The operation failed.", failedOutput.spoken.single())
    }

    @Test fun ttsDisabledOrUnavailableKeepsTypedScreenResultWithoutSpeaking() = runTest {
        val controller = Controller()
        val disabledOutput = FakeOutput()
        val disabled = coordinator(this, FakeEngine("volume up"), controller, disabledOutput, speak = false)
        disabled.start()
        runCurrent()
        assertTrue(disabled.state.value is VoiceState.Completed)
        assertTrue(disabledOutput.spoken.isEmpty())

        val unavailableOutput = FakeOutput(SpeechOutputState.Unavailable("No offline voice"))
        val unavailable = coordinator(this, FakeEngine("volume up"), controller, unavailableOutput)
        unavailable.start()
        runCurrent()
        assertTrue(unavailable.state.value is VoiceState.Completed)
        assertTrue(unavailableOutput.spoken.isEmpty())
    }

    @Test fun missingVoskModelFailsClearlyAndLifecycleReleasesResources() = runTest {
        val missing = VoskSpeechRecognizerEngine(File("/path/that/does/not/exist"))
        val failure = runCatching { missing.initialize() }.exceptionOrNull()
        assertTrue(failure?.message?.contains("missing", true) == true)

        val engine = FakeEngine()
        val output = FakeOutput()
        val voice = coordinator(this, engine, Controller(), output)
        val released = CountDownLatch(1)
        val releaseEngine = object : SpeechRecognizerEngine by engine {
            override fun release() { engine.release(); released.countDown() }
        }
        val releasable = SpeechSessionCoordinator(releaseEngine, FakeInstaller(), dispatcher(Controller()), output, this) { false }
        releasable.close()
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertTrue(output.stopped)
        assertTrue(output.closed)
        assertTrue(voice.state.value is VoiceState.Idle)
    }
}