package com.nexaai.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexaai.assistant.data.ConfigurationDocuments
import com.nexaai.assistant.diagnostics.DeviceDiagnostics
import com.nexaai.assistant.ui.AssistantViewModel
import com.nexaai.assistant.ui.NexaApp

class MainActivity : ComponentActivity() {
    private val assistantViewModel by viewModels<AssistantViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AssistantViewModel((application as NexaAiApplication).repository,
                    ConfigurationDocuments(contentResolver), (application as NexaAiApplication).commandRuntime,
                    (application as NexaAiApplication).speechEngine,
                    (application as NexaAiApplication).speechModelInstaller,
                    (application as NexaAiApplication).speechOutput,
                    (application as NexaAiApplication).voiceCoordinator,
                    (application as NexaAiApplication).handsFreeControl,
                    (application as NexaAiApplication).wakeModelInstaller,
                    (application as NexaAiApplication).overlayControl,
                    (application as NexaAiApplication).voiceDiagnostics,
                    com.nexaai.assistant.diagnostics.AndroidResourceMetrics(application)) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexaApp(assistantViewModel, DeviceDiagnostics.inspect(this))
        }
    }

    override fun onResume() {
        super.onResume()
        (application as NexaAiApplication).overlayControl.refreshFromActivity()
    }
}