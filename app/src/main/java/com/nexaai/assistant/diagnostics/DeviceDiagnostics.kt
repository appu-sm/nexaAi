package com.nexaai.assistant.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.PowerManager
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import java.io.File

data class DeviceFacts(val manufacturer: String, val model: String, val androidVersion: String, val api: Int, val microphoneGranted: Boolean)

object DeviceDiagnostics {
    fun inspect(context: Context) = DeviceFacts(
        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )
}

class AndroidResourceMetrics(private val context: Context) : ResourceMetrics {
    override fun read(): ResourceReading {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val rss = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
            }
        }.getOrNull()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.takeIf { it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else null
        return ResourceReading(SystemClock.elapsedRealtime(), Process.getElapsedCpuTime(), memory.totalPss.toLong(),
            rss, if (level in 0..scale && scale > 0) (level * 100L / scale).toInt() else null, temperature, thermal)
    }
}