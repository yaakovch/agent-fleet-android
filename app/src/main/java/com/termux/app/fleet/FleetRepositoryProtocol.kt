package com.termux.app.fleet

import org.json.JSONObject

object FleetRepositoryProtocol {
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    fun parsePage(value: JSONObject): FleetRepositoryPage {
        require(keys(value) == setOf("rootName", "relativePath", "parentPath", "entries", "nextCursor", "truncated")) {
            "Repository response fields are invalid."
        }
        val entries = value.getJSONArray("entries")
        require(entries.length() <= 250) { "Repository response exceeded its safety limit." }
        val relativePath = value.getString("relativePath").also { require(validPath(it, true)) }
        val parsedEntries = List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            require(keys(item) == setOf("name", "relativePath", "kind", "size", "modifiedAt", "hidden", "isLink"))
            val kind = item.getString("kind").also { require(it in setOf("directory", "file")) }
            val size = if (item.isNull("size")) null else item.getLong("size").also { require(it in 0..MAX_FILE_BYTES) }
            require((kind == "directory") == (size == null)) { "Repository entry size does not match its kind." }
            FleetRepositoryEntry(
                name = safeText(item.getString("name"), 255),
                relativePath = item.getString("relativePath").also { require(validPath(it, false)) },
                kind = kind,
                size = size,
                modifiedAt = safeText(item.getString("modifiedAt"), 40),
                hidden = item.getBoolean("hidden"),
                isLink = item.getBoolean("isLink")
            )
        }
        require(parsedEntries.map(FleetRepositoryEntry::relativePath).toSet().size == parsedEntries.size) {
            "Repository response contains a duplicate path."
        }
        return FleetRepositoryPage(
            rootName = safeText(value.getString("rootName"), 255),
            relativePath = relativePath,
            parentPath = if (value.isNull("parentPath")) null else value.getString("parentPath").also { require(validPath(it, true)) },
            entries = parsedEntries,
            nextCursor = if (value.isNull("nextCursor")) null else value.getString("nextCursor").also { require(validCursor(it)) },
            truncated = value.getBoolean("truncated")
        )
    }

    private fun validPath(value: String, empty: Boolean): Boolean {
        if (value.length > 2_048 || (!empty && value.isBlank()) || value.startsWith('/') || value.contains('\\') || value.any(Char::isISOControl)) return false
        if (value.isBlank()) return empty
        return value.split('/').all { it.isNotBlank() && it !in setOf(".", "..") }
    }

    private fun validCursor(value: String): Boolean = value.length <= 2_048 && value.none(Char::isISOControl)

    private fun safeText(value: String, maximum: Int): String = value.also {
        require(it.isNotBlank() && it.length <= maximum && it.none(Char::isISOControl))
    }

    private fun keys(value: JSONObject): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }
}
