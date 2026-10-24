package com.nexaai.assistant

import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import com.nexaai.assistant.execution.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SafeControllersTest {
    @Test fun appAllowlistCollisionAndSchemaValidation() {
        val apps = listOf(AllowedApp("trusted.maps", listOf("maps", "navigation")))
        assertEquals(apps, AppAllowlistCodec.decode(AppAllowlistCodec.encode(apps)))
        listOf(apps + AllowedApp("trusted.other", listOf("maps")), listOf(AllowedApp("invalid", listOf("maps")))).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppAllowlistCodec.validate(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppAllowlistCodec.decode("{\"schemaVersion\":2,\"apps\":[]}") }
    }

    @Test fun missingUninstalledAndFailedAppLaunches() = runBlocking {
        var launches = 0
        var installed = listOf(InstalledApp("trusted.maps", "Maps"))
        val device = object : AppDevice {
            override suspend fun installedApps() = installed
            override suspend fun launch(packageName: String): Boolean { launches++; return false }
            override suspend fun home() = false
            override suspend fun canGoHome() = false
        }
        val launcher = AllowlistedAppLauncher(device) { listOf(AllowedApp("trusted.maps", listOf("maps"))) }
        assertTrue(launcher.execute(AssistantCommand.LaunchApp("arbitrary.package")) is CommandResult.InvalidParameters)
        installed = emptyList()
        assertTrue(launcher.execute(AssistantCommand.LaunchApp("trusted.maps")) is CommandResult.Unsupported)
        assertEquals(0, launches)
        installed = listOf(InstalledApp("trusted.maps", "Maps"))
        assertTrue(launcher.execute(AssistantCommand.LaunchApp("trusted.maps")) is CommandResult.Failed)
        assertTrue(launcher.execute(AssistantCommand.Action(CommandId.GO_HOME)) is CommandResult.Unsupported)
        assertEquals(1, launches)
    }

    @Test fun mediaRequiresAccessSingleSessionAndAdvertisedActions() = runBlocking {
        var requested = 0
        val session = object : MediaSessionTarget {
            override val actions = setOf(CommandId.PLAY)
            override fun request(action: CommandId) { requested++ }
        }
        val device = object : MediaDevice {
            override var accessGranted = false
            var active = emptyList<MediaSessionTarget>()
            override fun sessions() = active
        }
        val media = SessionMediaController(device)
        val play = AssistantCommand.Action(CommandId.PLAY)
        assertTrue(media.execute(play) is CommandResult.PermissionRequired)
        device.accessGranted = true
        assertTrue(media.execute(play) is CommandResult.Unsupported)
        device.active = listOf(session, session)
        assertTrue(media.execute(play) is CommandResult.InvalidParameters)
        device.active = listOf(session)
        assertTrue(media.execute(AssistantCommand.Action(CommandId.NEXT_TRACK)) is CommandResult.Unsupported)
        assertEquals(0, requested)
        val result = media.execute(play)
        assertTrue(result is CommandResult.Success)
        assertTrue(result.message.contains("not verified"))
        assertEquals(1, requested)
    }
}