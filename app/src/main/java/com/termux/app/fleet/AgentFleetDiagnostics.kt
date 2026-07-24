package com.termux.app.fleet

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

enum class DiagnosticStatus { Healthy, Attention, Failure }

data class AgentFleetDiagnosticCheck(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val summary: String,
    val detail: String = "",
    val durationMs: Long = 0
)

data class AgentFleetDiagnosticEvent(
    val id: String,
    val occurredAt: String,
    val epochMs: Long,
    val operation: String,
    val status: String,
    val durationMs: Long,
    val code: String,
    val message: String,
    val hostId: String? = null,
    val sessionId: String? = null
)

data class AgentFleetDiagnosticReport(
    val generatedAt: String,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val abi: String,
    val runtimeVersion: String,
    val fleetRevision: String,
    val checks: List<AgentFleetDiagnosticCheck>,
    val events: List<AgentFleetDiagnosticEvent>,
    val legacyUsage: LegacyUsage = LegacyUsage.blockedDefault(),
    val correlationId: String = LayeredDiagnostics.newCorrelationId()
) {
    val overall: DiagnosticStatus
        get() = when {
            checks.any { it.status == DiagnosticStatus.Failure } -> DiagnosticStatus.Failure
            checks.any { it.status == DiagnosticStatus.Attention } -> DiagnosticStatus.Attention
            else -> DiagnosticStatus.Healthy
        }

    fun preview(): String = buildString {
        append("Agent Fleet diagnostics · ").append(overall.label()).append('\n')
        append(generatedAt).append(" · app ").append(appVersion).append(" · Android ").append(androidVersion).append('\n')
        append(checks.count { it.status == DiagnosticStatus.Healthy }).append(" healthy · ")
        append(checks.count { it.status == DiagnosticStatus.Attention }).append(" attention · ")
        append(checks.count { it.status == DiagnosticStatus.Failure }).append(" failed")
        checks.forEach { check ->
            append('\n').append(check.status.symbol()).append(' ').append(check.title).append(": ").append(check.summary)
        }
        append("\n\nPrivacy: metadata only. No prompts, responses, transcripts, terminal output, credentials, tokens, invitations, attachments, or repository paths.")
    }

    fun diagnosticsJson(): String = LayeredDiagnostics.reportJson(this)

    fun eventsNdjson(): String = events.joinToString(separator = "\n", postfix = if (events.isEmpty()) "" else "\n") {
        it.toJson().toString()
    }

    fun contractDiagnosticsJson(): String = CompatibilityContract.diagnosticsJson(
        componentId = "android-app",
        version = appVersion,
        compatibilityStatus = checks.firstOrNull { it.id == "compatibility" }?.status ?: DiagnosticStatus.Attention,
        generatedAt = generatedAt
    )
}

internal fun localShellDiagnosticCommand(bash: File): List<String> =
    listOf(bash.absolutePath, "--noprofile", "--norc", "-c", "printf agent-fleet-diagnostic-ok")

data class DiagnosticsUiState(
    val running: Boolean = false,
    val report: AgentFleetDiagnosticReport? = null,
    val error: String = ""
)

