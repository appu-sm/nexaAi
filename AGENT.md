# NexaAI --- End-to-End Android Head-Unit Assistant Build Guide

> **Purpose:** This file is the source of truth for GitHub Copilot and
> other coding agents implementing NexaAI, an offline-first,
> voice-controlled assistant for a low-memory aftermarket Android head
> unit.
>
> **Target device:** NWD_K2501 (T5) based, firmware identifier
> `F52L_UPGZ_S212A70`, Android 9--11, RAM 2 GB or less, built-in FM
> radio, normal APK installation only (no root/system privileges
> assumed).
>
> **Product name:** NexaAI\
> **Primary objective:** Reliably execute explicitly supported voice
> commands offline, with every practical feature configurable in the
> app. Do not claim a feature works on the target head unit until it has
> been tested on that device.

------------------------------------------------------------------------

## 0. Instructions to the coding agent

You are the senior Android engineer responsible for designing,
implementing, testing, and documenting NexaAI end to end.

1.  **Inspect before changing.** If a repository already exists, inspect
    its files, Gradle setup, package name, SDK levels, architecture, and
    tests before modifying anything. Preserve working code and
    conventions where reasonable.
2.  **Work in small, buildable increments.** Implement one milestone at
    a time. After each milestone, build and run tests where tooling
    permits. Fix failures before continuing.
3.  **Do not stop at a plan or scaffolding.** Create the actual Kotlin
    source, resources, manifest entries, tests, documentation, and build
    configuration needed for the milestone.
4.  **Do not fake hardware support.** Radio tuning, app force-stop,
    wake-word listening, auto-start, overlay, and media controls depend
    on Android version, permissions, app APIs, and OEM firmware.
    Implement capability detection and truthful UI states. If support
    cannot be verified, mark it unsupported or unverified and document
    what evidence is needed.
5.  **Prefer offline, deterministic execution.** A local language model
    is optional and must never be required for core commands.
6.  **No cloud dependency for core functions.** Do not send audio,
    transcripts, contacts, location, or commands to remote services. No
    analytics or telemetry by default.
7.  **Keep user control and safety central.** No arbitrary shell
    execution, arbitrary intents from model output, hidden recording, or
    silent permission escalation.
8.  **Do not invent APIs.** Use documented Android APIs, verified
    third-party library APIs, or a vendor integration discovered from
    actual device evidence. Do not guess proprietary radio broadcasts or
    service names.
9.  **Keep configuration editable.** Settings should be persisted and
    applied at runtime where possible. Clearly indicate when a setting
    requires restart or is unavailable on the device.
10. **Update docs continuously.** Maintain `README.md`, setup
    instructions, known limitations, test results, and this file if
    implementation decisions materially change.

If a requirement conflicts with the device's normal-app restrictions,
implement the best supported alternative, expose the limitation clearly,
and record it in `docs/DEVICE_COMPATIBILITY.md`. Do not bypass Android
security restrictions.

------------------------------------------------------------------------

## 1. Product requirements

### 1.1 Core user experience

NexaAI should allow a driver or passenger to: - Start listening with a
visible microphone button. - Optionally activate listening with a
configurable wake phrase, if the chosen detector can support it on the
device. - Speak a command in a supported language. - See a transcript
and the interpreted action when useful. - Have the app validate and
execute supported actions. - Hear a short spoken confirmation or
error. - Configure the assistant without editing source code. - Use core
features without internet access after required assets have been
installed.

### 1.2 Required configurable options

Build a Settings screen with persisted settings grouped as follows.

**General** - Assistant enabled/disabled. - Assistant display name
(default `NexaAI`). - Preferred language / speech-recognition model. -
Offline-only mode (default enabled; core code must remain offline
regardless). - Verbosity: silent, short confirmations, or detailed
confirmations. - Haptic feedback toggle, if device supports it. - Debug
logging toggle; default off. - Clear local logs and reset settings, with
confirmation.

**Listening** - Listening mode: push-to-talk, tap overlay to listen, or
wake word (only when implemented and available). - Wake-word enabled
toggle. - Wake phrase text, default `Hey Nexa` (display that changing
the phrase alone does not train a detector). - Wake-word sensitivity /
threshold only if the selected engine exposes a real supported
setting. - Listening timeout and end-of-utterance timeout. - Maximum
command duration. - Language and speech model selection from installed
models. - Microphone test and recognition test. - Start/stop listening
controls and visible listening state. - Explicit indication when
microphone permission is missing or the selected engine/model is
unavailable.

**Commands** - List of enabled/disabled commands. - Editable
aliases/phrases per command. - Add/remove aliases. - Parameters and
validation constraints shown for each command. - Per-command
confirmation setting where appropriate. - Import/export command
configuration as validated JSON. - Reset commands to defaults. - Prevent
users from registering arbitrary code, shell commands, or unrestricted
intents. - Alias collisions must be detected and surfaced before saving.

