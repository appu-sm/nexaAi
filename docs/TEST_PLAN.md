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

## Milestone 5 Overlay and Startup

### Latest Speech-Bubble Results (2026-10-29)

Chest-circle/grey-background correction: the full command was rerun successfully
with the same 92-test and lint counts below. The artwork test now requires changed
pixels to stay inside the measured circle (center 50%, 70.5%; radius 5.5%), checks
that the pale light center changes in all four variants, and preserves every
outside pixel/alpha. The bubble test checks actual drawable fill colors (#424242)
for both text backing and pointer, catching the prior unset-fill name collision.
Physical visual verification remains pending; compare chest-circle edges against
the source image and confirm readable white text on grey over other apps.

Full test/build/lint command passed: **92 tests, zero failures/errors/skips; debug
APK assembled (~191 MiB); lint 0 errors/26 warnings**. Edited runtime files have
no editor diagnostics. This supersedes previous host counts. Nine additional
warnings concern physical left/right gravity in bubble placement, intentionally
computed after resolving the icon's logical RTL coordinates; none are suppressed.
No new dependency, permission, microphone path or command engine was introduced.

Tests verify saturated green only inside the blue body-indicator region; live
partial/final transcript and result/error mapping; clearing text on new capture;
retaining observed text during processing/TTS and completed wake cycles; two
bounded windows only while text is visible; left/right edge placement, unchanged
icon size/coordinates, long output and compact portrait constraints; 12-second
expiry without resurrection on repeated updates; and teardown cancelling expiry.
All existing radio/command/speech/service/permission/startup regressions pass.

Physical acceptance additions (all **not run**, no target attached/tested):

1. Compare the body light against the previous pale green; only reported wake or
	command capture should use the saturated green. Confirm all other artwork and
	idle/processing/speaking colors are unchanged.
2. Speak a configured command through overlay tap and hands-free. Observe partial
	and final `You:` text, then the actual `NexaAI:` result beside the robot; ensure
	partials never execute. Test success, unsupported, rejected and error messages.
3. Drag to each display edge, rotate/change font scale where supported, and compare
	radio/navigation controls. Bubble should point to the icon, choose available
	side space, remain on-screen, wrap/scroll long output and not resize/move the
	icon. Verify tap/Stop, hold/open and drag actions retain their prior behavior.
4. Tap bubble text to dismiss; wait 12 seconds without new text to check expiry.
	Repeated service/settings/display updates must not resurrect identical text.
	A new command/text change may show another bubble. Disable overlay, revoke
	permission and destroy/restart the service: no orphaned icon, bubble or timer.
5. Confirm text is visible to anyone looking at the screen, but no transcript or
	result is logged, saved or uploaded. Disable overlay when this display is unwanted.

### Latest Tap-to-Command Results (2026-10-29)

Final full command `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
--console=plain -Pkotlin.compiler.execution.strategy=in-process` passed:
**89 tests, zero failures/errors/skips; debug APK assembled (~191 MiB); lint 0
errors/17 warnings**. Warnings include pinned dependency/target/backup notices,
three stopService Intent identity false positives, three bitmap KTX style suggestions
and native touch-to-click delegation. No suppression or new dependency was added.
Edited runtime files have no editor diagnostics. This supersedes earlier host counts.

Additional tests cover: one command without wake, service-owned capture surviving
activity lifecycle loss, duplicate starts, cancellation blocking late dispatch,
manual wake interruption closing capture before ASR and rearming afterward,
overlay actions never opening an activity on tap, permission/background-start
failures, repeated pending taps and a missing-service watchdog without retries or
wake-setting writes. Bitmap tests cover localized green body light, unchanged
surrounding artwork/alpha and cached normal/pressed variants. Low-alpha hue rounding
is accounted for while visible-pixel hue is checked. Existing Milestones 0-5 tests
all pass; real model/recording/radio/firmware behavior is not established by fakes.

Latest physical acceptance additions, all **not run**:

1. Grant mic from Home, import Vosk, leave hands-free off, then navigate to another
	app and tap the overlay. Expect no NexaAI activity, a microphone foreground
	notification, green body light during command listening, one final validated
	command/action and return to idle blue. No wake phrase should be needed.
2. Tap again during initialization/capture/processing and use notification Stop;
	check prompt capture release and no late dispatch. Repeated taps must not
	create parallel recorders or service starts. Drag must neither capture nor stop.
3. Explicitly enable hands-free with both models, leave NexaAI, and check green
	wake waiting, wake/command/TTS cycles and rearming. Tap while waiting to bypass
	wake for one command, then verify automatic return to wake waiting. Stop the
	continuous session using notification/in-app Stop; command-active tap also stops.
4. Check normal and pressed artwork: only the small body blue light becomes light
	green while listening, not the face/arms/background. Loading, processing, speech,
	idle and unavailable remain blue; compare with real capture/notification state.
5. Deny/revoke mic, remove models, disable assistant and exercise rejected background
	mic/FGS starts on actual APIs 28-30 and newer if available. Expect an error without
	activity launch, no stuck green state/retry loop, and hold-to-open Home fallback.
	Overlay grant alone must not bypass mic restrictions. Repeat screen-off/sleep/
	ignition and radio/navigation coexistence checks; record actual results only.

The earlier idle-tap-opens-Home procedure below describes the superseded icon-only
revision; use these latest tap/hold/color expectations for current APK acceptance.

Host evidence, 2026-10-29: the complete
`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
-Pkotlin.compiler.execution.strategy=in-process` run passed **82 tests, zero
failures/errors/skips**, debug APK assembled (~191 MiB), and lint had zero errors.
After the final logical-edge/accessibility drag adjustment, overlay tests, APK and
lint were rerun successfully: **0 lint errors, 12 warnings** (pinned dependency/
target/backup notices and two `stopService(Intent)` ImplicitSamInstance false
positives; no suppressions added). No real device, microphone, keyword/model weights,
boot broadcast or Android special-access UI is required by these tests.

New/updated coverage:

- ConfigurationTest: overlay/auto-start/wake defaults, independent enabled flags,
  strict geometry types/ranges, round-trip and missing-field older-file defaults.
- OverlayPolicyTest: fail-closed startup gates, exact-once add/update/remove,
  denial/revocation/disable/destruction, failed attachment without self-retry,
  real voice-state presentation and interface-only callbacks (no capture/dispatch).
- OverlayRuntimeTest: denied/revoked access, no implicit Settings launch, deduplicated
  start, thrown service failure and startup watchdog without retries, supported
  boot-only overlay requests, real Robolectric Android window/status/click/Stop
  update and removal. Injected boundaries prevent hardware dependency.
- ShellUiTest: enabling overlay with missing permission safely persists the
  preference, auto-start remains separate, hands-free stays off and push-to-talk
  remains enabled. Only tapping the permission button launches the package Settings
  intent; returning denied does not launch another prompt. Disabling remains usable.
- All existing parser, controller/radio protocol, configuration/import/persistence,
  offline speech, wake/audio handoff, manual stop, TTS isolation and compact/
  landscape UI tests passed in the full run. No core algorithm/mapping was changed.

No new dependency. Changed implementation inventory: app Gradle artwork resource
task and manifest; MainActivity/NexaAiApplication wiring; Configuration/Codec;
AssistantViewModel/NexaApp; HandsFreeService Stop error handling; new
overlay/{OverlayPolicy,OverlayControl,AndroidOverlayWindow,OverlayService,BootReceiver}.
Tests: ConfigurationTest, ShellUiTest and new OverlayPolicyTest/OverlayRuntimeTest.
Documentation: README, AGENT, CHANGELOG, Architecture, Device Compatibility,
Privacy and Permissions, and this test plan. Existing user `images/` assets are
read by the build, not rewritten. No source audio/transcript logging was introduced.

### Stationary Target Procedure

Record date, operator, installed APK hash/version, actual API/build/firmware, ABI,
available RAM/storage, notification policy and battery/OEM restrictions for every
run. Target description NWD_K2501/T5 F52L_UPGZ_S212A70 is supplied, not independently
verified here. Test only while stationary. Observe real audio/tuner state separately
from requests and saved preferences. Do not label broadcast registration as a pass.

1. Fresh install/open: confirm overlay, auto-start and wake word all off. Upgrade
	over a configured build separately; confirm settings, aliases and models survive.
	With networking disabled, verify typed Help, push-to-talk and existing radio
	requests before and after each failure test. Record actual tuner effects.
2. Enable overlay without granting access: no crash, Settings auto-launch or audio.
	Tap Overlay permission, deny, return, navigate/recreate the activity and wait:
	status remains missing, with no repeated prompt. Tap again, grant and return:
	one window and its notification appear; microphone remains off.
3. Disable/re-enable repeatedly (also via notification); count one window only,
	no invisible touch-blocking area and no retained window after disable. Keep
	hands-free independently on/off to confirm disabling overlay does not change it.
4. Drag while stationary to avoid navigation/radio controls, test edges, saved
	position, size and opacity; rotate/recreate, change display/font scale if supported,
	and switch NexaAI/radio/navigation/other apps. Check readable status, >=56 dp
	touch targets, no keyboard focus stealing and no critical obstruction.
5. From idle tap the image: existing Home opens, capture does not start until its
	explicit mic action. Import required assets and explicitly enable hands-free;
	compare overlay with actual initializing/wake-waiting/command/TTS/error states.
	Only actual wake waiting may show Wake active artwork. Stop during wake,
	capture, playback and pending initialization: microphone must release promptly,
	no delayed command, then push-to-talk must still work. Do not infer silence
	from artwork alone; observe Android mic state and command outcomes.
6. Revoke overlay access while idle and during dragging/listening. Window must
	disappear without crash or duplicate/invisible remnants; speech is independently
	controllable in app/notification. Regrant and retry manually. Revoke microphone
	permission separately and verify visible failure and typed/manual fallback.
7. With auto-start off and overlay previously enabled, perform cold/warm reboot;
	do not manually open NexaAI until checking whether any service/window started.
	Expect no NexaAI startup request. Repeat with auto-start and overlay enabled,
	permitted and assistant enabled: check before/after first unlock and notification/
	window appearance. No activity/radio launch or microphone capture is expected,
	even if wake preference was previously enabled. Repeat with overlay off, grant
	revoked and assistant off: no startup request. Record delay or non-delivery.
8. Test screen off/on, supported sleep/wake, ignition-off/on and prolonged radio/
	navigation operation with each service separately and together. Record service
	kills/suspends, actual mic ownership, notification/window status, latency,
	RAM/CPU/power/thermal behavior. No wake lock or guaranteed ignition recovery exists.
9. Force-stop NexaAI, reboot, then manually reopen. No auto-start is promised while
	force-stopped. Test an APK update with configured overlay, including permission
	denied. Package update may request only the configured overlay, never listening.
10. Where feasible trigger denied foreground start/firmware service termination.
	 Check no repeated restart, truthful stopped/unavailable state, manual launcher
	 and Refresh / retry recovery, push-to-talk after restoring mic access, and all
	 existing radio controls. Optional Battery settings may help but cannot prove
	 exemption. Do not grant root or change firmware to obtain a pass.

### Actual Physical Results

Floating-icon follow-up (2026-10-29): the overlay is now a transparent icon-sized
window rather than a strip. Host window tests cover square dimensions, no padding,
idle tap/open, active tap/Stop, hold/open and drag without either action, including
clamped saved position and cleanup. For procedure steps 4-5, check full text in
NexaAI/accessibility descriptions and active/offline artwork on the icon; tap to
stop and hold to open. Verify only the icon's square touch area blocks underlying
apps, with no trailing invisible strip. Physical validation is still not run.
The full regression/build/lint command passed again: **83 tests, zero failures or
errors; debug APK assembled; lint 0 errors/13 warnings**. The additional
ClickableViewAccessibility warning concerns the touch listener's delegation of
normal tap/hold events to native ImageButton.onTouchEvent (which invokes the
existing performClick/long-click handlers); drags cancel native clicks. No warning
was suppressed. VS Code's known stale test-library classpath diagnostics persist
despite successful Gradle compilation and test execution.

No physical K2501 was available or tested for Milestone 5. All rows below are
**not run**, not simulated successes. Fill observations/evidence only after testing.

| Case | Actual result | Evidence |
| --- | --- | --- |
| Fresh/upgrade defaults and preserved settings | Not run | No target test |
| Overlay grant/deny/return/revoke/regrant | Not run | No target test |
| Exact-once enable/disable, navigation and unobstructed dragging | Not run | No target test |
| Real listening status, Stop and independent settings | Not run | No target audio test |
| Auto-start off cold/warm reboot | Not run | No target boot test |
| Auto-start on after first unlock; no microphone/radio launch | Not run | No target boot test |
| Revoked permissions/disabled overlay or assistant at boot | Not run | No target test |
| Screen-off/on and sleep/wake | Not run | No firmware power test |
| Ignition-off/on and firmware kills/restrictions | Not run | No firmware power test |
| Force-stop, package update and manual recovery | Not run | No target test |
| Failed service start leaves push-to-talk/radio usable | Not run | Host fakes only |
| <=2 GB long-run resources and native audio coexistence | Not run | No target measurements |