class AgentFleetDiagnosticJournal(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(File(context.filesDir, "agent-fleet-diagnostics/events.ndjson"))

    @Synchronized
    fun record(
        operation: String,
        status: String,
        durationMs: Long = 0,
        code: String = "",
        message: String = "",
        hostId: String? = null,
        sessionId: String? = null
    ): AgentFleetDiagnosticEvent {
        val epoch = now()
        val event = AgentFleetDiagnosticEvent(
            id = "diag-${UUID.randomUUID().toString().take(8)}",
            occurredAt = diagnosticIsoUtc(epoch),
            epochMs = epoch,
            operation = safeDiagnosticToken(operation, "operation"),
            status = safeDiagnosticToken(status, "unknown"),
            durationMs = durationMs.coerceIn(0, 24L * 60L * 60L * 1_000L),
            code = safeDiagnosticToken(code, ""),
            message = safeDiagnosticText(message),
            hostId = hostId?.let(::safeDiagnosticIdentifier),
            sessionId = sessionId?.let(::safeDiagnosticIdentifier)
        )
        persist((events() + event).filter { epoch - it.epochMs <= MAX_EVENT_AGE_MS }.takeLast(MAX_EVENTS))
        return event
    }

    @Synchronized
    fun events(): List<AgentFleetDiagnosticEvent> {
        if (!file.isFile || file.length() > MAX_JOURNAL_BYTES * 2) return emptyList()
        val cutoff = now() - MAX_EVENT_AGE_MS
        return file.readLines(Charsets.UTF_8).takeLast(MAX_EVENTS * 2).mapNotNull { line ->
            runCatching { eventFromJson(JSONObject(line)) }.getOrNull()
        }.filter { it.epochMs >= cutoff }.takeLast(MAX_EVENTS)
    }

    private fun persist(input: List<AgentFleetDiagnosticEvent>) {
        var events = input.takeLast(MAX_EVENTS)
        var body = events.joinToString("\n") { it.toJson().toString() }.let { if (it.isEmpty()) it else "$it\n" }
        while (body.toByteArray(Charsets.UTF_8).size > MAX_JOURNAL_BYTES && events.isNotEmpty()) {
            events = events.drop(1)
            body = events.joinToString("\n") { it.toJson().toString() }.let { if (it.isEmpty()) it else "$it\n" }
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}")
        temporary.writeText(body, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            require(temporary.renameTo(file)) { "Diagnostics journal could not be updated" }
        }
    }

    companion object {
        const val MAX_EVENTS = 200
        const val MAX_JOURNAL_BYTES = 256 * 1024
        const val MAX_EVENT_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}

class AgentFleetDiagnosticsRunner(
    private val context: Context,
    private val runtime: EmbeddedRuntimeManager,
    private val policyStore: ClientPolicyStore,
    private val fleetRuntime: FleetRuntime,
    private val journal: AgentFleetDiagnosticJournal
) {
    private val appFiles = context.filesDir
    private val prefix = File(appFiles, "usr")
    private val home = File(appFiles, "home")

    fun run(snapshotHint: FleetSnapshot?): AgentFleetDiagnosticReport {
        val started = System.currentTimeMillis()
        val deadline = started + WHOLE_TIMEOUT_MS
        val checks = mutableListOf<AgentFleetDiagnosticCheck>()
        checks += check("app", "App and device") {
            val supported = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            require(supported.isNotBlank()) { "Android did not report an ABI" }
            "Android ${Build.VERSION.RELEASE} · $supported" to "App metadata and runtime architecture are readable."
        }
        checks += check("tools", "Required tools") {
            val names = listOf(
                "bash", "python3", "wtmux", "wtmux-bridge", "wtmux-host-runtime", "wtmux-scheduler"
            )
            val missing = names.filter { executable(it) == null }
            require(missing.isEmpty()) { "Missing ${missing.joinToString()}" }
            "${names.size} required tools ready" to
                "Built-in shell, Python, fleet bridge, stable host runtime, and scheduler launchers are executable."
        }
        val runtimeStatus = runCatching { withTimeout(LOCAL_TIMEOUT_SECONDS) { runtime.inspect() } }.getOrNull()
        checks += if (runtimeStatus == null) failedCheck("runtime", "Built-in runtime", "Runtime inspection timed out or failed") else {
            AgentFleetDiagnosticCheck(
                "runtime", "Built-in runtime",
                when { !runtimeStatus.usable -> DiagnosticStatus.Failure; runtimeStatus.repairNeeded -> DiagnosticStatus.Attention; else -> DiagnosticStatus.Healthy },
                runtimeStatus.detail,
                "${runtimeStatus.packageCount - runtimeStatus.missingOrOldPackages}/${runtimeStatus.packageCount} locked packages ready; baseline ${runtimeStatus.baseline.ifBlank { "not installed" }}."
            )
        }
        checks += check("downloads", "Downloads storage") { verifyAgentFleetDownloads(context) }
        checks += check("policy", "Pairing and updates") {
            val policy = policyStore.load() ?: throw DiagnosticAttention("No paired client policy is installed")
            "Policy ${policy.policyRevision} is ready" to "Signed app and runtime update sources are configured."
        }
        checks += AgentFleetDiagnosticCheck(
            "compatibility", "Protocol compatibility", DiagnosticStatus.Healthy,
            "Contracts ${CompatibilityContract.CONTRACT_PACKAGE_VERSION} are supported",
            "Control ${CompatibilityContract.CONTROL_VERSIONS.joinToString()} · conversation ${CompatibilityContract.CONVERSATION_VERSIONS.joinToString()} · workspace ${CompatibilityContract.WORKSPACE_LAYOUT_VERSIONS.joinToString()}."
        )
        val supervisor = FleetControlSupervisor.metrics()
        checks += AgentFleetDiagnosticCheck(
            "control-supervisor",
            "Control supervisor",
            if (supervisor.currentControlProcesses <= 1 && supervisor.queuedControlRequests <= SUPERVISOR_MAX_QUEUED_CONTROL) {
                DiagnosticStatus.Healthy
            } else {
                DiagnosticStatus.Failure
            },
            if (ClientSupervisorSettings.usesSharedControl(context)) {
                "${supervisor.currentControlProcesses} shared control process"
            } else {
                "Legacy one-shot control adapter enabled"
            },
            "Starts ${supervisor.processStarts} · generation ${supervisor.connectionGeneration} · " +
                "queued ${supervisor.queuedControlRequests} · ready ${supervisor.lastReadyLatencyMs ?: 0}ms · " +
                "request ${supervisor.lastRequestDurationMs ?: 0}ms."
        )
        checks += check("shell", "Local process") {
            val bash = executable("bash") ?: error("Bash is missing")
            val process = ProcessBuilder(localShellDiagnosticCommand(bash))
                .directory(home).apply { configureEnvironment(environment()) }.start()
            if (!process.waitForCompat(LOCAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForciblyCompat()
                error("Local shell timed out")
            }
            require(process.exitValue() == 0 && process.inputStream.bufferedReader().readText() == "agent-fleet-diagnostic-ok") {
                "Local shell returned an invalid result"
            }
            "Local shell is responsive" to "A bounded process started, returned the expected marker, and exited cleanly."
        }

        var snapshot = snapshotHint
        val fleetSeconds = ((deadline - System.currentTimeMillis()) / 1_000L).coerceIn(1L, REMOTE_TIMEOUT_SECONDS)
        checks += check("fleet", "Fleet snapshot", fleetSeconds) {
            snapshot = snapshot ?: fleetRuntime.loadSnapshot()
            val value = snapshot ?: error("Fleet snapshot is unavailable")
            "${value.hosts.size} hosts · ${value.sessions.size} sessions" to "The paired bridge returned a valid metadata snapshot."
        }
        snapshot?.let { value ->
            val remainingSeconds = ((deadline - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
            if (remainingSeconds == 0L) {
                checks += AgentFleetDiagnosticCheck(
                    "hosts", "Host health", DiagnosticStatus.Attention,
                    "Skipped after the 45 second diagnostic budget", "Run again when host connectivity is stable."
                )
            } else {
                checks += hostChecks(value, minOf(remainingSeconds, REMOTE_TIMEOUT_SECONDS))
            }
        }

        val elapsed = System.currentTimeMillis() - started
        if (elapsed > WHOLE_TIMEOUT_MS) {
            checks += AgentFleetDiagnosticCheck(
                "duration", "Diagnostics duration", DiagnosticStatus.Attention,
                "Checks exceeded the 45 second target", "Completed in ${elapsed}ms.", elapsed
            )
        }
        val report = AgentFleetDiagnosticReport(
            generatedAt = diagnosticIsoUtc(System.currentTimeMillis()),
            appVersion = installedVersion(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            device = safeDiagnosticText("${Build.MANUFACTURER} ${Build.MODEL}"),
            abi = safeDiagnosticText(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
            runtimeVersion = runtimeStatus?.current.orEmpty().ifBlank { runtimeStatus?.baseline.orEmpty().ifBlank { "unavailable" } },
            fleetRevision = safeDiagnosticIdentifier(snapshot?.revision.orEmpty()),
            checks = checks,
            events = journal.events(),
            legacyUsage = createLegacyUsage(LegacyUsageInput(
                successfulReleaseCycles = 0,
                registeredHosts = snapshot?.physicalHosts?.size ?: 0,
                verifiedHosts = 0,
                registeredClients = 1,
                verifiedClients = 1,
                syntheticWindowsIdentities = snapshot?.physicalHosts.orEmpty()
                    .flatMap(FleetPhysicalHost::legacyHostIds)
                    .count { it.endsWith("_windows") },
                ambientRuntimeResolutions = 0,
                androidOneShotControlStarts = ClientSupervisorSettings.oneShotControlStarts(),
                legacyConfigFields = if (ClientSupervisorSettings.usesSharedControl(context)) 0 else 1
            ))
        )
        journal.record("diagnostics.run", report.overall.wire(), elapsed, message = "${checks.size} checks completed")
        return report.copy(events = journal.events())
    }

    fun export(report: AgentFleetDiagnosticReport): File {
        val directory = File(context.cacheDir, "agent-fleet-diagnostics").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > EXPORT_MAX_AGE_MS }?.forEach(File::delete)
        val file = File(directory, "agent-fleet-diagnostics-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics-v2.json"))
            zip.write(report.diagnosticsJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        journal.record("diagnostics.export", "healthy", message = "Metadata-only report exported")
        return file
    }

    private fun hostChecks(snapshot: FleetSnapshot, timeoutSeconds: Long): List<AgentFleetDiagnosticCheck> {
        val hosts = snapshot.hosts.take(MAX_DOCTOR_HOSTS)
        if (hosts.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(minOf(hosts.size, 4))
        return try {
            val futures = executor.invokeAll(hosts.map { host -> Callable {
                runCatching { fleetRuntime.doctorHost(snapshot, host.id) }.fold(
                    onSuccess = { doctor -> AgentFleetDiagnosticCheck(
                        id = "host-${safeDiagnosticIdentifier(host.id)}",
                        title = "Host · ${safeDiagnosticText(host.name)}",
                        status = doctor.status.toDiagnosticStatus(),
                        summary = "${doctor.checks.count { it.status == "healthy" }}/${doctor.checks.size} host checks healthy",
                        detail = doctor.checks.joinToString(" · ") { "${safeDiagnosticText(it.summary)} (${it.status})" }
                    ) },
                    onFailure = { failedCheck("host-${safeDiagnosticIdentifier(host.id)}", "Host · ${safeDiagnosticText(host.name)}", safeDiagnosticText(it.message.orEmpty())) }
                )
            } }, timeoutSeconds, TimeUnit.SECONDS)
            futures.mapIndexed { index, future ->
                if (future.isCancelled) failedCheck("host-${safeDiagnosticIdentifier(hosts[index].id)}", "Host · ${safeDiagnosticText(hosts[index].name)}", "Host check timed out")
                else runCatching { future.get() }.getOrElse { failedCheck("host", "Host health", "Host check failed") }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun check(
        id: String,
        title: String,
        timeoutSeconds: Long = LOCAL_TIMEOUT_SECONDS,
        action: () -> Pair<String, String>
    ): AgentFleetDiagnosticCheck {
        val started = System.currentTimeMillis()
        return try {
            val (summary, detail) = withTimeout(timeoutSeconds, action)
            AgentFleetDiagnosticCheck(id, title, DiagnosticStatus.Healthy, safeDiagnosticText(summary), safeDiagnosticText(detail), System.currentTimeMillis() - started)
        } catch (attention: DiagnosticAttention) {
            AgentFleetDiagnosticCheck(id, title, DiagnosticStatus.Attention, safeDiagnosticText(attention.message.orEmpty()), durationMs = System.currentTimeMillis() - started)
        } catch (error: Exception) {
            failedCheck(id, title, safeDiagnosticText(error.message.orEmpty()), System.currentTimeMillis() - started)
        }
    }

    private fun <T> withTimeout(seconds: Long, action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { action() }.get(seconds, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    private fun executable(name: String): File? = sequenceOf(File(home, ".local/bin/$name"), File(prefix, "bin/$name"))
        .firstOrNull { it.isFile && it.canExecute() }

    private fun configureEnvironment(environment: MutableMap<String, String>) {
        environment["HOME"] = home.absolutePath
        environment["PREFIX"] = prefix.absolutePath
        environment["PATH"] = listOf(File(home, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    companion object {
        private const val LOCAL_TIMEOUT_SECONDS = 5L
        private const val REMOTE_TIMEOUT_SECONDS = 20L
        private const val WHOLE_TIMEOUT_MS = 45_000L
        private const val MAX_DOCTOR_HOSTS = 8
        private const val EXPORT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

private class DiagnosticAttention(message: String) : Exception(message)

internal fun safeDiagnosticText(input: String): String {
    var value = input.filterNot(Char::isISOControl).trim()
    value = value.replace(Regex("wtmux://pair\\?[^\\s]+", RegexOption.IGNORE_CASE), "[invitation]")
    value = value.replace(Regex("(?i)(authorization|password|passwd|secret|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "${'$'}1=[redacted]")
    value = value.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}"), "Bearer [redacted]")
    value = value.replace(Regex("(?:/data/|/home/|/mnt/|/storage/|[A-Za-z]:[\\\\/])[^\\s,;]+"), "[path]")
    value = value.replace(Regex("(?<![A-Za-z0-9])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9])"), "[redacted]")
    return value.take(240)
}

private fun safeDiagnosticToken(input: String, fallback: String): String = input.lowercase(Locale.US)
    .replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(64).ifBlank { fallback }

private fun safeDiagnosticIdentifier(input: String): String {
    if (input.isBlank()) return ""
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return "id-" + digest.take(8).joinToString("") { "%02x".format(it) }
}

private fun DiagnosticStatus.wire(): String = name.lowercase(Locale.US)
private fun DiagnosticStatus.label(): String = name.replaceFirstChar { it.uppercase() }
private fun DiagnosticStatus.symbol(): String = when (this) {
    DiagnosticStatus.Healthy -> "✓"
    DiagnosticStatus.Attention -> "!"
    DiagnosticStatus.Failure -> "×"
}
private fun String.toDiagnosticStatus(): DiagnosticStatus = when (this) {
    "healthy" -> DiagnosticStatus.Healthy
    "attention" -> DiagnosticStatus.Attention
    else -> DiagnosticStatus.Failure
}

private fun AgentFleetDiagnosticCheck.toJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("status", status.wire()).put("summary", summary)
    .put("detail", detail).put("durationMs", durationMs)

private fun AgentFleetDiagnosticEvent.toJson(): JSONObject = JSONObject()
    .put("id", id).put("occurredAt", occurredAt).put("epochMs", epochMs).put("operation", operation)
    .put("status", status).put("durationMs", durationMs).put("code", code).put("message", message)
    .apply { hostId?.let { put("hostId", it) }; sessionId?.let { put("sessionId", it) } }

private fun eventFromJson(value: JSONObject): AgentFleetDiagnosticEvent = AgentFleetDiagnosticEvent(
    value.getString("id"), value.getString("occurredAt"), value.getLong("epochMs"), value.getString("operation"),
    value.getString("status"), value.getLong("durationMs"), value.optString("code"), value.optString("message"),
    value.optString("hostId").takeIf(String::isNotBlank), value.optString("sessionId").takeIf(String::isNotBlank)
)

private fun failedCheck(id: String, title: String, summary: String, durationMs: Long = 0) = AgentFleetDiagnosticCheck(
    id, title, DiagnosticStatus.Failure, summary.ifBlank { "Check failed" }, durationMs = durationMs
)

internal fun diagnosticIsoUtc(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(epochMs))

const val DIAGNOSTICS_SCHEMA = "agent-fleet-diagnostics-v1"
