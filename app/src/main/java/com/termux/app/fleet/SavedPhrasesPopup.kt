package com.termux.app.fleet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Position above the invoking icon rather than beside the screen edge. */
internal class AbovePhraseIcon(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
        (anchorBounds.top - popupContentSize.height - gap).coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedPhrasesPopup(input: TerminalInputController, generation: Long, availableHeight: Dp, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val loaded = remember { runCatching { SavedPhrases.load(context) } }
    var phrases by remember { mutableStateOf(loaded.getOrDefault(emptyList())) }
    var error by remember { mutableStateOf(if (loaded.isFailure) "Saved phrases could not be read. Existing data was kept." else "") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var enter by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    fun persist(next: List<SavedPhrase>): Boolean = runCatching { SavedPhrases.save(context, next) }.fold(
        onSuccess = { phrases = next; error = ""; true },
        onFailure = { error = "Could not save phrases. Please try again."; false }
    )
    if (editingId != null) {
        val focus = remember { FocusRequester() }
        Dialog(onDismissRequest = { editingId = null; error = "" },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(4.dp),
                contentAlignment = Alignment.Center) {
                val compact = maxHeight < 240.dp
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().testTag("saved-phrase-editor")) {
                    Column(Modifier.padding(if (compact) 4.dp else 16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (compact) "Phrase" else if (phrases.any { it.id == editingId }) "Edit phrase" else "Add phrase",
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editingId = null; error = "" }, modifier = Modifier.testTag("saved-phrase-cancel")) { Text("Cancel") }
                            Button(onClick = {
                                val phrase = SavedPhrase(requireNotNull(editingId), text, enter)
                                if (persist(if (phrases.any { it.id == phrase.id }) phrases.map { if (it.id == phrase.id) phrase else it } else phrases + phrase)) editingId = null
                            }, enabled = SavedPhrases.validText(text), modifier = Modifier.testTag("saved-phrase-save")) { Text("Save") }
                        }
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(value = text, onValueChange = { if (it.length <= SavedPhrases.MAX_LENGTH) text = it },
                                label = if (compact) null else ({ Text("Phrase") }), singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("saved-phrase-text"))
                            Row {
                                Checkbox(checked = enter, onCheckedChange = { enter = it }, modifier = Modifier.testTag("saved-phrase-editor-enter"))
                                Text("Enter after sending", modifier = Modifier.padding(top = 12.dp))
                            }
                            if (phrases.any { it.id == editingId }) TextButton(onClick = {
                                if (persist(phrases.filter { it.id != editingId })) editingId = null
                            }, modifier = Modifier.testTag("saved-phrase-delete")) { Text("Delete phrase") }
                            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        LaunchedEffect(editingId) { focus.requestFocus() }
    } else {
        val density = LocalDensity.current
        val position = remember(density) { with(density) { AbovePhraseIcon(8.dp.roundToPx(), 12.dp.roundToPx()) } }
        val width = (LocalConfiguration.current.screenWidthDp.dp - 24.dp).coerceAtMost(320.dp)
        Popup(popupPositionProvider = position, onDismissRequest = { if (!sending) onDismiss() },
            properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 8.dp, tonalElevation = 4.dp,
                modifier = Modifier.width(width).heightIn(max = availableHeight.coerceAtLeast(48.dp)).testTag("saved-phrases-popup")) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Saved phrases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = onDismiss, enabled = !sending, modifier = Modifier.testTag("saved-phrases-close")) { Text("Close") }
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("saved-phrases-error"))
                    if (phrases.isEmpty() && loaded.isSuccess) Text("No saved phrases yet.")
                    phrases.forEach { phrase ->
                        Column(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = {
                                if (!sending) {
                                    sending = true
                                    if (input.composerPhrase(phrase.text, phrase.enter, generation)) onDismiss()
                                    else { sending = false; error = "The terminal changed or is disconnected. Reopen saved phrases to send." }
                                }
                            }, enabled = !sending && input.canInput() && input.getInputGeneration() == generation,
                                modifier = Modifier.fillMaxWidth().testTag("saved-phrase-send-${phrase.id}")) {
                                Text(phrase.text, modifier = Modifier.fillMaxWidth())
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(Modifier.weight(1f)) {
                                    Checkbox(checked = phrase.enter, onCheckedChange = { checked -> persist(phrases.map { if (it.id == phrase.id) it.copy(enter = checked) else it }) },
                                        enabled = !sending, modifier = Modifier.testTag("saved-phrase-enter-${phrase.id}"))
                                    Text("Enter after sending", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { editingId = phrase.id; text = phrase.text; enter = phrase.enter }, enabled = !sending,
                                    modifier = Modifier.testTag("saved-phrase-edit-${phrase.id}")) { Text("Edit") }
                            }
                        }
                    }
                    TextButton(onClick = { val phrase = SavedPhrases.newPhrase(); editingId = phrase.id; text = phrase.text; enter = phrase.enter },
                        enabled = !sending && loaded.isSuccess && phrases.size < SavedPhrases.MAX_COUNT,
                        modifier = Modifier.testTag("saved-phrase-add")) { Text("Add phrase") }
                }
            }
        }
    }
}