**Startup and service** - Start assistant service automatically where
Android/OEM behavior permits. - Start after boot toggle; explain that
boot reception and background microphone use may be restricted. - Resume
previous listening mode toggle, default off for wake-word mode until
explicitly enabled. - Notification behavior and foreground-service
state. - Battery optimization guidance screen; never misrepresent
exemption as guaranteed. - Clear status when the OS or firmware prevents
automatic startup.

**Overlay** - Floating overlay icon enabled/disabled. - Icon size,
opacity, edge position, and drag-to-position where feasible. - Tap
overlay to start/stop listening. - Long press to open NexaAI. - Optional
show-on-top-of-other-apps permission status and shortcut to system
settings. - Overlay status must be disabled gracefully when permission
is denied. - Overlay must not block critical controls or obscure
navigation/radio UI unnecessarily.

**Voice response** - TTS enabled/disabled. - Speech rate and pitch if
supported. - Confirmation sounds or speech toggle. - Voice/language
selection from installed offline voices. - Test voice button. - Detect
and explain if the selected TTS voice requires downloading or internet.

**AI model** - Optional local LLM enabled/disabled; default off on the
target device. - Model inventory, file size, download/import status,
checksum where available, and deletion. - Context/output limits only if
supported by runtime. - Model must be loaded on demand and unloaded
after inactivity if runtime supports it. - Display estimated storage and
measured memory/performance notes. - Never download a large model
automatically over mobile data. Prefer manual import or explicit user
consent. - Model files must be licensed for the intended distribution;
document license and source.

**Device integration** - Volume controller status. - Media control
status. - App launch status. - Radio adapter status: supported,
unsupported, or unverified. - Diagnostic screen with Android version,
device manufacturer/model, available permissions, and integration test
results. - No root/system access assumption.

### 1.3 Initial supported command catalogue

Seed the app with editable aliases for these commands. The exact alias
list must live in configuration/data, not be hard-coded across UI and
controllers.

-   Set volume to N percent.
-   Increase/decrease volume.
-   Mute/unmute, only if supported by the device.
-   Launch an allowlisted installed app by friendly name.
-   Go home, if allowed and supported.
-   Play/pause, next track, previous track using supported media-session
    mechanisms.
-   Tune FM to a valid frequency, only when the radio adapter is
    verified.
-   Seek next/previous station, only when supported.
-   Start/stop listening.
-   Help / list supported commands.
-   Repeat last response.
-   Cancel current interpretation before execution, where practical.

Commands such as "close app" must be handled honestly: a normal APK
generally cannot force-stop other apps. Offer supported alternatives
such as pause media, navigate home, or use an OEM-supported interface.
Never claim an app was force-stopped when it was not.

------------------------------------------------------------------------

## 2. Target constraints and engineering decisions

### 2.1 Android compatibility

-   Minimum supported Android version: choose and document a minimum
    compatible with selected dependencies and the actual device. Prefer
    Android 9/API 28 compatibility unless a dependency requires higher.
-   Target/compile SDK: use a currently supported SDK available in the
    build environment, while preserving runtime compatibility with
    Android 9--11. Document foreground-service and microphone permission
    differences across versions.
-   Use Kotlin and native Android APIs.
-   Use Jetpack Compose for UI unless an existing repository uses
    another consistent UI framework.
-   Use Gradle Kotlin DSL if creating a new project.
-   Use AndroidX and stable, compatible dependency versions. Do not
    blindly upgrade all dependencies.
-   Use coroutines for asynchronous work and structured concurrency.
-   Use DataStore Preferences or Proto DataStore for settings; use Room
    only for structured command/model metadata if useful.
-   Do not introduce Hilt or another large framework unless project
    complexity justifies it.
-   Keep package name configurable at project initialization; default
    suggestion: `com.nexaai.assistant`.

### 2.2 Resource budget

The device has 2 GB RAM or less. The OS, launcher, radio app, and other
services already consume memory.

-   First implementation must work with **no LLM**.
-   Start with a constrained offline speech engine such as Vosk small,
    after verifying Android packaging, ABI, language support, and memory
    on the actual device.
-   Keep Whisper.cpp tiny as an optional alternative only after
    measuring CPU latency and peak memory.
-   Do not run a speech recognizer and LLM concurrently unless
    measurements prove it is safe.
-   Optional local LLM candidate: a quantized sub-1B instruct model
    through llama.cpp or a compatible runtime, but only as a
    benchmarked, optional fallback. It is not a release requirement.
-   Never claim a model is compatible solely because its parameter count
    is small.
-   Measure cold-start latency, warm latency, peak memory, CPU load,
    battery impact, and interference with radio/navigation apps.
-   Model download size, RAM use, and runtime memory are different
    quantities. Document all three when known.
-   Provide a no-LLM, no-network fallback for all core commands.

### 2.3 Offline guarantees

-   All core command execution must work in airplane mode after app
    installation and model setup.
-   Do not use Android's default speech recognition as the sole engine
    unless offline recognition is explicitly verified on-device.
-   Do not assume Android TextToSpeech voices are offline. Inspect
    installed voices and tell the user if a voice needs downloading.
