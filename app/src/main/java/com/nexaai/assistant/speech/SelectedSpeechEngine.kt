package com.nexaai.assistant.speech

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

object AsrEngines {
    const val VOSK = "vosk"
    const val SHERPA = "sherpa_onnx"
    val labels = linkedMapOf(VOSK to "Vosk - Indian English", SHERPA to "sherpa-onnx - English")
}

class SelectedSpeechEngine(
    private val selectedId: () -> String,
    private val factories: Map<String, () -> SpeechRecognizerEngine>,
    private val installers: Map<String, SpeechModelInstaller>,
    private val awaitConfiguration: suspend () -> Unit = {},
) : SpeechRecognizerEngine, SpeechModelInstaller {
    private val gate = Mutex()
    @Volatile private var active: SpeechRecognizerEngine? = null
    val activeEngineId: String? get() = active?.takeIf { it.status.value == RecognitionStatus.Ready }?.engineId
    override val engineId: String get() = activeEngineId ?: selectedId()
    override val displayName: String get() = AsrEngines.labels[engineId] ?: "Unknown ASR engine"
    override val status = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Unloaded)

    private suspend fun activate(): SpeechRecognizerEngine {
        awaitConfiguration()
        val selected = selectedId()
        if (active?.engineId != selected) {
            active?.release()
            active = null
            status.value = RecognitionStatus.Unloaded
        }
        val engine = active ?: (factories[selected] ?: throw RecognitionException(RecognitionError(selected,
            RecognitionErrorCode.UNKNOWN_ENGINE, "Unknown ASR engine '$selected'. Select an available engine in Settings.", false)))().also { active = it }
        status.value = RecognitionStatus.Loading
        engine.initialize()
        status.value = RecognitionStatus.Ready
        return engine
    }

    private suspend fun <Result> exclusively(action: suspend () -> Result): Result = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.BUSY,
            "Stop and join the active recognition or evaluation before switching engines."))
        try { action() }
        catch (failure: RecognitionException) { status.value = RecognitionStatus.Failed(failure.error); throw failure }
        finally { gate.unlock() }
    }

    override suspend fun initialize() { exclusively { activate() } }
    override suspend fun recognize(onPartialResult: (String) -> Unit): String = recognize({}, onPartialResult)
    override suspend fun recognize(onReady: () -> Unit, onPartialResult: (String) -> Unit): String =
        recognizeResult(onReady) { if (!it.isFinal) onPartialResult(it.transcript) }.transcript

    override suspend fun recognizeResult(onReady: () -> Unit, onResult: (RecognitionResult) -> Unit): RecognitionResult =
        exclusively { activate().recognizeResult(onReady, onResult) }

    override suspend fun openSession(): RecognitionSession {
        if (!gate.tryLock()) throw RecognitionException(RecognitionError(engineId, RecognitionErrorCode.BUSY, "Recognition session already active."))
        try {
            val delegate = activate().openSession()
            return object : RecognitionSession by delegate {
                override fun close() { try { delegate.close() } finally { gate.unlock() } }
            }
        } catch (failure: Throwable) { gate.unlock(); throw failure }
    }

    override fun stop() { active?.stop() }
    override fun release() {
        check(gate.tryLock()) { "Stop and join recognition before releasing the selected engine." }
        try { active?.release(); active = null; status.value = RecognitionStatus.Unloaded }
        finally { gate.unlock() }
    }

    fun isInstalled(id: String): Boolean = installers[id]?.isInstalled() == true
    override fun isInstalled(): Boolean = isInstalled(selectedId())
    override suspend fun install(uri: Uri) {
        exclusively {
            awaitConfiguration()
            val selected = selectedId()
            val installer = installers[selected] ?: throw RecognitionException(RecognitionError(selected,
                RecognitionErrorCode.UNKNOWN_ENGINE, "Unknown ASR engine. Select Vosk or sherpa-onnx."))
            active?.release()
            active = null
            status.value = RecognitionStatus.Unloaded
            installer.install(uri)
        }
    }
}