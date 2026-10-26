# Command Catalogue

Milestone 2 added typed deterministic execution; Milestone 3 adds push-to-talk
input and Milestone 4 adds radio integration. Command configuration is preserved,
and core execution requires no LLM or networking.
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
| OPEN_RADIO | open radio; launch radio; start radio | Explicit `com.nwd.radio/.home_horizontalActivity` launch |
| TUNE_FM | tune fm to {frequency}; tune to fm {frequency}; tune to {frequency} fm | 87.5-108.0 MHz; nearest 10 kHz, decimal `HALF_UP`; request only |
| SEEK_NEXT | next station; search next station; find the next FM station; tune to the next station | `ACTION_TEST_KEY`, key 5; station search request |
| SEEK_PREVIOUS | previous station; search previous station; find the previous station | `ACTION_TEST_KEY`, key 6; station search request |
| MUTE_RADIO | mute radio; mute the radio; silence the radio | `ACTION_SET_MUTE`, `extra_mute=true`; request only |
| UNMUTE_RADIO | unmute radio; restore radio sound; turn radio sound back on | `ACTION_SET_MUTE`, `extra_mute=false`; request only |
| AUTO_MEMORY_SCAN | scan and save stations; automatically store radio stations; run automatic station memory; find and save available stations | `ACTION_TEST_KEY`, key 46 (AMS); request only |
| RADIO_INTRO_SCAN | start radio intro scan; preview radio stations; start station preview | `ACTION_TEST_KEY`, key 61 (INTRO); request only |
| START_LISTENING | start listening | Installed engine/model, microphone permission |
| STOP_LISTENING | stop listening | Unsupported as a command; use the visible push-to-talk Finish/Cancel control |
| HELP | help; list commands | Enabled aliases with runtime limitations; visible text and optional offline summary |
| REPEAT_RESPONSE | repeat; repeat last response | Latest visible engine response; optional offline speech when enabled |
| CANCEL | cancel | Pending interpretation, before execution |

Alias edits must be nonempty, bounded and unique after case/whitespace
normalization. Alias collisions must be rejected before persistence, including
aliases belonging to disabled commands. Exact aliases take precedence over
parameter templates for the two reserved radio-launch phrases; the two FM word
orders are separate numeric templates. Other overlapping aliases are rejected.
Unknown IDs and extra JSON
fields must be rejected; no arbitrary package names or intents are executable.

The implemented editor permits 1-12 aliases per command, each 1-100 characters,
with letters/numbers, spaces, apostrophes, hyphens and the command's required
placeholder (exactly once as a separate word). Case and whitespace are normalized.
Enabled flags and aliases apply without restarting. Matching is exact after
case/whitespace normalization; punctuation is not silently removed. All matching
templates, including disabled ones, participate in ambiguity detection. Partial
or fuzzy matches never execute. Percentages outside 0-100, non-integers and
missing parameters are invalid. FM frequencies are limited to the configured
87.5-108.0 MHz range and rounded to the nearest 10 kHz using `HALF_UP`. The
standard range is an implementation configuration, not a measured regional tuner
plan. Intent dispatch does not prove receiver delivery or tuner state.

Typed Home input accepts aliases such as `help`: Interpret, then Execute or Cancel.
Interpretation checks support without executing hardware actions. Unsupported or
invalid commands never enable Execute. Tokens are single-use and configuration is
revalidated at execution. The Cancel button and typed `cancel` invalidate pending
work, not an action already sent to Android.

Home push-to-talk is a separate input path into the same `CommandDispatcher`. It
captures locally only after runtime microphone permission is granted, displays
partial text without execution, and submits one normalized final transcript through
the existing parser, validator, current command enablement and controller. A valid
voice command executes after preparation; partial, empty, unknown, ambiguous,
disabled or invalid transcripts do not dispatch actions. Typed development input
remains available for parser/execution testing without a microphone. Model setup
and offline voice requirements are in [Models](MODELS.md).

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
The production runtime uses `VerifiedRadioController` and `AndroidRadioDevice`.
The adapter uses the explicit radio activity, frequency action/extra, key 5/6/46/61,
and mute boolean action/extra. Local tests verify outgoing intents and request
mapping; they do not prove the normal APK is permitted by the receiver or that the
tuner completed an operation. All radio voice phrases above are available offline.

Hands-free uses the same command path after a separate Hey Nexa keyword event.
Pause after the wake phrase, then speak the command; wake audio is discarded and
not prefixed to the command transcript. The detector never calls radio controllers
or emits broadcasts. Verified launch, frequency extra, search 5/6, AMS 46, INTRO 61
and mute mappings are unchanged; frequency-step keys 26/27 are never search.
START/STOP_LISTENING aliases remain unsupported rather than bypassing validation;
use the persistent listening controls or foreground notification Stop action.