package com.nexaai.assistant.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.*
import com.nexaai.assistant.data.*
import com.nexaai.assistant.diagnostics.DeviceFacts
import com.nexaai.assistant.domain.CommandResult
import com.nexaai.assistant.execution.InstalledApp
import com.nexaai.assistant.speech.*
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.provider.Settings

private enum class Destination(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home), COMMANDS("Commands", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings), MODELS("Models", Icons.Default.Storage),
    DIAGNOSTICS("Diagnostics", Icons.Default.Info), HELP("Help", Icons.AutoMirrored.Filled.HelpOutline),
}

@Composable
fun NexaApp(viewModel: AssistantViewModel, device: DeviceFacts) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configuration = state.snapshot.configuration
    val editable = state.snapshot.ready && !state.busy && state.snapshot.warning == null
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route ?: Destination.HOME.name
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reset by rememberSaveable { mutableStateOf<String?>(null) }
    var microphoneRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var pendingHandsFree by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setHandsFreeEnabled(true)
    }
    val enableHandsFree = {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.setHandsFreeEnabled(true)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importDocument)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportDocument)
    }
    val modelImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installSpeechModel)
    }
    val wakeImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installWakeModel)
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (pendingHandsFree) enableHandsFree() else viewModel.startVoice()
        } else {
            val activity = context as? Activity
            val permanent = microphoneRequestAttempted && activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            viewModel.microphonePermissionDenied(permanent)
        }
        pendingHandsFree = false
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.cancelVoiceForLifecycle()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(current) {
        if (current != Destination.HOME.name) viewModel.cancelVoiceForLifecycle()
    }
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF006B57), secondary = Color(0xFF765400),
        background = Color(0xFFF4F7F6), surface = Color(0xFFF4F7F6),
    )) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 600.dp
                Row {
                    if (wide) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                        Destination.entries.forEach { destination ->
                            NavigationRailItem(selected = current == destination.name,
                                onClick = { navController.navigate(destination.name) { launchSingleTop = true; popUpTo(Destination.HOME.name) { saveState = true }; restoreState = true } },
                                icon = { Icon(destination.icon, null) }, label = { Text(destination.title, fontSize = 12.sp) },
                                modifier = Modifier.testTag("nav_${destination.name}"))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        if (!wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Destination.entries.forEach { destination ->
                                TextButton(onClick = { navController.navigate(destination.name) { launchSingleTop = true; popUpTo(Destination.HOME.name) { saveState = true }; restoreState = true } },
                                    modifier = Modifier.heightIn(min = 56.dp).testTag("nav_${destination.name}")) {
                                    Icon(destination.icon, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp)); Text(destination.title)
                                }
                            }
                        }
                        if (state.busy || !state.snapshot.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.snapshot.warning?.let { warning ->
                            Text(warning, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { reset = "all" }, enabled = !state.busy) { Text("Restore safe defaults") }
                        }
                        state.message?.let { message ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f).testTag("notice"))
                                IconButton(onClick = viewModel::dismissMessage) { Icon(Icons.Default.Close, "Dismiss message") }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Hands-free: ${configuration.settings.wakePhrase}", style = MaterialTheme.typography.titleMedium)
                                Text(if (state.handsFreeEnabled) when (state.voice) {
                                    is VoiceState.WaitingForWakeWord -> "Waiting for wake word - microphone active"
                                    is VoiceState.Listening -> "Listening for command"
                                    VoiceState.SpeakingResponse -> "Speaking - microphone suspended"
                                    else -> "Hands-free session active"
                                } else "Stopped")
                            }
                            if (state.handsFreeEnabled) IconButton(onClick = { viewModel.setHandsFreeEnabled(false) },
                                modifier = Modifier.testTag("stop_hands_free")) { Icon(Icons.Default.Stop, "Stop hands-free immediately") }
                            Switch(state.handsFreeEnabled, { enabled ->
                                if (!enabled) viewModel.setHandsFreeEnabled(false)
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) enableHandsFree()
                                else {
                                    pendingHandsFree = true
                                    microphoneRequestAttempted = true
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }, enabled = state.handsFreeEnabled || (editable && configuration.settings.assistantEnabled &&
                                !state.controls.working && state.voice !is VoiceState.Initializing && state.voice !is VoiceState.Listening),
                                modifier = Modifier.testTag("hands_free_enabled").semantics { contentDescription = "Hands-free enabled" })
                        }
                        (state.voice as? VoiceState.Error)?.let {
                            Text(it.message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                        }
                        NavHost(navController, Destination.HOME.name, Modifier.weight(1f)) {
                            composable(Destination.HOME.name) {
                                HomeScreen(configuration, state.controls, editable, state.handsFreeEnabled, viewModel::interpretCommand,
                                    viewModel::executeCommand, viewModel::cancelCommand, state.voice,
                                    state.speechOutput, state.modelInstalled,
                                    {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            viewModel.startVoice()
                                        } else {
                                            pendingHandsFree = false
                                            microphoneRequestAttempted = true
                                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }, viewModel::finishVoice, viewModel::cancelVoice,
                                    { navController.navigate(Destination.MODELS.name) }, {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            "package:${context.packageName}".toUri()))
                                    })
                            }
                            composable(Destination.COMMANDS.name) {
                                CommandsScreen(configuration, editable, viewModel::setCommandEnabled, viewModel::setAliases,
                                    { importer.launch(arrayOf("application/json", "text/plain")) },
                                    { exporter.launch("nexaai-configuration.json") }, { reset = "commands" },
                                    state.controls, viewModel::saveAllowedApp, viewModel::removeAllowedApp,
                                    viewModel::refreshControls, { reset = "apps" })
                            }
                            composable(Destination.SETTINGS.name) {
                                SettingsScreen(configuration.settings, editable, viewModel::setName, viewModel::setAssistantEnabled,
                                    { reset = "settings" }, { importer.launch(arrayOf("application/json", "text/plain")) },
                                    { exporter.launch("nexaai-configuration.json") }, viewModel::setTtsEnabled, state.speechOutput,
                                    state.handsFreeEnabled, viewModel::setWakePhrase)
                            }
                            composable(Destination.MODELS.name) { ModelsScreen(state.modelInstalled, state.voice,
                                configuration.settings.wakePhrase, state.handsFreeEnabled, { wakeImporter.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                modelImporter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            } }
                            composable(Destination.DIAGNOSTICS.name) { DiagnosticsScreen(device, state.controls, state.modelInstalled, state.speechOutput, viewModel::refreshControls) }
                            composable(Destination.HELP.name) { HelpScreen(configuration.settings) }
                        }
                    }
                }
            }
        }
        reset?.let { target ->
            AlertDialog(onDismissRequest = { reset = null }, title = { Text("Reset $target?") },
                text = { Text("Saved ${if (target == "all") "configuration" else target} will be replaced with safe defaults. This cannot be undone.") },
                confirmButton = { TextButton(onClick = {
                    when (target) { "settings" -> viewModel.resetSettings(); "commands" -> viewModel.resetCommands(); "apps" -> viewModel.resetAllowedApps(); else -> viewModel.resetAll() }
                    reset = null
                }, enabled = !state.busy) { Text("Reset") } },
                dismissButton = { TextButton(onClick = { reset = null }) { Text("Cancel") } })
        }
        state.importPreview?.let { imported ->
            val changed = imported.commands.count { command -> configuration.commands.find { it.id == command.id } != command }
            AlertDialog(onDismissRequest = viewModel::cancelImport, title = { Text("Review configuration import") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Schema ${imported.schemaVersion}\nName: ${imported.settings.displayName}\nAssistant: ${if (imported.settings.assistantEnabled) "enabled" else "disabled"}\n$changed command definitions changed\n${imported.commands.count { it.enabled }} commands enabled\nEnabled commands may execute supported device actions. Microphone remains off. App allowlist is not imported.")
                    imported.commands.filter { command -> configuration.commands.find { it.id == command.id } != command }.forEach {
                        Text("${CommandCatalogue.spec(it.id).title}: ${if (it.enabled) "enabled" else "disabled"}; ${it.aliases.joinToString()}", Modifier.padding(top = 8.dp))
                    }
                } }, confirmButton = { TextButton(onClick = viewModel::applyImport, enabled = !state.busy) { Text("Apply import") } },
                dismissButton = { TextButton(onClick = viewModel::cancelImport, enabled = !state.busy) { Text("Cancel") } })
        }
    }
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, status: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(status, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun HomeScreen(configuration: Configuration, controls: CommandUiState, editable: Boolean, handsFree: Boolean,
    interpret: (String) -> Unit, execute: () -> Unit, cancel: () -> Unit,
    voice: VoiceState, speechOutput: SpeechOutputState, modelInstalled: Boolean,
    startListening: () -> Unit, finishListening: () -> Unit, cancelListening: () -> Unit,
    openModels: () -> Unit, openPermissionSettings: () -> Unit) = Page("NexaAI") {
    val voiceBusy = handsFree || voice is VoiceState.Initializing || voice is VoiceState.Listening ||
        voice is VoiceState.Processing || voice is VoiceState.Recognized || voice is VoiceState.Executing
    val listening = voice is VoiceState.Listening
    Text(configuration.settings.displayName, style = MaterialTheme.typography.titleLarge)
    Text(when {
        !configuration.settings.assistantEnabled -> "Assistant disabled"
        listening -> "Listening. Tap Finish when you are done speaking."
        voice is VoiceState.Initializing -> "Loading offline speech model..."
        voice is VoiceState.Processing -> "Processing speech locally..."
        voice is VoiceState.Recognized -> "Transcript recognized"
        voice is VoiceState.Executing -> "Executing validated command..."
        controls.working -> "Processing command - microphone off"
        else -> "Idle - ${if (modelInstalled) "offline model ready" else "offline model not installed"}"
    }, Modifier.testTag("assistant_state"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledIconButton(onClick = if (listening) finishListening else startListening,
            enabled = !handsFree && configuration.settings.assistantEnabled && (listening || !voiceBusy) && !controls.working,
            modifier = Modifier.size(88.dp).testTag("microphone").semantics {
                contentDescription = if (listening) "Finish voice command" else "Start push-to-talk voice command"
            }) {
            Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic,
                if (listening) "Finish listening" else "Start listening", Modifier.size(40.dp))
        }
        Column {
            Text(if (listening) "Finish" else "Push to talk", style = MaterialTheme.typography.titleMedium)
            Text("Audio is processed locally and not saved.")
        }
    }
    if (voiceBusy && !handsFree && !listening && voice !is VoiceState.Executing) {
        TextButton(onClick = cancelListening) { Icon(Icons.Default.Close, null); Text("Cancel") }
    }
    if (!modelInstalled) {
        Text("Offline English model is not installed.")
        TextButton(onClick = openModels) { Icon(Icons.Default.Storage, null); Text("Install speech model") }
    }
    when (voice) {
        is VoiceState.Idle -> voice.notice?.let { Text(it, Modifier.testTag("voice_status")) }
        is VoiceState.Listening -> Text("Partial transcript: ${voice.partialTranscript.ifBlank { "..." }}", Modifier.testTag("voice_transcript"))
        is VoiceState.Recognized -> Text("Transcript: ${voice.transcript}", Modifier.testTag("voice_transcript"))
        is VoiceState.Executing -> Text("Transcript: ${voice.transcript}", Modifier.testTag("voice_transcript"))
        is VoiceState.Completed -> {
            Text("Transcript: ${voice.transcript}", Modifier.testTag("voice_transcript"))
            Text("${voice.result.javaClass.simpleName}: ${voice.result.message}", Modifier.testTag("voice_result"))
        }
        is VoiceState.Error -> {
            Text(voice.message, Modifier.testTag("voice_error"), color = MaterialTheme.colorScheme.error)
            if (voice.message.contains("app settings", ignoreCase = true)) {
                TextButton(onClick = openPermissionSettings) { Icon(Icons.Default.Settings, null); Text("Microphone settings") }
            }
        }
        VoiceState.Initializing -> Unit
        VoiceState.Processing -> Unit
        is VoiceState.WaitingForWakeWord -> {
            Text("Waiting for wake word", Modifier.testTag("voice_status"))
            voice.previous?.let { Text("${it.transcript}: ${it.result.message}") }
        }
        VoiceState.WakeWordDetected -> Text("Wake word detected")
        VoiceState.SpeakingResponse -> Text("Speaking response")
    }
    Text(when (speechOutput) {
        SpeechOutputState.Initializing -> "Offline speech output: checking installed voices"
        SpeechOutputState.Ready -> if (configuration.settings.ttsEnabled) "Offline speech responses enabled" else "Offline speech responses muted"
        is SpeechOutputState.Unavailable -> "Speech output unavailable: ${speechOutput.message}"
    })
    Section("Command status", "${configuration.commands.count { it.enabled }} of ${configuration.commands.size} definitions enabled.")
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, label = { Text("Development command") },
        modifier = Modifier.fillMaxWidth().testTag("command_input"), enabled = !controls.working && !voiceBusy)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { interpret(text) }, enabled = editable && !controls.working && !voiceBusy && text.isNotBlank(), modifier = Modifier.testTag("interpret_command")) {
            Icon(Icons.Default.Search, null); Text("Interpret")
        }
        Button(onClick = execute, enabled = editable && !controls.working && !voiceBusy && controls.pending != null, modifier = Modifier.testTag("execute_command")) {
            Icon(Icons.Default.PlayArrow, null); Text("Execute")
        }
        TextButton(onClick = cancel, enabled = !controls.working && !voiceBusy && controls.pending != null, modifier = Modifier.testTag("cancel_command")) {
            Icon(Icons.Default.Close, null); Text("Cancel")
        }
    }
    if (controls.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("Transcript: ${controls.transcript.ifEmpty { "none" }}\nInterpreted command: ${controls.interpreted ?: "none"}")
    controls.result?.let { Text("${it.javaClass.simpleName}: ${it.message}", Modifier.testTag("command_result")) }
}

