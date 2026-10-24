package com.nexaai.assistant

import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import com.nexaai.assistant.execution.*
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CommandExecutionTest {
    private class FakeController : VolumeController, AppLauncher, MediaController, RadioController {
        override var band: FmBand? = null
        var calls = 0
        var denied: CommandResult? = null
        var outcome: CommandResult = CommandResult.Success("done")
        var throws = false
        override suspend fun check(command: AssistantCommand) = denied
        override suspend fun execute(command: AssistantCommand): CommandResult {
            calls++
            if (throws) throw IllegalStateException("OEM failure")
            return outcome
        }
    }
    private val fake = FakeController()
    private var environment = ExecutionConfiguration(ConfigurationSnapshot(ready = true), emptyList())
    private fun dispatcher(radio: RadioController = fake) = CommandDispatcher({ environment }, fake, fake, fake, radio)

    @Test fun invalidDisabledUnknownAndUnsupportedNeverExecute() = runBlocking {
        val engine = dispatcher(UnsupportedRadioController())
        assertTrue(engine.dispatch(AssistantCommand.SetVolume(-1)) is CommandResult.InvalidParameters)
        assertTrue(engine.dispatch(AssistantCommand.SetVolume(101)) is CommandResult.InvalidParameters)
        assertTrue(engine.dispatch(AssistantCommand.Action(CommandId.SET_VOLUME)) is CommandResult.InvalidParameters)
        assertTrue(engine.dispatch(AssistantCommand.LaunchApp("untrusted.package")) is CommandResult.InvalidParameters)
        assertTrue(engine.dispatch(AssistantCommand.TuneFm(BigDecimal("98.1"))) is CommandResult.Unsupported)
        assertTrue(engine.dispatch(AssistantCommand.TuneFm(BigDecimal("-1"))) is CommandResult.InvalidParameters)
        environment = environment.copy(snapshot = environment.snapshot.copy(configuration = Configuration(commands = Configuration().commands.map { it.copy(enabled = false) })))
        assertTrue(engine.dispatch(AssistantCommand.SetVolume(30)) is CommandResult.InvalidParameters)
        assertEquals(0, fake.calls)
    }

    @Test fun validatesVerifiedFrequencyBandAndStepBeforeCallingRadio() = runBlocking {
        assertTrue(dispatcher().dispatch(AssistantCommand.TuneFm(BigDecimal("98.1"))) is CommandResult.Unsupported)
        assertEquals(0, fake.calls)
        fake.band = FmBand(BigDecimal("87.5"), BigDecimal("108"), BigDecimal("0.1"))
        listOf("87.4", "108.1", "98.15").forEach {
            assertTrue(dispatcher().dispatch(AssistantCommand.TuneFm(BigDecimal(it))) is CommandResult.InvalidParameters)
        }
        assertEquals(0, fake.calls)
        assertTrue(dispatcher().dispatch(AssistantCommand.TuneFm(BigDecimal("98.1"))) is CommandResult.Success)
    }

    @Test fun permissionAndExecutionFailuresAreTruthful() = runBlocking {
        val engine = dispatcher()
        fake.denied = CommandResult.PermissionRequired("media_access", "Grant access")
        assertEquals(fake.denied, engine.dispatch(AssistantCommand.Action(CommandId.PLAY)))
        assertEquals(0, fake.calls)
        fake.denied = null
        fake.outcome = CommandResult.Failed("No action", "OEM_FAILURE")
        assertEquals(fake.outcome, engine.dispatch(AssistantCommand.SetVolume(30)))
        fake.throws = true
        assertTrue(engine.dispatch(AssistantCommand.SetVolume(30)) is CommandResult.Failed)
    }

    @Test fun cancelledRepeatedAndStalePreparationsNeverExecute() = runBlocking {
        val engine = dispatcher()
        val cancelled = (engine.prepare("louder") as Preparation.Ready).pending
        cancelled.cancel()
        assertEquals(CommandResult.Cancelled, engine.execute(cancelled))
        val stale = (engine.prepare("louder") as Preparation.Ready).pending
        environment = environment.copy(snapshot = environment.snapshot.copy(configuration = Configuration(commands = Configuration().commands.map {
            if (it.id == CommandId.VOLUME_UP) it.copy(enabled = false) else it
        })))
        assertTrue(engine.execute(stale) is CommandResult.InvalidParameters)
        assertEquals(0, fake.calls)
        environment = environment.copy(snapshot = ConfigurationSnapshot(ready = true))
        val once = (engine.prepare("louder") as Preparation.Ready).pending
        assertTrue(engine.execute(once) is CommandResult.Success)
        assertEquals(CommandResult.Cancelled, engine.execute(once))
        assertEquals(1, fake.calls)
    }

    @Test fun assistantAndCorruptConfigurationBlockActions() = runBlocking {
        environment = environment.copy(snapshot = ConfigurationSnapshot(ready = true, warning = "corrupt"))
        assertTrue(dispatcher().dispatch(AssistantCommand.SetVolume(30)) is CommandResult.Failed)
        environment = environment.copy(snapshot = ConfigurationSnapshot(Configuration(settings = AssistantSettings(assistantEnabled = false)), ready = true))
        assertTrue(dispatcher().dispatch(AssistantCommand.SetVolume(30)) is CommandResult.InvalidParameters)
        assertEquals(0, fake.calls)
    }

    @Test fun cancelCommandInvalidatesPendingInterpretation() = runBlocking {
        val engine = dispatcher()
        val first = (engine.prepare("louder") as Preparation.Ready).pending
        assertEquals(CommandResult.Cancelled, engine.dispatch(AssistantCommand.Action(CommandId.CANCEL)))
        assertEquals(CommandResult.Cancelled, engine.execute(first))
        val second = (engine.prepare("quieter") as Preparation.Ready).pending
        assertEquals(Preparation.Rejected(CommandResult.Cancelled), engine.prepare("cancel"))
        assertEquals(CommandResult.Cancelled, engine.execute(second))
        assertEquals(0, fake.calls)
    }

    @Test fun helpRepeatAndListeningAreLocalAndTruthful() = runBlocking {
        val engine = dispatcher(UnsupportedRadioController())
        assertTrue(engine.dispatch(AssistantCommand.Action(CommandId.REPEAT_RESPONSE)) is CommandResult.InvalidParameters)
        val help = engine.dispatch(AssistantCommand.Action(CommandId.HELP))
        assertTrue(help is CommandResult.Success)
        assertEquals(help, engine.dispatch(AssistantCommand.Action(CommandId.REPEAT_RESPONSE)))
        assertTrue(engine.dispatch(AssistantCommand.Action(CommandId.START_LISTENING)) is CommandResult.Unsupported)
        assertTrue(engine.dispatch(AssistantCommand.Action(CommandId.STOP_LISTENING)) is CommandResult.Unsupported)
        val unknown = engine.prepare("not a real command") as Preparation.Rejected
        assertEquals(CommandResult.Success(unknown.result.message), engine.dispatch(AssistantCommand.Action(CommandId.REPEAT_RESPONSE)))
        assertEquals(0, fake.calls)
    }

    @Test fun volumeMappingClampingFixedAndNoOpDetection() = runBlocking {
        val device = object : VolumeDevice {
            override var fixed = false
            override val minimum = 0
            override val maximum = 10
            override var current = 10
            var muteState = false
            override val muted get() = muteState
            var ignore = false
            override fun setLevel(level: Int) { if (!ignore) current = level }
            override fun setMuted(muted: Boolean) { if (!ignore) muteState = muted }
        }
        val controller = VerifiedVolumeController(device)
        assertTrue(controller.execute(AssistantCommand.SetVolume(30)) is CommandResult.Success)
        assertEquals(3, device.current)
        assertTrue(controller.execute(AssistantCommand.Action(CommandId.MUTE)) is CommandResult.Success)
        assertTrue(device.muted)
        assertTrue(controller.execute(AssistantCommand.Action(CommandId.UNMUTE)) is CommandResult.Success)
        assertFalse(device.muted)
        controller.execute(AssistantCommand.SetVolume(100))
        controller.execute(AssistantCommand.Action(CommandId.VOLUME_UP))
        assertEquals(10, device.current)
        device.ignore = true
        assertTrue(controller.execute(AssistantCommand.SetVolume(20)) is CommandResult.Failed)
        device.fixed = true
        assertTrue(controller.execute(AssistantCommand.SetVolume(20)) is CommandResult.Unsupported)
    }
}