# Test Plan and Results

## Evidence policy

Record actual command outcomes, not assumed success. JVM tests can validate
configuration and Compose flows; they do not verify OEM permissions, microphones,
radio hardware, offline voices or ignition lifecycle. No device was attached at
baseline inspection (`adb devices -l`: empty).

## Automated gates

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Milestone 0: build baseline and package-identity test before adding configuration.
Milestone 1: safe defaults, schema migration/recovery, strict import validation,
alias collisions, enablement, persistence after repository recreation, reset,
six-screen navigation, disabled listening, settings and alias UI flows.

## Host results (2026-10-23)

Milestone 0: `assembleDebug` and the baseline package-identity test passed before
Milestone 1 implementation (44 tasks, first build 7m24s after Gradle download).

Milestone 1 host suite: 12 tests, including 3 Compose UI tests, with no failures:

- `BaselineTest`: package identity (1).
- `ConfigurationTest`: defaults/round-trip, strict schemas/fields/types/flags,
	aliases/collisions, legacy migration, invalid/corrupt configuration and
	repository state, Unicode export/import size limit (6).
- `ConfigurationFlowTest`: close/reopen actual DataStore file; document
	round-trip, import preview/cancel/apply, reset, oversized import rejection (2).
- `ShellUiTest`: six-screen navigation and unavailable microphone; saved name
	and collision error; compact disable/reset cancel/confirm flow (3).

Tests run on the host with Robolectric API 28. No instrumented device/emulator
tests were run. SAF I/O tests use file URIs through a host ContentResolver;
real external document providers still require manual testing.

The final checked-in-wrapper command `./gradlew :app:assembleDebug
:app:testDebugUnitTest :app:lintDebug --console=plain` passed in 43s
(53 tasks). All 12 tests passed with zero skipped, failed or errored tests.
Lint reports 0 errors and 10 dependency/target-version warnings. Editor diagnostics
for the configuration and UI source files also report no errors.

The debug build, tests and lint pass. Lint initially found an API 33-only
`readNBytes` call; a bounded API 28-safe stream read fixed it. A compact reset
test exposed clipped horizontally scrolling actions; wrapping the action rows
fixed the failed flow. Remaining lint warnings concern intentionally pinned
dependency versions and target API 35 rather than the latest SDK. No lint
baseline or API suppression is used.

The Gradle 8.11.1 distribution SHA-256 was fetched from the published Gradle
endpoint and matched the downloaded ZIP; its checksum is pinned in the wrapper.
Further device-attachment checks were skipped at the user's request. All target
acceptance gates below remain unverified rather than passed.

## Manual Milestone 1 acceptance (not yet run)

1. Install debug APK on the stationary K2501; record API/fingerprint/ABI/memory/storage.
2. Navigate all six screens in landscape and portrait, including small/large font scale.
3. Edit name/verbosity and command aliases; force-stop/relaunch; verify persistence.
4. Reject invalid/duplicate aliases without altering saved definitions.
5. Export/import configuration via a real document provider; inspect preview and cancel/apply.
6. Try corrupt, oversize and future-version configuration; existing settings must survive.
7. Reset with cancel/confirm; verify safe defaults after restart.
8. Disable networking; repeat configuration flows. No speech claim follows from this test.
9. Use visible push-to-talk only: verify runtime permission grant/denial, and ensure
	Cancel, Home navigation, backgrounding and the 15-second cap stop capture.
10. Check touch targets, focus/pressed states, readable wrapping and scroll access on the head unit.

## Milestone 2 host results (2026-10-23)

Baseline tests and debug build passed before changes. Final command
`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`
passed in 1m25s (53 tasks): **29 tests, zero failures/errors/skips**, lint zero
errors and 10 existing dependency/target notices. No lint/API suppression added.

After documentation updates, `:app:compileDebugUnitTestKotlin --rerun-tasks`
also passed (17 executed tasks). VS Code intermittently reports unresolved
JUnit/Robolectric/test imports in the existing UI/persistence test files despite
successful Gradle compilation and execution; editor test classpath synchronization
remains an environment caveat, not a suppressed build error.

- `CommandParserTest` (4): seeded/live aliases, case/whitespace normalization,
  full matches, invalid volume/frequency syntax, unknown apps, disabled/ambiguous
  commands and multiword-template collision prevention.