@Composable
private fun CommandsScreen(
    configuration: Configuration, editable: Boolean,
    setEnabled: (CommandId, Boolean) -> Unit, setAliases: (CommandId, List<String>) -> Unit,
    importConfig: () -> Unit, exportConfig: () -> Unit, reset: () -> Unit,
    controls: CommandUiState, saveApp: (String, List<String>) -> Unit, removeApp: (String) -> Unit,
    refresh: () -> Unit, resetApps: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<CommandDefinition?>(null) }
    Page("Commands") {
        Text("Aliases and enablement apply without restarting. Device actions can be tested from Home.")
        OutlinedTextField(search, { search = it }, label = { Text("Search commands") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("command_search"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = importConfig, enabled = editable) { Icon(Icons.Default.FileOpen, null); Text("Import") }
            TextButton(onClick = exportConfig, enabled = editable) { Icon(Icons.Default.SaveAlt, null); Text("Export") }
            TextButton(onClick = reset, enabled = editable) { Icon(Icons.Default.Restore, null); Text("Reset commands") }
        }
        AppAllowlistEditor(controls, editable, saveApp, removeApp, refresh, resetApps)
        val filtered = configuration.commands.filter { definition ->
            CommandCatalogue.spec(definition.id).title.contains(search, true) || definition.aliases.any { it.contains(search, true) }
        }
        if (filtered.isEmpty()) Text("No matching commands")
        filtered.forEach { definition ->
            val spec = CommandCatalogue.spec(definition.id)
            OutlinedCard(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(definition.enabled, { setEnabled(definition.id, it) }, enabled = editable,
                            modifier = Modifier.testTag("enabled_${definition.id}").semantics { contentDescription = "${spec.title} definition" }, thumbContent = { Icon(if (definition.enabled) Icons.Default.Check else Icons.Default.Close, if (definition.enabled) "Definition enabled" else "Definition disabled", Modifier.size(16.dp)) })
                    }
                    Text(definition.aliases.joinToString("\n"))
                    Text(spec.parameters)
                    Text(controls.availability[definition.id] ?: "Capability not checked")
                    TextButton(onClick = { editing = definition }, enabled = editable, modifier = Modifier.testTag("edit_${definition.id}")) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit aliases")
                    }
                }
            }
        }
    }
    editing?.let { definition ->
        AliasDialog(definition, configuration, onDismiss = { editing = null }) { aliases ->
            setAliases(definition.id, aliases); editing = null
        }
    }
}

