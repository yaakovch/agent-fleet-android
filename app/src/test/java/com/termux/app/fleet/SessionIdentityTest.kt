package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionIdentityTest {
    private val session = FleetSession(
        id = "host:wtmux-demo-1", hostId = "host", internalName = "wtmux-demo-1",
        name = "demo:1", title = "Fix Android session titles", nameMode = "automatic",
        project = "demo", tool = "codex", backend = "linux", activity = "active",
        attached = true, updatedAt = null, pendingScheduleCount = 0
    )

    @Test fun automaticUsesSmartPrimaryAndStableSecondary() {
        assertEquals("Fix Android session titles", sessionIdentityPresentation(session).primary)
        assertEquals("demo:1 · host · demo", sessionIdentityPresentation(session).secondary)
    }

    @Test fun manualOverrideSuppressesAutomaticTitleInCompactUi() {
        val presentation = sessionIdentityPresentation(session.copy(name = "Release work", nameMode = "manual"))
        assertEquals("Release work", presentation.primary)
        assertEquals("host · demo", presentation.secondary)
    }

    @Test fun bridgeArgumentsArePrivacyGated() {
        assertEquals(listOf("--snapshot", "--session-titles"), snapshotBridgeArguments(true))
        assertEquals(listOf("--snapshot"), snapshotBridgeArguments(false))
    }
}