-   Do not include remote APIs, cloud LLM endpoints, analytics, or
    remote logging in the default implementation.
-   Test with Wi-Fi and mobile data disabled.
-   Document any one-time model download/import and its size.

------------------------------------------------------------------------

## 3. Architecture and module boundaries

Use a layered architecture. UI must not directly call Android system
APIs for device actions.

Suggested package layout:

``` text
app/src/main/java/com/nexaai/assistant/
  NexaAiApplication.kt
  MainActivity.kt
  navigation/
  ui/
    home/
    commands/
    settings/
    models/
    diagnostics/
    components/
    theme/
  domain/
    model/
      AssistantCommand.kt
      CommandDefinition.kt
      CommandResult.kt
      DeviceCapabilities.kt
    parser/
      CommandParser.kt
      RuleBasedCommandParser.kt
      OptionalLlmCommandParser.kt
    validation/
      CommandValidator.kt
  data/
    settings/
    commands/
    models/
  voice/
    AudioInputController.kt
    SpeechEngine.kt
    VoskSpeechEngine.kt
    OptionalWhisperEngine.kt
    WakeWordEngine.kt
    TextToSpeechController.kt
  execution/
    CommandDispatcher.kt
    CommandExecutor.kt
    VolumeController.kt
    AppLauncher.kt
    MediaController.kt
    RadioController.kt
    UnsupportedRadioController.kt
  service/
    AssistantForegroundService.kt
    BootReceiver.kt
  overlay/
    OverlayController.kt
  diagnostics/
    DeviceDiagnostics.kt
  security/
    SafeIntentPolicy.kt
    ModelFileVerifier.kt
```

Adapt this to the existing repository rather than duplicating equivalent
layers.

### 3.1 Domain model

Use a closed command model, for example a sealed interface: -
`SetVolume(percent)` - `VolumeUp` - `VolumeDown` - `Mute` - `Unmute` -
`LaunchApp(appId)` - `GoHome` - `Play` - `Pause` - `NextTrack` -
`PreviousTrack` - `TuneFm(frequencyMhz)` - `SeekNextStation` -
`SeekPreviousStation` - `StartListening` - `StopListening` -
`ShowHelp` - `Cancel`

Every execution returns a typed result: - `Success(message, data?)` -
`Unsupported(reason)` - `PermissionRequired(permission, explanation)` -
`InvalidParameters(reason)` - `Failed(userMessage, diagnosticCode?)` -
`Cancelled`

Do not represent unsupported functionality as success.

### 3.2 Processing pipeline

1.  Capture audio only when the configured listening mode is active.
2.  Run offline speech recognition.
3.  Normalize transcript safely.
4.  Match enabled command aliases using deterministic rules first.
5.  Resolve ambiguity; ask for clarification rather than guessing.
6.  If enabled, use a local LLM only for unmatched or ambiguous
    utterances.
7.  Parse model output as strict JSON matching a schema.
8.  Validate command type, parameters, allowlists, and device
    capability.
9.  Apply confirmation policy when needed.
10. Execute through a controller.
11. Verify state/result where possible.
12. Speak/show a truthful result.
13. Release temporary resources and return to idle state.

### 3.3 LLM output safety

-   The LLM is a parser, not an authority.
-   Accept only a known JSON schema and enum of command types.
-   Reject extra fields, unknown commands, malformed JSON, out-of-range
    values, unknown app IDs, and unsupported radio actions.
-   Never execute code, shell commands, arbitrary URLs, arbitrary
    intents, or arbitrary component names from model output.
-   Do not allow model-generated command definitions to change
    permissions or security policy.
-   Limit input/output tokens and timeouts.
-   If parsing fails or inference is too slow, fall back to a clear "I
    didn't understand" response.
-   Treat all recognized speech as untrusted user input.

------------------------------------------------------------------------

## 4. Device control requirements

### 4.1 Volume

Implement `VolumeController` using `AudioManager` where supported.

-   Check `AudioManager.isVolumeFixed` before promising volume changes.
-   Determine which audio stream affects the head unit's audible output.
-   Clamp percentage to 0--100 and map to the supported stream range.
-   Handle security exceptions and OEM-specific no-op behavior.
-   Provide a diagnostics action to test and report actual volume
    changes.
-   Do not modify unrelated audio streams without an explicit design and
    explanation.

### 4.2 App launching

-   Use a fixed registry of friendly app names and package names.
-   Resolve installed apps using Android package visibility rules for
    the supported SDK.
-   Prefer narrow `<queries>` declarations for known packages; avoid
    broad package visibility unless genuinely needed.
-   Launch only an installed, allowlisted app with a valid launch
    intent.
-   Do not assume example packages such as Maps or YouTube Music are
    installed.
-   Expose a UI for selecting installed apps and assigning aliases.
-   Do not infer package names from LLM output.
-   "Close app" must not promise force-stop. Offer `Pause`, `GoHome`, or
    an OEM-supported option and explain its behavior.

### 4.3 Media controls

-   Prefer documented media-session/media-controller APIs and supported
    media actions.
