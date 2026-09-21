package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQuestionLifecycleTest {
    private fun question() = ConversationItem(
        id = "async-1", kind = "question", timestamp = "2026-09-21T10:00:00Z", role = "assistant",
        title = "Answer needed", text = "", detail = "", state = "pending", tool = "question",
        attachments = emptyList(), choices = emptyList(), source = "codex_async_question", revision = "request-1"
    )

    @Test fun asyncQuestionSurvivesNewActivityAndStaleSnapshots() {
        val request = question()
        val work = request.copy(id = "later", kind = "tool", source = "", timestamp = "2026-09-21T10:01:00Z")
        val waiting = mergeConversationItems(listOf(request), listOf(work))
        assertEquals(request.id, activePendingAction(waiting)?.id)
        val sending = mergeConversationItems(waiting, listOf(request.copy(state = "running", title = "Sending…")))
        assertEquals("running", mergeConversationItems(sending, listOf(request)).first().state)
        val done = mergeConversationItems(sending, listOf(request.copy(state = "complete")))
        assertEquals("complete", mergeConversationItems(done, listOf(request)).first().state)
        assertNull(activePendingAction(done))
    }

    @Test fun retryCanLeaveErrorStateAndPreservesAsyncIdentity() {
        val failed = question().copy(state = "error", title = "Answer not sent")
        val retry = mergeConversationItems(listOf(failed), listOf(failed.copy(state = "running", title = "Sending…")))
        assertEquals("running", retry.single().state)
        assertEquals("Sending…", retry.single().title)
        assertEquals("codex_async_question", retry.single().source)
    }
}
