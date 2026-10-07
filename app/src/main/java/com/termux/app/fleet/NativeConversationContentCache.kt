package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject

/** Bounded process-memory content. Restoring it never restores action authority. */
internal object NativeConversationContentCache {
    const val MAX_ENTRIES = 4
    const val MAX_ENTRY_BYTES = 256 * 1024
    private val entries = linkedMapOf<String, String>()

    @Synchronized
    fun record(binding: String, line: String) {
        if (binding.isEmpty()) return
        val incoming = JSONObject(line)
        val snapshot = when (incoming.optString("type")) {
            "conversation.snapshot" -> {
                entries.remove(binding)
                if (incoming.optString("mode") != "ai") return
                incoming
            }
            "conversation.event" -> {
                val previous = entries[binding] ?: return
                val value = JSONObject(previous)
                if (value.getString("session") != incoming.getString("session")) return
                val item = incoming.getJSONObject("item")
                val old = value.getJSONArray("items")
                val items = (0 until old.length()).map { old.getJSONObject(it) }
                    .filterNot { it.getString("id") == item.getString("id") } + item
                value.put("items", JSONArray(items))
                value
            }
            else -> return
        }
        val items = snapshot.getJSONArray("items")
        snapshot.put("items", JSONArray((maxOf(0, items.length() - 20) until items.length()).map { items.getJSONObject(it) }))
        val encoded = snapshot.toString()
        entries.remove(binding)
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) return
        entries[binding] = encoded
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(binding: String): ConversationFrame.Snapshot? {
        val encoded = entries.remove(binding) ?: return null
        entries[binding] = encoded
        return ConversationStreamParser.parseFrame(encoded) as ConversationFrame.Snapshot
    }

    @Synchronized fun remove(binding: String) { entries.remove(binding) }
    @Synchronized fun removeSession(identity: String) {
        entries.keys.filter { it.startsWith(identity + "\u0000") }.forEach(entries::remove)
    }
    @Synchronized fun clear() { entries.clear() }
}
