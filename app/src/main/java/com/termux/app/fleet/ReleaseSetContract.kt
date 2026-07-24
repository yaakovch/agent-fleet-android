package com.termux.app.fleet

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class ReleaseProtocolRange(val minimum: Int, val maximum: Int)

data class ReleaseComponent(
    val sequence: Long,
    val version: String,
    val compatibility: Map<String, ReleaseSequenceRange>
)

data class ReleaseSequenceRange(val minimum: Long, val maximum: Long)
data class ProviderUnitVersion(val sequence: Long, val version: String)
data class ProviderAdapterVersion(
    val parser: ProviderUnitVersion,
    val actions: ProviderUnitVersion
)

data class ReleaseArtifact(
    val id: String,
    val component: String,
    val componentSequence: Long,
    val version: String,
    val platform: String,
    val architecture: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val sourceRepository: String,
    val sourceCommit: String,
    val contractPackageVersion: String,
    val sbomSha256: String,
    val licenseSha256: String
)

data class ReleaseRollbackFloor(
    val releaseSetSequence: Long,
    val componentSequences: Map<String, Long>
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
    val providerAdapterVersions: Map<String, ProviderAdapterVersion>,
    val artifacts: List<ReleaseArtifact>,
    val rollbackFloor: ReleaseRollbackFloor,
    val signature: ReleaseSignature
)

