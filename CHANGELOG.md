# Changelog

## 0.1.0 - In development

- Milestone 4: integrate the explicit built-in radio launch and verified
    frequency/key/mute broadcasts through the existing deterministic command
    pipeline. Add distinct next/previous search, AMS and INTRO commands, bounded
    FM conversion, truthful request-only responses and host tests. K2501
    installed-APK receiver access and tuner behavior remain unverified.

- Fix completed Vosk utterances being displayed as partials and discarded before
    an empty trailing final result. A nonempty endpoint result now ends capture
    and reaches the existing validated command pipeline once; partials remain
    display-only. Host regression tests cover endpoint, partial and silence cases;
    the reported device behavior still requires retesting.

- Milestone 3: add offline Vosk push-to-talk (`vosk-android:0.3.47`), explicit
    model ZIP import into private storage, runtime microphone permission, typed
    voice lifecycle, final-only dispatch through the existing command engine, and
    optional offline-only Android TTS. Real model, voice and target-device
    verification remain outstanding; production radio remains on its existing
    unsupported adapter.

- Fix Android ICU-incompatible placeholder regexes that can crash Interpret;
    parser and configuration validation share an explicitly escaped expression.
    Reproduce old-pattern failure with native ICU and verify the corrected source
    pattern; 30 host tests, debug build and lint pass. On 2026-10-24, the user
    confirmed the app works and all reported issues are fixed; detailed target
    hardware acceptance remains unverified.

- Milestone 2: typed deterministic parsing, central validation/dispatch, single-use
    preparations, live configuration revalidation and engine-level cancellation.
- Add injectable music-stream volume with state verification, installed app
    allowlisting with persisted friendly names, home navigation and action-checked
    media sessions behind optional notification-listener access.
- Keep radio/listening unsupported: no invented vendor API/band, speech runtime,
    LLM, arbitrary package/intents or force-stop claims.
- Add Home command controls, installed-app picker, capability refresh and stronger
    multiword-template collision checks; preserve schema-1 settings.
- Verify 29 host tests, debug build and lint (zero errors, 10 existing warnings).
    Target hardware remains unverified.

- Milestone 0: initialize Kotlin/Compose Android baseline with API 28 minimum,
  API 35 compile/target, pinned dependencies and baseline unit test.
- Record actual host tools and absent target-device evidence.
- Establish architecture, privacy, command, model and acceptance documentation.
- Milestone 1: six-screen Material 3/Compose navigation, DataStore-backed safe
    settings with legacy migration/corruption handling, 19 seeded command
    definitions, searchable alias editing/enablement and collision validation.
- Add strict, bounded configuration import/export via SAF, preview/apply/cancel,
    confirmed settings/command resets and honest unavailable feature states.
- Add configuration, persistence, import-flow and landscape/compact UI tests.
- Fix API 28 import compatibility and clipped compact reset actions found by checks.
- Add explicit Android 12+ backup exclusions, launcher icon and verified wrapper
    distribution checksum. No speech/device integration claimed; target checks skipped.