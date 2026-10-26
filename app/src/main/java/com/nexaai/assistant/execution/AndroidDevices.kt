package com.nexaai.assistant.execution

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import com.nexaai.assistant.data.CommandId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidRadioDevice(private val context: Context) : RadioDevice {
    override fun launchRadio(): Boolean {
        val intent = Intent().setComponent(ComponentName(RadioProtocol.PACKAGE, RadioProtocol.ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (missing: ActivityNotFoundException) {
            false
        }
    }

    override fun tuneFrequency(frequency10Khz: Int) {
        context.sendBroadcast(Intent(RadioProtocol.ACTION_SET_FREQUENCY)
            .setPackage(RadioProtocol.PACKAGE)
            .putExtra(RadioProtocol.EXTRA_FREQUENCY, frequency10Khz))
    }

    override fun sendKey(key: Int) {
        context.sendBroadcast(Intent(RadioProtocol.ACTION_TEST_KEY)
            .setPackage(RadioProtocol.PACKAGE)
            .putExtra(RadioProtocol.EXTRA_KEY, key))
    }

    override fun setMuted(muted: Boolean) {
        context.sendBroadcast(Intent(RadioProtocol.ACTION_SET_MUTE)
            .setPackage(RadioProtocol.PACKAGE)
            .putExtra(RadioProtocol.EXTRA_MUTE, muted))
    }
}

class AndroidVolumeDevice(context: Context) : VolumeDevice {
    private val audio = context.getSystemService(AudioManager::class.java)
    override val fixed get() = audio.isVolumeFixed
    override val minimum get() = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    override val maximum get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    override val current get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    override val muted get() = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    override fun setLevel(level: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    override fun setMuted(muted: Boolean) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
        if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
}

class AndroidAppDevice(private val context: Context) : AppDevice {
    private val packages get() = context.packageManager
    private fun launcherQuery() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        packages.queryIntentActivities(launcherQuery(), 0).map { resolved ->
            InstalledApp(resolved.activityInfo.packageName, resolved.loadLabel(packages).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    override suspend fun launch(packageName: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val launch = packages.getLaunchIntentForPackage(packageName) ?: return@withContext false
        val component = launch.resolveActivity(packages) ?: return@withContext false
        if (component.packageName != packageName) return@withContext false
        try {
            context.startActivity(launch.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (missing: ActivityNotFoundException) { false }
    }

    override suspend fun canGoHome() = withContext(Dispatchers.IO) { homeIntent().resolveActivity(packages) != null }
    override suspend fun home(): Boolean = withContext(Dispatchers.Main.immediate) {
        try { context.startActivity(homeIntent()); true } catch (missing: ActivityNotFoundException) { false }
    }
}

class MediaAccessService : NotificationListenerService()

class AndroidMediaDevice(private val context: Context) : MediaDevice {
    private val listener = ComponentName(context, MediaAccessService::class.java)
    override val accessGranted get() = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
    override fun sessions(): List<MediaSessionTarget> = context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(listener).map { controller ->
            object : MediaSessionTarget {
                override val actions: Set<CommandId> get() {
                    val available = controller.playbackState?.actions ?: 0L
                    return mapOf(CommandId.PLAY to PlaybackState.ACTION_PLAY, CommandId.PAUSE to PlaybackState.ACTION_PAUSE,
                        CommandId.NEXT_TRACK to PlaybackState.ACTION_SKIP_TO_NEXT,
                        CommandId.PREVIOUS_TRACK to PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .filterValues { available and it != 0L }.keys
                }
                override fun request(action: CommandId) {
                    with(controller.transportControls) {
                        when (action) {
                            CommandId.PLAY -> play()
                            CommandId.PAUSE -> pause()
                            CommandId.NEXT_TRACK -> skipToNext()
                            CommandId.PREVIOUS_TRACK -> skipToPrevious()
                            else -> throw IllegalArgumentException("Unsupported media action")
                        }
                    }
                }
            }
        }
}