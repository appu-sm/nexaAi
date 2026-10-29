package com.nexaai.assistant.overlay

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.nexaai.assistant.MainActivity
import com.nexaai.assistant.NexaAiApplication
import com.nexaai.assistant.R
import com.nexaai.assistant.data.ConfigurationSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class OverlayService : Service(), DisplayManager.DisplayListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as NexaAiApplication
    private var controller: OverlayController? = null
    private var snapshot = ConfigurationSnapshot()
    private var presentation = OverlayPresentation("Hands-free off")
    private var observing = false
    private var lastNotice: String? = null
    private val permissionListener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if ((operation == null || operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW) &&
            (packageName == null || packageName == this.packageName)) {
            scope.launch { reconcile() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HIDE) {
            scope.launch {
                try { app.repository.update { it.copy(settings = it.settings.copy(overlayEnabled = false)) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { app.overlayControl.failed("Overlay hidden, but preference could not be saved.") }
                finally { stopSelf() }
            }
            return START_NOT_STICKY
        }
        if (observing) return START_NOT_STICKY
        try {
            check(Settings.canDrawOverlays(this)) { "Overlay permission is missing." }
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Floating assistant", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val hide = PendingIntent.getService(this, 11, Intent(this, OverlayService::class.java).setAction(HIDE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_nexaai)
                .setContentTitle("NexaAI floating controls")
                .setContentText("Overlay service only; does not enable microphone listening.")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Disable overlay", hide).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(42, notification)
            controller = OverlayController(AndroidOverlayWindow(this,
                OverlayActions({
                    try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                    catch (_: RuntimeException) { app.overlayControl.failed("Android could not open NexaAI. Use the launcher.") }
                }, {
                    app.handsFreeControl.disable()
                }, {
                    app.handsFreeControl.listenOnce()
                }), { x, y ->
                    scope.launch {
                        try { app.repository.update { it.copy(settings = it.settings.copy(overlayXPercent = x, overlayYPercent = y)) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { app.overlayControl.failed("Overlay position could not be saved. Retry from NexaAI."); stopSelf() }
                    }
                }, {
                    app.overlayControl.failed("Overlay touch became unavailable. Check permission and reopen NexaAI.")
                    stopSelf()
                }))
            getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
            getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
            observing = true
            app.overlayControl.started(false)
            scope.launch {
                combine(app.repository.snapshots, app.voiceCoordinator.state, app.handsFreeControl.enabled,
                    app.voiceCoordinator.handsFreeEnabled, app.handsFreeControl.commandPending) { saved, voice, requested, active, command ->
                    saved to overlayPresentation(voice, requested || command, active)
                }.collect { (saved, state) ->
                    snapshot = saved; presentation = state
                    if (state.notice != null && state.notice != lastNotice) {
                        android.widget.Toast.makeText(this@OverlayService, state.notice, android.widget.Toast.LENGTH_LONG).show()
                    }
                    lastNotice = state.notice
                    reconcile()
                }
            }
        } catch (_: RuntimeException) {
            app.overlayControl.failed("Android denied the overlay window/service. Check permission and retry from NexaAI.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun reconcile() {
        try {
            val permission = app.overlayControl.refreshPermission()
            if (!snapshot.ready || snapshot.warning != null || !snapshot.configuration.settings.let { it.assistantEnabled && it.overlayEnabled } || !permission) {
                controller?.close()
                stopSelf()
                return
            }
            controller?.reconcile(snapshot.configuration.settings, permission, presentation)
            app.overlayControl.started(controller?.attached == true)
        } catch (_: RuntimeException) {
            controller?.close()
            app.overlayControl.failed("Overlay became unavailable. Check permission and reopen NexaAI.")
            stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); reconcile() }
    override fun onDisplayChanged(displayId: Int) { if (snapshot.ready) reconcile() }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) { controller?.close(); stopSelf() }

    override fun onDestroy() {
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        controller?.close()
        controller = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        app.overlayControl.ended()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nexaai-overlay"
        private const val HIDE = "com.nexaai.assistant.HIDE_OVERLAY"
    }
}