- `CommandExecutionTest` (8): parameter/configuration/allowlist gates, verified
  fake FM band/step, unsupported radio, missing permission, returned/thrown
  failures, cancellation/single-use/stale tokens, help/repeat/listening and
  volume mapping/fixed/no-op handling.
- `SafeControllersTest` (3): allowlist schema/collisions, unknown/removed apps,
  failed launch and media access/session/action/ambiguity gates.
- `ConfigurationFlowTest` (3): original tests plus private allowlist close/reopen
  persistence, corrupt data failing closed and explicit reset.
- `ShellUiTest` (4): original screen/settings/compact flows plus Home
  interpretation/cancel/execute and unsupported FM preventing Execute.
- Existing `ConfigurationTest` (6) and `BaselineTest` (1) pass unchanged.

Fake volume tests validate mapping semantics, not actual audible output. The fake
87.5-108/0.1 MHz band is test data only, not the K2501 tuner specification. No
head unit/emulator/instrumented hardware test was performed.

## Interpret crash correction (2026-10-24)

An Android 14 user reported Interpret crashing and Execute never becoming
enabled. Both placeholder patterns used an unescaped literal closing brace.
The host JVM accepted these patterns, so Robolectric did not reproduce the
Android regex failure. Native ICU 74 rejects both old patterns with status
66305 and accepts the corrected shared `\{[^{}]+\}` pattern with status 0.
The corrected expression was extracted from the actual Kotlin source for the
native check. Parsing and configuration validation now use that same expression.

`CommandParserTest.placeholderPatternEscapesBracesForAndroidIcu` locks the escaped
pattern contract and tests valid/empty/nested placeholders plus default validation
and Help interpretation. Existing Compose tests confirm successful interpretation
enables Execute, cancellation disables it, and unsupported FM cannot execute.

Full `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`
passes: 30 tests, zero failed/errored/skipped, lint zero errors and 10 existing
warnings. The debug APK was rebuilt. Native ICU is host reproduction evidence,
not Android 14 or K2501 device verification; no device crash log was captured.
On 2026-10-24, the user confirmed the app was working fine and all reported issues
were fixed. Record the reported regression as resolved by user retest in the
previously reported Android 14 environment. No detailed command matrix, device
model/build or logs were supplied; this does not mark the K2501 acceptance matrix
as passed. Unsupported or rejected commands must still leave Execute disabled.

## Manual Milestone 2 acceptance (not yet run)

1. Install on the stationary K2501; record API/build/ABI/memory/storage evidence.
2. Disable Wi-Fi/mobile data and execute typed Help/volume/app/media commands;
	record actual outcomes. Confirm microphone stays off.
3. On each audio source, test 0/30/100 percent, up/down at limits, mute/unmute;
	compare Android level with audible behavior and check OEM fixed/no-op cases.
4. Select installed apps/aliases; verify restart persistence, collisions, app
	removal/uninstall, home navigation and absence of force-stop claims.
5. Deny/grant/revoke notification-listener access. Test each media action/player,
	zero/multiple sessions and unsupported actions. Check actual playback separately
	from the request-sent message.
6. Change aliases/disable commands after interpretation and retry execution;
	ensure revalidation. Cancel pending work; verify no repeat controller action.
7. Test real SAF imports with preview/cancel/apply and no imported app grants.
	Verify definition, allowlist and full resets.
8. Verify FM tune/seek and listening remain unsupported without vendor calls or
	recording. Check compact/landscape, long names, font scaling and touch access.
9. Measure latency/RAM alongside radio/navigation; APK size is not RAM evidence.

LLM JSON safety and model-load fallback remain deferred to their real runtime
milestones; Milestone 2 introduces no LLM/speech/model dependency.

## Milestone 3 host tests and setup

Speech session tests use fake recognizer, installer, command controllers and TTS
output. They cover successful/failed initialization, empty/partial/final text,
single submission, duplicate start, cancellation, permission denial, parser
rejection for unknown/ambiguous/disabled/invalid commands, injected radio dispatch,
truthful unsupported/failure speech, TTS disabled/unavailable and cleanup. A
malformed local ZIP test covers model import rejection. These tests do not load the
real Vosk model, access a microphone, initialize a real TTS voice or call tuner
hardware. See [Models](MODELS.md) for local model setup.

