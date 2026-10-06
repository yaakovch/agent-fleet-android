package com.termux.app.fleet

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class HostFileReference(val start: Int, val end: Int, val target: String)

object HostFileReferences {
    private val control = Regex("[\\x00-\\x1f\\x7f-\\x9f]")
    private val scheme = Regex("^[A-Za-z][A-Za-z\\d+.-]*:")
    private val drive = Regex("^[A-Za-z]:[/\\\\]")
    private val pattern = Regex("(?:\"([^\"\\r\\n]+)\"|'([^'\\r\\n]+)'|`([^`\\r\\n]+)`)|(?:file://[^\\s<>\"'`]+|[A-Za-z]:[/\\\\][^\\s<>\"'`]+|(?:\\.{1,2}/|/)[^\\s<>\"'`]+|[A-Za-z\\d_.-]+(?:[/\\\\][A-Za-z\\d_.-]+)+\\.[A-Za-z\\d]{1,16})")

    @JvmStatic fun target(value: String, explicit: Boolean = false): String? {
        if (value.isEmpty() || value.length > 2048 || control.containsMatchIn(value) || value.startsWith("\\\\") || value.startsWith("//")) return null
        if (value.startsWith("file:", true)) {
            return runCatching {
                val uri = URI(value)
                if (!value.startsWith("file://", true) || uri.scheme.lowercase() != "file" || !uri.rawAuthority.orEmpty().lowercase().let { it == "" || it == "localhost" } || uri.rawQuery != null || uri.rawFragment != null) return null
                val raw = uri.rawPath ?: return null
                // Decode percent-encoded bytes with strict UTF-8, matching the other clients.
                val decoded = StringBuilder()
                var index = 0
                while (index < raw.length) {
                    if (raw[index] != '%') { decoded.append(raw[index++]); continue }
                    val bytes = java.io.ByteArrayOutputStream()
                    while (index < raw.length && raw[index] == '%') {
                        if (index + 2 >= raw.length) return null
                        bytes.write(raw.substring(index + 1, index + 3).toInt(16)); index += 3
                    }
                    decoded.append(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())))
                }
                if (control.containsMatchIn(decoded) || decoded.startsWith("//")) null else value
            }.getOrNull()
        }
        if (scheme.containsMatchIn(value) && !drive.containsMatchIn(value)) return null
        if (value.startsWith('/') || drive.containsMatchIn(value) || Regex("^(?:\\.\\.?)[/\\\\]").containsMatchIn(value)) return value
        if ((explicit || '/' in value || '\\' in value) && Regex("^[^<>\"|?*]+\\.[A-Za-z\\d]{1,16}$").matches(value)) return value
        return null
    }

    @JvmStatic fun extract(text: String): List<HostFileReference> = buildList {
        for (match in pattern.findAll(text)) {
            val quoted = (1..3).map { match.groups[it]?.value }.firstOrNull { it != null }
            val candidate = quoted ?: match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            val target = target(candidate) ?: continue
            val start = match.range.first + if (quoted == null) 0 else 1
            if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in "_:/\\~%$")) continue
            add(HostFileReference(start, start + candidate.length, target))
            if (size >= 256) break
        }
    }
}
