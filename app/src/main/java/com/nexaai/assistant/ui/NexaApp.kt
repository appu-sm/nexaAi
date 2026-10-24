package com.nexaai.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.navigation.compose.*
import com.nexaai.assistant.data.*
import com.nexaai.assistant.diagnostics.DeviceFacts
import com.nexaai.assistant.domain.CommandResult
import com.nexaai.assistant.execution.InstalledApp
import android.content.Intent
import android.content.ActivityNotFoundException
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
    var reset by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importDocument)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportDocument)
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
                        NavHost(navController, Destination.HOME.name, Modifier.weight(1f)) {
                            composable(Destination.HOME.name) {
                                HomeScreen(configuration, state.controls, editable, viewModel::interpretCommand,
                                    viewModel::executeCommand, viewModel::cancelCommand)
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
                                    { exporter.launch("nexaai-configuration.json") })
                            }
                            composable(Destination.MODELS.name) { ModelsScreen() }
                            composable(Destination.DIAGNOSTICS.name) { DiagnosticsScreen(device, state.controls, viewModel::refreshControls) }
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
private fun HomeScreen(configuration: Configuration, controls: CommandUiState, editable: Boolean,
    interpret: (String) -> Unit, execute: () -> Unit, cancel: () -> Unit) = Page("NexaAI") {
    Text(configuration.settings.displayName, style = MaterialTheme.typography.titleLarge)
    Text(if (!configuration.settings.assistantEnabled) "Assistant disabled" else if (controls.working) "Processing command - microphone off" else "Idle - speech unavailable", Modifier.testTag("assistant_state"))
    FilledIconButton(onClick = {}, enabled = false, modifier = Modifier.size(88.dp).testTag("microphone")) {
        Icon(Icons.Default.MicOff, "Microphone unavailable: offline speech engine and model not installed", Modifier.size(40.dp))
    }
    Text("Microphone off. Offline speech engine and model not installed (Milestone 3).")
    Text("No network permission. Typed command execution available; microphone off.")
    Section("Command status", "${configuration.commands.count { it.enabled }} of ${configuration.commands.size} definitions enabled.")
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, label = { Text("Development command") },
        modifier = Modifier.fillMaxWidth().testTag("command_input"), enabled = !controls.working)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { interpret(text) }, enabled = editable && !controls.working && text.isNotBlank(), modifier = Modifier.testTag("interpret_command")) {
            Icon(Icons.Default.Search, null); Text("Interpret")
        }
        Button(onClick = execute, enabled = editable && !controls.working && controls.pending != null, modifier = Modifier.testTag("execute_command")) {
            Icon(Icons.Default.PlayArrow, null); Text("Execute")
        }
        TextButton(onClick = cancel, enabled = !controls.working && controls.pending != null, modifier = Modifier.testTag("cancel_command")) {
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
    setEnabled: (Boolean) -> Unit, reset: () -> Unit, importConfig: () -> Unit, exportConfig: () -> Unit) {
    var name by rememberSaveable(settings.displayName) { mutableStateOf(settings.displayName) }
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
        Text("Offline-only: enforced. No network permission. Confirmation: short (inactive). Haptics and debug logs unavailable; no logs stored.")
        Section("Listening", "Push-to-talk selected. Engine/model, microphone and recognition tests unavailable (Milestone 3). Timeouts and language/model selection unavailable until an engine is installed.")
        Text("Wake word off; no detector installed (Milestone 6). Desired phrase: Hey Nexa. Phrase text does not train a detector; sensitivity unavailable.")
        Section("Startup and service", "Off. No boot receiver, foreground service or background microphone (Milestone 5). Resume listening off. Notification and battery settings are not applicable without a service.")
        Section("Overlay", "Off. No overlay implementation or permission requested (Milestone 5). Size, opacity, position and tap controls unavailable.")
        Section("Voice response", "Off. TTS/voice selection, rate, pitch and test voice unavailable (Milestone 3). No installed offline voices verified.")
        Section("AI model", "Off. No runtime or models. Model import, context/output limits and inactivity unloading unavailable (Milestone 7).")
        Section("Device integration", "Music-stream volume, allowlisted app launching and media-session controls are installed. Runtime availability is shown in Commands and Diagnostics. Radio is unsupported. Target hardware remains unverified. No root/system access.")
        Section("Configuration", "Version 1. Name, assistant preference and command edits apply without restarting. Unsupported features cannot be enabled through an import.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = importConfig, enabled = editable) { Icon(Icons.Default.FileOpen, null); Text("Import") }
            TextButton(onClick = exportConfig, enabled = editable) { Icon(Icons.Default.SaveAlt, null); Text("Export") }
            TextButton(onClick = reset, enabled = editable) { Icon(Icons.Default.Restore, null); Text("Reset settings") }
        }
    }
}

@Composable
private fun ModelsScreen() = Page("Models") {
    Icon(Icons.Default.Storage, null, Modifier.size(40.dp))
    Text("No installed models", style = MaterialTheme.typography.titleLarge)
    Text("Speech: unavailable\nWake word: unavailable\nLocal LLM: off\nOffline TTS voices: not inspected")
    Text("Model import and deletion unavailable until a validated runtime and model store are implemented (Milestone 3+). No automatic downloads.")
    Text("Source/license, checksum, language, file size and measured performance: no installed model to inspect.")
}

@Composable
private fun DiagnosticsScreen(device: DeviceFacts, controls: CommandUiState, refresh: () -> Unit) = Page("Diagnostics") {
    Section("This device", "${device.manufacturer} / ${device.model}\nAndroid ${device.androidVersion}, API ${device.api}")
    Section("Permissions", "Microphone: ${if (device.microphoneGranted) "granted" else "not granted"}; not requested by this build.\nInternet, overlay, boot and foreground-service permissions: not declared.")
    Section("Integrations", "Volume: ${controls.availability[CommandId.SET_VOLUME] ?: "not checked"}\nMedia: ${controls.availability[CommandId.PLAY] ?: "not checked"}\nApp launch: ${controls.availability[CommandId.LAUNCH_APP] ?: "not checked"}\nRadio: unsupported; no verified adapter\nMicrophone/recognition test: unavailable\nTTS offline availability: unverified\nOverlay/service/boot: not implemented")
    TextButton(onClick = refresh, enabled = !controls.working) { Icon(Icons.Default.Refresh, null); Text("Refresh capabilities") }
    MediaAccessSettings()
    Section("Evidence", "No integration tests performed on target hardware. Target ABI, actual RAM, free storage and firmware interface still require verification. No audio, transcripts or local error logs stored.")
    Text("Diagnostic export unavailable; configuration export excludes device identifiers.")
}

@Composable
private fun HelpScreen(settings: AssistantSettings) = Page("Help and privacy") {
    Section("Current state", "${if (settings.assistantEnabled) "Assistant preference enabled" else "Assistant disabled"}. Push-to-talk selected; microphone off. No recording or speech engine is active.")
    Section("Offline setup", "No models bundled. Speech setup is unavailable until Milestone 3. Local settings need no network; offline speech has not been tested.")
    Section("Normal APK limits", "No force-stop of other apps, hidden radio API, root or system privilege. Radio and media availability require real firmware/app evidence.")
    Section("Your data", "Settings, command definitions and the local app allowlist stay in private DataStore storage. No audio recordings, cloud APIs, telemetry or analytics. Exported configuration contains only settings and command definitions, not the app allowlist. Development transcripts/results remain in memory only.")
    Section("Stop and disable", "Nothing is listening in this build. Assistant preference can be disabled in Settings. Overlay, boot startup, wake word and LLM are unavailable and off.")
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