Final host gate on 2026-10-24: `:app:testDebugUnitTest` passed **40 tests** with
zero failures/errors/skips; `:app:assembleDebug` succeeded; `:app:lintDebug`
reported zero errors and 10 existing target/dependency/backup warnings. The debug
APK is approximately 93 MiB and contains Vosk libraries for arm64-v8a,
armeabi-v7a, x86 and x86_64. The merged manifest contains `RECORD_AUDIO` and
`MODIFY_AUDIO_SETTINGS`, with no Internet permission. No target device, real model,
microphone or TTS voice was available, so those remain manual acceptance gates.

## Manual Milestone 3 acceptance (not yet run)

Recognition-result correction (2026-10-26): a user reports visible partial speech
followed by an empty-recognition error, including after Finish. Inspection found
the engine consumed Vosk endpoint `result` as partial feedback, then returned only
the often-empty trailing `finalResult`. Nonempty endpoint text now immediately
ends capture and returns to the existing coordinator. `VoskResultTest` distinguishes
completed endpoints from partials and silence; existing coordinator tests cover
final-only single dispatch and cancellation. Retest on-device with a configured
alias such as `volume up`, both waiting for the endpoint and tapping Finish.

1. Install on the stationary target, note Android/API, ABI, available storage/RAM,
	grant `RECORD_AUDIO` only when prompted, then deny/revoke it and verify typed
	command entry remains usable.
2. Import the official 40 MB English model ZIP through Models. Try missing, corrupt,
	incomplete and low-storage cases; confirm useful errors and no network fallback.
3. Disable Wi-Fi and mobile data; test volume 30%, a selected friendly app, play/
	pause, unknown/ambiguous phrases and invalid FM frequencies. Record transcript,
	typed result and actual action separately.
4. Verify partial speech never acts, Finish submits once, and Cancel/navigation/
	background/timeout stop capture. Check another app does not keep the microphone.
5. In Android TTS settings, select an installed English offline voice. Test with TTS
	off/on, missing voice data and network disabled; verify no automatic download and
	that errors remain visible.
6. Radio request adapter host tests are covered under Milestone 4; complete the
	separate installed-APK radio checklist below on K2501 before claiming device
	compatibility.
7. Measure cold/warm model load, recognition latency, peak RAM/CPU, battery impact
	and interference with radio/navigation. Record results before calling the head unit
	compatible or the complete offline flow verified.

## Target acceptance matrix (all not yet verified)

| Area | Required conditions |
| --- | --- |
| Offline speech | Wi-Fi/mobile data disabled; installed offline assets |
| Recognition | Radio/music playing, stationary cabin noise, silence, partials, duplicate finals |
| Audio lifecycle | Permission denied/granted/revoked, TTS isolation, timeout/cancel |
| Volume | Each actual source, fixed-volume detection, OEM no-op verification |
| App/media | Each installed allowlisted app/session and each supported transport action |
| Radio | From installed NexaAI APK, test explicit launch, tune, seek, mute/unmute, AMS and INTRO; separately observe receiver delivery and tuner state |
| Startup | Cold/warm start, reboot, ignition cycle, sleep/wake, screen off |
| Overlay/service | Grant/deny/revoke, dismiss, battery optimization, no obstructed critical UI |
| Storage | Low space, corrupt/missing models, safe import/deletion |
| Resources | Long-run peak RAM/CPU, cold/warm latency, battery and navigation interference |
| Wake word | Real-engine false accepts/rejects, measured threshold, stop mechanism |

Do not proceed to later milestones until the current build and automated gates
pass. Target-dependent items remain explicitly unverified until tested there.

## Milestone 4 host tests and device checklist

Host tests cover the required voice phrase mappings, outside-band rejection,
explicit activity component, package-scoped broadcast actions/extras, integer
frequency conversion (`HALF_UP` to 10 kHz), key values 5/6/46/61, mute booleans,
and local activity-launch failure. A successful `sendBroadcast()` is treated as
a request, not receiver or tuner confirmation. These tests do not reproduce
receiver permissions or prove that the normal NexaAI APK can control the radio.

Final host gate on 2026-10-26: `:app:assembleDebug :app:testDebugUnitTest
:app:lintDebug --console=plain` passed; all **49 tests** passed, the debug APK
assembled and lint completed successfully. `git diff --check` is clean. `adb
devices -l` returned no attached devices, so installed-APK broadcast permissions,
receiver delivery and K2501 radio behavior were not tested.

The exact pre-radio 19-command schema-1 catalogue is upgraded by adding the five
radio defaults; existing settings, aliases and enablement are retained. Partial
or otherwise invalid command catalogues continue to be rejected.

