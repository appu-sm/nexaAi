package com.nexaai.assistant.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.nexaai.assistant.NexaAiApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val pending = goAsync()
        val app = context.applicationContext as NexaAiApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                val snapshot = withTimeout(6_000) { app.repository.snapshots.first() }
                val granted = app.overlayControl.refreshPermission()
                if (StartupPolicy.restore(snapshot, true, granted) { app.overlayControl.requestStart() }) {
                    app.overlayControl.startupStatus("Boot/update received after unlock; overlay start requested, not proof of persistence. Microphone stays off.")
                } else app.overlayControl.startupStatus("Boot/update received; no startup requested. Check auto-start, overlay, permission and assistant settings. Microphone stays off.")
            } catch (_: Exception) {
                app.overlayControl.startupStatus("Boot/update startup failed. No retry scheduled; open NexaAI manually.")
            } finally { pending.finish(); scope.cancel() }
        }
    }
}