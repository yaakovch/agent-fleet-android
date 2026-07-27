package com.termux.app.fleet

import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class LayeredDiagnosticCheck(
    val id: String,
    val layer: String,
    val label: String,
    val status: String,
    val severity: String,
    val errorCode: String,
    val durationMs: Long,
    val version: String,
    val recoveryAction: String,
    val summary: String
)

data class LegacyUsageInput(
    val successfulReleaseCycles: Int,
    val registeredHosts: Int,
    val verifiedHosts: Int,
    val registeredClients: Int,
    val verifiedClients: Int,
    val syntheticWindowsIdentities: Int,
    val ambientRuntimeResolutions: Int,
    val androidOneShotControlStarts: Int,
    val legacyConfigFields: Int
)

data class LegacyUsage(
    val successfulReleaseCycles: Int,
    val registeredHosts: Int,
    val verifiedHosts: Int,
    val registeredClients: Int,
    val verifiedClients: Int,
    val syntheticWindowsIdentities: Int,
    val ambientRuntimeResolutions: Int,
    val androidOneShotControlStarts: Int,
    val legacyConfigFields: Int,
    val removalEligible: Boolean,
    val blockers: List<String>
) {
    companion object {
        fun blockedDefault() = createLegacyUsage(LegacyUsageInput(
            successfulReleaseCycles = 0,
            registeredHosts = 0,
            verifiedHosts = 0,
            registeredClients = 1,
            verifiedClients = 1,
            syntheticWindowsIdentities = 0,
            ambientRuntimeResolutions = 0,
            androidOneShotControlStarts = 0,
            legacyConfigFields = 0
        ))
    }
}

object LayeredDiagnostics {
    val layers = listOf(
        "client_app", "platform_adapter", "client_runtime", "tailnet", "ssh",
        "host_runtime", "tmux", "endpoint", "execution_target", "provider_adapter",
        "update_channel"
    )
    private val labels = mapOf(
        "client_app" to "Client app",
        "platform_adapter" to "Platform adapter",
        "client_runtime" to "Client runtime",
        "tailnet" to "Private network",
        "ssh" to "Secure Shell",
        "host_runtime" to "Host runtime",
        "tmux" to "Session service",
        "endpoint" to "Host endpoint",
        "execution_target" to "Execution target",
        "provider_adapter" to "Native provider",
        "update_channel" to "Update channel"
    )
    private val recovery = mapOf(
        "client_app" to "none",
        "platform_adapter" to "copy_redacted_report",
        "client_runtime" to "repair_client_runtime",
        "tailnet" to "open_tailscale",
        "ssh" to "review_host_key",
        "host_runtime" to "retry",
        "tmux" to "retry",
        "endpoint" to "retry",
        "execution_target" to "retry",
        "provider_adapter" to "open_terminal",
        "update_channel" to "rollback_runtime"
    )

    fun checks(report: AgentFleetDiagnosticReport): List<LayeredDiagnosticCheck> {
        val source = report.checks.associateBy(AgentFleetDiagnosticCheck::id)
        val host = report.checks.filter { it.id.startsWith("host-") }
        return listOf(
            healthy("client_app", report.appVersion, "Client metadata is readable", source["app"]?.durationMs ?: 0),
            fromSource("platform_adapter", source["downloads"], report.androidVersion, "Platform integration"),
            fromSource("client_runtime", source["runtime"], report.runtimeVersion, "Verified runtime"),
            fromSource("tailnet", source["fleet"], "external", "Private network path"),
            fromHost("ssh", host, "external", "Secure connection"),
            fromSource("host_runtime", source["compatibility"], CompatibilityContract.CONTRACT_PACKAGE_VERSION, "Host runtime"),
            fromHost("tmux", host, "external", "Session service"),
            fromHost("endpoint", host, "registry", "Selected endpoint"),
            fromSource("execution_target", source["fleet"], "registry", "Execution target metadata"),
            notRun("provider_adapter", "PROVIDER_ADAPTER_NOT_OBSERVED", "release-set", "No provider session was observed"),
            fromSource("update_channel", source["policy"], CompatibilityContract.CONTRACT_PACKAGE_VERSION, "Update policy")
        )
    }

    fun reportJson(report: AgentFleetDiagnosticReport): String {
        val checks = checks(report)
        val root = JSONObject()
            .put("schemaVersion", 2)
            .put("correlationId", report.correlationId)
            .put("generatedAt", report.generatedAt)
            .put("totalDurationMs", checks.sumOf { it.durationMs }.coerceIn(0, 300_000))
            .put("components", JSONArray()
                .put(JSONObject().put("id", "android-app").put("version", safeVersion(report.appVersion)))
                .put(JSONObject().put("id", "client-runtime").put("version", safeVersion(report.runtimeVersion)))
                .put(JSONObject().put("id", "contracts").put("version", CompatibilityContract.CONTRACT_PACKAGE_VERSION)))
            .put("legacyUsage", legacyUsageJson(report.legacyUsage))
            .put("checks", JSONArray(checks.map(::checkJson)))
        requireValid(root)
        return root.toString(2) + "\n"
    }