@Composable
private fun AliasDialog(definition: CommandDefinition, configuration: Configuration, onDismiss: () -> Unit, save: (List<String>) -> Unit) {
    var aliases by remember { mutableStateOf(definition.aliases) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${CommandCatalogue.spec(definition.id).title} aliases") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CommandCatalogue.spec(definition.id).placeholder?.let { Text("Required parameter: $it") }
            aliases.forEachIndexed { index, alias ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(alias, { changed -> aliases = aliases.mapIndexed { position, existing -> if (position == index) changed else existing } },
                        label = { Text("Alias ${index + 1}") }, modifier = Modifier.weight(1f).testTag("alias_$index"))
                    IconButton(onClick = { aliases = aliases.filterIndexed { position, _ -> position != index } }, enabled = aliases.size > 1) {
                        Icon(Icons.Default.Delete, "Remove alias ${index + 1}")
                    }
                }
            }
            TextButton(onClick = { aliases = aliases + "" }, enabled = aliases.size < 12) { Icon(Icons.Default.Add, null); Text("Add alias") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("alias_error")) }
        } }, confirmButton = { TextButton(onClick = {
            val normalized = aliases.map(::normalizeAlias)
            try {
                ConfigurationValidator.validate(configuration.copy(commands = configuration.commands.map {
                    if (it.id == definition.id) it.copy(aliases = normalized) else it
                }))
                save(normalized)
            } catch (invalid: IllegalArgumentException) { error = invalid.message }
        }, modifier = Modifier.testTag("save_aliases")) { Text("Save aliases") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SettingsScreen(settings: AssistantSettings, editable: Boolean, setName: (String) -> Unit,
    setEnabled: (Boolean) -> Unit, reset: () -> Unit, importConfig: () -> Unit, exportConfig: () -> Unit,
    setTtsEnabled: (Boolean) -> Unit, speechOutput: SpeechOutputState, handsFree: Boolean, setWakePhrase: (String) -> Unit) {
    var name by rememberSaveable(settings.displayName) { mutableStateOf(settings.displayName) }
    var wakePhrase by rememberSaveable(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    val phraseError = runCatching { ConfigurationValidator.validateWakePhrase(wakePhrase.trim()) }.exceptionOrNull()?.message
    Page("Settings") {
        Text("General", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Assistant enabled", Modifier.weight(1f))
            Switch(settings.assistantEnabled, setEnabled, enabled = editable, modifier = Modifier.testTag("assistant_enabled").semantics { contentDescription = "Assistant enabled" })
        }
        OutlinedTextField(name, { name = it }, label = { Text("Assistant display name") }, enabled = editable,
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("display_name"), supportingText = { Text("1-40 characters") })
        Button(onClick = { setName(name) }, enabled = editable && name.trim().isNotEmpty() && name.trim().length <= 40, modifier = Modifier.testTag("save_name")) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save name")
        }
        Text("Offline-only: enforced. No network permission. Haptics and debug logs unavailable; no logs stored.")
        HorizontalDivider()
        Text("Listening", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(wakePhrase, { wakePhrase = it }, label = { Text("Wake phrase") },
            enabled = editable && !handsFree, singleLine = true, isError = phraseError != null,
            supportingText = { phraseError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag("wake_phrase"))
        Button(onClick = { setWakePhrase(wakePhrase) }, enabled = editable && !handsFree && phraseError == null,
            modifier = Modifier.testTag("save_wake_phrase")) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save wake phrase")
        }
        Section("Startup and service", "Hands-free uses a microphone foreground service with an ongoing notification. Automatic startup and resume after reboot or process death are off. Screen-off and firmware sleep behavior are unverified.")
        Section("Overlay", "Off. No overlay implementation or permission requested (Milestone 5). Size, opacity, position and tap controls unavailable.")
        Section("Voice response", when (speechOutput) {
            SpeechOutputState.Initializing -> "Checking installed English voices..."
            SpeechOutputState.Ready -> "An installed English offline voice is available. Responses replace any current utterance."
            is SpeechOutputState.Unavailable -> speechOutput.message
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speak command results", Modifier.weight(1f))
            Switch(settings.ttsEnabled, setTtsEnabled, enabled = editable && (speechOutput == SpeechOutputState.Ready || settings.ttsEnabled),
                modifier = Modifier.testTag("tts_enabled").semantics { contentDescription = "Speak command results" })
        }
        if (speechOutput is SpeechOutputState.Unavailable) Text("On-screen results remain available. Configure an installed English offline voice in Android Settings > System > Languages & input > Text-to-speech output, then close and relaunch NexaAI.")
        Section("AI model", "Off. No runtime or models. Model import, context/output limits and inactivity unloading unavailable (Milestone 7).")
        Section("Device integration", "Music-stream volume, allowlisted app launching, media-session controls and the built-in radio request adapter are installed. Radio receiver access and target hardware behavior remain unverified. No root/system access.")
        Section("Configuration", "Version 1. Name, assistant preference and command edits apply without restarting. Unsupported features cannot be enabled through an import.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = importConfig, enabled = editable) { Icon(Icons.Default.FileOpen, null); Text("Import") }
            TextButton(onClick = exportConfig, enabled = editable) { Icon(Icons.Default.SaveAlt, null); Text("Export") }
            TextButton(onClick = reset, enabled = editable) { Icon(Icons.Default.Restore, null); Text("Reset settings") }
        }
    }
}

@Composable
private fun ModelsScreen(modelInstalled: Boolean, voice: VoiceState, wakePhrase: String, handsFree: Boolean, importWakeModel: () -> Unit,
    importModel: () -> Unit) = Page("Models") {
    Icon(Icons.Default.Storage, null, Modifier.size(40.dp))
    Text(if (modelInstalled) "Vosk English model installed" else "No installed speech model", style = MaterialTheme.typography.titleLarge)
    Text("Speech: ${if (modelInstalled) "offline model installed" else "unavailable"}\nWake word: sherpa-onnx 1.12.14; $wakePhrase\nLocal LLM: off")
    Text("Import the official vosk-model-small-en-us-0.15 ZIP (about 40 MB). It is stored in private app files; allow up to 160 MB expanded. No download is automatic.")
    Text("Model license: Apache-2.0. Import requires the official model ZIP selected from local storage.")
    val canImport = !handsFree && voice !is VoiceState.Initializing && voice !is VoiceState.Listening
    Button(onClick = importModel, enabled = canImport) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose model ZIP") }
    Button(onClick = importWakeModel, enabled = canImport) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose wake-word ZIP") }
    Text("Wake model: English GigaSpeech 3.3M, Apache-2.0. Required: encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt, and one phrase in keywords.txt matching the saved wake phrase. Setup: docs/MODELS.md. No model is downloaded automatically.")
    (voice as? VoiceState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    Text("The model is loaded only on explicit listening/model installation. Target memory, latency, ABI and accuracy still need physical-device testing.")
}

@Composable
private fun DiagnosticsScreen(device: DeviceFacts, controls: CommandUiState, modelInstalled: Boolean,
    speechOutput: SpeechOutputState, refresh: () -> Unit) = Page("Diagnostics") {
    Section("This device", "${device.manufacturer} / ${device.model}\nAndroid ${device.androidVersion}, API ${device.api}")
    Section("Permissions", "Microphone: ${if (device.microphoneGranted) "granted" else "not granted; requested on explicit listening action"}.\nMicrophone foreground-service and notification permissions declared. Internet, overlay and boot permissions: not declared.")
    Section("Integrations", "Volume: ${controls.availability[CommandId.SET_VOLUME] ?: "not checked"}\nMedia: ${controls.availability[CommandId.PLAY] ?: "not checked"}\nApp launch: ${controls.availability[CommandId.LAUNCH_APP] ?: "not checked"}\nRadio: ${controls.availability[CommandId.OPEN_RADIO] ?: "not checked"}; receiver and tuner behavior unverified\nMicrophone/recognition: use Home push-to-talk\nHands-free: explicit-start microphone service; target unverified\nOverlay/boot: not implemented")
    Section("Speech", "Vosk model: ${if (modelInstalled) "installed" else "missing"}\nSpeech output: ${when (speechOutput) {
        SpeechOutputState.Initializing -> "checking"
        SpeechOutputState.Ready -> "offline English voice available"
        is SpeechOutputState.Unavailable -> speechOutput.message
    }}")
    TextButton(onClick = refresh, enabled = !controls.working) { Icon(Icons.Default.Refresh, null); Text("Refresh capabilities") }
    MediaAccessSettings()
    Section("Evidence", "No integration tests performed on target hardware. Target ABI, actual RAM, free storage and firmware interface still require verification. No audio, transcripts or local error logs stored.")
    Text("Diagnostic export unavailable; configuration export excludes device identifiers.")
}

@Composable
private fun HelpScreen(settings: AssistantSettings) = Page("Help and privacy") {
    Section("Current state", "${if (settings.assistantEnabled) "Assistant preference enabled" else "Assistant disabled"}. Push-to-talk or explicitly enabled ${settings.wakePhrase} hands-free. Background listening has an ongoing notification and Stop action.")
    Section("Offline setup", "Import the official Vosk English small-model ZIP from Models. TTS uses only an installed English offline Android voice; no voice data is downloaded automatically.")
    Section("Normal APK limits", "No force-stop of other apps, hidden radio API, root or system privilege. Radio and media availability require real firmware/app evidence.")
    Section("Your data", "Audio is processed locally and not saved or uploaded. Transcripts/results remain in memory only. Settings, command definitions and the app allowlist stay in private app storage. No cloud APIs, telemetry or analytics.")
    Section("Stop and disable", "Stop hands-free using the listening switch, Stop control, or notification. Leaving Home cancels push-to-talk, not an enabled hands-free session. Spoken results can be muted in Settings. Overlay, boot startup and LLM remain off.")
}

@Composable
private fun AppAllowlistEditor(controls: CommandUiState, editable: Boolean,
    save: (String, List<String>) -> Unit, remove: (String) -> Unit, refresh: () -> Unit, reset: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    var names by rememberSaveable { mutableStateOf("") }
    Section("Allowed apps", "Only explicitly selected installed apps can launch. Friendly names must be unique.")
    controls.allowlist.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = editable && controls.installedApps.isNotEmpty()) {
                Icon(Icons.Default.Apps, null); Text(selected?.label ?: "Select installed app")
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                controls.installedApps.forEach { app ->
                    DropdownMenuItem(text = { Text("${app.label}\n${app.packageName}") }, onClick = {
                        selected = app
                        names = controls.allowlist.apps.find { it.packageName == app.packageName }?.aliases?.joinToString(", ") ?: normalizeAlias(app.label)
                        expanded = false
                    })
                }
            }
        }
        TextButton(onClick = refresh, enabled = !controls.working) { Icon(Icons.Default.Refresh, null); Text("Refresh apps") }
        TextButton(onClick = reset, enabled = editable) { Icon(Icons.Default.Restore, null); Text("Reset app allowlist") }
    }
    if (controls.installedApps.isEmpty()) Text("No visible launchable apps found")
    OutlinedTextField(names, { names = it }, enabled = editable && selected != null, label = { Text("Friendly names, comma separated") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { selected?.let { save(it.packageName, names.split(',')) } }, enabled = editable && selected != null && names.isNotBlank()) {
        Icon(Icons.Default.Save, null); Text("Save allowed app")
    }
    controls.allowlist.apps.forEach { app ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${app.aliases.joinToString()}\n${app.packageName}", Modifier.weight(1f))
            IconButton(onClick = {
                selected = controls.installedApps.find { it.packageName == app.packageName }
                names = app.aliases.joinToString(", ")
            }, enabled = editable && controls.installedApps.any { it.packageName == app.packageName }) { Icon(Icons.Default.Edit, "Edit friendly names") }
            IconButton(onClick = { remove(app.packageName) }, enabled = editable) { Icon(Icons.Default.Delete, "Remove allowed app") }
        }
    }
    MediaAccessSettings()
}

@Composable
private fun MediaAccessSettings() {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Text("Media access is optional. Android notification-listener access is broad; NexaAI uses it only to obtain active media sessions and does not store or inspect notification contents.")
    TextButton(onClick = {
        try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (missing: ActivityNotFoundException) { error = "Notification access settings unavailable on this firmware"
        } catch (denied: SecurityException) { error = "Android denied opening notification access settings" }
    }) { Icon(Icons.Default.Settings, null); Text("Media access settings") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}