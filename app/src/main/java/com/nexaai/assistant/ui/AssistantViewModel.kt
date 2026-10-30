package com.nexaai.assistant.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexaai.assistant.data.*
import com.nexaai.assistant.domain.*
import com.nexaai.assistant.execution.*
import com.nexaai.assistant.speech.*
import com.nexaai.assistant.overlay.OverlayControl
import com.nexaai.assistant.overlay.OverlayRuntime
import java.io.IOException
import com.nexaai.assistant.diagnostics.VoiceDiagnostics
import com.nexaai.assistant.diagnostics.ResourceMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AssistantUiState(
    val snapshot: ConfigurationSnapshot = ConfigurationSnapshot(),
    val busy: Boolean = false,
    val message: String? = null,
    val importPreview: Configuration? = null,
    val controls: CommandUiState = CommandUiState(),
    val voice: VoiceState = VoiceState.Idle(),
    val speechOutput: SpeechOutputState = SpeechOutputState.Initializing,
    val modelInstalled: Boolean = false,
    val handsFreeEnabled: Boolean = false,
    val overlay: OverlayRuntime = OverlayRuntime(),
)

data class CommandUiState(
    val installedApps: List<InstalledApp> = emptyList(),
    val allowlist: AllowlistSnapshot = AllowlistSnapshot(),
    val pending: PreparedCommand? = null,
    val transcript: String = "",
    val interpreted: String? = null,
    val result: CommandResult? = null,
    val working: Boolean = false,
    val availability: Map<CommandId, String> = emptyMap(),
)

