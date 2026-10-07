package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test

class ConversationFramePublisherTest {
    @Test fun burstReductionRetainsEachItemsEffectAndToolCompletion() {
        fun item(id: String, state: String, input: String = "", result: String = "") = ConversationItem(
            id, "tool", "", "", "tool", "", "", state, "exec", emptyList(), emptyList(), input = input, result = result)
        val frames = (0 until 1000).flatMap { id -> listOf(
            ConversationFrame.Event("session", "codex", item(id.toString(), "running", input = "command")),
            ConversationFrame.Event("session", "codex", item(id.toString(), "complete", result = "result"))) }
        val batch = reduceConversationEvents(frames)
        assertEquals(2000, batch.frames.size)
        assertEquals(1000, batch.items.size)
        assertTrue(batch.items.all { it.state == "complete" && it.input == "command" && it.result == "result" })
    }
}
