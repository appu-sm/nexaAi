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
9. Confirm microphone controls are disabled with reasons and no recording indicator appears.
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

## Target acceptance matrix (all not yet verified)

| Area | Required conditions |
| --- | --- |
| Offline speech | Wi-Fi/mobile data disabled; installed offline assets |
| Recognition | Radio/music playing, stationary cabin noise, silence, partials, duplicate finals |
| Audio lifecycle | Permission denied/granted/revoked, TTS isolation, timeout/cancel |
| Volume | Each actual source, fixed-volume detection, OEM no-op verification |
| App/media | Each installed allowlisted app/session and each supported transport action |
| Radio | Only after verified adapter evidence; tune/seek and observed actual state |
| Startup | Cold/warm start, reboot, ignition cycle, sleep/wake, screen off |
| Overlay/service | Grant/deny/revoke, dismiss, battery optimization, no obstructed critical UI |
| Storage | Low space, corrupt/missing models, safe import/deletion |
| Resources | Long-run peak RAM/CPU, cold/warm latency, battery and navigation interference |
| Wake word | Real-engine false accepts/rejects, measured threshold, stop mechanism |

Do not proceed to later milestones until the current build and automated gates
pass. Target-dependent items remain explicitly unverified until tested there.