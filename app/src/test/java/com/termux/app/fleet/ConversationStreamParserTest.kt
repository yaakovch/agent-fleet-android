package com.termux.app.fleet

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun acceptsAndRoundTripsEverySharedFrameFamily() {
        val fixture = checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/conversation-frames-v2.json"))
            .bufferedReader().use { it.readText() }
        val frames = org.json.JSONObject(fixture).getJSONArray("frames")
        val types = mutableSetOf<String>()
        repeat(frames.length()) { index ->
            val frame = frames.getJSONObject(index)
            ConversationStreamParser.requireValidProtocolFrame(frame.toString())
            ConversationStreamParser.requireValidProtocolFrame(org.json.JSONObject(frame.toString()).toString())
            types += frame.getString("type")
            val unknown = org.json.JSONObject(frame.toString()).put("unexpected", true)
            assertThrows(IllegalArgumentException::class.java) {
                ConversationStreamParser.requireValidProtocolFrame(unknown.toString())
            }
        }
        assertEquals(
            setOf(
                "conversation.snapshot", "conversation.event", "conversation.status", "conversation.heartbeat",
                "conversation.error", "directory.snapshot", "question.response", "approval.response"
            ),
            types
        )
    }

    @Test
    fun managedShellSessionsKeepTheComposerInput() {
        assertTrue(AgentFleetContract.supportsComposerInput("shell"))
        assertTrue(AgentFleetContract.supportsComposerInput("codex"))
        assertTrue(AgentFleetContract.supportsComposerInput("claude"))
        assertTrue(AgentFleetContract.supportsComposerInput("copilot"))
        assertFalse(AgentFleetContract.supportsComposerInput(""))
    }

    @Test
    fun consumesTheSharedStructuredWorkFixture() {
        val fixture = checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/conversation-structured-work-v2.json"))
            .bufferedReader().use { it.readText() }
        val frame = ConversationStreamParser.parseFrame(fixture) as ConversationFrame.Snapshot
        assertEquals(listOf("task_list", "plan", "question"), frame.items.map { it.kind })
        assertEquals("Repairing the Native view", frame.items.first().tasks[1].activeTitle)
        assertEquals("codex_plan_gate", frame.items.last().source)
        assertEquals(listOf("implement", "implement-clear", "stay"), frame.items.last().questions.single().options.map { it.id })
    }

    @Test
    fun consumesProviderConfidenceReplayCorpusAndFailsClosed() {
        val fixture = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("contracts/provider-confidence-replay-v1.json")
        ).bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(fixture)
        val adapters = root.getJSONArray("adapters").let { values ->
            (0 until values.length()).associate { index ->
                val value = values.getJSONObject(index)
                value.getString("id") to Pair(
                    value.getString("parserVersion"),
                    value.getString("actionVersion")
                )
            }
        }
        val cases = root.getJSONArray("cases")
        assertEquals(24, cases.length())
        val conditions = mutableMapOf<String, MutableSet<String>>()
        repeat(cases.length()) { index ->
            val value = cases.getJSONObject(index)
            val adapter = value.getString("adapter")
            val confidence = value.getString("confidence")
            val allowed = value.getBoolean("mutationsAllowed")
            val fallback = value.getString("fallback")
            conditions.getOrPut(adapter) { mutableSetOf() } += value.getString("condition")
            assertTrue(adapter in adapters)
            assertEquals(confidence == "verified", allowed)
            assertEquals(allowed, fallback == "none")
            if (!allowed) assertTrue(fallback in setOf("read_only_native", "terminal_only"))
        }
        assertEquals(setOf("codex", "claude", "copilot", "shell"), conditions.keys)
        assertTrue(conditions.values.all {
            it == setOf("current", "truncated", "reordered", "partial", "stale", "mixed_version")
        })

        val (parserVersion, actionVersion) = checkNotNull(adapters["codex"])
        val providerState = """
            {"confidence":"verified","reasonCode":"PROVIDER_STATE_VERIFIED","observedRevision":"r1","eventPosition":42,"parser":{"id":"codex-parser","version":"$parserVersion"},"actions":{"id":"codex-actions","version":"$actionVersion"},"mutationsAllowed":true,"fallback":"none"}
        """.trimIndent()
        val frame = ConversationStreamParser.parseFrame(
            """{"protocolVersion":2,"type":"conversation.snapshot","session":"s","adapter":"codex","mode":"ai","interactionMode":"default","revision":"r1","items":[],"nextCursor":null,"hasMore":false,"providerState":$providerState}"""
        ) as ConversationFrame.Snapshot
        assertTrue(checkNotNull(frame.providerState).mutationsAllowed)

        val unsafe = org.json.JSONObject(providerState)
            .put("confidence", "stale")
            .put("mutationsAllowed", true)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationStreamParser.parseFrame(
                """{"protocolVersion":2,"type":"conversation.snapshot","session":"s","adapter":"codex","mode":"ai","interactionMode":"default","revision":"r1","items":[],"nextCursor":null,"hasMore":false,"providerState":$unsafe}"""
            )
        }
    }

    @Test
    fun rejectsSharedUnknownFieldsAndOverLimitFrames() {
        listOf("conversation-unknown-field-v2.json", "conversation-item-unknown-field-v2.json").forEach { name ->
            val fixture = checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/$name"))
                .bufferedReader().use { it.readText() }
            assertThrows(IllegalArgumentException::class.java) { ConversationStreamParser.parseFrame(fixture) }
        }
        val baseline = org.json.JSONObject(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/conversation-structured-work-v2.json"))
                .bufferedReader().use { it.readText() }
        )
        val options = org.json.JSONArray()
        repeat(17) { index ->
            options.put(org.json.JSONObject().put("id", "option-$index").put("label", "Option $index").put("description", ""))
        }
        baseline.getJSONArray("items").getJSONObject(2).getJSONArray("questions").getJSONObject(0).put("options", options)
        assertThrows(IllegalArgumentException::class.java) { ConversationStreamParser.parseFrame(baseline.toString()) }
        val oversized = """{"protocolVersion":2,"type":"conversation.error","timestamp":"2026-07-22T12:00:00Z","error":{"code":"large","message":"${"x".repeat(256 * 1024)}"}}"""
        assertThrows(IllegalArgumentException::class.java) { ConversationStreamParser.parseFrame(oversized) }
    }

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
    fun parsesAuthoritativeCodexActivityAndPreservesOlderHostFallback() {
        val snapshot = ConversationStreamParser.parseFrame(
            """{"protocolVersion":2,"type":"conversation.snapshot","session":"s","adapter":"codex","mode":"ai","interactionMode":"default","revision":"r","items":[],"nextCursor":null,"hasMore":false,"providerActivity":{"label":"Waiting for background terminal","elapsedSeconds":617,"observedAt":"2026-07-19T17:00:00Z"}}"""
        ) as ConversationFrame.Snapshot
        assertTrue(snapshot.hasProviderActivity)
        assertEquals("Waiting for background terminal", snapshot.providerActivity?.label)
        assertEquals(617L, snapshot.providerActivity?.elapsedSeconds)
        assertEquals("Terminal wait", compactProviderActivityLabel(checkNotNull(snapshot.providerActivity).label))
        assertEquals(617_000L, providerActivityStartedAt(snapshot.providerActivity)?.let { started ->
            checkNotNull(snapshot.providerActivity).receivedAtMillis - started
        })

        val cleared = ConversationStreamParser.parseFrame(
            """{"protocolVersion":2,"type":"conversation.heartbeat","session":"s","adapter":"codex","status":"ready","interactionMode":"default","providerActivity":null}"""
        ) as ConversationFrame.Status
        assertTrue(cleared.hasProviderActivity)
        assertEquals(null, cleared.providerActivity)

        val olderHost = ConversationStreamParser.parseFrame(
            """{"protocolVersion":2,"type":"conversation.heartbeat","session":"s","adapter":"codex","status":"ready","interactionMode":"default"}"""
        ) as ConversationFrame.Status
        assertFalse(olderHost.hasProviderActivity)
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
    fun retiresAnUnansweredQuestionWhenTheConversationHasContinued() {
        val question = ConversationItem(
            "question-1", "question", "2026-07-16T01:00:00Z", "", "Answer needed", "", "",
            "pending", "question", emptyList(), emptyList()
        )
        val later = ConversationItem(
            "message-2", "message", "2026-07-16T01:01:00Z", "user", "", "continue", "",
            "complete", "codex", emptyList(), emptyList()
        )

        val merged = mergeConversationItems(emptyList(), listOf(question, later))
        assertEquals("complete", merged.first().state)
        assertEquals("No longer active", merged.first().title)
        assertEquals(null, activePendingAction(merged))
    }

    @Test
    fun ignoresAStaleQuestionAppendedAfterNewerSnapshotItems() {
        val later = ConversationItem(
            "tool-2", "tool", "2026-07-16T01:01:00Z", "", "Tool completed", "", "",
            "complete", "exec", emptyList(), emptyList()
        )
        val stale = ConversationItem(
            "question-1", "question", "2026-07-16T01:00:00Z", "", "Answer needed", "", "",
            "pending", "question", emptyList(), emptyList()
        )

        val merged = mergeConversationItems(emptyList(), listOf(later, stale))
        assertEquals("complete", merged.last().state)
        assertEquals(null, activePendingAction(merged))
    }

    @Test
    fun keepsTheNewestQuestionActionable() {
        val earlier = ConversationItem(
            "message-1", "message", "2026-07-16T01:00:00Z", "assistant", "", "choose", "",
            "complete", "codex", emptyList(), emptyList()
        )
        val question = ConversationItem(
            "question-1", "question", "2026-07-16T01:01:00Z", "", "Answer needed", "", "",
            "pending", "question", emptyList(), emptyList()
        )

        val merged = mergeConversationItems(emptyList(), listOf(earlier, question))
        assertEquals("pending", merged.last().state)
        assertEquals("question-1", activePendingAction(merged)?.id)
    }

    @Test
    fun parsesAndMergesHumanToolPresentationWithoutDroppingRawData() {
        val line = """
            {"protocolVersion":2,"type":"conversation.event","session":"s","adapter":"codex","item":{"id":"t1","kind":"tool","timestamp":"","role":"","title":"Running exec_command","text":"","detail":"","state":"running","tool":"exec_command","attachments":[],"choices":[],"action":"command","target":"git status","input":"{\"cmd\":\"git status --short\"}","result":"","presentation":{"version":1,"title":"Run command","subtitle":"git status","previewLines":12,"inputBlocks":[{"title":"Cmd","kind":"code","content":"git status --short"}],"resultBlocks":[]}}}
        """.trimIndent()
        val start = (ConversationStreamParser.parseFrame(line) as ConversationFrame.Event).item
        assertEquals("git status --short", start.presentation?.inputBlocks?.single()?.content)
        assertTrue(start.input.contains("cmd"))
        val complete = start.copy(
            state = "complete", input = "", result = "clean", presentation = ToolPresentation(
                "Tool call", "", 12, emptyList(), listOf(ToolPresentationBlock("Output", "terminal", "clean"))
            )
        )
        val merged = mergeConversationItems(listOf(start), listOf(complete)).single()
        assertEquals("git status --short", merged.presentation?.inputBlocks?.single()?.content)
        assertEquals("clean", merged.presentation?.resultBlocks?.single()?.content)
        assertEquals("Run command", toolCallTitle(merged))
        assertFalse(shouldGroupToolActions(merged))

        val grouped = merged.copy(presentation = merged.presentation?.copy(inputBlocks = listOf(
            ToolPresentationBlock("Read file", "path", "/tmp/one"),
            ToolPresentationBlock("Run command", "code", "git status")
        )))
        assertTrue(shouldGroupToolActions(grouped))
    }

    @Test
    fun parsesAndMergesStructuredTasksAndPlans() {
        val snapshot = """
            {"protocolVersion":2,"type":"conversation.snapshot","session":"s","adapter":"codex","mode":"ai","interactionMode":"plan","revision":"r1","items":[{"id":"task-list:codex:turn-1","kind":"task_list","timestamp":"","role":"assistant","title":"Current work","text":"","detail":"","state":"running","tool":"update_plan","attachments":[],"choices":[],"source":"codex","turnId":"turn-1","taskListId":"turn-1","updateMode":"replace","tasks":[{"id":"task-1","title":"Inspect","activeTitle":"Inspecting","detail":"Read the current view","state":"in_progress"},{"id":"task-2","title":"Fix","activeTitle":"Fixing","detail":"","state":"pending"}]},{"id":"plan:codex:turn-1","kind":"plan","timestamp":"","role":"assistant","title":"Plan","text":"## Plan\n\n- Inspect\n- Fix","detail":"","state":"complete","tool":"plan","attachments":[],"choices":[],"source":"codex","turnId":"turn-1"}],"nextCursor":null,"hasMore":false}
        """.trimIndent()
        val frame = ConversationStreamParser.parseFrame(snapshot) as ConversationFrame.Snapshot
        assertEquals(listOf("task_list", "plan"), frame.items.map { it.kind })
        assertEquals("Inspecting", frame.items.first().tasks.first().activeTitle)
        assertTrue(frame.items.last().text.startsWith("## Plan"))

        val patch = frame.items.first().copy(
            updateMode = "merge",
            tasks = listOf(ConversationTask("task-1", "", "", "", "completed"))
        )
        val merged = mergeConversationItems(frame.items, listOf(patch))
        assertEquals("completed", merged.first().tasks.first().state)
        assertEquals("Inspect", merged.first().tasks.first().title)
        assertEquals("Fix", merged.first().tasks.last().title)
        assertFalse(buildConversationRows(merged + ConversationItem(
            "done", "status", "", "", "Done", "", "", "complete", "codex", emptyList(), emptyList()
        ), false).any { it is ConversationRow.Item && it.value.id == "done" })
    }

    @Test
    fun toolFeedPreviewIsStrictlyBounded() {
        val content = (1..20).joinToString("\n") { "line-$it-${"x".repeat(80)}" }
        val preview = toolPreviewText(ToolPresentationBlock("Output", "terminal", content))
        assertTrue(preview.length <= 361)
        assertTrue(preview.lines().size <= 6)
        assertTrue(preview.endsWith("…"))
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
    fun nativeStreamRunsForEveryVisibleManagedSurfaceSoHeaderActivityStaysLive() {
        assertTrue(shouldRunConversationStream(true, true, false, NativeViewMode.Native))
        assertFalse(shouldRunConversationStream(false, true, false, NativeViewMode.Native))
        assertTrue(shouldRunConversationStream(true, true, false, NativeViewMode.ManualTerminal))
        assertTrue(shouldRunConversationStream(true, true, false, NativeViewMode.AutomaticTerminal))
        assertFalse(shouldRunConversationStream(true, true, true, NativeViewMode.Native))
    }

    @Test
    fun unchangedHeartbeatsDoNotCreateUiUpdates() {
        assertEquals(null, conversationStatusUpdate("Live", "plan", "ready", "unknown"))
        assertEquals(null, conversationStatusUpdate("Live", "plan", "ready", "plan"))
        assertEquals("Live" to "default", conversationStatusUpdate("Live", "plan", "ready", "default"))
        assertEquals("reconnecting" to "plan", conversationStatusUpdate("Live", "plan", "reconnecting", "unknown"))
    }

    @Test
    fun nativeWorkingDurationTracksProviderLifecycleAndClaudeFallback() {
        val user = ConversationItem(
            "user", "message", "2026-07-19T00:00:00Z", "user", "", "Fix it", "", "complete", "",
            emptyList(), emptyList()
        )
        val working = ConversationItem(
            "working", "status", "2026-07-19T00:00:01Z", "", "Working", "", "", "running", "",
            emptyList(), emptyList()
        )
        val done = ConversationItem(
            "done", "status", "2026-07-19T00:30:56Z", "", "Done", "", "", "complete", "",
            emptyList(), emptyList()
        )
        for (adapter in listOf("codex", "copilot")) {
            assertEquals(parseConversationTimestamp(user.timestamp), activeWorkStartedAt(adapter, listOf(user, working)))
            assertEquals(null, activeWorkStartedAt(adapter, listOf(user, working, done)))
            assertEquals(30L * 60L * 1_000L + 56_000L, latestCompletedWorkDuration(adapter, listOf(user, working, done)))
            assertEquals(
                parseConversationTimestamp(working.timestamp),
                activeWorkStartedAt(adapter, listOf(done, done.copy(id = "newer-tool", kind = "tool"), working))
            )
        }
        val providerTimedDone = done.copy(
            startedAt = "2026-07-19T00:23:15.862Z",
            completedAt = "2026-07-19T00:30:56.000Z"
        )
        assertEquals(460_138L, latestCompletedWorkDuration("codex", listOf(user, working, providerTimedDone)))
        assertEquals(parseConversationTimestamp(user.timestamp), activeWorkStartedAt("claude", listOf(user)))
        assertEquals(null, activeWorkStartedAt("claude", listOf(user, user.copy(id = "reply", role = "assistant"))))
        assertEquals("30m 55s", formatWorkingDuration(1_000L, 1_856_000L))
        assertEquals("7m 40s", formatElapsedDuration(460_000L))
        assertEquals(10_000L, optimisticWorkAfterComposerSend(null, "Fix it", true, true, 10_000L))
        assertEquals(null, optimisticWorkAfterComposerSend(null, "", true, true, 10_000L))
        assertEquals(9_000L, optimisticWorkAfterComposerSend(9_000L, "Draft", false, true, 10_000L))
        assertEquals(null, optimisticWorkAfterEvent(10_000L, working))
        assertEquals(null, optimisticWorkAfterEvent(10_000L, done))
        assertEquals(10_000L, optimisticWorkAfterEvent(10_000L, user))
        assertEquals(null, reconcileOptimisticWork(10_000L, "codex", listOf(working)))
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

    @Test
    fun singleChoiceTapsAdvanceOrSubmitWithoutASecondConfirmation() {
        val option = ConversationQuestion("mode", "", "Mode?", "single", true, true, emptyList())
        assertTrue(shouldAdvanceQuestion(option, ConversationAnswer("mode", listOf("fast"), ""), true))
        assertFalse(shouldAdvanceQuestion(option, ConversationAnswer("mode", listOf("fast"), ""), false))
        assertEquals("advance", questionTapAction(option, ConversationAnswer("mode", listOf("fast"), ""), true))
        assertEquals("submit", questionTapAction(option, ConversationAnswer("mode", listOf("fast"), ""), false))
        val text = option.copy(type = "text")
        assertFalse(shouldAdvanceQuestion(text, ConversationAnswer("mode", emptyList(), ""), true))
        assertTrue(shouldAdvanceQuestion(text, ConversationAnswer("mode", emptyList(), "yes"), true))
        assertEquals("wait", questionTapAction(text, ConversationAnswer("mode", emptyList(), "yes"), true))
        assertEquals("wait", questionTapAction(option.copy(type = "multi"), ConversationAnswer("mode", listOf("fast"), ""), false))
    }
}
