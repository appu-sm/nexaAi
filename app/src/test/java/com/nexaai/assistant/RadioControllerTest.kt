package com.nexaai.assistant

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.test.core.app.ApplicationProvider
import com.nexaai.assistant.data.CommandId
import com.nexaai.assistant.domain.AssistantCommand
import com.nexaai.assistant.domain.CommandResult
import com.nexaai.assistant.execution.AndroidRadioDevice
import com.nexaai.assistant.execution.RadioDevice
import com.nexaai.assistant.execution.RadioProtocol
import com.nexaai.assistant.execution.VerifiedRadioController
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RadioControllerTest {
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var started: Intent? = null
        var startFailure: RuntimeException? = null
        val broadcasts = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            startFailure?.let { throw it }
            started = intent
        }
        override fun sendBroadcast(intent: Intent) { broadcasts += intent }
    }

    private class RecordingRadioDevice : RadioDevice {
        var launchSucceeds = true
        var launches = 0
        val frequencies = mutableListOf<Int>()
        val keys = mutableListOf<Int>()
        val muteValues = mutableListOf<Boolean>()
        override fun launchRadio() = launchSucceeds.also { launches++ }
        override fun tuneFrequency(frequency10Khz: Int) { frequencies += frequency10Khz }
        override fun sendKey(key: Int) { keys += key }
        override fun setMuted(muted: Boolean) { muteValues += muted }
    }

    @Test fun androidAdapterSendsExplicitLaunchAndExactBroadcastPayloads() {
        val context = RecordingContext(ApplicationProvider.getApplicationContext())
        val device = AndroidRadioDevice(context)

        assertTrue(device.launchRadio())
        assertEquals(ComponentName(RadioProtocol.PACKAGE, RadioProtocol.ACTIVITY), context.started?.component)
        assertTrue(context.started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        device.tuneFrequency(9830)
        device.sendKey(RadioProtocol.SEARCH_NEXT_KEY)
        device.sendKey(RadioProtocol.SEARCH_PREVIOUS_KEY)
        device.sendKey(RadioProtocol.AUTO_MEMORY_SCAN_KEY)
        device.sendKey(RadioProtocol.INTRO_SCAN_KEY)
        device.setMuted(true)
        device.setMuted(false)

        val tune = context.broadcasts[0]
        val next = context.broadcasts[1]
        val previous = context.broadcasts[2]
        val ams = context.broadcasts[3]
        val intro = context.broadcasts[4]
        val mute = context.broadcasts[5]
        val unmute = context.broadcasts[6]
        assertBroadcast(tune, RadioProtocol.ACTION_SET_FREQUENCY, RadioProtocol.EXTRA_FREQUENCY)
        assertEquals(9830, tune.getIntExtra(RadioProtocol.EXTRA_FREQUENCY, -1))
        listOf(next to 5, previous to 6, ams to 46, intro to 61).forEach { (intent, key) ->
            assertBroadcast(intent, RadioProtocol.ACTION_TEST_KEY, RadioProtocol.EXTRA_KEY)
            assertEquals(key, intent.getIntExtra(RadioProtocol.EXTRA_KEY, -1))
        }
        assertBroadcast(mute, RadioProtocol.ACTION_SET_MUTE, RadioProtocol.EXTRA_MUTE)
        assertTrue(mute.getBooleanExtra(RadioProtocol.EXTRA_MUTE, false))
        assertBroadcast(unmute, RadioProtocol.ACTION_SET_MUTE, RadioProtocol.EXTRA_MUTE)
        assertFalse(unmute.getBooleanExtra(RadioProtocol.EXTRA_MUTE, true))
    }

    @Test fun missingRadioActivityIsReportedAsLaunchFailure() {
        val context = RecordingContext(ApplicationProvider.getApplicationContext()).apply {
            startFailure = ActivityNotFoundException("Radio activity is missing")
        }
        assertFalse(AndroidRadioDevice(context).launchRadio())
    }

    @Test fun controllerMapsAllOperationsAndRoundsHalfUpToTenKhz() = kotlinx.coroutines.runBlocking {
        val device = RecordingRadioDevice()
        val controller = VerifiedRadioController(device)
        listOf("98.30", "98.90", "101.10", "98.305").forEach {
            val result = controller.execute(AssistantCommand.TuneFm(BigDecimal(it)))
            assertTrue(result is CommandResult.Success)
            assertTrue(result.message.contains("confirmation unavailable"))
        }
        controller.execute(AssistantCommand.Action(CommandId.OPEN_RADIO))
        controller.execute(AssistantCommand.Action(CommandId.SEEK_NEXT))
        controller.execute(AssistantCommand.Action(CommandId.SEEK_PREVIOUS))
        controller.execute(AssistantCommand.Action(CommandId.MUTE_RADIO))
        controller.execute(AssistantCommand.Action(CommandId.UNMUTE_RADIO))
        controller.execute(AssistantCommand.Action(CommandId.AUTO_MEMORY_SCAN))
        controller.execute(AssistantCommand.Action(CommandId.RADIO_INTRO_SCAN))

        assertEquals(listOf(9830, 9890, 10110, 9831), device.frequencies)
        assertEquals(1, device.launches)
        assertEquals(listOf(5, 6, 46, 61), device.keys)
        assertEquals(listOf(true, false), device.muteValues)
        assertTrue(controller.execute(AssistantCommand.TuneFm(BigDecimal("120"))) is CommandResult.InvalidParameters)
        device.launchSucceeds = false
        assertTrue(controller.execute(AssistantCommand.Action(CommandId.OPEN_RADIO)) is CommandResult.Failed)
    }

    private fun assertBroadcast(intent: Intent, action: String, extra: String) {
        assertEquals(action, intent.action)
        assertEquals(RadioProtocol.PACKAGE, intent.`package`)
        assertTrue(intent.hasExtra(extra))
    }
}