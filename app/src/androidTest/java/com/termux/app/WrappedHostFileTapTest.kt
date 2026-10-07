package com.termux.app

import android.content.Intent
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.app.HostFilePreviewTestActivity.Fixture
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WrappedHostFileTapTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualTapsOpenEveryAbsoluteAndRelativeSegmentInLiveAndHistory() {
        val cases = listOf(
            "  Open (/home/user/projects/very-long-project/\r\n  reports/wrapped-report.md)" to "/home/user/projects/very-long-project/reports/wrapped-report.md",
            "  Open (reports/very-long-\r\n  report.md)" to "reports/very-long-report.md"
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<WrappedLinksTestActivity>(Intent(context, WrappedLinksTestActivity::class.java)).use { scenario ->
            for ((output, destination) in cases) for (history in listOf(false, true)) {
                Fixture.reset(); Fixture.expectedReference = destination
                Fixture.body = "Complete wrapped reference from origin host: $destination".toByteArray()
                scenario.onActivity { it.show(output.replace("\r\n", if (history) "\n" else "\r\n"), history = history) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Assert.assertEquals(0, Fixture.fetches.get())
                for (row in 0..1) {
                    var position = 0f to 0f
                    scenario.onActivity { position = it.tapPosition(row, if (row == 0) 9 else 3) }
                    PlatformTestStorageRegistry.getInstance().openOutputFile("terminal-before-${if (destination.startsWith('/')) "absolute" else "relative"}-${if (history) "history" else "live"}-$row.png").use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    val time = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 50 else 0, action, position.first, position.second, 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        Assert.assertTrue(automation.injectInputEvent(event, true)); event.recycle()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { Assert.assertEquals("Actual terminal tap must resolve before the viewer renders", destination, it.selected) }
                    compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithText("Complete wrapped reference from origin host: $destination", substring = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                    Assert.assertEquals(row + 1, Fixture.fetches.get())
                    Assert.assertEquals("origin-session|origin-host|managed-origin", Fixture.origins.last())
                    scenario.onActivity { Assert.assertEquals(destination, it.selected) }
                    val screenshot = automation.takeScreenshot()
                    PlatformTestStorageRegistry.getInstance().openOutputFile("wrapped-${if (destination.startsWith('/')) "absolute" else "relative"}-${if (history) "history" else "live"}-$row.png").use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    compose.onNodeWithText("Close").performClick()
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
            }
        }
    }
}
