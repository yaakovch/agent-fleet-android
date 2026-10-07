package com.termux.app.fleet

data class HostFileRow(val text: String, val wrapped: Boolean = false)
data class HostFileRowReference(val row: Int, val start: Int, val end: Int, val target: String)

/** UTF-16, bounded, syntax-only detection. See the canonical host-file fixture. */
object HostFileRowReferences {
    private val pathStart = "(?:file://|[A-Za-z]:[/\\\\]|\\.{1,2}[/\\\\]|/|[\\p{L}\\p{N}_.-]+[/\\\\])"
    private val opening = Regex("[('\"`](?=$pathStart)")
    private val suffix = Regex("\\.[A-Za-z\\d]{1,16}$")
    private val independent = Regex("^$pathStart")
    private val multiple = Regex("\\s+(?:file://|[A-Za-z]:[/\\\\]|\\.{1,2}[/\\\\]|/)")
    private val multipleRelative = Regex("\\.[A-Za-z\\d]{1,16}\\s+[\\p{L}\\p{N}_.-]+[/\\\\][^\\s]+\\.[A-Za-z\\d]{1,16}")

    @JvmStatic fun extract(rows: List<HostFileRow>): List<HostFileRowReference> {
        val text = StringBuilder()
        val locations = mutableListOf<Pair<Int, Int>?>()
        rows.forEachIndexed { row, value ->
            if (row > 0 && !value.wrapped) { text.append('\n'); locations.add(null) }
            value.text.forEachIndexed { column, char -> text.append(char); locations.add(row to column) }
            if (text.length > 65536) return emptyList()
        }
        val source = text.toString()
        val blocked = mutableSetOf<Int>()
        val result = mutableListOf<HostFileRowReference>()
        fun emit(indices: List<Int>, target: String) {
            var segment = -1
            for (index in indices) {
                val location = locations[index] ?: continue
                val old = result.getOrNull(segment)
                if (old != null && old.row == location.first && old.end == location.second) result[segment] = old.copy(end = old.end + 1)
                else { result.add(HostFileRowReference(location.first, location.second, location.second + 1, target)); segment = result.lastIndex }
            }
        }
        for (match in opening.findAll(source)) {
            val start = match.range.first
            if (start in blocked || (start > 0 && (source[start - 1].isLetterOrDigit() || source[start - 1] in "_:/\\~%$"))) continue
            val close = if (match.value == "(") ')' else match.value[0]
            val end = source.indexOf(close, start + 1)
            val stop = if (end < 0) source.length else end + 1
            blocked.addAll(start until stop)
            if (end < 0 || end - start > 8192) continue
            val first = locations[start] ?: continue
            val final = locations[end] ?: continue
            if (final.first - first.first >= 32) continue
            val firstText = rows[first.first].text
            val base = firstText.takeWhile { it == ' ' }.length
            val listIndent = Regex("^ *[-*•] ").find(firstText)?.value?.length
            val indices = mutableListOf<Int>()
            val candidate = StringBuilder()
            var valid = true
            var i = start + 1
            while (i < end) {
                if (source[i] != '\n') { candidate.append(source[i]); indices.add(i); i++; continue }
                if (candidate.lastOrNull()?.isWhitespace() == true) { valid = false; break }
                var next = i + 1
                while (next < end && source[next] == ' ') next++
                val indentation = next - i - 1
                if (indentation > 0 && !(indentation >= 2 && indentation in listOf(base, listIndent, first.second + 1))) { valid = false; break }
                if (next == end || source[next] == '\n' || source[next] == '\t' || (suffix.containsMatchIn(candidate) && independent.containsMatchIn(source.substring(next)))) { valid = false; break }
                i = next
            }
            val value = candidate.toString()
            if (!valid || value != value.trim() || value.any { it in "()'\"`" } || multiple.containsMatchIn(value) || multipleRelative.containsMatchIn(value)) continue
            HostFileReferences.target(value)?.let { emit(indices, it) }
        }
        var first = 0
        while (first < rows.size) {
            var last = first
            while (last + 1 < rows.size && rows[last + 1].wrapped) last++
            if (last - first < 32 && !rows[first].wrapped) {
                val indices = locations.indices.filter { locations[it]?.first?.let { row -> row in first..last } == true }
                val value = indices.map { source[it] }.joinToString("")
                for (ref in if (value.length <= 8192) HostFileReferences.extract(value) else emptyList()) {
                    val selected = indices.subList(ref.start, ref.end)
                    if (selected.none { it in blocked }) emit(selected, ref.target)
                }
            }
            first = last + 1
        }
        return result.sortedWith(compareBy({ it.row }, { it.start }))
    }
}
