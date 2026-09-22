package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test

class NativeQuestionStateTest {
    private fun question(id: String = "q", timestamp: String = "2026-09-22T10:00:00Z", revision: String? = "r1") =
        ConversationItem(id, "question", timestamp, "assistant", "Answer needed", "", "", "pending", "question", emptyList(), emptyList(),
            revision = revision, source = "codex_async_question")

    @Test fun laterUserMessageSeparatesEarlierQuestionsEvenWhenHostAppendsThemLast() {
        val old = question()
        val user = old.copy(id = "u", kind = "message", role = "user", state = "complete", timestamp = "2026-09-22T10:01:00Z")
        val current = question("new", "2026-09-22T10:02:00Z")
        val approval = old.copy(id = "approval", kind = "approval", source = "")
        val parts = partitionPendingActions(listOf(user, current, old, approval))
        assertEquals(listOf("new", "approval"), parts.current.map { it.id })
        assertEquals(listOf(old), parts.earlier)
        assertEquals("pending", parts.earlier.single().state)
    }

    @Test fun continuedAssistantActivityDoesNotArchiveAnUnansweredAsyncRequest() {
        val q = question()
        val assistant = q.copy(id = "assistant", kind = "message", role = "assistant", state = "complete", timestamp = "2026-09-23T10:00:00Z")
        assertEquals(listOf(q), partitionPendingActions(listOf(q, assistant)).current)
    }

    @Test fun tiesUseTranscriptOrderAndUnknownTimestampsRemainCurrent() {
        val q = question()
        val user = q.copy(id = "u", kind = "message", role = "user", state = "complete")
        assertEquals(listOf(q), partitionPendingActions(listOf(q, user)).earlier)
        assertEquals(listOf(q), partitionPendingActions(listOf(user, q)).current)
        val missing = question("missing", "")
        val invalid = question("invalid", "not-a-time")
        assertEquals(listOf(missing, invalid), partitionPendingActions(listOf(missing, invalid, user)).current)
        val offset = question("offset", "2026-09-22T12:59:59+03:00")
        assertEquals(listOf(offset), partitionPendingActions(listOf(user, offset)).earlier)
    }

    @Test fun receiptsAreBoundedAndNeverCrossRevisionsOrClearedSessions() {
        val memory = CompletedQuestionMemory(2)
        val all = (1..3).map { question("q$it") }
        memory.reconcile(all.map { it.copy(state = "complete") }, all)
        assertEquals(listOf("pending", "complete", "complete"), memory.reconcile(all, emptyList()).map { it.state })
        assertEquals("pending", memory.reconcile(listOf(all.last().copy(revision = "new")), emptyList()).single().state)
        memory.clear()
        assertEquals("pending", memory.reconcile(listOf(all.last()), emptyList()).single().state)
    }

    @Test fun completionWithoutRevisionNeedsMatchingObservedRequestIdentity() {
        val q = question().copy(questions = listOf(ConversationQuestion("one", "Setting", "Choose setting", "single", true, false, emptyList())))
        val memory = CompletedQuestionMemory()
        memory.reconcile(listOf(q.copy(state = "complete", revision = null)), listOf(q))
        assertEquals("complete", memory.reconcile(listOf(q), emptyList()).single().state)
        memory.clear()
        memory.reconcile(listOf(q.copy(state = "complete", revision = null, timestamp = "2026-09-23T00:00:00Z")), listOf(q))
        assertEquals("pending", memory.reconcile(listOf(q), emptyList()).single().state)
        memory.reconcile(listOf(q.copy(state = "complete", title = "No longer active")), listOf(q))
        assertEquals("pending", memory.reconcile(listOf(q), emptyList()).single().state)
    }

    @Test fun olderHostReplayWithoutRevisionNeedsExactUnambiguousQuestionDefinition() {
        val definition = ConversationQuestion("one", "Setting", "Choose setting", "single", true, false, emptyList())
        val q = question().copy(questions = listOf(definition))
        val memory = CompletedQuestionMemory()
        val answer = ConversationAnswer("one", emptyList(), "Confirmed answer")
        memory.reconcile(listOf(q.copy(state = "complete", answers = listOf(answer))), listOf(q))
        memory.reconcile(listOf(q.copy(state = "complete")), emptyList())
        assertEquals(listOf(answer), memory.reconcile(listOf(q), emptyList()).single().answers)
        assertEquals("complete", memory.reconcile(listOf(q.copy(revision = null)), emptyList()).single().state)
        assertEquals("pending", memory.reconcile(listOf(q.copy(revision = "different")), emptyList()).single().state)
        assertEquals("pending", memory.reconcile(listOf(q.copy(revision = null, questions = listOf(definition.copy(prompt = "Other setting")))), emptyList()).single().state)
        memory.reconcile(listOf(q.copy(state = "complete", revision = "r2")), emptyList())
        assertEquals("pending", memory.reconcile(listOf(q.copy(revision = null)), emptyList()).single().state)
    }
}
