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
                    ConfigurationDocuments(contentResolver), (application as NexaAiApplication).commandRuntime) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexaApp(assistantViewModel, DeviceDiagnostics.inspect(this))
        }
    }
}