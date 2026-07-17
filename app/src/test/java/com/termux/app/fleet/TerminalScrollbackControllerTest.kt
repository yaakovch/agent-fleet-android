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
