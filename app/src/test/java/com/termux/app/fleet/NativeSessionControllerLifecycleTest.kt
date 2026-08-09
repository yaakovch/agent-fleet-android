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
