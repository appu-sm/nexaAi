# Offline ASR Comparison

## Audited Baseline

Before integration: Kotlin SpeechRecognizerEngine exposed initialize/recognize/
stop/release with String partial/final values; Vosk Android 0.3.47 owned a cached
Model and per-command AudioRecord/Recognizer. Capture: 16 kHz mono PCM16,
VOICE_RECOGNITION (optional MIC), 100% gain (100-300% optional),
max(2 * minimumBufferBytes, 16000) bytes, minimum <=65536, 15-second maximum,
first nonempty Vosk endpoint or finalResult on Finish/deadline. Optional grammar
maps exact numeric phrases only on finals. Confidence was unavailable. Partial
JSON text was UI-only. Cancel stopped capture and cancelled the coordinator job;
cleanup released recorder/recognizer, while the model cached until import/teardown.

One application-owned coordinator already gated wake/PTT/overlay/service capture.
sherpa 1.12.14 GigaSpeech 3.3M KWS closed wake AudioRecord before command capture;
TTS completion preceded wake rearming. Detector and TTS algorithm, registry,
parser/validator/dispatcher and frozen radio mappings are unchanged. Settings
use strict schema-1 JSON in Preferences DataStore. All model weights remain
manually provisioned, never packaged in the APK. The legacy Vosk private directory
is vosk-model-small-en-us-0.15 even when Indian English 0.4 is imported; no model
is silently replaced or renamed.

Increments extended the existing contract, extracted shared command capture,
added native sherpa decoding, persisted selection, then added replay/tests.
Behavior-affecting changes: ASR now loads on first hands-free command rather than
before wake waiting; session-close-before-model-release and cancellation ownership
are enforced. No baseline rate, gain, source, grammar default, Vosk endpoint or
phrase filter changed. Detailed lifecycle is in [Architecture](ARCHITECTURE.md).

## Prepare Consented Recordings

Use the intended head-unit microphone while stationary with explicit speaker
consent. Record source/gain, firmware/API/ABI, speaker/accent, distance, cabin
noise, playback, date and actual model-file checksums in a private ledger.
Do not invent accent variants or errors: add only observed recordings. Avoid
identifiable/sensitive content.

1. Install both [models](MODELS.md); retain baseline Vosk files.
2. Copy docs/asr-corpus.json to ignored asr-local/corpus.json. Edit as JSON,
   set consentConfirmed true only after consent, truthful provenance and voskModel.
   The repository template has no consent and no recordings.
3. Provide `<id>.pcm` for each sample ID: headerless little-endian signed PCM16,
   16000 Hz mono, <=15 seconds; include trailing silence for endpointing. Convert
   a consented WAV explicitly on the host:

   ```sh
   ffmpeg -i consented-volume.wav -ar 16000 -ac 1 -c:a pcm_s16le -f s16le asr-local/volume_spoken.pcm
   cd asr-local
   zip nexa-evaluation.zip corpus.json *.pcm
   ```

4. ZIP root: only corpus.json and unique `<id>.pcm`, no folders. Limits: 100
   samples, 480,000 bytes/PCM, 64 KiB manifest, 32 MiB expanded run. App staging
   is private cache, deleted after success/cancel/failure. OS process kills can
   leave cache until Android clears it or app data is removed. Original selected
   ZIP/recordings are not deleted by NexaAI.
5. Stop hands-free, Models > Choose evaluation ZIP > Confirm and run. Replay
   needs no microphone permission. Export evaluation with SAF. Reports contain
   raw reference/recognized transcripts and provenance, unlike redacted voice
   diagnostics; export only with consent. Reports are memory-only until explicit
   export, lost on process death; exported files remain under user control.
   Never commit recordings, models or private reports.

## Corpus and Scoring

The registry remains authoritative. The template includes supported aliases,
spoken numbers, invalid FM, unrelated speech, AMS/INTRO/mute/unmute and deliberate
unconfigured phrases. Null expectedCommandId explicitly expects rejection:
open FM, seek next/previous station and play music are not default aliases.
expectedParameter is canonical numeric text or an allowlisted package ID.
Spoken thirty/ninety-eight point three retain desired SET_VOLUME/TUNE_FM outcomes
so the unrestricted parser's lack of word-number conversion remains visible.
No aliases/parser rules were added. Update expectations from any edited registry.

Text comparison: Locale.ROOT lowercase, nonletter/nonnumber punctuation except
periods to spaces, terminal periods trimmed, whitespace collapsed. Decimal points
remain. No number conversion, fuzzy/phonetic rewriting or synonyms. Exact match
and word edit counts use this policy. WER = total edits / total reference words,
emitted only at >=20 samples and >=100 words with an initialized engine. Raw
edit/word counts always remain available. This is an output guard, not statistical
acceptance. Failed/empty decodes count as missing words, not recognition success.

Command scoring passes raw ASR text to existing exact parser + CommandValidator
with the application's configured 87.5-108.0 MHz, 0.01 MHz request band. No live
capability check or hardware dispatch. Correct ID/wrong parameter is ID success
but parameter failure. Explicit expected rejections are counted in denominator
metadata; do not describe them as hardware commands. referencePipelineCommand
shows the raw reference's parser outcome: punctuation/spoken numbers can cause
rejection. Production still revalidates capabilities and uses existing controller
rounding; parser accuracy is not hardware success.

## Execution and Measurements

