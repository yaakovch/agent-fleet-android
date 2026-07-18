package com.termux.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.fleet.TerminalAppearance
import com.termux.app.fleet.TerminalAppearanceStore
import com.termux.app.fleet.TerminalThemePreset
import com.termux.app.fleet.AgentFleetDisplayDensity
import com.termux.app.fleet.AgentFleetDisplayDensityStore
import com.termux.shared.termux.TermuxConstants

class TerminalAppearanceActivity : ComponentActivity() {
    private var appearance by mutableStateOf<TerminalAppearance?>(null)
    private var displayDensity by mutableStateOf<AgentFleetDisplayDensity?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = TerminalAppearanceStore.load(this)
        displayDensity = AgentFleetDisplayDensityStore.load(this)
        setContent {
            AgentFleetTheme {
                appearance?.let { current ->
                    displayDensity?.let { density ->
                        TerminalAppearanceScreen(
                            appearance = current,
                            displayDensity = density,
                            customFontAvailable = TermuxConstants.TERMUX_FONT_FILE.isFile,
                            onBack = ::finish,
                            onChange = ::applyAppearance,
                            onDensityChange = ::applyDisplayDensity,
                            onReset = {
                                applyAppearance(TerminalAppearanceStore.defaults(this))
                                applyDisplayDensity(AgentFleetDisplayDensityStore.defaults())
                            }
                        )
                    }
                }
            }
        }
    }

    private fun applyAppearance(value: TerminalAppearance) {
        runCatching { TerminalAppearanceStore.apply(this, value) }
            .onSuccess { appearance = value }
            .onFailure { Toast.makeText(this, it.message ?: "Appearance could not be applied", Toast.LENGTH_LONG).show() }
    }

    private fun applyDisplayDensity(value: AgentFleetDisplayDensity) {
        runCatching {
            AgentFleetDisplayDensityStore.save(this, value)
            TermuxActivity.updateTermuxActivityStyling(this)
        }.onSuccess { displayDensity = value.bounded() }
            .onFailure { Toast.makeText(this, it.message ?: "Display density could not be applied", Toast.LENGTH_LONG).show() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalAppearanceScreen(
    appearance: TerminalAppearance,
    displayDensity: AgentFleetDisplayDensity,
    customFontAvailable: Boolean,
    onBack: () -> Unit,
    onChange: (TerminalAppearance) -> Unit,
    onDensityChange: (AgentFleetDisplayDensity) -> Unit,
    onReset: () -> Unit
) {
    val theme = TerminalAppearanceStore.themes.first { it.id == appearance.themeId }
    Scaffold(
        modifier = Modifier.testTag("terminal-appearance-screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Fleet appearance", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text("Changes apply immediately", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { TerminalPreview(theme, appearance) }
            item {
                AppearanceCard {
                    SectionTitle("Native conversation", "Reading-first transcript density")
                    Stepper(
                        "Text size", displayDensity.nativeBodySp, "sp",
                        AgentFleetDisplayDensity.MIN_NATIVE_BODY_SP,
                        AgentFleetDisplayDensity.MAX_NATIVE_BODY_SP,
                        1
                    ) { onDensityChange(displayDensity.copy(nativeBodySp = it)) }
                }
            }
            item {
                AppearanceCard {
                    SectionTitle("Session drawer", "Compact rows show more sessions")
                    Stepper(
                        "Title size", displayDensity.drawerTitleSp, "sp",
                        AgentFleetDisplayDensity.MIN_DRAWER_TITLE_SP,
                        AgentFleetDisplayDensity.MAX_DRAWER_TITLE_SP,
                        1
                    ) { onDensityChange(displayDensity.copy(drawerTitleSp = it)) }
                }
            }
            item { SectionTitle("Color theme", theme.name) }
            TerminalAppearanceStore.themes.chunked(2).forEach { rowThemes ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowThemes.forEach { preset ->
                            ThemeButton(preset, appearance.themeId == preset.id, Modifier.weight(1f)) {
                                onChange(appearance.copy(themeId = preset.id))
                            }
                        }
                        if (rowThemes.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            item {
                AppearanceCard {
                    SectionTitle("Text", "Readable without wasting terminal space")
                    Stepper("Font size", appearance.fontSize, "px", 12, 72, 2) {
                        onChange(appearance.copy(fontSize = it))
                    }
                    Text("Font", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    ChoiceRow(
                        choices = listOf("system" to "System Mono", "sans" to "Sans Mono", "custom" to "Custom font"),
                        selected = appearance.fontFamily,
                        isEnabled = { it != "custom" || customFontAvailable }
                    ) { onChange(appearance.copy(fontFamily = it)) }
                    if (!customFontAvailable) {
                        Text(
                            "Put a compatible font at ~/.termux/font.ttf to enable Custom font.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                AppearanceCard {
                    SectionTitle("Cursor", "Shape and motion")
                    ChoiceRow(
                        choices = listOf("block" to "Block", "bar" to "Bar", "underline" to "Underline"),
                        selected = appearance.cursorStyle
                    ) { onChange(appearance.copy(cursorStyle = it)) }
                    ToggleRow("Blink cursor", appearance.cursorBlink) { onChange(appearance.copy(cursorBlink = it)) }
                }
            }
            item {
                AppearanceCard {
                    SectionTitle("Spacing", "Padding around the terminal grid")
                    Stepper("Side margin", appearance.horizontalMargin, "dp", 0, 24, 1) {
                        onChange(appearance.copy(horizontalMargin = it))
                    }
                    Stepper("Top and bottom", appearance.verticalMargin, "dp", 0, 24, 1) {
                        onChange(appearance.copy(verticalMargin = it))
                    }
                }
            }
            item {
                AppearanceCard {
                    SectionTitle("Controls", "Classic terminal input")
                    ToggleRow("Show extra-key row", appearance.extraKeys) { onChange(appearance.copy(extraKeys = it)) }
                    Stepper(
                        "Shortcut row", displayDensity.terminalShortcutHeightDp, "dp",
                        AgentFleetDisplayDensity.MIN_TERMINAL_SHORTCUT_HEIGHT_DP,
                        AgentFleetDisplayDensity.MAX_TERMINAL_SHORTCUT_HEIGHT_DP,
                        2
                    ) { onDensityChange(displayDensity.copy(terminalShortcutHeightDp = it)) }
                }
            }
            item {
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Reset Agent Fleet appearance", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalPreview(theme: TerminalThemePreset, appearance: TerminalAppearance) {
    val background = color(theme.background)
    val foreground = color(theme.foreground)
    val accent = color(theme.colors[4])
    val previewFamily = if (appearance.fontFamily == "sans") FontFamily.SansSerif else FontFamily.Monospace
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                theme.colors.take(8).forEach { value -> Box(Modifier.weight(1f).size(8.dp).background(color(value), RoundedCornerShape(3.dp))) }
            }
            Text("Agent Fleet terminal", color = accent, fontFamily = previewFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("~/projects/wtmux  main", color = foreground, fontFamily = previewFamily, fontSize = 15.sp)
            Text("$ wtmux", color = foreground, fontFamily = previewFamily, fontSize = 15.sp)
            Text("2 hosts · 8 sessions", color = color(theme.colors[8]), fontFamily = previewFamily, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ThemeButton(preset: TerminalThemePreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp)) {
            Text(preset.name, maxLines = 2, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp)) {
            Text(preset.name, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AppearanceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChoiceRow(
    choices: List<Pair<String, String>>,
    selected: String,
    isEnabled: (String) -> Boolean = { true },
    onSelect: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = isEnabled(value),
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, suffix: String, minimum: Int, maximum: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text("$value $suffix", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(minimum)) }, enabled = value > minimum) { Text("−", fontSize = 20.sp) }
        OutlinedButton(onClick = { onChange((value + step).coerceAtMost(maximum)) }, enabled = value < maximum) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun color(value: String) = Color(AndroidColor.parseColor(value))