object ReleaseSetContract {
    private const val SCHEMA_VERSION = 1
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}$")
    private val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val TOKEN = Regex("^[a-z][a-z0-9._-]{0,95}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val COMMIT = Regex("^[a-f0-9]{40}$")
    private val KEY_ID = Regex("^[a-f0-9]{32}$")
    private val SIGNATURE = Regex("^[A-Za-z0-9_-]{64,128}$")
    private val COMPONENTS = listOf(
        "windowsApp", "androidApp", "clientRuntime", "hostRuntime", "providerAdapters", "contracts"
    )
    private val PROVIDERS = listOf("codex", "claude", "copilot", "shell")
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
            "protocols", "components", "providerAdapterVersions", "artifacts", "rollbackFloor", "signature"
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
                it.requireExactFields("sequence", "version", "compatibility")
            }
            val compatibilityObject = value.requiredObject("compatibility").also {
                it.requireAllowedFields(*COMPONENTS.toTypedArray())
            }
            val compatibility = compatibilityObject.keys().asSequence().associateWith { dependency ->
                require(dependency != id) { "Invalid release set: $id cannot depend on itself" }
                val accepted = compatibilityObject.requiredObject(dependency).also {
                    it.requireExactFields("minimum", "maximum")
                }
                val minimum = accepted.requiredLong("minimum", 1, MAX_SAFE_INTEGER)
                val maximum = accepted.requiredLong("maximum", 1, MAX_SAFE_INTEGER)
                require(minimum <= maximum) { "Invalid release set: compatibility range is inverted" }
                ReleaseSequenceRange(minimum, maximum)
            }
            ReleaseComponent(
                sequence = value.requiredLong("sequence", 1, MAX_SAFE_INTEGER),
                version = value.requiredPattern("version", VERSION),
                compatibility = compatibility
            )
        }
        components.forEach { (id, component) ->
            component.compatibility.forEach { (dependency, accepted) ->
                val selected = components.getValue(dependency).sequence
                require(selected in accepted.minimum..accepted.maximum) {
                    "Invalid release set: $id is incompatible with $dependency"
                }
            }
        }
        val providerObject = root.requiredObject("providerAdapterVersions").also {
            it.requireExactFields(*PROVIDERS.toTypedArray())
        }
        val providerAdapterVersions = PROVIDERS.associateWith { provider ->
            val adapter = providerObject.requiredObject(provider).also {
                it.requireExactFields("parser", "actions")
            }
            fun unit(name: String): ProviderUnitVersion {
                val value = adapter.requiredObject(name).also {
                    it.requireExactFields("sequence", "version")
                }
                return ProviderUnitVersion(
                    sequence = value.requiredLong("sequence", 1, MAX_SAFE_INTEGER),
                    version = value.requiredPattern("version", SEMVER)
                )
            }
            ProviderAdapterVersion(parser = unit("parser"), actions = unit("actions"))
        }

        val artifactsArray = root.requiredArray("artifacts")
        require(artifactsArray.length() <= 64) { "Invalid release set: artifacts are invalid" }
        val artifactIds = mutableSetOf<String>()
        val artifacts = artifactsArray.mapObjects { value ->
            value.requireExactFields(
                "id", "component", "componentSequence", "version", "platform", "architecture", "url",
                "sha256", "size", "sourceRepository", "sourceCommit", "contractPackageVersion",
                "sbomSha256", "licenseSha256"
            )
            val id = value.requiredPattern("id", TOKEN)
            require(artifactIds.add(id)) { "Invalid release set: artifact id is duplicated" }
            val component = value.requiredMember("component", COMPONENTS.toSet())
            val platform = value.requiredMember("platform", PLATFORMS)
            val architecture = value.requiredMember("architecture", ARCHITECTURES)
            val componentSequence = value.requiredLong("componentSequence", 1, MAX_SAFE_INTEGER)
            val version = value.requiredPattern("version", VERSION)
            val artifactContractVersion = value.requiredPattern("contractPackageVersion", VERSION)
            require(componentSequence == components.getValue(component).sequence &&
                version == components.getValue(component).version
            ) { "Invalid release set: artifact component identity does not match" }
            require(artifactContractVersion == contractPackageVersion) {
                "Invalid release set: artifact contract package version does not match"
            }
            ReleaseArtifact(
                id = id,
                component = component,
                componentSequence = componentSequence,
                version = version,
                platform = platform,
                architecture = architecture,
                url = value.requiredHttpsUrl("url"),
                sha256 = value.requiredPattern("sha256", SHA256),
                size = value.requiredLong("size", 1, 2L * 1024 * 1024 * 1024),
                sourceRepository = value.requiredHttpsUrl("sourceRepository"),
                sourceCommit = value.requiredPattern("sourceCommit", COMMIT),
                contractPackageVersion = artifactContractVersion,
                sbomSha256 = value.requiredPattern("sbomSha256", SHA256),
                licenseSha256 = value.requiredPattern("licenseSha256", SHA256)
            )
        }

        val floor = root.requiredObject("rollbackFloor").also {
            it.requireExactFields("releaseSetSequence", "componentSequences")
        }
        val floorSequences = floor.requiredObject("componentSequences").also {
            it.requireExactFields(*COMPONENTS.toTypedArray())
        }
        val rollbackFloor = ReleaseRollbackFloor(
            releaseSetSequence = floor.requiredLong("releaseSetSequence", 0, releaseSetSequence),
            componentSequences = COMPONENTS.associateWith { id ->
                floorSequences.requiredLong(id, 0, components.getValue(id).sequence)
            }
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
            providerAdapterVersions = providerAdapterVersions,
            artifacts = artifacts,
            rollbackFloor = rollbackFloor,
            signature = signature
        )
    }

    fun verify(
        json: String,
        trustedKeys: Map<String, ByteArray>,
        now: Instant,
        allowedOrigins: Set<String>,
        installedAndroidVersion: String,
        minimumReleaseSetSequence: Long = 0,
        componentFloors: Map<String, Long> = emptyMap()
    ): AgentFleetReleaseSet {
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid release set: release set is not valid JSON", error)
        }
        val releaseSet = parse(root)
        require(now >= Instant.parse(releaseSet.issuedAt)) { "release_set_not_yet_valid" }
        require(now < Instant.parse(releaseSet.expiresAt)) { "release_set_expired" }
        require(releaseSet.releaseSetSequence >=
            maxOf(minimumReleaseSetSequence, releaseSet.rollbackFloor.releaseSetSequence)
        ) { "release_set_downgrade" }
        require(releaseSet.components.getValue("androidApp").version == installedAndroidVersion) {
            "release_set_incompatible: Android app version is outside the selected set"
        }
        COMPONENTS.forEach { name ->
            val selected = releaseSet.components.getValue(name).sequence
            val floor = maxOf(
                componentFloors[name] ?: 0,
                releaseSet.rollbackFloor.componentSequences.getValue(name)
            )
            require(selected >= floor) { "release_set_component_downgrade: $name" }
        }
        releaseSet.artifacts.forEach { artifact ->
            require(ClientPolicyParser.canonicalOrigin(artifact.url) in allowedOrigins) {
                "Release-set artifact origin is not approved"
            }
            require(ClientPolicyParser.canonicalOrigin(artifact.sourceRepository) in allowedOrigins) {
                "Release-set source origin is not approved"
            }
        }
        val key = trustedKeys[releaseSet.signature.keyId]
            ?: throw IllegalArgumentException("Release set uses an unknown signing key")
        val signatureBytes = decodeSignature(releaseSet.signature.value)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(canonicalSignaturePayload(root))
        require(verifier.verify(signatureBytes)) { "Release-set signature verification failed" }
        return releaseSet
    }

    internal fun canonicalSignaturePayload(root: JSONObject): ByteArray {
        val signable = JSONObject(root.toString())
        val signature = signable.getJSONObject("signature")
        signature.remove("value")
        return canonicalJson(signable).toByteArray(Charsets.UTF_8)
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]", separator = ","
        ) { index -> canonicalJson(value.get(index)) }
        is String -> JSONObject.quote(value).replace("\\/", "/")
        is Number, is Boolean -> value.toString()
        JSONObject.NULL, null -> "null"
        else -> throw IllegalArgumentException("Invalid release set: canonical JSON value is unsupported")
    }

    private fun decodeSignature(value: String): ByteArray {
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        require(decoded.size == 64 &&
            Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) == value
        ) { "Release-set signature is not canonical" }
        return decoded
    }

    private fun JSONObject.requireExactFields(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet()) { "Invalid release set: object fields are invalid" }
    }

    private fun JSONObject.requireAllowedFields(vararg allowed: String) {
        require(keys().asSequence().all { it in allowed }) {
            "Invalid release set: object fields are invalid"
        }
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
