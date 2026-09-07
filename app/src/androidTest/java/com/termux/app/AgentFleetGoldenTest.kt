package com.termux.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.app.fleet.ConversationAnswer
import com.termux.app.fleet.ConversationItem
import com.termux.app.fleet.ConversationQuestion
import com.termux.app.fleet.ConversationQuestionOption
import com.termux.app.fleet.ConversationTask
import com.termux.app.fleet.NativeSessionScreen
import com.termux.app.fleet.NativeSessionUiState
import com.termux.app.fleet.ProviderComponent
import com.termux.app.fleet.ProviderState
import com.termux.app.fleet.ToolPresentation
import com.termux.app.fleet.ToolPresentationBlock
import com.termux.app.fleet.DrawerLocalSession
import com.termux.app.fleet.DrawerRemoteSession
import com.termux.app.fleet.DrawerSessionSurface
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.UnifiedDrawerState
import com.termux.app.fleet.UnifiedTerminalDrawer
import java.io.FileNotFoundException
import kotlin.math.abs
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetGoldenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before
    fun prepareReferenceWindow() {
        compose.runOnUiThread {
            // NativeSessionScreen owns its status-bar padding. Make the
            // screenshot host's window contract independent of prior activities.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            androidx.core.view.ViewCompat.requestApplyInsets(compose.activity.window.decorView)
        }
    }

    @Test
    fun darkPlanQuestionsMatchGolden() {
        val question = ConversationItem(
            "question-group", "question", "2026-07-15T00:00:00Z", "assistant", "Plan questions", "", "", "pending", "codex",
            emptyList(), emptyList(), revision = "revision-1", questions = listOf(
                ConversationQuestion(
                    "q1", "Rollout", "Which environment should receive this carefully validated Android workflow first?",
                    "single", true, false, listOf(
                        ConversationQuestionOption("a", "Emulator first", "Fast and repeatable"),
                        ConversationQuestionOption("b", "Manual phone", "Use only for the final smoke test")
                    )
                )
            )
        )
        setNative(NativeSessionUiState(
            "Plan fixture", "gaming", "wtmux-main", adapter = "codex", interactionMode = "plan", connection = "Live",
            items = listOf(question), focusQuestionId = question.id, focusQuestionSerial = 1
        ))
        compose.onNodeWithTag("native-pending-action").performClick()
        compose.waitForIdle()
        assertGolden("native-plan-question")
    }

    @Test
    fun darkStructuredWorkMatchesGolden() {
        val tools = (1..4).map { index ->
            ConversationItem(
                "tool-$index", "tool", "2026-07-15T00:00:0${index}Z", "assistant", "Inspect source $index", "", "", "completed", "exec",
                emptyList(), emptyList(), action = "read", target = "source-$index",
                presentation = ToolPresentation(
                    "Inspect source $index", "exec · completed", 2,
                    listOf(ToolPresentationBlock("Command", "terminal", "rg source-$index")),
                    listOf(ToolPresentationBlock("Result", "terminal", "matched source $index"))
                )
            )
        }
        val tasks = ConversationItem(
            "tasks", "task_list", "2026-07-15T00:01:00Z", "assistant", "", "Implementation", "", "running", "",
            emptyList(), emptyList(), tasks = listOf(
                ConversationTask("1", "Inspect", "Inspecting", "Read sources", "completed"),
                ConversationTask("2", "Implement", "Implementing diagnostics", "Compose UI", "in_progress"),
                ConversationTask("3", "Verify", "Verifying", "Pixel 7 emulator", "pending")
            )
        )
        setNative(NativeSessionUiState(
            "Structured work", "gaming", "wtmux-main", adapter = "codex", connection = "Live", items = tools + tasks
        ))
        assertGolden("native-structured-work")
    }

    /** Candidate-only visual review for the deliberate Classic drawer redesign. */
    @Test
    fun darkUnifiedDrawerReviewCandidate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentFleetUpdateGoldens") == "true")
        val sessions = (1..11).map { index ->
            drawerRemote(
                name = if (index == 1) "wtmux-main" else "session-$index",
                label = if (index == 1) "Main work" else "Agent task $index",
                pinned = index == 1,
                available = index != 11,
                used = (12 - index).toLong()
            )
        }
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                UnifiedTerminalDrawer(
                    state = UnifiedDrawerState(
                        remoteSessions = sessions,
                        hostNames = mapOf("gaming" to "Gaming desktop"),
                        localSessions = listOf(DrawerLocalSession("local", "Local shell", "bash", true)),
                        attachedSessionIds = setOf(sessions.first().session.id),
                        activeRemoteId = sessions.first().session.id,
                        drawerOpen = true
                    ),
                    onOpenRemote = { _, _ -> }, onTogglePin = {}, onKillRemote = {}, onCloseRemote = {},
                    onRemoveRemote = {}, onOpenAgentFleetSession = {}, onRefresh = {},
                    onCloseLocal = {}, onCreateLocal = { _, _ -> }, onOpenAgentFleet = {},
                    onKeyboard = {}, onAppearance = {}
                )
            }
        }
        compose.waitForIdle()
        assertGolden("unified-terminal-drawer")
    }

    /** Candidate-only visual review for the approved Reading-first transcript. */
    @Test
    fun darkReadingFirstTranscriptReviewCandidate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentFleetUpdateGoldens") == "true")
        val messages = listOf(
            ConversationItem(
                "m1", "message", "2026-07-18T00:00:00Z", "user", "", "Can you tighten the Android session view without hiding any actions?", "", "complete", "",
                emptyList(), emptyList()
            ),
            ConversationItem(
                "m2", "message", "2026-07-18T00:00:01Z", "assistant", "",
                "Yes. I’ll flatten ordinary assistant turns, reduce the feed rhythm, and keep questions and approvals visually distinct.\n\nThe drawer will cover the composer and expose its three utilities in a fixed footer.",
                "", "complete", "", emptyList(), emptyList()
            ),
            ConversationItem(
                "m3", "message", "2026-07-18T00:00:02Z", "user", "", "Keep terminal compatibility and make the key row scroll horizontally.", "", "complete", "",
                emptyList(), emptyList()
            ),
            ConversationItem(
                "m4", "message", "2026-07-18T00:00:03Z", "assistant", "",
                "Done. Custom key matrices retain row-major order, modifiers, repeats, haptics, and popup behavior. The default strip is one compact row.",
                "", "complete", "", emptyList(), emptyList()
            )
        )
        setNative(
            NativeSessionUiState(
                "High-density UI", "gaming", "wtmux-main", adapter = "codex", connection = "Live", items = messages
            ),
            inlineComposer = true
        )
        assertGolden("native-reading-first")
    }

    private fun drawerRemote(name: String, label: String, pinned: Boolean = false, available: Boolean = true, used: Long) =
        DrawerRemoteSession(
            session = FleetSession(
                "gaming:$name", "gaming", name, label, "Codex", "wtmux", "codex", "linux",
                "active", false, null, 0
            ),
            pinned = pinned,
            lastUsed = used,
            surface = DrawerSessionSurface.Native,
            available = available,
            cached = !available
        )

    private fun setNative(state: NativeSessionUiState, inlineComposer: Boolean = false) {
        val fixtureState = if (state.providerState.reasonCode == "PROVIDER_STATE_UNAVAILABLE") {
            state.copy(providerState = ProviderState(
                confidence = "verified",
                reasonCode = "PROVIDER_STATE_VERIFIED",
                observedRevision = "golden-revision",
                eventPosition = 1,
                parser = ProviderComponent("codex-parser", "3.0.0"),
                actions = ProviderComponent("codex-actions", "2.0.0"),
                mutationsAllowed = true,
                fallback = "none"
            ))
        } else state
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                NativeSessionScreen(
                    state = fixtureState, aiComposer = true, onToggleTerminal = {}, onRetry = {}, onLoadOlder = {},
                    onApproval = { _, _ -> }, onQuestion = { _: ConversationItem, _: List<ConversationAnswer> -> },
                    onShellCommand = {}, onShellKey = {}, onDirectory = {}, onRefreshDirectory = {}, onControlC = {},
                    onCloseSession = {}, onKillSession = {}, onScheduleContinue = {}, onDismissAttention = {},
                    inlineComposer = inlineComposer
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertGolden(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(750)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val source = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val actual = Bitmap.createScaledBitmap(source, GOLDEN_WIDTH, GOLDEN_HEIGHT, true)
        val storage = PlatformTestStorageRegistry.getInstance()
        compose.runOnUiThread {
            val content = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val location = IntArray(2).also(content::getLocationOnScreen)
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(content)
            storage.openOutputFile("$name-viewport.json").use {
                it.write(org.json.JSONObject()
                    .put("contentY", location[1]).put("contentHeight", content.height)
                    .put("statusBarInset", insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top)
                    .put("observedAt", java.time.Instant.now().toString()).toString().toByteArray())
            }
        }
        storage.openOutputFile("$name-actual.png").use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val update = InstrumentationRegistry.getArguments().getString("agentFleetUpdateGoldens") == "true"
        if (update) {
            storage.openOutputFile("$name-reference.png").use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val reference = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("goldens/$name.png").use(BitmapFactory::decodeStream)
        } catch (_: FileNotFoundException) {
            fail("Missing golden goldens/$name.png; run scripts/debug/android-check.sh update-goldens and review the output")
            return
        }
        if (reference.width != GOLDEN_WIDTH || reference.height != GOLDEN_HEIGHT) {
            fail("Golden $name is ${reference.width}x${reference.height}; expected ${GOLDEN_WIDTH}x${GOLDEN_HEIGHT}")
        }
        val actualPixels = IntArray(GOLDEN_WIDTH * GOLDEN_HEIGHT)
        val referencePixels = IntArray(actualPixels.size)
        val diffPixels = IntArray(actualPixels.size)
        actual.getPixels(actualPixels, 0, GOLDEN_WIDTH, 0, 0, GOLDEN_WIDTH, GOLDEN_HEIGHT)
        reference.getPixels(referencePixels, 0, GOLDEN_WIDTH, 0, 0, GOLDEN_WIDTH, GOLDEN_HEIGHT)
        var changed = 0
        actualPixels.indices.forEach { index ->
            val a = actualPixels[index]
            val b = referencePixels[index]
            val different = abs(Color.red(a) - Color.red(b)) > CHANNEL_THRESHOLD ||
                abs(Color.green(a) - Color.green(b)) > CHANNEL_THRESHOLD ||
                abs(Color.blue(a) - Color.blue(b)) > CHANNEL_THRESHOLD
            if (different) {
                changed++
                diffPixels[index] = Color.RED
            } else {
                val gray = (Color.red(b) + Color.green(b) + Color.blue(b)) / 3
                diffPixels[index] = Color.argb(100, gray, gray, gray)
            }
        }
        val diff = Bitmap.createBitmap(diffPixels, GOLDEN_WIDTH, GOLDEN_HEIGHT, Bitmap.Config.ARGB_8888)
        storage.openOutputFile("$name-diff.png").use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val ratio = changed.toDouble() / actualPixels.size.toDouble()
        if (ratio > MAX_CHANGED_RATIO) fail("Golden $name changed ${(ratio * 100).format(3)}%; allowed 0.5%. See actual and diff artifacts.")
    }

    private fun Double.format(digits: Int): String = String.format(java.util.Locale.US, "%.${digits}f", this)

    companion object {
        private const val GOLDEN_WIDTH = 393
        private const val GOLDEN_HEIGHT = 852
        private const val CHANNEL_THRESHOLD = 8
        private const val MAX_CHANGED_RATIO = 0.005
        private val changedViewportOverlays = mutableListOf<String>()

        private fun deviceShell(command: String): String {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

        private fun isEnabled(overlays: String, name: String): Boolean =
            overlays.lineSequence().any { it.trim() == "[x] $name" }

        @JvmStatic @BeforeClass
        fun prepareReferenceViewport() {
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") &&
                "x86_64" in android.os.Build.SUPPORTED_ABIS && android.os.Build.VERSION.SDK_INT == 36)
            check(deviceShell("getprop ro.kernel.qemu").trim() == "1")
            // The references use a flat display. Managed Pixel 7 images enable
            // model overlays that change the cutout and status-bar height.
            // Keep the reference pixels and threshold; restore the device after
            // this class so every functional test uses its normal viewport.
            val overlays = deviceShell("cmd overlay list --user current")
            for (name in listOf("com.android.internal.emulation.pixel_7", "com.android.systemui.emulation.pixel_7")) {
                if (isEnabled(overlays, name)) {
                    changedViewportOverlays.add(name)
                    deviceShell("cmd overlay disable --user current $name")
                    check(!isEnabled(deviceShell("cmd overlay list --user current"), name))
                }
            }
            if (changedViewportOverlays.isNotEmpty()) SystemClock.sleep(500)
        }

        @JvmStatic @AfterClass
        fun restoreDeviceViewport() {
            for (name in changedViewportOverlays.asReversed()) {
                deviceShell("cmd overlay enable --user current $name")
                check(isEnabled(deviceShell("cmd overlay list --user current"), name))
            }
            changedViewportOverlays.clear()
        }
    }
}
