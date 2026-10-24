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
Later milestones own speech, models, verified radio, startup and wake word.
An enabled alias is not proof of hardware support. Do not use this
development build as an operational driving assistant.

Implemented controls: assistant preference, display name, searchable command
definitions, command enablement, add/remove/edit aliases, collision validation,
confirmed resets, validated JSON import with preview/confirmation, and export.
Settings that need later integrations are read-only with reasons rather than
cosmetic toggles. TTS is off until a real offline voice can be verified.

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
- FM tune/seek and start/stop listening return explicit unsupported results.
    Help/repeat work as text only. No vendor API or tuner band is invented.

Preferences schema 1 migrates the early `display_name` key when present. Missing
data uses safe defaults; corrupt data shows recovery status; future schema
versions use safe defaults without silently overwriting stored data. Confirmed
reset or validated import can recover the store. Imports/exports are limited to
64 KiB UTF-8; unsupported settings, unknown commands/fields, invalid aliases and
alias collisions are rejected. All 19 catalogue IDs must appear exactly once.

No model is bundled and no dependency was added for Milestone 2. Microphone,
overlay, boot, foreground-service, notification-posting and internet permissions
remain absent. Normal `MODIFY_AUDIO_SETTINGS` is declared. Media uses a
system-protected notification-listener service with optional special access:
the access is broad, but NexaAI does not inspect or store notification contents.
No `QUERY_ALL_PACKAGES`, arbitrary shell, user-supplied intents or inferred package
names are used. Speech, radio, wake word and automatic startup remain unavailable.

## Verification and troubleshooting

Automated results and manual acceptance gates are tracked in
[the test plan](docs/TEST_PLAN.md). Host JVM/Compose tests cannot establish
K2501 hardware compatibility. A debug APK is for stationary device testing.

Host validation: debug build, all 29 unit/configuration/Compose tests and lint
pass (zero errors, 10 existing dependency/target warnings). UI tests
run with Robolectric API 28, not an attached emulator/head unit. Target hardware
checks remain unperformed. Speech is unimplemented; OEM controls remain
unverified on the target. Lint retains dependency-update/target notices;
the toolchain is intentionally pinned rather than upgraded without testing.

- Missing SDK: set `ANDROID_HOME` or an untracked `local.properties`.
- Dependency resolution failure: check access to Google Maven, Maven Central,
  and the Gradle distribution server; the first build is not offline.
- Unavailable speech: expected until Milestone 3 and explicit offline model setup.
- Radio unavailable: no verified public vendor interface has been collected.
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

`AGENT.md` remains the product source of truth. This project does not claim the
initial-release definition of done is satisfied by an app shell.

Next: Milestone 3, offline speech/model setup, audio lifecycle and offline TTS
verification. These are not included in this command-engine milestone.