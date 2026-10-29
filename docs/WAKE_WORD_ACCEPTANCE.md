# Wake-Word Acceptance Report - Milestone 6

Report date: 2026-10-29. Decision: **PENDING physical acceptance**.
Diagnostics and host simulations are not proof that hands-free is suitable for
driving. Keep it optional, off by default, with notification/UI Stop and Home
push-to-talk available. No engine, model, threshold, radio key or command behavior
was replaced to obtain a better result. No physical measurements were made here.

## Device and Run Identity

| Field | Current evidence / value to fill for every run |
| --- | --- |
| Target | User supplied NWD_K2501 (T5), F52L_UPGZ_S212A70 |
| Android / RAM | User supplied Android 9-11, <=2 GB; actual API, ABI, MemTotal and MemAvailable not measured |
| Installation | Normal APK, min API 28, target/compile API 35; no root/system privilege |
| Build host | Linux, JDK 17; Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20 |
| APK / configuration | Record git revision, dirty patch, APK SHA-256, configuration export and local allowlist description |
| Model identity | Record SHA-256 and exact bytes of all installed model files; see Models for official sources |
| Run identity | Assign run ID, operator, UTC start/end, app elapsed epoch, cold/warm status |
| Acoustic environment | Stationary vehicle; mic location/type/route, distance/angle, speaker ID, radio level/source, windows, HVAC, ambient noise; SPL only if measured with a meter |
| Operating conditions | Screen brightness, foreground app, overlay/TTS/grammar flags, installed offline voice/engine, charger/ignition state, battery start/end, networking disabled |

Use the same device, brightness, speaker/mic placement, audio material, volume,
duration, model hashes and configuration for comparisons. Exported diagnostics
omit configuration, device identifiers, model hashes and UTC; attach this run
sheet separately. Do not mix elapsed clocks from different boots or processes.

## Implementation Audit

