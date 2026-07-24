package com.termux.app.fleet

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class FleetHostTrust(
    val physicalHostId: String,
    val endpointId: String,
    val identityState: String,
    val sshHostKeySha256: String,
    val tailscaleNodeId: String
)

data class FleetConfigurationBundle(
    val bundleId: String,
    val fleetId: String,
    val configurationRevision: Long,
    val createdAt: String,
    val registryCount: Int,
    val clientPolicy: ClientPolicy,
    val hostTrust: List<FleetHostTrust>,
    val contractPackageVersion: String,
    val controlVersions: List<Int>,
    val conversationVersions: List<Int>,
    val minimumReleaseSetSequence: Long,
    val digest: String,
    val json: String
)

data class FleetPairingInvitationReview(
    val bootstrapPeer: String,
    val bootstrapUser: String,
    val expiresAt: String,
    val expired: Boolean,
    val integrity: String = "one-time-secret"
)

object FleetConfigurationParser {
    private const val MAX_BUNDLE_BYTES = 4 * 1024 * 1024
    private val ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val HOST = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val HOST_KEY = Regex("^(|SHA256:[A-Za-z0-9+/]{20,88})$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val forbidden = setOf(
        "attachment", "credential", "devicepreference", "discoveredhealth", "invitation",
        "message", "output", "panetitle", "prompt", "response", "secret", "terminal",
        "token", "transcript"
    )

    fun parse(input: String): FleetConfigurationBundle {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BUNDLE_BYTES) { "Fleet configuration exceeds 4 MiB" }
        val root = JSONObject(input)
        root.requireExact(setOf(
            "schemaVersion", "bundleId", "fleetId", "configurationRevision", "createdAt",
            "registry", "clientPolicy", "hostTrust", "compatibility", "integrity"
        ))
        require(root.getInt("schemaVersion") == 1)
        val bundleId = root.requireId("bundleId")
        val fleetId = root.requireId("fleetId")
        val revision = root.getLong("configurationRevision").also { require(it > 0) }
        val createdAt = root.getString("createdAt").also { require(it.length <= 40); Instant.parse(it) }
        val registry = root.getJSONArray("registry")
        require(registry.length() in 1..256)
        val machineIds = mutableSetOf<String>()
        val knownEndpoints = mutableSetOf<String>()
        repeat(registry.length()) { index ->
            val record = registry.getJSONObject(index)
            val version = record.getInt("schemaVersion")
            val base = setOf(
                "schemaVersion", "id", "name", "roles", "platform", "linuxUsername",
                "tailscaleNode", "projectsRoot", "transport", "wslDistro", "fallback", "hostCommand"
            )
            val identity = setOf("fleetId", "physicalHostId", "aliases", "endpoints", "executionTargets")
            record.requireExact(if (version == 2) base + identity else base)
            require(version in 1..2)
            val machineId = record.requireId("id")
            require(machineIds.add(machineId))
            val name = record.getString("name")
            require(name.isNotBlank() && name.length <= 128 && name.none(Char::isISOControl))
            if (version == 2) {
                require(record.getString("fleetId") == fleetId)
                val physicalHostId = record.requireId("physicalHostId")
                val endpoints = record.getJSONArray("endpoints")
                repeat(endpoints.length()) { endpointIndex ->
                    val endpoint = endpoints.getJSONObject(endpointIndex)
                    if (endpoint.has("id")) knownEndpoints += "$physicalHostId:${endpoint.getString("id")}"
                }
            }
        }
        val policyJson = root.getJSONObject("clientPolicy")
        val policy = ClientPolicyParser.parse(policyJson.toString())
        val trust = parseTrust(root.getJSONArray("hostTrust"), knownEndpoints)
        val compatibility = root.getJSONObject("compatibility").also {
            it.requireExact(setOf(
                "contractPackageVersion", "controlVersions", "conversationVersions", "minimumReleaseSetSequence"
            ))
        }
        val contractVersion = compatibility.getString("contractPackageVersion").also { require(VERSION.matches(it)) }
        val controlVersions = compatibility.requireVersions("controlVersions")
        val conversationVersions = compatibility.requireVersions("conversationVersions")
        val minimumSequence = compatibility.getLong("minimumReleaseSetSequence").also { require(it >= 0) }
        val integrity = root.getJSONObject("integrity").also { it.requireExact(setOf("algorithm", "digest")) }
        require(integrity.getString("algorithm") == "sha256")
        val digest = integrity.getString("digest").also { require(SHA256.matches(it)) }
        rejectForbidden(root)
        val payload = JSONObject(root.toString()).apply { remove("integrity") }
        val actual = MessageDigest.getInstance("SHA-256").digest((canonical(payload) + "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(actual == digest) { "Fleet configuration integrity check failed" }
        return FleetConfigurationBundle(
            bundleId, fleetId, revision, createdAt, registry.length(), policy, trust,
            contractVersion, controlVersions, conversationVersions, minimumSequence, digest,
            root.toString(2) + "\n"
        )
    }

    fun reviewInvitation(value: String, now: Instant = Instant.now()): FleetPairingInvitationReview {
        require(value.length <= 4096 && value.none(Char::isISOControl)) { "Pairing invitation is invalid" }
        val uri = URI(value)
        require(uri.scheme == "wtmux" && uri.host == "pair" && (uri.path.isNullOrEmpty() || uri.path == "/"))
        val values = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.forEach { part ->
            val pieces = part.split("=", limit = 2)
            require(pieces.size == 2)
            val key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.name())
            val item = URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.name())
            require(values.put(key, item) == null) { "Duplicate pairing invitation field" }
        }
        require(values.keys in setOf(
            setOf("pairingVersion", "bootstrapPeer", "token", "expiresAt"),
            setOf("pairingVersion", "bootstrapPeer", "bootstrapUser", "token", "expiresAt")
        ))
        val peer = values.getValue("bootstrapPeer")
        val user = values["bootstrapUser"].orEmpty()
        val token = values.getValue("token")
        val expiresAt = values.getValue("expiresAt")
        require(values["pairingVersion"] == "1" && HOST.matches(peer))
        require(user.isEmpty() || user.matches(Regex("^[a-z_][a-z0-9_-]{0,63}$")))
        require(token.matches(Regex("^(?:[A-Za-z0-9_-]{22}|p[A-Za-z0-9_-]{22})$")))
        val expiry = Instant.parse(expiresAt)
        return FleetPairingInvitationReview(peer.lowercase(), user, expiresAt, !expiry.isAfter(now))
    }

    private fun parseTrust(value: JSONArray, known: Set<String>): List<FleetHostTrust> {
        require(value.length() <= 4096)
        val seen = mutableSetOf<String>()
        return List(value.length()) { index ->
            val item = value.getJSONObject(index).also {
                it.requireExact(setOf(
                    "physicalHostId", "endpointId", "identityState", "sshHostKeySha256", "tailscaleNodeId"
                ))
            }
            val host = item.requireId("physicalHostId")
            val endpoint = item.requireId("endpointId")
            val key = "$host:$endpoint"
            val state = item.getString("identityState")
            val hostKey = item.getString("sshHostKeySha256")
            val node = item.getString("tailscaleNodeId")
            require(seen.add(key) && (known.isEmpty() || key in known))
            require(state in setOf("verified", "unverified", "reverify-required"))
            require(HOST_KEY.matches(hostKey) && node.length <= 128 && node.none(Char::isISOControl))
            require(state != "verified" || hostKey.isNotEmpty() || node.isNotEmpty())
            FleetHostTrust(host, endpoint, state, hostKey, node)
        }
    }

    private fun JSONObject.requireVersions(name: String): List<Int> {
        val array = getJSONArray(name)
        require(array.length() in 1..8)
        return List(array.length()) { array.getInt(it) }.also {
            require(it.toSet().size == it.size && it.all { version -> version in 1..1024 })
        }
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported JSON value")
    }

    private fun quote(value: String): String = JSONObject.quote(value).replace("\\/", "/")

    private fun rejectForbidden(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach { key ->
                require(key.lowercase().replace(Regex("[^a-z]"), "") !in forbidden) {
                    "Forbidden fleet configuration field: $key"
                }
                rejectForbidden(value.opt(key))
            }
            is JSONArray -> repeat(value.length()) { rejectForbidden(value.opt(it)) }
        }
    }

    private fun JSONObject.requireExact(fields: Set<String>) {
        require(keys().asSequence().toSet() == fields) { "Fleet configuration fields are invalid" }
    }

    private fun JSONObject.requireId(name: String): String = getString(name).also { require(ID.matches(it)) }
}

