# Architecture

## Switchable ASR (2026-10-29)

The existing `SpeechRecognizerEngine` is extended, not duplicated. It exposes
stable identity, Loading/Ready/Failed/Unloaded status, timed typed partial/final
results, typed errors and a closeable PCM `RecognitionSession`. Null confidence
means unavailable, never zero or an invented score. Times are monotonic milliseconds,
not wall-clock timestamps. The older String callback bridge remains for existing
tests/alternate engines. Only explicitly final results pass the coordinator's
existing prepare/validate/single-use execute path; cancellation is checked before
dispatch. Raw text is kept by replay; optional Vosk grammar canonicalization
remains only in the existing production Vosk command path.

`SelectedSpeechEngine` snapshots schema-1 `asrEngineId`, waits for valid settings,
serializes activation/import/recognition, and releases the old model before
constructing its replacement. Unknown IDs fail closed; strict imports reject them.
There is no automatic fallback. Menu changes are rejected while the coordinator
is active. Config imports/resets can update preferences but cannot mutate an
active decoder; they apply next session. The UI distinguishes selected from
cached loaded engine. The cache releases at next switch, service teardown, import
or evaluation; no two complete ASR models coexist. Startup/boot loads none.
Hands-free now delays ASR loading until first command activation, instead of
preloading Vosk before waiting for wake. The tiny KWS model may remain loaded
alongside the one ASR model; its cost needs measurement.

Microphone lifecycle:

1. The application-owned coordinator gate excludes push-to-talk, hands-free,
    imports and evaluation from one another.
2. The unchanged wake detector stops/closes/joins its recorder before ASR starts.
3. Both ASR adapters use one application-owned `AndroidCommandAudioCapture`.
    Decoders consume frames and never create AudioRecord. Capture preserves
    16 kHz mono PCM16, `max(minBufferBytes * 2, 16000)` buffer (minimum <=65536),
    15 seconds, persisted source and saturating gain. A recorder is created once
    per command, not per frame. Listening follows real recorder readiness.
4. First nonempty endpoint or Finish flushes the decoder. Vosk retains its
    acceptWaveForm/result/partialResult/finalResult and optional grammar behavior.
    sherpa uses default endpoint rules (2.4 s silence; 1.4 s trailing speech silence;
    20 s utterance rule, bounded by app capture), greedy CPU/one thread.
5. Capture closes before decoder-session cleanup; models cache across commands.
    Cancel stops capture, closes sessions and suppresses dispatch. Release follows
    session join. TTS follows capture closure; hands-free waits for completion and
    cooldown before reopening wake audio.
6. Explicit replay takes the same gate, stops TTS, releases cached ASR/KWS, then
    runs each ASR model sequentially on local PCM on IO. No recorder/controller runs.

Session creation and frame calls are IO-caller operations. They deliberately do
not return newly allocated native streams across cancellable dispatcher handoffs,
which could discard ownership before cleanup. Loading/production/replay entry
points provide IO. Native calls are not forcibly preemptible; deadlines apply at
call/frame boundaries. Recoverable linkage/OOM errors surface truthfully; native
aborts or OS kills need user restart. No permission, dependency or radio/parser/
TTS policy change. See [evaluation](ASR_EVALUATION.md).

## Command Capture Readiness and Input Controls

The coordinator remains Initializing during Vosk grammar/recognizer construction;
the engine's readiness callback publishes Listening only after AudioRecord enters
recording state. This avoids prompting speech before capture starts. The legacy
engine method is retained with a default bridge for alternate/fake engines.
Wake audio still closes before command capture and is not replayed.

Two optional schema-1 settings configure command capture: `commandAudioSource`
(`VOICE_RECOGNITION` default or `MIC`) and `commandInputGainPercent` (100 default,
100-300 allowed). Old files retain original behavior. Validated options are read
once before the next capture, not changed mid-buffer. Bounded in-place integer
amplification affects only the samples actually read and saturates to PCM16;
100% is unchanged. This is not a hardware gain, noise suppression or measured
accuracy improvement. Both command ASR engines share these controls; native wake thresholds,
audio, model, parser and radio mappings do not. Settings persist/export through
the existing strict configuration path; physical source routing remains OEM-owned.

