package com.termux.app.fleet

data class WorkspacePaneChrome(
    val title: String,
    val context: String,
    val modeBadge: String,
    val status: String,
    val nativeEnabled: Boolean,
    val terminalEnabled: Boolean,
    val retryVisible: Boolean,
    val hasSessionActions: Boolean,
    val opening: Boolean
)

fun workspacePaneChrome(
    pane: WorkspacePane,
    session: FleetSession?,
    available: Boolean,
    attachmentStatus: String = "connecting",
    attachmentMessage: String = "Opening session…"
): WorkspacePaneChrome {
    val modeBadge = if (pane.viewMode == WorkspaceViewMode.Native) "N" else "T"
    if (pane.sessionId == null) return WorkspacePaneChrome(
        "Empty pane", "Choose a session from the rail", modeBadge, "empty",
        nativeEnabled = false, terminalEnabled = false, retryVisible = false,
        hasSessionActions = false, opening = false
    )
    if (session == null) return WorkspacePaneChrome(
        "Opening session…", "Waiting for the session descriptor", modeBadge, "connecting",
        nativeEnabled = false, terminalEnabled = false, retryVisible = false,
        hasSessionActions = false, opening = true
    )
    if (!available) return WorkspacePaneChrome(
        sessionIdentityPresentation(session).primary, "${session.hostId} · Host unavailable", modeBadge, "offline",
        nativeEnabled = false, terminalEnabled = false, retryVisible = false,
        hasSessionActions = true, opening = false
    )
    val ready = attachmentStatus == "live" || attachmentStatus == "ended"
    return WorkspacePaneChrome(
        sessionIdentityPresentation(session).primary,
        "${session.hostId} · ${session.project} · $attachmentMessage",
        modeBadge,
        attachmentStatus,
        nativeEnabled = ready && session.tool != "shell",
        terminalEnabled = ready,
        retryVisible = attachmentStatus == "error" || attachmentStatus == "offline",
        hasSessionActions = true,
        opening = !ready && attachmentStatus != "error"
    )
}
