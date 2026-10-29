package com.nexaai.assistant.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nexaai.assistant.data.ConfigurationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class OverlayRuntime(
    val permission: Boolean = false,
    val running: Boolean = false,
    val visible: Boolean = false,
    val message: String = "Service not running. Open NexaAI to restore a configured overlay.",
    val startup: String = "Boot startup not observed in this process; firmware delivery is unverified.",
)

class OverlayControl(
    private val context: Context,
    private val repository: ConfigurationRepository,
    private val scope: CoroutineScope,
    private val permission: () -> Boolean = { Settings.canDrawOverlays(context) },
    private val start: () -> Unit = {
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        Unit
    },
) {
    private val mutableState = MutableStateFlow(OverlayRuntime())
    val state = mutableState.asStateFlow()
    private var requested = false
    private var watchdog: Job? = null

    fun refreshFromActivity() {
        refreshPermission()
        scope.launch {
            try {
                val snapshot = withTimeout(5_000) { repository.snapshots.first() }
                if (snapshot.ready && snapshot.warning == null && snapshot.configuration.settings.let { it.assistantEnabled && it.overlayEnabled }) {
                    requestStart()
                } else stop()
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                failed("Overlay settings timed out. Retry from NexaAI.")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { failed("Overlay settings could not be read. Retry from NexaAI.") }
        }
    }

    fun refreshPermission(): Boolean {
        val granted = permission()
        mutableState.value = mutableState.value.copy(permission = granted)
        if (!granted) {
            stop()
            mutableState.value = mutableState.value.copy(message = "Overlay permission missing. Grant it from Settings when wanted.")
        }
        return granted
    }

    fun requestStart() {
        if (!refreshPermission() || requested || state.value.running) return
        requested = true
        mutableState.value = mutableState.value.copy(message = "Requesting overlay service; not yet visible.")
        try {
            start()
            watchdog?.cancel()
            watchdog = scope.launch {
                delay(10_000)
                if (requested && !state.value.running) failed("Android did not start the overlay. Open NexaAI and retry manually.")
            }
        } catch (failure: RuntimeException) {
            failed("Android denied the overlay service. Open NexaAI and retry manually.")
        }
    }

    fun started(visible: Boolean) {
        requested = false
        watchdog?.cancel()
        mutableState.value = mutableState.value.copy(running = true, visible = visible,
            message = if (visible) "Overlay attached; microphone state is shown separately." else "Overlay service starting.")
    }

    fun ended() {
        requested = false
        watchdog?.cancel()
        mutableState.value = mutableState.value.copy(running = false, visible = false,
            message = if (state.value.running) "Overlay service stopped. Open NexaAI to restore it." else state.value.message)
    }

    fun failed(message: String) {
        stop()
        mutableState.value = mutableState.value.copy(message = message)
    }

    fun stop() {
        ended()
        context.stopService(Intent(context, OverlayService::class.java))
        mutableState.value = mutableState.value.copy(message = "Overlay stopped; push-to-talk remains available.")
    }

    fun startupStatus(message: String) { mutableState.value = mutableState.value.copy(startup = message) }
}