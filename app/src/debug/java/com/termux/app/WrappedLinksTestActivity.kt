package com.termux.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.app.fleet.WorkspaceTerminalViewClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.lang.reflect.Proxy

/** Deterministic terminal input; production gestures, resolver and preview UI. */
class WrappedLinksTestActivity : Activity() {
    lateinit var terminal: TerminalView
    var selected: String? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        terminal = TerminalView(this, null)
        terminal.setTextSize(20)
        terminal.setTerminalViewClient(WorkspaceTerminalViewClient { reference ->
            selected = reference
            startActivity(Intent(this, HostFilePreviewTestActivity::class.java)
                .putExtra("sessionId", "origin-session").putExtra("hostId", "origin-host").putExtra("internalName", "managed-origin")
                .putExtra("reference", reference))
        })
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        layout.addView(TextView(this).apply { text = "Origin host · synthetic terminal · Live"; setTextColor(android.graphics.Color.WHITE) })
        layout.addView(terminal, LinearLayout.LayoutParams(-1, -1))
        setContentView(layout)
    }
    fun show(output: String, width: Int = 72, history: Boolean = false) {
        val sink = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {}
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader, arrayOf(TerminalSessionClient::class.java)) { _, method, _ -> if (method.name == "getTerminalCursorStyle") 0 else null } as TerminalSessionClient
        val emulator = TerminalEmulator(sink, width, 12, 10, 20, 200, client)
        TerminalView::class.java.getDeclaredField("mEmulator").apply { isAccessible = true }.set(terminal, emulator)
        TerminalView::class.java.getDeclaredField("mTopRow").apply { isAccessible = true }.setInt(terminal, 0)
        if (history) {
            check(terminal.setLocalScrollback((output + "\n" + "padding\n".repeat(25)).toByteArray(), width, 12))
            TerminalView::class.java.getDeclaredField("mLocalScrollbackActive").apply { isAccessible = true }.setBoolean(terminal, true)
            val local = terminal.displayedEmulator()
            val first = (-local.screen.activeTranscriptRows until local.mRows).first { local.screen.getLineTextAt(it).trimStart().startsWith("Open (") }
            TerminalView::class.java.getDeclaredField("mTopRow").apply { isAccessible = true }.setInt(terminal, first)
        } else {
            val bytes = output.toByteArray(); emulator.append(bytes, bytes.size)
            TerminalView::class.java.getDeclaredField("mLocalScrollbackActive").apply { isAccessible = true }.setBoolean(terminal, false)
        }
        terminal.invalidate()
    }
    fun tapPosition(visibleRow: Int, column: Int): Pair<Float, Float> {
        val renderer = TerminalView::class.java.getDeclaredField("mRenderer").apply { isAccessible = true }.get(terminal)
        fun metric(name: String) = (renderer.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(renderer) as Number).toFloat()
        check(metric("mFontWidth") > 0 && metric("mFontLineSpacing") > 0)
        val location = IntArray(2); terminal.getLocationOnScreen(location)
        return location[0] + (column + .5f) * metric("mFontWidth") to location[1] + metric("mFontLineSpacingAndAscent") + (visibleRow + .5f) * metric("mFontLineSpacing")
    }
}