Manual K2501 checklist (not run in this environment):

1. Install the normal debug APK; confirm `com.nwd.radio` is installed and NexaAI
	can open its explicit activity. Test missing activity/security failure handling
	where safely possible.
2. While stationary and offline, execute “Open radio”, “Tune to FM 98.3”, and
	“Tune to 101.1 FM”; observe actual station and displayed frequency separately
	from the spoken request confirmation.
3. Test “Next station” and “Previous station”; confirm station seeking, not a
	frequency step. Test “Increase frequency” and confirm it never maps to search.
4. Test “Mute radio” / “Unmute radio”; observe radio audio state separately from
	the request result.
5. Test “Scan and save stations” (AMS) and “Preview radio stations” (INTRO)
	separately; verify the radio's native behavior and saved/previewed stations.
6. Test unsupported frequency “Tune to 120 FM”, an unknown phrase, receiver
	permission restrictions, airplane mode, and app restart. Confirm no crash and
	that no completion is claimed without radio feedback.

## Requested Milestone 4 Hands-Free Extension

Automated host checks add to the existing push-to-talk/parser/configuration/radio
regressions, with no physical audio or model weights in tests:

- `SpeechSessionTest`: idle detector inactivity, initialization error cleanup,
  one wake/command session, no ordinary/wake transcript dispatch, partial-only
  nonexecution, silence, coordinator timeout, cancellation before final dispatch,
  duplicate start rejection, TTS completion barrier, ordered microphone handoff,
  foreground lifecycle survival and five enable/disable resource cycles.
- `WakeWordDetectorTest`: missing/mismatched/unsupported keyword files, vocabulary
  mismatch, injected incompatible native model/ABI, one model load, fake PCM no-match
  frames, reused buffers, read failures and recorder/inference cleanup.
- `HandsFreeServiceTest`: denied microphone permission and injected foreground-start
  SecurityException leave capture inactive, show errors and allow explicit disable.
- Existing dispatcher tests cover disabled/ambiguous/invalid/unsupported commands;
  `RadioControllerTest` retains exact component/actions/extras/5/6/46/61/mute tests.
- Existing API 28 compact/landscape Compose flows remain acceptance gates, as do
  all Milestone 0-3 host tests. Real recorder/TTS/FGS interaction needs a device.

Required gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
The normal APK contains no network permission. Test results establish control-flow
and payload behavior, not acoustic recognition or actual hardware effects.

Host completion evidence (2026-10-26): **60 tests, zero failures/errors/skips**;
`:app:assembleDebug` and `:app:lintDebug` passed; lint reports **0 errors and 11
warnings**. Ten are existing dependency/target/backup warnings; the additional
`ImplicitSamInstance` warning incorrectly treats `Context.stopService(Intent)`
as listener removal (Android identifies a service by component, not Intent object
identity). No lint error was suppressed. `git diff --check` passes. The debug APK
is approximately **191 MiB**, including both multi-ABI native runtimes but no
model weights. ABI-specific release optimization is deferred until actual target
ABI evidence is available. Tests and builds used the pinned toolchain; this host
needed `-Pkotlin.compiler.execution.strategy=in-process` after a Kotlin daemon
session error. This flag does not change runtime behavior.

Changed-file inventory for this extension:

- Build/manifest: `settings.gradle.kts`, `app/build.gradle.kts`,
	`app/src/main/AndroidManifest.xml`.
- Application wiring: `MainActivity.kt`, `NexaAiApplication.kt` under
	`app/src/main/java/com/nexaai/assistant/`.
- Configuration/UI: `data/Configuration.kt`, `ui/AssistantViewModel.kt`,
	`ui/NexaApp.kt` under the same source package.
- Speech: `speech/SpeechRecognition.kt`, `speech/VoskSpeechRecognizer.kt`,
	`speech/AndroidSpeechOutput.kt`, new `speech/SherpaWakeWordDetector.kt` and
	new `speech/HandsFreeService.kt` under the same source package.
- Tests under `app/src/test/java/com/nexaai/assistant/`: `SpeechSessionTest.kt`,
	new `WakeWordDetectorTest.kt`, new `HandsFreeServiceTest.kt`.
- Documentation: `README.md`, `AGENT.md`, `CHANGELOG.md`, `docs/ARCHITECTURE.md`,
	`docs/COMMANDS.md`, `docs/DEVICE_COMPATIBILITY.md`, `docs/MODELS.md`,
	`docs/PRIVACY_AND_PERMISSIONS.md`, `docs/TEST_PLAN.md`.

