# Privacy and Permissions

## Current application

The app stores settings, command definitions and a separate app allowlist in
app-private Preferences DataStore. Development transcripts/results stay in memory
only, not logs or storage. No audio, contacts, locations, analytics or telemetry
are collected. No runtime network permission is declared. Backup is disabled;
Android 12+ cloud-backup/device-transfer rules also exclude app data. OEM backup
behavior is not verified on the target. The debug APK includes Vosk native runtime
libraries but no speech model.

Import/export uses the Android Storage Access Framework: the user chooses a
document, with no broad storage permission. Configuration exports contain only
validated settings/command data and never include audio, private paths, device
identifiers, secrets, the app allowlist or diagnostic history. Imports require a preview and
explicit confirmation. Clearing app data/uninstalling removes local settings;
exported documents remain wherever the user saved them.

Debug logging is unavailable, off, and does not record transcripts. There are no
local logs to clear. Destructive configuration resets require confirmation.

## Microphone and offline speech

- `RECORD_AUDIO`: declared for push-to-talk/hands-free and requested only after an
	explicit microphone or hands-free action. Denial leaves typed testing available;
	permanent denial points to Android app settings.
- Speech runs locally through Vosk. Capture uses a bounded in-memory PCM buffer;
	audio and transcripts are not saved, logged or uploaded. The imported model is
	stored in private app files, not copied to public storage.
- TTS uses an already-installed English voice not marked as network synthesis.
	NexaAI never downloads voice data. TTS defaults off, and on-screen results remain
	available if speech output is disabled or unavailable.

## Deferred permissions
- Overlay: optional, disabled by default; system grant does not grant microphone access.
- Boot: optional, best effort, disabled by default; never silent continuous recording.

Neither deferred permission is currently declared or requested. No accessibility
service, root, arbitrary shell execution or hidden vendor API is used.

## Hands-Free Foreground Service

`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` support the nonexported
microphone service. `POST_NOTIFICATIONS` is requested on Android 13+ during opt-in.
The service posts an ongoing notification with Open and Stop actions; if notification
permission is denied Android still exposes active foreground services in its task
UI. The app always retains Stop and listening status. Microphone privacy indicators
and while-in-use/background-start restrictions are not bypassed.

The service starts only from the user's explicit visible-app control. Persisted
wake flags or configuration imports never silently start it after launch, reboot,
process death or permission grant. There is no sticky restart or boot receiver.
Leaving Home cancels push-to-talk, but not an explicitly enabled hands-free service.
Stopping/destroying the service cancels the session and releases audio/models.

Keyword audio is processed by sherpa-onnx locally, discarded, and never dispatched
as a command. Wake capture stops before command ASR and remains off throughout
TTS. Imported keyword weights/tokens stay in private app files; no key or automatic
model download is needed. All transient transcripts/results remain memory-only.

## Milestone 2 access

- `MODIFY_AUDIO_SETTINGS` is a normal permission used only for music-stream
	volume, without microphone access or capture.
- Intent-scoped launcher/home queries support installed-app selection on Android
	11+. No `QUERY_ALL_PACKAGES`. Only user-allowlisted, installed, launchable apps
	may open; app IDs are not inferred from transcripts.
- Radio integration adds a single package-visibility entry for `com.nwd.radio`.
	It launches the explicit radio activity and sends broadcasts scoped to that
	package. No privileged permission is requested or assumed; receiver access and
	actual tuner behavior must be tested from the installed NexaAI APK.
- Media requires optional notification-listener special access. Android's grant
	is broader than media alone; review the system disclosure. NexaAI's listener
	does not override notification-content callbacks, inspect contents, log
	notifications or send them anywhere. It supplies the authorized component for
	active-session queries and documented transport controls.
- The listener service is protected by system `BIND_NOTIFICATION_LISTENER_SERVICE`;
	the APK cannot grant access to itself. Commands/Diagnostics links to Android
	notification-access settings after disclosure; nothing is requested automatically.
	Access can be revoked there; execution rechecks. No notification-posting or
	privileged `MEDIA_CONTENT_CONTROL` permission is requested.

The app has no runtime network permission. Offline recognition and TTS code paths
are implemented, but they have not been physically verified in airplane mode on
the target device. The model import is explicit and documents its source/license;
the English TTS voice must be checked on the device before enabling speech.