# Model Inventory

No speech model is bundled and the app does not download one automatically. The
Models screen accepts a user-selected local ZIP and stores it in private app files.
The model is loaded only on explicit speech initialization and reused for sessions.

| Category | Candidate | Current status |
| --- | --- | --- |
| Speech | Vosk Android `0.3.47` + `vosk-model-small-en-us-0.15` | Runtime integrated; model must be imported; device verification pending |
| Optional speech | Whisper.cpp tiny | Deferred pending measured latency and memory |
| Wake word | No selected engine | Unavailable; phrase text does not train a detector |
| Local LLM | No selected runtime/model | Off; not a release requirement |
| TTS | Android TextToSpeech | Integrated; selects installed English voices without the network-synthesis feature; target availability unverified |

## Vosk setup

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
5. Tap the Home microphone button. The engine loads one Vosk `Model` off the main
	thread, then reuses it; each recording creates and closes a recognizer. Missing,
	incomplete, corrupt or ABI-incompatible data is shown as an error and never
	falls back to online recognition.

Vosk model source and license are published at the official catalog above. The
native dependency is pinned to `com.alphacephei:vosk-android:0.3.47` from Maven
Central. Model archives are not committed to this repository. Model file size is
not the same as expanded storage or runtime RAM; the Vosk catalog describes small
models as typically around 50 MB and roughly 300 MB runtime memory. Actual K2501
memory use, CPU, startup, accuracy and ABI compatibility have not been measured.

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