package com.termux.app.fleet

import android.util.Base64
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalScrollbackControllerTest {
    @Test fun hiddenSameTargetKeepsPinnedHistoryButClosingReleasesIt() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val view = com.termux.view.TerminalView(context, null)
        val output = object : com.termux.terminal.TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        val live = com.termux.terminal.TerminalEmulator(output, 20, 10, 1, 1, 100, null)
        val alternate = "\u001b[?1049hLive".toByteArray(); live.append(alternate, alternate.size)
        view.mEmulator = live
        val controller = TerminalScrollbackController(context)
        controller.setTerminalView(view)
        assertTrue(view.setLocalScrollback((0..40).joinToString("\n") { "pinned-$it" }.toByteArray(), 20, 10))
        assertTrue(view.enterPreparedLocalHistory())
        val pinned = view.displayedEmulator()
        val row = view.topRow
        controller.onStart(); controller.onStop(); controller.onStart()
        org.junit.Assert.assertSame(pinned, view.displayedEmulator())
        assertEquals(row, view.topRow); assertTrue(view.isLocalScrollbackActive)
        controller.close()
        assertFalse(view.isLocalScrollbackActive); assertFalse(view.hasLocalScrollback())
    }

    @Test fun paneFitsBelowTmuxChromeWithoutAcceptingAChangedWrappingWidth() {
        val snapshot = parsePaneScrollbackSnapshot(frame("wtmux-main", "row".toByteArray()), "wtmux-main")
        assertTrue(snapshot.fitsViewport(120, 33))
        assertTrue(snapshot.fitsViewport(120, 32))
        assertFalse(snapshot.fitsViewport(120, 31))
        assertFalse(snapshot.fitsViewport(119, 33))
    }
    @Test fun cacheUsesIncarnationAndDimensionsAndRetainsOnlyFourCaptures() {
        val cache = PaneCaptureCache()
        val identity = VerifiedSessionIdentity("host", "session", "a".repeat(64), "/fixture", "linux", "codex")
        val first = parsePaneScrollbackSnapshot(frame("wtmux-main", "row".toByteArray()), "wtmux-main")
        assertTrue(cache.put(identity, first) === first)
        assertTrue(cache.put(identity, first.copy()) === first)
        val resized = first.copy(columns = 121)
        assertTrue(cache.put(identity, resized) === resized)
        repeat(5) { index -> cache.put(identity.copy(incarnationId = index.toString().repeat(64)), first.copy()) }
        assertEquals(4, cache.size())
        assertFalse(cache.put(identity, first.copy()) === first)
    }
    @Test
    fun acceptsBoundedIntegrityCheckedAnsiPaneCapture() {
        val ansi = "\u001b[31molder row\u001b[0m\ncurrent row".toByteArray()
        val snapshot = parsePaneScrollbackSnapshot(frame("wtmux-main", ansi), "wtmux-main")

        assertEquals("wtmux-main", snapshot.session)
        assertEquals(120, snapshot.columns)
        assertEquals(32, snapshot.rows)
        assertEquals(1_900, snapshot.historyLines)
        assertTrue(snapshot.truncated)
        assertArrayEquals(ansi, snapshot.ansi)
    }

    @Test
    fun rejectsWrongSessionCorruptPayloadAndStructuredHistoryShape() {
        val ansi = "row".toByteArray()
        assertFails { parsePaneScrollbackSnapshot(frame("other", ansi), "wtmux-main") }
        assertFails {
            parsePaneScrollbackSnapshot(
                JSONObject(frame("wtmux-main", ansi)).put("revision", "0".repeat(64)).toString(),
                "wtmux-main"
            )
        }
        assertFails {
            parsePaneScrollbackSnapshot(
                JSONObject().put("protocolVersion", 2).put("type", "conversation.snapshot").toString(),
                "wtmux-main"
            )
        }
    }

    @Test
    fun rejectsCoercedMetadataAndNonCanonicalBase64() {
        val ansi = "row".toByteArray()
        assertFails {
            parsePaneScrollbackSnapshot(
                JSONObject(frame("wtmux-main", ansi)).put("capturedLines", "1").toString(),
                "wtmux-main"
            )
        }
        assertFails {
            parsePaneScrollbackSnapshot(
                JSONObject(frame("wtmux-main", ansi)).put("truncated", "false").toString(),
                "wtmux-main"
            )
        }
        assertFails {
            parsePaneScrollbackSnapshot(
                JSONObject(frame("wtmux-main", ansi)).put("ansiBase64", "${Base64.encodeToString(ansi, Base64.NO_WRAP)}\n").toString(),
                "wtmux-main"
            )
        }
    }

    private fun frame(session: String, ansi: ByteArray): String {
        val revision = MessageDigest.getInstance("SHA-256").digest(ansi).joinToString("") { "%02x".format(it) }
        return JSONObject()
            .put("protocolVersion", 1)
            .put("type", "pane.scrollback")
            .put("session", session)
            .put("columns", 120)
            .put("rows", 32)
            .put("historyLines", 1_900)
            .put("capturedLines", 2_000)
            .put("truncated", true)
            .put("revision", revision)
            .put("ansiBase64", Base64.encodeToString(ansi, Base64.NO_WRAP))
            .toString()
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
