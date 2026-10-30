package com.nexaai.assistant.speech

import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AsrEngines {
    val labels = mapOf("vosk" to "Vosk English", "moonshine_base_en" to "Moonshine Base English")
    fun label(id: String): String = labels[id] ?: "Unknown speech engine"
}

class SelectedSpeechEngine(
    private val selectedId: suspend () -> String,
    private val currentId: () -> String,
    private val factories: Map<String, () -> SpeechRecognizerEngine>,
    private val installers: Map<String, SpeechModelInstaller>,
) : SpeechRecognizerEngine, SpeechModelInstaller {
    private val gate = Mutex()
    @Volatile private var loaded: SpeechRecognizerEngine? = null
    private var loadedId: String? = null
    override val recognitionTimeoutMillis: Long get() = loaded?.recognitionTimeoutMillis ?: 16_000L

    override suspend fun initialize() = gate.withLock {
        val id = selectedId()
        val factory = factories[id] ?: throw IllegalArgumentException("Unknown offline speech engine")
        if (loadedId != id) {
            loaded?.release()
            loaded = null
            loadedId = null
            loaded = factory()
            loadedId = id
        }
        loaded!!.initialize()
    }

    override suspend fun recognize(onPartialResult: (String) -> Unit): String = recognize({}, onPartialResult, {})
    override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit): String =
        recognize(onReady, onPartialResult, {})

    override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit, onProcessing: () -> Unit): String =
        gate.withLock { checkNotNull(loaded) { "Offline speech engine is not initialized." }.recognize(onReady, onPartialResult, onProcessing) }

    override fun stop() { loaded?.stop() }

    override fun release() {
        check(gate.tryLock()) { "Stop and join recognition before releasing the speech engine." }
        try {
            loaded?.release()
            loaded = null
            loadedId = null
        } finally { gate.unlock() }
    }

    override fun isInstalled(): Boolean = installers[currentId()]?.isInstalled() == true

    override suspend fun install(uri: Uri) = gate.withLock {
        val installer = installers[selectedId()] ?: throw IllegalArgumentException("Unknown offline speech engine")
        installer.install(uri)
    }
}