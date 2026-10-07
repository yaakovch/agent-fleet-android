package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.shared.logger.Logger
import com.termux.view.TerminalView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal data class PaneScrollbackSnapshot(
    val session: String,
    val columns: Int,
    val rows: Int,
    val historyLines: Int,
    val capturedLines: Int,
    val truncated: Boolean,
    val revision: String,
    val ansi: ByteArray
)

/** tmux chrome consumes rows outside the pane; wrapping still requires the same width. */
internal fun PaneScrollbackSnapshot.fitsViewport(columns: Int, rows: Int): Boolean =
    this.columns == columns && this.rows > 0 && this.rows <= rows

internal fun parsePaneScrollbackSnapshot(raw: String, expectedSession: String): PaneScrollbackSnapshot {
    val value = JSONObject(raw)
    require(value.optInt("protocolVersion") == 1) { "Unsupported pane scrollback protocol." }
    require(value.optString("type") == "pane.scrollback") { "Invalid pane scrollback response." }
    val session = value.optString("session")
    require(session == expectedSession) { "Pane scrollback returned the wrong session." }
    val columns = value.requiredInt("columns", 4..1_000)
    val rows = value.requiredInt("rows", 4..1_000)
    require(columns in 4..1_000 && rows in 4..1_000) { "Pane scrollback dimensions are invalid." }
    val encoded = value.optString("ansiBase64")
    require(encoded.length <= MAX_ENCODED_SCROLLBACK_CHARS) { "Pane scrollback is too large." }
    val ansi = try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Pane scrollback is malformed.")
    }
    require(ansi.isNotEmpty() && ansi.size <= MAX_SCROLLBACK_BYTES) { "Pane scrollback is empty or too large." }
    require(Base64.encodeToString(ansi, Base64.NO_WRAP) == encoded) { "Pane scrollback is malformed." }
    val revision = value.optString("revision")
    require(REVISION.matches(revision)) { "Pane scrollback revision is invalid." }
    val actualRevision = MessageDigest.getInstance("SHA-256").digest(ansi).joinToString("") { "%02x".format(it) }
    require(revision == actualRevision) { "Pane scrollback integrity check failed." }
    return PaneScrollbackSnapshot(
        session = session,
        columns = columns,
        rows = rows,
        historyLines = value.requiredInt("historyLines", 0..Int.MAX_VALUE),
        capturedLines = value.requiredInt("capturedLines", 0..MAX_CAPTURED_LINES),
        truncated = (value.opt("truncated") as? Boolean)
            ?: throw IllegalArgumentException("Pane scrollback truncation flag is invalid."),
        revision = revision,
        ansi = ansi
    )
}

private fun JSONObject.requiredInt(name: String, range: IntRange): Int {
    val number = opt(name) as? Number ?: throw IllegalArgumentException("Pane scrollback $name is invalid.")
    val long = number.toLong()
    require(number.toDouble() == long.toDouble() && long in range.first.toLong()..range.last.toLong()) {
        "Pane scrollback $name is invalid."
    }
    return long.toInt()
}

private object TerminalScrollbackExecutor {
    val value = Executors.newSingleThreadExecutor { task ->
        Thread(task, "terminal-scrollback-prefetch").apply { isDaemon = true }
    }
}

internal class PaneCaptureCache {
    private val entries = linkedMapOf<String, PaneScrollbackSnapshot>()
    @Synchronized fun put(identity: VerifiedSessionIdentity, snapshot: PaneScrollbackSnapshot): PaneScrollbackSnapshot {
        val key = sessionStateKey(identity, identity.backend) + ":${snapshot.columns}:${snapshot.rows}"
        val old = entries.remove(key)
        val current = if (old?.revision == snapshot.revision) old else snapshot
        entries[key] = current
        while (entries.size > 4) entries.remove(entries.keys.first())
        return current
    }
    @Synchronized fun size() = entries.size
}

private val paneCaptures = PaneCaptureCache()

