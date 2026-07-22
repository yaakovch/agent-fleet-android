package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class CompatibilityComponent(
    val sourceVersion: String,
    val controlVersions: List<Int>,
    val conversationVersions: List<Int>,
    val workspaceLayoutVersions: List<Int>
)

data class CompatibilityMatrix(
    val contractPackageVersion: String,
    val components: Map<String, CompatibilityComponent>
)

object CompatibilityContract {
    const val CONTRACT_PACKAGE_VERSION = "1.0.0"
    val CONTROL_VERSIONS = listOf(1)
    val CONVERSATION_VERSIONS = listOf(2)
    val WORKSPACE_LAYOUT_VERSIONS = listOf(1)
    val CAPABILITIES = listOf("control.v1", "conversation.v2", "workspace-layout.v1")
    private const val MAX_DOCUMENT_BYTES = 64 * 1024
    private val componentIds = setOf("wtmux", "windowsApp", "androidApp")
    private val privateFields = setOf("message", "prompt", "output", "transcript", "panetitle", "command")
    private val layers = setOf(
        "client_app", "platform_adapter", "client_runtime", "tailnet", "ssh", "host_runtime",
        "tmux", "endpoint", "execution_target", "provider_adapter", "update_channel"
    )
    private val recoveryActions = setOf(
        "none", "automatic", "after_refresh", "after_recovery", "user_action",
        "terminal_fallback", "no_retry", "rollback"
    )

    fun parse(input: String): CompatibilityMatrix {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Compatibility matrix is too large" }
        val root = JSONObject(input)
        root.requireExact(setOf("schemaVersion", "contractPackageVersion", "components"))
        require(root.getInt("schemaVersion") == 1)
        val packageVersion = root.requireVersion("contractPackageVersion")
        val componentsJson = root.getJSONObject("components").also { it.requireExact(componentIds) }
        val components = componentIds.associateWith { id -> parseComponent(componentsJson.getJSONObject(id)) }
        rejectPrivateFields(root)
        return CompatibilityMatrix(packageVersion, components)
    }

    fun supportsCurrentContracts(component: CompatibilityComponent): Boolean =
        component.controlVersions.containsAll(CONTROL_VERSIONS) &&
            component.conversationVersions.containsAll(CONVERSATION_VERSIONS) &&
            component.workspaceLayoutVersions.containsAll(WORKSPACE_LAYOUT_VERSIONS)

    fun diagnosticsJson(componentId: String, version: String, compatibilityStatus: DiagnosticStatus, generatedAt: String): String {
        val status = when (compatibilityStatus) {
            DiagnosticStatus.Healthy -> "healthy"
            DiagnosticStatus.Attention -> "attention"
            DiagnosticStatus.Failure -> "failure"
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("generatedAt", generatedAt)
            .put("components", JSONArray().put(JSONObject()
                .put("id", componentId)
                .put("version", version)
                .put("contractPackageVersion", CONTRACT_PACKAGE_VERSION)
                .put("controlVersions", JSONArray(CONTROL_VERSIONS))
                .put("conversationVersions", JSONArray(CONVERSATION_VERSIONS))
                .put("workspaceLayoutVersions", JSONArray(WORKSPACE_LAYOUT_VERSIONS))
                .put("capabilities", JSONArray(CAPABILITIES))))
            .put("checks", JSONArray().put(JSONObject()
                .put("id", "runtime-compatibility")
                .put("layer", "host_runtime")
                .put("status", status)
                .put("severity", if (status == "failure") "error" else if (status == "attention") "warning" else "info")
                .put("errorCode", if (status == "failure") "HOST_RUNTIME_INCOMPATIBLE" else "OK")
                .put("durationMs", 0)
                .put("recoveryAction", if (status == "failure") "rollback" else if (status == "attention") "after_recovery" else "none")))
        requireValidDiagnostics(report)
        return report.toString(2)
    }

    fun requireValidDiagnostics(root: JSONObject) {
        root.requireExact(setOf("schemaVersion", "generatedAt", "components", "checks"))
        require(root.getInt("schemaVersion") == 1)
        requireInstant(root.getString("generatedAt"))
        val components = root.getJSONArray("components")
        require(components.length() <= 32)
        repeat(components.length()) { validateDiagnosticComponent(components.getJSONObject(it)) }
        val checks = root.getJSONArray("checks")
        require(checks.length() <= 256)
        repeat(checks.length()) { validateDiagnosticCheck(checks.getJSONObject(it)) }
        rejectPrivateFields(root)
    }

    private fun parseComponent(value: JSONObject): CompatibilityComponent {
        value.requireExact(setOf("sourceVersion", "controlVersions", "conversationVersions", "workspaceLayoutVersions"))
        return CompatibilityComponent(
            value.requireVersion("sourceVersion"),
            value.requireVersions("controlVersions"),
            value.requireVersions("conversationVersions"),
            value.requireVersions("workspaceLayoutVersions")
        )
    }

    private fun validateDiagnosticComponent(value: JSONObject) {
        value.requireExact(setOf("id", "version", "contractPackageVersion", "controlVersions", "conversationVersions", "workspaceLayoutVersions", "capabilities"))
        require(value.getString("id").matches(Regex("^[a-z][a-z0-9._-]{0,63}$")))
        requireSafeText(value.getString("version"), 128)
        requireSafeText(value.getString("contractPackageVersion"), 128)
        value.requireVersions("controlVersions")
        value.requireVersions("conversationVersions")
        value.requireVersions("workspaceLayoutVersions")
        val capabilities = value.getJSONArray("capabilities")
        require(capabilities.length() <= 64)
        val seen = mutableSetOf<String>()
        repeat(capabilities.length()) {
            val capability = capabilities.getString(it)
            require(capability.matches(Regex("^[a-z][a-z0-9._-]{0,63}$")) && seen.add(capability))
        }
    }

    private fun validateDiagnosticCheck(value: JSONObject) {
        value.requireExact(setOf("id", "layer", "status", "severity", "errorCode", "durationMs", "recoveryAction"))
        require(value.getString("id").matches(Regex("^[a-z][a-z0-9._-]{0,63}$")))
        require(value.getString("layer") in layers)
        require(value.getString("status") in setOf("healthy", "attention", "failure", "not-run"))
        require(value.getString("severity") in setOf("info", "warning", "error"))
        require(value.getString("errorCode").matches(Regex("^[A-Z][A-Z0-9_]{0,63}$")))
        require(value.getLong("durationMs") in 0L..300_000L)
        require(value.getString("recoveryAction") in recoveryActions)
    }

    private fun JSONObject.requireVersions(name: String): List<Int> {
        val values = getJSONArray(name)
        require(values.length() in 1..8)
        val result = (0 until values.length()).map { values.getInt(it) }
        require(result.toSet().size == result.size && result.all { it in 1..1024 })
        return result
    }

    private fun JSONObject.requireExact(fields: Set<String>) {
        require(keys().asSequence().toSet() == fields) { "Compatibility fields are invalid" }
    }

    private fun JSONObject.requireVersion(name: String): String = getString(name).also {
        require(it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$")))
    }

    private fun requireSafeText(value: String, maximum: Int) {
        require(value.length <= maximum && value.none(Char::isISOControl))
    }

    private fun requireInstant(value: String) {
        require(value.length <= 40)
        Instant.parse(value)
    }

    private fun rejectPrivateFields(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach { key ->
                require(key.lowercase() !in privateFields) { "Private diagnostic field is forbidden" }
                rejectPrivateFields(value.opt(key))
            }
            is JSONArray -> repeat(value.length()) { rejectPrivateFields(value.opt(it)) }
        }
    }
}
