package com.termux.app.fleet

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NativeSessionRegistryTest {
    private val application: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun releaseRetainsStateAndStopsUntilSameTargetIsReacquired() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        val factory = factory(created)
        val first = registry.acquire("target-a", FakeHost(application, "first"), factory)
        first.session.draft = "retained draft"
        assertEquals(0, first.session.starts)

        registry.onActivityStart()
        registry.onActivityStart()
        assertEquals(1, first.session.starts)
        assertEquals(setOf("target-a"), registry.state().runningTargets)

        first.release()
        first.release()
        assertEquals(1, created.single().stops)
        assertEquals(0, created.single().closes)
        assertTrue(registry.state().activeTargets.isEmpty())

        val second = registry.acquire("target-a", FakeHost(application, "second"), factory)
        assertSame(created.single(), second.session)
        assertEquals("retained draft", second.session.draft)
        assertEquals(2, second.session.starts)

        registry.onActivityStop()
        registry.onActivityStop()
        assertEquals(2, second.session.stops)
        assertTrue(registry.state().runningTargets.isEmpty())

        registry.onActivityStart()
        assertEquals(3, second.session.starts)
        val retained = second.session
        registry.destroy()
        registry.destroy()
        assertEquals(1, retained.closes)
        assertEquals(0, registry.state().entries)
        assertThrows(IllegalStateException::class.java) { second.session }
    }

    @Test
    fun newerAcquireRefreshesDelegateAndStaleReleaseCannotStopIt() {
        val created = mutableListOf<FakeSession>()
        val wrappedActivityContext = ContextWrapper(application)
        val registry = NativeSessionRegistry<FakeSession>(wrappedActivityContext)
        registry.onActivityStart()
        val firstHost = FakeHost(wrappedActivityContext, "first", nativeInlineComposer = false)
        val first = registry.acquire("target", firstHost, factory(created))
        val retained = first.session
        assertSame(application, retained.host.nativeContext)
        assertFalse(retained.host.nativeInlineComposer)
        assertTrue(retained.host.sendAgentFleetComposerText("one", true))
        assertEquals(listOf("one"), firstHost.sent)

        val secondHost = FakeHost(wrappedActivityContext, "second", nativeInlineComposer = true)
        val second = registry.acquire("target", secondHost, factory(created))
        assertSame(retained, second.session)
        assertEquals(1, retained.starts)
        assertTrue(retained.host.nativeInlineComposer)

        first.release()
        assertEquals(0, retained.stops)
        assertEquals(setOf("target"), registry.state().runningTargets)
        assertTrue(retained.host.sendAgentFleetComposerText("two", false))
        retained.host.pickAgentFleetCamera()
        assertEquals(listOf("two"), secondHost.sent)
        assertEquals(1, secondHost.cameraPicks)
        assertEquals(listOf("one"), firstHost.sent)

        second.release()
        assertEquals(1, retained.stops)
        assertFalse(retained.host.sendAgentFleetComposerText("cleared", false))
        retained.host.pickAgentFleetCamera()
        assertEquals(1, secondHost.cameraPicks)
        registry.destroy()
    }

    @Test
    fun fifthTargetEvictsDeterministicInactiveLruAndNeverAnActiveEntry() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        val factory = factory(created)
        val leases = (1..MAX_RETAINED_NATIVE_SESSIONS).associate { index ->
            val target = "target-$index"
            target to registry.acquire(target, FakeHost(application, target), factory)
        }
        leases.values.forEach(NativeSessionLease<FakeSession>::release)

        // Refresh target-1 so target-2 becomes the deterministic inactive LRU.
        registry.acquire("target-1", FakeHost(application, "target-1-refresh"), factory).release()
        val fifth = registry.acquire("target-5", FakeHost(application, "target-5"), factory)
        assertEquals(MAX_RETAINED_NATIVE_SESSIONS, registry.state().entries)
        assertEquals(1, created.single { it.target == "target-2" }.closes)
        assertEquals(0, created.single { it.target == "target-1" }.closes)
        assertEquals(0, created.single { it.target == "target-3" }.closes)
        assertEquals(0, created.single { it.target == "target-4" }.closes)

        // Fill every retained slot with an active lease; a new target must fail without creation.
        val active = listOf(
            registry.acquire("target-1", FakeHost(application, "active-1"), factory),
            registry.acquire("target-3", FakeHost(application, "active-3"), factory),
            registry.acquire("target-4", FakeHost(application, "active-4"), factory),
            fifth
        )
        val creationsBeforeOverflow = created.size
        assertThrows(IllegalStateException::class.java) {
            registry.acquire("target-6", FakeHost(application, "overflow"), factory)
        }
        assertEquals(creationsBeforeOverflow, created.size)
        assertTrue(created.filter { it.target != "target-2" }.all { it.closes == 0 })

        active.forEach(NativeSessionLease<FakeSession>::release)
        registry.destroy()
        assertTrue(created.all { it.closes == 1 })
    }

    @Test
    fun managedPaneCanReplaceTheFourthActiveTargetBeforeComposeForgetsItsOldLease() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        val factory = factory(created)
        registry.onActivityStart()
        val leases = (1..MAX_RETAINED_NATIVE_SESSIONS).associate { index ->
            val slot = "pane-$index"
            slot to registry.manage(slot, "target-$index", FakeHost(application, slot), factory).also {
                it.onRemembered()
            }
        }

        val replacement = registry.manage(
            "pane-1",
            "target-5",
            FakeHost(application, "pane-1-replacement"),
            factory
        )
        replacement.onRemembered()

        assertEquals(MAX_RETAINED_NATIVE_SESSIONS, registry.state().entries)
        assertEquals(setOf("target-2", "target-3", "target-4", "target-5"), registry.state().activeTargets)
        assertEquals(1, created.single { it.target == "target-1" }.stops)
        assertEquals(1, created.single { it.target == "target-1" }.closes)
        assertEquals(1, replacement.session.starts)

        // Compose forgets the old value after constructing the replacement. Its stale callback
        // must not release or stop the new pane assignment.
        leases.getValue("pane-1").onForgotten()
        assertEquals(setOf("target-2", "target-3", "target-4", "target-5"), registry.state().runningTargets)
        assertEquals(0, replacement.session.stops)
        leases.filterKeys { it != "pane-1" }.values.forEach { it.onForgotten() }
        replacement.onForgotten()
        registry.destroy()
    }

    @Test
    fun abandonedFullCapacityReplacementKeepsTheCommittedPaneAndSessionValid() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        val factory = factory(created)
        registry.onActivityStart()
        val leases = (1..MAX_RETAINED_NATIVE_SESSIONS).associate { index ->
            val slot = "pane-$index"
            slot to registry.manage(slot, "target-$index", FakeHost(application, slot), factory).also {
                it.onRemembered()
            }
        }
        val originalLease = leases.getValue("pane-1")
        val originalSession = originalLease.session

        val abandoned = registry.manage(
            "pane-1",
            "target-5",
            FakeHost(application, "abandoned-replacement"),
            factory
        )
        val abandonedSession = abandoned.session
        abandoned.onAbandoned()

        assertSame(originalSession, originalLease.session)
        assertEquals(setOf("target-1", "target-2", "target-3", "target-4"), registry.state().activeTargets)
        assertEquals(setOf("target-1", "target-2", "target-3", "target-4"), registry.state().runningTargets)
        assertEquals(0, originalSession.stops)
        assertEquals(0, originalSession.closes)
        assertEquals(0, abandonedSession.starts)
        assertEquals(1, abandonedSession.closes)

        leases.values.forEach { it.onForgotten() }
        registry.destroy()
    }

    @Test
    fun abandonedManagedLeaseReleasesItsSurfaceExactlyOnce() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        registry.onActivityStart()
        val lease = registry.manage(
            "pane",
            "target",
            FakeHost(application, "host"),
            factory(created)
        )

        lease.onAbandoned()
        lease.onForgotten()

        assertTrue(registry.state().activeTargets.isEmpty())
        assertTrue(registry.state().runningTargets.isEmpty())
        assertEquals(0, created.single().starts)
        assertEquals(0, created.single().stops)
        assertEquals(1, created.single().closes)
        registry.destroy()
        assertEquals(1, created.single().closes)
    }

    @Test
    fun managedFocusIsInitializedBeforeForegroundStartAndRestartDoesNotDuplicateTarget() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        registry.onActivityStart()
        val first = registry.manage(
            "pane",
            "target",
            FakeHost(application, "first"),
            factory(created),
            beforeStart = { it.suggestionFocused = false }
        )
        val retained = first.session
        first.onRemembered()
        assertEquals(listOf(false), retained.focusAtStart)

        registry.onActivityStop()
        registry.onActivityStart()
        assertEquals(2, retained.starts)
        assertEquals(listOf(false, false), retained.focusAtStart)

        val second = registry.manage(
            "pane",
            "target",
            FakeHost(application, "second"),
            factory(created),
            beforeStart = { it.suggestionFocused = true }
        )
        second.onRemembered()
        assertSame(retained, second.session)
        assertEquals(1, created.size)
        assertEquals(2, retained.starts)
        first.onForgotten()
        assertEquals(setOf("target"), registry.state().runningTargets)
        second.onForgotten()
        registry.destroy()
    }

    @Test
    fun foregroundStartsOnlyActiveEntriesOnceAndDestroyClearsEveryDelegate() {
        val created = mutableListOf<FakeSession>()
        val registry = NativeSessionRegistry<FakeSession>(application)
        val factory = factory(created)
        val active = registry.acquire("active", FakeHost(application, "active"), factory)
        registry.acquire("inactive", FakeHost(application, "inactive"), factory).release()

        registry.onActivityStart()
        assertEquals(1, active.session.starts)
        assertEquals(0, created.single { it.target == "inactive" }.starts)
        registry.onActivityStop()
        assertEquals(1, active.session.stops)
        registry.onActivityStart()
        assertEquals(2, active.session.starts)

        val retained = created.toList()
        registry.destroy()
        retained.forEach { session ->
            assertEquals(1, session.closes)
            assertFalse(session.host.sendAgentFleetKey("ENTER"))
        }
    }

    @Test
    fun retainedControllerUsesLifecycleCompositionDisposalStrategy() {
        assertSame(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            nativeSessionCompositionStrategy(retainAcrossDetach = true)
        )
        assertSame(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
            nativeSessionCompositionStrategy(retainAcrossDetach = false)
        )
    }

    private fun factory(created: MutableList<FakeSession>) = RetainedNativeSessionFactory<FakeSession> { target, host ->
        FakeSession(target, host).also(created::add)
    }

    private class FakeSession(
        val target: String,
        val host: NativeSessionHost
    ) : RetainedNativeSession {
        var draft = ""
        var starts = 0
        var stops = 0
        var closes = 0
        var suggestionFocused = true
        val focusAtStart = mutableListOf<Boolean>()

        override fun onStart() {
            starts++
            focusAtStart += suggestionFocused
        }

        override fun onStop() {
            stops++
        }

        override fun close() {
            closes++
        }
    }

    private class FakeHost(
        override val nativeContext: Context,
        val name: String,
        override val nativeInlineComposer: Boolean = true
    ) : NativeSessionHost {
        val sent = mutableListOf<String>()
        var cameraPicks = 0

        override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean): Boolean {
            sent += text
            return true
        }

        override fun sendAgentFleetControlC(): Boolean = true
        override fun sendAgentFleetKey(key: String): Boolean = true
        override fun pickAgentFleetImages() = Unit
        override fun pickAgentFleetCamera() { cameraPicks++ }
        override fun setAgentFleetNativeView(
            nativeAvailable: Boolean,
            nativeView: Boolean,
            automaticTerminal: Boolean,
            aiComposer: Boolean
        ) = Unit
        override fun closeAgentFleetSessionTab() = Unit
    }
}
