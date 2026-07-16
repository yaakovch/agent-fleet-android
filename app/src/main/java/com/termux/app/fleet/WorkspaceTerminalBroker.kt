package com.termux.app.fleet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.termux.app.TermuxService
import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxSession
import com.termux.terminal.TerminalSession

data class WorkspaceTerminalBinding(
    val sessionId: String,
    val terminal: TerminalSession? = null,
    val status: String = "connecting",
    val message: String = "Connecting…"
)

class WorkspaceTerminalBroker(context: Context) : ServiceConnection {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val runtime = FleetRuntime(appContext)
    private val states = mutableMapOf<String, androidx.compose.runtime.MutableState<WorkspaceTerminalBinding>>()
    private val requested = mutableMapOf<String, FleetSession>()
    private val starting = mutableSetOf<String>()
    private val pendingFullscreen = mutableMapOf<String, List<String>>()
    private val attachedIds = mutableStateOf<Set<String>>(emptySet())
    private var activeIds = emptySet<String>()
    private var service: TermuxService? = null
    private var bound = false

    val attachedSessionIds: State<Set<String>> = attachedIds

    fun start() {
        if (bound) return
        val intent = Intent(appContext, TermuxService::class.java)
        appContext.startService(intent)
        bound = appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun state(sessionId: String): State<WorkspaceTerminalBinding> = states.getOrPut(sessionId) {
        mutableStateOf(WorkspaceTerminalBinding(sessionId))
    }

    fun attach(session: FleetSession) {
        requested[session.id] = session
        states.getOrPut(session.id) { mutableStateOf(WorkspaceTerminalBinding(session.id)) }
        connect(session, startIfMissing = true)
    }

    fun setActiveSessions(sessions: Collection<FleetSession>) {
        activeIds = sessions.map(FleetSession::id).take(AgentFleetAttachmentPolicy.MAX_RETAINED_ATTACHMENTS).toSet()
        service?.setAgentFleetActiveSessionIds(activeIds)
        refreshAttachments()
    }

    fun detach(sessionId: String) {
        requested.remove(sessionId)
        starting.remove(sessionId)
        pendingFullscreen.remove(sessionId)
        states.remove(sessionId)
        service?.finishAgentFleetWorkspaceSession(sessionId)
        attachedIds.value = attachedIds.value - sessionId
    }

    fun refreshAttachments() {
        attachedIds.value = service?.agentFleetWorkspaceSessionIds.orEmpty()
    }

    fun write(sessionId: String, value: String): Boolean {
        if (value.isEmpty() || value.length > 32_768) return false
        val terminal = states[sessionId]?.value?.terminal ?: return false
        val bytes = value.toByteArray(Charsets.UTF_8)
        terminal.write(bytes, 0, bytes.size)
        return true
    }

    fun key(sessionId: String, key: String): Boolean {
        val value = when (key) {
            "ENTER" -> "\r"
            "CTRL_C" -> "\u0003"
            "TAB" -> "\t"
            "SHIFT_TAB" -> "\u001b[Z"
            "UP" -> "\u001b[A"
            "DOWN" -> "\u001b[B"
            else -> return false
        }
        return write(sessionId, value)
    }

    fun openFullscreen(session: FleetSession, sharedImages: List<String> = emptyList()): Boolean {
        requested[session.id] = session
        states.getOrPut(session.id) { mutableStateOf(WorkspaceTerminalBinding(session.id)) }
        pendingFullscreen.clear()
        pendingFullscreen[session.id] = sharedImages.take(8)
        activeIds = setOf(session.id)
        service?.setAgentFleetActiveSessionIds(activeIds)
        val existing = service?.getAgentFleetWorkspaceSession(session.id)
        if (existing != null) {
            updateBinding(session.id, existing)
            launchFullscreen(session, pendingFullscreen.remove(session.id).orEmpty())
        } else {
            connect(session, startIfMissing = true)
        }
        return true
    }

    private fun launchFullscreen(session: FleetSession, sharedImages: List<String>) {
        val host = service ?: return
        if (!host.selectAgentFleetWorkspaceSession(session.id)) return
        refreshAttachments()
        appContext.startActivity(Intent(appContext, TermuxActivity::class.java).apply {
            putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, session.tool in setOf("codex", "claude", "copilot"))
            putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
            putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, session.id)
            putExtra(AgentFleetContract.EXTRA_HOST_ID, session.hostId)
            putExtra(AgentFleetContract.EXTRA_PROJECT, session.project)
            putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, session.internalName)
            putExtra(AgentFleetContract.EXTRA_SESSION_NAME, session.name)
            if (sharedImages.isNotEmpty()) putStringArrayListExtra(
                AgentFleetContract.EXTRA_SHARED_IMAGES, ArrayList(sharedImages)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        if (bound) runCatching { appContext.unbindService(this) }
        bound = false
        service = null
        starting.clear()
        pendingFullscreen.clear()
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = (binder as? TermuxService.LocalBinder)?.service
        service?.reconcileAgentFleetWorkspaceSessions()
        service?.setAgentFleetActiveSessionIds(activeIds)
        refreshAttachments()
        requested.values.toList().forEach { connect(it, startIfMissing = true) }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        requested.keys.forEach { id ->
            states[id]?.value = WorkspaceTerminalBinding(id, status = "offline", message = "Local terminal service disconnected")
        }
    }

    private fun connect(session: FleetSession, startIfMissing: Boolean) {
        val host = service ?: return
        val existing: TermuxSession? = host.getAgentFleetWorkspaceSession(session.id)
        if (existing != null) {
            starting.remove(session.id)
            updateBinding(session.id, existing)
            pendingFullscreen.remove(session.id)?.let { launchFullscreen(session, it) }
            return
        }
        if (!startIfMissing || !starting.add(session.id)) return
        states[session.id]?.value = WorkspaceTerminalBinding(session.id, status = "connecting", message = "Starting local attachment…")
        runCatching { runtime.startWorkspaceSession(session) }.onFailure { error ->
            starting.remove(session.id)
            states[session.id]?.value = WorkspaceTerminalBinding(
                session.id, status = "error", message = error.message ?: "Local attachment could not start"
            )
            return
        }
        poll(session, 0)
    }

    private fun poll(session: FleetSession, attempt: Int) {
        main.postDelayed({
            if (requested[session.id] == null) return@postDelayed
            val existing = service?.getAgentFleetWorkspaceSession(session.id)
            if (existing != null) {
                starting.remove(session.id)
                updateBinding(session.id, existing)
                pendingFullscreen.remove(session.id)?.let { launchFullscreen(session, it) }
            } else if (attempt < 50) {
                poll(session, attempt + 1)
            } else {
                starting.remove(session.id)
                states[session.id]?.value = WorkspaceTerminalBinding(
                    session.id, status = "error", message = "Local attachment did not become ready"
                )
            }
        }, if (attempt == 0) 80 else 120)
    }

    private fun updateBinding(sessionId: String, session: TermuxSession) {
        states.getOrPut(sessionId) { mutableStateOf(WorkspaceTerminalBinding(sessionId)) }.value = WorkspaceTerminalBinding(
            sessionId,
            session.terminalSession,
            if (session.terminalSession.isRunning) "live" else "ended",
            if (session.terminalSession.isRunning) "Live" else "Attachment ended"
        )
        if (session.terminalSession.isRunning) attachedIds.value = attachedIds.value + sessionId
    }
}
