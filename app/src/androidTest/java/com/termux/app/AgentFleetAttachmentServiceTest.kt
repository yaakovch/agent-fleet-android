package com.termux.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.termux.app.fleet.AgentFleetAttachmentPolicy
import com.termux.app.fleet.AgentFleetContract
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.shell.TermuxSession
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
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
    }

    private fun createSession(sessionId: String?, name: String): TermuxSession {
        val created = AtomicReference<TermuxSession>()
        onMain {
            val command = ExecutionCommand(
                TermuxService.getNextExecutionId(),
                "/system/bin/sh",
                arrayOf("-c", "sleep 30"),
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
        Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path("/system/bin/sh").build(),
        context,
        TermuxService::class.java
    ).apply {
        putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf("-c", "sleep 30"))
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

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        check(condition()) { "Timed out waiting for managed attachment state" }
    }
}
