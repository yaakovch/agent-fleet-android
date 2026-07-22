package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject

object ControlResultContract {
    private const val MAX_RESULT_BYTES = 256 * 1024
    private const val MAX_NESTING_DEPTH = 16
    private val privateFields = setOf("message", "prompt", "output", "transcript", "panetitle", "command")

    fun requireValidResult(value: JSONObject) {
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) { "Control result is too large" }
        inspect(value, 0)
        val keys = value.keys().asSequence().toSet()
        when {
            "methods" in keys && "events" in keys -> validateCapabilities(value)
            "revision" in keys && "hosts" in keys -> FleetSnapshotParser.parse(value.toString())
            keys == setOf("backend", "path", "parentPath", "entries", "shortcuts", "truncated") -> validateDirectory(value)
            keys == setOf("rootName", "relativePath", "parentPath", "entries", "nextCursor", "truncated") -> FleetRepositoryProtocol.parsePage(value)
            keys == setOf("sessionId", "configRevision", "tool", "status", "selected", "effective", "pending", "catalog", "detail") -> validateModelControl(value)
            keys == setOf("operationId", "status", "modelControl") -> {
                shape(value, "control-results-v1:#/\$defs/modelMutation")
                validateModelControl(value.getJSONObject("modelControl"))
            }
            keys.containsAll(setOf("operationId", "status", "snapshot")) -> validateMutation(value)
            else -> require(false) { "Unknown control result family" }
        }
    }

    fun parseFixture(input: String): List<JSONObject> {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) { "Control result fixture is too large" }
        val root = JSONObject(input)
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "results") && root.getInt("schemaVersion") == 1)
        val results = root.getJSONArray("results")
        return List(results.length()) { index -> results.getJSONObject(index).also(::requireValidResult) }
    }

    private fun validateCapabilities(value: JSONObject) {
        shape(value, "control-results-v1:#/\$defs/capabilityBase")
        require(value.has("agentVersion") != value.has("bridgeVersion"))
        require(value.getInt("protocolVersion") == 1)
        listOf("controlVersions", "conversationVersions", "workspaceLayoutVersions").forEach { versions(value.getJSONArray(it)) }
        stringArray(value.getJSONArray("methods"), 64)
        stringArray(value.getJSONArray("events"), 16)
    }

    private fun validateDirectory(value: JSONObject) {
        shape(value, "control-results-v1:#/\$defs/directoryListing")
        require(value.getString("backend") in setOf("linux", "windows"))
        val entries = value.getJSONArray("entries").also { require(it.length() <= 1_000) }
        repeat(entries.length()) { shape(entries.getJSONObject(it), "control-results-v1:#/\$defs/directoryEntry") }
        val shortcuts = value.getJSONArray("shortcuts").also { require(it.length() <= 64) }
        repeat(shortcuts.length()) { shape(shortcuts.getJSONObject(it), "control-results-v1:#/\$defs/directoryShortcut") }
        value.getBoolean("truncated")
    }

    private fun validateModelControl(value: JSONObject) {
        shape(value, "control-results-v1:#/\$defs/modelControl")
        shape(value.getJSONObject("selected"), "control-results-v1:#/\$defs/modelSelection")
        if (!value.isNull("effective")) shape(value.getJSONObject("effective"), "control-results-v1:#/\$defs/modelSelection")
        if (!value.isNull("pending")) shape(value.getJSONObject("pending"), "control-results-v1:#/\$defs/pendingModel")
        if (!value.isNull("catalog")) {
            val catalog = value.getJSONObject("catalog")
            shape(catalog, "control-results-v1:#/\$defs/catalog")
            val models = catalog.getJSONArray("models")
            repeat(models.length()) { index ->
                val model = models.getJSONObject(index)
                shape(model, "control-results-v1:#/\$defs/model")
                val efforts = model.getJSONArray("efforts")
                repeat(efforts.length()) { shape(efforts.getJSONObject(it), "control-results-v1:#/\$defs/effort") }
            }
        }
    }

    private fun validateMutation(value: JSONObject) {
        shape(value, "control-results-v1:#/\$defs/mutation")
        require(value.length() <= 4)
        FleetSnapshotParser.parse(value.getJSONObject("snapshot").toString())
        value.optJSONObject("doctor")?.let { doctor ->
            shape(doctor, "control-results-v1:#/\$defs/doctor")
            val checks = doctor.getJSONArray("checks").also { require(it.length() <= 32) }
            repeat(checks.length()) { shape(checks.getJSONObject(it), "control-results-v1:#/\$defs/doctorCheck") }
        }
        value.optJSONObject("invitation")?.let { invitation ->
            shape(invitation, "control-results-v1:#/\$defs/invitation")
            shape(invitation.getJSONObject("file"), "control-results-v1:#/\$defs/invitationFile")
        }
        value.optJSONObject("pairingRequest")?.let { pairing ->
            shape(pairing, "control-results-v1:#/\$defs/pairingRequest")
            val proposal = pairing.getJSONObject("proposal")
            shape(proposal, "control-results-v1:#/\$defs/pairingProposal")
            shape(proposal.getJSONObject("fallback"), "control-results-v1:#/\$defs/pairingProposal/properties/fallback")
        }
    }

    private fun shape(value: JSONObject, id: String) {
        val model = requireNotNull(GeneratedAgentFleetContracts.objectShapes[id]) { "Missing generated result shape $id" }
        val actual = value.keys().asSequence().toSet()
        require(actual.containsAll(model.required) && actual.all { it in model.required || it in model.optional }) { "$id fields are invalid" }
    }

    private fun inspect(value: Any?, depth: Int) {
        require(depth <= MAX_NESTING_DEPTH) { "Control result nesting is too deep" }
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach { key ->
                require(key.lowercase() !in privateFields) { "Private control result field is forbidden" }
                inspect(value.opt(key), depth + 1)
            }
            is JSONArray -> repeat(value.length()) { inspect(value.opt(it), depth + 1) }
        }
    }

    private fun versions(values: JSONArray) {
        require(values.length() in 1..8)
        val items = (0 until values.length()).map(values::getInt)
        require(items.toSet().size == items.size && items.all { it in 1..1024 })
    }

    private fun stringArray(values: JSONArray, maximum: Int) {
        require(values.length() <= maximum)
        val items = (0 until values.length()).map(values::getString)
        require(items.toSet().size == items.size && items.all { it.length in 1..64 })
    }
}
