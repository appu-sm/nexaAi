package com.nexaai.assistant.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.nexaai.assistant.MainActivity
import com.nexaai.assistant.NexaAiApplication
import com.nexaai.assistant.R
import com.nexaai.assistant.data.ConfigurationRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class HandsFreeControl(
    private val context: Context,
    private val repository: ConfigurationRepository,
    private val voice: SpeechSessionCoordinator,
    private val scope: CoroutineScope,
    private val startCommandService: () -> Unit = {
        ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java).setAction(HandsFreeService.COMMAND))
        Unit
    },
    private val startService: () -> Unit = {
        ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java))
        Unit
    },
) {
    private val requested = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = requested.asStateFlow()
    private var startupWatchdog: Job? = null
    private val commandRequested = MutableStateFlow(false)
    val commandPending: StateFlow<Boolean> = commandRequested.asStateFlow()
    private var commandWatchdog: Job? = null

    fun listenOnce() {
        if (voice.requestCommand()) return
        if (requested.value || commandRequested.value || voice.handsFreeEnabled.value ||
            voice.state.value.let { it !is VoiceState.Idle && it !is VoiceState.Completed && it !is VoiceState.Error }) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voice.reportError("Microphone permission is missing. Hold the icon to open NexaAI and grant microphone access first.")
            return
        }
        commandRequested.value = true
        scope.launch {
            try {
                val snapshot = kotlinx.coroutines.withTimeout(5_000) { repository.snapshots.first() }
                require(snapshot.ready && snapshot.warning == null && snapshot.configuration.settings.assistantEnabled) {
                    "Enable the assistant and repair settings before listening."
                }
                if (!commandRequested.value) return@launch
                startCommandService()
                if (commandRequested.value) commandWatchdog = scope.launch {
                    delay(10_000)
                    if (commandRequested.value) {
                        serviceEnded()
                        context.stopService(Intent(context, HandsFreeService::class.java))
                        voice.reportError("Android did not start overlay listening. Hold the icon and use Home push-to-talk.")
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                commandRequested.value = false
                voice.reportError("Listening settings timed out. Retry manually.")
            } catch (cancelled: CancellationException) {
                commandRequested.value = false
                throw cancelled
            } catch (_: Exception) {
                commandRequested.value = false
                voice.reportError("Overlay listening could not start. Check microphone permission and assistant settings; hold the icon for Home push-to-talk.")
            }
        }
    }

    fun commandStarted() { commandWatchdog?.cancel(); commandRequested.value = false }

    fun enable() {
        if (requested.value || commandRequested.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voice.reportError("Microphone permission is required for hands-free mode. Push-to-talk remains available.")
            return
        }
        requested.value = true
        scope.launch {
            try {
                val snapshot = repository.snapshots.first { it.ready }
                require(snapshot.warning == null && snapshot.configuration.settings.assistantEnabled) { "Enable the assistant before hands-free mode." }
                repository.update { it.copy(settings = it.settings.copy(wakeWordEnabled = true, listeningMode = "WAKE_WORD")) }
                if (requested.value) startService()
                startupWatchdog?.cancel()
                startupWatchdog = scope.launch {
                    delay(10_000)
                    if (requested.value && !voice.handsFreeEnabled.value) {
                        disable()
                        voice.reportError("Android did not start hands-free listening. Use push-to-talk and retry from the visible app.")
                    }
                }
            } catch (cancelled: CancellationException) {
                requested.value = false
                throw cancelled
            } catch (failure: Exception) {
                requested.value = false
                voice.reportError("Hands-free could not start: ${failure.message ?: "Android service unavailable"}. Use push-to-talk.")
            }
        }
    }

    fun disable() {
        startupWatchdog?.cancel()
        commandWatchdog?.cancel()
        commandRequested.value = false
        requested.value = false
        voice.cancel()
        context.stopService(Intent(context, HandsFreeService::class.java))
        scope.launch {
            try { repository.update { it.copy(settings = it.settings.copy(wakeWordEnabled = false, listeningMode = "PUSH_TO_TALK")) } }
            catch (failure: IOException) { voice.reportError("Listening stopped, but the preference could not be saved.") }
            catch (failure: IllegalArgumentException) { voice.reportError("Listening stopped. Repair stored configuration before enabling it again.") }
        }
    }

    fun serviceEnded() {
        startupWatchdog?.cancel(); commandWatchdog?.cancel()
        requested.value = false; commandRequested.value = false
    }
}

class HandsFreeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as NexaAiApplication
    private var started = false
    private var activePhrase: String? = null
    private var commandOnly = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            app.handsFreeControl.disable()
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        commandOnly = intent?.action == COMMAND
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Hands-free microphone", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, HandsFreeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_nexaai).setContentTitle(if (commandOnly) "NexaAI command microphone" else "NexaAI hands-free active")
                .setContentText("Local microphone listening or command session")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop listening", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(41, notification)
            check(if (commandOnly) app.handsFreeControl.commandPending.value else app.handsFreeControl.enabled.value) {
                "Start listening with an explicit user action."
            }
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is missing."
            }
            started = true
            scope.launch {
                if (commandOnly) {
                    val snapshot = app.repository.snapshots.first()
                    if (!snapshot.ready || snapshot.warning != null || !snapshot.configuration.settings.assistantEnabled ||
                        !app.handsFreeControl.commandPending.value) { stopSelf(); return@launch }
                    if (!app.voiceCoordinator.start(fromService = true)) { stopSelf(); return@launch }
                    app.handsFreeControl.commandStarted()
                    app.voiceCoordinator.awaitSessionEnd()
                    stopSelf()
                    return@launch
                }
                app.voiceCoordinator.stopAndJoin()
                if (!app.handsFreeControl.enabled.value) { stopSelf(); return@launch }
                val snapshot = app.repository.snapshots.first { it.ready }
                activePhrase = snapshot.configuration.settings.wakePhrase
                if (snapshot.warning != null || !snapshot.configuration.settings.assistantEnabled ||
                    !snapshot.configuration.settings.wakeWordEnabled ||
                    !app.voiceCoordinator.enableHandsFree(snapshot.configuration.settings.wakePhrase)) {
                    stopSelf()
                    return@launch
                }
                app.voiceCoordinator.handsFreeEnabled.first { !it }
                stopSelf()
            }
            scope.launch {
                app.repository.snapshots.collect { snapshot ->
                    if (snapshot.ready && (snapshot.warning != null || !snapshot.configuration.settings.assistantEnabled ||
                        (!commandOnly && !snapshot.configuration.settings.wakeWordEnabled) ||
                        (activePhrase != null && snapshot.configuration.settings.wakePhrase != activePhrase))) {
                        app.voiceCoordinator.cancel()
                        stopSelf()
                    }
                }
            }
        } catch (failure: RuntimeException) {
            app.voiceCoordinator.reportError("Android denied background listening: ${failure.message}. Use push-to-talk.")
            app.handsFreeControl.serviceEnded()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (started) app.voiceCoordinator.cancel()
        app.handsFreeControl.serviceEnded()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nexaai-hands-free"
        private const val STOP = "com.nexaai.assistant.STOP_LISTENING"
        const val COMMAND = "com.nexaai.assistant.LISTEN_ONCE"
    }
}