/** Prefetches the real tmux pane and installs it into TerminalView's read-only renderer buffer. */
class TerminalScrollbackController(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val appRoot = requireNotNull(appContext.filesDir.parentFile)
    private val prefix = File(appRoot, "files/usr")
    private val home = File(appRoot, "files/home")
    private var hostId = ""
    private var internalSession = ""
    private var eligible = false
    private var alternateScreen = false
    private var lifecycleVisible = false
    private var terminalVisible = true
    private var terminalView: TerminalView? = null
    @Volatile private var generation = 0
    @Volatile private var requestRunning = false
    private var lastCaptureAt = 0L
    private var installedRevision = ""
    private var installedBinding = ""
    private var dirtyVersion = 1L
    private var capturedVersion = 0L
    private var pendingPrepared: Pair<PaneScrollbackSnapshot, TerminalView.PreparedScrollback>? = null
    var historyActive by mutableStateOf(false)
        private set
    var historyAvailable by mutableStateOf(false)
        private set
    private var openAfterCapture = false
    private var refreshVisible = false
    private val quietRequest = Runnable { requestIfEligible() }

    fun bind(intent: Intent?) {
        bind(
            intent?.getStringExtra(AgentFleetContract.EXTRA_HOST_ID).orEmpty(),
            intent?.getStringExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION).orEmpty(),
            intent?.getBooleanExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, false) == true
        )
    }

    fun bind(host: String, session: String, requested: Boolean) {
        generation++
        main.removeCallbacks(quietRequest)
        hostId = host
        internalSession = session
        eligible = requested && host.isNotBlank() && session.isNotBlank()
        historyAvailable = eligible && alternateScreen
        openAfterCapture = false; refreshVisible = false
        installedRevision = ""
        installedBinding = ""
        pendingPrepared = null
        dirtyVersion++; capturedVersion = 0
        lastCaptureAt = 0
        terminalView?.clearLocalScrollback()
        schedulePrefetch()
    }

    fun setTerminalView(view: TerminalView?) {
        if (terminalView === view) return
        terminalView?.clearLocalScrollback()
        terminalView?.setLocalHistoryListener(null)
        terminalView = view
        installedRevision = ""
        view?.setLocalHistoryListener(object : TerminalView.LocalHistoryListener {
            override fun onRequested() { showHistory() }
            override fun onOpened() { historyActive = true }
            override fun onClosed() {
                historyActive = false
                pendingPrepared?.let { (snapshot, prepared) ->
                    if (terminalView?.installPreparedScrollback(prepared) == true) installedRevision = snapshot.revision
                }
                pendingPrepared = null
                schedulePrefetch()
            }
        })
        alternateScreen = view?.mEmulator?.isAlternateBufferActive == true
        historyAvailable = eligible && alternateScreen
        schedulePrefetch()
    }

    fun setTerminalVisible(value: Boolean) {
        terminalVisible = value
        if (value) schedulePrefetch() else main.removeCallbacks(quietRequest)
    }

    fun onStart() {
        lifecycleVisible = true
        schedulePrefetch()
    }

    fun onStop() {
        lifecycleVisible = false
        generation++
        main.removeCallbacks(quietRequest)
        terminalView?.clearLocalScrollback()
        installedRevision = ""; installedBinding = ""; pendingPrepared = null
        openAfterCapture = false; refreshVisible = false
    }

    fun close() {
        onStop()
        terminalView = null
    }

    fun onTerminalScreenChanged(value: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onTerminalScreenChanged(value) }
            return
        }
        if (alternateScreen == value) return
        alternateScreen = value
        historyAvailable = eligible && value
        generation++
        main.removeCallbacks(quietRequest)
        if (!value) terminalView?.clearLocalScrollback() else schedulePrefetch()
    }

    fun onTerminalActivity() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::onTerminalActivity)
            return
        }
        dirtyVersion++
        schedulePrefetch()
    }

    private fun schedulePrefetch() {
        main.removeCallbacks(quietRequest)
        if (lifecycleVisible && terminalVisible && eligible && alternateScreen && !requestRunning &&
            terminalView?.isLocalScrollbackActive != true && (dirtyVersion != capturedVersion || terminalView?.hasLocalScrollback() != true)) {
            main.postDelayed(quietRequest, maxOf(PREFETCH_QUIET_MS, lastCaptureAt + MIN_CAPTURE_INTERVAL_MS - SystemClock.elapsedRealtime()))
        }
    }

    fun showHistory() {
        if (!historyAvailable) return
        openAfterCapture = terminalView?.enterPreparedLocalHistory() != true
        requestIfEligible(true)
    }

    fun returnToLive() { openAfterCapture = false; terminalView?.returnToLiveTerminal() }

    fun refresh() {
        if (!historyAvailable) return
        refreshVisible = true
        requestIfEligible(true)
    }

    private fun requestIfEligible(explicit: Boolean = false) {
        if (!lifecycleVisible || !terminalVisible || !eligible || !alternateScreen || requestRunning ||
            (!explicit && terminalView?.isLocalScrollbackActive == true)) return
        val host = hostId
        val session = internalSession
        val token = ++generation
        val colors = terminalView?.localScrollbackColors()
        val previousRevision = installedRevision
        val previousBinding = installedBinding
        val hasBuffer = terminalView?.hasLocalScrollback() == true
        val dimensions = terminalView?.mEmulator?.let { it.mColumns to it.mRows }
        val version = dirtyVersion
        lastCaptureAt = SystemClock.elapsedRealtime()
        requestRunning = true
        TerminalScrollbackExecutor.value.execute {
            val result = runCatching {
                val runtime = FleetRuntime(appContext)
                val identity = runCatching { runtime.sessionIdentity(host, session) }.getOrNull()
                var snapshot = loadSnapshot(host, session)
                if (identity != null) {
                    require(runtime.sessionIdentity(host, session) == identity) { "Session changed during capture." }
                    snapshot = paneCaptures.put(identity, snapshot)
                }
                val binding = identity?.let { sessionStateKey(it, it.backend) }.orEmpty()
                require(dimensions != null && snapshot.fitsViewport(dimensions.first, dimensions.second)) {
                    "Pane dimensions changed during capture."
                }
                // tmux chrome occupies viewport rows outside the captured pane.
                // Keep the wrapping width and fill the isolated viewport's height.
                val prepared = if (binding.isNotEmpty() && binding == previousBinding && snapshot.revision == previousRevision && hasBuffer) null else
                    TerminalView.prepareLocalScrollback(snapshot.ansi, snapshot.columns, dimensions.second, colors)
                Triple(snapshot, prepared, binding)
            }
            main.post {
                requestRunning = false
                if (token != generation || host != hostId || session != internalSession || !alternateScreen) {
                    schedulePrefetch()
                    return@post
                }
                result.onSuccess { (snapshot, prepared, binding) ->
                    if (installedBinding.isNotEmpty() && installedBinding != binding) terminalView?.clearLocalScrollback()
                    installedBinding = binding
                    capturedVersion = version
                    if (prepared != null) {
                        if (terminalView?.isLocalScrollbackActive == true && !refreshVisible) pendingPrepared = snapshot to prepared
                        else if (terminalView?.installPreparedScrollback(prepared, refreshVisible) == true) {
                            installedRevision = snapshot.revision
                            pendingPrepared = null
                        }
                    }
                    if (openAfterCapture) terminalView?.enterPreparedLocalHistory()
                    openAfterCapture = false; refreshVisible = false
                }.onFailure { error ->
                    openAfterCapture = false; refreshVisible = false
                    Logger.logWarn(LOG_TAG, "Pane scrollback prefetch failed: ${safeFailure(error)}")
                }
                if (dirtyVersion != capturedVersion) schedulePrefetch()
            }
        }
    }

    private fun loadSnapshot(host: String, session: String): PaneScrollbackSnapshot {
        require(host.isNotBlank() && session.isNotBlank()) { "Session identity is unavailable." }
        val bash = executable("bash") ?: error("Bash is unavailable.")
        val wtmux = executable("wtmux") ?: error("wtmux is unavailable.")
        val builder = ProcessBuilder(
            bash.absolutePath, wtmux.absolutePath, "pane", "scrollback",
            "--host", host, "--session", session, "--limit", SCROLLBACK_ROWS.toString()
        ).directory(home).redirectErrorStream(false)
        builder.environment()["HOME"] = home.absolutePath
        builder.environment()["PREFIX"] = prefix.absolutePath
        builder.environment()["PATH"] = listOf(File(home, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
        enableTermuxExec(builder.environment(), prefix)

        val process = builder.start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutExceeded = AtomicBoolean(false)
        val stderrExceeded = AtomicBoolean(false)
        val stdoutThread = drain(process.inputStream, stdout, MAX_STDOUT, stdoutExceeded, "terminal-scrollback-stdout")
        val stderrThread = drain(process.errorStream, stderr, MAX_STDERR, stderrExceeded, "terminal-scrollback-stderr")
        val finished = process.waitForCompat(20, TimeUnit.SECONDS)
        if (!finished) process.terminateAndReapCompat()
        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        if (!finished) error("Pane scrollback request timed out.")
        if (stdoutThread.isAlive || stderrThread.isAlive) {
            process.closePipesCompat()
            error("Pane scrollback response did not close.")
        }
        process.closePipesCompat()
        if (stdoutExceeded.get()) error("Pane scrollback response was too large.")
        if (process.exitValue() != 0) {
            if (stderrExceeded.get()) error("Pane scrollback failed with excessive output.")
            error(stderr.toString(Charsets.UTF_8.name()).ifBlank { "Pane scrollback request failed." })
        }
        val raw = stdout.toString(Charsets.UTF_8.name()).lineSequence().lastOrNull { it.isNotBlank() }
            ?: error("Pane scrollback returned no result.")
        return parsePaneScrollbackSnapshot(raw, session)
    }

    private fun executable(name: String): File? = sequenceOf(
        File(home, ".local/bin/$name"), File(prefix, "bin/$name")
    ).firstOrNull { it.canExecute() }

    private fun drain(
        input: InputStream,
        output: ByteArrayOutputStream,
        maximum: Int,
        exceeded: AtomicBoolean,
        name: String
    ): Thread = Thread({
        input.use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = try {
                    stream.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (count < 0) break
                if (output.size() + count > maximum) {
                    exceeded.set(true)
                } else if (!exceeded.get()) {
                    output.write(buffer, 0, count)
                }
            }
        }
    }, name).apply { isDaemon = true; start() }

    private fun safeFailure(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "timed out" in message -> "connection timed out"
            "session_not_found" in message || "session not found" in message -> "session changed"
            else -> error.javaClass.simpleName
        }
    }

    private companion object {
        const val LOG_TAG = "TerminalScrollback"
        const val PREFETCH_QUIET_MS = 900L
        const val MIN_CAPTURE_INTERVAL_MS = 5_000L
        const val SCROLLBACK_ROWS = 2_000
        const val MAX_STDOUT = 6 * 1024 * 1024
        const val MAX_STDERR = 64 * 1024
    }
}

private const val MAX_SCROLLBACK_BYTES = 4 * 1024 * 1024
private const val MAX_ENCODED_SCROLLBACK_CHARS = 6 * 1024 * 1024
private const val MAX_CAPTURED_LINES = 6_000
private val REVISION = Regex("[0-9a-f]{64}")
