# Changelog

## 0.1.0 - In development

- Name packaged APKs `<project-name>.<build-type>.<version>.apk`, including
    NexaAI.debug.0.1.0.apk and NexaAI.release.0.1.0.apk. Centralize app version/code
    and enabled build types in settings.gradle.kts alongside the project name;
    keep actual variant labels and Android version metadata consistent. Signing
    behavior is unchanged; release signing still requires separate configuration.

- Quiet/missing-first-word command follow-up: publish Listening only after actual
    microphone capture starts, not during Vosk grammar preparation. Add saved
    command microphone source (voice-recognition/standard) and bounded software
    input gain (100-300%, unchanged defaults), with saturating PCM16 processing.
    Preserve wake detection, exact aliases, allowlisting and radio behavior.
    Document source/gain/grammar comparisons; physical accuracy remains unverified.

- Milestone 6 validation: optional bounded monotonic voice events/counters,
    retained-pair latency summaries, on-demand injectable process/battery/thermal
    snapshots, reset/inspect and confirmed diagnostic JSON export. Add fake-clock,
    lifecycle instrumentation, compact UI and export regressions; no audio or
    transcripts retained, no engine/threshold/command/radio behavior change.
    Document reproducible quality, latency, resource and soak procedures with
    editable provisional budgets and pending K2501 acceptance gates. No physical
    measurements or firmware compatibility claims.

- Fix listening green spilling onto the robot's body: use a circular chest-light
    mask centered at (50%, 70.5%), radius 5.5%, including the pale center pixels.
    Give overlay text and its pointer a neutral grey (#424242) background; fix a
    drawable color-name collision that previously left the fill unset. Pixel and
    fill-color regressions pass for all four artwork variants; 92 host tests,
    debug build and lint pass. Physical head-unit appearance remains unverified.

- Use saturated green for the listening body light. Add a temporary speech bubble
    beside the floating robot for real partial/final transcripts and command
    results/errors. Keep the icon's touch area/position unchanged; choose the
    roomier side, wrap/scroll output, dismiss on text tap or 12 seconds of inactivity,
    and release both windows/timers on teardown. No audio/command behavior change,
    new dependency or target-device verification.

- Overlay tap-to-command: idle tap uses the existing microphone foreground service
    for one bounded command without opening Home or requiring wake word. Tap during
    wake waiting safely hands off to command ASR and rearms the opted-in loop;
    command-active tap stops, hold opens Home and dragging only repositions. Recolor
    only the small body indicator light green during reported listening. Add
    single-session/handoff/Stop/watchdog/permission/pixel regressions. Background
    microphone restrictions are reported, not bypassed; no automatic wake opt-in
    or boot microphone start. No new dependencies or physical verification.

- Refine the overlay into one transparent draggable robot icon, without a status
    panel or separate Stop button. Tap opens NexaAI when idle or stops active
    listening; hold opens the app in either state. Dragging saves position without
    triggering either action. Keep real wake-state artwork and accessible status.

- Milestone 5: optional draggable floating controls with supplied robot launcher/
    overlay artwork, explicit overlay permission flow and revocation handling,
    saved size/opacity/position, real shared listening status and immediate Stop.
    Add non-recording foreground overlay service and bounded after-unlock
    boot/package-update restoration, all off by default. Never auto-resume the
    microphone, launch the radio at boot or restart failed services in a loop.
    Preserve existing radio/command/speech/wake behavior and schema-1 settings;
    no new dependencies. Add host policy/window/service-failure/permission UI
    tests and physical acceptance procedure. Target firmware remains untested.

- Add optional Vosk grammar mode and custom vocabulary in Settings. Build bounded
    per-session grammars from enabled aliases, allowlisted app names, spoken
    volume/FM values and `[unk]`; map final numeric phrases into the unchanged
    command parser. Persist/export preferences with older-file defaults. No
    retraining, automatic command authorization or measured accuracy claim.

- Make the English wake phrase configurable in Settings and portable schema-1
    configuration. Accept custom single-phrase keyword ZIPs and check their tokens
    against the saved phrase before hands-free starts. Changing text still requires
    matching tokenized assets; target acoustic verification remains pending.

- Requested Milestone 4 hands-free extension: real sherpa-onnx `1.12.14` keyword
    spotting with an explicit Hey Nexa model import, serialized handoff to existing
    Vosk/command dispatch, awaited offline TTS, microphone foreground service,
    persistent opt-in controls and notification Stop. Add fake-audio/lifecycle/
    permission/service regressions and model/device setup documentation. Preserve
    verified radio mappings. No acoustic, resource or screen-off device verification
    is claimed; overlay, automatic boot/restart and LLM remain deferred.

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