-   Check Android version/API availability and app behavior.
-   Report unsupported when no compatible session/action is available.
-   Do not simulate taps on screen coordinates as the primary
    implementation.
-   Do not assume every music app supports next/previous/play/pause.
-   Keep audio focus and ducking behavior conservative; do not
    unexpectedly stop navigation guidance.

### 4.4 FM radio adapter

A built-in tuner is not automatically accessible to third-party apps.

-   Create a `RadioController` interface and a default
    `UnsupportedRadioController`.
-   Only implement a real radio adapter after inspecting the actual
    K2501 firmware and identifying a documented or verified vendor
    interface.
-   Do not invent broadcast action strings, package names, service
    names, hidden APIs, or intent extras.
-   Record evidence and steps to reproduce each radio integration test.
-   If radio control requires privileged permissions, root, a system
    app, or firmware modification, do not pretend a normal APK can do
    it. Mark it unsupported and explain the options.
-   Validate FM frequency against a configurable regional band/step only
    after the actual tuner specification is known. Do not hard-code an
    incorrect band plan.
-   Radio adapter must report whether a command was accepted and, if
    possible, verify current frequency.
-   If it cannot verify the result, report "request sent; result could
    not be verified," not "station tuned successfully."

### 4.5 Wake word

Changing a text field does **not** teach a generic detector a new wake
word.

-   Default listening mode must be push-to-talk.
-   Implement a wake-word interface that can be backed by a real
    keyword-spotting engine.
-   The configured phrase should be editable only if the selected engine
    supports custom phrases, training, or model generation.
-   If custom phrase training is not supported, label the field as an
    alias/desired phrase and do not imply that it changes the acoustic
    detector.
-   Show engine status, sensitivity only when supported, false-trigger
    testing, and microphone state.
-   Wake-word detection must be local and must not upload audio.
-   Avoid running a full speech-to-text model continuously just to
    detect the wake word.
-   Stop listening immediately when the user disables it or revokes
    permission.
-   Explain microphone indicator/foreground notification behavior.

### 4.6 Auto-start and background service

-   Implement boot auto-start as best-effort, disabled by default.
-   Use `RECEIVE_BOOT_COMPLETED` only if required and explain its
    purpose.
-   Do not assume the OEM firmware allows a third-party app to start
    after boot or remain alive.
-   Comply with Android version-specific foreground-service and
    microphone restrictions.
-   Never silently start continuous recording after boot without a
    clearly enabled setting and visible status.
-   Provide "Open battery settings" guidance if the device kills the
    service.
-   If auto-start fails, do not loop-restart aggressively.
-   Test cold boot, warm reboot, ignition off/on, sleep/wake, screen
    off, and battery optimization on the actual head unit.

### 4.7 Floating overlay

-   Use `SYSTEM_ALERT_WINDOW` only for the optional floating overlay.
-   Ask the user to grant overlay permission through Android's settings
    UI; do not try to bypass it.
-   Overlay disabled by default.
-   Use a small movable icon with configurable size, opacity, and
    position where practical.
-   Provide tap-to-listen and long-press-to-open settings.
-   Ensure the overlay can be dismissed/disabled from the app.
-   Do not create an invisible overlay or cover system controls.
-   Handle permission denial gracefully and keep the in-app microphone
    button available.
-   Do not assume overlay permission grants microphone/background
    privileges.

------------------------------------------------------------------------

## 5. Configuration and persistence

All user-editable options must be stored persistently. Prefer DataStore
for app preferences and a repository abstraction for command
definitions.

Create versioned configuration models with safe defaults. Example
conceptual settings:

``` json
{
  "schemaVersion": 1,
  "assistantEnabled": true,
  "displayName": "NexaAI",
  "offlineOnly": true,
  "listeningMode": "PUSH_TO_TALK",
  "wakeWordEnabled": false,
  "wakePhrase": "Hey Nexa",
  "autoStartAfterBoot": false,
  "overlayEnabled": false,
  "ttsEnabled": true,
  "confirmationMode": "SHORT",
  "llmEnabled": false,
  "debugLoggingEnabled": false
}
```

This JSON is an example schema, not a substitute for Kotlin data classes
and validation.

Requirements: - Use migrations when configuration schema changes. -
Handle missing/corrupt preferences by restoring safe defaults without
crashing. - Validate settings before saving. - Reflect runtime
capability limitations in the UI. - Export configuration without private
paths, tokens, audio, or personal data. - Import configuration with
schema version checks, validation, and a preview before applying. - Keep
secrets out of configuration; preferably the app should have no
secrets. - Ensure alias edits take effect without requiring app restart
when safe. - For settings that require service restart, explain and
provide a restart action.

------------------------------------------------------------------------

## 6. UI and UX requirements

Create a clean, high-contrast, automotive-friendly UI designed for a
head-unit screen. Large touch targets, low visual clutter, and readable
text are more important than decorative animations.

### Screens

