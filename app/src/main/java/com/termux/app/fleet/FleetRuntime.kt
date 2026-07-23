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
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.concurrent.thread

internal class ReusableRepositoryBridge<T : Any>(
    private val isAlive: (T) -> Boolean,
    private val closeResource: (T) -> Unit
) {
    private val lock = Any()
    private var current: T? = null

    fun acquire(factory: () -> T): T = synchronized(lock) {
        current?.takeIf(isAlive) ?: factory().also { replacement ->
            current?.let(closeResource)
            current = replacement
        }
    }

    fun discard(resource: T) {
        synchronized(lock) {
            if (current === resource) current = null
        }
        closeResource(resource)
    }

    fun close() {
        val resource = synchronized(lock) { current.also { current = null } }
        resource?.let(closeResource)
    }
}

internal fun conciseFleetError(value: String, fallback: String = "Fleet refresh failed."): String {
    val lines = value.lineSequence()
        .map { line -> line.filterNot(Char::isISOControl).trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return fallback
    val preferred = lines.lastOrNull { line ->
        line.startsWith("wtmux file:", ignoreCase = true) ||
            line.substringBefore(':').let { label -> label.endsWith("Error") || label.endsWith("Exception") }
    } ?: lines.last()
    return preferred.take(180)
}

internal fun killSessionWithStaleRetry(
    snapshot: FleetSnapshot,
    session: FleetSession,
    execute: (FleetSnapshot, FleetSession) -> FleetSnapshot,
    refresh: () -> FleetSnapshot
): FleetSnapshot {
    if (!isFleetSessionAvailable(snapshot, session)) {
        throw FleetUnavailableException("${session.name}'s host is offline; no changes were made.", "host_offline")
    }
    return try {
        execute(snapshot, session)
    } catch (error: FleetUnavailableException) {
        if (error.code != "stale_revision") throw error
        val fresh = refresh()
        val current = fresh.sessions.firstOrNull { it.id == session.id } ?: return fresh
        if (!isFleetSessionAvailable(fresh, current)) {
            throw FleetUnavailableException("${current.name}'s host is offline; no changes were made.", "host_offline")
        }
        execute(fresh, current)
    }
}

internal fun doctorHostWithStaleRetry(
    snapshot: FleetSnapshot,
    hostId: String,
    execute: (FleetSnapshot, String) -> FleetDoctorResult,
    refresh: () -> FleetSnapshot
): FleetDoctorResult {
    require(snapshot.hosts.any { it.id == hostId }) { "Host is not part of this fleet snapshot." }
    return try {
        execute(snapshot, hostId)
    } catch (error: FleetUnavailableException) {
        if (error.code != "stale_revision") throw error
        val fresh = refresh()
        require(fresh.hosts.any { it.id == hostId }) { "Host is no longer part of this fleet snapshot." }
        execute(fresh, hostId)
    }
}

class FleetRuntime(private val context: Context) {
    private data class BridgeOptionSupport(
        val identity: String,
        val sessionTitles: Boolean,
        val identityGraph: Boolean
    )

    private data class RepositoryBridge(
        val process: Process,
        val reader: BufferedReader,
        val writer: BufferedWriter
    )

    private val home = requireNotNull(context.filesDir.parentFile) { "Agent Fleet data directory is unavailable" }
    private val prefix = File(home, "files/usr")
    private val userHome = File(home, "files/home")
    private val repositoryRequestLock = Any()
    private val repositoryReaderExecutor = Executors.newSingleThreadExecutor()
    private val repositoryBridge = ReusableRepositoryBridge<RepositoryBridge>(
        isAlive = { it.process.isAliveCompat() },
        closeResource = ::closeRepositoryBridge
    )
    private val bridgeOptionSupportLock = Any()
    private var bridgeOptionSupport: BridgeOptionSupport? = null

    fun loadSnapshot(): FleetSnapshot {
        val bridge = executable("wtmux-bridge")
            ?: throw FleetUnavailableException("Pair or restore wtmux to connect this phone to your fleet.")
        val python = executable("python3")
            ?: throw FleetUnavailableException("Python is missing from the restored Termux environment.")
        val titlesEnabled = AutomaticSessionTitleSettings.isEnabled(context)
        val support = supportsBridgeOptions(python, bridge)
        val process = ProcessBuilder(listOf(python.absolutePath, bridge.absolutePath) + snapshotBridgeArguments(
            titlesEnabled,
            !titlesEnabled || support.sessionTitles,
            support.identityGraph
        ))
            .directory(userHome)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment()) }
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

        if (!process.waitForCompat(12, TimeUnit.SECONDS)) {
            process.destroyForciblyCompat()
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

    fun openSession(session: FleetSession, sharedImages: List<String> = emptyList()) {
        val wtmux = executable("wtmux")
            ?: throw FleetUnavailableException("wtmux is not installed in this Agent Fleet terminal.")
        val bash = executable("bash")
            ?: throw FleetUnavailableException("Bash is missing from the restored Termux environment.")
        val arguments = arrayOf(
            wtmux.absolutePath,
            "--noninteractive",
            "--host", session.hostId,
            "--project", session.project,
            "--session", session.internalName
        )
        openTerminalCommand(
            bash,
            arguments,
            "Agent Fleet · ${sessionIdentityPresentation(session).primary}",
            sessionIdentityPresentation(session).primary,
            AgentFleetContract.supportsComposerInput(session.tool),
            session,
            sharedImages
        )
    }

    fun openLocalShell() {
        val bash = executable("bash")
            ?: throw FleetUnavailableException("A local shell is unavailable.")
        val wrapper = executable("wtmux-shell")
        openTerminalCommand(
            bash,
            wrapper?.let { arrayOf(it.absolutePath) } ?: emptyArray(),
            "Agent Fleet · Local shell",
            "Local shell",
            false,
            null,
            emptyList(),
            localNative = true
        )
    }

    fun openPairing(invitation: String) {
        require(invitation.startsWith("wtmux://pair?") && invitation.length <= 4_096 && invitation.none { it.isISOControl() }) {
            "Paste a valid wtmux pairing invitation."
        }
        val client = executable("wtmux-pair-client")
            ?: throw FleetUnavailableException("The wtmux pairing client is not installed. Restore it in the terminal first.")
        val python = executable("python3")
            ?: throw FleetUnavailableException("Python is missing from the restored Termux environment.")
        openTerminalCommand(
            python,
            arrayOf(client.absolutePath, "pair", "--invitation", invitation),
            "Agent Fleet pairing",
            "Pair Agent Fleet",
            false,
            null,
            emptyList()
        )
    }

    private fun openTerminalCommand(
        executable: File,
        arguments: Array<String>,
        label: String,
        sessionName: String,
        composeInput: Boolean,
        session: FleetSession?,
        sharedImages: List<String>,
        localNative: Boolean = false
    ) {
        val uri = Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executable.absolutePath).build()
        val intent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri, context, TermuxService::class.java).apply {
            putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arguments)
            putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, userHome.absolutePath)
            putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)
            putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY.toString())
            putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label)
            if (session != null) putExtra(
                TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION,
                AgentFleetContract.WORKSPACE_SESSION_PREFIX + session.id
            )
            putExtra(AgentFleetContract.EXTRA_SESSION_NAME, sessionName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        context.startActivity(Intent(context, TermuxActivity::class.java).apply {
            putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, composeInput)
            putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, session != null || localNative)
            putExtra(AgentFleetContract.EXTRA_LOCAL_SESSION, localNative)
            if (session != null) {
                putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, session.id)
                putExtra(AgentFleetContract.EXTRA_HOST_ID, session.hostId)
                putExtra(AgentFleetContract.EXTRA_PROJECT, session.project)
                putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, session.internalName)
            }
            if (sharedImages.isNotEmpty()) putStringArrayListExtra(AgentFleetContract.EXTRA_SHARED_IMAGES, ArrayList(sharedImages.take(8)))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun startWorkspaceSession(session: FleetSession) {
        val wtmux = executable("wtmux")
            ?: throw FleetUnavailableException("wtmux is not installed in this Agent Fleet terminal.")
        val bash = executable("bash")
            ?: throw FleetUnavailableException("Bash is missing from the restored Termux environment.")
        val uri = Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(bash.absolutePath).build()
        val intent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri, context, TermuxService::class.java).apply {
            putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(
                wtmux.absolutePath, "--noninteractive", "--host", session.hostId,
                "--project", session.project, "--session", session.internalName
            ))
            putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, userHome.absolutePath)
            putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)
            putExtra(
                TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY.toString()
            )
            putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Agent Fleet · ${sessionIdentityPresentation(session).primary}")
            putExtra(TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, AgentFleetContract.WORKSPACE_SESSION_PREFIX + session.id)
            putExtra(AgentFleetContract.EXTRA_SESSION_NAME, sessionIdentityPresentation(session).primary)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
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

    fun resetSessionName(snapshot: FleetSnapshot, session: FleetSession): FleetSnapshot = mutate(
        "session.name.reset",
        JSONObject()
            .put("hostId", session.hostId)
            .put("sessionId", session.id)
            .put("expectedRevision", snapshot.revision)
            .put("idempotencyKey", UUID.randomUUID().toString())
    )

    fun createSession(
        snapshot: FleetSnapshot,
        hostId: String,
        project: String,
        backend: String,
        tool: String,
        path: String,
        locationKind: String
    ): FleetSnapshot {
        require(project.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}"))) { "Enter a valid session label." }
        require(backend in setOf("linux", "windows")) { "Choose a valid backend." }
        require(tool in setOf("shell", "codex", "claude", "copilot")) { "Choose a valid tool." }
        require(validDirectoryPath(path, backend, false)) { "Choose a valid folder." }
        require(locationKind in setOf("project", "custom")) { "Choose a valid location type." }
        return mutate(
            "session.create",
            JSONObject()
                .put("hostId", hostId)
                .put("project", project)
                .put("backend", backend)
                .put("tool", tool)
                .put("path", path)
                .put("locationKind", locationKind)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
    }

    fun listDirectory(snapshot: FleetSnapshot, hostId: String, backend: String, path: String): FleetDirectoryListing {
        require(hostId.isNotBlank() && hostId.length <= 160 && backend in setOf("linux", "windows")) { "Directory request is invalid." }
        require(validDirectoryPath(path, backend, true)) { "Directory path is invalid." }
        val result = request(
            "directory.list",
            JSONObject()
                .put("hostId", hostId)
                .put("backend", backend)
                .put("path", path)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
        return parseDirectoryListing(result)
    }

    fun createDirectory(snapshot: FleetSnapshot, hostId: String, backend: String, parentPath: String, name: String): String {
        require(validDirectoryPath(parentPath, backend, false)) { "Parent folder is invalid." }
        require(
            name.matches(Regex("[^./\\\\][^/\\\\]{0,126}")) &&
                name.none(Char::isISOControl) && !name.endsWith(" ") && !name.endsWith(".")
        ) { "Folder name is invalid." }
        val result = request(
            "directory.create",
            JSONObject()
                .put("hostId", hostId)
                .put("backend", backend)
                .put("parentPath", parentPath)
                .put("name", name)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
        return result.optString("path").takeIf { validDirectoryPath(it, backend, false) }
            ?: throw FleetUnavailableException("Host returned an invalid folder path.")
    }

    fun listRepository(
        snapshot: FleetSnapshot,
        session: FleetSession,
        relativePath: String,
        includeHidden: Boolean,
        cursor: String = ""
    ): FleetRepositoryPage {
        require(validRepositoryPath(relativePath, true) && validRepositoryCursor(cursor)) { "Repository path is invalid." }
        val result = repositoryRequest(
            "repository.list",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("relativePath", relativePath)
                .put("includeHidden", includeHidden)
                .put("cursor", cursor)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
        return parseRepositoryPage(result)
    }

    fun searchRepository(
        snapshot: FleetSnapshot,
        session: FleetSession,
        query: String,
        includeHidden: Boolean
    ): FleetRepositoryPage {
        val cleanQuery = query.trim()
        require(cleanQuery.length in 2..160 && cleanQuery.none(Char::isISOControl)) { "Search needs at least two characters." }
        val result = repositoryRequest(
            "repository.search",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("query", cleanQuery)
                .put("includeHidden", includeHidden)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
        return parseRepositoryPage(result)
    }

    fun getSessionModel(session: FleetSession, includeCatalog: Boolean): FleetModelControlState = parseModelControl(
        repositoryRequest(
            "session.model.get",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("includeCatalog", includeCatalog)
        ),
        session.id
    )

    fun setSessionModel(
        session: FleetSession,
        modelId: String,
        effortId: String,
        custom: Boolean,
        expectedConfigRevision: String,
        historyImpactAcknowledged: Boolean
    ): FleetModelControlState {
        require(modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}"))) { "Model ID is invalid." }
        require(effortId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}"))) { "Effort ID is invalid." }
        require(expectedConfigRevision.matches(Regex("[a-f0-9]{16}"))) { "Model state changed; refresh and try again." }
        val result = repositoryRequest(
            "session.model.set",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("modelId", modelId)
                .put("effortId", effortId)
                .put("custom", custom)
                .put("expectedConfigRevision", expectedConfigRevision)
                .put("idempotencyKey", UUID.randomUUID().toString())
                .put("historyImpactAcknowledged", historyImpactAcknowledged)
        )
        return parseModelControl(result.requireObject("modelControl"), session.id)
    }

    fun cancelSessionModel(session: FleetSession, expectedConfigRevision: String): FleetModelControlState {
        require(expectedConfigRevision.matches(Regex("[a-f0-9]{16}"))) { "Model state changed; refresh and try again." }
        val result = repositoryRequest(
            "session.model.cancel",
            JSONObject()
                .put("hostId", session.hostId)
                .put("sessionId", session.id)
                .put("expectedConfigRevision", expectedConfigRevision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        )
        return parseModelControl(result.requireObject("modelControl"), session.id)
    }

    fun closeRepositoryBrowser() {
        repositoryBridge.close()
    }

    fun shutdown() {
        closeRepositoryBrowser()
        repositoryReaderExecutor.shutdownNow()
    }

    fun downloadRepositoryFile(
        session: FleetSession,
        entry: FleetRepositoryEntry,
        cancellation: FleetDownloadCancellation,
        onProgress: (FleetDownloadState) -> Unit
    ): FleetDownloadState {
        require(entry.kind == "file" && entry.size != null && entry.size in 0..MAX_FILE_BYTES) { "File is not downloadable." }
        require(validRepositoryPath(entry.relativePath, false)) { "Repository file path is invalid." }
        val wtmux = executable("wtmux") ?: throw FleetUnavailableException("wtmux is not installed.")
        val bash = executable("bash") ?: throw FleetUnavailableException("Bash is missing from the terminal runtime.")
        val downloads = createAgentFleetDownloadDirectory(context)
        return try {
            onProgress(FleetDownloadState(entry.name, entry.relativePath, "running", 0, entry.size, message = "Starting download…"))
            val process = ProcessBuilder(
                bash.absolutePath, wtmux.absolutePath, "file", "download",
                "--host", session.hostId, "--session", session.internalName, "--path", entry.relativePath,
                "--output-dir", downloads.absolutePath, "--yes", "--json", "--json-progress"
            ).directory(userHome).apply { configureEnvironment(environment()) }.start()
            cancellation.bind(process)
            val output = ByteArrayOutputStream()
            val outputExceeded = AtomicBoolean(false)
            val errors = StringBuilder()
            val stdoutReader = thread(name = "fleet-download-output", isDaemon = true) {
                process.inputStream.use { input ->
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        val remaining = MAX_OUTPUT_BYTES - output.size()
                        if (remaining > 0) output.write(chunk, 0, minOf(count, remaining))
                        if (count > remaining) outputExceeded.set(true)
                    }
                }
            }
            val stderrReader = thread(name = "fleet-download-progress", isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val progress = runCatching { JSONObject(line) }.getOrNull()
                        val received = progress?.optLong("received", -1) ?: -1
                        val total = progress?.optLong("total", -1) ?: -1
                        if (progress?.optString("type") == "progress" && received in 0..total && total in 0..MAX_FILE_BYTES) {
                            val percent = if (total == 0L) 100 else (received * 100 / total).toInt()
                            onProgress(FleetDownloadState(entry.name, entry.relativePath, "running", received, total, message = "Downloading · $percent%"))
                        } else if (errors.length < MAX_ERROR_BYTES) {
                            errors.append(line).append('\n')
                        }
                    }
                }
            }
            val exitCode = process.waitFor()
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            if (cancellation.isCancelled()) {
                return FleetDownloadState(entry.name, entry.relativePath, "cancelled", 0, entry.size, message = "Download cancelled")
            }
            if (exitCode != 0 || outputExceeded.get()) {
                throw FleetUnavailableException(safeError(errors.toString().ifBlank { "Download failed." }))
            }
            val result = JSONObject(String(output.toByteArray(), Charsets.UTF_8).lineSequence().last { it.isNotBlank() })
            require(result.optString("status") == "downloaded") { "Host returned an invalid download result." }
            val resultName = result.getString("name").safeDirectoryLabel(255)
            require(!resultName.contains('/') && !resultName.contains('\\')) { "Host returned an invalid file name." }
            val resultFile = File(result.getString("path")).canonicalFile
            val root = downloads.canonicalFile
            require(resultFile.parentFile == root && resultFile.name == resultName && resultFile.isFile) {
                "Downloaded file was not written safely to Android Downloads."
            }
            val published = publishAgentFleetDownload(context, resultFile, resultName)
            FleetDownloadState(
                published.name, entry.relativePath, "completed", published.size, published.size,
                published.location, "Downloaded to Android Downloads"
            )
        } finally {
            cleanupAgentFleetDownloadDirectory(context, downloads)
        }
    }

    fun killSession(snapshot: FleetSnapshot, session: FleetSession): FleetSnapshot {
        val idempotencyKey = UUID.randomUUID().toString()
        fun execute(current: FleetSnapshot, target: FleetSession): FleetSnapshot = mutate(
            "session.kill",
            JSONObject()
                .put("hostId", target.hostId)
                .put("sessionId", target.id)
                .put("expectedRevision", current.revision)
                .put("idempotencyKey", idempotencyKey)
        )
        return killSessionWithStaleRetry(snapshot, session, ::execute, ::loadSnapshot)
    }

    fun doctorHost(snapshot: FleetSnapshot, hostId: String): FleetDoctorResult {
        return doctorHostWithStaleRetry(snapshot, hostId, ::doctorHostOnce, ::loadSnapshot)
    }

    private fun doctorHostOnce(snapshot: FleetSnapshot, hostId: String): FleetDoctorResult {
        val doctor = request(
            "host.doctor",
            JSONObject()
                .put("hostId", hostId)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", UUID.randomUUID().toString())
        ).optJSONObject("doctor") ?: throw FleetUnavailableException("Host doctor response is missing.")
        val status = doctor.getString("status").also { require(it in setOf("healthy", "attention", "failure")) }
        val checks = doctor.getJSONArray("checks").also { require(it.length() <= 32) }
        return FleetDoctorResult(
            hostId = doctor.getString("hostId").also { require(it == hostId) },
            checkedAt = doctor.getString("checkedAt").also { require(it.length <= 40 && it.none(Char::isISOControl)) },
            status = status,
            checks = List(checks.length()) { index ->
                checks.getJSONObject(index).let { check ->
                    FleetDoctorCheck(
                        id = check.getString("id").safeDiagnosticField(64),
                        status = check.getString("status").also { require(it in setOf("healthy", "attention", "failure")) },
                        summary = check.getString("summary").safeDiagnosticField(256),
                        detail = check.getString("detail").safeDiagnosticField(512, allowEmpty = true)
                    )
                }
            }
        )
    }

    fun scheduleContinue(
        snapshot: FleetSnapshot,
        session: FleetSession,
        deliverAtEpochMs: Long,
        attentionId: String? = null
    ): FleetSnapshot {
        require(deliverAtEpochMs > System.currentTimeMillis()) { "Scheduled time must be in the future." }
        if (attentionId != null) {
            require(attentionId.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) { "Limit action is invalid." }
            require(snapshot.attention.any {
                it.id == attentionId && it.hostId == session.hostId && it.sessionId == session.id &&
                    it.state in setOf("detected", "offering", "offered")
            }) { "This limit action is no longer available." }
        }
        val params = JSONObject()
            .put("hostId", session.hostId)
            .put("sessionId", session.id)
            .put("deliverAt", isoUtc(deliverAtEpochMs))
            .put("action", "continue")
            .put("expectedRevision", snapshot.revision)
            .put("idempotencyKey", UUID.randomUUID().toString())
        if (attentionId != null) params.put("attentionId", attentionId)
        return mutate(
            "schedule.create",
            params
        )
    }

    fun dismissAttention(
        snapshot: FleetSnapshot,
        attention: FleetAttention,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): FleetSnapshot {
        require(attention.state in setOf("detected", "offering", "offered")) {
            "This limit action is no longer available."
        }
        return mutate(
            "attention.dismiss",
            JSONObject()
                .put("hostId", attention.hostId)
                .put("attentionId", attention.id)
                .put("expectedRevision", snapshot.revision)
                .put("idempotencyKey", idempotencyKey)
        )
    }

    fun cancelSchedule(snapshot: FleetSnapshot, schedule: FleetSchedule): FleetSnapshot = mutate(
        "schedule.cancel",
        JSONObject()
            .put("hostId", schedule.hostId)
            .put("scheduleId", schedule.id)
            .put("expectedRevision", snapshot.revision)
            .put("idempotencyKey", UUID.randomUUID().toString())
    )

    fun attachCommand(session: FleetSession): String = listOf(
        "wtmux", "--noninteractive", "--host", session.hostId,
        "--project", session.project, "--session", session.internalName
    ).joinToString(" ") { shellDisplayQuote(it) }

    private fun mutate(method: String, params: JSONObject): FleetSnapshot {
        val snapshot = request(method, params).optJSONObject("snapshot")
            ?: throw FleetUnavailableException("Fleet action response did not include a snapshot.")
        return FleetSnapshotParser.parse(snapshot.toString())
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val bridge = executable("wtmux-bridge") ?: throw FleetUnavailableException("wtmux bridge is not installed.")
        val python = executable("python3") ?: throw FleetUnavailableException("Python is missing from the restored Termux environment.")
        val titlesEnabled = AutomaticSessionTitleSettings.isEnabled(context)
        val support = supportsBridgeOptions(python, bridge)
        val process = ProcessBuilder(listOf(python.absolutePath, bridge.absolutePath) + stdioBridgeArguments(
            titlesEnabled,
            !titlesEnabled || support.sessionTitles,
            support.identityGraph
        ))
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
        ControlContract.requireValidRequest(request)
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
                process.destroyForciblyCompat()
                throw FleetUnavailableException("Fleet action timed out or returned an invalid response.")
            }
            if (!response.optBoolean("ok")) {
                val error = response.optJSONObject("error")
                throw FleetUnavailableException(
                    safeError(error?.optString("message").orEmpty().ifBlank { "Fleet action failed." }),
                    error?.optString("code").orEmpty()
                )
            }
            return response.optJSONObject("result")
                ?.also(ControlResultContract::requireValidResult)
                ?: throw FleetUnavailableException("Fleet action response did not include a result.")
        } finally {
            process.destroy()
            readerExecutor.shutdownNow()
        }
    }

    private fun repositoryRequest(method: String, params: JSONObject): JSONObject = synchronized(repositoryRequestLock) {
        val channel = ensureRepositoryBridge()
        val requestId = UUID.randomUUID().toString()
        val request = JSONObject()
            .put("protocolVersion", 1)
            .put("type", "request")
            .put("requestId", requestId)
            .put("method", method)
            .put("timestamp", isoUtc(System.currentTimeMillis()))
            .put("params", params)
        ControlContract.requireValidRequest(request)
        try {
            channel.writer.write(request.toString())
            channel.writer.newLine()
            channel.writer.flush()
        } catch (error: Exception) {
            discardRepositoryBridge(channel)
            throw FleetUnavailableException("Repository connection was lost. Tap Retry to reconnect.", "bridge_disconnected")
        }

        val responseFuture = repositoryReaderExecutor.submit<JSONObject> {
            repeat(2_000) {
                val line = channel.reader.readLine()
                    ?: throw FleetUnavailableException("Repository connection closed without a response.", "bridge_disconnected")
                if (line.length <= MAX_OUTPUT_BYTES) {
                    val frame = runCatching { JSONObject(line) }.getOrNull()
                    if (frame?.optString("type") == "response" && frame.optString("requestId") == requestId) {
                        return@submit frame
                    }
                }
            }
            throw FleetUnavailableException("Repository response exceeded the safety limit.", "invalid_response")
        }
        val response = try {
            responseFuture.get(REPOSITORY_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            responseFuture.cancel(true)
            discardRepositoryBridge(channel)
            throw FleetUnavailableException("Repository request timed out. Tap Retry to reconnect.", "timeout")
        }
        if (!response.optBoolean("ok")) {
            val detail = response.optJSONObject("error")
            throw FleetUnavailableException(
                safeError(detail?.optString("message").orEmpty().ifBlank { "Repository request failed." }),
                detail?.optString("code").orEmpty()
            )
        }
        response.optJSONObject("result")
            ?.also(ControlResultContract::requireValidResult)
            ?: throw FleetUnavailableException("Repository response did not include a result.", "invalid_response")
    }

    private fun ensureRepositoryBridge(): RepositoryBridge = repositoryBridge.acquire {
        val bridge = executable("wtmux-bridge")
            ?: throw FleetUnavailableException("wtmux bridge is not installed.", "runtime_unavailable")
        val python = executable("python3")
            ?: throw FleetUnavailableException("Python is missing from the restored Termux environment.", "runtime_unavailable")
        val process = ProcessBuilder(python.absolutePath, bridge.absolutePath, "--stdio")
            .directory(userHome)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment()) }
            .start()
        RepositoryBridge(
            process,
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)),
            BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        )
    }

    private fun discardRepositoryBridge(channel: RepositoryBridge) {
        repositoryBridge.discard(channel)
    }

    private fun closeRepositoryBridge(channel: RepositoryBridge) {
        runCatching { channel.process.destroy() }
        runCatching { channel.writer.close() }
        runCatching { channel.reader.close() }
        if (channel.process.isAliveCompat()) runCatching { channel.process.destroyForciblyCompat() }
    }

    private fun parseDirectoryListing(value: JSONObject): FleetDirectoryListing {
        val backend = value.optString("backend").also { require(it in setOf("linux", "windows")) }
        val path = value.optString("path").also { require(validDirectoryPath(it, backend, false)) }
        val entries = value.optJSONArray("entries") ?: throw FleetUnavailableException("Directory entries are missing.")
        val shortcuts = value.optJSONArray("shortcuts") ?: throw FleetUnavailableException("Directory shortcuts are missing.")
        require(entries.length() <= 1_000 && shortcuts.length() <= 64)
        return FleetDirectoryListing(
            backend = backend,
            path = path,
            parentPath = if (value.isNull("parentPath")) null else value.optString("parentPath").also { require(validDirectoryPath(it, backend, false)) },
            entries = List(entries.length()) { index ->
                entries.getJSONObject(index).let { entry ->
                    FleetDirectoryEntry(
                        entry.getString("name").safeDirectoryLabel(255),
                        entry.getString("path").also { require(validDirectoryPath(it, backend, false)) }
                    )
                }
            },
            shortcuts = List(shortcuts.length()) { index ->
                shortcuts.getJSONObject(index).let { shortcut ->
                    FleetDirectoryShortcut(
                        shortcut.getString("id").safeDirectoryLabel(80),
                        shortcut.getString("label").safeDirectoryLabel(80),
                        shortcut.getString("path").also { require(validDirectoryPath(it, backend, false)) }
                    )
                }
            },
            truncated = value.optBoolean("truncated")
        )
    }

    private fun parseRepositoryPage(value: JSONObject): FleetRepositoryPage = FleetRepositoryProtocol.parsePage(value)

    internal fun parseModelControl(value: JSONObject, expectedSessionId: String): FleetModelControlState {
        value.requireFields(setOf("sessionId", "configRevision", "tool", "status", "selected", "effective", "pending", "catalog", "detail"))
        val sessionId = value.requireSafeString("sessionId", 320).also { require(it == expectedSessionId) }
        val revision = value.requireSafeString("configRevision", 16).also { require(it.matches(Regex("[a-f0-9]{16}"))) }
        val tool = value.requireSafeString("tool", 16).also { require(it in setOf("codex", "claude", "copilot")) }
        val status = value.requireSafeString("status", 32).also {
            require(it in setOf("ready", "queued", "applying", "cancelled", "already-clear", "expired", "error", "unknown"))
        }
        val catalogObject = value.optJSONObject("catalog")
        val catalog = catalogObject?.let { parseModelCatalog(it) }
        return FleetModelControlState(
            sessionId = sessionId,
            configRevision = revision,
            tool = tool,
            status = status,
            selected = parseModelSelection(value.requireObject("selected")),
            effective = if (value.isNull("effective")) null else parseModelSelection(value.requireObject("effective")),
            pending = if (value.isNull("pending")) null else parsePendingModel(value.requireObject("pending")),
            catalog = catalog?.first,
            customAllowed = catalog?.second ?: false,
            detail = value.requireSafeString("detail", 240, allowEmpty = true)
        )
    }

    private fun parseModelSelection(value: JSONObject): FleetModelSelection {
        value.requireFields(setOf("modelId", "modelLabel", "effortId", "effortLabel"))
        return FleetModelSelection(
            value.requireModelId("modelId"),
            value.requireSafeString("modelLabel", 120),
            value.requireEffortId("effortId"),
            value.requireSafeString("effortLabel", 120)
        )
    }

    private fun parsePendingModel(value: JSONObject): FleetPendingModelChange {
        value.requireFields(setOf("operationId", "modelId", "effortId", "custom", "requestedAt", "expiresAt"))
        return FleetPendingModelChange(
            value.requireSafeString("operationId", 160).also { require(it.matches(Regex("[A-Za-z0-9._:-]+"))) },
            value.requireModelId("modelId"),
            value.requireEffortId("effortId"),
            value.getBoolean("custom"),
            value.requireSafeString("requestedAt", 40),
            value.requireSafeString("expiresAt", 40)
        )
    }

    private fun parseModelCatalog(value: JSONObject): Pair<List<FleetModelOption>, Boolean> {
        value.requireFields(setOf("models", "customAllowed"))
        val models = value.getJSONArray("models")
        require(models.length() in 1..128)
        val parsed = List(models.length()) { index ->
            val item = models.getJSONObject(index)
            item.requireFields(setOf("id", "label", "description", "isDefault", "efforts", "defaultEffort"))
            val efforts = item.getJSONArray("efforts")
            require(efforts.length() in 1..16)
            val parsedEfforts = List(efforts.length()) { effortIndex ->
                val effort = efforts.getJSONObject(effortIndex)
                effort.requireFields(setOf("id", "label"))
                FleetModelEffortOption(effort.requireEffortId("id"), effort.requireSafeString("label", 80))
            }
            val defaultEffort = item.requireEffortId("defaultEffort")
            require(parsedEfforts.any { it.id == defaultEffort })
            FleetModelOption(
                item.requireModelId("id"), item.requireSafeString("label", 120),
                item.requireSafeString("description", 240, allowEmpty = true), item.getBoolean("isDefault"),
                parsedEfforts, defaultEffort
            )
        }
        return parsed to value.getBoolean("customAllowed")
    }

    private fun JSONObject.requireObject(name: String): JSONObject = optJSONObject(name)
        ?: throw FleetUnavailableException("Model control response is invalid.", "invalid_response")

    private fun JSONObject.requireFields(expected: Set<String>) {
        require(keys().asSequence().toSet() == expected) { "Model control response fields are invalid." }
    }

    private fun JSONObject.requireSafeString(name: String, maximum: Int, allowEmpty: Boolean = false): String =
        getString(name).also { require(it.length <= maximum && (allowEmpty || it.isNotBlank()) && it.none(Char::isISOControl)) }

    private fun JSONObject.requireModelId(name: String): String = requireSafeString(name, 160).also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")))
    }

    private fun JSONObject.requireEffortId(name: String): String = requireSafeString(name, 64).also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")))
    }

    private fun String.safeDirectoryLabel(maximum: Int): String = also {
        require(isNotBlank() && length <= maximum && none(Char::isISOControl))
    }

    private fun validDirectoryPath(value: String, backend: String, empty: Boolean): Boolean {
        if (value.length > 2_048 || (!empty && value.isBlank()) || value.any(Char::isISOControl)) return false
        if (value.isBlank()) return empty
        return if (backend == "linux") value.startsWith("/")
        else !value.startsWith("\\\\") && !value.startsWith("//") && value.matches(Regex("[A-Za-z]:[\\\\/].*"))
    }


    private fun validRepositoryPath(value: String, empty: Boolean): Boolean {
        if (value.length > 2_048 || (!empty && value.isBlank()) || value.startsWith('/') || value.contains('\\') || value.any(Char::isISOControl)) return false
        if (value.isBlank()) return empty
        return value.split('/').all { it.isNotBlank() && it !in setOf(".", "..") }
    }

    private fun validRepositoryCursor(value: String): Boolean = value.length <= 2_048 && value.none(Char::isISOControl)

    private fun executable(name: String): File? = sequenceOf(
        File(userHome, ".local/bin/$name"),
        File(prefix, "bin/$name")
    ).firstOrNull { it.isFile && it.canExecute() }

    private fun supportsBridgeOptions(python: File, bridge: File): BridgeOptionSupport {
        val identity = listOf(
            runCatching { bridge.canonicalPath }.getOrDefault(bridge.absolutePath),
            bridge.length().toString(),
            bridge.lastModified().toString()
        ).joinToString("|")
        return synchronized(bridgeOptionSupportLock) {
            bridgeOptionSupport?.takeIf { it.identity == identity } ?: run {
                val supported = probeBridgeOptions(python, bridge, identity)
                bridgeOptionSupport = supported
                supported
            }
        }
    }

    private fun probeBridgeOptions(python: File, bridge: File, identity: String): BridgeOptionSupport = runCatching {
        val process = ProcessBuilder(python.absolutePath, bridge.absolutePath, "--help")
            .directory(userHome)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment()) }
            .start()
        if (!process.waitForCompat(BRIDGE_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForciblyCompat()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            return@runCatching BridgeOptionSupport(identity, sessionTitles = false, identityGraph = false)
        }
        val output = ByteArrayOutputStream()
        process.inputStream.use { input ->
            val chunk = ByteArray(4 * 1024)
            while (output.size() <= MAX_BRIDGE_PROBE_BYTES) {
                val count = input.read(chunk)
                if (count < 0) break
                output.write(chunk, 0, count)
            }
        }
        val help = output.toString(Charsets.UTF_8.name())
        val valid = output.size() <= MAX_BRIDGE_PROBE_BYTES
        BridgeOptionSupport(
            identity,
            sessionTitles = valid && bridgeHelpSupportsSessionTitles(process.exitValue(), help),
            identityGraph = valid && bridgeHelpSupportsIdentityGraph(process.exitValue(), help)
        )
    }.getOrDefault(BridgeOptionSupport(identity, sessionTitles = false, identityGraph = false))

    private fun configureEnvironment(environment: MutableMap<String, String>) {
        environment["HOME"] = userHome.absolutePath
        environment["PREFIX"] = prefix.absolutePath
        environment["PATH"] = listOf(File(userHome, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
        enableTermuxExec(environment, prefix)
    }

    private fun isoUtc(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))

    private fun shellDisplayQuote(value: String): String = if (value.matches(Regex("[A-Za-z0-9._:/-]+"))) value else
        "'${value.replace("'", "'\\''")}'"

    private fun safeError(value: String): String = conciseFleetError(value)

    private fun String.safeDiagnosticField(maximum: Int, allowEmpty: Boolean = false): String = also {
        require(length <= maximum && (allowEmpty || isNotBlank()) && none(Char::isISOControl))
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 256 * 1024
        private const val MAX_ERROR_BYTES = 4 * 1024
        private const val MAX_BRIDGE_PROBE_BYTES = 64 * 1024
        private const val BRIDGE_PROBE_TIMEOUT_SECONDS = 3L
        private const val REPOSITORY_REQUEST_TIMEOUT_SECONDS = 20L
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    }
}

class FleetUnavailableException(message: String, val code: String = "") : Exception(message)