class AssistantViewModel(
    private val repository: ConfigurationRepository,
    private val documents: ConfigurationDocuments,
    private val runtime: CommandRuntime? = null,
    speechEngine: SpeechRecognizerEngine? = null,
    speechModelInstaller: SpeechModelInstaller? = null,
    speechOutput: SpeechOutput? = null,
    private val sharedVoice: SpeechSessionCoordinator? = null,
    private val handsFreeControl: HandsFreeControl? = null,
    private val wakeInstaller: SpeechModelInstaller? = null,
    private val overlayControl: OverlayControl? = null,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val resources: ResourceMetrics? = null,
) : ViewModel() {
    val diagnosticReport = MutableStateFlow("")
    val diagnosticsEnabled = MutableStateFlow(diagnostics.enabled)

    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagnostics.setEnabled(enabled)
        diagnosticsEnabled.value = enabled
    }

    fun resetDiagnostics() {
        diagnostics.reset()
        diagnosticReport.value = ""
    }

    fun inspectDiagnostics() = perform("Diagnostic snapshot captured") {
        diagnosticReport.value = withContext(Dispatchers.IO) { diagnostics.export(resources?.read()) }
    }

    fun exportDiagnostics(uri: Uri) = perform("Diagnostic snapshot exported") {
        require(diagnosticReport.value.isNotEmpty()) { "Capture a diagnostic snapshot first" }
        documents.writeDiagnosticReport(uri, diagnosticReport.value)
    }

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val preview = MutableStateFlow<Configuration?>(null)
    private val controls = MutableStateFlow(CommandUiState())
    private val voiceRuntime = sharedVoice ?: if (runtime != null && speechEngine != null && speechModelInstaller != null && speechOutput != null) {
        SpeechSessionCoordinator(speechEngine, speechModelInstaller, runtime.dispatcher, speechOutput, viewModelScope) {
            repository.snapshots.first().configuration.settings.ttsEnabled
        }
    } else null
    private val voiceState = voiceRuntime?.state ?: MutableStateFlow<VoiceState>(VoiceState.Idle("Speech engine unavailable."))
    private val outputState = voiceRuntime?.outputState ?: MutableStateFlow<SpeechOutputState>(SpeechOutputState.Unavailable("Speech output unavailable."))
    private val modelInstalled = voiceRuntime?.modelInstalled ?: MutableStateFlow(false)
    private val baseState = combine(repository.snapshots, busy, message, preview, controls) { snapshot, working, notice, imported, commandState ->
        AssistantUiState(snapshot, working, notice, imported, commandState)
    }
    private val voiceUiState = combine(baseState, voiceState, outputState, modelInstalled) { shell, voice, speech, installed ->
        shell.copy(voice = voice, speechOutput = speech, modelInstalled = installed)
    }
    val state = combine(voiceUiState, handsFreeControl?.enabled ?: MutableStateFlow(false),
        overlayControl?.state ?: MutableStateFlow(OverlayRuntime())) { shell, enabled, overlay ->
        shell.copy(handsFreeEnabled = enabled, overlay = overlay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState())

    init {
        runtime?.let { engine ->
            viewModelScope.launch { engine.allowlist.snapshots.collect { saved ->
                controls.value = controls.value.copy(allowlist = saved)
            } }
            refreshControls()
        }
    }

    private fun controlAction(action: suspend (CommandRuntime) -> Unit) {
        val engine = runtime ?: return
        if (controls.value.working) return
        controls.value = controls.value.copy(working = true)
        viewModelScope.launch {
            try { action(engine)
            } catch (denied: SecurityException) {
                controls.value = controls.value.copy(result = CommandResult.PermissionRequired("device_control", "Android denied device control"))
            } catch (failure: IllegalStateException) {
                controls.value = controls.value.copy(result = CommandResult.Failed("Device integration unavailable", "DEVICE_STATE"))
            } catch (failure: IOException) {
                controls.value = controls.value.copy(result = CommandResult.Failed("Device settings could not be read", "STORAGE_READ"))
            } finally { controls.value = controls.value.copy(working = false) }
        }
    }

    fun refreshControls() = controlAction { engine ->
        val installed = engine.appDevice.installedApps()
        val status = CommandId.entries.associateWith { engine.dispatcher.availability(it) }
        controls.value = controls.value.copy(installedApps = installed, availability = status)
    }

    fun interpretCommand(transcript: String) = controlAction { engine ->
        controls.value = controls.value.copy(pending = null, transcript = transcript, interpreted = null, result = null)
        when (val prepared = engine.dispatcher.prepare(transcript)) {
            is Preparation.Ready -> controls.value = controls.value.copy(pending = prepared.pending, interpreted = prepared.pending.command.toString())
            is Preparation.Rejected -> controls.value = controls.value.copy(result = prepared.result)
        }
    }

    fun executeCommand() = controlAction { engine ->
        val pending = controls.value.pending ?: return@controlAction
        controls.value = controls.value.copy(pending = null)
        controls.value = controls.value.copy(result = engine.dispatcher.execute(pending))
    }

    fun cancelCommand() {
        if (controls.value.working) return
        val result = runtime?.dispatcher?.cancelPending() ?: CommandResult.InvalidParameters("Command engine unavailable")
        controls.value = controls.value.copy(pending = null, result = result)
    }

    fun startVoice() { voiceRuntime?.start() }
    fun finishVoice() { voiceRuntime?.finish() }
    fun cancelVoice() { voiceRuntime?.cancel() }
    fun cancelVoiceForLifecycle() { voiceRuntime?.cancelForLifecycle() }
    fun microphonePermissionDenied(permanentlyDenied: Boolean) {
        voiceRuntime?.microphonePermissionDenied(permanentlyDenied)
    }
    fun installSpeechModel(uri: Uri) { voiceRuntime?.installModel(uri) }
    fun installWakeModel(uri: Uri) { wakeInstaller?.let { voiceRuntime?.installWakeModel(uri, it) } }
    fun setHandsFreeEnabled(enabled: Boolean) {
        if (enabled) handsFreeControl?.enable() else handsFreeControl?.disable()
    }
    fun setTtsEnabled(enabled: Boolean) = perform("Speech response preference saved") {
        repository.update { it.copy(settings = it.settings.copy(ttsEnabled = enabled)) }
    }

    fun setOverlayEnabled(enabled: Boolean) = perform("Overlay preference saved") {
        repository.update { it.copy(settings = it.settings.copy(overlayEnabled = enabled)) }
        overlayControl?.refreshFromActivity()
    }

    fun setAutoStart(enabled: Boolean) = perform("Startup preference saved") {
        repository.update { it.copy(settings = it.settings.copy(autoStartAfterBoot = enabled)) }
    }

    fun setOverlayAppearance(size: Int, opacity: Int) = perform("Overlay appearance saved") {
        repository.update { it.copy(settings = it.settings.copy(overlaySizeDp = size, overlayOpacityPercent = opacity)) }
    }

    fun refreshOverlay() { overlayControl?.refreshFromActivity() }

    fun saveAllowedApp(packageName: String, aliases: List<String>) = perform("App allowlist saved") {
        val engine = runtime ?: throw IllegalArgumentException("App controller unavailable")
        require(engine.appDevice.installedApps().any { it.packageName == packageName }) { "Select a currently installed app" }
        engine.allowlist.save(AllowedApp(packageName, aliases.map(::normalizeAlias)))
    }

    fun removeAllowedApp(packageName: String) = perform("App removed from allowlist") { runtime?.allowlist?.remove(packageName) }
    fun resetAllowedApps() = perform("App allowlist reset") { runtime?.allowlist?.reset() }

    fun dismissMessage() { message.value = null }
    fun cancelImport() { preview.value = null }

    private fun perform(success: String, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
                message.value = success
            } catch (error: IllegalArgumentException) {
                message.value = error.message ?: "Invalid configuration"
            } catch (error: IOException) {
                message.value = "Storage operation failed. Saved configuration was not replaced."
            } catch (error: SecurityException) {
                message.value = "Document permission denied. Choose the document again."
            } finally {
                busy.value = false
            }
        }
    }

    fun setName(name: String) = perform("Name saved") {
        repository.update { it.copy(settings = it.settings.copy(displayName = name.trim())) }
    }

    fun setWakePhrase(phrase: String) = perform("Wake phrase saved") {
        require(handsFreeControl?.enabled?.value != true) { "Stop hands-free before changing the wake phrase." }
        repository.update { it.copy(settings = it.settings.copy(wakePhrase = phrase.trim())) }
    }

    fun setGrammarEnabled(enabled: Boolean) = perform("Grammar mode saved") {
        repository.update { it.copy(settings = it.settings.copy(grammarEnabled = enabled)) }
    }

    fun setAsrEngine(id: String) = perform("Speech engine saved") {
        require(id in AsrEngines.labels) { "Unknown offline speech engine" }
        val save: suspend () -> Unit = {
            repository.update { it.copy(settings = it.settings.copy(asrEngineId = id)) }
        }
        voiceRuntime?.changeEngine(save) ?: save()
    }

    fun setCommandAudioInput(source: String, gainPercent: Int) = perform("Command microphone saved") {
        repository.update { it.copy(settings = it.settings.copy(commandAudioSource = source, commandInputGainPercent = gainPercent)) }
    }

    fun setGrammarWords(words: List<String>) = perform("Custom grammar words saved") {
        val normalized = words.map(::normalizeAlias).filter(String::isNotEmpty).distinct()
        repository.update { it.copy(settings = it.settings.copy(grammarCustomWords = normalized)) }
    }

    fun setAssistantEnabled(enabled: Boolean) = perform("Assistant preference saved") {
        repository.update { it.copy(settings = it.settings.copy(assistantEnabled = enabled)) }
        overlayControl?.refreshFromActivity()
    }

    fun setCommandEnabled(id: CommandId, enabled: Boolean) = perform("Command preference saved") {
        repository.update { config -> config.copy(commands = config.commands.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }) }
    }

    fun setAliases(id: CommandId, aliases: List<String>) = perform("Aliases saved") {
        repository.update { config -> config.copy(commands = config.commands.map {
            if (it.id == id) it.copy(aliases = aliases.map(::normalizeAlias)) else it
        }) }
    }

    fun resetSettings() = perform("Settings reset") {
        repository.update { it.copy(settings = AssistantSettings()) }
        overlayControl?.refreshFromActivity()
    }

    fun resetCommands() = perform("Commands reset") {
        repository.update { it.copy(commands = CommandCatalogue.defaults()) }
    }

    fun resetAll() = perform("Safe defaults restored") {
        runtime?.allowlist?.reset()
        repository.replace(Configuration())
        overlayControl?.refreshFromActivity()
    }
    fun importDocument(uri: Uri) = perform("Import validated. Review before applying.") {
        preview.value = documents.read(uri)
    }

    fun applyImport() {
        val imported = preview.value ?: return
        perform("Configuration imported") {
            repository.replace(imported)
            overlayControl?.refreshFromActivity()
            preview.value = null
        }
    }

    fun exportDocument(uri: Uri) {
        val configuration = state.value.snapshot.configuration
        perform("Configuration exported") { documents.write(uri, configuration) }
    }

    override fun onCleared() {
        if (sharedVoice == null) voiceRuntime?.close() else voiceRuntime?.cancelForLifecycle()
        super.onCleared()
    }
}