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
adb install -r app/build/outputs/apk/debug/NexaAI.debug.0.1.0.apk
```

APK naming is `<project-name>.<build-type>.<version>.apk`. The single source of
truth is `settings.gradle.kts`: `rootProject.name`, `appVersionName`,
`appVersionCode` and `apkBuildTypes` (enabled variants, initially debug/release).
The app's Android version metadata uses those same version values. Change them
there before building; the filename always uses the actual variant's build type.
For the current settings, `:app:assembleDebug` produces
`app/build/outputs/apk/debug/NexaAI.debug.0.1.0.apk`, and `:app:assembleRelease`
produces `app/build/outputs/apk/release/NexaAI.release.0.1.0.apk`.
Release signing is not configured: the release filename does not imply a signed,
installable production APK. Debug keeps Android's normal development signing.

The application ID and namespace start as `com.nexaai.assistant`. To change
identity, update both in the app Gradle configuration, Kotlin package declarations,
source directories, and test package references before distribution. Minimum
Android is 9/API 28; compile/target API is 35. Landscape is preferred but neither
orientation is forced. No release signing credentials are included.

## Current scope

### Switchable Offline ASR

Settings > Listening > ASR engine selects **Vosk - Indian English** (default,
`vosk`) or **sherpa-onnx - English** (`sherpa_onnx`). Selection persists in the
existing DataStore configuration. Stop listening before changing it; the next
session releases the old model before loading the new one. There is no silent
fallback. Selected engine, loaded engine, model availability and initialization
status are shown separately. Only the selected ASR model loads, lazily; wake-word
spotting remains a separate unchanged detector.

Existing Vosk files/import path are preserved, including an already imported
`vosk-model-small-en-in-0.4`. Do not replace it for the initial comparison.
The legacy private directory name contains `en-us-0.15`; that name does not
identify the imported model. New sherpa ASR imports use the official int8
`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` candidate, not the KWS model.
Runtime dependencies stay pinned at Vosk 0.3.47 and sherpa-onnx 1.12.14.
See [model setup](docs/MODELS.md#sherpa-onnx-command-asr).

Models > Choose evaluation ZIP runs both engines sequentially on consented
16 kHz mono PCM16 recordings, without opening a microphone or executing commands.
Confirm consent, then Export evaluation for raw transcripts, counts, timing and
supported process snapshots. Follow the [reproduction protocol](docs/ASR_EVALUATION.md)
and [corpus template](docs/asr-corpus.json). Recordings/models are not included.
Native model loading/real offline recognition, accuracy, CPU and peak RAM remain
physical acceptance gates; no superiority claim or default change is justified.

Milestone 0 establishes a buildable Android baseline. Milestone 1 implements six
screens, persistent versioned configuration and an editable command catalogue.
Milestone 2 adds deterministic command execution and safe Android controls.
Milestone 3 adds offline push-to-talk recognition and optional offline TTS.
Milestone 4 includes the verified radio request adapter and the requested offline
wake-word/hands-free extension. K2501 APK-level receiver access and real wake-word
performance remain device acceptance gates. Milestone 5 adds optional floating
controls and best-effort overlay startup after unlock; target behavior is unverified.
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
- Push-to-talk uses the selected local ASR engine after model ZIP import; only final
    transcripts reach the existing deterministic command dispatcher. No model is bundled.
- Settings > Listening offers optional Vosk grammar mode (off by default) and
    saved custom vocabulary, one entry per line. Generated phrases follow enabled
    aliases, allowlisted app names and spoken volume/FM values. Changes apply on
    the next capture; custom words do not create commands or retrain the model.
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
task UI still applies, and NexaAI retains an in-app Stop control. Overlay
permission is never granted automatically. Internet permission remains absent;
optional overlay and boot permissions are declared. Normal `MODIFY_AUDIO_SETTINGS` is
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

## Overlay and startup setup

All three switches start **off**: floating overlay, auto-start, and hands-free.
Existing settings survive upgrades. Overlay and startup never enable wake word.

1. In Settings, enable Floating overlay. Read the draw-over-other-apps rationale
    and tap **Overlay permission** to grant Android special access. Denial is safe;
    no permission screen opens automatically. Returning to NexaAI rechecks access.
2. The supplied robot artwork is used for the launcher and overlay. Drag its icon
    to an unobstructive edge while stationary; position, size (56-96 dp), and
    opacity (50-100%) persist. The robot floats without a permanent panel or status
    strip. A grey speech bubble appears beside it for partial/final transcripts
    (`You:`) and actual command results/errors (`NexaAI:`). It chooses the roomier
    side, wraps/scrolls long output, and disappears after 12 seconds without new text;
    tap its text to dismiss. The icon's saved position and touch area stay unchanged.
    Tap the icon when idle to capture one command directly, without
    opening Home or saying a wake word. The final recognized command uses the
    existing validated command engine and executes once. Tap during command
    capture/processing to stop; hold opens NexaAI. Dragging only moves the icon.
    Grant microphone access once using Home first and import the selected ASR model. Missing
    permission/model or a blocked service produces a visible error, not an automatic
    activity launch. The small body light turns saturated green during reported wake or
    command listening, and returns to blue while idle, loading, processing, speaking
    or unavailable. Full status stays in the app and accessible icon description.
    Simply displaying the overlay never captures audio or creates a second recorder.
3. Optionally enable **Auto-start overlay after boot**. After unlock, boot and
    package-update broadcasts may request an enabled overlay with granted permission.
    Startup does not open an activity, radio, or microphone service. There is no
    automatic wake-word resume, even when its preference was previously enabled.
4. Settings/Diagnostics distinguish configured switches, current permission,
    running service, attached window, and a boot/update observed in this process.
    A request or registered receiver is not proof of boot reliability or uptime.
5. For continuous wake-word/command listening over other apps, explicitly enable
    the existing hands-free switch from NexaAI after installing both models.
    It rearms after each command and optional spoken response. Tapping the icon
    while waiting for the wake phrase skips that wait for one command, then resumes
    the loop. Stop continuous listening with the notification or in-app Stop;
    tapping during command capture also stops the session. Overlay and hands-free
    remain independent opt-ins; no automatic microphone resume occurs after reboot.

Direct overlay capture uses the existing microphone foreground service and its
ongoing Stop notification. Android 11+ background-microphone restrictions, Android
12+ FGS-start restrictions and Android 14+ while-in-use checks may reject it even
with overlay permission. A normal APK cannot bypass these checks. If denied, hold
the icon, open Home and use visible push-to-talk. Target firmware capture and
continuous-listening reliability still require stationary physical testing.

The overlay uses its own non-recording foreground notification with Disable overlay;
hands-free retains its existing microphone notification and Stop action. On API 34+
the overlay service declares the documented `specialUse` type/permission; API 28-33
uses the ordinary foreground-service API. Android 13+ notification access is
requested on opt-in, not at boot; denial does not grant background microphone access.
Overlay permission can be revoked in Android special access; NexaAI removes the
window and leaves push-to-talk available. Disable overlay and hands-free independently.

Startup is best-effort. The app must have been opened at least once; force-stop
blocks broadcasts until opened again. There is no direct-boot/locked-storage support,
sticky restart, wake lock, battery-policy bypass, or microphone boot start. F52L
firmware may suppress boot delivery or kill/suspend normal APK services during
screen-off, sleep or ignition cycles. Open NexaAI manually and use **Refresh / retry
overlay** after failures; restore permission if needed. Battery settings are optional
guidance, not a guarantee. Never change firmware privileges to satisfy this feature.
See the [physical acceptance procedure and actual result record](docs/TEST_PLAN.md#milestone-5-overlay-and-startup).

## Verification and troubleshooting

### Quiet Speech or Missing First Words

Command listening is now shown only after AudioRecord has actually started,
not while grammar/recognizer preparation is still running. Wait for Listening
(green overlay indicator for command capture) before saying the command. In
hands-free mode, pause after the wake phrase until command capture begins; audio
spoken during the wake-to-ASR handoff is not replayed.

For `open clock`, add the installed Clock app in Commands > Allowed apps with
friendly name `clock`. In Settings > Listening, try **Vosk grammar mode** to
constrain recognition to configured commands, including `open clock`. Adding
only `clock` as custom vocabulary does not teach the app an `open` command.
Grammar can also misrecognize unrelated speech as a command; compare false
commands with grammar off/on while stationary.

For low command-microphone level, Settings > Listening > **Command microphone**
offers **Standard microphone** versus the existing **Voice recognition microphone**.
Save, then compare the same phrase/distance with gain at 100% first. If still
quiet, try 150-200% input gain and save. This is bounded digital amplification
(100-300%), not hardware microphone sensitivity or automatic noise cancellation;
it raises noise too and can clip loud speech. Reduce gain if accuracy worsens.
Both options persist and apply to the next command recording, including
push-to-talk/overlay/hands-free ASR; wake-word audio is unchanged. Older settings
retain the original source and 100% gain. No audio is saved. Firmware decides the
physical mic route and source processing: if both sources remain quiet, check
the unit's selected internal/external mic, connector and mounting while parked.
No improved far-field accuracy has yet been measured on the head unit.

Milestone 6 adds opt-in, memory-only voice diagnostics: Diagnostics > Voice
diagnostics > Reset, exercise listening, Snapshot, then Export JSON through the
document picker. Events/counters contain no audio or transcripts. Resources are
sampled only on Snapshot; diagnostics start off after process restart. See the
[wake-word acceptance report](docs/WAKE_WORD_ACCEPTANCE.md) for audited engine/
model details, editable proposed thresholds, raw-count protocols, non-root ADB
steps and outstanding physical gates. All K2501 measurements remain pending;
passing host tests/build is not wake-word acceptance.

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
- Overlay missing: check its saved switch, assistant switch and Android overlay
    grant, then open NexaAI and tap Refresh / retry overlay. Do not infer activity
    from a saved wake flag. If firmware blocks overlays, leave it off and use Home.
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