# Faster Fleet, October 2026

The coordinated change keeps conversation/scrollback/heartbeat wire formats and
adds contracts 1.14.0 for verified session identity and app-private saved state.
The runtime source is `aece51530efe0379ac5812c62d66a60bd68033ae`.

Android reduces frames in order on a worker, publishes immutable state per frame
with immediate authority/question/error publication, and moves JSON cache work,
process reaping and nonessential maintenance away from first interaction.
Markdown and file destinations are cached separately from current-session actions.

Message and matching live-question drafts remain local until successful Send or
explicit Clear. Identity includes the verified host, target, project, tool and
session incarnation. Restoration never grants input permission. Writes debounce
for 300 ms and flush on lifecycle/session changes. A late identity result cannot
override a user's newer view selection. Normal entry restores the reading anchor;
notification entry focuses the live question or newest activity. Attachments are
excluded from durable restoration.

History keeps four bounded captures and prepares its read-only emulator on a
worker. Unchanged revisions reuse the buffer. Reading stays stable until explicit
Refresh or return to live. A pane below tmux chrome may be shorter than the local
viewport; its wrapping width must still match. Real resizing invalidates the buffer.
Background captures retain the quiet delay and five-second minimum interval.

Profiles use AndroidX Benchmark 1.5.0 and ProfileInstaller 1.4.1, with lockfiles
and verified artifacts. The existing AGP/Compose versions remain pinned.
`benchmark` generates profiles; `measure` uses release R8 settings;
`measureWithoutFleetProfiles` omits Fleet rules and retains library profiles.
These activities exist only in measurement builds. Generated profile provenance
is in `fleet-profile-generation-v1.json`.

## Verified observations

Controller evidence is retained under
`~/.cache/agent-fleet/faster-fleet-20261007/`. It uses synthetic transcripts and
metadata, with ephemeral fixture credentials excluded from release artifacts.

| Scenario | Adopted baseline median / p95 | Candidate median / p95 |
|---|---:|---:|
| Android readable Native content, 20 openings | 2507 / 3119 ms | 2731 / 3061 ms |
| Android Native/Terminal switch, 20 taps | 106 / 148 ms | 70.5 / 108 ms |
| Windows readable Native content, 20 openings | 1225 / 1362 ms | 1181 / 1388 ms |
| Windows Native/Terminal switch, 20 taps | 19.3 / 32.8 ms | 17.4 / 28.2 ms |
| Host 13 MB later page 2, separate processes | 355 / 489 ms | 104 / 148 ms |
| Host 13 MB later page 3, separate processes | 675 / 791 ms | 173 / 213 ms |

Content timing includes pinned synthetic SSH and is separate from local Android
startup/frame benchmarks. Later-page warm medians improve 70.6% and 74.4%.
The measured history source is byte-identical to the final runtime history code.
Both production snapshot stores use 11 requests instead of 101 in their quiet
five-minute logical-clock fixtures (89.1% fewer), with immediate revision and
foreground refresh. This is a request-count fixture, not wall-clock phone timing.

Protected API 36 Android acceptance exercised draft reentry, fresh disk binding,
Clear, notification taps, replacement SSH attachment, real Send and History
entry/stability/Refresh/return to live. The draft reentry check is activity reentry,
not a full operating-system process restart. Windows also exercised a restarted
disk store and failed-send retention. Failed attempts remain retained; a repeated
Windows opening timed out intermittently before a later complete run passed.
No accumulating fixture channels were observed; the timeout cause is unconfirmed.

Final source-pinned profile comparisons, complete protected release gates,
signed updater acceptance and coordinated staging remain pending at this source
checkpoint. Physical-phone installation/performance are owner-operated and
adoption is unconfirmed. Active PC app/window restarts require the review checkpoint.