1.  **Home**
    -   NexaAI branding.
    -   Large push-to-talk/listening control.
    -   Current state: Idle, Listening, Recognizing, Processing,
        Executing, Speaking, Error.
    -   Transcript and interpreted command.
    -   Last command result.
    -   Clear microphone and offline indicators.
    -   Shortcuts to commands, settings, and diagnostics.
2.  **Commands**
    -   Searchable command list.
    -   Enable/disable each command.
    -   Edit aliases.
    -   Edit supported parameters.
    -   Test command without executing dangerous/unsupported actions.
    -   Show capability/permission status.
    -   Import/export and reset defaults.
3.  **Settings**
    -   Group settings as described in Section 1.2.
    -   Show permission rationale and open system settings where
        appropriate.
    -   Indicate settings that require a restart.
    -   No dead controls or cosmetic toggles that do not change
        behavior.
4.  **Models**
    -   List installed speech, wake-word, and optional LLM models.
    -   Show language, local file size, source/license, checksum if
        available, and status.
    -   Import model files through Android's Storage Access Framework.
    -   Validate files before loading.
    -   Allow deleting models only after confirmation and when not in
        use.
    -   Do not bundle large models into the APK by default.
5.  **Diagnostics**
    -   Device/Android details.
    -   Permission state.
    -   Microphone test.
    -   Speech-engine test.
    -   TTS offline availability.
    -   Volume capability test.
    -   Media-control capability.
    -   Radio adapter status and test action only if safely available.
    -   Overlay/service/boot status.
    -   Recent redacted error codes.
    -   Export a diagnostic report with user confirmation; no audio
        recordings by default.
6.  **Help and privacy**
    -   Explain offline behavior and any one-time model setup.
    -   Explain limitations of normal APK permissions.
    -   Show current listening mode and microphone status.
    -   Explain how to stop listening and disable auto-start/overlay.

### UI implementation quality

-   Use Material 3 or existing design system consistently.
-   Use accessible labels, large touch targets, clear
    focus/pressed/disabled states.
-   Avoid tiny controls and dense forms.
-   Support landscape orientation as the primary layout.
-   Avoid forcing portrait orientation.
-   Do not rely on color alone for status.
-   Provide empty, loading, permission-denied, unsupported, and error
    states.
-   No buttons that look functional but do nothing.
-   Use Compose previews where practical and UI tests for key
    settings/flows.

------------------------------------------------------------------------

## 7. Speech recognition and audio lifecycle

Create a `SpeechEngine` interface with lifecycle methods such as: -
`initialize(modelConfig)` - `startListening()` - `stopListening()` -
`release()` - State/results/errors exposed through Flow or callbacks.

Implementation rules: - Initialize on a background dispatcher. - Never
block the main thread while loading models or processing audio. - Reuse
loaded models where safe; release native resources on shutdown. - Ensure
only one component owns microphone capture at a time. - Handle
microphone permission denial, audio device changes, recognizer errors,
silence, partial results, and cancellation. - Do not persist raw audio
by default. - If audio diagnostics are ever added, require explicit
opt-in, short retention, and a clear delete action. - Stop or pause
recognition before TTS if self-triggering is possible. - Prevent
NexaAI's spoken confirmation from being interpreted as a new user
command. - Debounce duplicate final transcripts. - Avoid executing a
command from partial recognition unless the action is explicitly safe
and the design calls for it. - Add timeouts so the state machine cannot
remain stuck in Listening/Processing.

------------------------------------------------------------------------

## 8. Security, privacy, and safety

-   Request only necessary permissions at the moment they are needed.
-   Explain microphone, overlay, and boot behavior in plain language.
-   Never hide active microphone/listening state.
-   No network calls for audio or command processing in offline mode.
-   No arbitrary command execution, `Runtime.exec`, shell use, or
    model-generated code execution.
-   No dynamic broadcast of guessed proprietary intents.
-   Use app-private storage for settings and model metadata; use SAF for
    model imports.
-   Validate model file size/type and avoid path traversal when handling
    imported files.
-   Avoid logging full transcripts by default. Redact sensitive
    information.
-   Do not use `QUERY_ALL_PACKAGES` unless a documented, unavoidable
    requirement exists and distribution policy permits it.
-   Avoid accessibility service usage as a shortcut for unsupported
    app/radio controls.
-   Use explicit user confirmation for configuration imports and
    destructive operations.
-   Keep driving interactions brief; do not require visual confirmation
    for ordinary safe commands when the action can be reliably verified.
-   For ambiguous, unsupported, or potentially disruptive commands, ask
    for clarification or refuse execution safely.

------------------------------------------------------------------------

## 9. Testing requirements

Create unit tests for parsing, aliases, validation, configuration
migration, dispatch, and capability handling.

### Minimum unit-test scenarios

-   "volume up", "increase volume", "louder" resolve to the configured
    volume-up command.
-   "set volume to 30 percent" resolves to 30.
-   Volume values below 0 or above 100 are rejected.
-   Invalid FM frequencies are rejected according to the configured
    region/band.
