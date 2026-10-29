# Device Compatibility

## Quiet Command Audio Follow-Up (2026-10-29)

User reports a missing first word (`open clock` -> `clock`) and needing loud/near
speech; exact device/mode/source/grammar/noise conditions are unspecified. Fixed
an application readiness mismatch: Listening now waits until command AudioRecord
is recording. Added optional command source (VOICE_RECOGNITION or MIC) and
100-300% saturating software gain, retaining old defaults. These are not verified
hardware sensitivity, external-mic selection or far-field performance. Actual OEM
route and source processing remain device dependent; wake capture is unchanged.
All 103 host tests, debug assemble and lint pass (0 errors/26 warnings); no
physical retest or target accuracy improvement is claimed. Reproduction and
comparison steps are in README and TEST_PLAN.

## Milestone 6 Acceptance Status (2026-10-29)

All physical K2501 wake-word gates are **pending / Not yet measured**: detection
quality, acoustic latency, RAM/CPU/power, soak, manual Stop/recovery, offline speech
and firmware screen/ignition behavior. No device attachment check or physical run
was performed in this milestone; prior baseline had no attached device. Normal
APK privileges remain unchanged. The existing engine/threshold/radio mappings
are preserved. Host diagnostics use simulated engines; they do not establish
hardware support. The [acceptance report](WAKE_WORD_ACCEPTANCE.md) provides the
audit, configurable provisional budgets, exact non-root profiling instructions,
sample-count ledgers and pending decisions. Supported in-app resource snapshots
must be taken on the head unit, not interpreted as host measurements. Battery
and thermal fields may be absent on this vehicle-powered firmware/API 28.

## Evidence baseline

Repository inspection: instructions only, no existing Android project or tests.
Build host: Linux, OpenJDK 17.0.20.1; SDK platforms 30, 31, 33-36 and 37.0;
build tools 30.0.3, 34-36. `adb devices -l` returned no attached devices.

The supplied target is NWD_K2501 (T5), firmware F52L_UPGZ_S212A70,
reportedly Android 9-11 and <=2 GB RAM. These are user-supplied details,
not independently measured device findings.

| Item | Status | Evidence needed |
| --- | --- | --- |
| Android build/API | Unverified | `adb shell getprop ro.build.version.sdk`, build fingerprint |
| CPU ABI | Unverified | `adb shell getprop ro.product.cpu.abilist` |
| Actual/available RAM | Unverified | `adb shell cat /proc/meminfo` |
| Available app storage | Unverified | `adb shell df -h /data` |
| Radio interface | User-supplied verified action/component/key mapping; NexaAI adapter implemented | Installed APK receiver permissions/delivery and end-to-end K2501 results |
| Volume/media/app launch | Milestone 2 implemented; target unverified | Actual source/app/session tests on head unit |
| Speech/TTS | Milestone 3 code implemented; target unverified | Real model/voice, airplane-mode, ABI, latency, RAM and lifecycle tests |
| Overlay/boot | Milestone 5 implemented; defaults off; target unverified | Real grant/revoke, post-unlock boot, screen/ignition/sleep and service survival tests |
| Hands-free service/wake word | Implemented; explicitly enabled only, models required; target unverified | Real keyword accuracy, resource, permission and sleep tests |
| Local LLM | Not implemented; off | Runtime and target measurements |

No NexaAI hardware-dependent capability has been verified. The radio protocol
mapping is user-supplied target evidence; app-level receiver access remains open.
Background capture requires explicit hands-free opt-in, runtime microphone
permission and a microphone foreground service. Optional notification-listener access
must be explicitly granted for media sessions.

## Project baseline

Package/namespace: `com.nexaai.assistant`; minimum API 28; compile/target API 35.
AGP 8.9.2, Gradle 8.11.1, Kotlin/Compose plugin 2.1.20, Compose BOM 2025.04.01.
Vosk Android `0.3.47` is included; no speech model is bundled. APK inspection found AndroidX
`libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` for arm64-v8a,
armeabi-v7a, x86 and x86_64 (60,292 bytes uncompressed in total). Presence of an
ABI in the APK is not proof of compatibility with the target firmware.

## Host-verified implementation

- Milestone 0 baseline debug build and baseline unit test passed before Milestone 1.
- Milestone 1 debug build, host configuration/Compose tests and Android lint pass.
- Host UI tests use Robolectric API 28 in landscape and compact portrait layouts.
- Configuration survives closing/reopening DataStore; imports require preview and
	confirmation; unsupported feature flags and alias collisions are rejected.
- Initial lint detected an API 33-only stream call; replaced with an API 28-safe
	bounded read and reran successfully. No API compatibility error was suppressed.
- No recording, radio, media, overlay or boot result has been simulated.
- Final debug APK: approximately 57 MiB at
	`app/build/outputs/apk/debug/app-debug.apk`. This is file size, not RAM usage;
	startup/runtime RAM and APK size optimization remain unmeasured/deferred.
