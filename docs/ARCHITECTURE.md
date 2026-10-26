# Architecture

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
overlay, startup, debugging, and local LLM remain unavailable/off until backed
by real implementations. TTS defaults off rather than the conceptual example's
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