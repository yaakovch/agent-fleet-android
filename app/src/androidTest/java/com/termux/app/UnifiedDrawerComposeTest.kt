package com.termux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.DrawerLocalSession
import com.termux.app.fleet.DrawerRemoteSession
import com.termux.app.fleet.DrawerSessionSurface
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.UnifiedDrawerState
import com.termux.app.fleet.UnifiedTerminalDrawer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetDrawerComposeTest {
    @get:Rule val compose = createAndroidComposeRule<DrawerComposeTestHostActivity>()

    @Test
    fun tapUsesRememberedSurfaceAndSearchAppearsOnlyForLongLists() {
        val opened = mutableListOf<Pair<String, DrawerSessionSurface>>()
        val rows = (0..8).map { index ->
            remote("session-$index", pinned = index == 0, surface = if (index == 1) DrawerSessionSurface.Terminal else DrawerSessionSurface.Native)
        }
        setDrawer(
            UnifiedDrawerState(remoteSessions = rows, hostNames = mapOf("gaming" to "Gaming"), drawerOpen = true),
            onOpenRemote = { row, surface -> opened += row.session.id to surface }
        )

        compose.onNodeWithTag("drawer-search").assertIsDisplayed().performTextInput("Session 1")
        compose.onNodeWithTag("drawer-session-gaming:session-1").performClick()

        assertEquals(listOf("gaming:session-1" to DrawerSessionSurface.Terminal), opened)
    }

    @Test
    fun shortListHasNoSearchAndOfflineSessionOffersLocalRemoval() {
        var removed = ""
        val row = remote("offline", available = false)
        setDrawer(
            UnifiedDrawerState(remoteSessions = listOf(row), hostNames = mapOf("gaming" to "Gaming"), drawerOpen = true),
            onRemoveRemote = { removed = it.session.id }
        )

        compose.onAllNodesWithTag("drawer-search").assertCountEquals(0)
        compose.onAllNodesWithTag("drawer-session-offline").assertCountEquals(0)
        compose.onNodeWithTag("drawer-session-gaming:offline").assertIsDisplayed()
        compose.onNodeWithTag("drawer-session-more-gaming:offline").performClick()
        compose.onNodeWithText("Remove from phone").performClick()

        assertEquals("gaming:offline", removed)
    }

    @Test
    fun swipesPinAndRevealConfirmedKill() {
        var pinCount = 0
        var killed = ""
        val row = remote("swipe")
        setDrawer(
            UnifiedDrawerState(remoteSessions = listOf(row), hostNames = mapOf("gaming" to "Gaming"), drawerOpen = true),
            onTogglePin = { pinCount++ },
            onKillRemote = { killed = it.session.id }
        )

        compose.onNodeWithTag("drawer-session-gaming:swipe").performTouchInput { swipeRight() }
        assertEquals(1, pinCount)
        compose.onNodeWithTag("drawer-session-gaming:swipe").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("drawer-swipe-action-gaming:swipe").performClick()
        compose.onNodeWithTag("drawer-confirm-kill").performClick()

        assertEquals("gaming:swipe", killed)
    }

    @Test
    fun localShellAndGenericSettingsAreNotExposed() {
        val local = DrawerLocalSession("local-1", "Shell", "bash", true)
        setDrawer(UnifiedDrawerState(localSessions = listOf(local), drawerOpen = true))

        compose.onAllNodesWithTag("drawer-new-local").assertCountEquals(0)
        compose.onAllNodesWithTag("drawer-local-local-1").assertCountEquals(0)
        compose.onAllNodesWithTag("drawer-settings").assertCountEquals(0)
    }

    @Test
    fun compactDrawerShowsNineSessionsAndBottomUtilities() {
        val rows = (1..9).map { remote("dense-$it") }
        setDrawer(UnifiedDrawerState(remoteSessions = rows, hostNames = mapOf("gaming" to "Gaming"), drawerOpen = true))

        compose.onNodeWithTag("drawer-session-gaming:dense-1").assertIsDisplayed()
        compose.onNodeWithTag("drawer-session-gaming:dense-9").assertIsDisplayed()
        compose.onNodeWithTag("drawer-agent-fleet").assertIsDisplayed()
        compose.onNodeWithText("Keyboard").assertIsDisplayed()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
    }

    private fun setDrawer(
        state: UnifiedDrawerState,
        onOpenRemote: (DrawerRemoteSession, DrawerSessionSurface) -> Unit = { _, _ -> },
        onTogglePin: (DrawerRemoteSession) -> Unit = {},
        onKillRemote: (DrawerRemoteSession) -> Unit = {},
        onRemoveRemote: (DrawerRemoteSession) -> Unit = {},
        onCreateLocal: (Boolean, String?) -> Unit = { _, _ -> },
        onCloseLocal: (DrawerLocalSession) -> Unit = {}
    ) {
        compose.setContent {
            AgentFleetTheme {
                Box(Modifier.fillMaxSize()) {
                    UnifiedTerminalDrawer(
                        state = state,
                        onOpenRemote = onOpenRemote,
                        onTogglePin = onTogglePin,
                        onKillRemote = onKillRemote,
                        onCloseRemote = {},
                        onRemoveRemote = onRemoveRemote,
                        onOpenAgentFleetSession = {},
                        onRefresh = {},
                        onCloseLocal = onCloseLocal,
                        onCreateLocal = onCreateLocal,
                        onOpenAgentFleet = {},
                        onKeyboard = {},
                        onAppearance = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun remote(
        name: String,
        pinned: Boolean = false,
        surface: DrawerSessionSurface = DrawerSessionSurface.Native,
        available: Boolean = true
    ): DrawerRemoteSession {
        val session = FleetSession(
            id = "gaming:$name", hostId = "gaming", internalName = name,
            name = name.replace('-', ' ').replaceFirstChar(Char::uppercase), title = "Codex", project = "wtmux",
            tool = "codex", backend = "linux", activity = "active", attached = false,
            updatedAt = null, pendingScheduleCount = 0
        )
        return DrawerRemoteSession(session, pinned, 1, surface, available, cached = !available)
    }
}
