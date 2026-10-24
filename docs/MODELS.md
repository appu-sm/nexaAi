# Model Inventory

No speech, wake-word or LLM models or runtimes are installed or bundled.
The Models screen must therefore show an empty inventory, not invented entries.
Model import and deletion are unavailable until Milestone 3 provides validated
storage and runtime ownership. No automatic download is performed.

| Category | Candidate | Current status |
| --- | --- | --- |
| Speech | Vosk small | Not selected/integrated; packaging/ABI/language/license review pending |
| Optional speech | Whisper.cpp tiny | Deferred pending measured latency and memory |
| Wake word | No selected engine | Unavailable; phrase text does not train a detector |
| Local LLM | No selected runtime/model | Off; not a release requirement |
| TTS | Installed Android voices | Not inspected/integrated; offline availability unknown |

Before adding a model, record its exact source URL, license/distribution rights,
language, runtime/ABI requirements, checksum, file/download size, expanded storage,
and measured runtime RAM. Validate SAF imports for size/type/path traversal and
load off the main thread. Deletion requires confirmation and an idle runtime.

K2501 cold/warm latency, peak RAM, CPU, power impact, and radio/navigation
interference are all unmeasured. A small parameter count or file size is not
evidence that a model fits in a 2 GB head unit. Do not run STT and an LLM together
without actual measurements. The no-LLM route remains the default.