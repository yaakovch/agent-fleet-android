package com.termux.app.fleet

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
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
        val normalizedJson = root.toString(2) + "\n"
        require(normalizedJson.toByteArray(Charsets.UTF_8).size <= MAX_BUNDLE_BYTES) {
            "Normalized fleet configuration exceeds 4 MiB"
        }
        return FleetConfigurationBundle(
            bundleId, fleetId, revision, createdAt, registry.length(), policy, trust,
            contractVersion, controlVersions, conversationVersions, minimumSequence, digest,
            normalizedJson
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
    private val transactionCurrent = File(root, ".transaction-current.json")
    private val transactionPrevious = File(root, ".transaction-previous.json")
    private val transactionMarker = File(root, ".transaction.json")
    private val storeLock = File(root, ".store.lock")

    fun current(): FleetConfigurationBundle? = withStoreLock {
        recoverTransaction()
        readBundle(current)
    }

    fun previous(): FleetConfigurationBundle? = withStoreLock {
        recoverTransaction()
        readBundle(previous)
    }

    fun review(content: String): FleetConfigurationBundle = FleetConfigurationParser.parse(content)

    fun activate(content: String): FleetConfigurationBundle {
        val candidate = FleetConfigurationParser.parse(content)
        return withStoreLock {
            recoverTransaction()
            val active = readBundle(current)
            require(active == null || candidate.configurationRevision >= active.configurationRevision) {
                "Fleet configuration is older than the last healthy revision"
            }
            if (active != null && candidate.configurationRevision == active.configurationRevision) {
                require(candidate.digest == active.digest) {
                    "Fleet configuration revision was reused with different content"
                }
                return@withStoreLock active
            }
            commitTransaction(candidate.json, active?.json)
            candidate
        }
    }

    fun rollback(): FleetConfigurationBundle = withStoreLock {
        recoverTransaction()
        val old = requireNotNull(readBundle(previous)) {
            "No previous healthy fleet configuration is available"
        }
        val active = requireNotNull(readBundle(current)) {
            "No active fleet configuration is available"
        }
        commitTransaction(old.json, active.json)
        old
    }

    fun export(): String = withStoreLock {
        recoverTransaction()
        requireNotNull(readBundle(current)) { "No active fleet configuration is available" }.json
    }

    private fun commitTransaction(currentContent: String, previousContent: String?) {
        require(currentContent.toByteArray(Charsets.UTF_8).size <= MAX_CONFIGURATION_BYTES)
        require(previousContent == null || previousContent.toByteArray(Charsets.UTF_8).size <= MAX_CONFIGURATION_BYTES)
        ensureRoot()
        atomicWrite(transactionCurrent, currentContent)
        if (previousContent == null) {
            check(!transactionPrevious.exists() || transactionPrevious.delete()) {
                "Unable to prepare fleet configuration transaction"
            }
        } else {
            atomicWrite(transactionPrevious, previousContent)
        }
        val marker = JSONObject()
            .put("version", TRANSACTION_VERSION)
            .put("currentSha256", sha256(currentContent))
            .put("hasPrevious", previousContent != null)
            .put("previousSha256", previousContent?.let(::sha256) ?: "")
        atomicWrite(transactionMarker, marker.toString() + "\n")
        recoverTransaction()
    }

    private fun recoverTransaction() {
        if (!transactionMarker.isFile) return
        val marker = JSONObject(readBoundedText(transactionMarker, MAX_TRANSACTION_MARKER_BYTES))
        require(marker.keys().asSequence().toSet() == setOf(
            "version", "currentSha256", "hasPrevious", "previousSha256"
        )) { "Fleet configuration transaction is invalid" }
        require(marker.getInt("version") == TRANSACTION_VERSION)
        val currentContent = readBoundedText(transactionCurrent, MAX_CONFIGURATION_BYTES)
        require(sha256(currentContent) == marker.getString("currentSha256")) {
            "Fleet configuration transaction current payload is invalid"
        }
        FleetConfigurationParser.parse(currentContent)
        val hasPrevious = marker.getBoolean("hasPrevious")
        val previousContent = if (hasPrevious) {
            readBoundedText(transactionPrevious, MAX_CONFIGURATION_BYTES).also {
                require(sha256(it) == marker.getString("previousSha256")) {
                    "Fleet configuration transaction previous payload is invalid"
                }
                FleetConfigurationParser.parse(it)
            }
        } else {
            require(marker.getString("previousSha256").isEmpty())
            null
        }

        ensureRoot()
        if (previousContent == null) {
            check(!previous.exists() || previous.delete()) {
                "Unable to recover fleet configuration transaction"
            }
        } else {
            atomicWrite(previous, previousContent)
        }
        atomicWrite(current, currentContent)
        check(transactionMarker.delete()) { "Unable to complete fleet configuration transaction" }
        syncRoot()
        transactionCurrent.delete()
        transactionPrevious.delete()
    }

    private fun readBundle(file: File): FleetConfigurationBundle? =
        file.takeIf(File::isFile)?.let {
            FleetConfigurationParser.parse(readBoundedText(it, MAX_CONFIGURATION_BYTES))
        }

    private fun readBoundedText(file: File, maximumBytes: Int): String {
        require(file.isFile) { "Fleet configuration payload is missing" }
        val declaredLength = file.length()
        require(declaredLength in 0..maximumBytes.toLong()) {
            "Fleet configuration payload exceeds its size limit"
        }
        val output = ByteArrayOutputStream(declaredLength.toInt().coerceAtMost(READ_BUFFER_BYTES))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximumBytes) {
                    "Fleet configuration payload exceeds its size limit"
                }
                output.write(buffer, 0, count)
            }
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
    }

    private inline fun <T> withStoreLock(action: () -> T): T = synchronized(PROCESS_LOCK) {
        ensureRoot()
        RandomAccessFile(storeLock, "rw").channel.use { channel ->
            channel.lock().use {
                action()
            }
        }
    }

    private fun ensureRoot() {
        check((root.isDirectory || root.mkdirs()) && root.isDirectory) {
            "Unable to prepare fleet configuration storage"
        }
    }

    private fun atomicWrite(destination: File, content: String) {
        ensureRoot()
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${android.os.Process.myPid()}.${System.nanoTime()}.tmp"
        )
        try {
            FileOutputStream(temporary).use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            atomicRenameCompat(temporary, destination)
            syncRoot()
        } finally {
            temporary.delete()
        }
    }

    private fun syncRoot() {
        fsyncDirectoryCompat(root)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TRANSACTION_VERSION = 1
        const val MAX_CONFIGURATION_BYTES = 4 * 1024 * 1024
        const val MAX_TRANSACTION_MARKER_BYTES = 16 * 1024
        const val READ_BUFFER_BYTES = 16 * 1024
        val PROCESS_LOCK = Any()
    }
}