-   An FM command is unsupported if the radio adapter is unsupported.
-   An unknown app alias cannot launch an arbitrary package.
-   Disabled commands do not execute.
-   Alias collisions are rejected or require explicit disambiguation.
-   Malformed or extra-field LLM JSON is rejected.
-   Unknown model-proposed actions are rejected.
-   Permission-denied results are surfaced accurately.
-   Failed controller calls never return success.
-   Configuration with an unknown schema version is handled safely.
-   Model load failure falls back to rules without crashing.
-   Cancellation prevents a pending command from executing where
    possible.

### Device acceptance test matrix

Test on the actual K2501 device: - Offline operation with Wi-Fi/mobile
data disabled. - Voice recognition with radio/music playing. -
Recognition while the vehicle is stationary and in representative cabin
noise. - Cold start and warm start. - Screen on/off and sleep/wake. -
Reboot and ignition-cycle behavior. - Radio station tune and seek, only
if adapter exists. - Volume behavior on all relevant audio sources. -
Launching actual installed apps. - Media actions against each intended
media app. - Overlay permission granted/denied. - Microphone permission
granted/denied/revoked. - Low-storage and model-corruption behavior. -
Long-duration memory/CPU stability. - Battery impact for push-to-talk
versus wake word. - No repeated or unintended command execution.

### Release acceptance gates

Do not mark a milestone complete unless: - The project builds. - Unit
tests pass. - No known crash is introduced. - UI states match real
capability/permission results. - Documentation is updated. -
Device-dependent limitations are clearly listed. - The feature is tested
on-device or explicitly marked "not yet verified on target hardware."

------------------------------------------------------------------------

## 10. Development milestones

### Milestone 0 --- Repository and device baseline

-   Inspect repository and build setup.
-   Record package name, compile/min SDK, dependency versions, device
    details, CPU ABI, free storage, and actual RAM if available.
-   Create `docs/DEVICE_COMPATIBILITY.md`.
-   Create a baseline build and run existing tests.
-   Do not begin proprietary radio integration until evidence is
    collected.

**Deliverables:** baseline build, architecture notes, compatibility
checklist.

### Milestone 1 --- App shell and configuration

**Implementation baseline (2026-10-23):** The initialized project uses package
`com.nexaai.assistant`, min API 28, compile/target API 35, JDK 17, AGP 8.9.2,
Gradle 8.11.1, Kotlin 2.1.20 and Compose BOM 2025.04.01. Milestone 1 implements
the six-screen shell and versioned DataStore configuration. Hardware-dependent
settings remain read-only/unavailable until their milestone is implemented;
they must never become cosmetic toggles. TTS defaults **off**, unlike the
conceptual schema example, until an actual offline voice is verified. Name,
assistant preference, command enablement and aliases are editable. No command
execution or recording is implied by catalogue enablement. Host build/test
evidence and outstanding target checks are maintained in `docs/TEST_PLAN.md`
and `docs/DEVICE_COMPATIBILITY.md`; device checks were skipped by user request.

-   Build Compose navigation and the Home, Commands, Settings, Models,
    Diagnostics, and Help screens.
-   Implement DataStore-backed settings and migrations.
-   Seed command definitions and aliases.
-   Ensure every visible control works or is clearly disabled with a
    reason.

**Deliverables:** functioning app shell, persisted settings, UI tests.

### Milestone 2 --- Command engine and safe controls

**Implementation decisions (2026-10-23):** The engine reads the existing schema-1
catalogue at preparation and execution. Home offers development input with
single-use interpretation, execute, and cancel controls, not speech capture.
Volume uses only `STREAM_MUSIC` with Android-state verification, not proof of
OEM audible output. Installed launcher apps are selected into a private versioned
DataStore allowlist, excluded from portable imports/exports. Media uses session
transport controls after optional notification-listener access, refuses multiple
sessions, and reports requests rather than unverified playback changes. Radio
and listening remain unsupported. No tuner band is assumed. Host verification
and outstanding target checks are maintained in the test/device reports.

-   Implement domain command types, parser, validator, dispatcher, and
    typed results.
-   Implement volume controller with capability checks.
-   Implement allowlisted app launcher.
-   Add supported media controls.
-   Add unit tests before connecting speech recognition.

**Deliverables:** testable controls and deterministic command execution.

### Milestone 3 --- Offline speech and TTS

Vosk command capture supports optional runtime grammar mode, off by default, and
up to 100 custom English vocabulary entries in Settings. The per-session grammar
uses enabled command aliases, allowlisted names and bounded spoken volume/FM
expansions plus `[unk]`. Final numeric phrases map back to existing parser inputs;
partials never dispatch. Existing schema-1 files default missing grammar settings
to off/empty. Custom vocabulary does not train new words or authorize commands.
Acoustic accuracy, false-command rates and native grammar resource use remain
device-verification gates; the wake detector and radio mappings are unchanged.

-   Integrate Vosk or a selected offline recognizer.
-   Add model import/setup and model status UI.
-   Implement audio lifecycle and state machine.
-   Integrate Android TTS and detect offline voice availability.
-   Test core commands with networking disabled.

