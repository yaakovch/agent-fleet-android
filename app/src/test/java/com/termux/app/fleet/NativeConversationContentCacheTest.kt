package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeConversationContentCacheTest {
    @Test fun cacheLimitsMatchTheSharedClientFixture() {
        val fixture = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream("native-startup-behavior-v1.json")).bufferedReader().use { it.readText() })
        assertEquals(fixture.getJSONObject("cache").getInt("entries"), NativeConversationContentCache.MAX_ENTRIES)
        assertEquals(fixture.getJSONObject("cache").getInt("maximumEntryBytes"), NativeConversationContentCache.MAX_ENTRY_BYTES)
    }

    @Before fun reset() = NativeConversationContentCache.clear()

    @Test fun contentIsIsolatedBoundedAndBindingSpecific() {
        val binding = "host/session\u0000conversation"
        NativeConversationContentCache.record(binding, frame(25))
        assertEquals((5..24).map(Int::toString), NativeConversationContentCache.get(binding)!!.items.map { it.id })
        assertNull(NativeConversationContentCache.get("other/session\u0000conversation"))
        assertNull(NativeConversationContentCache.get("host/session\u0000detailed"))
        NativeConversationContentCache.removeSession("host/session")
        assertNull(NativeConversationContentCache.get(binding))
    }

    @Test fun leastRecentlyUsedContentIsEvictedAndOversizedUnicodeIsRejected() {
        repeat(4) { NativeConversationContentCache.record(it.toString(), frame(1)) }
        NativeConversationContentCache.get("0")
        NativeConversationContentCache.record("4", frame(1))
        assertNull(NativeConversationContentCache.get("1"))
        assertNotNull(NativeConversationContentCache.get("0"))
        NativeConversationContentCache.record("0", frame(1, "😀".repeat(100_000)))
        assertNull(NativeConversationContentCache.get("0"))
    }

    @Test fun anEventFromAnotherSessionCannotPoisonCachedContent() {
        NativeConversationContentCache.record("binding", frame(1))
        val event = JSONObject().put("protocolVersion", 2).put("type", "conversation.event").put("adapter", "codex").put("session", "other").put("item", item(99))
        NativeConversationContentCache.record("binding", event.toString())
        assertEquals(listOf("0"), NativeConversationContentCache.get("binding")!!.items.map { it.id })
    }

    private fun frame(count: Int, text: String = "Visible content") = JSONObject()
        .put("protocolVersion", 2).put("type", "conversation.snapshot").put("session", "session")
        .put("adapter", "codex").put("mode", "ai").put("interactionMode", "default").put("revision", "r1")
        .put("items", JSONArray((0 until count).map { item(it, text) }))
        .put("nextCursor", JSONObject.NULL).put("hasMore", false).toString()

    private fun item(id: Int, text: String = "Visible content") = JSONObject()
        .put("id", id.toString()).put("kind", "message").put("text", text).put("role", "assistant")
        .put("timestamp", "").put("title", "").put("detail", "").put("state", "complete").put("tool", "")
        .put("attachments", JSONArray()).put("choices", JSONArray())
}
