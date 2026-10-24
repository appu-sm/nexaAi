package com.nexaai.assistant.execution

import com.nexaai.assistant.data.CommandId
import com.nexaai.assistant.domain.*

data class InstalledApp(val packageName: String, val label: String)

interface AppDevice {
    suspend fun installedApps(): List<InstalledApp>
    suspend fun launch(packageName: String): Boolean
    suspend fun home(): Boolean
    suspend fun canGoHome(): Boolean
}

class AllowlistedAppLauncher(
    private val device: AppDevice,
    private val allowlist: suspend () -> List<AllowedApp>,
) : AppLauncher {
    override suspend fun check(command: AssistantCommand): CommandResult? {
        if (command.id == CommandId.GO_HOME) return if (device.canGoHome()) null else CommandResult.Unsupported("No home activity is available")
        if (command !is AssistantCommand.LaunchApp) return CommandResult.InvalidParameters("Select an allowlisted app")
        if (allowlist().none { it.packageName == command.appId }) return CommandResult.InvalidParameters("App is not allowlisted")
        if (device.installedApps().none { it.packageName == command.appId }) return CommandResult.Unsupported("Allowlisted app is no longer installed or launchable")
        return null
    }

    override suspend fun execute(command: AssistantCommand): CommandResult {
        check(command)?.let { return it }
        return if (command.id == CommandId.GO_HOME) {
            if (device.home()) CommandResult.Success("Home navigation requested; no app was force-stopped")
            else CommandResult.Failed("Home activity could not be opened", "HOME_UNAVAILABLE")
        } else {
            if (device.launch((command as AssistantCommand.LaunchApp).appId)) CommandResult.Success("Allowlisted app launch requested")
            else CommandResult.Failed("App could not be launched", "APP_LAUNCH_FAILED")
        }
    }
}

interface MediaSessionTarget {
    val actions: Set<CommandId>
    fun request(action: CommandId)
}

interface MediaDevice {
    val accessGranted: Boolean
    fun sessions(): List<MediaSessionTarget>
}

class SessionMediaController(private val device: MediaDevice) : MediaController {
    private fun selection(command: AssistantCommand): Pair<MediaSessionTarget?, CommandResult?> {
        if (!device.accessGranted) return null to CommandResult.PermissionRequired("notification_listener_access", "Media control requires notification-listener access enabled in Android settings")
        val sessions = device.sessions()
        if (sessions.isEmpty()) return null to CommandResult.Unsupported("No active media session")
        if (sessions.size != 1) return null to CommandResult.InvalidParameters("Multiple media sessions are active; leave only the intended player active")
        val session = sessions.single()
        if (command.id !in session.actions) return null to CommandResult.Unsupported("The active media session does not advertise this action")
        return session to null
    }

    override suspend fun check(command: AssistantCommand) = selection(command).second

    override suspend fun execute(command: AssistantCommand): CommandResult {
        val (session, failure) = selection(command)
        if (failure != null) return failure
        session!!.request(command.id)
        return CommandResult.Success("${command.id.name.lowercase().replace('_', ' ')} request sent to media session; playback change not verified")
    }
}