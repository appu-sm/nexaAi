# NexaAI

Native Kotlin/Compose Android head-unit assistant, developed in ordered milestones.
The target is an NWD_K2501/T5 with firmware F52L_UPGZ_S212A70. No target
hardware is attached to this build environment. See [device evidence](docs/DEVICE_COMPATIBILITY.md).

## Build and install

Requirements: JDK 17, Android SDK platform 35 and build tools 35.0.0, and
`ANDROID_HOME` pointing to the SDK (or an untracked `local.properties` with
`sdk.dir`). Accept the SDK licenses yourself with `sdkmanager --licenses`.
Dependency downloads require network access on the build host; this does not
add runtime networking to the application.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID and namespace start as `com.nexaai.assistant`. To change
identity, update both in the app Gradle configuration, Kotlin package declarations,
source directories, and test package references before distribution. Minimum
Android is 9/API 28; compile/target API is 35. Landscape is preferred but neither
orientation is forced. No release signing credentials are included.

## Current scope

Milestone 0 establishes a buildable Android baseline. Milestone 1 implements six
screens, persistent versioned configuration and an editable command catalogue.
Milestone 2 adds deterministic command execution and safe Android controls.
Milestone 3 adds offline push-to-talk recognition and optional offline TTS.
Milestone 4 includes the verified radio request adapter and the requested offline
wake-word/hands-free extension. K2501 APK-level receiver access and real wake-word
performance remain device acceptance gates. Overlay and boot startup are deferred.
An enabled alias is not proof of hardware support. Do not use this
development build as an operational driving assistant.

Implemented controls: assistant preference, display name, searchable command
definitions, command enablement, add/remove/edit aliases, collision validation,
confirmed resets, validated JSON import with preview/confirmation, and export.
Settings that need later integrations remain read-only with reasons. The spoken
response toggle is available only when Android reports an installed English offline
voice; it defaults off.

Milestone 2 adds typed commands/results, exact alias parsing, central validation,
single-use dispatch and cancellation. In Home, type `help`, Interpret, then
Execute or Cancel the pending interpretation. Interpretation does not perform
hardware actions. Test actual controls only while stationary.

- Music-stream volume uses AudioManager, detects fixed volume and verifies
    Android-reported level/mute state. OEM audible output is unverified.
- Commands provides an installed-app picker and unique friendly-name aliases.
    The private app allowlist starts empty and is not imported/exported.
- Home navigation is available where resolvable; it does not force-stop apps.
- Media requires optional notification-listener access in Commands/Diagnostics,
    exactly one active session and an advertised action. Results say request sent;
    playback changes are not verified.
- Push-to-talk uses Vosk locally after a user imports the model ZIP; only final
    transcripts reach the existing deterministic command dispatcher. No model is bundled.
