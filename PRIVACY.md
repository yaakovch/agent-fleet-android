# Privacy

The private alpha is designed for one trusted owner and has no analytics,
telemetry, advertising identifier, cloud relay, or automatic crash upload.

Fleet APIs carry metadata only: hosts, projects, session names/titles, tools,
activity, schedules, quota percentages/reset times, health, and versions.
They must not carry terminal screen contents, prompts, responses, transcript
excerpts, authentication files, or service credentials.

Terminal scrollback and Android composer drafts are memory-only. Image files
selected by the user are copied to private temporary storage only long enough
to transfer them; successful local copies are deleted. Host copies default to
seven-day retention under the selected project's ignored `.wtmux/images/`.

Diagnostics are local, bounded, sanitized, previewable, and opt-in to export.