## Milestone 6 Diagnostics

One application-owned `VoiceDiagnostics` is injected into the existing coordinator,
native detector/ASR wrappers, TTS callbacks and microphone service. Typed events
use elapsedRealtime milliseconds; no UI-state inference, transcript strings or
per-frame samples. A synchronized 512-event ring and bounded event/code counters
are opt-in, memory-only and resettable. Events identify actual capture boundaries,
manual versus native wake triggers and observed service re-creations, not OS-wide
uptime. Complete retained event pairs yield count/median and p95 only at >=20;
failures, cancellations and acoustic endpoints are not invented as successes.

`ResourceMetrics` is injectable and read only on explicit Snapshot, off main.
Android reads own-process PSS, optional RSS and cumulative CPU time plus supported
device-wide battery/thermal status. Missing values are omitted. No background
sampler, new permission/dependency or persistent audio is introduced. ViewModel
holds a frozen preview; user-confirmed SAF JSON export is separate from portable
configuration and capped at 512 KiB. Reset clears the preview/history but not
previously exported files. Process death loses diagnostics and disables capture
of metrics until explicit re-enable. No service restart, detector tuning, boot
policy or radio/command change is authorized by diagnostics. See
[acceptance protocol and metric limitations](WAKE_WORD_ACCEPTANCE.md).

## Baseline decisions

Single Android application module, Kotlin, Gradle Kotlin DSL, Compose Material 3.
Minimum API 28 preserves Android 9 compatibility without assuming the reported
head-unit Android version is accurate. Compile/target API 35 is installed on the
host. JDK 17, AGP 8.9.2 and Gradle 8.11.1 form the pinned build baseline.

Dependency choices: Compose BOM 2025.04.01, activity-compose 1.10.1,
lifecycle 2.9.0, navigation-compose 2.8.9, DataStore Preferences 1.1.4,
coroutines 1.10.2 and kotlinx.serialization JSON 1.8.1; test-only JUnit 4.13.2, Robolectric 4.14.1,
AndroidX Test core 1.6.1 and Compose UI tests. AndroidX is Apache-2.0,
Kotlin/coroutines/serialization Apache-2.0, JUnit EPL-1.0 and Robolectric MIT.
No speech/LLM runtime or model license is implicitly approved by these choices.

## Milestone 1 boundaries

The implemented configuration path is:

```text
Compose screens -> lifecycle ViewModel -> validated repositories -> Preferences DataStore
                                      <- immutable StateFlow <- persisted snapshots
```

UI does not call action controllers. Milestone 2 adds execution below this shell.
Command IDs form a closed catalogue. Aliases and enablement are data; they never
register code, intents or package names. Settings and command edits are applied
to state only after storage succeeds. Errors must be visible, not replaced with
success messages. No DI framework or database is needed for this scope.

## Configuration policy

Schema version 1 uses safe defaults: assistant enabled, name NexaAI, offline-only
true, push-to-talk, short confirmation preference (inactive). TTS, wake word,
overlay and startup are implemented with explicit opt-in and default off;
debugging and local LLM remain unavailable. TTS defaults off rather than the conceptual example's
true value: no voice has been verified offline. Only display name and assistant
preference are editable general settings in this shell.
Future capability values must not be trusted from imported configuration.

Missing data uses defaults. Corrupt preferences recover to safe defaults with a
visible status. Unknown schema versions must not be silently executed or
interpreted as current data. Imports must be size-bounded, strict, validated and
previewed before the user explicitly applies them. Exports contain only portable
configuration, not diagnostics, device identifiers or private file paths.

`LegacySettingsMigration` migrates a pre-schema `display_name` preference, retaining
only a valid printable name and applying all other safe defaults. There was no
preexisting released app schema. Preferences protobuf corruption is recovered by
DataStore's corruption handler with a visible recovery notice. Invalid/newer JSON
is preserved on disk while safe defaults are displayed; editing stays disabled
until explicit full reset or an import replaces it. No unknown schema is silently
downgraded. JSON syntax is checked by kotlinx.serialization, with explicit field,
type, command and capability validation before storage.

