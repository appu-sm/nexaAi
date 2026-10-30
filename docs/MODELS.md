# Model Inventory

No speech model is bundled and the app does not download one automatically. The
Models screen accepts a user-selected local ZIP and stores it in private app files.
The model is loaded only on explicit speech initialization and reused for sessions.

| Category | Candidate | Current status |
| --- | --- | --- |
| Speech (default) | Vosk Android `0.3.47` + existing `vosk-model-small-en-in-0.4` | Existing import preserved; target quality unmeasured |
| Alternative speech | sherpa-onnx Android `1.12.14` + streaming English Zipformer 20M 2023-02-17 int8 | Real streaming API; manual four-file ZIP; target unverified |
| Optional speech | Whisper.cpp tiny | Deferred pending measured latency and memory |
| Wake word | sherpa-onnx Android `1.12.14`, English GigaSpeech KWS 3.3M 2024-01-01 | Real keyword runtime integrated; explicit Hey Nexa ZIP import required; target unverified |
| Local LLM | No selected runtime/model | Off; not a release requirement |
| TTS | Android TextToSpeech | Integrated; selects installed English voices without the network-synthesis feature; target availability unverified |

## Vosk setup

For this comparison preserve the user's existing **Indian English**
`vosk-model-small-en-in-0.4` (official catalogue: 36 MB, Apache-2.0).
If installing afresh, select Vosk in Settings and import that official ZIP.
The older US-English setup below remains valid for existing installations but
is **not** an instruction to change the baseline. Both use the same legacy
private directory; the app does not infer a version from its name.
Record the actually imported version in evaluation `voskModel` metadata.