**Deliverables:** push-to-talk voice assistant that works offline.

### Milestone 4 --- K2501 radio investigation

**Requested hands-free extension (2026-10-26):** The user explicitly expands
Milestone 4 to include offline wake word and its necessary foreground service,
bringing forward those parts of Milestones 5/6 without implementing overlay or
boot startup. Preserve the radio adapter and frozen keys 5/6/46/61; 26/27 are
frequency steps, never station search. sherpa-onnx Android `1.12.14` (Apache-2.0)
implements real keyword spotting through `WakeWordDetector`, using the English
GigaSpeech 3.3M 2024-01-01 model and a user-imported single English BPE keyword.
The wake phrase is configurable in Settings (default Hey Nexa), persisted in the
existing schema and checked against the imported tokens when hands-free starts.
Changing text does not regenerate tokens; the ZIP must match the selected phrase.
The runtime and model sources/licenses/setup are in `docs/MODELS.md`.
One application-owned `SpeechSessionCoordinator` serves push-to-talk and a
nonexported, explicit-start microphone foreground service. Only one capture runs;
wake capture closes before Vosk starts, final transcripts use the existing
dispatcher, and TTS completion plus a short cooldown precede rearming. Errors
stop continuous listening rather than restarting aggressively. Service destruction
releases models off main. Persisted settings never authorize boot/process restart
or configuration import to start recording. Target accuracy, native model loading,
RAM/CPU, power, audio interruptions and screen-off/ignition behavior remain
unverified. Use host tests and the separate stationary device checklist before
claiming hardware completion. START/STOP_LISTENING voice aliases remain unsupported;
the explicit UI/notification Stop controls are authoritative.

-   Collect device properties and package/activity details.
-   Identify radio app and any documented vendor SDK or exported
    integration.
-   Implement only a verified radio adapter.
-   If no public interface is available, document the limitation and
    keep the unsupported adapter.

**Deliverables:** verified radio support or a clear, evidence-backed
limitation report.

### Milestone 5 --- Overlay and startup

**Implementation decisions (2026-10-29):** Existing overlay/auto-start flags now
work through the same validated schema-1 DataStore; new optional size/opacity/
normalized-position fields default safely for older files. All three of overlay,
auto-start and hands-free remain off on fresh installs. A nonexported, non-sticky
overlay foreground service owns one `TYPE_APPLICATION_OVERLAY` window, checks
`Settings.canDrawOverlays`, watches grant changes, clamps/persists dragging, and
removes the window on disable/destruction. Supplied `images/` artwork provides
launcher/offline/wake/pressed states. Wake-active means the existing coordinator
is actually waiting; no model/algorithm/radio mapping is changed. Only a transparent,
draggable robot icon is shown, without a panel/status strip. Tap when idle requests
one command through the existing microphone foreground service, without a wake word
or activity launch. Tap while waiting for wake manually triggers a serialized
wake-to-command handoff and rearms afterward; tap during command processing stops.
Hold opens NexaAI; dragging only moves the icon. The body light is recolored light
green only for reported wake/command listening, leaving other pixels unchanged.
Stop uses `HandsFreeControl.disable` to cancel the shared
session, including push-to-talk. No overlay callback dispatches commands.

`BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` are bounded, after-unlock, best-effort
overlay-only requests gated by valid settings, assistant/overlay/auto-start and
overlay permission. No direct boot, activity/radio launch, microphone start or
automatic wake-word resume is implemented. API 28-33 uses an ordinary foreground
notification; API 34+ declares non-recording `specialUse` with its normal FGS
permission/subtype. The existing Milestone 4 microphone service also handles explicit
tap-to-command requests, without changing wake preferences or detector algorithms.
One-shot requests are deduplicated and have a startup watchdog; service-owned
capture survives activity lifecycle loss, still uses the coordinator's single
session gate, and awaits optional TTS before teardown. Explicitly enabled hands-free
already provides the continuous wake/command loop over other apps. Missing permission
or blocked background capture is reported without opening MainActivity; normal APK
restrictions, especially API 30+ background microphone checks, are not bypassed.
Both services are non-sticky and do
not schedule retries. Firmware may still suppress delivery or kill normal APKs;
configured, permission, attached-window and runtime status remain distinct.
89 host tests, debug build and lint pass after the tap-to-command refinement; see the
test plan for exact evidence.
No physical K2501, boot, sleep/ignition, real overlay grant or audio test was run.
These are outstanding acceptance gates, not verified firmware support.

-   Implement optional overlay with permission flow.
-   Implement best-effort boot startup and foreground-service behavior
    compatible with Android 9--11.
-   Keep wake-word mode disabled by default.
-   Test firmware power management and screen/ignition lifecycle.

**Deliverables:** configurable overlay and startup behavior with honest
capability status.

### Milestone 6 --- Wake word

-   Select and integrate a real local keyword-spotting engine compatible
    with the device.
-   Confirm whether custom wake phrases are supported. If not, clearly
    restrict the configurable phrase feature.
-   Measure false accepts, false rejects, latency, RAM, CPU, and
    battery.
