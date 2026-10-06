package com.termux.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.app.HostFilePreviewTestActivity.Fixture
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HostFilePreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun prepare() { Fixture.reset() }

    private fun launch(): ActivityScenario<HostFilePreviewTestActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch(Intent(context, HostFilePreviewTestActivity::class.java)
            .putExtra("sessionId", "origin-session").putExtra("hostId", "origin-host").putExtra("internalName", "managed-origin")
            .putExtra("reference", "/outside/project/fixture.txt"))
    }
    private fun waitFor(text: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    @Test fun previewRefreshesCurrentBytesAndCleansPrivateCopyOnClose() {
        launch().use { scenario ->
            waitFor("Verified current host file")
            Assert.assertEquals(listOf("origin-session|origin-host|managed-origin"), Fixture.origins.toList())
            val directory = Fixture.directories.single()
            Assert.assertEquals("Host preview fixture", directory.listFiles()!!.single().readText())
            Fixture.body = "Updated host file".toByteArray()
            compose.onNodeWithText("Refresh").performClick()
            compose.waitUntil(20_000) { Fixture.fetches.get() == 2 && directory.listFiles()?.firstOrNull()?.readText() == "Updated host file" }
            waitFor("Verified current host file")
            waitFor("Updated host file")
            compose.waitForIdle()
            Thread.sleep(500)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            Assert.assertTrue("Viewer body must contrast with its background", android.graphics.Color.red(screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)) > 200)
            PlatformTestStorageRegistry.getInstance().openOutputFile("host-file-text-preview.png").use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("Close").performClick()
            compose.waitUntil(20_000) { !directory.exists() }
        }
    }
    @Test fun backgroundCancelsPartialTransferAndRetryUsesSameOrigin() {
        Fixture.slow = true
        launch().use { scenario ->
            compose.waitUntil(20_000) { Fixture.fetches.get() == 1 }
            val directory = Fixture.directories.single()
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(20_000) { directory.listFiles()?.isEmpty() == true }
            Fixture.slow = false
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("interrupted in the background")
            compose.onNodeWithText("Retry").performClick()
            waitFor("Verified current host file")
            Assert.assertEquals(2, Fixture.fetches.get())
            Assert.assertTrue(Fixture.origins.all { it == "origin-session|origin-host|managed-origin" })
        }
    }
    @Test fun interactiveHtmlHasNoApplicationBridgeOrHostFileAccess() {
        Fixture.kind = "html"; Fixture.name = "interactive.html"
        Fixture.body = """<!doctype html><button id="counter" onclick="this.textContent='Clicked'">Click me</button><script>
            window.probe={bridge:typeof hostFilePreview,file:'pending',network:'pending'};
            fetch('file:///data/data/com.yaakovch.fleet/files/private').then(()=>probe.file='allowed').catch(()=>probe.file='blocked');
            fetch('https://example.invalid/preview-test').then(()=>probe.network='allowed').catch(()=>probe.network='blocked');
            </script>""".toByteArray()
        launch().use { scenario ->
            waitFor("Verified current host file")
            var web: WebView? = null
            compose.waitUntil(20_000) { scenario.onActivity { web = findWebView(it.window.decorView) }; web != null }
            var result = ""
            compose.waitUntil(20_000) {
                val latch = CountDownLatch(1)
                scenario.onActivity { web!!.evaluateJavascript("JSON.stringify(window.probe)") { result = it; latch.countDown() } }
                latch.await(2, TimeUnit.SECONDS)
                result.contains("blocked") && !result.contains("pending")
            }
            Assert.assertTrue(result, result.contains("undefined"))
            Assert.assertFalse(result, result.contains("allowed"))
            val latch = CountDownLatch(1)
            scenario.onActivity { web!!.evaluateJavascript("document.getElementById('counter').click();document.getElementById('counter').textContent") { result = it; latch.countDown() } }
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS))
            Assert.assertEquals("\"Clicked\"", result)
        }
    }
    @Test fun damagedPdfShowsRecoveryInsteadOfCrashing() {
        Fixture.kind = "pdf"; Fixture.name = "damaged.pdf"; Fixture.body = "%PDF-invalid".toByteArray()
        launch().use { waitFor("PDF preview is unavailable"); compose.onNodeWithText("Save").assertIsEnabled() }
    }
    @Test fun savePublishesExactVerifiedBytesToRealDownloadsProvider() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Fixture.name = "preview-${java.util.UUID.randomUUID()}.txt"
        val resolver = context.contentResolver
        var saved: android.net.Uri? = null
        try { launch().use {
            waitFor("Verified current host file")
            compose.onNodeWithText("Save").performClick()
            waitFor("Saved to content:")
            resolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.MediaColumns._ID), "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(Fixture.name), null)?.use { cursor ->
                Assert.assertTrue(cursor.moveToFirst())
                saved = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            }
            Assert.assertNotNull(saved)
            Assert.assertArrayEquals(Fixture.body, resolver.openInputStream(saved!!)?.use { stream -> stream.readBytes() })
        } } finally { saved?.let { resolver.delete(it, null, null) } }
    }
    @Test fun pdfProvidesPageNavigationAndZoom() {
        val document = android.graphics.pdf.PdfDocument()
        repeat(2) { index ->
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
            page.canvas.drawText("Host page ${index + 1}", 20f, 80f, android.graphics.Paint().apply { textSize = 22f })
            document.finishPage(page)
        }
        Fixture.body = java.io.ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray(); document.close()
        Fixture.kind = "pdf"; Fixture.name = "two-pages.pdf"
        launch().use {
            waitFor("Page of 2")
            compose.onNodeWithText("Page of 2").performTextReplacement("2")
            compose.onNodeWithText("Go").performClick()
            waitFor("Page 2")
            compose.onNodeWithText("Zoom 100%").assertExists()
        }
    }
    @Test fun imageProvidesZoomControl() {
        val image = android.graphics.Bitmap.createBitmap(600, 300, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        Fixture.body = java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); image.recycle()
        Fixture.kind = "image"; Fixture.name = "image.png"
        launch().use { waitFor("Verified current host file"); waitFor("Zoom 100%") }
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
