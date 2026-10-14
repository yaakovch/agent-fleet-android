package com.termux.app.fleet

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Entry preference only; changing it never opens a keyboard in an existing pane. */
object TerminalKeyboardSettings {
    enum class Entry(val value: String) { Hidden("hidden"), Android("android") }
    private const val PREFERENCES = "agent-fleet-terminal-keyboard"
    private const val KEY_ENTRY = "direct-entry"

    fun load(context: Context): Entry {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.getString(KEY_ENTRY, "hidden")
        if (saved == "terminal") preferences.edit().putString(KEY_ENTRY, "hidden").apply()
        return if (saved == "android") Entry.Android else Entry.Hidden
    }

    fun save(context: Context, entry: Entry) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY_ENTRY, entry.value).apply()
    }

    @JvmStatic fun controller(context: Context) = TerminalInputController().apply {
        systemImeOnEntry = { load(context) == Entry.Android }
    }
}

@Composable
fun TerminalKeyboardSettingsCard() {
    val context = LocalContext.current
    var selected by remember(context) { mutableStateOf(TerminalKeyboardSettings.load(context)) }
    Card(Modifier.fillMaxWidth().testTag("terminal-keyboard-settings")) {
        Column(Modifier.fillMaxWidth().padding(18.dp).selectableGroup()) {
            Text("Direct keyboard", style = MaterialTheme.typography.titleMedium)
            Text("Choose how Direct starts. The ⌨ button always shows or hides your keyboard.",
                style = MaterialTheme.typography.bodyMedium)
            listOf(TerminalKeyboardSettings.Entry.Hidden to "Start with Android keyboard hidden",
                TerminalKeyboardSettings.Entry.Android to "Start with Android keyboard visible").forEach { (entry, label) ->
                Row(Modifier.fillMaxWidth().testTag("terminal-keyboard-entry-${entry.value}")
                    .selectable(selected == entry, role = Role.RadioButton, onClick = {
                        selected = entry; TerminalKeyboardSettings.save(context, entry)
                    }).padding(vertical = 8.dp)) {
                    RadioButton(selected = selected == entry, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(label)
                        Text(if (entry == TerminalKeyboardSettings.Entry.Hidden) "Terminal shortcuts stay visible" else "Use your Android keyboard for typing",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Applies when you enter Direct or open a shell. Composer still uses your Android keyboard.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
