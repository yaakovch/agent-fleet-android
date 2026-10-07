package com.termux.app.fleet

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedSessionStateTest {
    @Test fun viewChosenBeforeIdentityResolutionIsDurable() {
        val directory = kotlin.io.path.createTempDirectory("saved-view-").toFile()
        try {
            val binding = SavedSessionBinding(directory)
            binding.updateView("terminal")
            binding.install(identity, "ubuntu")
            binding.flush()
            SavedSessionStates.writer.submit {}.get(5, TimeUnit.SECONDS)
            val restored = SavedSessionBinding(directory)
            restored.install(identity, "ubuntu")
            assertEquals("terminal", restored.state.value!!.selectedView)
        } finally { directory.deleteRecursively() }
    }
    @Test fun sharedFormFingerprintMatchesWindows() {
        val behavior = JSONObject(requireNotNull(javaClass.classLoader?.getResource("saved-session-behavior-v1.json")).readText())
        val form = behavior.getJSONObject("form")
        val question = form.getJSONArray("questions").getJSONObject(0)
        val option = question.getJSONArray("options").getJSONObject(0)
        val questions = listOf(ConversationQuestion(question.getString("id"), question.getString("header"),
            question.getString("prompt"), question.getString("type"), question.getBoolean("required"), question.getBoolean("allowOther"),
            listOf(ConversationQuestionOption(option.getString("id"), option.getString("label"), option.getString("description")))))
        assertEquals(form.getString("sha256"), questionFormFingerprint(questions))
        assertFalse(behavior.getJSONObject("persistence").getBoolean("restoredMutationsAllowed"))
    }
    private val identity = VerifiedSessionIdentity("host", "session", "a".repeat(64), "/projects/fixture", "linux", "codex")
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResource("contracts/$name")).readText()

    @Test fun canonicalIdentityAndSavedStateFixturesMatchWindows() {
        val verified = parseSessionIdentity(fixture("session-identity-v1.json"), "synthetic-host", "synthetic-session")
        for (name in listOf("session-identity-unknown-field-v1.json", "session-identity-incarnation-v1.json")) {
            assertThrows(IllegalArgumentException::class.java) { parseSessionIdentity(fixture(name), verified.host, verified.session) }
        }
        val state = decodeSavedSession(fixture("saved-session-v1.json"), verified, "linux")
        assertEquals("Unsent 😀 draft", state.message)
        for (name in listOf("saved-session-unknown-field-v1.json", "saved-session-stale-revision-v1.json")) {
            assertThrows(IllegalArgumentException::class.java) { decodeSavedSession(fixture(name), verified, "linux") }
        }
    }

    @Test fun restartRestoresContentButNeverInputAuthorityOrUploads() {
        val directory = kotlin.io.path.createTempDirectory("saved-session-").toFile()
        try {
            val binding = SavedSessionBinding(directory)
            binding.install(identity, "ubuntu")
            binding.updateMessage("unsent 😀")
            binding.updatePosition(false, SavedReadingAnchor("message-1", 12))
            binding.flush()
            SavedSessionStates.writer.submit {}.get(5, TimeUnit.SECONDS)
            val restored = SavedSessionBinding(directory)
            restored.install(identity, "ubuntu")
            assertEquals("unsent 😀", restored.message.value)
            assertEquals(SavedReadingAnchor("message-1", 12), restored.state.value!!.anchor)
            val json = JSONObject(directory.listFiles()!!.single().readText())
            assertFalse(json.has("providerState")); assertFalse(json.has("attachments"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun clearCannotBeUndoneByAnEarlierDebouncedSave() {
        val directory = kotlin.io.path.createTempDirectory("saved-session-").toFile()
        try {
            val binding = SavedSessionBinding(directory)
            binding.install(identity, "ubuntu")
            binding.updateMessage("draft")
            binding.clearMessage()
            SavedSessionStates.writer.submit {}.get(5, TimeUnit.SECONDS)
            val restored = SavedSessionBinding(directory)
            restored.install(identity, "ubuntu")
            assertEquals("", restored.message.value)
            assertEquals(2, restored.state.value!!.revision)
        } finally { directory.deleteRecursively() }
    }

    @Test fun reusedSessionNamesAndExecutionTargetsDoNotRestoreOldContent() {
        val original = sessionStateKey(identity, "ubuntu")
        assertNotEquals(original, sessionStateKey(identity.copy(incarnationId = "b".repeat(64)), "ubuntu"))
        assertNotEquals(original, sessionStateKey(identity, "another-target"))
        assertNotEquals(original, sessionStateKey(identity.copy(projectRoot = "/other"), "ubuntu"))
        assertNotEquals(original, sessionStateKey(identity.copy(tool = "claude"), "ubuntu"))
    }

    @Test fun changedFormsRemainAvailableForManualRecovery() {
        val drafts = listOf(SavedAnswerDraft("question", "old-form", listOf(ConversationAnswer("answer", emptyList(), "old"))),
            SavedAnswerDraft("question", "new-form", listOf(ConversationAnswer("answer", emptyList(), "new"))))
        val original = SavedSessionState(identity, "ubuntu", questions = drafts)
        assertEquals(drafts, decodeSavedSession(encodeSavedSession(original), identity, "ubuntu").questions)
        val changed = listOf(ConversationQuestion("question", "", "New prompt", "text", true, false, emptyList()))
        assertNotEquals(questionFormFingerprint(changed), questionFormFingerprint(changed.map { it.copy(prompt = "Old prompt") }))
    }

    @Test fun uncertainIdentityHidesSavedContentAndRejectsLateResolution() {
        val directory = kotlin.io.path.createTempDirectory("saved-session-").toFile()
        try {
            val binding = SavedSessionBinding(directory)
            binding.install(identity, "ubuntu")
            binding.updateMessage("old incarnation")
            val stale = binding.beginResolution()
            assertNull(binding.state.value); assertNull(binding.restored.value)
            assertFalse(binding.ready.value); assertEquals("", binding.message.value)
            val current = binding.beginResolution()
            assertFalse(binding.isCurrentResolution(stale))
            assertTrue(binding.isCurrentResolution(current))
            binding.install(identity, "ubuntu", stale)
            assertNull(binding.state.value)
            binding.install(identity.copy(incarnationId = "b".repeat(64)), "ubuntu", current)
            assertEquals("", binding.message.value)
            SavedSessionStates.writer.submit {}.get(5, TimeUnit.SECONDS)
        } finally { directory.deleteRecursively() }
    }

    @Test fun questionClearBeforeIdentityResolutionIsDurable() {
        val directory = kotlin.io.path.createTempDirectory("saved-session-").toFile()
        try {
            val binding = SavedSessionBinding(directory)
            val item = ConversationItem(id = "question", kind = "question", timestamp = "", role = "", title = "", text = "", detail = "", state = "pending", tool = "", attachments = emptyList(), choices = emptyList(), questions = listOf(
                ConversationQuestion("q", "", "Choose", "text", true, false, emptyList())))
            binding.updateQuestion(item, listOf(ConversationAnswer("q", emptyList(), "draft")))
            binding.clearQuestion(item.id)
            binding.install(identity, "ubuntu")
            assertTrue(binding.state.value!!.questions.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun inputLimitsAndUnknownFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { encodeSavedSession(SavedSessionState(identity, "ubuntu", message = "x".repeat(32769))) }
        val raw = JSONObject(encodeSavedSession(SavedSessionState(identity, "ubuntu"))).put("attachments", "private").toString()
        assertThrows(IllegalArgumentException::class.java) { decodeSavedSession(raw, identity, "ubuntu") }
    }
}
