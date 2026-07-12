package com.termux.app.fleet

import android.content.Context
import android.graphics.Typeface
import android.system.Os
import com.termux.app.TermuxActivity
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream

data class TerminalThemePreset(
    val id: String,
    val name: String,
    val foreground: String,
    val background: String,
    val cursor: String,
    val colors: List<String>,
    val managed: Boolean = true
) {
    init {
        require(colors.size == 16)
    }
}

data class TerminalAppearance(
    val themeId: String,
    val fontSize: Int,
    val fontFamily: String,
    val cursorStyle: String,
    val cursorBlink: Boolean,
    val horizontalMargin: Int,
    val verticalMargin: Int,
    val extraKeys: Boolean
)

object TerminalAppearanceStore {
    private const val PREFS = "agent-fleet-terminal-appearance"
    private const val COLORS_BEGIN = "# BEGIN Agent Fleet terminal colors"
    private const val COLORS_END = "# END Agent Fleet terminal colors"
    private const val PROPERTIES_BEGIN = "# BEGIN Agent Fleet terminal appearance"
    private const val PROPERTIES_END = "# END Agent Fleet terminal appearance"

    val themes = listOf(
        TerminalThemePreset(
            "system", "System", "#ffffff", "#000000", "#ffffff",
            listOf("#000000", "#cd0000", "#00cd00", "#cdcd00", "#6495ed", "#cd00cd", "#00cdcd", "#e5e5e5", "#7f7f7f", "#ff0000", "#00ff00", "#ffff00", "#5c5cff", "#ff00ff", "#00ffff", "#ffffff"),
            managed = false
        ),
        TerminalThemePreset(
            "classic", "Classic", "#ffffff", "#000000", "#ffffff",
            listOf("#000000", "#cd0000", "#00cd00", "#cdcd00", "#6495ed", "#cd00cd", "#00cdcd", "#e5e5e5", "#7f7f7f", "#ff0000", "#00ff00", "#ffff00", "#5c5cff", "#ff00ff", "#00ffff", "#ffffff")
        ),
        TerminalThemePreset(
            "dark", "Dark", "#e6e8ee", "#111318", "#afc6ff",
            listOf("#17191f", "#ff6b6b", "#69db7c", "#ffd43b", "#74c0fc", "#da77f2", "#66d9e8", "#dee2e6", "#868e96", "#ff8787", "#8ce99a", "#ffe066", "#a5d8ff", "#e599f7", "#99e9f2", "#f8f9fa")
        ),
        TerminalThemePreset(
            "light", "Light", "#202124", "#f8f9fa", "#174ea6",
            listOf("#202124", "#c5221f", "#188038", "#b06000", "#1967d2", "#a142f4", "#007b83", "#dadce0", "#5f6368", "#d93025", "#1e8e3e", "#e37400", "#1a73e8", "#a142f4", "#008b8b", "#ffffff")
        ),
        TerminalThemePreset(
            "oled", "OLED", "#f4f4f5", "#000000", "#c4b5fd",
            listOf("#000000", "#ff5c57", "#5af78e", "#f3f99d", "#57c7ff", "#ff6ac1", "#9aedfe", "#f1f1f0", "#686868", "#ff5c57", "#5af78e", "#f3f99d", "#57c7ff", "#ff6ac1", "#9aedfe", "#ffffff")
        ),
        TerminalThemePreset(
            "nord", "Nord", "#d8dee9", "#2e3440", "#88c0d0",
            listOf("#3b4252", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0", "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4")
        ),
        TerminalThemePreset(
            "dracula", "Dracula", "#f8f8f2", "#282a36", "#f8f8f2",
            listOf("#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6", "#8be9fd", "#f8f8f2", "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5", "#d6acff", "#ff92df", "#a4ffff", "#ffffff")
        ),
        TerminalThemePreset(
            "solarized-dark", "Solarized Dark", "#839496", "#002b36", "#93a1a1",
            listOf("#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5", "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3")
        ),
        TerminalThemePreset(
            "solarized-light", "Solarized Light", "#657b83", "#fdf6e3", "#586e75",
            listOf("#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5", "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3")
        )
    )

    fun load(context: Context): TerminalAppearance {
        val own = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val termux = TermuxAppSharedPreferences.build(context)
        val fontFile = TermuxConstants.TERMUX_FONT_FILE
        return TerminalAppearance(
            themeId = own.getString("theme", "system").orEmpty().takeIf { id -> themes.any { it.id == id } } ?: "system",
            fontSize = own.getInt("font-size", termux?.fontSize ?: defaultFontSize(context)).coerceIn(12, 72),
            fontFamily = own.getString("font-family", if (fontFile.isFile) "custom" else "system").orEmpty().takeIf { it in setOf("system", "sans", "custom") } ?: "system",
            cursorStyle = own.getString("cursor-style", "block").orEmpty().takeIf { it in setOf("block", "bar", "underline") } ?: "block",
            cursorBlink = own.getBoolean("cursor-blink", false),
            horizontalMargin = own.getInt("horizontal-margin", 3).coerceIn(0, 24),
            verticalMargin = own.getInt("vertical-margin", 0).coerceIn(0, 24),
            extraKeys = own.getBoolean("extra-keys", termux?.shouldShowTerminalToolbar() ?: true)
        )
    }

    fun defaults(context: Context) = TerminalAppearance(
        themeId = "system",
        fontSize = defaultFontSize(context),
        fontFamily = if (TermuxConstants.TERMUX_FONT_FILE.isFile) "custom" else "system",
        cursorStyle = "block",
        cursorBlink = false,
        horizontalMargin = 3,
        verticalMargin = 0,
        extraKeys = true
    )

    fun apply(context: Context, appearance: TerminalAppearance) {
        require(themes.any { it.id == appearance.themeId })
        require(appearance.fontSize in 12..72)
        require(appearance.fontFamily in setOf("system", "sans", "custom"))
        require(appearance.cursorStyle in setOf("block", "bar", "underline"))
        require(appearance.horizontalMargin in 0..24 && appearance.verticalMargin in 0..24)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme", appearance.themeId)
            .putInt("font-size", appearance.fontSize)
            .putString("font-family", appearance.fontFamily)
            .putString("cursor-style", appearance.cursorStyle)
            .putBoolean("cursor-blink", appearance.cursorBlink)
            .putInt("horizontal-margin", appearance.horizontalMargin)
            .putInt("vertical-margin", appearance.verticalMargin)
            .putBoolean("extra-keys", appearance.extraKeys)
            .apply()

        TermuxAppSharedPreferences.build(context)?.apply {
            setFontSize(appearance.fontSize)
            setShowTerminalToolbar(appearance.extraKeys)
        }
        writeTheme(appearance.themeId)
        writeTerminalProperties(appearance)
        TermuxActivity.updateTermuxActivityStyling(context)
    }

    @JvmStatic
    fun resolveTypeface(context: Context, compatibleFont: File): Typeface = when (load(context).fontFamily) {
        "custom" -> runCatching { Typeface.createFromFile(compatibleFont) }.getOrDefault(Typeface.MONOSPACE)
        "sans" -> Typeface.create("sans-serif-monospace", Typeface.NORMAL)
        else -> Typeface.MONOSPACE
    }

    fun replaceManagedBlock(content: String, begin: String, end: String, body: List<String>?): String {
        val lines = content.lines().toMutableList().also { if (it.lastOrNull() == "") it.removeAt(it.lastIndex) }
        val starts = lines.indices.filter { lines[it] == begin }
        val ends = lines.indices.filter { lines[it] == end }
        require(starts.size == ends.size && starts.size <= 1 && (starts.isEmpty() || starts[0] < ends[0])) {
            "Managed terminal appearance block is malformed"
        }
        if (starts.isNotEmpty()) lines.subList(starts[0], ends[0] + 1).clear()
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        if (body != null) {
            if (lines.isNotEmpty()) lines += ""
            lines += begin
            lines += body
            lines += end
        }
        return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    }

    private fun writeTheme(themeId: String) {
        val preset = themes.first { it.id == themeId }
        val file = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
        val existing = file.takeIf(File::isFile)?.readText().orEmpty()
        val body: List<String>? = if (preset.managed) buildList {
            add("foreground=${preset.foreground}")
            add("background=${preset.background}")
            add("cursor=${preset.cursor}")
            preset.colors.forEachIndexed { index, color -> add("color$index=$color") }
        } else null
        atomicWrite(file, replaceManagedBlock(existing, COLORS_BEGIN, COLORS_END, body))
    }

    private fun writeTerminalProperties(appearance: TerminalAppearance) {
        val file = TermuxPropertyConstants.getTermuxPropertiesFile() ?: TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE
        val existing = file.takeIf(File::isFile)?.readText().orEmpty()
        val body = listOf(
            "terminal-cursor-style=${appearance.cursorStyle}",
            "terminal-cursor-blink-rate=${if (appearance.cursorBlink) 600 else 0}",
            "terminal-margin-horizontal=${appearance.horizontalMargin}",
            "terminal-margin-vertical=${appearance.verticalMargin}"
        )
        atomicWrite(file, replaceManagedBlock(existing, PROPERTIES_BEGIN, PROPERTIES_END, body))
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Os.rename(temporary.absolutePath, file.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun defaultFontSize(context: Context): Int = TermuxAppSharedPreferences.getDefaultFontSizes(context)[0].coerceIn(12, 72)
}
