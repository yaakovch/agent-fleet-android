package com.termux.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.R
import com.termux.app.fleet.AgentFleetAttachmentPolicy
import com.termux.app.fleet.AgentFleetContract
import com.termux.app.fleet.AgentFleetSessionResumeController
import com.termux.app.fleet.DrawerSessionStore
import com.termux.app.fleet.DrawerSessionSurface
import com.termux.app.fleet.FleetSession
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.shell.TermuxSession
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.view.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TermuxAttachmentServiceTest {
    @get:Rule val serviceRule = ServiceTestRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var service: TermuxService

    @Before
    fun bindService() {
        val binder = serviceRule.bindService(Intent(context, TermuxService::class.java))
        service = (binder as TermuxService.LocalBinder).service
    }

    @After
    fun cleanUp() {
        onMain {
            service.agentFleetWorkspaceSessionIds.toList().forEach(service::finishAgentFleetWorkspaceSession)
            service.classicTermuxSessions.toList().forEach { it.killIfExecuting(context, true) }
            TermuxAppSharedPreferences.build(context)?.setCurrentSession(null)
        }
    }

    @Test
    fun repeatedOpenReusesOneAttachmentAndLegacyDuplicatesAreReconciled() {
        val sessionId = "emulator:reuse"
        val first = createSession(sessionId, "First")
        val firstHandle = first.terminalSession.mHandle

        context.startService(managedOpenIntent(sessionId, "Reused"))
        waitUntil { service.getAgentFleetWorkspaceSession(sessionId)?.terminalSession?.mSessionName == "Reused" }
        assertEquals(firstHandle, service.getAgentFleetWorkspaceSession(sessionId)?.terminalSession?.mHandle)
        assertEquals(1, managedCount(sessionId))

        val duplicate = createSession(sessionId, "Legacy duplicate")
        TermuxAppSharedPreferences.build(context)?.setCurrentSession(duplicate.terminalSession.mHandle)
        onMain { service.reconcileAgentFleetWorkspaceSessions() }
        waitUntil { managedCount(sessionId) == 1 }
        assertEquals(duplicate.terminalSession.mHandle, service.getAgentFleetWorkspaceSession(sessionId)?.terminalSession?.mHandle)

        val newest = "emulator:cache-5"
        (2..5).forEach { createSession("emulator:cache-$it", "Cached $it") }
        onMain { service.setAgentFleetActiveSessionIds(setOf(newest)) }
        waitUntil { service.agentFleetWorkspaceSessionIds.size == AgentFleetAttachmentPolicy.MAX_RETAINED_ATTACHMENTS }
        check(newest in service.agentFleetWorkspaceSessionIds) { "The active attachment was evicted" }

        createSession(null, "Local shell")
        assertEquals(1, service.classicTermuxSessionsSize)
        assertEquals(AgentFleetAttachmentPolicy.MAX_RETAINED_ATTACHMENTS, service.agentFleetWorkspaceSessionIds.size)
        check(sessionId in service.agentFleetWorkspaceSessionIds) { "The reused attachment was not retained" }
        assertEquals(sessionId, service.getAgentFleetWorkspaceSessionId(service.getAgentFleetWorkspaceSession(sessionId)?.terminalSession))
    }

    @Test
    fun resumeReappliesManagedTerminalChromeAndComposer() {
        val descriptor = fleetSession("emulator:presentation")
        DrawerSessionStore(context).apply {
            recordOpened(descriptor, DrawerSessionSurface.Terminal)
            setActiveFullscreen(descriptor.id)
        }
        createSession(descriptor.id, descriptor.name)
        val intent = Intent(context, TermuxActivity::class.java).apply {
            putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, true)
            putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
            putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, descriptor.id)
            putExtra(AgentFleetContract.EXTRA_HOST_ID, descriptor.hostId)
            putExtra(AgentFleetContract.EXTRA_PROJECT, descriptor.project)
            putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, descriptor.internalName)
            putExtra(AgentFleetContract.EXTRA_SESSION_NAME, descriptor.name)
            putExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE, AgentFleetContract.SURFACE_TERMINAL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ActivityScenario.launch<TermuxActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.agent_fleet_terminal_chrome).visibility)
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.agent_fleet_composer).visibility)
                assertEquals(
                    context.resources.getDimensionPixelSize(R.dimen.agent_fleet_compact_session_header_height),
                    (activity.findViewById<TerminalView>(R.id.terminal_view).layoutParams as ViewGroup.MarginLayoutParams).topMargin
                )
                activity.findViewById<android.view.View>(R.id.agent_fleet_terminal_chrome).visibility = android.view.View.GONE
                activity.findViewById<android.view.View>(R.id.agent_fleet_composer).visibility = android.view.View.GONE
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.agent_fleet_terminal_chrome).visibility)
                assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.agent_fleet_composer).visibility)
                assertEquals(
                    context.resources.getDimensionPixelSize(R.dimen.agent_fleet_compact_session_header_height),
                    (activity.findViewById<TerminalView>(R.id.terminal_view).layoutParams as ViewGroup.MarginLayoutParams).topMargin
                )
            }
        }
    }

    @Test
    fun foregroundResumeReplacesAnExitedManagedAttachment() {
        val descriptor = fleetSession("emulator:resume")
        val expired = createSession(descriptor.id, "Expired")
        onMain { service.onTermuxSessionExited(expired) }
        waitUntil { service.getAgentFleetWorkspaceSession(descriptor.id) == null }
        var starts = 0
        var selected = ""
        val controller = AgentFleetSessionResumeController(
            { id -> descriptor.takeIf { it.id == id } },
            object : AgentFleetSessionResumeController.AttachmentHost {
                override fun hasRunningAttachment(sessionId: String): Boolean =
                    service.getAgentFleetWorkspaceSession(sessionId) != null

                override fun startAttachment(session: FleetSession) {
                    starts++
                    createSession(session.id, session.name)
                }

                override fun selectAttachment(sessionId: String): Boolean =
                    service.selectAgentFleetWorkspaceSession(sessionId)
            },
            object : AgentFleetSessionResumeController.Scheduler {
                override fun postDelayed(runnable: Runnable, delayMillis: Long) = runnable.run()
                override fun cancelAll() = Unit
            },
            object : AgentFleetSessionResumeController.Listener {
                override fun onSelected(sessionId: String) { selected = sessionId }
                override fun onError(message: String) { error(message) }
            }
        )

        controller.onForeground(descriptor.id)

        assertEquals(1, starts)
        assertEquals(descriptor.id, selected)
        assertEquals(1, managedCount(descriptor.id))
    }

    private fun createSession(sessionId: String?, name: String): TermuxSession {
        val created = AtomicReference<TermuxSession>()
        onMain {
            val command = ExecutionCommand(
                TermuxService.getNextExecutionId(),
                "/system/bin/sleep",
                arrayOf("30"),
                null,
                context.cacheDir.absolutePath,
                false,
                false
            ).apply {
                commandLabel = name
                commandDescription = sessionId?.let { AgentFleetContract.WORKSPACE_SESSION_PREFIX + it }
            }
            created.set(service.createTermuxSession(command, name))
        }
        return created.get().also(::assertNotNull)
    }

    private fun managedOpenIntent(sessionId: String, name: String) = Intent(
        TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
        Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path("/system/bin/sleep").build(),
        context,
        TermuxService::class.java
    ).apply {
        putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf("30"))
        putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, context.cacheDir.absolutePath)
        putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)
        putExtra(
            TERMUX_SERVICE.EXTRA_SESSION_ACTION,
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY.toString()
        )
        putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, name)
        putExtra(TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, AgentFleetContract.WORKSPACE_SESSION_PREFIX + sessionId)
        putExtra(AgentFleetContract.EXTRA_SESSION_NAME, name)
    }

    private fun managedCount(sessionId: String): Int = service.termuxSessions.count {
        AgentFleetAttachmentPolicy.sessionId(it.executionCommand?.commandDescription) == sessionId
    }

    private fun fleetSession(id: String) = FleetSession(
        id = id,
        hostId = "emulator",
        internalName = "resume",
        name = "Resume",
        title = "Codex",
        project = "wtmux",
        tool = "codex",
        backend = "linux",
        activity = "active",
        attached = false,
        updatedAt = null,
        pendingScheduleCount = 0
    )

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        check(condition()) { "Timed out waiting for managed attachment state" }
    }
}