class FleetConfigurationStore(context: Context) {
    private val root = File(context.filesDir, "fleet-configuration")
    private val current = File(root, "current.json")
    private val previous = File(root, "previous.json")

    fun current(): FleetConfigurationBundle? = current.takeIf(File::isFile)?.let {
        FleetConfigurationParser.parse(it.readText(Charsets.UTF_8))
    }

    fun previous(): FleetConfigurationBundle? = previous.takeIf(File::isFile)?.let {
        FleetConfigurationParser.parse(it.readText(Charsets.UTF_8))
    }

    fun review(content: String): FleetConfigurationBundle = FleetConfigurationParser.parse(content)

    fun activate(content: String): FleetConfigurationBundle {
        val candidate = FleetConfigurationParser.parse(content)
        val active = current()
        require(active == null || candidate.configurationRevision >= active.configurationRevision) {
            "Fleet configuration is older than the last healthy revision"
        }
        if (active != null && candidate.configurationRevision == active.configurationRevision) {
            require(candidate.digest == active.digest) { "Fleet configuration revision was reused with different content" }
            return active
        }
        root.mkdirs()
        if (active != null) atomicWrite(previous, active.json)
        atomicWrite(current, candidate.json)
        return candidate
    }

    fun rollback(): FleetConfigurationBundle {
        val old = requireNotNull(previous()) { "No previous healthy fleet configuration is available" }
        val active = requireNotNull(current()) { "No active fleet configuration is available" }
        atomicWrite(previous, active.json)
        atomicWrite(current, old.json)
        return old
    }

    fun export(): String = requireNotNull(current()) { "No active fleet configuration is available" }.json

    private fun atomicWrite(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temporary).use {
            it.write(content.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        check(temporary.renameTo(destination)) { "Unable to activate fleet configuration" }
    }
}
