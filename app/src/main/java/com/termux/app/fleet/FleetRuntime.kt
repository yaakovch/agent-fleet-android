package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.concurrent.thread

class FleetRuntime(private val context: Context) {
    private val home = context.filesDir.parentFile ?: File("/data/data/com.termux")
    private val prefix = File(home, "files/usr")
    private val userHome = File(home, "files/home")

    fun loadSnapshot(): FleetSnapshot {
        val bridge = executable("wtmux-bridge")
            ?: throw FleetUnavailableException("Pair or restore wtmux to connect this phone to your fleet.")
        val process = ProcessBuilder(bridge.absolutePath, "--snapshot")
            .directory(userHome)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = userHome.absolutePath
                environment()["PREFIX"] = prefix.absolutePath
                environment()["PATH"] = listOf(File(userHome, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
            }
            .start()

        val outputBuffer = ByteArrayOutputStream()
        val outputReader = thread(name = "fleet-snapshot-reader", isDaemon = true) {
            process.inputStream.use { input ->
                val chunk = ByteArray(8 * 1024)
                while (outputBuffer.size() <= MAX_OUTPUT_BYTES) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    outputBuffer.write(chunk, 0, count)
                }
            }
        }

        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputReader.join(1_000)
            throw FleetUnavailableException("Fleet refresh timed out. Check Tailscale and host reachability.")
        }
        outputReader.join(1_000)
        val output = outputBuffer.toByteArray()
        if (output.size > MAX_OUTPUT_BYTES) throw FleetUnavailableException("Fleet response exceeded the safety limit.")
        if (process.exitValue() != 0) {
            val error = output.take(MAX_ERROR_BYTES).toByteArray().toString(Charsets.UTF_8).trim()
            throw FleetUnavailableException(safeError(error.ifBlank { "Fleet bridge exited with status ${process.exitValue()}." }))
        }
        return FleetSnapshotParser.parse(output.toString(Charsets.UTF_8))
    }

    fun openSession(session: FleetSession) {
        val wtmux = executable("wtmux")
            ?: throw FleetUnavailableException("wtmux is not installed in this Agent Fleet terminal.")
        val arguments = arrayOf(
            "--noninteractive",
            "--host", session.hostId,
            "--project", session.project,
            "--session", session.internalName
        )
        openTerminalCommand(wtmux, arguments, "Agent Fleet · ${session.name}", session.name)
    }

    fun openPairing(invitation: String) {
        require(invitation.startsWith("wtmux://pair?") && invitation.length <= 4_096 && invitation.none { it.isISOControl() }) {
            "Paste a valid wtmux pairing invitation."
        }
        val client = executable("wtmux-pair-client")
            ?: throw FleetUnavailableException("The wtmux pairing client is not installed. Restore it in the terminal first.")
        openTerminalCommand(client, arrayOf("pair", "--invitation", invitation), "Agent Fleet pairing", "Pair Agent Fleet")
    }

    private fun openTerminalCommand(executable: File, arguments: Array<String>, label: String, sessionName: String) {
        val uri = Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executable.absolutePath).build()
        val intent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri, context, TermuxService::class.java).apply {
            putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arguments)
            putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, userHome.absolutePath)
            putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)
            putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY.toString())
            putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label)
            putExtra(AgentFleetContract.EXTRA_SESSION_NAME, sessionName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        context.startActivity(Intent(context, TermuxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun renameSession(snapshot: FleetSnapshot, session: FleetSession, name: String): FleetSnapshot {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}"))) { "Use 1–64 letters, numbers, spaces, dots, underscores, or dashes." }
        return mutate(
            "session.rename",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("name", name)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
    }

    fun createSession(snapshot: FleetSnapshot, hostId: String, project: String, backend: String, tool: String): FleetSnapshot {
        require(project.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}"))) { "Enter a valid project name." }
        require(backend in setOf("linux", "windows")) { "Choose a valid backend." }
        require(tool in setOf("shell", "codex", "claude", "copilot")) { "Choose a valid tool." }
        return mutate(
            "session.create",
            JSONObject()
                .put("hostId", hostId)
                .put("project", project)
                .put("backend", backend)
                .put("tool", tool)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
    }

    fun killSession(snapshot: FleetSnapshot, session: FleetSession): FleetSnapshot = mutate(
        "session.kill",
        JSONObject()
            .put("hostId", session.hostId)
            .put("sessionId", session.id)
            .put("expectedRevision", snapshot.revision)
            .put("idempotencyKey", UUID.randomUUID().toString())
    )

    fun scheduleContinue(snapshot: FleetSnapshot, session: FleetSession, deliverAtEpochMs: Long): FleetSnapshot {
        require(deliverAtEpochMs > System.currentTimeMillis()) { "Scheduled time must be in the future." }
        return mutate(
            "schedule.create",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("deliverAt", isoUtc(deliverAtEpochMs))
                .put("action", "continue")
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
    }

    fun attachCommand(session: FleetSession): String = listOf(
        "wtmux", "--noninteractive", "--host", session.hostId,
        "--project", session.project, "--session", session.internalName
    ).joinToString(" ") { shellDisplayQuote(it) }

    private fun mutate(method: String, params: JSONObject): FleetSnapshot {
        val bridge = executable("wtmux-bridge") ?: throw FleetUnavailableException("wtmux bridge is not installed.")
        val process = ProcessBuilder(bridge.absolutePath, "--stdio")
            .directory(userHome)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment()) }
            .start()
        val requestId = UUID.randomUUID().toString()
        val request = JSONObject()
            .put("protocolVersion", 1)
            .put("type", "request")
            .put("requestId", requestId)
            .put("method", method)
            .put("timestamp", isoUtc(System.currentTimeMillis()))
            .put("params", params)
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
        }

        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val responseFuture = readerExecutor.submit<String> {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.take(2_000).firstOrNull { line ->
                        line.length <= MAX_OUTPUT_BYTES && runCatching {
                            val frame = JSONObject(line)
                            frame.optString("type") == "response" && frame.optString("requestId") == requestId
                        }.getOrDefault(false)
                    } ?: throw FleetUnavailableException("Fleet bridge closed without a response.")
                }
            }
            val response = try {
                JSONObject(responseFuture.get(20, TimeUnit.SECONDS))
            } catch (error: Exception) {
                process.destroyForcibly()
                throw FleetUnavailableException("Fleet action timed out or returned an invalid response.")
            }
            if (!response.optBoolean("ok")) {
                val error = response.optJSONObject("error")
                throw FleetUnavailableException(safeError(error?.optString("message").orEmpty().ifBlank { "Fleet action failed." }))
            }
            val snapshot = response.optJSONObject("result")?.optJSONObject("snapshot")
                ?: throw FleetUnavailableException("Fleet action response did not include a snapshot.")
            return FleetSnapshotParser.parse(snapshot.toString())
        } finally {
            process.destroy()
            readerExecutor.shutdownNow()
        }
    }

    private fun executable(name: String): File? = sequenceOf(
        File(userHome, ".local/bin/$name"),
        File(prefix, "bin/$name")
    ).firstOrNull { it.isFile && it.canExecute() }

    private fun configureEnvironment(environment: MutableMap<String, String>) {
        environment["HOME"] = userHome.absolutePath
        environment["PREFIX"] = prefix.absolutePath
        environment["PATH"] = listOf(File(userHome, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
    }

    private fun isoUtc(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))

    private fun shellDisplayQuote(value: String): String = if (value.matches(Regex("[A-Za-z0-9._:/-]+"))) value else
        "'${value.replace("'", "'\\''")}'"

    private fun safeError(value: String): String = value
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.take(180)
        ?.filterNot { it.isISOControl() }
        ?: "Fleet refresh failed."

    companion object {
        private const val MAX_OUTPUT_BYTES = 256 * 1024
        private const val MAX_ERROR_BYTES = 4 * 1024
    }
}

class FleetUnavailableException(message: String) : Exception(message)
