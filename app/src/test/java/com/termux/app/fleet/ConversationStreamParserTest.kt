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
            {"protocolVersion":1,"type":"conversation.snapshot","session":"wtmux-main","adapter":"codex","mode":"ai","revision":"rev-1","items":[${item("m1")},{"id":"approval-1","kind":"approval","timestamp":"","role":"","title":"Run command?","text":"git status","detail":"","state":"pending","tool":"shell","attachments":[],"choices":[{"id":"approve","label":"Approve"},{"id":"deny","label":"Deny"}],"revision":"approval-rev"}],"nextCursor":"older","hasMore":true}
        """.trimIndent()

        val frame = ConversationStreamParser.parseFrame(line) as ConversationFrame.Snapshot
        assertEquals("codex", frame.adapter)
        assertEquals(2, frame.items.size)
        assertEquals("approval-rev", frame.items.last().revision)
        assertEquals("deny", frame.items.last().choices.last().id)
        assertTrue(frame.hasMore)
    }

    @Test
    fun parsesDirectoryAndRejectsUnknownFrames() {
        val directory = ConversationStreamParser.parseDirectory(
            """{"protocolVersion":1,"type":"directory.snapshot","session":"s","cwd":"/home/me","entries":[{"name":"projects","symlink":false}],"truncated":false}"""
        )
        assertEquals("projects", directory.entries.single().name)
        assertFalse(directory.truncated)

        val error = runCatching {
            ConversationStreamParser.parseFrame("""{"protocolVersion":1,"type":"unexpected"}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
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
