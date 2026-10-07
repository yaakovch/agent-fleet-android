package com.termux.app.fleet

/** Immutable parsed content. Restoring it never restores action authority. */
internal object NativeConversationContentCache {
    const val MAX_ENTRIES = 4
    const val MAX_ENTRY_BYTES = 256 * 1024
    private data class Entry(
        val snapshot: ConversationFrame.Snapshot,
        val initialIds: Set<String>,
        val initialBytes: Int,
        val eventBytes: Map<String, Int> = emptyMap()
    )
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun record(binding: String, frame: ConversationFrame, encodedBytes: Int) {
        if (binding.isEmpty()) return
        val next = when (frame) {
            is ConversationFrame.Snapshot -> {
                entries.remove(binding)
                if (frame.mode != "ai" || encodedBytes > MAX_ENTRY_BYTES) return
                val snapshot = frame.copy(items = frame.items.takeLast(20))
                Entry(snapshot, snapshot.items.map { it.id }.toSet(), encodedBytes)
            }
            is ConversationFrame.Event -> {
                val previous = entries[binding] ?: return
                if (previous.snapshot.session != frame.session) return
                val items = (previous.snapshot.items.filterNot { it.id == frame.item.id } + frame.item).takeLast(20)
                val ids = items.map { it.id }.toSet()
                val initialIds = previous.initialIds.intersect(ids) - frame.item.id
                val sizes = (previous.eventBytes + (frame.item.id to encodedBytes)).filterKeys { it in ids }
                Entry(previous.snapshot.copy(items = items), initialIds,
                    if (initialIds.isEmpty()) 0 else previous.initialBytes, sizes)
            }
            else -> return
        }
        entries.remove(binding)
        // Frame sizes are conservative upper bounds for the retained content.
        if (next.initialBytes.toLong() + next.eventBytes.values.sumOf { it.toLong() } > MAX_ENTRY_BYTES) return
        entries[binding] = next
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    fun record(binding: String, line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_ENTRY_BYTES) { remove(binding); return }
        record(binding, ConversationStreamParser.parseFrame(line), bytes)
    }

    @Synchronized
    fun get(binding: String): ConversationFrame.Snapshot? {
        val entry = entries.remove(binding) ?: return null
        entries[binding] = entry
        return entry.snapshot
    }

    @Synchronized fun remove(binding: String) { entries.remove(binding) }
    @Synchronized fun removeSession(identity: String) {
        entries.keys.filter { it.startsWith(identity + "\u0000") }.forEach(entries::remove)
    }
    @Synchronized fun clear() { entries.clear() }
}