1. On a trusted device, download `vosk-model-small-en-us-0.15.zip` manually from
	the [official Vosk model catalog](https://alphacephei.com/vosk/models). The
	catalog lists the model at 40 MB, English, Apache-2.0, and identifies it as a
	lightweight model for Android/Raspberry Pi.
2. Transfer the ZIP to the head unit without extracting it.
3. In NexaAI, open Models, choose **Choose model ZIP**, and select that local file.
	Installation uses Android's document picker and requires no broad storage
	permission. There is no automatic network request or silent download.
4. The app extracts into private app storage at
	`$filesDir/vosk-model-small-en-us-0.15`; the importer allows at most 160 MiB of
	expanded data, rejects path traversal, and requires core `am`, `conf` and
	`graph` files. The folder inside the ZIP may be named `Vosk`, use the official
	model name, or be absent. Validation checks nonempty `am/final.mdl`,
	`conf/model.conf`, `conf/mfcc.conf`, `graph/HCLr.fst`, `graph/Gr.fst` and
	`graph/phones/word_boundary.int`; `graph/words.txt` is not required for this
	small model. Native loading still checks model compatibility after import.
	The ZIP is not copied to public storage. Removing app data or
	uninstalling NexaAI deletes the installed model.
5. Tap the Home microphone button. Import validates structure only; native loading
	occurs lazily at recognition. The engine loads one Vosk `Model` off the main
	thread, then reuses it; each recording creates and closes a recognizer. Missing,
	incomplete, corrupt or ABI-incompatible data is shown as an error and never
	falls back to online recognition.

Vosk model source and license are published at the official catalog above. The
native dependency is pinned to `com.alphacephei:vosk-android:0.3.47` from Maven
Central. Model archives are not committed to this repository. Model file size is
not the same as expanded storage or runtime RAM; the Vosk catalog describes small
models as typically around 50 MB and roughly 300 MB runtime memory. Actual K2501
memory use, CPU, startup, accuracy and ABI compatibility have not been measured.

## Vosk Grammar Mode

For missing leading words, wait for the real command Listening indication before
speaking; grammar/recognizer preparation precedes microphone readiness. For quiet
command audio, Settings > Listening > Command microphone offers the existing
VOICE_RECOGNITION source or standard MIC plus saved 100-300% software input gain
(default 100%). Compare sources at unity gain before increasing to 150-200%.
These are next-capture command-ASR controls, not wake sensitivity, acoustic
retraining, noise removal or guaranteed firmware mic routing. Gain amplifies
noise and saturates loud PCM16 samples. Record source/gain with future acceptance
runs; no target accuracy improvement is claimed without a physical comparison.

In **Settings > Listening**, enable **Vosk grammar mode** to constrain command
recognition. It is off by default. Enter optional custom words or phrases in
**Custom grammar words**, one entry per line, then choose **Save custom words**.
Up to 100 distinct entries are accepted, each 1-60 English letters with single
spaces, apostrophes or hyphens between letter groups. Entries are lowercased and
deduplicated. Turning the mode off retains them. These preferences persist and
are included in configuration export/import; older schema-1 files default to
grammar off and no custom entries.

At each command capture, the app snapshots the current settings, enabled aliases
and private app allowlist. It builds the grammar off main and passes a JSON phrase
array to Vosk's grammar-aware recognizer constructor. Push-to-talk and wake-driven
commands share this path; the loaded acoustic model is reused. Settings or alias
changes during recording take effect on the next capture, not halfway through it.

Generated phrases expand app placeholders using allowlisted names, volume values
0-100, and FM frequencies 87.50-108.00 in 0.01 MHz steps. Numbers support English
cardinal and digit-by-digit forms, with decimal digits after `point`; for example,
`set volume to fifty percent` and `tune fm to ninety eight point three`. Exact
generated numeric phrases are mapped to the parser's existing digit format on
final results only. Unrestricted mode and typed-command parsing are unchanged.
Grammar-mode FM expansion uses the head unit's 10 kHz request granularity; finer
numeric input remains available through typed commands or unrestricted decoding.

`[unk]` is always included. Results containing it, and ambiguous generated spoken
forms, are rejected rather than guessed. Custom vocabulary does not define new
commands: final transcripts still go through the same parser, enabled-command
checks, parameter validation and dispatcher. A grammar is not an exact phrase
matcher or a safety boundary; unrelated audio may still decode to a valid command.

Use the recommended small English model, which supports dynamic grammar. Custom
entries select vocabulary already available in that model; they do not add new
pronunciations, train the model, or guarantee recognition of out-of-vocabulary
names. Recognizer construction errors stop capture visibly rather than silently
falling back to unrestricted decoding. Generation is bounded to 65,536 entries
and 4 MiB of phrase data; overly large alias sets fail with an actionable error.
CPU/RAM cost, accuracy and false-command rates still require on-device A/B tests.

## sherpa-onnx Command ASR

This uses the **existing** official `com.k2fsa:sherpa-onnx:1.12.14@aar`, with
release URL/digest and native licensing documented under Hey Nexa below.
Its manifest declares min SDK 21, below NexaAI's min 28; the cached AAR includes
armeabi-v7a, arm64-v8a, x86 and x86_64. This supports Android 9-11 at packaging
level only; CPU instructions, native loading and firmware behavior need target
testing. No new runtime or network permission is added.

Candidate: [sherpa-onnx-streaming-zipformer-en-20M-2023-02-17](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17),
Apache-2.0 per its model card, exported from the small streaming LibriSpeech
transducer. The pinned upstream [Kotlin configuration, type 10](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.14/sherpa-onnx/kotlin-api/OnlineRecognizer.kt)
uses the same int8 encoder/joiner and float decoder. Runtime: Apache-2.0;
ONNX Runtime: MIT. Retain upstream notices when redistributing models/libraries.

Selected files and upstream sizes/digests checked 2026-10-29:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| encoder-epoch-99-avg-1.int8.onnx | 42845182 | 3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3 |
| decoder-epoch-99-avg-1.onnx | 2092272 | 45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30 |
| joiner-epoch-99-avg-1.int8.onnx | 259572 | e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e |
| tokens.txt | 5048 | Record locally with sha256sum |

Total: 45,202,074 bytes (~43.1 MiB). Quantized storage, a small 20M streaming
model, one CPU thread and greedy decoding make this a conservative candidate
versus large/offline-batch models, not proof it fits RAM. Runtime RAM, load peaks,
steady CPU, latency and Indian English accuracy are **Not measured**. General
English does not guarantee improvement over Indian English Vosk. Vosk grammar/
hints are not applied to sherpa; no custom grammar capability is assumed.

On a trusted host, download only the four files above from the model's Files tab,
verify the three weight hashes above and retain the tokens hash/source in your
local run ledger. NexaAI performs no automatic download. After placing them in
ignored `asr-local/sherpa-model/`:

```sh
cd asr-local/sherpa-model
sha256sum encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt
zip -j ../nexa-sherpa-asr.zip encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt
```

Transfer the ZIP, stop listening, select **sherpa-onnx - English** in Settings,
then Models > Choose model ZIP. Files keep their exact names and install at
`$filesDir/sherpa-asr-english`. The existing safe ZIP installer supports optional
outer directories, staging/rollback, 20,000 entries and 160 MiB expanded total;
required files must be nonempty and <=100 MiB each. Budget at least 160 MiB free
for staging and old-model backup. Import checks structure, not authenticity/native
compatibility. Loading is lazy; missing/corrupt/incompatible/memory-limited models
fail visibly without Vosk fallback. Native model parsers are not sandboxed; import
only trusted verified files.

Wake ZIP import does not install ASR; ASR import does not replace wake models.
Switching retains both engines' model files on disk but only one complete ASR
model in memory. Models are not bundled/committed. See [replay](ASR_EVALUATION.md).

## Offline TTS

Android TextToSpeech initializes asynchronously. NexaAI chooses an already
installed English voice whose feature set does not indicate network synthesis or
missing voice data. It does not trigger `ACTION_INSTALL_TTS_DATA`, download voice
data, or use a cloud fallback. In Settings, enable **Speak command results** only
when the app reports an offline voice is available. Otherwise use the device's
Android text-to-speech settings to install/configure an English offline voice, then
close and relaunch NexaAI so the asynchronous voice check runs again. On-screen
results remain available when TTS is off or unavailable.

Capture uses 16 kHz mono signed 16-bit PCM and is capped at 15 seconds; Finish,
Cancel, lifecycle stop, timeout and recorder errors stop the session. Model loading,
ZIP extraction and inference run off main. Target model/native compatibility and
offline TTS must still be confirmed with networking disabled on the actual unit.

K2501 cold/warm latency, peak RAM, CPU, power impact, and radio/navigation
interference are all unmeasured. A small parameter count or file size is not
evidence that a model fits in a 2 GB head unit. Do not run STT and an LLM together
without actual measurements. The no-LLM route remains the default.

## Hey Nexa keyword model

The runtime is `com.k2fsa:sherpa-onnx:1.12.14@aar`, resolved from the
[official versioned release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.14).
The downloaded AAR SHA-256 was checked against the release asset metadata:
`5a629a899888cb2760e245d9d5340858b15591aee7fa13644cf57199ff2829b9`.
sherpa-onnx is [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.14/LICENSE);
its ONNX Runtime backend is MIT. Preserve upstream licenses and dependency notices
when redistributing the runtime. No key, account, online activation or runtime
network permission is required. Only build dependencies are fetched by Gradle.

Use `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, not a general ASR
or Chinese keyword model. The upstream
[model card](https://modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01)
declares Apache License 2.0, English GigaSpeech XL training and BPE modeling.
[Official model/tokenizer instructions](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html#sherpa-onnx-kws-zipformer-gigaspeech-3-3m-2024-01-01-english)
provide the dated archive. No weights are bundled or downloaded by NexaAI.

On a trusted computer, explicitly download the
[dated model archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2),
extract it and prepare a ZIP as follows. These commands are setup instructions,
not actions performed automatically by the app:

Run these commands from the extracted model's root, not `test_wavs`, using the
archive's original nonempty `bpe.model` and `tokens.txt`. Empty tokenizer files
cause SentencePiece's `unk is not defined` error. Replace `HEY NEXA` below with
your desired English wake phrase before tokenization.

```sh
python3 -m venv "$HOME/.venvs/nexa-model-tools"
. "$HOME/.venvs/nexa-model-tools/bin/activate"
python -m pip install sherpa-onnx==1.12.26 click==8.1.8 sentencepiece==0.2.1
cd sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
printf 'HEY NEXA\n' > nexa-keywords-raw.txt
sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type bpe \
	--bpe-model bpe.model nexa-keywords-raw.txt nexa-keywords.txt
mkdir nexa-wake
cp encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx nexa-wake/encoder.onnx
cp decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx nexa-wake/decoder.onnx
cp joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx nexa-wake/joiner.onnx
cp tokens.txt nexa-wake/tokens.txt
cp nexa-keywords.txt nexa-wake/keywords.txt
zip -j nexa-wake.zip nexa-wake/*
```

The virtual environment keeps these tools separate from the system-managed Python
and avoids Linux's `externally-managed-environment` error. Do not use `sudo pip`
or `--break-system-packages`. If virtual-environment creation reports that
`ensurepip` is unavailable, install your distribution's `python3-venv` package
first (Debian/Ubuntu: `sudo apt install python3-venv`), then retry. In a new
terminal, run the activation command again before using `sherpa-onnx-cli`.
The host tokenizer is pinned separately to `1.12.26` because `1.12.14` is not
available for every host Python/package index. It generates BPE text using the
downloaded model's own vocabulary; the Android runtime remains `1.12.14`.
`click` supplies the CLI and `sentencepiece` supplies BPE tokenization; install
both explicitly because the sherpa package does not declare all CLI dependencies.

Transfer the ZIP locally and choose **Models > Choose wake-word ZIP**. The import
uses the existing bounded, traversal-safe staging/backup installer. Storage is
private `$filesDir/sherpa-kws-english`. Required files are `encoder.onnx`,
`decoder.onnx`, `joiner.onnx`, `tokens.txt` and `keywords.txt`. Only one keyword
line is accepted, without score/threshold/label overrides. Its BPE tokens must
exist in the supplied vocabulary and reconstruct a valid English phrase. ZIP
import validates that structure independently of the currently saved phrase.
Before enabling hands-free, stop listening and save the same phrase in
**Settings > Listening > Wake phrase > Save wake phrase** (default: `Hey Nexa`).
Hands-free initialization compares the tokens with that setting, ignoring case,
and rejects a mismatch with both phrases in the error. Missing files and
incompatible models produce a visible error. Structural
validation is not a cryptographic authenticity check: import only trusted assets.
Limits: 160 MiB expanded ZIP, 20,000 entries, 40 MiB per required model file,
1 MiB vocabulary and 4 KiB keywords. Incompatible native initialization is reported;
malicious native model parsers are not sandboxed, so do not import untrusted models.

The underlying engine supports open-vocabulary keywords through tokenization,
without acoustic retraining. The saved wake phrase accepts 1-80 English letters
with single spaces, apostrophes or hyphens between letter groups. Changing it
does not regenerate or train keyword tokens: import a package tokenized for the
same phrase. Only one phrase per package is supported; sensitivity overrides are
not supported. The phrase persists and round-trips through configuration export
and import. A configuration change to the phrase stops an active hands-free
session; listening must be enabled explicitly again. Pronunciation and
false-trigger performance of custom names remain unmeasured.

## Resource Decision

Continuous detection uses the dedicated KWS decoder of a tiny transducer, not the
Vosk command recognizer. One CPU thread, four active paths, default score 1.5,
threshold 0.25 and two trailing blanks are fixed; no cosmetic sensitivity slider.
Quantized upstream files total roughly 5 MiB (not measured RAM). Wake capture uses
16 kHz mono PCM16, 100 ms frames, reusable 3.2 KiB PCM and 6.4 KiB float arrays,
and a bounded AudioRecord buffer. The model loads once per enabled session; new
streams discard old wake audio and new recorders separate wake and command capture.
Selected ASR now loads at the first command, then caches alongside KWS for warm
activation. This increases idle RAM after first use versus unloading after every
command, and must be measured on the 2 GB unit.
Both models are released when hands-free ends. No LLM is loaded.

Compared with alternatives, Porcupine requires an access key and a separate
licensing/setup review; openWakeWord needs additional Android audio/frontend
integration; PocketSphinx's older Android packaging needs maintenance/ABI review.
sherpa provides a pinned Android AAR and a small, published English KWS model,
at the cost of a larger multi-ABI runtime/APK. These are engineering selection
criteria, not measured comparative benchmarks. No engine was benchmarked on K2501.

Continuous capture/inference costs more power than push-to-talk. Frame size, model
chunking and the 350 ms post-command cooldown trade latency against CPU/echo safety;
default thresholds trade false accepts against missed wakes. No low-power,
accuracy, noise robustness or persistent-screen-off claim is made. Measure PSS/RSS,
CPU, false accepts per hour, missed wakes, cold/warm latency and radio/navigation
interference before enabling this in regular use.