package com.termux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.AgentFleetDiagnosticCheck
import com.termux.app.fleet.AgentFleetDiagnosticReport
import com.termux.app.fleet.AgentFleetDisplayDensity
import com.termux.app.fleet.AgentFleetDisplayDensityStore
import com.termux.app.fleet.ConversationAnswer
import com.termux.app.fleet.ConversationItem
import com.termux.app.fleet.ConversationQuestion
import com.termux.app.fleet.ConversationQuestionOption
import com.termux.app.fleet.ConversationTask
import com.termux.app.fleet.DiagnosticStatus
import com.termux.app.fleet.DiagnosticsUiState
import com.termux.app.fleet.EmbeddedRuntimeStatus
import com.termux.app.fleet.FleetAttention
import com.termux.app.fleet.FleetAlert
import com.termux.app.fleet.FleetAlertCategory
import com.termux.app.fleet.FleetAlertSettings
import com.termux.app.fleet.FleetAlertTarget
import com.termux.app.fleet.FleetPairingRequest
import com.termux.app.fleet.FleetPairingReview
import com.termux.app.fleet.FleetDirectoryListing
import com.termux.app.fleet.FleetDownloadCancellation
import com.termux.app.fleet.FleetDownloadState
import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.FleetPhysicalHost
import com.termux.app.fleet.FleetUnavailableException
import com.termux.app.fleet.FleetLoadState
import com.termux.app.fleet.FleetModelControlState
import com.termux.app.fleet.FleetModelEffortOption
import com.termux.app.fleet.FleetModelOption
import com.termux.app.fleet.FleetModelSelection
import com.termux.app.fleet.FleetRepositoryEntry
import com.termux.app.fleet.FleetRepositoryPage
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.NativeSessionScreen
import com.termux.app.fleet.NativeSessionRegistry
import com.termux.app.fleet.NativeSessionUiState
import com.termux.app.fleet.ProviderActivity
import com.termux.app.fleet.ProviderComponent
import com.termux.app.fleet.ProviderState
import com.termux.app.fleet.AgentFleetTerminalSessionChrome
import com.termux.app.fleet.ToolPresentation
import com.termux.app.fleet.ToolPresentationBlock
import com.termux.app.fleet.UpdateUiState
import com.termux.app.fleet.LocalModelUiState
import com.termux.app.fleet.LocalSuggestionClient
import com.termux.app.fleet.LocalSuggestionRuntime
import com.termux.app.fleet.LocalSuggestionMode
import com.termux.app.fleet.AgentFleetComposerContent
import com.termux.app.fleet.AgentFleetComposerNativeState
import com.termux.app.fleet.AndroidWorkspaceState
import com.termux.app.fleet.WorkspaceTerminalBroker
import com.termux.app.fleet.WorkspaceTerminalBinding
import com.termux.app.fleet.WorkspacePreset
import com.termux.app.fleet.WorkspaceReducer
import com.termux.app.fleet.WorkspaceSplit
import com.termux.app.fleet.WorkspaceViewMode
import com.termux.app.fleet.agentFleetWorkspaceTarget
import com.termux.app.fleet.emptyWorkspaceLayout
import com.termux.app.fleet.workspacePanes
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun discoveredHostRequiresReviewAndPairsAndRepairsExactlyOnce() {
        val pairs = AtomicInteger()
        val repairs = AtomicInteger()
        val changes = AtomicInteger()
        val service = object : com.termux.app.fleet.FleetHostSetup {
            override fun discover() = listOf(com.termux.app.fleet.TailnetHost(
                "node-new", "New host", "new.tailnet.ts.net", "linux", true, ""))
            override fun review(nodeId: String, username: String): com.termux.app.fleet.TailnetHostReview {
                assertEquals("node-new", nodeId)
                assertEquals("tester", username)
                return com.termux.app.fleet.TailnetHostReview("a".repeat(32), "New host", "new.tailnet.ts.net", username, false, true)
            }
            override fun pair(reviewId: String): String { assertEquals("a".repeat(32), reviewId); pairs.incrementAndGet(); return "new-linux" }
            override fun repair(hostId: String) { assertEquals("new-linux", hostId); repairs.incrementAndGet() }
        }
        compose.setContent { AgentFleetTheme(darkTheme = true) {
            HostSetupDialog(onDismiss = {}, onChanged = { changes.incrementAndGet() }, setup = service)
        } }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("host-setup-select-node-new").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("host-setup-select-node-new").performScrollTo().performClick()
        compose.onNodeWithTag("host-setup-username").performTextInput("tester")
        compose.onNodeWithTag("host-setup-review").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("host-setup-pair").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, pairs.get())
        compose.onNodeWithText("tester@new.tailnet.ts.net").assertIsDisplayed()
        discoveryScreenshot("tailnet-host-review")
        compose.onNodeWithTag("host-setup-pair").performScrollTo().performClick()
        compose.waitUntil(20_000) { pairs.get() == 1 && changes.get() == 1 }
        compose.onNodeWithTag("host-setup-repair-confirm").performScrollTo().performClick()
        compose.waitUntil(20_000) { repairs.get() == 1 && changes.get() == 2 }
        compose.onNodeWithText("Host runtime repaired; reconnecting").assertIsDisplayed()
        discoveryScreenshot("tailnet-host-repaired")
        assertEquals(1, pairs.get())
        assertEquals(1, repairs.get())
    }

    @Test
    fun configurationFailureOffersRepairWithoutRequiringPairing() {
        val state = mutableStateOf(com.termux.app.fleet.FleetLoadState.Unavailable(
            "Saved fleet information needs repair. Your trusted hosts are retained.", "REGISTRY_INVALID"
        ))
        val repairs = AtomicInteger()
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp)) {
                    FleetUnavailableCard(state.value, onRefresh = {
                        repairs.incrementAndGet()
                        state.value = state.value.copy(recovering = true, reason = "Restoring your saved fleet information…")
                    }, onPair = { error("Existing fleet recovery must not request pairing") })
                }
            }
        }
        compose.onNodeWithText("Fleet configuration unavailable").assertIsDisplayed()
        compose.onNodeWithText("Pair").assertDoesNotExist()
        compose.onNodeWithText("Repair and retry").performClick()
        assertEquals(1, repairs.get())
        compose.onNodeWithText("Repair and retry").assertIsNotEnabled()
        compose.onNodeWithText("Restoring your saved fleet information…").assertIsDisplayed()
        discoveryScreenshot("configuration-recovery")
    }

    @Test
    fun cachedHostRecoversThroughThePackagedBridgeAndEnablesItsSession() {
        LocalFleetDiscoveryProbe(ApplicationProvider.getApplicationContext(), legacyAdvertisement = true).use { probe ->
            val live = probe.snapshot()
            assertEquals(1, live.sessions.size)
            val state = mutableStateOf(com.termux.app.fleet.staleFleetSnapshot(live, "NETWORK_UNREACHABLE"))
            val opens = AtomicInteger()
            var connectionEvidence = ""
            compose.setContent { FixtureApp(fleetSnapshot = state.value,
                onRefresh = { state.value = probe.snapshot() },
                onOpenSession = {
                    connectionEvidence = probe.confirmConnection(it)
                    probe.openTerminal(it)
                    opens.incrementAndGet()
                }) }
            val session = live.sessions.single()
            compose.onNodeWithTag("fleet-cache-status").assertIsDisplayed()
            compose.onNodeWithTag("host-failure-emulator-host").assertIsDisplayed()
            compose.onNodeWithTag("session-open-${session.id}").assertIsNotEnabled()
            discoveryScreenshot("discovery-cached")
            compose.onNodeWithText("Retry").performClick()
            compose.onNodeWithTag("host-status-emulator-host").assertIsDisplayed()
            compose.onNodeWithTag("session-${session.id}").assertIsDisplayed()
            discoveryScreenshot("discovery-recovered")
            compose.onNodeWithTag("session-open-${session.id}").assertIsEnabled().performClick()
            assertEquals(1, opens.get())
            connectionEvidence = probe.confirmUsableTerminal(connectionEvidence)
            discoveryScreenshot("discovery-terminal", waitForCompose = false)
            androidx.test.platform.io.PlatformTestStorageRegistry.getInstance().openOutputFile("discovery-connection.json").use {
                it.write(connectionEvidence.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun discoveryScreenshot(name: String, waitForCompose: Boolean = true) {
        if (waitForCompose) compose.waitForIdle()
        else {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            // Terminal output is asserted in the emulator buffer first; allow
            // its scheduled draw to reach the captured display frame as well.
            android.os.SystemClock.sleep(250)
            instrumentation.waitForIdleSync()
        }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        androidx.test.platform.io.PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        androidx.test.platform.io.PlatformTestStorageRegistry.getInstance().openOutputFile("$name-observation.json").use {
            it.write(org.json.JSONObject().put("observedAt", java.time.Instant.now().toString())
                .put("screen", name).toString().toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun modelAndEffortPickerIsSharedByNativeAndTerminalChrome() {
        val applied = AtomicInteger()
        val modelState = FleetModelControlState(
            sessionId = "gaming:wtmux-main", configRevision = "0123456789abcdef", tool = "codex", status = "ready",
            selected = FleetModelSelection("auto", "Auto", "automatic", "Automatic"), effective = null, pending = null,
            catalog = listOf(
                FleetModelOption("auto", "Auto", "Provider default", true, listOf(FleetModelEffortOption("automatic", "Automatic")), "automatic"),
                FleetModelOption("provider/model-2", "Provider Model 2", "Discovered on host", false,
                    listOf(FleetModelEffortOption("low", "Low"), FleetModelEffortOption("high", "High")), "high")
            ),
            customAllowed = true, detail = ""
        )
        val state = NativeSessionUiState(
            "Model fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
            modelControl = modelState
        )
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                AgentFleetTerminalSessionChrome(
                    state, {}, {}, { _, _, _, acknowledged -> if (acknowledged) applied.incrementAndGet() }, {}
                )
            }
        }
        compose.onNodeWithTag("model-control-chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("model-control-dialog").assertIsDisplayed()
        compose.onNodeWithText("Auto").performClick()
        compose.onNodeWithText("Provider Model 2").assertIsDisplayed()
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()
        assertEquals(1, applied.get())
    }

    @Test
    fun terminalChromeUsesTwoReadableRowsAndActions() {
        val state = NativeSessionUiState(
            "Working fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live"
        )
        var controlC = 0
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().height(176.dp)) {
                    AgentFleetTerminalSessionChrome(
                        state, {}, {}, { _, _, _, _ -> }, {}, onControlC = { controlC++ }
                    )
                }
            }
        }
        compose.onNodeWithTag("terminal-session-chrome").assertIsDisplayed().assertHeightIsEqualTo(96.dp)
        compose.onNodeWithTag("compact-session-identity-row").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("compact-session-control-row").assertHeightIsEqualTo(48.dp)
        val identity = compose.onNodeWithTag("compact-session-identity-row").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("compact-session-control-row").fetchSemanticsNode().boundsInRoot
        assertTrue(identity.bottom <= controls.top)
        compose.onNodeWithText("Working fixture").assertIsDisplayed()
        compose.onNodeWithText("Codex · Live").assertIsDisplayed()
        compose.onNodeWithText("Native").assertIsDisplayed()
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Ctrl+C").performClick()
        compose.runOnIdle { assertEquals(1, controlC) }
    }

    @Test
    fun nativeHeaderProtectsLongIdentityAndModelAtMaximumBodyDensity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalDensity = AgentFleetDisplayDensityStore.load(context)
        val title = "Gaming desktop · long implementation conversation"
        val modelLabel = "gpt-5.6-solo · Extra high"
        val modelState = FleetModelControlState(
            sessionId = "gaming:wtmux-main", configRevision = "0123456789abcdef", tool = "codex", status = "ready",
            selected = FleetModelSelection("gpt-5.6-solo", "gpt-5.6-solo", "xhigh", "Extra high"),
            effective = null, pending = null, catalog = emptyList(), customAllowed = true, detail = ""
        )
        try {
            AgentFleetDisplayDensityStore.save(
                context,
                originalDensity.copy(nativeBodySp = AgentFleetDisplayDensity.MAX_NATIVE_BODY_SP)
            )
            compose.setContent {
                NativeStateFixture(
                    NativeSessionUiState(
                        title, "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                        modelControl = modelState
                    )
                )
            }

            compose.onNodeWithTag("native-session-header").assertIsDisplayed().assertHeightIsEqualTo(96.dp)
            compose.onNodeWithTag("compact-session-identity-row").assertHeightIsEqualTo(48.dp)
            compose.onNodeWithTag("compact-session-control-row").assertHeightIsEqualTo(48.dp)
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText("Codex · Live").assertIsDisplayed()
            compose.onNodeWithText(modelLabel).assertIsDisplayed()
            compose.onNodeWithText("Actions").assertIsDisplayed()
            compose.onNodeWithText("Terminal").assertIsDisplayed()

            val identity = compose.onNodeWithTag("compact-session-identity-row").fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("compact-session-control-row").fetchSemanticsNode().boundsInRoot
            val model = compose.onNodeWithTag("model-control-chip").fetchSemanticsNode().boundsInRoot
            val viewSwitch = compose.onNodeWithTag("compact-session-view-switch").fetchSemanticsNode().boundsInRoot
            assertTrue(identity.bottom <= controls.top)
            assertTrue(model.right <= viewSwitch.left)
        } finally {
            AgentFleetDisplayDensityStore.save(context, originalDensity)
        }
    }

    @Test
    fun nativeHeaderRowsDoNotOverlapAtThePhoneFontScale() {
        val state = NativeSessionUiState(
            "wtmux:1", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
            optimisticWorkStartedAt = System.currentTimeMillis() - 70L * 60L * 1_000L,
            modelControl = FleetModelControlState(
                sessionId = "gaming:wtmux-main", configRevision = "0123456789abcdef", tool = "codex", status = "ready",
                selected = FleetModelSelection("gpt-5.6-sol", "gpt-5.6-sol", "xhigh", "Xhigh"),
                effective = null, pending = null, catalog = emptyList(), customAllowed = true, detail = ""
            )
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                NativeStateFixture(state, applyStatusBarInset = false)
            }
        }

        compose.onNodeWithTag("native-session-header").assertHeightIsEqualTo(96.dp)
        compose.onNodeWithTag("compact-session-identity-row").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("compact-session-control-row").assertHeightIsEqualTo(48.dp)
        val header = compose.onNodeWithTag("native-session-header").fetchSemanticsNode().boundsInRoot
        val identity = compose.onNodeWithTag("compact-session-identity-row").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("native-session-status").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("compact-session-control-row").fetchSemanticsNode().boundsInRoot
        val feed = compose.onNodeWithTag("native-conversation-feed").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom <= identity.bottom)
        assertTrue(identity.bottom <= controls.top)
        assertTrue(controls.bottom <= header.bottom)
        assertTrue(header.bottom <= feed.top)
    }

    @Test
    fun nativeHeaderUsesTheTopOfItsInsetAwareActivityWindow() {
        compose.setContent {
            NativeStateFixture(
                NativeSessionUiState(
                    "wtmux:1", "gaming", "wtmux-main", adapter = "codex", connection = "Live"
                ),
                applyStatusBarInset = false
            )
        }
        val header = compose.onNodeWithTag("native-session-header").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, header.top, 0.5f)
    }

    @Test
    fun terminalHeaderTransitionsFromAProviderWorkingEventToCompletedDuration() {
        val state = mutableStateOf(
            NativeSessionUiState("wtmux:1", "gaming", "wtmux-main", adapter = "codex", connection = "Live")
        )
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                AgentFleetTerminalSessionChrome(state.value, {}, {}, { _, _, _, _ -> }, {})
            }
        }
        compose.onNodeWithText("Codex · Live").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(ConversationItem(
                id = "working", kind = "status", timestamp = "2026-07-19T11:23:18Z", role = "",
                title = "Working", text = "", detail = "", state = "running", tool = "codex",
                attachments = emptyList(), choices = emptyList()
            )))
        }
        compose.onNodeWithText("Codex · Working (", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(items = state.value.items + ConversationItem(
                id = "done", kind = "status", timestamp = "2026-07-19T11:31:00Z", role = "",
                title = "Done", text = "", detail = "", state = "complete", tool = "codex",
                attachments = emptyList(), choices = emptyList(),
                startedAt = "2026-07-19T11:23:20Z", completedAt = "2026-07-19T11:31:00Z"
            ))
        }
        compose.onNodeWithText("Codex · Worked for 7m 40s").assertIsDisplayed()
    }

    @Test
    fun nativeShowsLiveWorkingTime() {
        val state = NativeSessionUiState(
            "Working fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
            optimisticWorkStartedAt = System.currentTimeMillis() - 2_000L
        )
        compose.setContent { NativeStateFixture(state) }
        compose.onNodeWithText("Codex · Working (", substring = true).assertIsDisplayed()
    }

    @Test
    fun nativeKeepsTheCompactCodexPhaseAndHostCounterVisible() {
        val state = NativeSessionUiState(
            "Working fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
            providerActivity = ProviderActivity(
                "Waiting for background terminal", 617,
                "2026-07-19T17:00:00Z", System.currentTimeMillis()
            ),
            providerActivityAuthoritative = true
        )
        compose.setContent { NativeStateFixture(state) }
        compose.onNodeWithText("Codex · Terminal wait · 10m", substring = true).assertIsDisplayed()
    }

    @Test
    fun nativeShowsCompletedWorkingTimeInVisibleStatusCard() {
        val user = ConversationItem(
            id = "user", kind = "message", timestamp = "2026-07-19T00:00:00Z", role = "user",
            title = "", text = "Fix it", detail = "", state = "complete", tool = "",
            attachments = emptyList(), choices = emptyList()
        )
        val working = ConversationItem(
            id = "working", kind = "status", timestamp = "2026-07-19T00:00:01Z", role = "",
            title = "Working", text = "", detail = "", state = "running", tool = "codex",
            attachments = emptyList(), choices = emptyList()
        )
        val reply = ConversationItem(
            id = "reply", kind = "message", timestamp = "2026-07-19T00:07:39Z", role = "assistant",
            title = "", text = "Fixed.", detail = "", state = "complete", tool = "codex",
            attachments = emptyList(), choices = emptyList()
        )
        val done = ConversationItem(
            id = "done", kind = "status", timestamp = "2026-07-19T00:08:41Z", role = "",
            title = "Done", text = "", detail = "", state = "complete", tool = "codex",
            attachments = emptyList(), choices = emptyList(),
            startedAt = "2026-07-19T00:01:00Z", completedAt = "2026-07-19T00:08:40Z"
        )
        val state = NativeSessionUiState(
            "Completed fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
            items = listOf(user, working, reply, done)
        )

        compose.setContent { NativeStateFixture(state) }

        compose.onNodeWithText("Worked for 7m 40s").assertIsDisplayed()
    }

    @Test
    fun appUpdatesIsTheFirstMoreCard() {
        compose.setContent { FixtureApp() }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("app-updates").assertIsDisplayed()
        val updatesTop = compose.onNodeWithTag("app-updates").fetchSemanticsNode().boundsInRoot.top
        val layoutTop = compose.onNodeWithTag("window-layout-settings").fetchSemanticsNode().boundsInRoot.top
        assertTrue(updatesTop < layoutTop)
    }

    @Test
    fun foregroundFleetAlertOpensTheExactSessionAndDismisses() {
        val opened = AtomicInteger()
        val dismissed = AtomicInteger()
        compose.setContent {
            FixtureApp(
                fleetAlerts = listOf(FleetAlert(
                    FleetAlertCategory.DeliveryFailures,
                    "Scheduled continue failed",
                    "Delivery could not be completed.",
                    FleetAlertTarget.Session(session.id)
                )),
                onOpenSession = { if (it.id == session.id) opened.incrementAndGet() },
                onDismissFleetAlert = { dismissed.incrementAndGet() }
            )
        }

        compose.onNodeWithTag("fleet-alert-banner").assertIsDisplayed()
        compose.onNodeWithText("Scheduled continue failed").assertIsDisplayed()
        compose.onNodeWithTag("fleet-alert-open").performClick()
        assertEquals(1, opened.get())
        assertEquals(1, dismissed.get())
    }

    @Test
    fun foregroundFleetAlertRespectsTheSafeTopInset() {
        val expectedTop = AtomicInteger()
        compose.setContent {
            val density = LocalDensity.current
            expectedTop.set(WindowInsets.safeDrawing.getTop(density) + with(density) { 16.dp.roundToPx() })
            FixtureApp(
                fleetAlerts = listOf(
                    FleetAlert(
                        FleetAlertCategory.HostState,
                        "Host offline",
                        "Live actions are paused.",
                        FleetAlertTarget.Host("gaming")
                    )
                )
            )
        }

        val top = compose.onNodeWithTag("fleet-alert-banner").fetchSemanticsNode().boundsInRoot.top
        assertTrue("banner top=$top expected>=${expectedTop.get()}", top >= expectedTop.get())
    }

    @Test
    fun dismissedPairingAlertRemainsRecoverableFromMore() {
        val alerts = mutableStateOf(listOf(pairingAlert("pair-1", FleetAlertTarget.PairingReview("pair-1"))))
        val reviewed = AtomicReference("")
        val fleetSnapshot = snapshot.copy(pairingRequests = listOf(pairingRequest("pair-1", "Phone one")))
        compose.setContent {
            FixtureApp(
                fleetSnapshot = fleetSnapshot,
                fleetAlerts = alerts.value,
                onDismissFleetAlert = { alerts.value = emptyList() },
                onReviewPairingRequest = reviewed::set
            )
        }

        compose.onNodeWithTag("fleet-alert-dismiss").performClick()
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-screen")
            .performScrollToNode(hasTestTag("pairing-request-review-pair-1"))
        compose.onNodeWithTag("pairing-request-review-pair-1")
            .assertContentDescriptionEquals("Review exact proposal from Phone one")
            .performClick()

        compose.runOnIdle { assertEquals("pair-1", reviewed.get()) }
    }

    @Test
    fun groupedPairingAlertRoutesToPersistentExactReviewList() {
        val alerts = mutableStateOf(listOf(pairingAlert("grouped", FleetAlertTarget.Dashboard)))
        val reviewed = AtomicReference("")
        val fleetSnapshot = snapshot.copy(
            pairingRequests = listOf(
                pairingRequest("pair-1", "Phone one"),
                pairingRequest("pair-2", "Tablet two")
            )
        )
        compose.setContent {
            FixtureApp(
                fleetSnapshot = fleetSnapshot,
                fleetAlerts = alerts.value,
                onDismissFleetAlert = { alerts.value = emptyList() },
                onReviewPairingRequest = reviewed::set
            )
        }

        compose.onNodeWithTag("fleet-alert-open").performClick()
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.onNodeWithTag("awaiting-pairing-reviews-highlight").assertIsDisplayed()
        compose.onNodeWithTag("more-screen")
            .performScrollToNode(hasTestTag("pairing-request-review-pair-2"))
        compose.onNodeWithTag("pairing-request-review-pair-2").performClick()

        compose.runOnIdle { assertEquals("pair-2", reviewed.get()) }
    }

    @Test
    fun legacyHostAlertScrollsToAndHighlightsExactPhysicalHost() {
        val fillers = List(12) { index -> physicalHost("filler-$index", listOf("legacy-filler-$index")) }
        val target = physicalHost("gaming-pc", listOf("gaming", "gaming_windows"), name = "Gaming PC")
        val alerts = mutableStateOf(
            listOf(
                FleetAlert(
                    FleetAlertCategory.HostState,
                    "Gaming PC is offline",
                    "The host is unavailable.",
                    FleetAlertTarget.Host("gaming_windows")
                )
            )
        )
        compose.setContent {
            FixtureApp(
                fleetSnapshot = snapshot.copy(physicalHosts = fillers + target),
                fleetAlerts = alerts.value,
                onDismissFleetAlert = { alerts.value = emptyList() }
            )
        }

        compose.onNodeWithTag("fleet-alert-open").performClick()
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.onNodeWithTag("fleet-host-gaming-pc").assertIsDisplayed()
        compose.onNodeWithTag("fleet-host-gaming-pc-highlight").assertIsDisplayed()
        compose.onNodeWithText("Gaming PC").assertIsDisplayed()

        compose.onNodeWithTag("nav-sessions").performClick()
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("app-updates").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("fleet-host-gaming-pc")).assertCountEquals(0)
    }

    @Test
    fun fleetAlertSettingsExposeSixIndependentForegroundControlsAndPause() {
        val settings = mutableStateOf(FleetAlertSettings())
        val pauses = AtomicInteger()
        compose.setContent {
            FixtureApp(
                fleetAlertSettings = settings.value,
                onFleetAlertSettings = { settings.value = it },
                onPauseFleetAlerts = { pauses.incrementAndGet() }
            )
        }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-screen").performScrollToNode(hasTestTag("fleet-alert-settings"))
        listOf("hardLimits", "deliveryFailures", "deliverySuccess", "hostState", "versionDrift", "pairing")
            .forEach { compose.onNodeWithTag("fleet-alert-$it").assertIsDisplayed() }
        compose.onNodeWithTag("fleet-alert-deliverySuccess")
            .assertContentDescriptionEquals("Delivery success")
        compose.onNodeWithTag("fleet-alert-deliverySuccess").performClick()
        compose.runOnIdle { assertTrue(!settings.value.deliverySuccess) }
        compose.onNodeWithTag("fleet-alert-pause").performClick()
        assertEquals(1, pauses.get())
        compose.onNodeWithTag("more-screen").performScrollToNode(hasTestTag("automatic-session-titles"))
        compose.onNodeWithTag("automatic-session-titles")
            .assertContentDescriptionEquals("Automatic coding-session titles")
        compose.onNodeWithTag("more-screen").performScrollToNode(hasTestTag("native-session-view"))
        compose.onNodeWithTag("native-session-view")
            .assertContentDescriptionEquals("Native session view")
    }

    @Test
    fun pairingAlertReviewShowsTheExactProposalBeforeDecision() {
        val rejected = AtomicInteger()
        compose.setContent {
            FixtureApp(
                pairingReviewUi = PairingReviewUiState(
                    requestId = "pair-1",
                    review = FleetPairingReview(
                        "pair-1", "Phone", "Android", "phone.tailnet.ts.net", "100.64.0.10",
                        "{\n  \"id\": \"phone-1\",\n  \"roles\": [\"client\"]\n}"
                    )
                ),
                onDecidePairingRequest = { id, approve ->
                    if (id == "pair-1" && !approve) rejected.incrementAndGet()
                }
            )
        }

        compose.onNodeWithTag("pairing-review-proposal").assertTextContains("phone-1", substring = true)
        compose.onNodeWithTag("pairing-review-reject").performClick()
        assertEquals(1, rejected.get())
    }

    @Test
    fun pairingDecisionIsDisabledWhenExactProposalReviewFailed() {
        compose.setContent {
            FixtureApp(
                pairingReviewUi = PairingReviewUiState(
                    requestId = "pair-1",
                    review = FleetPairingReview(
                        "pair-1", "Phone", "Android", "phone.tailnet.ts.net", "100.64.0.10",
                        "{\"id\":\"phone-1\"}"
                    ),
                    error = "Pairing decision failed. Refresh and review again."
                )
            )
        }

        compose.onAllNodes(hasTestTag("pairing-review-proposal")).assertCountEquals(0)
        compose.onNodeWithTag("pairing-review-approve").assertIsNotEnabled()
        compose.onNodeWithTag("pairing-review-reject").assertIsNotEnabled()
        compose.onNodeWithTag("pairing-review-retry").assertIsDisplayed()
    }

    @Test
    fun nativeSuggestionCancellationSerialClearsRetainedPaneWork() {
        val assistant = ConversationItem(
            id = "assistant", kind = "message", timestamp = "2026-07-18T00:00:00Z", role = "assistant",
            title = "", text = "Which rollout should I use?", detail = "", state = "complete", tool = "",
            attachments = emptyList(), choices = emptyList()
        )
        val native = mutableStateOf(
            NativeSessionUiState(
                "Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                items = listOf(assistant)
            )
        )
        compose.setContent {
            NativeStateFixture(
                native.value,
                inlineComposer = true,
                localSuggestionsAvailableOverride = true,
                localSuggestionDebugFakeOutput = """{"suggestions":["Use the safe rollout"]}"""
            )
        }
        compose.onNodeWithTag("local-suggest-composer").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("local-suggestion-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            native.value = native.value.copy(localSuggestionCancellationSerial = 1)
        }
        compose.onAllNodes(hasTestTag("local-suggestion-0")).assertCountEquals(0)
    }

    @Test
    fun sessionListUsesAutomaticTitleWithStableIdentityUnderIt() {
        compose.setContent { FixtureApp() }
        compose.onNodeWithText("Diagnostics").assertIsDisplayed()
        compose.onNodeWithText("wtmux:1 · gaming · wtmux").assertIsDisplayed()
    }

    @Test
    fun sessionsRepositoryErrorRetryAndDownloadAreUsable() {
        val attempts = AtomicInteger()
        compose.setContent {
            FixtureApp(
                onListRepository = { _, _, _, _, callback ->
                    if (attempts.incrementAndGet() == 1) callback(Result.failure(FleetUnavailableException("Host temporarily offline", "host_offline")))
                    else callback(Result.success(repositoryPage))
                },
                onDownloadRepository = { _, entry, callback ->
                    callback(FleetDownloadState(entry.name, entry.relativePath, "running", 4, 12, message = "Downloading · 33%"))
                    FleetDownloadCancellation()
                }
            )
        }
        compose.onNodeWithTag("session-more-gaming:wtmux").performClick()
        compose.onNodeWithText("Download a file").performClick()
        compose.onNodeWithTag("repository-error").assertIsDisplayed()
        compose.onNodeWithTag("repository-retry").performClick()
        compose.onNodeWithTag("repository-entry-README.md").assertIsDisplayed().performClick()
        compose.onNodeWithTag("repository-download-state").assertIsDisplayed()
        compose.onNodeWithText("Downloading · 33%").assertIsDisplayed()
        assertEquals(2, attempts.get())
    }

    @Test
    fun wideWorkspaceAssignsFromTheVerticalSessionRail() {
        val state = mutableStateOf(AndroidWorkspaceState(emptyWorkspaceLayout()))
        val broker = WorkspaceTerminalBroker(ApplicationProvider.getApplicationContext())
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                DesktopWorkspaceScreen(
                    snapshot = snapshot,
                    sessions = snapshot.sessions,
                    state = state.value,
                    broker = broker,
                    onStateChange = { state.value = it },
                    onMoreSession = { _, _ -> },
                    onRefresh = {}
                )
            }
        }
        compose.onNodeWithTag("desktop-session-search").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.runOnIdle {
            assertEquals(session.id, com.termux.app.fleet.workspacePanes(state.value.layout.root).single().sessionId)
        }
        compose.onNodeWithTag("workspace-pane-${state.value.layout.focusedPaneId}").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("workspace-mode-native")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("workspace-mode-terminal")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("workspace-more")).assertCountEquals(1)
        broker.close()
    }

    @Test
    fun wideWorkspaceRetainsTheProductionNativeSessionAcrossModeSwitches() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initial = emptyWorkspaceLayout()
        val state = mutableStateOf(
            AndroidWorkspaceState(WorkspaceReducer.assign(initial, initial.focusedPaneId, session.id))
        )
        val broker = WorkspaceTerminalBroker(context)
        @Suppress("UNCHECKED_CAST")
        val binding = broker.state(session.id) as androidx.compose.runtime.MutableState<WorkspaceTerminalBinding>
        binding.value = WorkspaceTerminalBinding(session.id, status = "live", message = "Live")
        val registry = NativeSessionRegistry<WorkspaceRetainedNativeSession>(context)
        val target = agentFleetWorkspaceTarget(session)

        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                CompositionLocalProvider(LocalWorkspaceNativeSessionRegistry provides registry) {
                    DesktopWorkspaceScreen(
                        snapshot = snapshot,
                        sessions = snapshot.sessions,
                        state = state.value,
                        broker = broker,
                        onStateChange = { state.value = it },
                        onMoreSession = { _, _ -> },
                        onRefresh = {}
                    )
                }
            }
        }

        compose.waitForIdle()
        compose.runOnIdle {
            binding.value = WorkspaceTerminalBinding(session.id, status = "live", message = "Live")
            assertEquals(1, registry.state().entries)
            assertEquals(setOf(target), registry.state().activeTargets)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            state.value = state.value.copy(
                layout = WorkspaceReducer.setView(
                    state.value.layout,
                    state.value.layout.focusedPaneId,
                    WorkspaceViewMode.Terminal
                )
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                WorkspaceViewMode.Terminal,
                workspacePanes(state.value.layout.root).single().viewMode
            )
            assertEquals(1, registry.state().entries)
            assertTrue(registry.state().activeTargets.isEmpty())
        }
        compose.runOnIdle {
            binding.value = WorkspaceTerminalBinding(session.id, status = "live", message = "Live")
            state.value = state.value.copy(
                layout = WorkspaceReducer.setView(
                    state.value.layout,
                    state.value.layout.focusedPaneId,
                    WorkspaceViewMode.Native
                )
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, registry.state().entries)
            assertEquals(setOf(target), registry.state().activeTargets)
        }

        compose.runOnIdle {
            state.value = state.value.copy(
                layout = WorkspaceReducer.setView(
                    state.value.layout,
                    state.value.layout.focusedPaneId,
                    WorkspaceViewMode.Terminal
                )
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            registry.destroy()
            broker.close()
        }
    }

    @Test
    fun wideWorkspaceUsesOneControlSetAndAChipForEveryPane() {
        val layout = WorkspaceReducer.preset(emptyWorkspaceLayout(), WorkspacePreset.Grid)
        val state = mutableStateOf(AndroidWorkspaceState(layout))
        val broker = WorkspaceTerminalBroker(ApplicationProvider.getApplicationContext())
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                DesktopWorkspaceScreen(
                    snapshot = snapshot,
                    sessions = snapshot.sessions,
                    state = state.value,
                    broker = broker,
                    onStateChange = { state.value = it },
                    onMoreSession = { _, _ -> },
                    onRefresh = {}
                )
            }
        }
        compose.onAllNodes(hasTestTag("workspace-mode-native")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("workspace-mode-terminal")).assertCountEquals(1)
        compose.onNodeWithTag("workspace-mode-native").assertIsSelected()
        compose.onNodeWithTag("workspace-mode-terminal").assertIsNotSelected()
        compose.onAllNodes(hasTestTag("workspace-more")).assertCountEquals(1)
        workspacePanes(state.value.layout.root).forEach { pane ->
            compose.onNodeWithTag("workspace-pane-chip-${pane.id}")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        val second = workspacePanes(state.value.layout.root)[1]
        compose.onNodeWithTag("workspace-pane-chip-${second.id}").performClick()
        compose.runOnIdle { assertEquals(second.id, state.value.layout.focusedPaneId) }
        compose.onNodeWithTag("workspace-more").performScrollTo().performClick()
        compose.onNodeWithText("Close pane").performClick()
        compose.runOnIdle { assertEquals(3, workspacePanes(state.value.layout.root).size) }
        broker.close()
    }

    @Test
    fun wideWorkspaceDividerAndPaneSwapExposeAccessibilityActions() {
        var layout = WorkspaceReducer.preset(emptyWorkspaceLayout(), WorkspacePreset.TwoColumns)
        val initial = workspacePanes(layout.root)
        layout = WorkspaceReducer.assign(layout, initial[0].id, session.id)
        layout = WorkspaceReducer.assign(layout, initial[1].id, "work-m:pending")
        val state = mutableStateOf(AndroidWorkspaceState(layout, railCollapsed = true))
        val broker = WorkspaceTerminalBroker(ApplicationProvider.getApplicationContext())
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                DesktopWorkspaceScreen(
                    snapshot = snapshot,
                    sessions = snapshot.sessions,
                    state = state.value,
                    broker = broker,
                    onStateChange = { state.value = it },
                    onMoreSession = { _, _ -> },
                    onRefresh = {}
                )
            }
        }

        val split = state.value.layout.root as WorkspaceSplit
        compose.onNodeWithTag("workspace-divider-${split.id}")
            .assertContentDescriptionEquals("Resize panes left and right")
            .assertWidthIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        compose.runOnIdle { assertEquals(0.7f, (state.value.layout.root as WorkspaceSplit).ratio) }

        val panes = workspacePanes(state.value.layout.root)
        val swap = compose.onNodeWithTag("workspace-pane-chip-${panes[0].id}")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Swap with pane 2" }
        compose.runOnIdle { assertTrue(swap.action()) }
        compose.runOnIdle {
            assertEquals(listOf("work-m:pending", session.id), workspacePanes(state.value.layout.root).map { it.sessionId })
        }
        broker.close()
    }

    @Test
    fun wideWorkspaceDragsTitleChipToSwapPaneAssignments() {
        var layout = WorkspaceReducer.preset(emptyWorkspaceLayout(), WorkspacePreset.TwoColumns)
        val initial = workspacePanes(layout.root)
        layout = WorkspaceReducer.assign(layout, initial[0].id, session.id)
        layout = WorkspaceReducer.assign(layout, initial[1].id, "work-m:pending")
        val state = mutableStateOf(AndroidWorkspaceState(layout, railCollapsed = true))
        val broker = WorkspaceTerminalBroker(ApplicationProvider.getApplicationContext())
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                DesktopWorkspaceScreen(
                    snapshot = snapshot,
                    sessions = snapshot.sessions,
                    state = state.value,
                    broker = broker,
                    onStateChange = { state.value = it },
                    onMoreSession = { _, _ -> },
                    onRefresh = {}
                )
            }
        }
        val panes = workspacePanes(state.value.layout.root)
        val source = compose.onNodeWithTag("workspace-pane-chip-${panes[0].id}")
        val sourceCenter = source.fetchSemanticsNode().boundsInRoot.center
        val targetCenter = compose.onNodeWithTag("workspace-pane-chip-${panes[1].id}").fetchSemanticsNode().boundsInRoot.center
        source.performTouchInput {
            down(center)
            moveBy(targetCenter - sourceCenter)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf("work-m:pending", session.id), workspacePanes(state.value.layout.root).map { it.sessionId })
            assertEquals(panes[1].id, state.value.layout.focusedPaneId)
        }
        broker.close()
    }

    @Test
    fun permanentRepositoryFailureDoesNotOfferRetry() {
        compose.setContent {
            FixtureApp(
                onListRepository = { _, _, _, _, callback ->
                    callback(Result.failure(FleetUnavailableException(
                        "Repository path is unavailable for this session", "repository_unavailable"
                    )))
                }
            )
        }
        compose.onNodeWithTag("session-more-gaming:wtmux").performClick()
        compose.onNodeWithText("Download a file").performClick()
        compose.onNodeWithTag("repository-error").assertIsDisplayed()
        compose.onNodeWithText("Repository path is unavailable", substring = true).assertIsDisplayed()
        compose.onAllNodes(hasTestTag("repository-retry")).assertCountEquals(0)
    }

    @Test
    fun diagnosticsRequiresPreviewBeforeShare() {
        val exports = AtomicInteger()
        val report = diagnosticReport()
        compose.setContent { FixtureApp(diagnosticsUi = DiagnosticsUiState(report = report), onExportDiagnostics = { exports.incrementAndGet() }) }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-screen").performScrollToNode(hasTestTag("open-diagnostics-button"))
        compose.onNodeWithTag("open-diagnostics-button").performClick()
        compose.onNodeWithTag("diagnostics-report").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-export").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-preview")
            .assertIsDisplayed()
            .assertTextContains("\"schemaVersion\": 2", substring = true)
        assertEquals(0, exports.get())
        compose.onNodeWithTag("diagnostics-share").performClick()
        assertEquals(1, exports.get())
    }

    @Test
    fun localSuggestionsAreOffUntilThePinnedModelIsReady() {
        compose.setContent { FixtureApp() }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-screen").performScrollToNode(hasTestTag("local-suggestions-settings"))
        compose.onNodeWithTag("local-suggestions-settings").assertIsDisplayed()
        compose.onNodeWithText("Gemma 4 E2B Instruct", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("local-suggestions-mode-off").assertIsDisplayed()
        compose.onNodeWithTag("local-suggestions-mode-manual").assertIsNotEnabled()
        compose.onNodeWithTag("local-suggestions-mode-automatic").assertIsNotEnabled()
        compose.onNodeWithTag("local-model-download").assertIsDisplayed()
        compose.onNodeWithTag("local-model-import").assertIsDisplayed()
    }

    @Test
    fun nativeSuggestIsAvailableOnlyWhileTheDraftIsEmpty() {
        val assistant = ConversationItem(
            id = "assistant", kind = "message", timestamp = "2026-07-17T00:00:00Z", role = "assistant",
            title = "", text = "Which rollout should I use?", detail = "", state = "complete", tool = "",
            attachments = emptyList(), choices = emptyList()
        )
        compose.setContent {
            NativeStateFixture(
                NativeSessionUiState(
                    "Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                    items = listOf(assistant)
                ),
                inlineComposer = true,
                localSuggestionsAvailableOverride = true
            )
        }
        compose.onNodeWithTag("local-suggest-composer").assertIsDisplayed()
        compose.onNodeWithTag("native-message-input").performTextInput("I will answer manually")
        compose.onAllNodes(hasTestTag("local-suggest-composer")).assertCountEquals(0)
    }

    @Test
    fun uncertainProviderStateIsVisiblyReadOnlyAndFallsBackToTerminal() {
        val stale = ProviderState(
            confidence = "stale",
            reasonCode = "PROVIDER_STATE_STALE",
            observedRevision = "revision-1",
            eventPosition = 42,
            parser = ProviderComponent("codex-parser", "3.0.0"),
            actions = ProviderComponent("codex-actions", "2.0.0"),
            mutationsAllowed = false,
            fallback = "read_only_native"
        )
        compose.setContent {
            NativeStateFixture(
                NativeSessionUiState(
                    "Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                    providerState = stale
                ),
                inlineComposer = true
            )
        }
        compose.onNodeWithTag("native-provider-confidence").assertIsDisplayed()
        compose.onNodeWithText("Native view is read-only").assertIsDisplayed()
        compose.onNodeWithTag("native-provider-fallback").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("native-message-input")).assertCountEquals(0)
    }

    @Test
    fun fullScreenNativeComposerOffersLocalSuggestionsAndStacksActions() {
        val assistant = ConversationItem(
            id = "assistant", kind = "message", timestamp = "2026-07-18T00:00:00Z", role = "assistant",
            title = "", text = "Which rollout should I use?", detail = "", state = "complete", tool = "",
            attachments = emptyList(), choices = emptyList()
        )
        val submissions = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                AgentFleetComposerContent(
                    target = "gaming:project:wtmux-main",
                    nativeState = AgentFleetComposerNativeState(
                        target = "gaming:project:wtmux-main",
                        visible = true,
                        items = listOf(assistant),
                        revision = "revision-1"
                    ),
                    attachments = emptyList(),
                    uploading = false,
                    uploadError = null,
                    onShowPendingQuestion = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onComposerText = { text, appendEnter -> submissions += text to appendEnter; true },
                    localSuggestionsAvailableOverride = true,
                    localSuggestionDebugFakeOutput = """{"suggestions":["Use the safe rollout"]}"""
                )
            }
        }

        compose.onNodeWithTag("local-suggest-composer").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("local-suggestion-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("local-suggestion-0").performClick()
        compose.onNodeWithTag("agent-fleet-message-input").assertTextContains("Use the safe rollout")
        compose.onAllNodes(hasTestTag("local-suggest-composer")).assertCountEquals(0)

        val attach = compose.onNodeWithTag("agent-fleet-composer-attach").fetchSemanticsNode().boundsInRoot
        val insert = compose.onNodeWithTag("agent-fleet-composer-insert").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("agent-fleet-composer-send").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("agent-fleet-message-input").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(attach.center.y < insert.center.y)
        org.junit.Assert.assertTrue(insert.center.y < send.center.y)
        org.junit.Assert.assertTrue(input.width > attach.width * 2)

        compose.onNodeWithTag("agent-fleet-composer-send").performClick()
        compose.runOnIdle { assertEquals(listOf("Use the safe rollout" to true), submissions) }
        LocalSuggestionRuntime.shutdown(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun automaticModePreparesChoicesOnlyForANewLiveReply() {
        val streaming = ConversationItem(
            id = "assistant", kind = "message", timestamp = "2026-07-19T00:00:00Z", role = "assistant",
            title = "", text = "Working", detail = "", state = "streaming", tool = "",
            attachments = emptyList(), choices = emptyList()
        )
        val native = mutableStateOf(
            AgentFleetComposerNativeState(
                target = "gaming:project:wtmux-main", visible = true, items = listOf(streaming),
                revision = "snapshot-1", liveEventSerial = 0
            )
        )
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                AgentFleetComposerContent(
                    target = "gaming:project:wtmux-main",
                    nativeState = native.value,
                    attachments = emptyList(), uploading = false, uploadError = null,
                    onShowPendingQuestion = {}, onAttach = {}, onRemoveAttachment = {},
                    onComposerText = { _, _ -> true },
                    localSuggestionModeOverride = LocalSuggestionMode.AUTOMATIC,
                    localSuggestionDebugFakeOutput = """{"suggestions":["Continue with the safe option"]}"""
                )
            }
        }
        compose.onAllNodes(hasTestTag("local-suggestion-results")).assertCountEquals(0)
        compose.runOnIdle {
            native.value = native.value.copy(
                items = listOf(streaming.copy(text = "Should I continue?", state = "complete")),
                liveEventSerial = 1
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("local-suggestion-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("local-suggestion-0").assertTextContains("Continue with the safe option")
        compose.onNodeWithTag("local-suggestion-regenerate").assertIsDisplayed()
    }

    @Test
    fun automaticModePreparesAnActiveFreeTextQuestion() {
        val question = ConversationItem(
            id = "question", kind = "question", timestamp = "2026-07-19T00:00:00Z", role = "assistant",
            title = "Answer needed", text = "", detail = "", state = "pending", tool = "codex",
            attachments = emptyList(), choices = emptyList(), revision = "question-revision",
            questions = listOf(ConversationQuestion("scope", "Scope", "What should I change?", "text", true, false, emptyList()))
        )
        val native = mutableStateOf(
            NativeSessionUiState(
                "Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                items = emptyList(), revision = "snapshot-1", liveEventSerial = 0
            )
        )
        compose.setContent {
            NativeStateFixture(
                state = native.value,
                localSuggestionModeOverride = LocalSuggestionMode.AUTOMATIC,
                localSuggestionDebugFakeOutput = """{"suggestions":["Change only the active session"]}"""
            )
        }
        compose.onAllNodes(hasTestTag("local-suggestion-results")).assertCountEquals(0)
        compose.runOnIdle { native.value = native.value.copy(items = listOf(question), liveEventSerial = 1) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("local-suggestion-0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("local-suggestion-0").assertTextContains("Change only the active session")
    }

    @Test
    fun nativeSecondaryControlsLiveInActionsMenu() {
        var controlC = 0
        val keys = mutableListOf<String>()
        compose.setContent {
            NativeStateFixture(
                NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live"),
                onShellKey = keys::add,
                onControlC = { controlC++ }
            )
        }

        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Ctrl+C").performClick()
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Shift+Tab").performClick()

        compose.runOnIdle {
            assertEquals(1, controlC)
            assertEquals(listOf("SHIFT_TAB"), keys)
        }
    }

    @Test
    fun localSuggestionBinderReturnsParsedFakeEngineResultsWithoutAModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun request(fakeOutput: String, prompt: String): List<String> {
            val latch = CountDownLatch(1)
            var result: Result<List<String>>? = null
            val client = LocalSuggestionClient(context, fakeOutput)
            client.generate(prompt) {
                result = it
                latch.countDown()
            }
            org.junit.Assert.assertTrue("fake local model callback timed out", latch.await(10, TimeUnit.SECONDS))
            client.close()
            return result?.getOrThrow().orEmpty()
        }

        assertEquals(
            listOf("Use the safe default", "Show me the tradeoff"),
            request(
                """{"suggestions":["Use the safe default","Show me the tradeoff"]}""",
                "first bounded fixture prompt"
            )
        )
        // The warm started service reuses its cached binder. A second request must carry its own
        // debuggable-only fake instead of depending on onBind() being invoked again.
        assertEquals(
            listOf("Keep the warm engine"),
            request(
                """{"suggestions":["Keep the warm engine"]}""",
                "second bounded fixture prompt"
            )
        )
        LocalSuggestionRuntime.shutdown(context)
    }

    @Test
    fun onePlanQuestionSubmitsExactlyOnceOnTap() {
        val submissions = mutableListOf<List<ConversationAnswer>>()
        compose.setContent { NativeFixture(listOf(question("q1", "Choose a direction")), submissions::add) }
        compose.onNodeWithTag("question-option-q1-a").performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals(listOf("a"), submissions.single().single().choiceIds)
        }
    }

    @Test
    fun threePlanQuestionsAdvanceByTapAndSubmitOnceAtEnd() {
        val submissions = mutableListOf<List<ConversationAnswer>>()
        val questions = listOf(
            question("q1", "Which host should run this?"),
            question("q2", "Which rollout channel should be used?"),
            question("q3", "This deliberately long final question verifies that the action sheet remains scrollable and tap-to-answer still works when the prompt occupies several lines on a phone-sized screen."),
        )
        compose.setContent { NativeFixture(questions, submissions::add) }
        compose.onNodeWithTag("question-option-q1-a").performClick()
        compose.onNodeWithTag("question-page").assertTextContains("2 of 3")
        compose.onNodeWithTag("question-option-q2-b").performClick()
        compose.onNodeWithTag("question-page").assertTextContains("3 of 3")
        compose.onNodeWithTag("question-option-q3-a").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals(listOf("q1", "q2", "q3"), submissions.single().map { it.questionId })
            assertEquals(listOf(listOf("a"), listOf("b"), listOf("a")), submissions.single().map { it.choiceIds })
        }
    }

    @Test
    fun staleQuestionDoesNotReplaceTheComposerWithAnActionPrompt() {
        val newerTool = ConversationItem(
            id = "tool-after-question", kind = "tool", timestamp = "2026-07-16T01:01:00Z", role = "assistant",
            title = "Tool completed", text = "", detail = "", state = "complete", tool = "exec",
            attachments = emptyList(), choices = emptyList()
        )
        val staleQuestion = ConversationItem(
            id = "stale-question", kind = "question", timestamp = "2026-07-16T01:00:00Z", role = "assistant",
            title = "Answer needed", text = "", detail = "", state = "pending", tool = "question",
            attachments = emptyList(), choices = emptyList(), revision = "stale-revision",
            questions = listOf(question("spec", "Do a SPEC first?"))
        )
        compose.setContent {
            NativeStateFixture(NativeSessionUiState(
                "Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                items = listOf(newerTool, staleQuestion)
            ))
        }

        compose.onAllNodes(hasTestTag("native-pending-action")).assertCountEquals(0)
    }

    @Test
    fun groupedToolsTasksAndDetailsExposeUsefulSemantics() {
        val tools = (1..10).map { index ->
            ConversationItem(
                id = "tool-$index", kind = "tool", timestamp = "2026-07-15T00:00:0${index % 10}Z",
                role = "assistant", title = "Read source $index", text = "", detail = "", state = "completed", tool = "exec",
                attachments = emptyList(), choices = emptyList(), action = "read", target = "source-$index",
                presentation = ToolPresentation(
                    "Read source $index", "exec · completed", 2,
                    listOf(ToolPresentationBlock("Command", "terminal", "rg source-$index")),
                    listOf(ToolPresentationBlock("Result", "terminal", "matched source $index"))
                )
            )
        }
        val tasks = ConversationItem(
            id = "tasks", kind = "task_list", timestamp = "2026-07-15T00:01:00Z", role = "assistant",
            title = "", text = "Implementation", detail = "", state = "running", tool = "",
            attachments = emptyList(), choices = emptyList(), tasks = listOf(
                ConversationTask("1", "Inspect", "Inspecting", "Read sources", "completed"),
                ConversationTask("2", "Implement", "Implementing diagnostics", "Compose UI", "in_progress"),
                ConversationTask("3", "Verify", "Verifying", "Emulator", "pending")
            )
        )
        compose.setContent { NativeStateFixture(NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live", items = tools + tasks)) }
        compose.onNodeWithTag("tool-group-tool-1").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithTag("tool-details-tool-1").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Command").assertIsDisplayed()
        compose.onNodeWithText("rg source-1").assertIsDisplayed()
    }

    @Test
    fun activeLimitCardDisappearsWhenAttentionIsNoLongerCurrent() {
        val attention = FleetAttention("attention-1", "gaming", "gaming:wtmux", "codex", "2026-07-15T10:00:00Z", "offered")
        val state = mutableStateOf(NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live", attention = attention))
        compose.setContent { NativeStateFixture(state.value) }
        compose.onNodeWithText("Codex usage limit reached").assertIsDisplayed()
        compose.runOnIdle { state.value = NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live") }
        compose.onNodeWithText("Codex usage limit reached").assertIsNotDisplayed()
    }

    private fun question(id: String, prompt: String) = ConversationQuestion(
        id, "Decision", prompt, "single", true, false,
        listOf(ConversationQuestionOption("a", "Recommended", "Safe default"), ConversationQuestionOption("b", "Alternative", "Different tradeoff"))
    )

    @androidx.compose.runtime.Composable
    private fun NativeFixture(questions: List<ConversationQuestion>, onQuestion: (List<ConversationAnswer>) -> Unit) {
        val item = ConversationItem(
            id = "question-group", kind = "question", timestamp = "2026-07-15T00:00:00Z", role = "assistant",
            title = "Plan questions", text = "", detail = "", state = "pending", tool = "codex",
            attachments = emptyList(), choices = emptyList(), revision = "revision-1", questions = questions
        )
        NativeStateFixture(
            NativeSessionUiState(
                "Plan fixture", "gaming", "wtmux-main", adapter = "codex", interactionMode = "plan",
                connection = "Live", items = listOf(item), focusQuestionId = item.id, focusQuestionSerial = 1
            ),
            onQuestion = { _, answers -> onQuestion(answers) }
        )
    }

    @androidx.compose.runtime.Composable
    private fun NativeStateFixture(
        state: NativeSessionUiState,
        onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit = { _, _ -> },
        onShellKey: (String) -> Unit = {},
        onControlC: () -> Unit = {},
        inlineComposer: Boolean = false,
        applyStatusBarInset: Boolean = true,
        localSuggestionsAvailableOverride: Boolean? = null,
        localSuggestionModeOverride: LocalSuggestionMode? = null,
        localSuggestionDebugFakeOutput: String? = null
    ) {
        val fixtureState = if (state.providerState.reasonCode == "PROVIDER_STATE_UNAVAILABLE") {
            state.copy(providerState = verifiedProviderState(state.adapter))
        } else state
        AgentFleetTheme(darkTheme = true) {
            NativeSessionScreen(
                state = fixtureState,
                aiComposer = true,
                onToggleTerminal = {},
                onRetry = {},
                onLoadOlder = {},
                onApproval = { _, _ -> },
                onQuestion = onQuestion,
                onShellCommand = {},
                onShellKey = onShellKey,
                onDirectory = {},
                onRefreshDirectory = {},
                onControlC = onControlC,
                onCloseSession = {},
                onKillSession = {},
                onScheduleContinue = {},
                onDismissAttention = {},
                inlineComposer = inlineComposer,
                applyStatusBarInset = applyStatusBarInset,
                localSuggestionsAvailableOverride = localSuggestionsAvailableOverride,
                localSuggestionModeOverride = localSuggestionModeOverride,
                localSuggestionDebugFakeOutput = localSuggestionDebugFakeOutput
            )
        }
    }

    private fun verifiedProviderState(adapter: String) = ProviderState(
        confidence = "verified",
        reasonCode = "PROVIDER_STATE_VERIFIED",
        observedRevision = "fixture-revision",
        eventPosition = 1,
        parser = ProviderComponent("${adapter.ifBlank { "codex" }}-parser", "3.0.0"),
        actions = ProviderComponent("${adapter.ifBlank { "codex" }}-actions", "2.0.0"),
        mutationsAllowed = true,
        fallback = "none"
    )

    @androidx.compose.runtime.Composable
    private fun FixtureApp(
        fleetSnapshot: FleetSnapshot = snapshot,
        onRefresh: () -> Unit = {},
        diagnosticsUi: DiagnosticsUiState = DiagnosticsUiState(),
        onListRepository: (FleetSession, String, Boolean, String, (Result<FleetRepositoryPage>) -> Unit) -> Unit = { _, _, _, _, callback -> callback(Result.success(repositoryPage)) },
        onDownloadRepository: (FleetSession, FleetRepositoryEntry, (FleetDownloadState) -> Unit) -> FleetDownloadCancellation = { _, _, _ -> FleetDownloadCancellation() },
        onExportDiagnostics: (AgentFleetDiagnosticReport) -> Unit = {},
        fleetAlerts: List<FleetAlert> = emptyList(),
        fleetAlertSettings: FleetAlertSettings = FleetAlertSettings(),
        onOpenSession: (FleetSession) -> Unit = {},
        onDismissFleetAlert: () -> Unit = {},
        onFleetAlertSettings: (FleetAlertSettings) -> Unit = {},
        onPauseFleetAlerts: () -> Unit = {},
        onReviewPairingRequest: (String) -> Unit = {},
        pairingReviewUi: PairingReviewUiState? = null,
        onDecidePairingRequest: (String, Boolean) -> Unit = { _, _ -> }
    ) {
        AgentFleetTheme(darkTheme = true) {
            AgentFleetApp(
                fleetState = FleetLoadState.Ready(fleetSnapshot),
                recentSessions = fleetSnapshot.sessions,
                pendingPairInvitation = null,
                onPairInvitationHandled = {},
                pendingSharedImages = emptyList(),
                updateState = UpdateUiState.Idle,
                updateManifestUrl = "https://updates.example.test/manifest.json",
                runtimeUi = RuntimeUiState(runtimeStatus, detail = "Ready"),
                diagnosticsUi = diagnosticsUi,
                diagnosticError = null,
                localModelUi = LocalModelUiState(),
                onSharedImagesHandled = {},
                onRefresh = onRefresh,
                onOpenSession = onOpenSession,
                onOpenSessionWithImages = { _, _ -> },
                onCreateSession = { _, _, _, _, _, _ -> },
                onListDirectory = { _, _, _, callback -> callback(Result.success(FleetDirectoryListing("linux", "/home", null, emptyList(), emptyList(), false))) },
                onCreateDirectory = { _, _, _, _, callback -> callback(Result.success("/home/new")) },
                onListRepository = onListRepository,
                onSearchRepository = { _, _, _, callback -> callback(Result.success(repositoryPage)) },
                onDownloadRepository = onDownloadRepository,
                onCloseRepository = {},
                onOpenDownload = {},
                onRenameSession = { _, _ -> },
                onResetSessionName = {},
                onScheduleContinue = { _, _ -> },
                onScheduleAttention = { _, _, _ -> },
                onDismissAttention = {},
                onCancelSchedule = {},
                onKillSession = {},
                onCopyAttachCommand = {},
                onPairInvitation = {},
                onCheckUpdate = {},
                onInstallUpdate = {},
                onRepairRuntime = {},
                onCheckRuntime = {},
                onRollbackRuntime = {},
                onRestoreBaseline = {},
                onOpenAppearance = {},
                onMigrateFleetState = {},
                onSetLocalSuggestions = {},
                onDownloadLocalModel = {},
                onImportLocalModel = {},
                onCancelLocalModel = {},
                onRemoveLocalModel = {},
                onRunDiagnostics = {},
                onCopyDiagnostics = {},
                onExportDiagnostics = onExportDiagnostics,
                onCopyDiagnosticError = {},
                onDiagnosticErrorHandled = {},
                fleetAlerts = fleetAlerts,
                fleetAlertSettings = fleetAlertSettings,
                onDismissFleetAlert = onDismissFleetAlert,
                onFleetAlertSettings = onFleetAlertSettings,
                onPauseFleetAlerts = onPauseFleetAlerts,
                onReviewPairingRequest = onReviewPairingRequest,
                pairingReviewUi = pairingReviewUi,
                onDecidePairingRequest = onDecidePairingRequest
            )
        }
    }

    private fun diagnosticReport() = AgentFleetDiagnosticReport(
        "2026-07-15T00:00:00Z", "test", "16", "Emulator", "x86_64", "fixture", "revision-1",
        listOf(AgentFleetDiagnosticCheck("runtime", "Built-in runtime", DiagnosticStatus.Healthy, "Ready")), emptyList()
    )

    private fun pairingAlert(id: String, target: FleetAlertTarget) = FleetAlert(
        FleetAlertCategory.Pairing,
        if (target == FleetAlertTarget.Dashboard) "2 pairing requests need review" else "Pairing request $id",
        "Review the verified device proposal.",
        target
    )

    private fun pairingRequest(id: String, deviceName: String) = FleetPairingRequest(
        id = id,
        deviceName = deviceName,
        platform = "Android",
        peer = "$id.tailnet.ts.net",
        requestedAt = "2026-08-09T00:00:00Z",
        expiresAt = "2026-08-09T00:10:00Z",
        status = "awaiting-review"
    )

    private fun physicalHost(id: String, aliases: List<String>, name: String = id) = FleetPhysicalHost(
        id = id,
        name = name,
        platform = "wsl",
        status = "healthy",
        lastSeenAt = "2026-08-09T00:00:00Z",
        errorCode = "",
        endpointIds = emptyList(),
        executionTargetIds = listOf("linux", "windows"),
        legacyHostIds = aliases
    )

    companion object {
        @JvmStatic @org.junit.BeforeClass
        fun prepareDiscoveryRuntimeBeforeCreatingTheComposeHost() {
            // Bootstrap uses its own ActivityScenario. Finish it before the
            // Compose rule owns a host, so closing bootstrap cannot close that UI.
            AgentFleetEmbeddedRegistryTest().prepareRuntime(ApplicationProvider.getApplicationContext())
        }

        private val session = FleetSession(
            "gaming:wtmux", "gaming", "wtmux-main", "wtmux:1", "Diagnostics", "wtmux", "codex", "linux", "active", false,
            "2026-07-15T00:00:00Z", 0, "/home/user/projects/wtmux", "project"
        )
        private val snapshot = FleetSnapshot(
            "revision-1", "2026-07-15T00:00:00Z",
            listOf(FleetHost("gaming", "Gaming desktop", "healthy", "wsl", "2026-07-15T00:00:00Z", emptySet())),
            listOf(session), emptyList(), emptyList()
        )
        private val repositoryPage = FleetRepositoryPage(
            "wtmux", "", null,
            listOf(FleetRepositoryEntry("README.md", "README.md", "file", 12, "2026-07-15T00:00:00Z", false, false)),
            null, false
        )
        private val runtimeStatus = EmbeddedRuntimeStatus(
            true, true, false, "fixture", "fixture", "fixture", "", 0, 6, emptyList(), "Ready"
        )
    }
}