Vosk runs first, releases streams/model, then sherpa runs the same files.
No complete ASR models/recorders coexist. Grammar/hotwords are disabled for the
initial comparison regardless of saved Vosk grammar. Configuration SHA-256
identifies saved setup, not model files. Versions: pinned sherpa candidate and
operator-declared Vosk identity; retain actual model hashes in the private ledger.
PCM hashes identify inputs. Replay uses 100 ms frames at CPU speed, not real time.
First nonempty endpoint ends a sample like production; remaining PCM is not fed.
consumedAudioMs exposes truncation versus full duration. This compares the same
offered recording and production endpoint policies, not endpoint-disabled full
utterances. Never trim references to improve scores.

Report: per-sample raw transcript, exact match, word edits, expected/actual command
and parameter, empty/error/timeout counts, format/duration, initialization success/
attempt duration, and replay latency (session construction + inference, excluding
initialization/read/conversion). Capture time: Not measured for prerecorded input.
First sample is cold-session; later samples share a loaded model. Restart process
for cold loads and repeat for cache/thermal effects. Fixed Vosk-first order can
bias results; acknowledge it. Do not equate replay CPU time with acoustic latency.

Optional process metrics: PSS before/after model load and after replay, loaded RSS,
cumulative CPU delta across load + replay + snapshot overhead. CPU percent, true
load peaks and steady-state memory are Not measured. Snapshots are not peaks;
release does not guarantee immediate RSS reduction. Measure externally on target:
sample `adb shell dumpsys meminfo com.nexaai.assistant` during repeated cold loads
and switches; capture supported top/dumpsys cpuinfo, retaining timestamps/overhead.
Use [resource/soak procedures](WAKE_WORD_ACCEPTANCE.md). Host JVM resources are not
head-unit measurements.

Deadlines: 60 s initialization, 30 s/sample replay. Native calls cannot be forcibly
preempted; checks occur between calls/frames. Cancel discards partial report and
releases resources before another engine starts. Native aborts/OS kills require
explicit user restart, not unsafe fallback. Trusted files, storage/RAM and ABI
compatibility remain prerequisites.

## Results and Acceptance

Host gate: 117 tests / 18 suites, no failures/errors/skips; assembleDebug and
lintDebug successful (0 errors/26 existing warnings). Exact artifact and command
evidence: [Test plan](TEST_PLAN.md#switchable-offline-asr-2026-10-29).

| NWD_K2501 measurement | Vosk Indian English | sherpa English |
| --- | --- | --- |
| Consented sample count | Not measured | Not measured |
| Native load + real offline transcript | Not verified | Not verified |
| Exact / canonical / parameter accuracy | Not measured | Not measured |
| WER / empty / timeout rate | Not measured | Not measured |
| Cold/warm latency / CPU | Not measured | Not measured |
| Load peak / steady memory / switch retention | Not measured | Not measured |

Models shows a generated simple summary and exports detailed JSON. No consented
audio, installed ASR models or Android target was supplied in this session; no
real-voice benchmark or native Android recognition is claimed. Host tests use
synthetic PCM/fake inference for lifecycle/scoring. Real adapters compile against
pinned APIs, not placeholder recognizers.

Physical gates: airplane-mode real recognition per model; unchanged Vosk source/
gain/grammar baseline; cancel/Finish/timeout/permission revoke; repeated safe
switching/restart; mic exclusivity across wake/overlay/PTT/TTS; low-memory/load
peaks; radio launch/tune/seek/AMS/INTRO/mute/unmute; actual stationary controls;
startup/screen/ignition regressions. Use >=20 samples/100 words just to enable WER,
then a substantially larger balanced speaker/noise corpus with raw counts before
conclusions. Vosk remains default pending agreed target acceptance budgets.

## Changed Surfaces

Speech: SpeechRecognition, VoskSpeechRecognizer, CommandAudioCapture,
SherpaSpeechRecognizer, SelectedSpeechEngine, AsrEvaluation Kotlin files.
Wiring/UI: NexaAiApplication, MainActivity, AssistantViewModel, NexaApp.
Persistence: Configuration, ConfigurationCodec; no second settings store.
Tests: AsrEngineTest, ConfigurationFlowTest, ShellUiTest and speech regressions.
Docs: README, AGENT, CHANGELOG, ARCHITECTURE, MODELS, COMMANDS, DEVICE_COMPATIBILITY,
TEST_PLAN, PRIVACY_AND_PERMISSIONS, this protocol, asr-corpus.json; .gitignore
excludes local audio/models/reports. No dependency upgrade, command/radio mapping,
wake algorithm, manifest or runtime networking change.

## Troubleshooting

- Selected model missing: stop listening, check the selected engine in Settings,
   then import its ZIP. Wake assets are not ASR assets. Vosk's historical directory
   name does not prove which Vosk language/version is installed.
- Initialization/ABI/memory error: verify official filenames/hashes, free storage
   and RAM; inspect device ABI against packaged libraries. No fallback is hidden.
   Explicitly select an installed engine after stopping. Typed Home controls remain
   available even when both ASR models are unavailable.
- Quiet/truncated command: compare the same source/gain at 100%, keep rate at
   16 kHz, wait for real Listening, and pause through wake-to-ASR handoff. Compare
   MIC/VOICE_RECOGNITION only as a separate logged experiment. Digital gain raises
   noise/clipping; it does not select a physical external microphone.
- Busy switch/import/evaluation: stop session and wait for cleanup/TTS completion.
   Replay never opens the mic; do not run a separate recorder alongside hands-free.
- Unrecognized numbers/phrases: inspect raw text, registry and parameter counts.
   Do not enable fuzzy matching to turn an incorrect decode into a hardware action.
- No comparative result yet: supply consented PCM files for every manifest ID,
   retain unchanged references/negative samples, run Models evaluation and export.
   Not measured is not zero, and a tiny corpus is not an accuracy claim.