`ConfigurationDocuments` performs bounded SAF I/O on `Dispatchers.IO`. The import
preview is temporary ViewModel state; cancelling or closing the app cannot apply
it. The repository writes validated snapshots transactionally through DataStore;
aliases and command enablement update the visible catalogue without a restart.
The only system reads in the UI path come through `DeviceDiagnostics`.

## Milestone 2 command execution

```text
Home input -> ViewModel -> CommandRuntime -> exact parser -> validator/capability check
    -> single-use PreparedCommand -> Execute or Cancel
    -> latest DataStore snapshot + reparse + revalidation -> controller -> typed result
```

`domain/Commands.kt` owns closed payloads and typed results. Existing `CommandId`
and `CommandCatalogue` remain the only catalogue/seed alias source. Full anchored
matching follows locale-independent case/whitespace normalization, without fuzzy
or substring matching. Unknown, ambiguous, disabled or incomplete commands are
rejected. Alias validation detects template intersection, including differently
sized multiword placeholders, before persistence.

`CommandDispatcher` validates settings, enablement, payload shape, parameters and
allowlists before controller permission/capability checks. Invalid/corrupt/newer
configuration blocks execution rather than activating default actions. A coroutine
Mutex serializes execution. Tokens are single-use; cancellation or replacement
invalidates pending work. Settings/aliases/allowlists are reread at execution;
changed interpretations must be prepared again. Cancellation cannot undo an
Android operation already sent. Expected security/state/argument exceptions become
typed permission/failure results; coroutine cancellation propagates.

Constructor-injected `VolumeDevice`, `AppDevice` and `MediaDevice` isolate Android
APIs for fake-device tests. Volume maps/clamps only the music stream's range,
rejects fixed volume and verifies Android level/mute state. Audible OEM output
remains a device gate. Launching requires allowlisting and current installed
launcher visibility; packages are never inferred from text. Media requires
optional notification-listener access, exactly one active session and an
advertised action. Transport APIs do not acknowledge playback changes: responses
say request sent/unverified. No media-button broadcast or coordinate fallback.

`AppAllowlistRepository` shares the existing DataStore but uses independent
`app_allowlist_v1` data/schema, empty by default. Installed package IDs and unique
friendly names persist privately with bounded, strict JSON validation and
fail-closed recovery. Excluding them from portable imports/exports prevents
imports granting app-launch access. Full reset also clears the allowlist.
Existing portable settings remain schema 1.

`AndroidDevices` owns AudioManager, PackageManager and media-session calls.
Discovery uses launcher/home intent-scoped queries without `QUERY_ALL_PACKAGES`.
Package discovery/SAF I/O use `Dispatchers.IO`; launches use `Main.immediate`.
ViewModel coroutines publish immutable UI state on main. Capability refresh is
explicit; execution always rechecks, including permission revocation. Development
transcripts, results and tokens remain in memory only.

The production `CommandRuntime` receives `VerifiedRadioController`, backed by
`AndroidRadioDevice`. `RadioProtocol` owns the package, component, action/extra
strings and key codes. The explicit activity launch catches a missing activity;
Android security failures are returned by the existing dispatcher boundary.
Broadcasts are package-scoped and asynchronous, so success means only that the
local request was sent; receiver delivery and tuner state are not confirmed.
The controller validates 87.5-108.0 MHz and converts decimal MHz to 10 kHz units
using `HALF_UP`. Host tests cover intent payloads and controller mappings, not
K2501 receiver permissions or radio behavior.

## Milestone 3 offline speech

```text
Home mic -> SpeechSessionCoordinator -> SpeechRecognizerEngine (Vosk)
    -> bounded AudioRecord capture / partial UI -> final transcript only
    -> existing CommandDispatcher.prepare -> single-use PreparedCommand
    -> existing CommandDispatcher.execute -> existing controller/result
    -> UI result + optional offline-only Android TextToSpeech
```

`SpeechSessionCoordinator` owns the typed `VoiceState` flow and a single-session
gate. It uses the repository's `normalizeAlias`, never dispatches partials, rejects
empty final transcripts, and sends normalized final text through the same parser,
validator, configuration checks and dispatcher as typed input. A valid voice
command executes once after `prepare`; typed development input keeps its existing
explicit Interpret/Execute confirmation path. Rejected, disabled, unsupported,
permission and controller outcomes remain `CommandResult` values.

