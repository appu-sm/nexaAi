package com.nexaai.assistant.execution

import com.nexaai.assistant.data.CommandId
import com.nexaai.assistant.domain.AssistantCommand
import com.nexaai.assistant.domain.CommandResult
import java.math.BigDecimal
import java.math.RoundingMode

object RadioProtocol {
    const val PACKAGE = "com.nwd.radio"
    const val ACTIVITY = "com.nwd.radio.home_horizontalActivity"
    const val ACTION_SET_FREQUENCY = "com.nwd.action.ACTION_SET_RADIO_FREQUENCE"
    const val EXTRA_FREQUENCY = "extra_radio_frequence"
    const val ACTION_TEST_KEY = "com.nwd.action.ACTION_TEST_KEY"
    const val EXTRA_KEY = "extra_key_value"
    const val ACTION_SET_MUTE = "com.nwd.action.ACTION_SET_MUTE"
    const val EXTRA_MUTE = "extra_mute"
    const val SEARCH_NEXT_KEY = 5
    const val SEARCH_PREVIOUS_KEY = 6
    const val AUTO_MEMORY_SCAN_KEY = 46
    const val INTRO_SCAN_KEY = 61
}

interface RadioDevice {
    fun launchRadio(): Boolean
    fun tuneFrequency(frequency10Khz: Int)
    fun sendKey(key: Int)
    fun setMuted(muted: Boolean)
}

class VerifiedRadioController(private val device: RadioDevice) : RadioController {
    override val band = FmBand(BigDecimal("87.5"), BigDecimal("108.0"), BigDecimal("0.001"))

    override suspend fun check(command: AssistantCommand): CommandResult? = when (command.id) {
        CommandId.OPEN_RADIO, CommandId.TUNE_FM, CommandId.SEEK_NEXT, CommandId.SEEK_PREVIOUS,
        CommandId.MUTE_RADIO, CommandId.UNMUTE_RADIO, CommandId.AUTO_MEMORY_SCAN,
        CommandId.RADIO_INTRO_SCAN -> null
        else -> CommandResult.InvalidParameters("Not a radio command")
    }

    override suspend fun execute(command: AssistantCommand): CommandResult {
        check(command)?.let { return it }
        return when (command.id) {
            CommandId.OPEN_RADIO -> if (device.launchRadio()) {
                CommandResult.Success("Radio launch requested")
            } else CommandResult.Failed("Radio activity could not be opened", "RADIO_ACTIVITY_UNAVAILABLE")
            CommandId.TUNE_FM -> {
                val frequency = (command as? AssistantCommand.TuneFm)?.frequencyMhz
                    ?: return CommandResult.InvalidParameters("FM frequency is required")
                if (!band.contains(frequency)) return CommandResult.InvalidParameters("Frequency is outside 87.5-108.0 MHz")
                val frequency10Khz = frequency.multiply(BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).intValueExact()
                device.tuneFrequency(frequency10Khz)
                val requestedMhz = BigDecimal(frequency10Khz).movePointLeft(2).stripTrailingZeros().toPlainString()
                CommandResult.Success("FM $requestedMhz request sent; tuner confirmation unavailable")
            }
            CommandId.SEEK_NEXT -> keyRequest(RadioProtocol.SEARCH_NEXT_KEY, "Searching for the next station")
            CommandId.SEEK_PREVIOUS -> keyRequest(RadioProtocol.SEARCH_PREVIOUS_KEY, "Searching for the previous station")
            CommandId.MUTE_RADIO -> muteRequest(true)
            CommandId.UNMUTE_RADIO -> muteRequest(false)
            CommandId.AUTO_MEMORY_SCAN -> keyRequest(RadioProtocol.AUTO_MEMORY_SCAN_KEY, "Automatic station memory scan requested")
            CommandId.RADIO_INTRO_SCAN -> keyRequest(RadioProtocol.INTRO_SCAN_KEY, "Station preview requested")
            else -> CommandResult.InvalidParameters("Not a radio command")
        }
    }

    private fun keyRequest(key: Int, message: String): CommandResult {
        device.sendKey(key)
        return CommandResult.Success("$message; tuner result not confirmed")
    }

    private fun muteRequest(muted: Boolean): CommandResult {
        device.setMuted(muted)
        return CommandResult.Success("Radio ${if (muted) "mute" else "unmute"} request sent; state not confirmed")
    }
}