    fun requireValid(root: JSONObject) {
        root.requireExact(setOf(
            "schemaVersion", "correlationId", "generatedAt", "totalDurationMs",
            "components", "legacyUsage", "checks"
        ))
        require(root.getInt("schemaVersion") == 2)
        require(root.getString("correlationId").matches(Regex("^diag-[a-f0-9]{32}$")))
        require(root.getString("generatedAt").length <= 40)
        Instant.parse(root.getString("generatedAt"))
        require(root.getLong("totalDurationMs") in 0..300_000)
        val components = root.getJSONArray("components")
        require(components.length() <= 32)
        repeat(components.length()) {
            val value = components.getJSONObject(it)
            value.requireExact(setOf("id", "version"))
            require(value.getString("id").matches(Regex("^[a-z][a-z0-9._-]{0,63}$")))
            requireSafe(value.getString("version"), 128)
        }
        requireValidLegacyUsage(root.getJSONObject("legacyUsage"))
        val checks = root.getJSONArray("checks")
        require(checks.length() in 11..256)
        val seen = mutableSetOf<String>()
        repeat(checks.length()) { index ->
            val value = checks.getJSONObject(index)
            value.requireExact(setOf(
                "id", "layer", "label", "status", "severity", "errorCode", "durationMs",
                "version", "recoveryAction", "summary", "readOnly"
            ))
            val layer = value.getString("layer")
            require(layer in layers && seen.add(layer))
            require(value.getString("id").matches(Regex("^[a-z][a-z0-9._-]{0,63}$")))
            require(value.getString("label") == labels.getValue(layer))
            require(value.getString("status") in setOf("healthy", "attention", "failure", "not-run"))
            require(value.getString("severity") in setOf("info", "warning", "error"))
            require(value.getString("errorCode").matches(Regex("^[A-Z][A-Z0-9_]{0,63}$")))
            require(value.getLong("durationMs") in 0..300_000)
            requireSafe(value.getString("version"), 128)
            require(value.getString("recoveryAction") == recovery.getValue(layer))
            requireSafe(value.getString("summary"), 160)
            require('/' !in value.getString("summary") && '\\' !in value.getString("summary"))
            require(value.getBoolean("readOnly"))
        }
        require(seen == layers.toSet())
        rejectPrivate(root)
    }

    private fun fromSource(layer: String, source: AgentFleetDiagnosticCheck?, version: String, subject: String): LayeredDiagnosticCheck {
        if (source == null) return notRun(layer, "${layer.uppercase()}_NOT_OBSERVED", version, "$subject was not checked")
        return when (source.status) {
            DiagnosticStatus.Healthy -> healthy(layer, version, "$subject is ready", source.durationMs)
            DiagnosticStatus.Attention -> diagnostic(layer, "attention", "${layer.uppercase()}_ATTENTION", version, "$subject needs attention", source.durationMs)
            DiagnosticStatus.Failure -> diagnostic(layer, "failure", "${layer.uppercase()}_UNAVAILABLE", version, "$subject is unavailable", source.durationMs)
        }
    }

    private fun fromHost(layer: String, sources: List<AgentFleetDiagnosticCheck>, version: String, subject: String): LayeredDiagnosticCheck {
        if (sources.isEmpty()) return notRun(layer, "${layer.uppercase()}_NOT_OBSERVED", version, "$subject was not checked")
        val status = when {
            sources.any { it.status == DiagnosticStatus.Failure } -> "failure"
            sources.any { it.status == DiagnosticStatus.Attention } -> "attention"
            else -> "healthy"
        }
        return diagnostic(
            layer, status, when (status) {
                "healthy" -> "OK"
                "attention" -> if (layer == "tmux") "RESOURCE_BUDGET_EXCEEDED" else "${layer.uppercase()}_ATTENTION"
                else -> "${layer.uppercase()}_UNAVAILABLE"
            },
            version, when (status) {
                "healthy" -> "$subject is ready"
                "attention" -> "$subject needs attention"
                else -> "$subject is unavailable"
            },
            sources.sumOf { it.durationMs }.coerceAtMost(300_000)
        )
    }

    private fun healthy(layer: String, version: String, summary: String, duration: Long = 0) =
        diagnostic(layer, "healthy", "OK", version, summary, duration)

    private fun notRun(layer: String, code: String, version: String, summary: String) =
        diagnostic(layer, "not-run", code, version, summary, 0)

    private fun diagnostic(
        layer: String, status: String, code: String, version: String, summary: String, duration: Long
    ) = LayeredDiagnosticCheck(
        layer.replace('_', '-'), layer, labels.getValue(layer), status,
        if (status == "failure") "error" else if (status == "healthy") "info" else "warning",
        code, duration.coerceIn(0, 300_000), safeVersion(version), recovery.getValue(layer),
        safeSummary(summary)
    )

