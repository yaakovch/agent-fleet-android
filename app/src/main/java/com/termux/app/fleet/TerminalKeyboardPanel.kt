package com.termux.app.fleet

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Workspace owns one dock outside its pane tree. Fullscreen uses the local controls host. */
val LocalWindowTerminalDock = staticCompositionLocalOf { false }

@Composable
fun TerminalKeyboardPanel(input: TerminalInputController) {
    if (!input.wantsTerminalKeyboard() || !input.canInput()) return
    val host = LocalView.current
    val config = LocalConfiguration.current
    var width by remember { mutableStateOf(0) }
    val wide = width / host.resources.displayMetrics.density >= 600 && config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 600
    val rows = if (wide) TerminalKeyboardLayout.wideRows(input.gestures.page) else TerminalKeyboardLayout.rows(input.gestures.page)
    val rowHeight = if (wide) 40.dp else 48.dp
    val requiredHeight = rows.size * (if (wide) 40 else 48) + 8
    var laidOut by remember { mutableStateOf(false) }
    var hideFailed by remember(input, input.getInputGeneration(), config.screenWidthDp, config.screenHeightDp) { mutableStateOf(false) }
    val generation = input.getInputGeneration()
    LaunchedEffect(input, width, config.screenWidthDp, config.screenHeightDp, config.fontScale) {
        input.keyboardGeometryChanged()
    }
    DisposableEffect(input, generation) { onDispose { input.updateKeyboardReady(false); input.gestures.cancel() } }
    BackHandler { input.dismissKeyboard() }
    // Observe real root IME visibility (including floating keyboards), then allow two layout frames.
    LaunchedEffect(input, generation, width, laidOut, requiredHeight, config.screenWidthDp, config.screenHeightDp) {
        input.updateKeyboardReady(false)
        var context = host.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val activity = context as? Activity ?: return@LaunchedEffect
        WindowCompat.getInsetsController(activity.window, host).hide(WindowInsetsCompat.Type.ime())
        var settled = 0
        var attempts = 0
        while (input.getInputGeneration() == generation && input.wantsTerminalKeyboard()) {
            val insets = ViewCompat.getRootWindowInsets(host)
            val hidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
            val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
            val rootHeight = (host.rootView.height - (bars?.top ?: 0) - (bars?.bottom ?: 0)) / host.resources.displayMetrics.density
            val fits = width / host.resources.displayMetrics.density >= 320 && rootHeight >= requiredHeight + 160 &&
                config.screenHeightDp >= requiredHeight + 160 && config.screenWidthDp >= 320 && config.fontScale < 1.5f
            settled = if (hidden && laidOut && fits) settled + 1 else 0
            input.updateKeyboardReady(settled >= 3)
            attempts++
            hideFailed = attempts > 90 && settled == 0
            delay(16)
        }
    }
    if (hideFailed || config.fontScale >= 1.5f || config.screenHeightDp < requiredHeight + 160 || config.screenWidthDp < 320) {
        Row(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.testTag("terminal-keyboard-fallback"), verticalAlignment = Alignment.CenterVertically) {
            Text("Use System keyboard in this window", Modifier.weight(1f), fontSize = 12.sp)
            TextButton(onClick = { input.chooseSurface(false) }, modifier = Modifier.testTag("terminal-keyboard-system")) { Text("System") }
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()
        .testTag("terminal-keyboard-dock").onSizeChanged { width = it.width; laidOut = it.height > 0 }
        .semantics { contentDescription = "Terminal keyboard"; stateDescription = if (input.keyboardReady) "Ready" else "Waiting for system keyboard to close" }) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    row.forEach { key -> TerminalKeyboardButton(input, key, Modifier.weight(key.width).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun TerminalKeyboardButton(input: TerminalInputController, key: TerminalKeyboardKey, modifier: Modifier) {
    val generation = input.getInputGeneration()
    val page = input.gestures.page
    val enabled = input.isAppKeyboardAllowed()
    val scope = rememberCoroutineScope()
    val selected = key.modifier != 0 && input.gestures.bits and key.modifier != 0
    val label = key.character(input.gestures.bits)?.let { if (it == ' ') "Space" else it.toString() } ?: key.text
    fun activate(): Boolean {
        if (!input.isAppKeyboardAllowed() || generation != input.getInputGeneration()) return false
        if (key.page != null) { input.changeKeyboardPage(key.page); return true }
        if (key.modifier != 0) { input.gestures.accessibilityModifier(key.modifier); return true }
        return input.appKey(key, input.gestures.reserve(), generation)
    }
    Box(modifier.testTag("terminal-app-key-${key.name}").semantics {
        role = Role.Button
        contentDescription = when (key.name) { "SHIFT_LEFT" -> "Shift plus Left Arrow"; "BACKSPACE" -> "Backspace"; "ENTER" -> "Enter"; "SHIFT" -> "Shift"; "CTRL" -> "Control"; "ALT" -> "Alt";
            "LEFT" -> "Left Arrow"; "RIGHT" -> "Right Arrow"; "UP" -> "Up Arrow"; "DOWN" -> "Down Arrow";
            "PAGE_Symbols" -> "Symbols page"; "PAGE_Fn" -> "Function and navigation page"; "PAGE_ABC" -> "Letters page"; else -> label }
        if (!enabled) disabled()
        if (key.modifier != 0) stateDescription = if (selected) "Active" else "Inactive"
        onClick { activate() }
    }.pointerInput(input, generation, page, key.name, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val token = down.id.value
            if (key.modifier != 0) input.gestures.downModifier(token, key.modifier)
            val bits = if (key.modifier == 0 && key.page == null) input.gestures.reserve() else 0
            val accepted = when {
                key.modifier != 0 -> true
                key.page != null -> { input.changeKeyboardPage(key.page); true }
                else -> input.appKey(key, bits, generation)
            }
            val repeat = if (accepted && key.repeatable) scope.launch {
                delay(400)
                while (input.getInputGeneration() == generation && input.gestures.page == page && input.isAppKeyboardAllowed()) {
                    if (!input.appKey(key, bits, generation)) break
                    delay(60) // No backlog or reconnect replay.
                }
            } else null
            var canceled = true
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.position.x < 0 || change.position.y < 0 || change.position.x >= size.width || change.position.y >= size.height) break
                    change.consume()
                    if (!change.pressed) { canceled = false; break }
                }
            } finally {
                repeat?.cancel()
                if (key.modifier != 0) input.gestures.upModifier(token, canceled)
            }
        }
    }.padding(2.dp).background(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        Text(label, fontSize = if (key.printable != null && key.printable != ' ' || key.name in setOf("SHIFT", "BACKSPACE", "ENTER", "LEFT", "RIGHT", "UP", "DOWN")) 18.sp else 12.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), maxLines = 1)
    }
}
