package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

data class ReleaseProtocolRange(val minimum: Int, val maximum: Int)

data class ReleaseComponent(
    val version: String,
    val minimumCompatibleVersion: String,
    val maximumCompatibleVersion: String
)

data class ReleaseArtifact(
    val id: String,
    val component: String,
    val platform: String,
    val architecture: String,
    val url: String,
    val sha256: String,
    val size: Long
)

data class ReleaseRollbackFloor(
    val releaseSetSequence: Long,
    val androidVersionCode: Long,
    val runtimeSequence: Long
)

data class ReleaseSignature(val algorithm: String, val keyId: String, val value: String)

data class AgentFleetReleaseSet(
    val schemaVersion: Int,
    val releaseSetSequence: Long,
    val issuedAt: String,
    val expiresAt: String,
    val contractPackageVersion: String,
    val protocols: Map<String, ReleaseProtocolRange>,
    val components: Map<String, ReleaseComponent>,
    val artifacts: List<ReleaseArtifact>,
    val rollbackFloor: ReleaseRollbackFloor,
    val signature: ReleaseSignature
)

object ReleaseSetContract {
    private const val SCHEMA_VERSION = 1
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}$")
    private val TOKEN = Regex("^[a-z][a-z0-9._-]{0,95}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val KEY_ID = Regex("^[a-f0-9]{32}$")
    private val SIGNATURE = Regex("^[A-Za-z0-9_-]{64,128}$")
    private val COMPONENTS = listOf(
        "windowsApp", "androidApp", "clientRuntime", "hostRuntime", "providerAdapters", "contracts"
    )
    private val PLATFORMS = setOf("windows", "android", "linux", "termux", "any")
    private val ARCHITECTURES = setOf("x86_64", "arm64", "universal", "any")

    fun parse(json: String): AgentFleetReleaseSet {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Invalid release set: release set exceeds its size limit" }
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid release set: release set is not valid JSON", error)
        }
        return parse(root)
    }

    fun parse(root: JSONObject): AgentFleetReleaseSet {
        root.requireExactFields(
            "schemaVersion", "releaseSetSequence", "issuedAt", "expiresAt", "contractPackageVersion",
            "protocols", "components", "artifacts", "rollbackFloor", "signature"
        )
        require(root.requiredLong("schemaVersion", 1, 1) == SCHEMA_VERSION.toLong()) {
            "Invalid release set: schema version is unsupported"
        }
        val releaseSetSequence = root.requiredLong("releaseSetSequence", 1, MAX_SAFE_INTEGER)
        val issuedAt = root.requiredInstant("issuedAt")
        val expiresAt = root.requiredInstant("expiresAt")
        require(Instant.parse(issuedAt).isBefore(Instant.parse(expiresAt))) {
            "Invalid release set: expiry must follow issuance"
        }
        val contractPackageVersion = root.requiredPattern("contractPackageVersion", VERSION)

        val protocolObject = root.requiredObject("protocols").also {
            it.requireExactFields("control", "conversation", "workspaceLayout")
        }
        val protocols = listOf("control", "conversation", "workspaceLayout").associateWith { name ->
            val range = protocolObject.requiredObject(name).also { it.requireExactFields("minimum", "maximum") }
            val minimum = range.requiredLong("minimum", 1, 1024).toInt()
            val maximum = range.requiredLong("maximum", 1, 1024).toInt()
            require(minimum <= maximum) { "Invalid release set: $name range is inverted" }
            ReleaseProtocolRange(minimum, maximum)
        }

        val componentObject = root.requiredObject("components").also {
            it.requireExactFields(*COMPONENTS.toTypedArray())
        }
        val components = COMPONENTS.associateWith { id ->
            val value = componentObject.requiredObject(id).also {
                it.requireExactFields("version", "minimumCompatibleVersion", "maximumCompatibleVersion")
            }
            ReleaseComponent(
                version = value.requiredPattern("version", VERSION),
                minimumCompatibleVersion = value.requiredPattern("minimumCompatibleVersion", VERSION),
                maximumCompatibleVersion = value.requiredPattern("maximumCompatibleVersion", VERSION)
            )
        }

        val artifactsArray = root.requiredArray("artifacts")
        require(artifactsArray.length() <= 64) { "Invalid release set: artifacts are invalid" }
        val artifactIds = mutableSetOf<String>()
        val artifacts = artifactsArray.mapObjects { value ->
            value.requireExactFields("id", "component", "platform", "architecture", "url", "sha256", "size")
            val id = value.requiredPattern("id", TOKEN)
            require(artifactIds.add(id)) { "Invalid release set: artifact id is duplicated" }
            val component = value.requiredMember("component", COMPONENTS.toSet())
            val platform = value.requiredMember("platform", PLATFORMS)
            val architecture = value.requiredMember("architecture", ARCHITECTURES)
            ReleaseArtifact(
                id = id,
                component = component,
                platform = platform,
                architecture = architecture,
                url = value.requiredHttpsUrl("url"),
                sha256 = value.requiredPattern("sha256", SHA256),
                size = value.requiredLong("size", 1, 2L * 1024 * 1024 * 1024)
            )
        }

        val floor = root.requiredObject("rollbackFloor").also {
            it.requireExactFields("releaseSetSequence", "androidVersionCode", "runtimeSequence")
        }
        val rollbackFloor = ReleaseRollbackFloor(
            releaseSetSequence = floor.requiredLong("releaseSetSequence", 0, releaseSetSequence),
            androidVersionCode = floor.requiredLong("androidVersionCode", 0, MAX_SAFE_INTEGER),
            runtimeSequence = floor.requiredLong("runtimeSequence", 0, MAX_SAFE_INTEGER)
        )

        val signatureObject = root.requiredObject("signature").also {
            it.requireExactFields("algorithm", "keyId", "value")
        }
        val algorithm = signatureObject.requiredString("algorithm")
        require(algorithm == "ed25519") { "Invalid release set: signature algorithm is unsupported" }
        val signature = ReleaseSignature(
            algorithm = algorithm,
            keyId = signatureObject.requiredPattern("keyId", KEY_ID),
            value = signatureObject.requiredPattern("value", SIGNATURE)
        )

        return AgentFleetReleaseSet(
            schemaVersion = SCHEMA_VERSION,
            releaseSetSequence = releaseSetSequence,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            contractPackageVersion = contractPackageVersion,
            protocols = protocols,
            components = components,
            artifacts = artifacts,
            rollbackFloor = rollbackFloor,
            signature = signature
        )
    }

    private fun JSONObject.requireExactFields(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet()) { "Invalid release set: object fields are invalid" }
    }

    private fun JSONObject.requiredObject(name: String): JSONObject = try {
        getJSONObject(name)
    } catch (error: JSONException) {
        throw IllegalArgumentException("Invalid release set: $name is invalid", error)
    }

    private fun JSONObject.requiredArray(name: String): JSONArray = try {
        getJSONArray(name)
    } catch (error: JSONException) {
        throw IllegalArgumentException("Invalid release set: $name is invalid", error)
    }

    private fun JSONObject.requiredString(name: String): String {
        val value = try {
            get(name)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid release set: $name is invalid", error)
        }
        require(value is String) { "Invalid release set: $name is invalid" }
        return value
    }

    private fun JSONObject.requiredPattern(name: String, pattern: Regex): String =
        requiredString(name).also { require(pattern.matches(it)) { "Invalid release set: $name is invalid" } }

    private fun JSONObject.requiredMember(name: String, values: Set<String>): String =
        requiredString(name).also { require(it in values) { "Invalid release set: $name is invalid" } }

    private fun JSONObject.requiredLong(name: String, minimum: Long, maximum: Long): Long {
        val raw = try {
            get(name)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid release set: $name is invalid", error)
        }
        require(raw is Number) { "Invalid release set: $name is invalid" }
        val number = raw.toDouble()
        require(number.isFinite() && number % 1.0 == 0.0 && number >= minimum.toDouble() && number <= maximum.toDouble()) {
            "Invalid release set: $name is invalid"
        }
        return number.toLong()
    }

    private fun JSONObject.requiredInstant(name: String): String {
        val value = requiredString(name)
        require(value.length <= 40) { "Invalid release set: $name is invalid" }
        try {
            Instant.parse(value)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Invalid release set: $name is invalid", error)
        }
        return value
    }

    private fun JSONObject.requiredHttpsUrl(name: String): String {
        val value = requiredString(name)
        require(value.length <= 2048) { "Invalid release set: artifact URL is invalid" }
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid release set: artifact URL is invalid", error)
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Invalid release set: artifact URL is invalid"
        }
        return value
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index ->
            val value = try {
                getJSONObject(index)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid release set: artifact is invalid", error)
            }
            transform(value)
        }
}
