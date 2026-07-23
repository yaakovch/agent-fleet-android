package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun inheritedTitleIsWordSafelyCappedWithoutChangingManualNames() {
        val longTitle = "Use the reclaimed native session space without allowing inherited titles to crowd the controls"
        val automatic = sessionIdentityPresentation(session.copy(title = longTitle))
        assertEquals("Use the reclaimed native session space…", automatic.primary)
        assertTrue(automatic.primary.length <= MAX_INHERITED_SESSION_TITLE_CHARS)
        assertEquals(longTitle, sessionIdentityPresentation(
            session.copy(name = longTitle, title = "ignored", nameMode = "manual")
        ).primary)
        val emoji = inheritedSessionTitle("🙂".repeat(60))
        assertEquals(48, emoji.codePointCount(0, emoji.length))
        assertTrue(emoji.endsWith("…"))
    }

    @Test fun bridgeArgumentsArePrivacyGated() {
        assertEquals(listOf("--snapshot", "--identity-graph", "--session-titles"), snapshotBridgeArguments(true))
        assertEquals(listOf("--snapshot", "--identity-graph"), snapshotBridgeArguments(false))
    }

    @Test fun oldBridgeFallsBackWithoutBreakingFleetConnection() {
        val oldHelp = "usage: wtmux-bridge [-h] [--snapshot] [--stdio]"
        assertEquals(false, bridgeHelpSupportsSessionTitles(0, oldHelp))
        assertEquals(false, bridgeHelpSupportsIdentityGraph(0, oldHelp))
        assertEquals(
            listOf("--snapshot"),
            snapshotBridgeArguments(titlesEnabled = true, titlesSupported = false, identityGraphSupported = false)
        )
        assertEquals(
            listOf("--stdio"),
            stdioBridgeArguments(titlesEnabled = true, titlesSupported = false, identityGraphSupported = false)
        )
    }

    @Test fun compatibleBridgeKeepsAutomaticTitlesEnabled() {
        val currentHelp = "usage: wtmux-bridge [--snapshot] [--stdio] [--identity-graph] [--session-titles]"
        assertEquals(true, bridgeHelpSupportsSessionTitles(0, currentHelp))
        assertEquals(true, bridgeHelpSupportsIdentityGraph(0, currentHelp))
        assertEquals(listOf("--snapshot", "--identity-graph", "--session-titles"), snapshotBridgeArguments(true, true, true))
        assertEquals(listOf("--stdio", "--identity-graph", "--session-titles"), stdioBridgeArguments(true, true, true))
    }

    @Test fun physicalTargetUsesTheCorrectLegacyTransportAlias() {
        val linuxHost = FleetHost("gaming", "Gaming", "healthy", "wsl", null, emptySet())
        val windowsHost = linuxHost.copy(id = "gaming_windows")
        val physical = FleetPhysicalHost(
            "gaming", "Gaming", "wsl", "healthy", null, "", emptyList(),
            listOf("linux", "windows"), listOf("gaming", "gaming_windows")
        )
        val snapshot = FleetSnapshot(
            "revision", "", listOf(linuxHost, windowsHost), emptyList(), emptyList(), emptyList(),
            physicalHosts = listOf(physical),
            executionTargets = listOf(
                FleetExecutionTarget("linux", "gaming", "linux", "WSL", "available", ""),
                FleetExecutionTarget("windows", "gaming", "windows-git-bash", "Windows", "available", "")
            )
        )
        assertEquals("gaming", transportHostId(snapshot, "gaming", "linux"))
        assertEquals("gaming_windows", transportHostId(snapshot, "gaming", "windows"))
    }

    @Test fun changedEndpointIdentityRequiresVisibleRecovery() {
        val host = FleetPhysicalHost(
            "gaming", "Gaming", "wsl", "healthy", null, "", listOf("endpoint"),
            listOf("linux"), listOf("gaming")
        )
        val snapshot = FleetSnapshot(
            "revision", "", listOf(FleetHost("gaming", "Gaming", "healthy", "wsl", null, emptySet())),
            emptyList(), emptyList(), emptyList(),
            physicalHosts = listOf(host),
            endpoints = listOf(
                FleetEndpoint(
                    "endpoint", "gaming", "tailnet", "gaming.example.ts.net", 22, "openssh",
                    "tailnet-ssh", "healthy", "reverify-required", "SHA256:changed", "node", ""
                )
            ),
            executionTargets = listOf(FleetExecutionTarget("linux", "gaming", "linux", "WSL", "available", ""))
        )
        assertEquals(
            "OpenSSH over Tailnet · Endpoint identity needs verification",
            physicalHostRecoveryDetail(snapshot, host)
        )
        assertEquals(
            null,
            physicalHostRecoveryDetail(
                snapshot.copy(endpoints = snapshot.endpoints.map { it.copy(identityState = "verified") }),
                host
            )
        )
    }
}