- Hands-free uses real sherpa-onnx `1.12.14` keyword spotting, not a continuous
    Vosk transcription loop. Import the English keyword model configured for
    your English wake phrase, save the same phrase in Settings > Listening
    (default **Hey Nexa**), explicitly enable the listening switch, grant microphone access,
    then speak the wake phrase, pause for command capture, and say a configured
    command. See the exact [model preparation steps](docs/MODELS.md#hey-nexa-keyword-model).
    Stop with the switch, Stop icon, or foreground notification action.
- Radio commands launch the explicit `com.nwd.radio` activity or send its
    package-scoped frequency, key and mute broadcasts. Supported actions are tune,
    next/previous search, mute/unmute, AMS and INTRO. Broadcast responses confirm
    dispatch requests only; target APK permissions, receiver delivery and tuner
    effects remain unverified on the K2501.
- START/STOP_LISTENING command aliases remain unsupported; listening is controlled
    by push-to-talk or the hands-free controls. Help/repeat use the existing
    command pipeline and optional offline spoken results.

Preferences schema 1 migrates the early `display_name` key when present. Missing
data uses safe defaults; corrupt data shows recovery status; future schema
versions use safe defaults without silently overwriting stored data. Confirmed
reset or validated import can recover the store. Imports/exports are limited to
64 KiB UTF-8; unsupported settings, unknown commands/fields, invalid aliases and
alias collisions are rejected. All 24 catalogue IDs must appear exactly once.

No speech model is bundled. Vosk Android `0.3.47` is pinned; model setup is manual
and documented in [Models](docs/MODELS.md). Microphone permission is requested only
after tapping the Home mic or enabling hands-free. Hands-free declares microphone
foreground-service permissions and requests notification permission on Android 13+.
Notification denial does not grant hidden recording: Android's foreground-service
task UI still applies, and NexaAI retains an in-app Stop control. Internet, overlay
and boot permissions remain absent. Normal `MODIFY_AUDIO_SETTINGS` is
declared. Media uses a
system-protected notification-listener service with optional special access:
the access is broad, but NexaAI does not inspect or store notification contents.
No `QUERY_ALL_PACKAGES`, arbitrary shell, user-supplied intents or inferred package
names are used. Radio uses one package visibility entry for `com.nwd.radio`, with
no privileged permission. Push-to-talk code is implemented, but offline
recognition and TTS have not been physically verified. Radio dispatch and actual
tuner behavior still require the installed APK and K2501. Hands-free requires both
local models; it fails visibly back to push-to-talk when initialization fails.
There is no automatic listening after reboot or process death, even when the last
saved preference was hands-free. Re-enable it explicitly from the visible app.

## Verification and troubleshooting

Automated results and manual acceptance gates are tracked in
[the test plan](docs/TEST_PLAN.md). Host JVM/Compose tests cannot establish
K2501 hardware compatibility. A debug APK is for stationary device testing.

Host validation covers configuration, Compose, push-to-talk, wake-session ordering,
fake audio, permission/service failures and radio mappings. UI tests use Robolectric
API 28, not an attached emulator/head unit. Both native engines package arm64-v8a,
armeabi-v7a, x86 and x86_64; this does not verify the target ABI. Latest gate results
are in [the test plan](docs/TEST_PLAN.md). Real keyword/model loading, offline voices,
OEM controls, screen-off listening, RAM/CPU and power remain unverified on target.
The toolchain is intentionally pinned rather than upgraded without testing.

- Missing SDK: set `ANDROID_HOME` or an untracked `local.properties`.
- Dependency resolution failure: check access to Google Maven, Maven Central,
    GitHub release assets, and the Gradle distribution server; the first build is not offline.
- Wake-word model error: prepare the five-file ZIP described in Models, including
        the actual Hey Nexa BPE tokens. A renamed file or edited phrase is insufficient.
- Hands-free service/microphone error: stop listening, restore microphone access,
        and retry while NexaAI is visible. If firmware prevents background listening,
        leave hands-free off and use push-to-talk. Do not enable automatic restarts.
- Missing/corrupt speech model: import the official ZIP from the Models screen; no
    automatic download or online fallback is attempted.
- Radio unavailable: confirm `com.nwd.radio` is installed and inspect the returned
    local failure. A successful broadcast dispatch does not confirm receiver
    delivery, tuner completion or changed mute state.
- Volume state differs from audible output: record the active source; only
    `STREAM_MUSIC` is changed, not an invented OEM mixer API.
- Unknown app: select the installed app and save friendly aliases in Commands.
- Media permission missing: review optional listener access in system settings,
    then refresh capabilities. Execution rechecks access after revocation.
- Multiple media sessions: stop other players; NexaAI refuses to guess a target.
- Device not visible: enable USB debugging and authorize this host on the device.
- Never grant root or install a privileged build to satisfy an unsupported toggle.

## Documentation

- [Architecture and decisions](docs/ARCHITECTURE.md)
- [Command catalogue](docs/COMMANDS.md)
- [Privacy and permissions](docs/PRIVACY_AND_PERMISSIONS.md)
- [Model inventory and licensing](docs/MODELS.md)
- [Changelog](CHANGELOG.md)

`AGENT.md` remains the product source of truth. Milestones 3 and 4 code are
implemented, but end-to-end completion still requires the real model, offline
voice and target head unit to be tested.