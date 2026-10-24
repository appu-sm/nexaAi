package com.nexaai.assistant.execution

import com.nexaai.assistant.data.CommandId
import com.nexaai.assistant.domain.*
import kotlin.math.roundToInt

interface VolumeDevice {
    val fixed: Boolean
    val minimum: Int
    val maximum: Int
    val current: Int
    val muted: Boolean
    fun setLevel(level: Int)
    fun setMuted(muted: Boolean)
}

class VerifiedVolumeController(private val device: VolumeDevice) : VolumeController {
    override suspend fun check(command: AssistantCommand): CommandResult? = when {
        device.fixed -> CommandResult.Unsupported("This device has fixed volume")
        device.maximum <= device.minimum -> CommandResult.Unsupported("Music stream has no adjustable volume range")
        else -> null
    }

    override suspend fun execute(command: AssistantCommand): CommandResult {
        check(command)?.let { return it }
        if (command is AssistantCommand.SetVolume && command.percent !in 0..100)
            return CommandResult.InvalidParameters("Volume must be from 0 to 100")
        if (command.id == CommandId.MUTE || command.id == CommandId.UNMUTE) {
            val target = command.id == CommandId.MUTE
            device.setMuted(target)
            return if (device.muted == target) CommandResult.Success(if (target) "Music stream muted" else "Music stream unmuted")
            else CommandResult.Failed("Device did not apply music-stream mute state", "VOLUME_NO_CHANGE")
        }
        val target = when (command) {
            is AssistantCommand.SetVolume -> device.minimum + ((device.maximum - device.minimum) * (command.percent.coerceIn(0, 100) / 100.0)).roundToInt()
            else -> when (command.id) {
                CommandId.VOLUME_UP -> device.current + 1
                CommandId.VOLUME_DOWN -> device.current - 1
                else -> return CommandResult.InvalidParameters("Not a volume command")
            }
        }.coerceIn(device.minimum, device.maximum)
        device.setLevel(target)
        return if (device.current == target) CommandResult.Success("Music stream level $target of ${device.maximum} reported by Android; audible output unverified")
        else CommandResult.Failed("Device did not apply the requested music-stream level", "VOLUME_NO_CHANGE")
    }
}