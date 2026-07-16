package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceLayoutContractTest {
    @Test
    fun validatesSharedDesktopLayoutGoldenWithoutChangingPhonePresentation() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("workspace_layout_v1.json")).readText()
        val layout = decodeWorkspaceLayout(org.json.JSONObject(fixture))
        assertEquals(3, workspacePanes(layout.root).size)
        assertEquals("pane-2", layout.focusedPaneId)
        assertEquals(listOf("gaming-desktop-ubuntu:codex-one", "work-m-ubuntu:claude-two", null), workspacePanes(layout.root).map { it.sessionId })
        assertEquals(fixture.trim(), encodeWorkspaceLayout(layout).toString(2).trim())
    }

    @Test
    fun reducesUniqueAssignmentsSplitsPresetsAndBoundedRatios() {
        var layout = emptyWorkspaceLayout()
        val first = layout.focusedPaneId
        layout = WorkspaceReducer.assign(layout, first, "host:one")
        layout = WorkspaceReducer.split(layout, first, WorkspaceDirection.Row)
        val second = layout.focusedPaneId
        layout = WorkspaceReducer.assign(layout, second, "host:two")
        layout = WorkspaceReducer.swap(layout, first, second)
        assertEquals(listOf("host:two", "host:one"), workspacePanes(layout.root).map { it.sessionId })
        assertEquals(second, layout.focusedPaneId)
        layout = WorkspaceReducer.assign(layout, second, "host:one")
        assertEquals(second, layout.focusedPaneId)
        assertEquals(1, workspacePanes(layout.root).count { it.sessionId == "host:one" })
        val split = layout.root as WorkspaceSplit
        layout = WorkspaceReducer.resize(layout, split.id, 9f)
        assertEquals(0.8f, (layout.root as WorkspaceSplit).ratio)
        layout = WorkspaceReducer.preset(layout, WorkspacePreset.Grid)
        assertEquals(4, workspacePanes(layout.root).size)
        layout = WorkspaceReducer.split(layout, layout.focusedPaneId, WorkspaceDirection.Row)
        assertEquals(4, workspacePanes(layout.root).size)
    }

    @Test
    fun derivesStableFocusedPaneChromeForEmptyOpeningReadyAndUnavailableStates() {
        val empty = WorkspacePane("pane-one")
        assertEquals(
            WorkspacePaneChrome("Empty pane", "Choose a session from the rail", "N", "empty", false, false, false, false, false),
            workspacePaneChrome(empty, null, available = false)
        )
        val assigned = empty.copy(sessionId = "gaming:one")
        assertTrue(workspacePaneChrome(assigned, null, available = false).opening)
        val session = FleetSession(
            "gaming:one", "gaming", "one", "One", "codex", "project", "codex", "linux",
            "active", false, null, 0
        )
        val ready = workspacePaneChrome(assigned, session, available = true, "live", "Live")
        assertTrue(ready.nativeEnabled)
        assertTrue(ready.terminalEnabled)
        assertEquals("N", ready.modeBadge)
        val retry = workspacePaneChrome(assigned, session, available = true, "error", "Attachment failed")
        assertTrue(retry.retryVisible)
        assertTrue(!retry.nativeEnabled && !retry.terminalEnabled)
        val unavailable = workspacePaneChrome(assigned, session, available = false, "offline", "Offline")
        assertTrue(!unavailable.retryVisible && unavailable.hasSessionActions)
    }

    @Test
    fun selectsPresentationAtTheSharedDesktopBreakpoint() {
        assertTrue(!isDesktopPresentation(WorkspacePresentationMode.Auto, 839))
        assertTrue(isDesktopPresentation(WorkspacePresentationMode.Auto, 840))
        assertTrue(isDesktopPresentation(WorkspacePresentationMode.Desktop, 393))
        assertTrue(!isDesktopPresentation(WorkspacePresentationMode.Phone, 1200))
    }

    @Test
    fun localHideSurvivesOnlyWhileTheCachedHostIsUnavailable() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agent_fleet_workspace_v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val store = AndroidWorkspaceStore(context)
        val session = FleetSession(
            "gaming:one", "gaming", "one", "One", "codex", "project", "codex", "linux",
            "active", false, null, 0
        )
        val offline = FleetSnapshot("one", "", listOf(FleetHost("gaming", "Gaming", "offline", "wsl", null, emptySet())), listOf(session), emptyList(), emptyList())
        val hidden = AndroidWorkspaceState(emptyWorkspaceLayout(), hiddenUnavailableSessionIds = setOf(session.id))
        assertEquals(setOf(session.id), store.reconcile(offline, hidden).hiddenUnavailableSessionIds)
        val healthy = offline.copy(hosts = offline.hosts.map { it.copy(status = "healthy") })
        assertTrue(store.reconcile(healthy, hidden).hiddenUnavailableSessionIds.isEmpty())
    }
}
