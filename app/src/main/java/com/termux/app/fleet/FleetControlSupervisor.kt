package com.termux.app.fleet

import android.content.Context
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import kotlin.concurrent.thread

data class FleetControlSupervisorMetrics(
    val processStarts: Int,
    val connectionGeneration: Int,
    val currentControlProcesses: Int,
    val queuedControlRequests: Int,
    val lastReadyLatencyMs: Long?,
    val lastRequestDurationMs: Long?
)

/**
 * One application-wide control bridge for foreground fleet surfaces.
 *
 * Conversation streams, PTYs, repository browsing, transfers, diagnostics and
 * updates deliberately remain outside this owner and keep independent
 * cancellation/failure lifecycles.
 */
object FleetControlSupervisor {
    private data class BridgePaths(
        val python: File,
        val bridge: File,
        val prefix: File,
        val userHome: File
    )

    private data class BridgeOptionSupport(
        val identity: String,
        val sessionTitles: Boolean,
        val identityGraph: Boolean
    )

    private data class ControlChannel(
        val process: Process,
        val reader: InputStream,
        val writer: BufferedWriter,
        val closing: AtomicBoolean = AtomicBoolean(false),
        @Volatile var lastFrameAt: Long = System.currentTimeMillis(),
        @Volatile var startupFailureCode: String = "",
        @Volatile var errorReader: Thread? = null
    )

    private data class ControlReply(
        val response: JSONObject? = null,
        val error: FleetUnavailableException? = null
    )

    private data class PendingRequest(
        val id: String,
        val replies: ArrayBlockingQueue<ControlReply> = ArrayBlockingQueue(1)
    )

