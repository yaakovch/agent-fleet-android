package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFleetSessionResumeControllerTest {
    @Test
    fun notificationReentryRechecksAnAttachmentThatDiedWithoutItsCallback() {
        val session = session()
        val host = FakeHost(running = true)
        val controller = controller(session, host, FakeScheduler(), mutableListOf(), mutableListOf())
        controller.onForeground(session.id)
        host.running = false
        controller.onForeground(session.id)
        assertEquals(1, host.starts)
    }

    @Test
    fun endedAttachmentStartsOnceAndSelectsItsReplacement() {
        val session = session()
        val host = FakeHost()
        val scheduler = FakeScheduler()
        val selected = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val controller = controller(session, host, scheduler, selected, errors)

        controller.onForeground(session.id)
        controller.onForeground(session.id)

        assertEquals(1, host.starts)
        assertTrue(selected.isEmpty())
        host.running = true
        scheduler.runNext()
        assertEquals(listOf(session.id), selected)
        assertEquals(1, host.selections)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun runningAttachmentIsReselectedWithoutStartingAnotherProcess() {
        val session = session()
        val host = FakeHost(running = true)
        val selected = mutableListOf<String>()
        val controller = controller(session, host, FakeScheduler(), selected, mutableListOf())

        controller.onForeground(session.id)

        assertEquals(0, host.starts)
        assertEquals(1, host.selections)
        assertEquals(listOf(session.id), selected)
    }

    @Test
    fun backgroundCancelsPollingAndNextForegroundUsesTheCreatedAttachment() {
        val session = session()
        val host = FakeHost()
        val scheduler = FakeScheduler()
        val selected = mutableListOf<String>()
        val controller = controller(session, host, scheduler, selected, mutableListOf())

        controller.onForeground(session.id)
        controller.onBackground()
        host.running = true
        scheduler.runAll()
        assertTrue(selected.isEmpty())

        controller.onForeground(session.id)
        assertEquals(1, host.starts)
        assertEquals(listOf(session.id), selected)
    }

    @Test
    fun missingRememberedSessionFailsWithoutStarting() {
        val host = FakeHost()
        val errors = mutableListOf<String>()
        val controller = AgentFleetSessionResumeController(
            { null }, host, FakeScheduler(), listener(mutableListOf(), errors)
        )

        controller.onForeground("gaming:missing")

        assertEquals(0, host.starts)
        assertEquals(1, errors.size)
    }

    @Test
    fun checkedRuntimeFailureIsReportedWithoutCrashingTheActivity() {
        val session = session()
        val errors = mutableListOf<String>()
        val host = FakeHost(startFailure = FleetUnavailableException("Runtime repair is required"))
        val controller = controller(session, host, FakeScheduler(), mutableListOf(), errors)

        controller.onForeground(session.id)

        assertEquals(1, host.starts)
        assertEquals(listOf("Runtime repair is required"), errors)
    }

    @Test
    fun visibleUnexpectedExitStartsAndSelectsOneReplacement() {
        val session = session()
        val host = FakeHost(running = true)
        val scheduler = FakeScheduler()
        val selected = mutableListOf<String>()
        val controller = controller(session, host, scheduler, selected, mutableListOf())

        controller.onForeground(session.id)
        host.running = false
        controller.onAttachmentEnded(session.id)

        assertEquals(0, host.starts)
        scheduler.runNext()
        assertEquals(1, host.starts)
        host.running = true
        scheduler.runNext()
        assertEquals(listOf(session.id, session.id), selected)
        assertEquals(1, host.starts)
    }

    @Test
    fun explicitBackgroundPreventsAnEndedAttachmentFromRestarting() {
        val session = session()
        val host = FakeHost(running = true)
        val scheduler = FakeScheduler()
        val controller = controller(session, host, scheduler, mutableListOf(), mutableListOf())

        controller.onForeground(session.id)
        controller.onBackground()
        host.running = false
        controller.onAttachmentEnded(session.id)
        scheduler.runAll()

        assertEquals(0, host.starts)
    }


    @Test
    fun offlineWaitsForAvailabilityAndNeverLaunchesDuplicateAttempts() {
        val session = session()
        val host = FakeHost(offline = true)
        val scheduler = FakeScheduler()
        val controller = controller(session, host, scheduler, mutableListOf(), mutableListOf())
        controller.onForeground(session.id)
        assertEquals(0, host.starts)
        assertTrue(scheduler.delays.isEmpty())
        controller.onForeground(session.id)
        controller.onAvailabilityChanged()
        assertEquals(0, host.starts)
        host.offline = false
        controller.onAvailabilityChanged()
        controller.onAvailabilityChanged()
        controller.retry()
        controller.onForeground(session.id)
        assertEquals(1, host.starts)
        host.running = true
        scheduler.runNext()
        assertEquals(1, host.selections)
    }

    @Test
    fun permanentTrustAndMissingSessionErrorsDoNotStartOrRetry() {
        for (code in listOf("HOST_KEY_CHANGED", "SSH_AUTH_REQUIRED", "SESSION_UNAVAILABLE", "EXACT_ATTACH_UNSUPPORTED")) {
            val session = session()
            val host = FakeHost(code = code)
            val scheduler = FakeScheduler()
            val errors = mutableListOf<String>()
            val controller = controller(session, host, scheduler, mutableListOf(), errors)
            controller.onForeground(session.id)
            controller.onAvailabilityChanged()
            controller.retry()
            assertEquals(code, 0, host.starts)
            assertTrue(code, scheduler.delays.isEmpty())
            assertTrue(code, errors.isNotEmpty())
        }
    }

    @Test
    fun recoveryBackoffContinuesAtThirtySecondsAndBackgroundCancelsIt() {
        val session = session()
        val host = FakeHost(running = true)
        val scheduler = FakeScheduler()
        val controller = controller(session, host, scheduler, mutableListOf(), mutableListOf())
        controller.onForeground(session.id)
        val observed = mutableListOf<Long>()
        repeat(8) {
            host.running = false
            controller.onAttachmentEnded(session.id)
            observed += scheduler.delays.last()
            scheduler.runNext()
            host.running = true
            scheduler.runNext()
        }
        assertEquals(listOf(1000L, 2000L, 5000L, 10000L, 30000L, 30000L, 30000L, 30000L), observed)
        assertEquals(8, host.starts)
        host.running = false
        controller.onAttachmentEnded(session.id)
        val cancelled = scheduler.peek()
        controller.onBackground()
        cancelled?.run()
        scheduler.runAll()
        assertEquals(8, host.starts)
    }

    private fun controller(
        session: FleetSession,
        host: FakeHost,
        scheduler: FakeScheduler,
        selected: MutableList<String>,
        errors: MutableList<String>
    ) = AgentFleetSessionResumeController(
        { id -> session.takeIf { it.id == id } },
        host,
        scheduler,
        listener(selected, errors)
    )

    private fun listener(selected: MutableList<String>, errors: MutableList<String>) =
        object : AgentFleetSessionResumeController.Listener {
            override fun onSelected(sessionId: String) { selected += sessionId }
            override fun onError(message: String) { errors += message }
        }

    private class FakeHost(
        var running: Boolean = false,
        private val startFailure: Exception? = null,
        var offline: Boolean = false,
        var code: String? = null
    ) : AgentFleetSessionResumeController.AttachmentHost {
        var starts = 0
        var selections = 0

        override fun isOffline(sessionId: String) = offline
        override fun recoveryCode(sessionId: String) = code
        override fun hasRunningAttachment(sessionId: String): Boolean = running
        override fun startAttachment(session: FleetSession) {
            starts++
            startFailure?.let { throw it }
        }
        override fun selectAttachment(sessionId: String): Boolean {
            selections++
            return running
        }
    }

    private class FakeScheduler : AgentFleetSessionResumeController.Scheduler {
        private val pending = java.util.ArrayDeque<Runnable>()
        val delays = mutableListOf<Long>()
        fun peek(): Runnable? = pending.peekFirst()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) { pending += runnable; delays += delayMillis }
        override fun cancelAll() { pending.clear() }
        fun runNext() { pending.pollFirst()?.run() }
        fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private fun session() = FleetSession(
        id = "gaming:wtmux",
        hostId = "gaming",
        internalName = "wtmux-main",
        name = "wtmux",
        title = "Codex",
        project = "wtmux",
        tool = "codex",
        backend = "linux",
        activity = "active",
        attached = false,
        updatedAt = null,
        pendingScheduleCount = 0
    )
}
