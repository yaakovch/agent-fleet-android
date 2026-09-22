package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationTurnsTest {
    private fun fixture(): ConversationFrame.Snapshot = ConversationStreamParser.parseFrame(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/conversation-turns-v2.json"))
            .bufferedReader().use { it.readText() }
    ) as ConversationFrame.Snapshot

    @Test fun sharedTurnFixtureAndActivityAreAcceptedStrictly() {
        val snapshot = fixture()
        assertEquals(listOf("message", "activity", "message"), snapshot.items.map { it.kind })
        val summary = snapshot.items[1].activitySummary!!
        assertEquals(1L, summary.toolCount)
        assertEquals("complete", summary.state)
        val frame = JSONObject().put("protocolVersion", 2).put("type", "conversation.activity")
            .put("timestamp", "now").put("session", "fixture").put("adapter", "codex").put("turnId", summary.turnId)
            .put("items", org.json.JSONArray()).put("nextCursor", JSONObject.NULL).put("hasMore", false)
        assertTrue(ConversationStreamParser.parseFrame(frame.toString()) is ConversationFrame.Activity)
        frame.put("unexpected", true)
        assertThrows(IllegalArgumentException::class.java) { ConversationStreamParser.parseFrame(frame.toString()) }
    }

    @Test fun unknownProseSurvivesAndOnlyIdentifiedProgressCollapses() {
        val snapshot = fixture()
        val original = snapshot.items.last()
        val one = original.copy(id = "p1", messagePurpose = "progress")
        val two = original.copy(id = "p2", messagePurpose = "progress")
        val unknown = original.copy(id = "unknown", messagePurpose = "unknown")
        val active = snapshot.items[1].copy(activitySummary = snapshot.items[1].activitySummary!!.copy(state = "running"))
        val running = listOf(one, unknown, two, active)
        assertEquals(listOf("unknown", "p2", active.id), buildConversationRows(running, false, ConversationView.Conversation).map { it.id })
        val complete = listOf(one, unknown, two, snapshot.items[1])
        assertEquals(listOf("unknown", active.id), buildConversationRows(complete, false, ConversationView.Conversation).map { it.id })
        assertEquals(4, buildConversationRows(complete, false, ConversationView.Detailed).size)
    }

    @Test fun completedConversationSummaryStopsOptimisticWorking() {
        val snapshot = fixture()
        val user = snapshot.items.first()
        val complete = snapshot.items[1]
        val running = complete.copy(state = "running", activitySummary = complete.activitySummary!!.copy(state = "running"))
        val start = parseConversationTimestamp(user.timestamp)!!
        assertEquals(start, activeWorkStartedAt("codex", listOf(user, running)))
        assertEquals(start, activeWorkStartedAt("codex", listOf(user, running, snapshot.items.last().copy(messagePurpose = "progress"))))
        assertNull(activeWorkStartedAt("codex", listOf(user, complete)))
        assertNull(optimisticWorkAfterEvent(start, complete))
        assertNull(reconcileOptimisticWork(start, "codex", listOf(user, complete)))
        assertNull(optimisticWorkAfterEvent(start, snapshot.items.last()))
    }

    @Test fun activityCacheBoundsTurnCountItemsAndTextBytes() {
        var pages = emptyMap<String, ActivityPage>()
        repeat(20) { index -> pages = boundedActivityPages(pages, "turn-$index", ActivityPage(List(300) { fixture().items.last().copy(id = "$it") })) }
        assertEquals(8, pages.size)
        assertTrue(pages.values.all { it.items.size <= 200 })
        val large = fixture().items.last().copy(text = "x".repeat(200_000))
        pages = boundedActivityPages(pages, "large", ActivityPage(List(20) { large }))
        assertTrue(pages.isEmpty())
    }
}