    private val lock = Any()
    private val requestGate = Semaphore(SUPERVISOR_MAX_IN_FLIGHT_CONTROL, true)
    private val queuedRequests = AtomicInteger(0)
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fleet-control-heartbeat").apply { isDaemon = true }
    }
    private var applicationContext: Context? = null
    private var foreground = false
    private var channel: ControlChannel? = null
    private var pending: PendingRequest? = null
    private var state = SupervisorState()
    private var processStarts = 0
    private var reconnectAttempt = 0
    private var nextReconnectAt = 0L
    private var lastFailure: FleetUnavailableException? = null
    private var connectionStartedAt = 0L
    private var lastReadyLatencyMs: Long? = null
    private var lastRequestDurationMs: Long? = null
    private var optionSupport: BridgeOptionSupport? = null

    init {
        heartbeatExecutor.scheduleAtFixedRate(::checkHeartbeat, 5, 5, TimeUnit.SECONDS)
    }

    fun setForeground(context: Context, enabled: Boolean) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            foreground = enabled
        }
        if (!enabled) stop()
    }

    fun loadSnapshot(context: Context): FleetSnapshot =
        FleetSnapshotParser.parse(request(context, "fleet.snapshot", JSONObject()).toString())

    fun request(context: Context, method: String, params: JSONObject): JSONObject {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + SUPERVISOR_REQUEST_DEADLINE_MS
        val waiting = queuedRequests.incrementAndGet()
        if (waiting > SUPERVISOR_MAX_QUEUED_CONTROL) {
            queuedRequests.decrementAndGet()
            applyAction(SupervisorAction("queue-saturated"))
            throw FleetUnavailableException("Fleet control is busy. Try again.", "backpressure")
        }
        val acquired = try {
            requestGate.tryAcquire(remaining(deadline), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            queuedRequests.decrementAndGet()
        }
        if (!acquired) {
            applyAction(SupervisorAction("queue-saturated"))
            throw FleetUnavailableException("Fleet control is busy. Try again.", "backpressure")
        }

        val ephemeral = synchronized(lock) {
            applicationContext = context.applicationContext
            !foreground
        }
        var activeForRequest: ControlChannel? = null
        try {
            val active = ensureChannel(context.applicationContext)
            activeForRequest = active
            val requestId = UUID.randomUUID().toString()
            val request = JSONObject()
                .put("protocolVersion", 1)
                .put("type", "request")
                .put("requestId", requestId)
                .put("method", method)
                .put("timestamp", isoUtc(System.currentTimeMillis()))
                .put("params", params)
            ControlContract.requireValidRequest(request)
            val requestPending = PendingRequest(requestId)
            synchronized(lock) {
                if (channel !== active || pending != null) {
                    throw FleetUnavailableException("Fleet control connection changed. Try again.", "bridge_disconnected")
                }
                pending = requestPending
            }
            try {
                active.writer.write(request.toString())
                active.writer.newLine()
                active.writer.flush()
            } catch (_: Exception) {
                active.errorReader?.join(200)
                val code = active.startupFailureCode.ifBlank { "LOCAL_RUNTIME_UNAVAILABLE" }
                disconnect(active, code)
                throw failureFor(code)
            }

            val reply = requestPending.replies.poll(remaining(deadline), TimeUnit.MILLISECONDS)
            if (reply == null) {
                applyAction(SupervisorAction("request-timed-out"))
                disconnect(active, "request_timeout")
                throw FleetUnavailableException("Fleet discovery timed out. Retrying automatically.", "SNAPSHOT_TIMEOUT")
            }
            reply.error?.let { throw it }
            val response = requireNotNull(reply.response)
            if (!response.optBoolean("ok")) {
                val detail = response.optJSONObject("error")
                throw FleetUnavailableException(
                    safeError(detail?.optString("message").orEmpty().ifBlank { "Fleet action failed." }),
                    detail?.optString("code").orEmpty()
                )
            }
            val result = response.optJSONObject("result")
                ?: throw FleetUnavailableException("Fleet action response did not include a result.", "invalid_response")
            try {
                ControlResultContract.requireValidResult(result)
            } catch (_: IllegalArgumentException) {
                disconnect(active, "protocol_error")
                throw FleetUnavailableException("Fleet action returned an invalid result.", "invalid_response")
            }
            synchronized(lock) {
                reconnectAttempt = 0
                nextReconnectAt = 0
                lastFailure = null
            }
            applyReady()
            return result
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            activeForRequest?.let { disconnect(it, "cancelled") }
            throw FleetUnavailableException("Fleet action was cancelled.", "cancelled")
        } finally {
            synchronized(lock) { pending = null }
            synchronized(lock) { lastRequestDurationMs = System.currentTimeMillis() - startedAt }
            requestGate.release()
            if (ephemeral) stop()
        }
    }

    fun stop() {
        val previous: ControlChannel?
        synchronized(lock) {
            previous = channel
            channel = null
            nextReconnectAt = 0
            reconnectAttempt = 0
            lastFailure = null
            if (state.phase != "stopped") state = reduceSupervisorState(state, SupervisorAction("background-stop"))
            failPendingLocked(FleetUnavailableException("Fleet control stopped in the background.", "background"))
        }
        previous?.let(::closeChannel)
        synchronized(lock) {
            state = reduceSupervisorState(state, SupervisorAction("shutdown-complete"))
        }
    }

    fun restartForConfigurationChange() {
        stop()
    }

    internal fun state(): SupervisorState = synchronized(lock) { state.copy() }

    fun metrics(): FleetControlSupervisorMetrics = synchronized(lock) {
        FleetControlSupervisorMetrics(
            processStarts = processStarts,
            connectionGeneration = state.connectionGeneration,
            currentControlProcesses = if (channel?.process?.isAliveCompat() == true) 1 else 0,
            queuedControlRequests = queuedRequests.get(),
            lastReadyLatencyMs = lastReadyLatencyMs,
            lastRequestDurationMs = lastRequestDurationMs
        )
    }

    private fun ensureChannel(context: Context): ControlChannel {
        synchronized(lock) {
            channel?.takeIf { it.process.isAliveCompat() }?.let { return it }
            channel?.let(::closeChannel)
            channel = null
            val now = System.currentTimeMillis()
            if (now < nextReconnectAt) {
                throw lastFailure ?: FleetUnavailableException("Fleet control is reconnecting. Try again.", "LOCAL_RUNTIME_UNAVAILABLE")
            }
            state = when (state.phase) {
                "backoff" -> reduceSupervisorState(state, SupervisorAction("retry-elapsed"))
                "stopped", "shutting-down" -> reduceSupervisorState(state, SupervisorAction("foreground-start"))
                else -> state
            }
            val paths = bridgePaths(context)
            val support = supportsBridgeOptions(paths)
            val titlesEnabled = AutomaticSessionTitleSettings.isEnabled(context)
            val process = try {
                ProcessBuilder(listOf(paths.python.absolutePath, paths.bridge.absolutePath) + stdioBridgeArguments(
                    titlesEnabled,
                    !titlesEnabled || support.sessionTitles,
                    support.identityGraph
                ))
                    .directory(paths.userHome)
                    .redirectErrorStream(false)
                    .apply { configureEnvironment(environment(), paths) }
                    .start()
            } catch (_: Exception) {
                state = reduceSupervisorState(state, SupervisorAction("process-exited"))
                scheduleReconnectLocked()
                throw FleetUnavailableException("Fleet control bridge could not start.", "runtime_unavailable")
            }
            val created = ControlChannel(
                process,
                process.inputStream,
                BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            )
            channel = created
            processStarts += 1
            connectionStartedAt = now
            startReaders(created)
            return created
        }
    }

    private fun startReaders(active: ControlChannel) {
        active.errorReader = thread(name = "fleet-control-errors", isDaemon = true) {
            runCatching {
                BoundedUtf8LineReader(active.process.errorStream, 512).use { errors ->
                    while (!active.closing.get()) {
                        val line = errors.readLine() ?: break
                        if (line.startsWith("REGISTRY_INVALID:")) active.startupFailureCode = "REGISTRY_INVALID"
                    }
                }
            }
        }
        thread(name = "fleet-control-reader", isDaemon = true) {
            try {
                while (!active.closing.get()) {
                    val line = readBoundedLine(active.reader) ?: break
                    acceptFrame(active, JSONObject(line))
                }
                // EOF can race the bounded stderr classification; retain only
                // an allowlisted code, never the raw diagnostic text.
                active.errorReader?.join(200)
                if (!active.closing.get()) disconnect(active, active.startupFailureCode.ifBlank { "process_exit" })
            } catch (_: Exception) {
                if (!active.closing.get()) disconnect(active, "protocol_error")
            }
        }
    }

    private fun acceptFrame(active: ControlChannel, frame: JSONObject) {
        require(frame.optInt("protocolVersion", -1) == 1)
        require(frame.toString().toByteArray(Charsets.UTF_8).size <= SUPERVISOR_MAX_FRAME_BYTES)
        active.lastFrameAt = System.currentTimeMillis()
        when (frame.optString("type")) {
            "event" -> {
                val allowed = mutableSetOf(
                    "protocolVersion", "type", "eventId", "event", "timestamp", "revision", "data"
                )
                if (frame.has("presentationRevision")) allowed += "presentationRevision"
                require(frame.keys().asSequence().toSet() == allowed)
                require(frame.optString("event") == "fleet.heartbeat")
                val data = frame.getJSONObject("data")
                require(data.keys().asSequence().toSet() == setOf("hostCount"))
                require(data.getInt("hostCount") >= 0)
                applyReady()
            }
            "response" -> {
                val requestPending = synchronized(lock) {
                    pending?.takeIf { it.id == frame.optString("requestId") }
                } ?: throw IllegalArgumentException("Uncorrelated control response")
                val allowed = if (frame.optBoolean("ok")) {
                    setOf("protocolVersion", "type", "requestId", "timestamp", "ok", "result")
                } else {
                    setOf("protocolVersion", "type", "requestId", "timestamp", "ok", "error")
                }
                require(frame.keys().asSequence().toSet() == allowed)
                if (!frame.optBoolean("ok")) {
                    val detail = frame.getJSONObject("error")
                    require(detail.keys().asSequence().toSet() == setOf("code", "message"))
                    require(detail.getString("code").matches(Regex("[A-Za-z0-9._:-]{1,64}")))
                    require(detail.getString("message").length <= 256)
                    require(detail.getString("message").none(Char::isISOControl))
                }
                requestPending.replies.offer(ControlReply(response = frame))
            }
            else -> throw IllegalArgumentException("Unknown control frame")
        }
    }

    private fun checkHeartbeat() {
        val active = synchronized(lock) { channel } ?: return
        val elapsed = System.currentTimeMillis() - active.lastFrameAt
        if (elapsed > SUPERVISOR_HEARTBEAT_TIMEOUT_MS) {
            applyAction(SupervisorAction("heartbeat-expired"))
            disconnect(active, "heartbeat_timeout")
        } else if (elapsed > SUPERVISOR_HEARTBEAT_TIMEOUT_MS / 2) {
            applyAction(SupervisorAction("heartbeat-missed"))
        }
    }

    private fun disconnect(active: ControlChannel, code: String) {
        synchronized(lock) {
            if (channel !== active) return
            channel = null
            state = when (code) {
                "heartbeat_timeout" -> reduceSupervisorState(state, SupervisorAction("heartbeat-expired"))
                "request_timeout" -> reduceSupervisorState(state, SupervisorAction("request-timed-out"))
                "protocol_error" -> reduceSupervisorState(state, SupervisorAction("channel-failed", "control"))
                else -> reduceSupervisorState(state, SupervisorAction("process-exited"))
            }
            val failure = failureFor(code)
            lastFailure = failure
            failPendingLocked(failure)
            scheduleReconnectLocked()
        }
        closeChannel(active)
    }

    private fun failureFor(code: String): FleetUnavailableException {
        val stable = TransportContract.stableCode(code) ?: when (code) {
            "request_timeout" -> "SNAPSHOT_TIMEOUT"
            else -> "LOCAL_RUNTIME_UNAVAILABLE"
        }
        val recovery = TransportContract.recoveryFor(stable)
        return FleetUnavailableException(
            recovery?.let { "${it.title}. ${it.action}." } ?: "Fleet control is reconnecting. Try again.", stable
        )
    }

    private fun scheduleReconnectLocked() {
        if (!foreground) return
        val index = reconnectAttempt.coerceAtMost(SUPERVISOR_RECONNECT_DELAYS_MS.lastIndex)
        nextReconnectAt = System.currentTimeMillis() + SUPERVISOR_RECONNECT_DELAYS_MS[index]
        reconnectAttempt += 1
    }

    private fun applyReady() {
        synchronized(lock) {
            state = reduceSupervisorState(state, SupervisorAction("ready"))
            lastReadyLatencyMs = (System.currentTimeMillis() - connectionStartedAt).coerceAtLeast(0)
        }
    }

    private fun applyAction(action: SupervisorAction) {
        synchronized(lock) { state = reduceSupervisorState(state, action) }
    }

    private fun failPendingLocked(error: FleetUnavailableException) {
        pending?.replies?.offer(ControlReply(error = error))
    }

    private fun closeChannel(active: ControlChannel) {
        if (!active.closing.compareAndSet(false, true)) return
        runCatching { active.process.destroy() }
        runCatching { active.writer.close() }
        runCatching { active.reader.close() }
        if (active.process.isAliveCompat()) runCatching { active.process.destroyForciblyCompat() }
    }

    private fun bridgePaths(context: Context): BridgePaths {
        val home = requireNotNull(context.filesDir.parentFile) { "Agent Fleet data directory is unavailable" }
        val prefix = File(home, "files/usr")
        val userHome = File(home, "files/home")
        fun executable(name: String) = sequenceOf(
            File(userHome, ".local/bin/$name"),
            File(prefix, "bin/$name")
        ).firstOrNull { it.isFile && it.canExecute() }
        val bridge = executable("wtmux-bridge")
            ?: throw FleetUnavailableException("wtmux bridge is not installed.", "runtime_unavailable")
        val python = executable("python3")
            ?: throw FleetUnavailableException("Python is missing from the restored Termux environment.", "runtime_unavailable")
        return BridgePaths(python, bridge, prefix, userHome)
    }

    private fun supportsBridgeOptions(paths: BridgePaths): BridgeOptionSupport {
        val identity = listOf(
            runCatching { paths.bridge.canonicalPath }.getOrDefault(paths.bridge.absolutePath),
            paths.bridge.length().toString(),
            paths.bridge.lastModified().toString()
        ).joinToString("|")
        synchronized(lock) {
            optionSupport?.takeIf { it.identity == identity }?.let { return it }
        }
        val support = probeBridgeOptions(paths, identity)
        synchronized(lock) { optionSupport = support }
        return support
    }

    private fun probeBridgeOptions(paths: BridgePaths, identity: String): BridgeOptionSupport = runCatching {
        val process = ProcessBuilder(paths.python.absolutePath, paths.bridge.absolutePath, "--help")
            .directory(paths.userHome)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment(), paths) }
            .start()
        if (!process.waitForCompat(3, TimeUnit.SECONDS)) {
            process.destroyForciblyCompat()
            return@runCatching BridgeOptionSupport(identity, sessionTitles = false, identityGraph = false)
        }
        val output = ByteArrayOutputStream()
        process.inputStream.use { input ->
            val chunk = ByteArray(4 * 1024)
            while (output.size() <= 64 * 1024) {
                val count = input.read(chunk)
                if (count < 0) break
                output.write(chunk, 0, count)
            }
        }
        val help = output.toString(Charsets.UTF_8.name())
        val valid = output.size() <= 64 * 1024
        BridgeOptionSupport(
            identity,
            sessionTitles = valid && bridgeHelpSupportsSessionTitles(process.exitValue(), help),
            identityGraph = valid && bridgeHelpSupportsIdentityGraph(process.exitValue(), help)
        )
    }.getOrDefault(BridgeOptionSupport(identity, sessionTitles = false, identityGraph = false))

    private fun configureEnvironment(environment: MutableMap<String, String>, paths: BridgePaths) {
        environment["HOME"] = paths.userHome.absolutePath
        environment["PREFIX"] = paths.prefix.absolutePath
        environment["PATH"] = listOf(
            File(paths.userHome, ".local/bin"),
            File(paths.prefix, "bin"),
            File(paths.prefix, "bin/applets")
        ).joinToString(":")
        enableTermuxExec(environment, paths.prefix)
    }

    private fun readBoundedLine(reader: InputStream): String? {
        val result = ByteArrayOutputStream()
        while (true) {
            val value = reader.read()
            if (value < 0) return result.takeIf { it.size() > 0 }?.toString(Charsets.UTF_8.name())
            if (value == '\n'.code) return result.toString(Charsets.UTF_8.name())
            if (value != '\r'.code) result.write(value)
            require(result.size() <= SUPERVISOR_MAX_FRAME_BYTES) { "Control frame is too large" }
        }
    }

    private fun remaining(deadline: Long): Long =
        (deadline - System.currentTimeMillis()).coerceAtLeast(1)

    private fun isoUtc(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))

    private fun safeError(value: String): String = conciseFleetError(value)
}