No radio controller, protocol constant, parser, command dispatcher or hardware
mapping was modified by this extension.

### Stationary Head-Unit Checklist (Not Run)

1. Record actual Android/API/build, ABI, free storage and available RAM. Install
	the normal APK and both explicitly prepared models. Start with hands-free off;
	verify push-to-talk still recognizes a supported alias and microphone stops.
2. Deny/revoke microphone permission, deny notification permission on API 33+, and
	test foreground-start restrictions. Confirm useful errors, no stuck enabled UI,
	no repeated restart attempts, and typed/push-to-talk fallback where permitted.
3. Enable hands-free while the app is visible. Observe the ongoing notification,
	privacy indicator where available and waiting state. Say ordinary speech and
	command aliases without the wake phrase: no hardware command should execute.
4. Say the saved wake phrase (default Hey Nexa), pause, then a valid command. Check final-only single execution.
	Test repeated wakes, partial phrases, silence, 15-second capture timeout, empty
	results, invalid frequency/volume, disabled commands and ambiguous aliases.
5. With installed offline TTS enabled, test long/error/interrupted responses and
	another wake during playback. Detection must not resume until playback ends;
	audio-focus/playback failures must stop hands-free visibly, with manual retry.
6. Disable with each in-app Stop/switch and notification Stop during waiting,
	recognition and playback. Verify microphone release, no delayed dispatch, and
	no retained native-model growth over repeated enable/disable cycles.
7. Navigate screens, rotate/recreate activity, background/foreground, switch to
	radio/navigation, introduce microphone contention and revoke permission. Record
	API 28 versus 29+ silencing behavior. Confirm no competing capture instances.
8. Disable Wi-Fi/mobile data before launching NexaAI; repeat wake/command/TTS
	flows without network access. Missing models/voices must not trigger a download.
9. Test screen off/on, sleep/wake, OS task stop/process death, reboot and ignition
	cycles. No automatic listening after process death or reboot is expected.
	Record firmware kills/suspends honestly; use push-to-talk if persistence fails.
10. Repeat the radio checklist above: launch, 9830 tuning extra, search 5/6, AMS
	 46, INTRO 61 and mute/unmute, observing tuner behavior separately from requests.
11. Measure cold/warm latency, false accepts/hour, missed wakes in cabin noise,
	 CPU/PSS/RSS over a long session, power/thermal effects and radio/navigation
	 interference. Compare with push-to-talk before declaring 2 GB suitability.
12. With hands-free stopped, import a ZIP tokenized for another English phrase
	and save that phrase in Settings > Listening. Verify the label updates,
	the setting survives relaunch/export/import, and the new phrase activates
	command capture. A mismatching phrase must fail before microphone capture;
	malformed/multiple keyword lines must still fail import. Importing a changed
	phrase configuration during a session must stop hands-free, not silently
	keep listening for the old phrase. Repeat on compact and landscape layouts.

## Configurable Vosk Grammar

Host regressions cover grammar off/on, enabled aliases, app-name expansion,
numeric final-transcript mapping through the existing parser, unknown and
ambiguous spoken-form rejection, custom vocabulary without command authorization,
per-session refresh, generation limits, preference validation, older schema-1
imports, persistence/export/import and Settings toggle/editor behavior.

Stationary device acceptance remains pending:

1. Compare the same recorded command and non-command audio with grammar off/on,
	in quiet and representative cabin noise. Record correct command rate and false
	executions separately; vocabulary constraints alone do not establish safety.
2. Exercise volume 0/50/100 and FM band edges/decimals, cardinal and digit-by-digit
	numbers, edited aliases, allowed app names, and `[unk]` rejection. Confirm only
	final transcripts dispatch and radio mappings remain unchanged.
3. Add custom entries, including a name absent from the model vocabulary. Verify
	persistence, no implicit command creation, and document any upstream ignored
	words. Custom vocabulary cannot add acoustic pronunciations.
4. Change mode/entries while a capture is active and during hands-free waiting.
	Confirm only the next command capture uses the new snapshot. Switching grammar
	off must use unrestricted Vosk again without restarting or reimporting models.
5. Test the Settings editor at compact/landscape sizes and invalid/oversized input.
	Measure native grammar construction latency and peak RAM on the 2 GB target.
	Verify model/grammar failures stop before capture without silent fallback.