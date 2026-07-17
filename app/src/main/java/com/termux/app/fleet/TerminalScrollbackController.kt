package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
        terminalView?.clearLocalScrollback()
        schedulePrefetch()
    }

    fun setTerminalView(view: TerminalView?) {
        if (terminalView === view) return
        terminalView?.clearLocalScrollback()
        terminalView = view
        alternateScreen = view?.mEmulator?.isAlternateBufferActive == true
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
        generation++
        main.removeCallbacks(quietRequest)
        if (!value) terminalView?.clearLocalScrollback() else schedulePrefetch()
    }

    fun onTerminalActivity() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::onTerminalActivity)
            return
        }
        if (terminalView?.isLocalScrollbackActive == true) return
        schedulePrefetch()
    }

    private fun schedulePrefetch() {
        main.removeCallbacks(quietRequest)
        if (lifecycleVisible && terminalVisible && eligible && alternateScreen && !requestRunning &&
            terminalView?.isLocalScrollbackActive != true) {
            main.postDelayed(quietRequest, PREFETCH_QUIET_MS)
        }
    }

    private fun requestIfEligible() {
        if (!lifecycleVisible || !terminalVisible || !eligible || !alternateScreen || requestRunning ||
            terminalView?.isLocalScrollbackActive == true) return
        val host = hostId
        val session = internalSession
        val token = ++generation
        requestRunning = true
        TerminalScrollbackExecutor.value.execute {
            val result = runCatching { loadSnapshot(host, session) }
            main.post {
                requestRunning = false
                if (token != generation || host != hostId || session != internalSession || !alternateScreen) {
                    schedulePrefetch()
                    return@post
                }
                result.onSuccess { snapshot ->
                    terminalView.orNullInstall(snapshot)
                }.onFailure { error ->
                    Logger.logWarn(LOG_TAG, "Pane scrollback prefetch failed: ${safeFailure(error)}")
                }
            }
        }
    }

    private fun TerminalView?.orNullInstall(snapshot: PaneScrollbackSnapshot): Boolean =
        this?.setLocalScrollback(snapshot.ansi, snapshot.columns, snapshot.rows) == true

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
        if (!finished) process.destroyForciblyCompat()
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
        if (!finished) error("Pane scrollback request timed out.")
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
        const val SCROLLBACK_ROWS = 2_000
        const val MAX_STDOUT = 6 * 1024 * 1024
        const val MAX_STDERR = 64 * 1024
    }
}

private const val MAX_SCROLLBACK_BYTES = 4 * 1024 * 1024
private const val MAX_ENCODED_SCROLLBACK_CHARS = 6 * 1024 * 1024
private const val MAX_CAPTURED_LINES = 6_000
private val REVISION = Regex("[0-9a-f]{64}")