- Milestone 1 `aapt dump permissions` listed only AndroidX's app-private
	`com.nexaai.assistant.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no internet,
	microphone, storage, overlay or boot permission is included.

## Milestone 2 host evidence (2026-10-23)

- Baseline Milestone 0/1 tests/build passed before changes.
- Final `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`
	passed: 29 tests, zero failed/errored/skipped; lint zero errors and 10 existing
	dependency/target warnings. Includes 4 Robolectric API 28 Compose tests.
- Fake-device tests verify parsing, collisions, live configuration, parameters,
	cancellation/single-use tokens, permission failures, controller errors/no-ops,
	allowlist persistence/corruption, removed apps, missing/multiple media sessions
	and unsupported actions/radio. They do not verify OEM behavior.
- Final APK remains approximately 57 MiB; this is not runtime RAM evidence.
- Final `aapt dump permissions`: normal `MODIFY_AUDIO_SETTINGS` plus AndroidX's
	private receiver permission. No internet, microphone, broad storage, overlay,
	boot or foreground-service permission. Manifest adds launcher/home intent
	queries, not `QUERY_ALL_PACKAGES`. The listener service is system-protected by
	`BIND_NOTIFICATION_LISTENER_SERVICE`, not a grant the APK can give itself.

## Android 14 Interpret report (2026-10-24)

A user reported a crash on Interpret with Execute remaining disabled. Inspection
identified Android-incompatible placeholder patterns in parsing and validation:
their unescaped closing brace compiles on the host JVM but fails native ICU 74
with status 66305. Both now share explicitly escaped `\{[^{}]+\}`; that exact
source expression compiles under native ICU with status 0. Host tests/build/lint
pass after the fix (30 tests, zero lint errors, 10 existing warnings).

This reproduces the regex defect on the host, not the full reported device crash.
On 2026-10-24, the user confirmed the app was verified, working fine and all
reported issues were fixed after the correction. This records a successful
user-reported retest on the previously reported Android 14 environment, not an
independently observed device test. No device log, model/build identifier or
per-control test results were supplied. No permission grant is required for Help.
Existing K2501 hardware acceptance gates remain open.

| Control | Implemented behavior | Still requires target evidence |
| --- | --- | --- |
| Volume | Fixed-volume check, music-stream mapping, Android state readback | Audible effect for radio/navigation/music; OEM mute/range/no-op |
| App/home | User-selected installed allowlist and launch intent; home request | Firmware package visibility and actual app/home behavior |
| Media | Optional listener access, single session, advertised actions only | Grant/revoke, intended players, actual playback/track changes |
| Radio | Request adapter implemented; target behavior unverified | Installed APK permission/delivery, regional band/step, tune/seek/readback |
| Push-to-talk | Milestone 3 code implemented; model required | Microphone, offline model, lifecycle and performance on K2501 |

No device attachment or K2501 test was performed for this milestone. Runtime
Diagnostics reads this running device's facts and API capability checks; it does
not establish target compatibility. Host tests do not prove audible changes,
launches, playback, airplane-mode operation or target memory/latency. Use Home
commands while stationary to record outcomes per source/player. See `TEST_PLAN.md`.

## Milestone 3 host evidence (2026-10-24)

- Pinned `com.alphacephei:vosk-android:0.3.47`; no model is bundled. Vosk lists
	`vosk-model-small-en-us-0.15` as 40 MB and Apache-2.0. The user imports the ZIP
	into private app storage. Target ABI remains unknown; artifact packaging alone
	does not establish native compatibility.
- Fake-engine/controller/TTS tests cover final-only dispatch, cancellation,
	duplicate prevention, parser/validator rejection, radio dispatcher routing,
	truthful result speech and cleanup. A malformed ZIP test covers model-store
	rejection. Tests do not use a real model, microphone, TTS voice or radio hardware.
- Capture uses 16 kHz mono PCM16, a 15-second limit and runtime `RECORD_AUDIO`
	permission requested only after mic tap. No wake word, boot listener or
	foreground service is implemented.
- Offline recognition, offline TTS, native ABI loading, accuracy, latency, peak
	RAM and K2501 radio interoperability remain unverified.
- The debug APK is about 93 MiB and packages Vosk native libraries for arm64-v8a,
	armeabi-v7a, x86 and x86_64. This is packaging evidence only; the target ABI is
	still unknown and the debug size is not a runtime RAM measurement.

## Milestone 4 host evidence (2026-10-26)

- Integrated the user-supplied `com.nwd.radio` activity and verified broadcasts
	into the existing command parser, validator and dispatcher. Frequency input is
	bounded to 87.5-108.0 MHz and converted to 10 kHz units using decimal
	`HALF_UP` rounding. This range is configured by NexaAI and is not a measured
	regional tuner plan.
- Existing schema-1 preferences with the exact prior 19-command catalogue gain
	the five radio defaults without losing saved settings, aliases or enablement;
	partial/malformed catalogues still fail closed.
- Host tests verify parser phrases, exact activity component, package-scoped
	broadcast actions/extras, key values 5/6/46/61, mute booleans, conversion and
	local launch failure handling. Build/test/lint results are recorded in
	`TEST_PLAN.md`.
- No K2501 was attached. APK broadcast permission, receiver delivery, actual
	tuning/seeking/muting/AMS/INTRO behavior, and foreground app transition remain
	unverified. A broadcast send is not treated as completion feedback.

## Requested Hands-Free Extension (2026-10-26)

Real sherpa-onnx Android `1.12.14` KWS is integrated behind `WakeWordDetector`,
with the user-imported GigaSpeech English 3.3M model and Hey Nexa BPE tokens.
The release AAR digest matches upstream metadata; it packages arm64-v8a,
armeabi-v7a, x86 and x86_64. This is packaging evidence, not a native loading or
CPU-instruction compatibility test on the NWD_K2501.

API 28/29 uses a normal foreground notification; API 30+ specifies microphone
service type. Target 35 declares `FOREGROUND_SERVICE_MICROPHONE` and enforces
explicit foreground starts with runtime `RECORD_AUDIO`. Android 13+ notification
permission is requested on opt-in; denial does not exempt the service from
Android task-manager visibility or in-app Stop. There is no boot start, automatic
restart, overlay dependency, root privilege, wake lock or battery-policy bypass.

Host fake tests verify handoffs, final-only commands, TTS isolation, timeout,
cleanup and permission/start failure. They do not establish native model validity,
actual microphone ownership under OEM audio routing, offline TTS quality or awake
time. Firmware F52L_UPGZ_S212A70 may kill/suspend a normal APK during screen-off,
sleep or ignition cycles. If persistent listening is blocked, keep hands-free off
and use visible push-to-talk. Screen-off reliability, accuracy, RAM/CPU/power and
radio/navigation coexistence must be measured with both models on the actual unit.
No physical device was tested for this extension; the linked private ChatGPT
conversation required login and could not be used as additional evidence.

Final host gates: 60 tests passed (none failed/skipped), debug APK assembled,
lint zero errors/11 warnings. The multi-ABI debug APK is about 191 MiB without
models. This is not a RAM/power measurement; runtime suitability remains open.

## Milestone 5 Host Evidence (2026-10-29)

Optional overlay and best-effort, after-unlock startup are implemented for the
unchanged min API 28 / target API 35 normal APK. Host gates pass: 92 tests, no
failures/errors/skips; debug APK assembled (~191 MiB, not RAM evidence); lint has
zero errors. Final warning count is recorded in the test plan. No new dependencies,
privileges, radio mappings, speech engine or wake detector changes were introduced.

`SYSTEM_ALERT_WINDOW` needs an explicit Android Settings grant. The floating window
has saved position/appearance and routes Open/Stop to existing application actions.
The non-recording overlay service uses a foreground notification; API 34+ declares
the normal special-use FGS permission/type. Hands-free remains its separate, explicit
microphone service, sharing the existing single speech coordinator. Boot/update
only requests an enabled overlay with valid settings and permission after unlock;
it never launches radio/UI or starts/resumes a microphone. No direct boot is claimed.

All target behavior remains **not tested** on NWD_K2501/T5 F52L_UPGZ_S212A70:
special-access screen availability, grant/revoke delivery, unobstructed placement,
foreground notification policy, boot broadcast delivery, native audio/status timing,
screen-off/on, sleep, ignition-off/on, and actual RAM/CPU/service survival. Host
Robolectric/fake tests are not physical firmware evidence. See the Milestone 5
procedure/result table in TEST_PLAN.md; every physical row is marked not run.

Normal APK limitations cannot be repaired with application code alone: force-stop
blocks automatic startup until manually opened; credential-encrypted settings need
unlock; OEM autostart/battery/task policies may suppress broadcasts or kill/suspend
foreground services. No root/system permission, firmware change, wake lock or retry
loop is attempted. Open NexaAI from the launcher and retry the overlay manually;
use visible push-to-talk if overlay/background operation is blocked. Optional battery
settings guidance does not establish an exemption or guaranteed persistence.

Tap-to-command follow-up: idle overlay tap now requests one command through the
existing microphone FGS without opening Home or requiring a wake phrase. Wake-waiting
tap closes wake capture before ASR and returns to the opted-in loop afterward.
Command-active tap stops; hold opens Home. Body indicator becomes light green only
for coordinator-reported wake/command listening. Pixel/fake-audio tests passed, not
physical color/recording checks. On API 30+ a background-started microphone FGS may
not have microphone access; API 31+/34+ add service-start/while-in-use restrictions.
The normal APK cannot guarantee direct capture from an overlay on those versions.
Permission/start/audio failures remain visible; use Home push-to-talk when blocked.
Continuous wake operation needs explicit hands-free opt-in and both models. No
overlay, microphone, latency, sleep/ignition or resource result is physically verified.