| Surface | Audited implementation |
| --- | --- |
| Wake runtime | sherpa-onnx Android `1.12.14`, ONNX Runtime backend; dedicated KWS, not continuous Vosk ASR |
| Wake model | English GigaSpeech Zipformer 3.3M, dated 2024-01-01; manually imported int8 encoder/decoder/joiner plus tokens/keywords; upstream files roughly 5 MiB, exact installed bytes **not measured** |
| Sources / licensing | [Models](MODELS.md#hey-nexa-keyword-model): official versioned release/archive, Apache-2.0 runtime/model; ONNX Runtime MIT. Preserve notices when distributing |
| Phrase | Default Hey Nexa; one configurable English phrase with matching BPE keyword ZIP; text edits do not train/regenerate tokens |
| Detector configuration | 16 kHz, 80 features, Zipformer2, one CPU thread; library defaults: four active paths, score 1.5, threshold 0.25, two trailing blanks. No supported sensitivity UI/keyword overrides; unchanged |
| Wake capture | VOICE_RECOGNITION AudioRecord, mono PCM16, reusable 1,600-sample (100 ms) frames; 3,200-byte PCM and 6,400-byte float arrays; normalize by 32768; decode while ready; no per-frame diagnostics |
| Wake buffer | max(Android minimum buffer x2, 6,400 bytes); rejects minimum outside 1..65,536 bytes; buffers are transient, not recordings |
| ASR | Vosk Android `0.3.47`, manually imported small English `0.15` (catalog ZIP 40 MB, Apache-2.0); optional grammar off by default; 16 kHz mono PCM16, buffer max(minimum x2, 16,000 bytes), 15 s capture / 16 s coordinator limit |
| Command input follow-up | Source VOICE_RECOGNITION (default) or MIC; software PCM gain 100% default, configurable 100-300%. Listening now waits for actual recorder readiness. Record source/gain in each future run; no physical improvement measurement yet |
| Ownership | One application coordinator and atomic session gate. Wake recorder closes before ASR opens; final-only dispatch; both recorders are off during awaited hands-free TTS |
| Readiness | Native KWS initialize completes before DETECTOR_READY; Vosk model is also preloaded before first wake wait; ASR_READY means AudioRecord entered recording, not first audible sample or proven OEM routing |
| Playback / rearm | Offline-eligible installed English Android voice; callback-based completion, 20 s deadline, 350 ms cooldown before fresh wake capture |
| Lifecycle | Explicit-start non-sticky microphone FGS, Stop cancels; no boot microphone or retry loop. Errors stop continuous mode except empty/timed-out ASR rearming. Overlay/boot cannot silently opt in |
| Prior evidence | 92 host tests at Milestone 5; no K2501 accuracy, latency, RAM, CPU, power or soak measurements. User-reported earlier Android 14 command fix is not wake-word acceptance |

The upstream size is storage information, not runtime RAM. Both Vosk and KWS
models reside together during hands-free; process memory must include both.
Native default values are tied to the pinned runtime; any future runtime/model/
threshold/grammar change requires a new run against both positives and negatives.

## Diagnostics Workflow

1. Install the debug APK, import both trusted model ZIPs and verify the saved
   phrase matches the keyword tokens. Configure TTS/grammar/overlay for this run.
2. Open Diagnostics, enable **Voice diagnostics**, then **Reset** immediately
   before the trial block. This does not start listening. The toggle is memory-only,
   off after process restart, and independent of configuration/debug logging.
3. Explicitly enable hands-free. Exercise the block. Tap **Snapshot** when needed;
   the snapshot includes current process and supported device resource fields.
   Navigation does not stop service-owned listening; it does cancel ordinary Home
   push-to-talk, so use ADB for uninterrupted push-to-talk phase measurements.
4. **Export JSON** opens Android's document picker. Choose a trusted local provider
   and confirm the destination. It writes the frozen preview, not a later live
   buffer. Snapshot again to refresh. Export a final snapshot after Stop.
5. Disable diagnostics for an A/B resource control. Reset deletes in-memory history
   and preview, not previously exported documents; delete those with the provider.

The shared synchronized collector records milliseconds from Android
`SystemClock.elapsedRealtime`, relative to reset. At most 512 events are retained;
typed event/code counters cover the whole reset window and `droppedEvents` reports
eviction. Export before 512 events (initially every 10 command cycles), after
errors and before process exit; save numbered blocks and sum counter *deltas*,
not cumulative totals. There are no timers, frame logs, persistent audio, transcript
fields, exception messages or automatic telemetry. No continuous resource sampler
is enabled. Export is bounded to 512 KiB. Snapshot/profiling overhead must be
recorded and compared with diagnostics off.

Events cover service creation/start/destruction and observed re-creations within
the reset window; session start/end, native detector readiness/detection, capture
start/closure, manual overlay trigger, ASR activation/readiness, final normalized
transcript availability, command dispatch/typed result, TTS Android start/done/stop,
actual wake-capture resumption, duplicate requests, cancellations, empty results,
timeouts and error categories (wake/ASR/TTS/service/permission).
`SERVICE_START` counts callbacks including Stop requests, not successful restarts.
`SERVICE_RECREATED` only identifies repeated creations in a surviving observed
window, not OS process-death counts. Error callbacks and session errors may both
describe one playback failure; deduplicate by trial, not by summing all error
events. A timeout may also have an empty result; these are not two failed trials.

Latency summaries include only complete retained pairs; missing pairs and dropped
events must be reported as gaps. They report count and median, with nearest-rank
p95 only at >=20 samples (still descriptive, not statistical certification).
Counters include events no longer retained. Reset/export blocks must not split a
trial; partial blocks cannot establish complete-trial latency. Result timings
include rejected/failed typed results: use raw event codes and dispatch presence
to report successful executions separately. Manual triggers are not positive wake
trials. Diagnostic counts alone cannot determine false accepts/rejects.

## Editable Acceptance Profile

These are **proposals for review**, not published engine benchmarks, universal
limits or measured K2501 capabilities. Edit and freeze this profile before testing;
attach changes and rationale. Never move a limit after seeing results without
labeling a new evaluation. A smaller actual RAM device may need stricter limits
or rejection of continuous mode. Unsupported/unobservable metrics stay pending
or require a documented reviewer-approved alternative, never an assumed pass.

```json
{
  "profile": "k2501-initial-review-v1",
  "wakeObservationWindowMs": 3000,
  "positiveTrialsPerCondition": 100,
  "maximumFalseRejectRate": 0.10,
  "negativeMinutesPerCategory": 30,
  "maximumFalseAcceptsPerHour": 1.0,
  "maximumUnintendedCommands": 0,
  "steadyStateTrials": 50,
  "coldStartTrials": 20,
  "p95ServiceCreateToDetectorReadyMs": 8000,
  "p95WakeEndToDetectionMs": 500,
  "p95WakeDetectionToAsrReadyMs": 1000,
  "p95CommandEndToFinalMs": 1500,
  "p95FinalToResultMs": 1000,
  "p95ResultToTtsStartMs": 1000,
  "p95WakeStartToActionableResultMs": 6000,
  "maximumIdleProcessPssMiB": 450,
  "maximumActiveProcessPssMiB": 600,
  "minimumDeviceMemAvailableMiB": 256,
  "maximumWarmIdlePssGrowthMiBOver8Hours": 32,
  "maximumIdleProcessCpuPercentOneCore": 20,
  "maximumActiveProcessCpuPercentOneCore": 150,
  "cpuSampleWindowSeconds": 60,
  "maximumAdditionalBatteryPercentagePointsOver2Hours": 5,
  "soakHours": 8,
  "commandCycles": 100,
  "startStopCycles": 50,
  "maximumCrashesAnrsOomsParallelCaptures": 0,
  "maximumDuplicateExecutions": 0,
  "maximumStopToCaptureClosedMs": 1000,
  "maximumManualRecoveryMs": 10000
}
```

## Detection Quality Protocol

Run only while safely stationary, with no safety-critical commands enabled.
Use an observer and paper/CSV trial ledger, not raw app audio recording. Fields:
run/block/trial IDs, UTC, event sequence interval, speaker, condition, positive/
negative, wake utterance start/end external marker, detected within window, number
of detections, duplicates, outcome, error and notes. Mark uncertain timing trials
invalid and repeat, but report their count/reasons; never discard failures silently.

Positive trials: wait for actual WAKE_CAPTURE_START/resumption, speak the configured
phrase once at the documented volume/distance, then wait 3 s after phrase completion.
One utterance is one trial; do not treat frames or echoes as extra trials. Record
miss if no detection within the window; late detections separately (still misses).
Pause until fully rearmed before the next trial; use a fixed harmless command such
as help after detection to finish each cycle. Commands are spoken only after ASR
capture begins. Separate misses from initialization errors/unavailable trials.

Collect >=100 positives per selected condition: normal voice at driver position,
near/far safe microphone distances (record metres), quiet versus representative
HVAC/radio/cabin noise; include multiple speaker voices when available and record
missing voice coverage. Stratify results by voice/distance/noise; do not hide a
poor condition in a pooled average. More trials are needed for reliability claims.

Negative trials: six separately labeled categories, >=30 minutes each: ordinary
conversation without the phrase; radio speech/music; app TTS responses including
similar words; silence/quiet noise; similar-sounding words (document the list);
safely reproducible cabin/HVAC noise. Fixed 60-second blocks define negative
trials (30 per category, 180 total, 3 hours). Record eligible wake-listening seconds,
all detector hits, blocks with >=1 hit and duplicate hits separately. Pause/reset
the exposure timer during ASR/TTS/cooldown; continue until eligible exposure is
reached. Do not count TTS-muted time as detector-negative exposure. Play app TTS
through normal result playback to check inhibition; log playback duration and
the post-playback rearm interval separately. Never bypass inhibition just to
increase the denominator. The actual configured wake phrase in radio/TTS is not
a false accept; isolate and label those intentional challenge trials separately.

Report raw positive N, detected D, misses N-D and FRR=(N-D)/N; negative blocks M,
blocks with hits B, false detection events F and eligible listening hours H;
report B/M and F/H, never F/M as a probability if multiple hits occur in a block.
Always show counts with rates and eligible exposure. Zero hits over a short test
is not proof of a low true rate. There are **zero** physical trials so far; all
rates are undefined, not 0%. Replay into a file/inference fake only tests decoding.
Loudspeaker replay is a separate acoustic replay run: document speaker, route,
level and distance and verify the real mic path. Neither replaces live-mic trials.
Tuning requires a new frozen profile and both positive/negative reruns.

## Latency Protocol

Collect 20 fresh-process cold starts and 50 steady-state successes per fixed
condition (also retain failures/timeouts). Explicitly disable/re-enable after
each cold process start; saved flags never start the microphone. Keep initialization
separate. Use diagnostics events for intervals below, plus synchronized external
observation for acoustic endpoints. A stopwatch/UI state is too coarse for 500 ms
claims: use a calibrated external timestamp/video method, without retaining raw
audio in NexaAI, and state resolution, clock alignment and observer uncertainty.
If reliable acoustic markers are unavailable, mark those rows not measured.

| Interval | Measurement / limitation |
| --- | --- |
| Service start to detector ready | SERVICE_CREATED -> DETECTOR_READY summary; excludes Android request-to-onCreate scheduling. Record explicit start request externally for full service-start latency; first WAKE_CAPTURE_START also shows time until actual listening after Vosk preload |
| Wake completion to detection | External wake-end marker -> native WAKE_DETECTED; no app acoustic endpoint is available |
| Detection to ASR ready | WAKE_DETECTED -> ASR_READY; includes wake recorder shutdown and fresh command recorder/grammar preparation; manual triggers excluded |
| Command completion to transcript | External command-end marker -> FINAL_TRANSCRIPT; includes endpointing and cleanup; native 15 s cap/coordinator 16 s timeout are not latency successes |
| Transcript to execution result | FINAL_TRANSCRIPT -> COMMAND_RESULT; distinguish parser rejection, failed dispatch and successful executed results by COMMAND_DISPATCH/code |
| Result to playback | COMMAND_RESULT -> Android callback TTS_START, not a queued speak request; TTS off/unavailable is N/A, not zero |
| End-to-end | External wake utterance START -> actionable COMMAND_RESULT; record wake duration, pause and fixed command wording/duration. Radio broadcast dispatch is only request-sent, not verified tuner completion |

Record successful N, failures, timeouts, missing markers, median and (N>=20) p95
per interval/condition, in ms. Publish raw sample rows alongside summaries. A
truncated buffer, cancelled trial, unsupported result or tiny sample cannot prove
the latency gate. Report diagnostic overhead with an on/off comparison.

## Resource Measurement Protocol

No device is attached for this report. Use supported Android APIs and ADB as the
normal shell user on the actual unit; never request root or grant privileged
permissions. Commands below are manual instructions, **not executed results**.
Record UTC before each dump, device elapsed uptime and sampling duration. Save
outputs on the trusted host; system logs can contain other apps' private data.

```sh
adb install -r app/build/outputs/apk/debug/NexaAI.debug.0.1.0.apk
adb shell getprop ro.product.model
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell cat /proc/meminfo
adb shell df -h /data
adb shell dumpsys package com.nexaai.assistant
adb shell run-as com.nexaai.assistant du -ak files/sherpa-kws-english
adb shell run-as com.nexaai.assistant sha256sum files/sherpa-kws-english/encoder.onnx files/sherpa-kws-english/decoder.onnx files/sherpa-kws-english/joiner.onnx files/sherpa-kws-english/tokens.txt files/sherpa-kws-english/keywords.txt
adb shell dumpsys meminfo com.nexaai.assistant
adb shell dumpsys cpuinfo
adb shell top -b -n 1
adb shell dumpsys battery
adb shell dumpsys thermalservice
adb shell dumpsys activity services com.nexaai.assistant
adb logcat -b crash -d
adb shell dumpsys activity lastanr
adb shell dumpsys procstats --hours 3
```

`run-as` needs this debuggable APK; a release APK need not allow it. Hash Vosk
files too, or retain the trusted source ZIP hash plus imported file inventory.
Firmware may omit tools/fields or restrict shell data; record denial/unavailable
as a gap. Do not change privileges to fill it. For timing/CPU traces use Android
Studio System Trace/CPU Profiler or `adb shell perfetto --help` where provided
(Android 10+ typical); Android 9 may expose only supported legacy System Tracing.
Select scheduling/CPU-frequency/process-memory sources available on that firmware,
save the trace with its config/duration and profiler overhead. A missing trace
source is not zero CPU. Poll from a host timer/profiler, not a busy loop.

Compare these states for equal 10-minute windows (three repeats, allow 2-minute
settling), plus cold initialization at 1-second profiler resolution and 8-hour
soak snapshots every 5 minutes. Capture transient ASR/TTS via profiler/ADB, not by
navigating away from Home mid push-to-talk:

1. NexaAI force-stopped, verified absent with `adb shell pidof com.nexaai.assistant`;
   collect device-wide baseline only, not a fictitious zero process snapshot.
2. App open, wake disabled: record whether a prior push-to-talk left Vosk cached;
   use a fresh-process baseline for comparison and separately label warm cache.
3. Both models loaded, actual wake capture waiting.
4. Wake detection and Vosk command processing.
5. Command dispatch and TTS playback.
6. Extended idle wake listening, with and without radio/navigation foreground.

The app's ResourceMetrics interface is injectable; Android implementation reports
own-process PSS via Debug.MemoryInfo, optional `/proc/self/status` VmRSS in KiB,
and cumulative process CPU milliseconds via Process.getElapsedCpuTime. Read two
snapshots >=60 s apart in a stable state: CPU percent of one core =
100 x delta(processCpuMs) / delta(elapsedRealtimeMs). Multi-thread processing can
exceed 100%; divide by actual logical CPU count only when explicitly labeling a
device-capacity-normalized value. Do not mix a new PID/reset or zero-length
interval. API/ADB CPU sampling windows differ; document the tool's interval.
Snapshots are not automatic peak capture. PSS apportions shared pages; RSS counts
resident shared pages fully. Neither equals total/free device RAM. Compare
initialization peak, steady medians, max sampled PSS/RSS and warm idle growth;
check MemAvailable and radio/navigation responsiveness together.

Battery percentage and temperature are **device-wide** sticky battery readings;
temperature is battery tenths of degrees C, not SoC/CPU temperature. Thermal status
is device-wide API 29+ severity, not degrees; API 28 is unavailable. Firmware may
expose no battery or a fixed value on this mains-powered head unit. Run matched
2-hour wake-on/off trials only with a real, stable unplugged battery and matched
conditions; record charging, percentage, duration and repeatability. Do not infer
mAh, watts or all-day endurance. If vehicle-powered, battery-percent comparison
is not applicable: leave power pending until a calibrated external supply meter
measures matched on/off energy with supply voltage/current and uncertainty.
No reliable watt/mAh source is available in this environment.

Crash/ANR/OOM evidence comes from supported crash buffers, lastanr, profiler and
procstats; on Android 11+ inspect supported process-exit history (`dumpsys activity
exit-info com.nexaai.assistant` if listed by `dumpsys activity help`). Permission
limits and ring-buffer losses must be noted. The in-memory collector cannot
survive process death, observe all OS kills, or prove absence of crashes from
missing events. Never add a deliberate OOM on the live head unit.

## Soak and Lifecycle Protocol

Run offline while parked, supervised, with safe volume and enabled commands.
Record run start/end, completed/missed/duplicate activations, executed successes,
typed failures, interruptions, crashes/ANRs/OOM, service creations, recovery times,
Stop-to-capture-closure times and exported blocks. Missing exports after death
are gaps; retain external OS observations. Stage tests rather than causing unsafe
power or microphone changes while moving.

1. Fresh install on a dedicated test device (clearing app data deletes models):
   wake/overlay/startup all off. Import models without listening. Enable overlay
   and startup separately; verify neither starts a recorder. Saved/imported wake
   settings and relaunch/reboot must not automatically record.
2. Idle wake listen for 8 hours, regular snapshots and matched resource baseline;
   record radio/navigation responsiveness and missed OS uptime periods.
3. Perform 100 wake/harmless-command cycles, alternating TTS on/off and representative
   commands; count one execution per final, no dispatch from partials, no self-wake
   during playback, and real capture resumption only after completion/cooldown.
4. Perform 50 start/stop cycles, including notification Stop, app switch/Stop and
   overlay command-active Stop. Externally mark each Stop and verify recorder
   closure <=1 s; no continuing mic use, parallel capturers or native model growth.
   Pair wake/ASR capture start/closure events, confirm no overlapping intervals,
   and corroborate with available Android audio diagnostics/profiler. Source-close
   events represent release boundaries; OS routing still requires observation.
5. Interrupt microphone via another permitted audio app; test temporary failure,
   denied/revoked permission, missing/corrupt model on a test install and blocked
   FGS starts. Expect visible stop/error, no retry storm. Restore access/model,
   explicitly retry; measure recovery and confirm Home push-to-talk still works.
6. Navigate/recreate Activity, drag/tap/dismiss overlay, revoke overlay permission,
   screen off/on for 20 transitions, suspend/resume and supervised ignition cycles
   only if safe. Record service/audio continuity, silence, errors and recovery.
7. Interrupt/stop the service or force-stop app, then reopen manually. Reboot/unlock
   once with auto-start overlay off and once on: at most permitted overlay restores;
   microphone must stay off until explicit enable. Verify manual recovery offline.
8. Re-run command, overlay/startup and frozen radio 5/6/46/61 mappings. 26/27 remain
   frequency steps, never search. Real receiver/tuner behavior needs independent
   target evidence; request sent is not verified tuning.

Do not infer screen-off, ignition or vendor power-management support from a host
test. Any failed/inconclusive gate leaves hands-free optional and unaccepted;
preserve manual Stop and push-to-talk, document conditions and propose a new run.

## Acceptance Matrix

All physical rows have zero samples so far. Review/freeze the profile above and
fill measured units, denominators, timestamps and attachments before deciding.

| Category | Proposed target / rationale | Method and conditions | Actual result | Status / required follow-up |
| --- | --- | --- | --- | --- |
| Detection quality | FRR <=10% per cell; FA <=1/h; zero unintended commands. Initial usability/safety budget, not statistical reliability | >=100 positives/cell; six 30-min eligible negative categories; live stationary mic, fixed profile | Not yet measured; positive=0, detected=0, negative=0, false detections=0, hours=0; rates undefined | Pending: complete live/replay-separated ledger, increase exposure for confidence |
| Latency | Profile p95: cold readiness 8 s, wake end .5 s, handoff 1 s, command end 1.5 s, dispatch 1 s, TTS 1 s, total 6 s. Interaction budgets; cold target leaves margin before existing 10 s startup watchdog | 20 cold/50 warm per condition; event pairs plus external acoustic markers; failures separate | Not yet measured; N=0 for all seven intervals | Pending: verify marker alignment, review cold Vosk preload and fixed command duration |
| Memory | Idle PSS <=450 MiB, active <=600 MiB, MemAvailable >=256 MiB, warm idle growth <=32 MiB/8 h. Starting budget for cached Vosk+KWS within <=2 GB, not proven safe | All six states; 3x10 min, startup peaks, 8 h drift, same foreground apps | Not yet measured; N=0 | Pending: actual RAM/headroom may require lower budget or rejecting continuous mode |
| CPU | Idle <=20%, active <=150% of one core over 60 s; no sustained severe thermal state. One-thread KWS should leave headroom for navigation, multi-thread ASR separately | CPU deltas/profiler, fixed windows, diagnostics on/off A/B, thermal where available | Not yet measured; intervals=0 | Pending: report tool window/core normalization, responsiveness and unavailable thermal fields |
| Power impact | Battery trial: <=5 additional percentage points over 2 h versus wake-off, only if real unplugged battery. Initial comparison budget, not energy estimate | Matched repeated 2 h trials; vehicle supply requires calibrated external meter and reviewer-defined energy budget | Not yet measured; durations=0 | Pending: determine battery applicability; approve meter-based limit if mains-powered |
| Stability | 8 h, 100 cycles, 50 start/stops; zero crashes/ANRs/OOM/parallel captures/duplicate executions. Catch leaks/races, not prove lifetime reliability | Soak/lifecycle ledger + events + OS diagnostics, offline, TTS/radio/navigation | Not yet measured; cycles=0 | Pending: interruption, navigation, screen, firmware tests and OS evidence |
| Manual stop/recovery | Capture closed <=1 s each Stop; explicit recovery <=10 s, no surprise resume. Safety escape and bounded usability budget | External Stop markers + release events + OS audio evidence; restore permission/model then manual retry | Not yet measured; trials=0 | Pending: test notification/app/overlay stop and Home fallback |
| Offline | No network dependence for wake/ASR or chosen TTS; no cloud fallback | Both models and installed voice; Wi-Fi/mobile data disabled for all live blocks | Not yet measured; physical trials=0 | Pending: perform actual offline recognition and playback, not merely manifest inspection |
| Regression | All host tests, debug assemble and lint error-free; radio/overlay/startup paths preserved | JUnit/Robolectric/fake engines on Linux; no physical audio | See Host Validation below | Host gate recorded separately; physical commands, offline audio and firmware still pending |

## Host Validation

Subsequent command-audio follow-up on 2026-10-29: 103 host tests passed, zero
failures/errors/skips; debug build and lint succeeded in 1m 7s (0 lint errors,
26 warnings). Listening now waits for AudioRecord readiness; optional source/gain
controls affect command ASR only. This updates the debug artifact hash to
`4bf34c9dbf6b4e8594b5acac621e9244169557f8bb0f2c5f47e963648395d609`, superseding
the earlier Milestone 6 artifact below. See TEST_PLAN for follow-up regressions
and user-reported accuracy symptoms. All acoustic/resource/firmware decisions
remain pending; no improved recognition result has been measured physically.

Diagnostics foundation: 3 tests passed before integration (`BUILD SUCCESSFUL in
1m 31s`). Focused final workflow: **39 tests passed**, `BUILD SUCCESSFUL in 41s`,
27 tasks (2 executed, 25 up-to-date). Tests use fake engines/clocks/resources and
Robolectric API 28, not live speech. Compact UI test initially exposed off-screen
test navigation and startup-settling assumptions; the helper now scrolls tabs
and the test waits for startup readiness. No physical result is inferred.

Final full command, completed before 2026-10-29T11:54:23Z:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Actual output: **BUILD SUCCESSFUL in 1m 26s**, 54 actionable tasks (11 executed,
43 up-to-date). Structured JUnit XML: **99 tests in 17 suites, zero failures,
errors or skips**. Android lint XML: **0 errors, 26 warnings** (same warning count
as the prior Milestone 5 baseline). No new dependency or permission was added.
The seven added tests cover bounded/resettable collection, clock/event ordering,
latency summaries, fake resource injection, final-only dispatch/cancellation/
duplicates, detector failure with push-to-talk recovery and compact diagnostics
UI. Existing detector and document-flow tests additionally verify native-boundary
instrumentation and bounded diagnostic SAF writes with simulated inputs.

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, **199,985,170 bytes**
(approximately 190.72 MiB; APK storage size, not RAM).
SHA-256: `9fe21acf489772dc09fbf7a3990c4db0a56aa53d02e58d5c04fd4bca7fcfd38e`.
Build/test reports live under `app/build/reports/`; rerunning a build may change
the debug APK hash. VS Code still reports stale nullable-Recognizer diagnostics
at mismatched Vosk source locations despite successful Gradle Kotlin compilation,
unit tests and lint. No source/dependency workaround was added for that editor
discrepancy. No instrumented emulator, physical model, microphone or head-unit
test ran. Host pass does not close any physical acceptance gate.

## Decision and Follow-Up

**No device acceptance yet.** Highest risks: idle Vosk+KWS memory, false accepts
from radio/cabin audio, command endpointing, firmware microphone contention and
screen-off service survival. First obtain the exact API/ABI/RAM/model hashes and
one short stationary smoke test; then freeze/review budgets and execute quality,
latency, resource and soak blocks. Do not tune thresholds silently, auto-enable
hands-free, disable existing manual controls or replace the engine without failed
acceptance evidence. If fails/inconclusive, retain the optional mode with explicit
limitations and default to push-to-talk.