`VoskSpeechRecognizerEngine` owns one cached `org.vosk.Model` per ViewModel runtime
and creates/closes a recognizer for each push-to-talk session. `AudioRecord` uses
VOICE_RECOGNITION, 16 kHz, mono, signed 16-bit PCM and bounded buffers; capture is
limited to 15 seconds and ends on Finish, Cancel, lifecycle stop, timeout or error.
Only final Vosk JSON text is submitted. Vosk confidence is not invented; exact
parser/validator matching remains the conservative gate. Initialization and
inference run off main.

`VoskModelInstaller` accepts a user-selected content URI, extracts into a staging
directory, rejects traversal/oversize archives and requires core model files before
replacing the private model directory. No model is bundled or fetched automatically.
Missing, invalid or ABI-incompatible model data produces an on-screen error; there
is no online fallback. See [Models](MODELS.md).

`AndroidSpeechOutput` initializes TextToSpeech asynchronously, selects an installed
English voice without the network-synthesis feature, and reports unavailable if
none qualifies. It never installs voice data or falls back to a cloud engine. The
persisted TTS switch defaults off; `QUEUE_FLUSH` replaces any ongoing utterance.
Detailed results remain visible if speech is disabled or unavailable.

The original push-to-talk route requests microphone permission from Home and
cancels capture when leaving Home or backgrounding the activity. The hands-free
extension below shares that runtime rather than creating a competing recognizer.
Audio and transcripts are not logged, stored or uploaded. Host fakes cover
coordinator/dispatcher semantics; they do not verify model quality, actual TTS
offline behavior or the head unit.

## SDK behavior to preserve in later work

Android 9 restricts background microphone access. Android 11 adds microphone
foreground-service type requirements. Push-to-talk stays in the visible activity
and does not use a microphone foreground service. Android 12 restricts background service
starts; Android 14 adds typed FGS permissions and while-in-use checks; newer
targets further restrict boot starts. Overlay permission is separate from
microphone permission. None of these permissions/services is needed by Milestone 1.