    private fun checkJson(value: LayeredDiagnosticCheck) = JSONObject()
        .put("id", value.id).put("layer", value.layer).put("label", value.label)
        .put("status", value.status).put("severity", value.severity).put("errorCode", value.errorCode)
        .put("durationMs", value.durationMs).put("version", value.version)
        .put("recoveryAction", value.recoveryAction).put("summary", value.summary).put("readOnly", true)

    private fun legacyUsageJson(value: LegacyUsage) = JSONObject()
        .put("successfulReleaseCycles", value.successfulReleaseCycles)
        .put("migrationVerification", JSONObject()
            .put("registeredHosts", value.registeredHosts)
            .put("verifiedHosts", value.verifiedHosts)
            .put("registeredClients", value.registeredClients)
            .put("verifiedClients", value.verifiedClients))
        .put("signals", JSONObject()
            .put("syntheticWindowsIdentities", value.syntheticWindowsIdentities)
            .put("ambientRuntimeResolutions", value.ambientRuntimeResolutions)
            .put("androidOneShotControlStarts", value.androidOneShotControlStarts)
            .put("legacyConfigFields", value.legacyConfigFields))
        .put("removalEligible", value.removalEligible)
        .put("blockers", JSONArray(value.blockers))

    private fun requireValidLegacyUsage(value: JSONObject) {
        value.requireExact(setOf(
            "successfulReleaseCycles", "migrationVerification", "signals", "removalEligible", "blockers"
        ))
        val migration = value.getJSONObject("migrationVerification")
        migration.requireExact(setOf(
            "registeredHosts", "verifiedHosts", "registeredClients", "verifiedClients"
        ))
        val signals = value.getJSONObject("signals")
        signals.requireExact(setOf(
            "syntheticWindowsIdentities", "ambientRuntimeResolutions",
            "androidOneShotControlStarts", "legacyConfigFields"
        ))
        val expected = createLegacyUsage(LegacyUsageInput(
            value.getInt("successfulReleaseCycles"),
            migration.getInt("registeredHosts"),
            migration.getInt("verifiedHosts"),
            migration.getInt("registeredClients"),
            migration.getInt("verifiedClients"),
            signals.getInt("syntheticWindowsIdentities"),
            signals.getInt("ambientRuntimeResolutions"),
            signals.getInt("androidOneShotControlStarts"),
            signals.getInt("legacyConfigFields")
        ))
        require(value.getBoolean("removalEligible") == expected.removalEligible)
        val blockers = value.getJSONArray("blockers")
        require(List(blockers.length()) { blockers.getString(it) } == expected.blockers)
    }

    private fun safeSummary(input: String) = input.filterNot(Char::isISOControl).replace("/", "").replace("\\", "").take(160)
    private fun safeVersion(input: String) = input.filterNot(Char::isISOControl).take(128)
    private fun requireSafe(input: String, maximum: Int) = require(input.length <= maximum && input.none(Char::isISOControl))

    private fun JSONObject.requireExact(fields: Set<String>) {
        require(keys().asSequence().toSet() == fields) { "Layered diagnostic fields are invalid" }
    }

    private fun rejectPrivate(value: Any?) {
        val forbidden = setOf(
            "credential", "invitation", "message", "output", "path", "prompt",
            "response", "terminal", "token", "transcript"
        )
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach {
                require(it.lowercase() !in forbidden) { "Private diagnostic field is forbidden" }
                rejectPrivate(value.opt(it))
            }
            is JSONArray -> repeat(value.length()) { rejectPrivate(value.opt(it)) }
        }
    }

    fun newCorrelationId(): String = "diag-${UUID.randomUUID().toString().replace("-", "")}"
}

fun createLegacyUsage(input: LegacyUsageInput): LegacyUsage {
    val values = listOf(
        input.successfulReleaseCycles, input.registeredHosts, input.verifiedHosts,
        input.registeredClients, input.verifiedClients, input.syntheticWindowsIdentities,
        input.ambientRuntimeResolutions, input.androidOneShotControlStarts, input.legacyConfigFields
    )
    require(values.all { it in 0..1_000_000 })
    require(input.successfulReleaseCycles <= 1024)
    require(input.verifiedHosts <= input.registeredHosts)
    require(input.verifiedClients <= input.registeredClients)
    val blockers = buildList {
        if (input.successfulReleaseCycles < 2) add("release_cycles")
        if (input.registeredHosts < 1 || input.verifiedHosts != input.registeredHosts) add("host_migration")
        if (input.registeredClients < 1 || input.verifiedClients != input.registeredClients) add("client_migration")
        if (listOf(
                input.syntheticWindowsIdentities, input.ambientRuntimeResolutions,
                input.androidOneShotControlStarts, input.legacyConfigFields
            ).any { it > 0 }
        ) add("legacy_usage")
    }
    return LegacyUsage(
        input.successfulReleaseCycles,
        input.registeredHosts,
        input.verifiedHosts,
        input.registeredClients,
        input.verifiedClients,
        input.syntheticWindowsIdentities,
        input.ambientRuntimeResolutions,
        input.androidOneShotControlStarts,
        input.legacyConfigFields,
        blockers.isEmpty(),
        blockers
    )
}
