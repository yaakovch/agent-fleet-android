package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalSuggestionsTest {
    private fun item(
        id: String,
        kind: String = "message",
        role: String = "assistant",
        text: String = "",
        state: String = "complete"
    ) = ConversationItem(id, kind, "", role, "", text, "", state, "", emptyList(), emptyList())

    @Test fun boundsNewestVisibleDialogueByCountAndUtf8Bytes() {
        val messages = (0 until 20).map { LocalSuggestionMessage(if (it % 2 == 0) "user" else "assistant", "$it:${"é".repeat(2000)}") }
        val result = boundSuggestionContext(messages)
        assertTrue(result.size <= 12)
        assertTrue(result.last().text.startsWith("19:"))
        assertTrue(result.sumOf { it.role.toByteArray().size + it.text.toByteArray().size + 2 } <= LOCAL_SUGGESTION_MAX_CONTEXT_BYTES)
    }

    @Test fun excludesToolsPlansAndNonDialogueRows() {
        val result = conversationSuggestionContext(listOf(
            item("user", role = "user", text = "Please fix it"),
            item("tool", kind = "tool", text = "private tool output"),
            item("plan", kind = "plan", text = "private plan"),
            item("assistant", text = "Which option do you prefer?")
        ))
        assertEquals(listOf("Please fix it", "Which option do you prefer?"), result.map { it.text })
    }

    @Test fun gatesEmptyComposerAndPureTextQuestions() {
        val assistant = item("assistant", text = "Should I continue?")
        val question = ConversationQuestion("q", "", "What should change?", "text", true, false, emptyList())
        assertTrue(canSuggestForComposer(listOf(assistant), ""))
        assertFalse(canSuggestForComposer(listOf(assistant), "manual"))
        assertFalse(canSuggestForComposer(listOf(assistant, item("user", role = "user", text = "Please continue")), ""))
        assertTrue(canSuggestForQuestion(question, ""))
        assertFalse(canSuggestForQuestion(question.copy(type = "single"), ""))
    }

    @Test fun automaticTriggerRequiresANewActiveNonHistoricalRevision() {
        val pending = item("assistant", text = "Working", state = "streaming")
        val complete = item("assistant", text = "Would you like me to continue?", state = "complete")
        val target = LocalSuggestionTarget("composer")
        val previous = localSuggestionRevision(listOf(pending), target)
        val current = localSuggestionRevision(listOf(complete), target)
        assertTrue(shouldStartAutomaticSuggestion(previous, current, active = true, historicalFrame = false))
        assertFalse(shouldStartAutomaticSuggestion(current, current, active = true, historicalFrame = false))
        assertFalse(shouldStartAutomaticSuggestion(previous, current, active = false, historicalFrame = false))
        assertFalse(shouldStartAutomaticSuggestion(previous, current, active = true, historicalFrame = true))
    }

    @Test fun putsQuotedConversationBeforeTheDirectReplyTask() {
        val prompt = buildLocalSuggestionPrompt(listOf(
            item("user", role = "user", text = "What is a completed assistant reply?"),
            item("assistant", text = "It is a response that has succeeded.")
        ), LocalSuggestionTarget("composer"))
        assertTrue(prompt.indexOf("ASSISTANT: It is a response that has succeeded.") < prompt.indexOf("TASK:"))
        assertTrue(prompt.contains("ASSISTANT: It is a response that has succeeded.\n</conversation>\n\nTASK:"))
        assertTrue(prompt.contains("send verbatim"))
        assertTrue(prompt.contains("Do not explain, summarize, interpret, or restate"))
        assertTrue(prompt.contains("Wrong: \"It means the assistant has finished.\""))
        assertTrue(prompt.contains("Right: \"Got it, thanks.\""))
        assertTrue(prompt.contains("language of the most recent USER messages"))
        assertTrue(prompt.trimEnd().endsWith("Return JSON only: {\"suggestions\":[\"...\"]}"))
    }

    @Test fun makesStructuredQuestionExplicitAndKeepsTheWholePromptBounded() {
        val items = (0 until 20).map { index ->
            item("$index", role = if (index % 2 == 0) "user" else "assistant", text = "$index:${"é".repeat(2_000)}")
        }
        val prompt = buildLocalSuggestionPrompt(items, LocalSuggestionTarget("question", "i", "q", "Pick ${"名".repeat(4_096)}"))
        assertTrue(prompt.contains("direct answer to this structured question: Pick"))
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= LOCAL_SUGGESTION_MAX_PROMPT_BYTES)
    }

    @Test fun parsesBoundedResults() {
        assertEquals(listOf("Yes", "No", "Maybe"), parseLocalSuggestions("{\"suggestions\":[\"Yes\",\"yes\",\"No\",\"Maybe\",\"Extra\"]}"))
        assertEquals(listOf("First", "Second"), parseLocalSuggestions("1. First\n2. Second"))
    }

    @Test fun generationOwnershipSupersedesAndCompletesEachRequesterExactlyOnce() {
        val owner = LocalSuggestionGenerationOwner<(String) -> Unit>()
        val completions = mutableListOf<String>()
        val first = owner.begin("same-id") { completions += "first:$it" }
        val second = owner.begin("same-id") { completions += "second:$it" }

        second.superseded?.value?.invoke("superseded")
        assertNull(owner.finish(first.generation))
        owner.finish(second.generation)?.invoke("success")
        assertNull(owner.finish(second.generation))
        assertEquals(listOf("first:superseded", "second:success"), completions)

        val third = owner.begin("third") { completions += "third:$it" }
        assertNull(owner.cancel("different"))
        assertTrue(owner.current(third.generation) != null)
        owner.cancel("third")?.value?.invoke("canceled")
        assertNull(owner.current(third.generation))
        assertEquals("third:canceled", completions.last())
    }

    @Test fun localModelShutdownWaitsForTheLastForegroundSurface() {
        val owners = LocalSuggestionForegroundOwners()
        val fleet = Any()
        val terminal = Any()
        owners.start(fleet)
        owners.start(terminal)
        owners.start(fleet)
        assertEquals(2, owners.count())
        assertFalse(owners.stop(fleet))
        assertEquals(1, owners.count())
        assertTrue(owners.stop(terminal))
        assertFalse(owners.stop(terminal))
    }

    @Test fun foregroundRecreationCancelsDelayedShutdownButTrueBackgroundCompletesIt() {
        val scheduler = FakeHandoffScheduler()
        val lifecycle = LocalSuggestionForegroundLifecycle(scheduler, handoffDelayMillis = 1_500L)
        val firstActivity = Any()
        var shutdowns = 0
        lifecycle.start(firstActivity)
        lifecycle.stop(firstActivity) { shutdowns++ }
        assertEquals(0, shutdowns)
        assertEquals(listOf(1_500L), scheduler.delays)

        val recreatedActivity = Any()
        lifecycle.start(recreatedActivity)
        scheduler.runPending()
        assertEquals(0, shutdowns)

        lifecycle.stop(recreatedActivity) { shutdowns++ }
        scheduler.runPending()
        assertEquals(1, shutdowns)
        scheduler.runPending()
        assertEquals(1, shutdowns)
    }

    @Test fun twoSequentialRequestsShareOneWarmEngineOwner() {
        var creations = 0
        var closes = 0
        val owner = LocalSuggestionEngineOwner(
            create = { Any().also { creations++ } },
            closeValue = { closes++ }
        )

        val firstRequestEngine = owner.get()
        val secondRequestEngine = owner.get()

        assertSame(firstRequestEngine, secondRequestEngine)
        assertEquals(1, creations)
        owner.close()
        owner.close()
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { owner.get() }
    }

    @Test fun reentrantCompletionCannotRetakeANewerGeneration() {
        val owner = LocalSuggestionGenerationOwner<(String) -> Unit>()
        val completions = mutableListOf<String>()
        owner.begin("first") {
            completions += "first:$it"
            owner.begin("reentrant") { value -> completions += "reentrant:$value" }
        }
        val second = owner.begin("second") { completions += "second:$it" }

        second.superseded?.value?.invoke("superseded")
        assertNull(owner.finish(second.generation))
        val reentrant = requireNotNull(owner.current("reentrant"))
        owner.finish(reentrant.generation)?.invoke("success")

        assertEquals(listOf("first:superseded", "reentrant:success"), completions)
    }

    @Test fun pinsTheExactSupportedModelAndNonExportedProcess() {
        assertEquals(2_588_147_712L, LocalSuggestionModel.SIZE)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", LocalSuggestionModel.SHA256)
        assertEquals("9262660a1676eed6d0c477ab1a86344430854664", LocalSuggestionModel.REVISION)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".app.fleet.LocalSuggestionService\""))
        assertTrue(manifest.contains("android:process=\":local_llm\""))
        assertTrue(manifest.substringAfter(".app.fleet.LocalSuggestionService").substringBefore("/>").contains("android:exported=\"false\""))
    }

    private class FakeHandoffScheduler : LocalSuggestionHandoffScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<FakeTask>()

        override fun schedule(delayMillis: Long, action: () -> Unit): LocalSuggestionScheduledTask {
            delays += delayMillis
            return FakeTask(action).also(tasks::add)
        }

        fun runPending() {
            tasks.toList().forEach(FakeTask::run)
        }

        private class FakeTask(private val action: () -> Unit) : LocalSuggestionScheduledTask {
            private var active = true

            override fun cancel() {
                active = false
            }

            fun run() {
                if (!active) return
                active = false
                action()
            }
        }
    }
}
