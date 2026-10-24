# Command Catalogue

Milestone 2 executes validated commands from development input or tests without
speech, an LLM or networking. Milestone 1 command configuration is preserved.
`CommandCatalogue` in the data layer owns the complete seed data. Alias placeholders are fixed
by command type, not a mechanism to add arbitrary parameters or actions.

| Command ID | Default aliases | Parameter contract / runtime gate |
| --- | --- | --- |
| SET_VOLUME | set volume to {percent} percent | Integer 0-100; music stream; Android state verified |
| VOLUME_UP | volume up; increase volume; louder | Music stream +1 index, clamped to range |
| VOLUME_DOWN | volume down; decrease volume; quieter | Music stream -1 index, clamped to range |
| MUTE | mute | Device support and result verification |
| UNMUTE | unmute | Device support and result verification |
| LAUNCH_APP | open {app}; launch {app} | Installed, explicitly allowlisted friendly name |
| GO_HOME | go home | Resolvable documented home action |
| PLAY | play; resume music | Compatible session/action |
| PAUSE | pause; pause music | Compatible session/action |
| NEXT_TRACK | next track | Compatible session/action |
| PREVIOUS_TRACK | previous track | Compatible session/action |
| TUNE_FM | tune fm to {frequency} | Verified adapter and tuner-specific band/step |
| SEEK_NEXT | next station | Verified adapter |
| SEEK_PREVIOUS | previous station | Verified adapter |
| START_LISTENING | start listening | Installed engine/model, microphone permission |
| STOP_LISTENING | stop listening | Unsupported until capture lifecycle exists; microphone off |
| HELP | help; list commands | Enabled aliases with runtime limitations; text only |
| REPEAT_RESPONSE | repeat; repeat last response | Latest visible engine response, text only; no TTS |
| CANCEL | cancel | Pending interpretation, before execution |

Alias edits must be nonempty, bounded and unique after case/whitespace
normalization. Alias collisions must be rejected before persistence, including
aliases belonging to disabled commands. Fixed phrases overlapping parameter
templates must not make interpretation ambiguous. Unknown IDs and extra JSON
fields must be rejected; no arbitrary package names or intents are executable.

The implemented editor permits 1-12 aliases per command, each 1-100 characters,
with letters/numbers, spaces, apostrophes, hyphens and the command's required
placeholder (exactly once as a separate word). Case and whitespace are normalized.
Enabled flags and aliases apply without restarting. Matching is exact after
case/whitespace normalization; punctuation is not silently removed. All matching
templates, including disabled ones, participate in ambiguity detection. Partial
or fuzzy matches never execute. Percentages outside 0-100, non-integers and
missing parameters are invalid. Positive decimal FM syntax additionally requires
a verified adapter/band/step before tuning; production tune/seek are unsupported
and never reach hardware. No default regional band is invented.

Home accepts aliases such as `help`: Interpret, then Execute or Cancel. Interpretation
checks support without executing hardware actions. Unsupported/invalid commands
never enable Execute. Tokens are single-use and configuration is revalidated at
execution. The Cancel button and typed `cancel` invalidate pending work, not an
action already sent to Android. Test while stationary; microphone stays off.

Commands contains an installed-app picker and friendly-name editor. Select an
installed launcher app and save unique aliases before using `open maps` (only if
`maps` is saved). The allowlist defaults empty; package names are not inferred.
Uninstalled/nonlaunchable apps cannot execute. The device-specific allowlist is
excluded from portable imports/exports. Reset commands affects definitions only;
Reset app allowlist revokes apps, while Restore safe defaults clears both.

Media requires optional notification-listener access, linked from Commands and
Diagnostics after disclosure. Missing access returns `PermissionRequired`; no
session or unadvertised action returns `Unsupported`; multiple sessions return
`InvalidParameters` rather than choosing a player. Success means the transport
request was sent, not that playback or track changes were verified.

Results are `Success`, `Unsupported`, `PermissionRequired`, `InvalidParameters`,
`Failed` or `Cancelled`. Unknown/ambiguous/disabled commands are invalid. Fixed
volume is unsupported; observed volume no-ops or controller errors fail. A level
already at its requested minimum/maximum is not an error. Android music-stream
state does not prove audible OEM output. Refresh capabilities in Diagnostics;
test actual volume using Home. No stream selector is exposed without mixer evidence.

There is deliberately no force-stop command. A normal APK cannot promise to
force-stop other applications. Supported alternatives are pause or home where available.
No regional FM band is assumed before actual tuner evidence is available.