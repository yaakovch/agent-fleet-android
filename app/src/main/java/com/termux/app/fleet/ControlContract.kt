package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object ControlContract {
    private const val MAX_FRAME_BYTES = 256 * 1024
    private data class Shape(val required: Set<String>, val optional: Set<String> = emptySet())

    private val shapes = mapOf(
        "fleet.snapshot" to Shape(emptySet(), setOf("includeSessionTitles")),
        "protocol.capabilities" to Shape(emptySet()),
        "session.create" to Shape(setOf("hostId", "project", "backend", "tool", "expectedRevision", "idempotencyKey"), setOf("path", "locationKind")),
        "session.kill" to Shape(setOf("hostId", "sessionId", "expectedRevision", "idempotencyKey")),
        "session.rename" to Shape(setOf("hostId", "sessionId", "name", "expectedRevision", "idempotencyKey")),
        "session.name.reset" to Shape(setOf("hostId", "sessionId", "expectedRevision", "idempotencyKey")),
        "schedule.cancel" to Shape(setOf("hostId", "scheduleId", "expectedRevision", "idempotencyKey")),
        "schedule.create" to Shape(setOf("hostId", "sessionId", "deliverAt", "action", "expectedRevision", "idempotencyKey"), setOf("attentionId")),
        "schedule.update" to Shape(setOf("hostId", "scheduleId", "deliverAt", "expectedRevision", "idempotencyKey")),
        "attention.dismiss" to Shape(setOf("hostId", "attentionId", "expectedRevision", "idempotencyKey")),
        "host.doctor" to Shape(setOf("hostId", "expectedRevision", "idempotencyKey")),
        "host.update" to Shape(setOf("hostId", "expectedRevision", "idempotencyKey")),
        "directory.list" to Shape(setOf("hostId", "backend", "path"), setOf("expectedRevision", "idempotencyKey")),
        "directory.create" to Shape(setOf("hostId", "backend", "parentPath", "name", "expectedRevision", "idempotencyKey")),
        "repository.list" to Shape(setOf("hostId", "sessionId", "relativePath", "includeHidden", "cursor"), setOf("expectedRevision", "idempotencyKey")),
        "repository.search" to Shape(setOf("hostId", "sessionId", "query", "includeHidden"), setOf("expectedRevision", "idempotencyKey")),
        "session.model.get" to Shape(setOf("hostId", "sessionId", "includeCatalog")),
        "session.model.set" to Shape(setOf("hostId", "sessionId", "modelId", "effortId", "custom", "expectedConfigRevision", "idempotencyKey", "historyImpactAcknowledged")),
        "session.model.cancel" to Shape(setOf("hostId", "sessionId", "expectedConfigRevision", "idempotencyKey")),
        "preset.upsert" to Shape(setOf("preset", "expectedRevision", "idempotencyKey")),
        "preset.delete" to Shape(setOf("presetId", "expectedRevision", "idempotencyKey")),
        "pairing.invite" to Shape(setOf("expectedRevision", "idempotencyKey")),
        "pairing.review" to Shape(setOf("pairingRequestId", "expectedRevision", "idempotencyKey")),
        "pairing.approve" to Shape(setOf("pairingRequestId", "expectedRevision", "idempotencyKey")),
        "pairing.reject" to Shape(setOf("pairingRequestId", "expectedRevision", "idempotencyKey")),
        "pairing.revoke" to Shape(setOf("invitationId", "expectedRevision", "idempotencyKey"))
    )
    private val id = Regex("^[A-Za-z0-9._:-]{1,160}$")
    private val sessionId = Regex("^[A-Za-z0-9._:-]{1,320}$")
    private val revision = Regex("^[a-f0-9]{16}$")
    private val legacySnapshotRevision = Regex("^[A-Za-z0-9._:-]{1,64}$")
    private val model = Regex("^[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}$")
    private val effort = Regex("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}$")
    private val forbidden = setOf("message", "prompt", "output", "transcript", "panetitle", "command")

    fun requireValidRequest(root: JSONObject) {
        require(root.toString().toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Control request is too large" }
        root.requireFields(setOf("protocolVersion", "type", "requestId", "method", "timestamp", "params"))
        require(root.get("protocolVersion") is Number && root.getInt("protocolVersion") == 1)
        require(root.get("type") == "request")
        require(root.requiredString("requestId").matches(id))
        requireInstant(root.requiredString("timestamp"))
        val method = root.requiredString("method")
        val shape = requireNotNull(shapes[method]) { "Unknown control method" }
        val params = root.getJSONObject("params")
        params.requireFields(shape.required, shape.optional)
        if (method == "session.create") require(params.has("path") == params.has("locationKind"))
        if (method in setOf("directory.list", "repository.list", "repository.search")) {
            require(params.has("expectedRevision") == params.has("idempotencyKey"))
        }
        validateParameters(method, params)
        rejectPrivateFields(root)
    }

    fun methods(): Set<String> = shapes.keys

    private fun validateParameters(method: String, params: JSONObject) {
        params.keys().asSequence().forEach { key ->
            val value = params.opt(key)
            when (key) {
                "hostId", "scheduleId", "attentionId", "presetId", "pairingRequestId", "invitationId", "idempotencyKey" ->
                    require(value is String && value.matches(id)) { "$key is invalid" }
                "sessionId" -> require(value is String && value.matches(sessionId)) { "$key is invalid" }
                "expectedRevision" -> require(value is String && value.matches(legacySnapshotRevision)) { "$key is invalid" }
                "expectedConfigRevision" -> require(value is String && value.matches(revision)) { "$key is invalid" }
            }
        }
        if (params.has("backend")) require(params.requiredString("backend") in setOf("linux", "windows"))
        if (params.has("tool")) require(params.requiredString("tool") in setOf("shell", "codex", "claude", "copilot"))
        if (params.has("locationKind")) require(params.requiredString("locationKind") in setOf("project", "custom"))
        if (params.has("action")) require(params.get("action") == "continue")
        listOf("includeSessionTitles", "includeHidden", "includeCatalog", "custom", "historyImpactAcknowledged").forEach { key ->
            if (params.has(key)) require(params.get(key) is Boolean) { "$key is invalid" }
        }
        if (params.has("deliverAt")) requireInstant(params.requiredString("deliverAt"))
        listOf("path", "parentPath", "relativePath", "cursor").forEach { key ->
            if (params.has(key)) requireSafeText(params.requiredString(key), 2048)
        }
        if (params.has("query")) {
            requireSafeText(params.requiredString("query"), 2048)
            require(params.getString("query").trim().length >= 2)
        }
        if (params.has("project")) require(params.requiredString("project").matches(Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$")))
        if (params.has("name")) requireSafeText(params.requiredString("name"), if (method == "session.rename") 64 else 127, empty = false)
        if (params.has("modelId")) require(params.requiredString("modelId").matches(model))
        if (params.has("effortId")) require(params.requiredString("effortId").matches(effort))
        if (params.has("preset")) validatePreset(params.getJSONObject("preset"))
    }

    private fun validatePreset(value: JSONObject) {
        value.requireFields(setOf("id", "name", "hostId", "project", "backend", "tool", "profileAlias"))
        require(value.requiredString("id").matches(id) && value.requiredString("hostId").matches(id))
        requireSafeText(value.requiredString("name"), 128, empty = false)
        requireSafeText(value.requiredString("project"), 128, empty = false)
        require(value.requiredString("backend") in setOf("linux", "windows"))
        require(value.requiredString("tool") in setOf("shell", "codex", "claude", "copilot"))
        requireSafeText(value.requiredString("profileAlias"), 64)
    }

    private fun JSONObject.requireFields(required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = keys().asSequence().toSet()
        require(required.all(actual::contains) && actual.all { it in required || it in optional }) { "Control fields are invalid" }
    }

    private fun JSONObject.requiredString(name: String): String = get(name).also { require(it is String) } as String

    private fun requireSafeText(value: String, maximum: Int, empty: Boolean = true) {
        require(value.length <= maximum && (empty || value.isNotEmpty()) && value.none { it.isISOControl() })
    }

    private fun requireInstant(value: String) {
        require(value.length <= 40)
        Instant.parse(value)
    }

    private fun rejectPrivateFields(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach { key ->
                require(key.lowercase() !in forbidden) { "Private control field is forbidden" }
                rejectPrivateFields(value.opt(key))
            }
            is JSONArray -> (0 until value.length()).forEach { rejectPrivateFields(value.opt(it)) }
        }
    }
}
