# Device Compatibility

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
| Radio package/API | Unverified; explicit unsupported adapter | Installed package evidence and documented/exported vendor API |
| Volume/media/app launch | Milestone 2 implemented; target unverified | Actual source/app/session tests on head unit |
| Speech/TTS | Not implemented (Milestone 3) | Engine, offline models/voices and airplane-mode tests |
| Overlay/boot/service | Not implemented (Milestone 5) | Permission and ignition/sleep/reboot tests |
| Wake word/local LLM | Not implemented; off | Real engines and target measurements |

No hardware-dependent capability has been verified. No proprietary radio APIs,
microphone capture or background recording service is included. Optional Android
notification-listener access must be explicitly granted for media sessions.

## Project baseline

Package/namespace: `com.nexaai.assistant`; minimum API 28; compile/target API 35.
AGP 8.9.2, Gradle 8.11.1, Kotlin/Compose plugin 2.1.20, Compose BOM 2025.04.01.
No bundled models or speech/LLM native runtime. APK inspection found AndroidX
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
| Radio | Unsupported; no vendor calls or assumed band | Verified interface/privileges, regional band/step, tune/seek readback |
| Listening | Unsupported; microphone off | Milestone 3 engine/model and lifecycle |

No device attachment or K2501 test was performed for this milestone. Runtime
Diagnostics reads this running device's facts and API capability checks; it does
not establish target compatibility. Host tests do not prove audible changes,
launches, playback, airplane-mode operation or target memory/latency. Use Home
commands while stationary to record outcomes per source/player. See `TEST_PLAN.md`.