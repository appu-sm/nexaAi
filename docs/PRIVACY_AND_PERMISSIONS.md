# Privacy and Permissions

## Current application

The app stores settings, command definitions and a separate app allowlist in
app-private Preferences DataStore. Development transcripts/results stay in memory
only, not logs or storage. No audio, contacts, locations, analytics or telemetry
are collected. No runtime network permission is declared. Backup is disabled;
Android 12+ cloud-backup/device-transfer rules also exclude app data. OEM backup
behavior is not verified on the target. The debug APK includes AndroidX graphics
and DataStore native helpers, not a speech or model runtime.

Import/export uses the Android Storage Access Framework: the user chooses a
document, with no broad storage permission. Configuration exports contain only
validated settings/command data and never include audio, private paths, device
identifiers, secrets, the app allowlist or diagnostic history. Imports require a preview and
explicit confirmation. Clearing app data/uninstalling removes local settings;
exported documents remain wherever the user saved them.

Debug logging is unavailable, off, and does not record transcripts. There are no
local logs to clear. Destructive configuration resets require confirmation.

## Deferred permissions

- Microphone: request only with an implemented speech engine and explicit listening.
- Overlay: optional, disabled by default; system grant does not grant microphone access.
- Boot: optional, best effort, disabled by default; never silent continuous recording.
- Foreground service/notification posting: only with a real, visible service and SDK-compliant types.

None of these deferred permissions is currently declared or requested. No accessibility
service, root, arbitrary shell execution or hidden vendor API is used.

## Milestone 2 access

- `MODIFY_AUDIO_SETTINGS` is a normal permission used only for music-stream
	volume, without microphone access or capture.
- Intent-scoped launcher/home queries support installed-app selection on Android
	11+. No `QUERY_ALL_PACKAGES`. Only user-allowlisted, installed, launchable apps
	may open; app IDs are not inferred from transcripts.
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

Offline app configuration can be tested without networking. This does not mean
offline speech has been implemented or verified. Future model downloads/imports
must require explicit consent and documented source/license/size. Do not treat
Android default recognition or TTS as offline without inspecting actual engines
and installed voices.