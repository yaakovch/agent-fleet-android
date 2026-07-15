package com.termux.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.termux.app.fleet.ToolPresentation
import com.termux.app.fleet.ToolPresentationBlock
import java.io.FileNotFoundException
import kotlin.math.abs
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetGoldenTest {
    @get:Rule val compose = createComposeRule()

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

    private fun setNative(state: NativeSessionUiState) {
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                NativeSessionScreen(
                    state = state, aiComposer = true, onToggleTerminal = {}, onRetry = {}, onLoadOlder = {},
                    onApproval = { _, _ -> }, onQuestion = { _: ConversationItem, _: List<ConversationAnswer> -> },
                    onShellCommand = {}, onShellKey = {}, onDirectory = {}, onRefreshDirectory = {}, onControlC = {},
                    onCloseSession = {}, onKillSession = {}, onScheduleContinue = {}, onDismissAttention = {}
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
    }
}
