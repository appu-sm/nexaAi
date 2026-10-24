package com.nexaai.assistant.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class DeviceFacts(val manufacturer: String, val model: String, val androidVersion: String, val api: Int, val microphoneGranted: Boolean)

object DeviceDiagnostics {
    fun inspect(context: Context) = DeviceFacts(
        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )
}