package com.termux.app.fleet

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConversationStreamParserTest {
    private fun item(id: String, text: String = "hello") = """
        {"id":"$id","kind":"message","timestamp":"2026-07-13T00:00:00Z","role":"assistant","title":"","text":"$text","detail":"","state":"complete","tool":"codex","attachments":[],"choices":[]}
    """.trimIndent()

    @Test
    fun parsesConversationSnapshotAndApproval() {
        val line = """
            {"protocolVersion":2,"type":"conversation.snapshot","session":"wtmux-main","adapter":"codex","mode":"ai","interactionMode":"plan","revision":"rev-1","items":[${item("m1")},{"id":"approval-1","kind":"approval","timestamp":"","role":"","title":"Run command?","text":"git status","detail":"","state":"pending","tool":"shell","attachments":[],"choices":[{"id":"approve","label":"Approve"},{"id":"deny","label":"Deny"}],"revision":"approval-rev"}],"nextCursor":"older","hasMore":true}
        """.trimIndent()

        val frame = ConversationStreamParser.parseFrame(line) as ConversationFrame.Snapshot
        assertEquals("codex", frame.adapter)
        assertEquals("plan", frame.interactionMode)
        assertEquals(2, frame.items.size)
        assertEquals("approval-rev", frame.items.last().revision)
        assertEquals("deny", frame.items.last().choices.last().id)
        assertTrue(frame.hasMore)
    }

    @Test
    fun parsesDirectoryAndRejectsUnknownFrames() {
        val directory = ConversationStreamParser.parseDirectory(
            """{"protocolVersion":2,"type":"directory.snapshot","session":"s","cwd":"/home/me","entries":[{"name":"projects","symlink":false}],"truncated":false}"""
        )
        assertEquals("projects", directory.entries.single().name)
        assertFalse(directory.truncated)

        val error = runCatching {
            ConversationStreamParser.parseFrame("""{"protocolVersion":2,"type":"unexpected"}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(runCatching {
            ConversationStreamParser.parseFrame("""{"protocolVersion":1,"type":"conversation.heartbeat"}""")
        }.isFailure)
    }

    @Test
    fun mergesUpdatesDeduplicatesAndPrependsHistory() {
        val first = ConversationItem("one", "message", "", "assistant", "", "old", "", "complete", "codex", emptyList(), emptyList())
        val second = first.copy(id = "two", text = "second")
        val updated = first.copy(text = "new")

        val merged = mergeConversationItems(listOf(first, second), listOf(updated))
        assertEquals(listOf("one", "two"), merged.map { it.id })
        assertEquals("new", merged.first().text)

        val older = first.copy(id = "zero", text = "older")
        assertEquals(listOf("zero", "one", "two"), mergeConversationItems(merged, listOf(older), prepend = true).map { it.id })
    }

    @Test
    fun mergesToolLifecycleAcrossOlderPagesAndGroupsOnlyAdjacentTools() {
        val start = ConversationItem(
            "call-1", "tool", "2026-07-13T00:00:00Z", "", "Running Read", "", "input", "running", "Read",
            emptyList(), emptyList(), action = "read", target = "README.md", input = "{\"path\":\"README.md\"}", startedAt = "2026-07-13T00:00:00Z"
        )
        val complete = start.copy(
            title = "Tool completed", detail = "result", state = "complete", tool = "", action = "other", target = "", input = "",
            result = "contents", startedAt = "", completedAt = "2026-07-13T00:00:01Z"
        )
        val merged = mergeConversationItems(listOf(complete), listOf(start), prepend = true).single()
        assertEquals("Read", merged.tool)
        assertEquals("README.md", merged.target)
        assertEquals("contents", merged.result)
        assertEquals("complete", merged.state)

        val second = start.copy(id = "call-2", action = "command", target = "git status")
        val message = start.copy(id = "message", kind = "message", role = "assistant")
        val rows = buildConversationRows(listOf(start, second, message, start.copy(id = "call-3")), hasMore = true)
        assertTrue(rows[0] is ConversationRow.ToolGroup)
        assertTrue((rows[0] as ConversationRow.ToolGroup).continuesIntoOlderHistory)
        assertTrue(rows[1] is ConversationRow.Item)
        assertTrue(rows[2] is ConversationRow.Item)
        assertEquals("2+ tool calls · Command 1, Read 1", toolGroupTitle(rows[0] as ConversationRow.ToolGroup))
    }

    @Test
    fun parsesStructuredQuestion() {
        val line = """
            {"protocolVersion":2,"type":"conversation.event","session":"s","adapter":"claude","item":{"id":"q1","kind":"question","timestamp":"","role":"","title":"Answer needed","text":"","detail":"","state":"pending","tool":"question","attachments":[],"choices":[],"revision":"r1","questions":[{"id":"files","header":"Files","prompt":"Which files?","type":"multi","required":true,"allowOther":true,"options":[{"id":"readme","label":"README","description":"Docs"}]}],"answers":[]}}
        """.trimIndent()
        val item = (ConversationStreamParser.parseFrame(line) as ConversationFrame.Event).item
        assertEquals("question", item.kind)
        assertEquals("multi", item.questions.single().type)
        assertEquals("Docs", item.questions.single().options.single().description)

        val locallyTimedOut = item.copy(state = "error", title = "Answer unconfirmed")
        val confirmed = item.copy(state = "complete", questions = emptyList())
        val resolved = mergeConversationItems(listOf(locallyTimedOut), listOf(confirmed)).single()
        assertEquals("complete", resolved.state)
        assertEquals("Which files?", resolved.questions.single().prompt)
    }

    @Test
    fun nativeViewSettingPersistsAndDefaultsOn() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("agent-fleet-native-session", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(NativeSessionSettings.isEnabled(context))
        NativeSessionSettings.setEnabled(context, false)
        assertFalse(NativeSessionSettings.isEnabled(context))
    }

    @Test
    fun alternateScreenTakeoverAppliesToShellButNotAiTools() {
        assertEquals(NativeViewMode.Native, terminalScreenViewMode("ai", true, NativeViewMode.Native))
        assertEquals(NativeViewMode.AutomaticTerminal, terminalScreenViewMode("shell", true, NativeViewMode.Native))
        assertEquals(NativeViewMode.Native, terminalScreenViewMode("shell", false, NativeViewMode.AutomaticTerminal))
        assertEquals(NativeViewMode.ManualTerminal, terminalScreenViewMode("shell", false, NativeViewMode.ManualTerminal))
    }

    @Test
    fun conversationPagingTriggersNearHistoryStartButNotWhileBusyOrFailed() {
        assertTrue(nearConversationBottom(2))
        assertFalse(nearConversationBottom(3))
        assertTrue(nearConversationHistoryStart(17, 20))
        assertFalse(nearConversationHistoryStart(16, 20))
        assertTrue(shouldRequestOlderMessages(true, true, false, null, false))
        assertFalse(shouldRequestOlderMessages(true, true, true, null, false))
        assertFalse(shouldRequestOlderMessages(true, true, false, "offline", false))
        assertFalse(shouldRequestOlderMessages(true, true, false, null, true))
    }
}