References: [foreground services](https://developer.android.com/develop/background-work/services/fgs),
[microphone permission](https://developer.android.com/training/permissions/requesting),
[DataStore](https://developer.android.com/topic/libraries/architecture/datastore),
[package visibility](https://developer.android.com/training/package-visibility).

## Requested Milestone 4 Hands-Free Extension

`NexaAiApplication` now owns one coordinator, recognizer/model cache and speech
output; ViewModels observe them without destroying a live foreground session.
`HandsFreeControl` persists the existing wake flags and requests a nonexported
`HandsFreeService` only after an explicit visible-app action. Saved/imported flags
are preferences, not permission to start recording. There is no boot receiver or
sticky service restart. No duplicate state machine or hardware controller is added.

```text
Explicit opt-in -> microphone permission -> microphone foreground service
    -> Initializing -> WaitingForWakeWord (sherpa keyword inference only)
    -> close wake AudioRecord -> WakeWordDetected -> Listening (existing Vosk)
    -> Processing -> Recognized -> existing prepare/revalidate/execute
    -> Completed -> optional SpeakingResponse (await offline TTS completion)
    -> 350 ms cooldown -> WaitingForWakeWord
```

The existing atomic session gate blocks parallel push-to-talk/import/hands-free.
Wake inference emits no transcript into the command path. Only the final ASR text
uses the existing exact parser/validator/single-use dispatcher; ordinary keyword
no-match frames and partial recognition never execute. Vosk capture is bounded to
15 seconds, with a 16-second coordinator deadline. Silence/timeouts rearm safely;
model, audio or TTS failures stop hands-free with `VoiceState.Error`. Manual retry
is required; there is no busy retry loop. Cancellation is checked before dispatch;
an already-sent hardware action cannot be undone.

Capture and inference run on IO. Awaiting keyword detection closes its recorder
before returning, so Vosk creates a fresh capture without replaying wake frames.
TTS has utterance-ID completion/error/stop callbacks, a 20-second deadline and
transient-may-duck focus. No recorder runs during spoken responses. Playback/focus
failure stops hands-free rather than resuming into uncertain playback. Wake listening
does not seize permanent audio focus from radio/navigation. API 29+ recorder silencing
stops capture and discards command audio; API 28 contention behavior needs target tests.

Service Stop/destruction cancels capture immediately and releases both native models
off main after the session unwinds. Foreground navigation/activity recreation keeps
an explicitly enabled service alive; push-to-talk still stops on lifecycle loss.
Permission denial, foreground-start exceptions, and a 10-second startup watchdog
surface useful errors with push-to-talk fallback. Live configuration disabling the
assistant or wake mode, or changing the wake phrase, stops the service. OS process death and vendor sleep are
not bypassed; no wake lock or restart receiver is introduced.

`VoskModelInstaller` retains its original call API and now accepts required-file
and root-validator parameters for the separate keyword package. Both import routes
share bounded staging, traversal rejection and safe replacement. See Models for
licenses and exact assets. Import validates a single English BPE keyword without
requiring the current setting to match; detector initialization enforces a match
with the saved configurable wake phrase. Text changes do not regenerate tokens.

## Milestone 5 Overlay and Startup

```text
Settings -> existing ConfigurationRepository -> overlay preferences
Activity resume / explicit retry -> OverlayControl -> OverlayService
    -> OverlayController -> AndroidOverlayWindow -> one WindowManager view
    <- existing VoiceState + requested/actual hands-free flows
Idle tap -> HandsFreeControl.listenOnce -> existing microphone FGS -> coordinator
Wake-waiting tap -> coordinator.requestCommand -> ordered wake/ASR handoff
Command-active tap -> HandsFreeControl.disable; hold -> MainActivity Home
After-unlock boot/update -> StartupPolicy gates -> OverlayControl (no voice start)
```

`OverlayControl` owns service-request/runtime status, not listening state. Its
pending-start gate and 10-second watchdog surface failure without retrying.
`OverlayController` is a constructor-injected, idempotent window lifecycle;
unit tests substitute an `OverlayWindow`. Android uses `TYPE_APPLICATION_OVERLAY`,
nonfocusable/nontouch-modal flags, visible opacity >=50%, and >=56 dp controls.
The transparent icon window contains one square robot ImageButton at the saved
icon size; no permanent panel, text strip or separate Stop button is drawn.
Tap requests a command when idle or waiting for wake; during command processing
it cancels the session. Hold opens Home. Crossing touch slop cancels native click/long-click handling before moving
the window; release persists the clamped position without invoking actions.
Native pressed-state artwork and accessibility descriptions expose the current
state/action; full textual listening status remains in the main activity.
Dragging uses logical-edge bounded coordinates, excludes the lower 48 dp and a
16 dp edge margin, and stores percentages for display changes. These margins do
not establish automotive safety; stationary placement and target checks are required.
Actual wake waiting selects wake artwork; initializing, command capture, speaking,
errors and stopped states do not masquerade as an active detector. All four user
PNG assets are copied/renamed as Android drawables by a Gradle resource task; the
source artwork is not rewritten. Existing vector remains the notification small icon.

`OverlayArtwork` caches four small listening variants. It recolors chromatic pixels
only inside a circular chest-light mask centered at (50% width, 70.5% height),
radius 5.5% of the square PNG. This replaces the oversized rectangular mask that
tinted body shading below the light. The low saturation cutoff includes its pale
center; alpha and every pixel outside the circle stay unchanged.
Only `WaitingForWakeWord` in an actual
hands-free session and `VoiceState.Listening` set `microphoneActive`; initializing,
pending starts, processing, speech and failures keep the blue indicator. Pixel
tests cover normal/pressed offline/wake variants and transparent edges.

The listening body color uses hue 125 degrees, 90% saturation and brightness capped
at 78%, replacing the earlier pale green without tinting other image regions.
`OverlaySpeech` carries only real partial/final transcripts and result/error messages
from VoiceState, with You/NexaAI labels. New capture/initialization clears stale text;
processing/speaking can retain the latest observed text and wake waiting retains
the previous completed command's result. It never submits partials to dispatch.

`OverlaySpeechBubble` owns a separate bounded TYPE_APPLICATION_OVERLAY window so
text cannot change the icon's coordinates, size or saved drag percentages. It uses
a scrollable 16 sp white text body and matching neutral grey (#424242) background
and pointer, choosing physical left/right room
after converting the icon's logical RTL coordinate. Width is capped at 320 dp and
available space; height at 220 dp and available display height. Physical gravity
constants are deliberate for this coordinate calculation; lint reports unsuppressed
RTL style warnings. The bubble follows dragging/configuration updates, expires
12 seconds after text changes and dismisses on text tap. Repeated state/layout
updates cannot resurrect dismissed/expired identical text. Removal clears the view,
handler callback and text references; partial attachment failure removes both
windows. There are no animations, polling, transcript logs, network calls or
additional command/audio sessions. Real head-unit framing/legibility needs testing.

`HandsFreeControl.listenOnce` rechecks microphone access and valid assistant
configuration, deduplicates pending starts, and requests the same nonexported
microphone service with a command-only action. It never writes wake preferences
or launches an activity. The 10-second watchdog reports a missing service without
retrying; failures are exposed in voice state and a changed-error overlay Toast.
The service foregrounds with the microphone type before capture, rechecks permission
and latest configuration, starts `SpeechSessionCoordinator.start(fromService=true)`,
waits for the session/optional TTS to finish, then stops. `cancelForLifecycle` leaves
this service-owned capture running, while ordinary activity push-to-talk retains
its cancellation behavior. Final transcripts still use prepare/revalidate/execute.

During hands-free wake waiting, a CompletableDeferred manual trigger races keyword
detection in a structured child scope. The losing wake wait is cancelled/stopped
and joined before Vosk capture starts; both paths produce exactly one command
session and the existing cooldown/TTS barrier precedes wake rearming. No second
detector, recorder, wake algorithm or command engine is added. Continuous operation
still requires the existing explicit hands-free opt-in. Background microphone/FGS
restrictions on newer APIs may reject a direct tap start; this never opens Home
automatically or weakens permissions. Hold-to-open visible push-to-talk is fallback.

Only the separate permission button opens Android special access. Activity resume
rechecks the grant without prompting. Service AppOps callbacks, configuration/display
changes, state changes and start-time checks recheck permission; loss removes the
view and stops the overlay service. No polling, animation, wake lock, recorder or
second command engine is added. Window/drag integration failures stop rather than
looping. Disabling overlay does not stop independently enabled hands-free.

The notification channel is low importance, with Open and Disable overlay actions.
API 34+ uses `FOREGROUND_SERVICE_SPECIAL_USE` and the manifest subtype for persistent
user-enabled floating controls; older APIs use ordinary foreground service behavior.
This is not a microphone service type or permission exemption. Boot work and overlay
creation do not call speech initialization/capture; the shared coordinator may be
constructed to observe state, but native models remain deferred to existing actions.

Boot receiver accepts only `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, checks
`UserManager.isUserUnlocked`, and uses `goAsync` with a six-second settings deadline.
No direct-boot receiver or device-protected duplicate settings exist. Valid saved
assistant + auto-start + overlay + current overlay grant are all required. The
only permitted request is the non-recording overlay. Existing wake preference is
never a request to resume recording. Both services return `START_NOT_STICKY`;
no scheduled restart or boot activity/radio launch exists. Startup status describes
only this process's observed broadcast/request; a fresh process explicitly says
not observed/unverified. Opening the app is the manual fallback after force-stop,
firmware termination or denied foreground starts.

Schema 1 adds optional, strictly typed/range-validated fields: `overlaySizeDp`
(56-96, default 64), `overlayOpacityPercent` (50-100, default 90),
`overlayXPercent`/`overlayYPercent` (0-100, defaults 100/50). Missing fields migrate
in memory without overwriting existing names, aliases or wake preferences; malformed
values continue to fail closed. Confirmed imports may configure overlay/startup but
cannot grant permissions or start the microphone. No new dependency is introduced.

Platform references: [overlay windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY),
[overlay special access](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)),
[special-use FGS](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[FGS background restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[direct boot](https://developer.android.com/privacy-and-security/direct-boot).