-   Provide a reliable manual stop mechanism.

**Deliverables:** optional wake-word mode only if it meets device
acceptance gates.

### Milestone 7 --- Optional local LLM

-   Add behind a feature flag, off by default.
-   Benchmark a quantized sub-1B model and runtime on target hardware.
-   Use constrained JSON output and strict validation.
-   Load on demand and unload after inactivity where supported.
-   Ensure rules still work if the model is missing, slow, or out of
    memory.

**Deliverables:** optional local intent interpretation, never a core
dependency.

### Milestone 8 --- Hardening and release

-   Run unit, UI, and device tests.
-   Verify offline behavior and data handling.
-   Review permissions, dependency licenses, model licenses, and APK
    size.
-   Add README setup instructions, known limitations, troubleshooting,
    and build steps.
-   Produce a debug APK for device testing; only produce a release APK
    when signing configuration is provided securely.
-   Do not commit signing keys, passwords, or private credentials.

**Deliverables:** documented, reproducible build and acceptance report.

------------------------------------------------------------------------

## 11. Build and repository conventions

-   Use meaningful names and small classes with one responsibility.
-   Keep UI state in ViewModels or a clearly defined state holder.
-   Use immutable UI state and Kotlin Flow/StateFlow where appropriate.
-   Use dependency injection through constructors; avoid global mutable
    singletons.
-   Catch expected exceptions at integration boundaries, not
    indiscriminately everywhere.
-   Never swallow errors silently. Convert them to typed results and
    diagnostic codes.
-   Avoid `GlobalScope`; use lifecycle/service-owned coroutine scopes.
-   Do not block the main thread.
-   Add comments for Android/OEM compatibility workarounds and cite
    official/vendor documentation in code comments or docs.
-   Avoid adding dependencies without a reason and license review.
-   Keep native model libraries ABI-compatible with the device. Inspect
    `ro.product.cpu.abi` and available ABIs before packaging native
    engines.
-   Provide Gradle commands in README, including debug build and tests.
-   If build tools or hardware are unavailable in the environment, state
    what was not verified; do not claim tests passed.

------------------------------------------------------------------------

## 12. Required documentation

Maintain these files: - `README.md` --- overview, requirements, setup,
build, installation, offline setup, troubleshooting. -
`docs/ARCHITECTURE.md` --- modules, data flow, state machine, decision
records. - `docs/DEVICE_COMPATIBILITY.md` --- K2501 hardware/firmware
evidence, permissions, verified/unverified controls. -
`docs/COMMANDS.md` --- command catalogue, aliases, parameter validation,
expected outcomes. - `docs/PRIVACY_AND_PERMISSIONS.md` --- microphone,
overlay, boot, data storage, offline guarantees. - `docs/MODELS.md` ---
speech/wake/LLM models, source, license, file size, language, setup,
measured performance. - `docs/TEST_PLAN.md` --- automated and manual
device test matrix. - `CHANGELOG.md` --- meaningful milestone-level
changes.

Do not write "supports FM radio," "supports custom wake word," "starts
automatically," or "works completely offline" without specifying exactly
what was tested and under what conditions.

------------------------------------------------------------------------

## 13. Definition of done

NexaAI's initial release is done when:

1.  The Android project builds reproducibly.
2.  The app has a usable automotive-friendly UI and all supported
    settings persist.
3.  Push-to-talk offline speech recognition works on the target head
    unit.
4.  Volume and allowlisted app launching work, or are accurately marked
    unsupported with a reason.
5.  Supported media actions work with documented limitations.
6.  Radio integration is verified on the actual device or explicitly
    documented as unavailable to a normal APK.
7.  No LLM is required for core commands.
8.  Unknown, malformed, ambiguous, or unsupported commands never trigger
    arbitrary actions.
9.  Offline TTS is used only when an offline voice is available;
    otherwise the UI explains the limitation.
10. Overlay, auto-start, and wake-word options reflect actual permission
    and firmware behavior.
11. Unit tests and applicable UI tests pass.
12. Privacy, model licenses, setup, device compatibility, and
    troubleshooting documentation are complete.
13. No fake controls, fake success messages, or unverified hardware
    claims remain.

------------------------------------------------------------------------

## 14. First task for Copilot

Begin by inspecting the current repository and reporting: 1. Existing
project structure and package name. 2. Gradle, Kotlin, compile SDK,
minimum SDK, and dependency versions. 3. Whether the repository is empty
or contains existing code. 4. Any build/test failures found before
changes. 5. The device facts that still need verification, especially
CPU ABI, actual available RAM, radio package/interface, and Android
build/API level.

Then implement **Milestone 0 and Milestone 1** in working increments. Do
not attempt to implement every milestone in one giant change. After
those milestones build successfully, proceed to Milestone 2 and continue
in order, updating this document and the compatibility report as
evidence is collected.

**Non-negotiable product principle:** NexaAI must prefer a small,
reliable, offline command assistant over a large but unstable AI model.
Every action must be supported by a real controller, validated,
observable, and honest about its result.
