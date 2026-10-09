package com.termux.app

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.app.fleet.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetCompatibilityTest {
    @get:Rule val compose = createAndroidComposeRule<DrawerComposeTestHostActivity>()

    @Test fun tableHasAlignedRowsAndCopiesAllColumns() {
        compose.setContent {
            MaterialTheme { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                MarkdownText("## Results\n\n| Measure | Value | Notes |\n|:---|---:|:---:|\n| Voting patterns | 15 | A longer note that wraps into multiple lines |\n| Public finance | 27 | Verified |")
            } } }
        }
        compose.onNodeWithTag("markdown-table-1").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("markdown-table-row-0") or hasTestTag("markdown-table-row-1") or hasTestTag("markdown-table-row-2"))
            .assertCountEquals(3)
        screenshot("compatibility-table-before-scroll")
        compose.runOnIdle {
            val texts = textViews(compose.activity.window.decorView).map { it.text.toString() }
            assertTrue("Android text views: $texts", texts.containsAll(listOf("Measure", "Value", "Notes", "Voting patterns", "15", "Public finance", "27")))
        }
        screenshot("compatibility-table-before-scroll")
        compose.onNodeWithTag("markdown-table-scroll-1").performTouchInput { swipeLeft() }
        screenshot("compatibility-table-after-scroll")
        compose.onNodeWithTag("markdown-table-copy-1").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.startsWith("Measure\tValue\tNotes"))
            assertTrue(copied.contains("Public finance\t27\tVerified"))
        }
    }

    @Test fun failedCreationKeepsWindowsFolderAndAllowsKnownSafeRetry() {
        val state = mutableStateOf(snapshot())
        val attempts = mutableListOf<List<String>>()
        var pending: ((Result<Unit>) -> Unit)? = null
        var dismissals = 0
        showDialog(state, { host, label, backend, tool, path, kind, callback ->
            attempts += listOf(host, label, backend, tool, path, kind); pending = callback
        }, { dismissals++ })
        selectWindowsFolder()
        compose.onNodeWithTag("session-create-submit").assertIsEnabled().performClick()
        compose.onNodeWithTag("session-create-submit").assertIsNotEnabled()
        compose.onNodeWithTag("session-create-label").assertIsNotEnabled()
        assertEquals(1, attempts.size)
        assertEquals(listOf("gaming", "home_through_work-strata-native", "windows", "codex", folder, "project"), attempts.single())
        compose.runOnIdle { pending!!(Result.failure(FleetUnavailableException("Fleet changed. Try again.", "stale_revision"))) }
        compose.onNodeWithTag("session-create-error").assertTextContains("Fleet changed", substring = true)
        compose.onNodeWithTag("session-create-label").assertTextContains("home_through_work-strata-native")
        compose.onNodeWithTag("session-create-submit").assertIsEnabled()
        assertEquals(0, dismissals)
        screenshot("compatibility-session-error")
        compose.onNodeWithTag("session-create-submit").performClick()
        compose.runOnIdle { pending!!(Result.success(Unit)) }
        assertEquals(2, attempts.size)
        assertEquals(1, dismissals)
    }

    @Test fun uncertainCreationBlocksRepeatAndExplainsHowToCheck() {
        showDialog(mutableStateOf(snapshot()), { _, _, _, _, _, _, callback ->
            callback(Result.failure(FleetUnavailableException("The result is unknown.", "unsafe_state")))
        })
        selectWindowsFolder()
        compose.onNodeWithTag("session-create-submit").performClick()
        compose.onNodeWithTag("session-create-submit").assertIsNotEnabled()
        compose.onNodeWithText("Check Sessions before starting another session.").performScrollTo().assertIsDisplayed()
        screenshot("compatibility-session-uncertain")
    }

    @Test fun hostGoingOfflineKeepsFolderAndTargetUntilItReturns() {
        val state = mutableStateOf(snapshot())
        showDialog(state, { _, _, _, _, _, _, _ -> fail("Offline host must not create") })
        selectWindowsFolder()
        compose.runOnIdle { state.value = snapshot("offline") }
        compose.onNodeWithTag("session-create-label").assertTextContains("home_through_work-strata-native")
        compose.onNodeWithText("✓ Windows Git Bash").assertExists()
        compose.onNodeWithTag("session-create-submit").assertIsNotEnabled()
        compose.runOnIdle { state.value = snapshot() }
        compose.onNodeWithTag("session-create-submit").assertIsEnabled()
    }

    private val folder = "C:\\projects\\home_through_work-strata-native"
    private fun snapshot(status: String = "healthy") = FleetSnapshot("revision", "", listOf(
        FleetHost("gaming", "Gaming Desktop", status, "wsl", null, emptySet())
    ), emptyList(), emptyList(), emptyList())

    private fun showDialog(state: androidx.compose.runtime.MutableState<FleetSnapshot>,
        confirm: (String, String, String, String, String, String, (Result<Unit>) -> Unit) -> Unit,
        dismiss: () -> Unit = {}) {
        compose.setContent { MaterialTheme {
            CreateSessionDialog(state.value,
                onListDirectory = { _, backend, _, callback -> callback(Result.success(
                    FleetDirectoryListing(backend, folder, null, emptyList(), emptyList(), false))) },
                onCreateDirectory = { _, _, _, _, _ -> fail("Unexpected folder creation") },
                onDismiss = dismiss, onConfirm = confirm)
        } }
    }
    private fun selectWindowsFolder() {
        compose.onNodeWithText("Windows Git Bash").performClick()
        compose.onNodeWithTag("session-create-use-folder").performScrollTo().performClick()
    }
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
