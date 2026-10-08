package com.termux.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic PTY output only. Run through the protected API36 runner. */
@RunWith(AndroidJUnit4::class)
class TerminalEntryPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitUi(timeout: Long, predicate: () -> Boolean) {
        var ready = false
        compose.waitUntil(timeout) { compose.runOnUiThread { ready = predicate() }; ready }
    }

    @Test fun recordBurstEntryAndReadingPosition() {
        check(android.os.Build.VERSION.SDK_INT == 36 && "x86_64" in android.os.Build.SUPPORTED_ABIS)
        lateinit var view: TerminalView
        var updates = 0
        var draws = 0
        val started = SystemClock.uptimeMillis()
        lateinit var session: TerminalSession
        compose.runOnUiThread { session = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
            "i=0; while [ \$i -lt 2000 ]; do printf 'row-%04d synthetic terminal output\\r\\n' \$i; i=\$((i+1)); done; printf 'SETTLED\\r\\n'; sleep 120"),
            arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 3000,
            object : TermuxTerminalSessionClientBase() {
                override fun onTextChanged(session: TerminalSession) { updates++; view.onScreenUpdated() }
            }) }
        try {
            compose.runOnUiThread {
                view = TerminalView(compose.activity, null)
                // Match activity_termux.xml: selection requests focus in touch mode.
                view.isFocusableInTouchMode = true
                view.viewTreeObserver.addOnDrawListener { draws++ }
                view.setTerminalViewClient(com.termux.app.fleet.WorkspaceTerminalViewClient {})
                view.setTextSize(26)
                compose.activity.setContentView(view)
                view.attachSession(session)
            }
            waitUi(60_000) { session.emulator?.screen?.transcriptText?.contains("SETTLED") == true }
            waitUi(10_000) { view.presentationState == TerminalView.PresentationState.READY }
            val stableMs = SystemClock.uptimeMillis() - started
            var before = 0
            var after = 0
            compose.runOnUiThread {
                view.topRow = -10
                before = view.topRow
                val bytes = "new output\r\n".toByteArray()
                session.emulator.append(bytes, bytes.size)
                view.onScreenUpdated()
                after = view.topRow
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                java.io.File(compose.activity.externalMediaDirs.first(), "terminal-entry.png").apply { parentFile?.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            java.io.File(compose.activity.externalMediaDirs.first(), "terminal-entry.txt").apply { parentFile?.mkdirs() }.outputStream().bufferedWriter().use {
                it.write("stableMs=$stableMs updates=$updates draws=$draws columns=${session.emulator.mColumns} rows=${session.emulator.mRows} readingBefore=$before readingAfter=$after paints=${view.terminalPaintCount} parsedBytes=${session.outputBytesProcessed} queueBytes=${session.pendingOutputBytes}\n")
            }
            assertTrue(updates > 0)
            assertEquals("Keep the same reading text as one new line enters history", before - 1, after)
            assertTrue("Entry must not paint every output callback", view.terminalPaintCount < updates)
            var historyColumns = 0
            compose.runOnUiThread {
                val event = android.view.MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_DOWN, 50f, 100f, 0)
                view.startTextSelectionMode(event); event.recycle()
                assertTrue("Selection must open", view.isSelectingText)
                val selected = view.selectedText
                assertFalse("Selection must contain terminal text", selected.isNullOrBlank())
                val next = "another line\r\n".toByteArray()
                session.emulator.append(next, next.size); view.onScreenUpdated()
                assertTrue(view.isSelectingText); assertEquals(selected, view.selectedText)
            }
            // The production selection controller guards accidental dismissal for 300ms.
            SystemClock.sleep(350)
            compose.runOnUiThread {
                view.stopTextSelectionMode()
                assertFalse(view.isSelectingText)
                val alternate = "\u001b[?1049h".toByteArray()
                session.emulator.append(alternate, alternate.size); view.onScreenUpdated()
                val history = (0 until 300).joinToString("\r\n") { "history-$it synthetic" }.toByteArray()
                assertTrue(view.setLocalScrollback(history, session.emulator.mColumns, session.emulator.mRows))
                assertTrue(view.enterPreparedLocalHistory())
                val snapshot = view.displayedEmulator().screen.transcriptText
                val next = "another line\r\n".toByteArray()
                session.emulator.append(next, next.size); view.onScreenUpdated()
                assertEquals(snapshot, view.displayedEmulator().screen.transcriptText)
                historyColumns = session.emulator.mColumns
                view.setTextSize(28)
            }
            waitUi(10_000) { session.emulator.mColumns != historyColumns }
            compose.runOnUiThread {
                assertTrue("Resizing must retain the History reader", view.isLocalScrollbackActive)
                assertTrue(view.displayedEmulator().screen.transcriptText.contains("history-299"))
                view.returnToLiveTerminal()
            }
        } finally { compose.runOnUiThread {
            java.io.File(compose.activity.externalMediaDirs.first(), "terminal-entry-debug.txt").apply { parentFile?.mkdirs() }.writeText(
                "pid=${session.pid} view=${view.width}x${view.height} columns=${session.emulator?.mColumns} rows=${session.emulator?.mRows} updates=$updates draws=$draws text=${session.emulator?.screen?.transcriptText?.takeLast(2048)}")
            session.finishIfRunning()
        } }
    }
    @Test fun recordTwentyMatchedEntries() {
        val samples = mutableListOf<Long>()
        repeat(20) { trial ->
            lateinit var view: TerminalView
            lateinit var session: TerminalSession
            val started = SystemClock.uptimeMillis()
            compose.runOnUiThread {
                session = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                    "i=0; while [ \$i -lt 200 ]; do printf 'matched-%04d synthetic output\\r\\n' \$i; i=\$((i+1)); done; printf 'SETTLED\\r\\n'; sleep 120"),
                    arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 1000,
                    object : TermuxTerminalSessionClientBase() {
                        override fun onTextChanged(session: TerminalSession) { view.onScreenUpdated() }
                    })
                view = TerminalView(compose.activity, null)
                view.setTerminalViewClient(com.termux.app.fleet.WorkspaceTerminalViewClient {})
                view.setTextSize(26)
                compose.activity.setContentView(view)
                view.attachSession(session)
            }
            try {
                waitUi(30_000) { session.emulator?.screen?.transcriptText?.contains("SETTLED") == true }
                waitUi(10_000) { view.presentationState == TerminalView.PresentationState.READY }
                samples.add(SystemClock.uptimeMillis() - started)
            } finally { compose.runOnUiThread { session.finishIfRunning() } }
        }
        java.io.File(compose.activity.externalMediaDirs.first(), "terminal-matched-times.txt").writeText(samples.joinToString("\n"))
    }

    @Test fun busyTerminalOffersShowLiveAndFailureRevealsImmediately() {
        lateinit var view: TerminalView
        lateinit var session: TerminalSession
        compose.runOnUiThread {
            session = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                "while true; do printf '.'; sleep 0.05; done"), arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 1000,
                object : TermuxTerminalSessionClientBase() {
                    override fun onTextChanged(session: TerminalSession) { view.onScreenUpdated() }
                })
            view = TerminalView(compose.activity, null)
            view.setTerminalViewClient(com.termux.app.fleet.WorkspaceTerminalViewClient {})
            view.setTextSize(26); compose.activity.setContentView(view); view.attachSession(session)
        }
        try {
            waitUi(10_000) { view.isShowLiveOffered }
            compose.runOnUiThread {
                assertEquals(TerminalView.PresentationState.LOADING, view.presentationState)
                assertEquals(0L, view.terminalPaintCount)
                assertTrue(view.performClick())
                assertEquals(TerminalView.PresentationState.READY, view.presentationState)
                view.setTextSize(28)
                session.finishIfRunning()
            }
            waitUi(10_000) { view.presentationState == TerminalView.PresentationState.FAILED }
        } finally { compose.runOnUiThread { session.finishIfRunning() } }
    }

    @Test fun exitedProcessDrainsCompleteOutputTail() {
        lateinit var view: TerminalView
        lateinit var session: TerminalSession
        compose.runOnUiThread {
            session = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                "i=0; while [ \$i -lt 3000 ]; do printf 'tail-%04d\\r\\n' \$i; i=\$((i+1)); done; printf 'TAIL_COMPLETE\\r\\n'"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 4000,
                object : TermuxTerminalSessionClientBase() {
                    override fun onTextChanged(session: TerminalSession) { view.onScreenUpdated() }
                })
            view = TerminalView(compose.activity, null)
            view.setTerminalViewClient(com.termux.app.fleet.WorkspaceTerminalViewClient {})
            view.setTextSize(26); compose.activity.setContentView(view); view.attachSession(session)
        }
        try {
            waitUi(30_000) { session.pid == -1 }
            compose.runOnUiThread {
                val text = session.emulator.screen.transcriptText
                var previous = -1
                repeat(3000) { row ->
                    val index = text.indexOf("tail-%04d".format(row))
                    assertTrue("Every output row must survive in order: $row", index > previous)
                    previous = index
                }
                assertTrue(text.contains("TAIL_COMPLETE")); assertEquals(0, session.pendingOutputBytes)
                assertEquals(TerminalView.PresentationState.FAILED, view.presentationState)
            }
        } finally { compose.runOnUiThread { session.finishIfRunning() } }
    }

    @Test fun fragmentedUnicodeInputReachesPtyExactlyOnce() {
        lateinit var view: TerminalView
        lateinit var session: TerminalSession
        compose.runOnUiThread {
            session = TerminalSession("/system/bin/sh", "/", arrayOf("sh", "-c",
                "printf 'INPUT_READY\\r\\n'; IFS= read -r line; printf 'INPUT_RECEIPT:%s\\r\\n' \"\$line\"; sleep 120"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 1000,
                object : TermuxTerminalSessionClientBase() {
                    override fun onTextChanged(session: TerminalSession) { view.onScreenUpdated() }
                })
            view = TerminalView(compose.activity, null)
            view.setTerminalViewClient(com.termux.app.fleet.WorkspaceTerminalViewClient {})
            view.setTextSize(26); compose.activity.setContentView(view); view.attachSession(session)
        }
        try {
            waitUi(10_000) { view.presentationState == TerminalView.PresentationState.READY }
            val payload = "שלום 😀 unique input"
            compose.runOnUiThread {
                val bytes = (payload + "\n").toByteArray()
                bytes.indices.forEach { session.write(bytes, it, 1) }
            }
            waitUi(10_000) { session.emulator.screen.transcriptText.contains("INPUT_RECEIPT:$payload") }
            compose.runOnUiThread {
                assertEquals(1, Regex("INPUT_RECEIPT:").findAll(session.emulator.screen.transcriptText).count())
            }
        } finally { compose.runOnUiThread { session.finishIfRunning() } }
    }

}
