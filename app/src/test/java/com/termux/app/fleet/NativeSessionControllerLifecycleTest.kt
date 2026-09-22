package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NativeSessionControllerLifecycleTest {
    @Test
    fun providerStateIsUnknownUntilAuthoritativeFrameArrives() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = NativeSessionController(FakeHost(context), ComposeView(context))
        val field = NativeSessionController::class.java.getDeclaredField("uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(controller) as androidx.compose.runtime.MutableState<NativeSessionUiState>
        assertFalse(state.value.providerStateKnown)
        assertFalse(state.value.providerState.mutationsAllowed)
        val apply = NativeSessionController::class.java.getDeclaredMethod("applyFrame", ConversationFrame::class.java).apply { isAccessible = true }
        apply.invoke(controller, ConversationFrame.Snapshot("", "codex", "ai", "default", "snapshot", emptyList(), null, false, null, false))
        assertTrue(state.value.providerStateKnown)
        assertFalse(state.value.providerState.mutationsAllowed)
        val restart = NativeSessionController::class.java.getDeclaredMethod("restartNow").apply { isAccessible = true }
        restart.invoke(controller)
        assertFalse(state.value.providerStateKnown)
        controller.close()
    }

    @Test
    fun answeredQuestionSurvivesReplacementSnapshotWithoutRetainingUnrelatedRows() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = NativeSessionController(FakeHost(context), ComposeView(context))
        val question = ConversationItem("question", "question", "2026-09-22T10:00:00Z", "", "Answer needed", "", "", "pending", "question", emptyList(), emptyList(), revision = "request-r1", source = "codex_async_question")
        val apply = NativeSessionController::class.java.getDeclaredMethod("applyFrame", ConversationFrame::class.java).apply { isAccessible = true }
        fun snapshot(items: List<ConversationItem>) = ConversationFrame.Snapshot("", "codex", "ai", "default", "snapshot", items, null, false, null, false)
        apply.invoke(controller, snapshot(listOf(question)))
        apply.invoke(controller, ConversationFrame.Event("", "codex", question.copy(state = "complete", title = "Answered")))
        apply.invoke(controller, snapshot(emptyList()))
        apply.invoke(controller, snapshot(listOf(question)))
        val field = NativeSessionController::class.java.getDeclaredField("uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(controller) as androidx.compose.runtime.MutableState<NativeSessionUiState>
        assertEquals("complete", state.value.items.single().state)
        apply.invoke(controller, snapshot(listOf(question.copy(revision = "different-request"))))
        assertEquals("pending", state.value.items.single().state)
        controller.close()
    }

    @Test
    fun earlierAsyncQuestionDoesNotCaptureCurrentAttention() {
        val question = ConversationItem("old", "question", "2026-09-06T10:00:00Z", "", "Answer needed", "", "", "pending", "question", emptyList(), emptyList(), source = "codex_async_question")
        val user = question.copy(id = "user", kind = "message", role = "user", timestamp = "2026-09-22T10:00:00Z", state = "complete", source = "")
        // The host appends old pending requests after its current message page.
        assertEquals(null, activePendingAction(listOf(user, question)))
        assertEquals("pending", question.state)
    }

    @Test
    fun composerVisibilityTracksForegroundNativeSurfaceWithoutSynthesizingAnEvent() {
        val context: Context = RuntimeEnvironment.getApplication()
        NativeSessionSettings.setEnabled(context, true)
        AgentFleetComposer.updateNativeState(
            target = "stale-target",
            mode = "unknown",
            pendingQuestionId = "",
            visible = true,
            items = emptyList(),
            revision = "stale-revision",
            liveEventSerial = 41L
        )
        val controller = NativeSessionController(
            activity = FakeHost(context),
            composeView = ComposeView(context)
        )
        controller.bind(
            Intent()
                .putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
                .putExtra(AgentFleetContract.EXTRA_LOCAL_SESSION, true)
                .putExtra(AgentFleetContract.EXTRA_SESSION_NAME, "Local lifecycle")
                .putExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE, AgentFleetContract.SURFACE_NATIVE)
        )

        val bound = AgentFleetComposer.nativeStateForTest()
        assertFalse(bound.visible)
        assertTrue(bound.target.isNotBlank())
        controller.onStart()
        val firstStart = AgentFleetComposer.nativeStateForTest()
        assertTrue(firstStart.visible)
        assertEquals(bound.target, firstStart.target)
        assertEquals(bound.revision, firstStart.revision)
        assertEquals(bound.liveEventSerial, firstStart.liveEventSerial)

        controller.onStop()
        val stopped = AgentFleetComposer.nativeStateForTest()
        assertFalse(stopped.visible)
        assertEquals(firstStart.revision, stopped.revision)
        assertEquals(firstStart.liveEventSerial, stopped.liveEventSerial)

        controller.onStart()
        val restarted = AgentFleetComposer.nativeStateForTest()
        assertTrue(restarted.visible)
        assertEquals(firstStart.revision, restarted.revision)
        assertEquals(firstStart.liveEventSerial, restarted.liveEventSerial)

        controller.onStop()
        controller.close()
    }

    private class FakeHost(
        override val nativeContext: Context
    ) : NativeSessionHost {
        override val nativeInlineComposer: Boolean = false

        override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean): Boolean = true
        override fun sendAgentFleetControlC(): Boolean = true
        override fun sendAgentFleetKey(key: String): Boolean = true
        override fun pickAgentFleetImages() = Unit
        override fun pickAgentFleetCamera() = Unit
        override fun setAgentFleetNativeView(
            nativeAvailable: Boolean,
            nativeView: Boolean,
            automaticTerminal: Boolean,
            aiComposer: Boolean
        ) = Unit
        override fun closeAgentFleetSessionTab() = Unit
    }
}
