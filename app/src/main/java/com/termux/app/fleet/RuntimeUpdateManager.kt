package com.termux.app.fleet

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

data class RuntimeUpdate(
    val sequence: Long,
    val version: String,
    val protocolVersion: Int,
    val artifactUrl: String,
    val sha256: String,
    val size: Long,
    val minAppVersionCode: Long,
    val createdAt: String,
    val keyId: String
)

sealed class RuntimeUpdateResult {
    object NoPolicy : RuntimeUpdateResult()
    data class Current(val source: String, val sequence: Long) : RuntimeUpdateResult()
    data class Installed(val source: String, val update: RuntimeUpdate) : RuntimeUpdateResult()
}

object RuntimeUpdateManifestVerifier {
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val KEY_ID = Regex("^[a-f0-9]{32}$")
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

    fun verify(
        text: String,
        trustedKeys: Map<String, ByteArray>,
        expectedProtocol: Int,
        installedAppVersion: Long,
        allowedOrigins: Set<String>
    ): RuntimeUpdate {
        require(text.toByteArray().size <= 64 * 1024) { "Runtime update manifest is too large" }
        val envelope = JSONObject(text)
        require(envelope.keys().asSequence().toSet() == setOf("schemaVersion", "keyId", "payload", "signature")) {
            "Runtime update envelope fields are invalid"
        }
        require(envelope.getInt("schemaVersion") == 1) { "Unsupported runtime update envelope" }
        val keyId = envelope.getString("keyId").also { require(KEY_ID.matches(it)) }
        val keyBytes = trustedKeys[keyId] ?: throw IllegalArgumentException("Runtime update uses an unknown signing key")
        val payloadText = decode(envelope.getString("payload"), 64 * 1024).toString(Charsets.UTF_8)
        val signatureBytes = decode(envelope.getString("signature"), 128)
        val payload = JSONObject(payloadText)
        require(payload.keys().asSequence().toSet() == setOf(
            "schemaVersion", "sequence", "version", "protocolVersion", "artifactUrl",
            "sha256", "size", "minAppVersionCode", "createdAt"
        )) { "Runtime update payload fields are invalid" }
        require(payload.getInt("schemaVersion") == 1)
        val sequence = payload.getLong("sequence").also { require(it > 0) }
        val version = payload.getString("version").also { require(VERSION.matches(it)) }
        val protocol = payload.getInt("protocolVersion").also { require(it == expectedProtocol) { "Runtime protocol is incompatible" } }
        val artifactUrl = payload.getString("artifactUrl")
        val origin = ClientPolicyParser.canonicalOrigin(artifactUrl)
        require(origin in allowedOrigins) { "Runtime artifact origin is not approved" }
        val sha256 = payload.getString("sha256").also { require(SHA256.matches(it)) }
        val size = payload.getLong("size").also { require(it in 1..32L * 1024L * 1024L) }
        val minimum = payload.getLong("minAppVersionCode").also {
            require(it > 0 && it <= installedAppVersion) { "Runtime update requires a newer Agent Fleet app" }
        }
        val createdAt = payload.getString("createdAt").also {
            require(validTimestamp(it)) { "Runtime update timestamp is invalid" }
        }
        val update = RuntimeUpdate(sequence, version, protocol, artifactUrl, sha256, size, minimum, createdAt, keyId)
        require(canonical(update) == payloadText) { "Runtime update payload is not canonical JSON" }
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payloadText.toByteArray(Charsets.UTF_8))
        require(verifier.verify(signatureBytes)) { "Runtime update signature verification failed" }
        return update
    }

    private fun canonical(value: RuntimeUpdate): String = buildString {
        append('{')
        append("\"artifactUrl\":").append(jsonQuote(value.artifactUrl)).append(',')
        append("\"createdAt\":").append(jsonQuote(value.createdAt)).append(',')
        append("\"minAppVersionCode\":").append(value.minAppVersionCode).append(',')
        append("\"protocolVersion\":").append(value.protocolVersion).append(',')
        append("\"schemaVersion\":1,")
        append("\"sequence\":").append(value.sequence).append(',')
        append("\"sha256\":").append(jsonQuote(value.sha256)).append(',')
        append("\"size\":").append(value.size).append(',')
        append("\"version\":").append(jsonQuote(value.version))
        append('}')
    }

    private fun jsonQuote(value: String): String = JSONObject.quote(value).replace("\\/", "/")

    private fun validTimestamp(value: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(value)) return false
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        return parser.parse(value, position) != null && position.index == value.length
    }

    private fun decode(value: String, maximum: Int): ByteArray {
        require(value.length in 1..maximum * 2 && BASE64URL.matches(value) && '=' !in value)
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        require(decoded.size in 1..maximum && Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) == value) {
            "Runtime update base64url is not canonical"
        }
        return decoded
    }
}

class RuntimeUpdateManager(
    private val context: Context,
    private val embedded: EmbeddedRuntimeManager = EmbeddedRuntimeManager(context),
    private val policyStore: ClientPolicyStore = ClientPolicyStore(context)
) {
    private val preferences = context.getSharedPreferences("agent-fleet-runtime-updates", Context.MODE_PRIVATE)

    fun shouldCheck(now: Long = System.currentTimeMillis()): Boolean {
        val policy = runCatching { policyStore.load() }.getOrNull() ?: return false
        return now - preferences.getLong("last-check-at", 0) >= policy.checkIntervalSeconds * 1000L
    }

    fun shouldPreserveCurrentRuntime(status: EmbeddedRuntimeStatus): Boolean {
        return shouldPreserveVerifiedRuntime(
            status,
            acceptedSequence(),
            healthySequence()
        )
    }

    fun reconcileRuntimeFloor(status: EmbeddedRuntimeStatus): EmbeddedRuntimeStatus {
        val floor = installedVersionCode()
        if (acceptedSequence() >= floor && healthySequence() >= floor) return status
        require(status.supported && status.usable && status.baseline == status.embeddedBaseline) {
            "APK runtime floor cannot be recorded before its baseline is healthy"
        }
        val preserveCurrent = shouldPreserveCurrentRuntime(status)
        val reconciled = if (status.current == status.baseline || preserveCurrent) status else embedded.restoreBaseline()
        require(
            reconciled.usable && reconciled.baseline == reconciled.embeddedBaseline &&
                (preserveCurrent || reconciled.current == reconciled.baseline)
        ) {
            "APK runtime floor activation failed"
        }
        check(preferences.edit()
            .putLong("accepted-sequence", maxOf(acceptedSequence(), floor))
            .putLong("healthy-sequence", maxOf(healthySequence(), floor))
            .putString("last-error", "")
            .commit()
        ) { "Runtime floor state could not be persisted" }
        return reconciled
    }

    fun checkAndInstall(manual: Boolean = false): RuntimeUpdateResult {
        val policy = policyStore.load() ?: return RuntimeUpdateResult.NoPolicy
        val floor = installedVersionCode()
        require(acceptedSequence() >= floor && healthySequence() >= floor) {
            "Built-in runtime must be prepared before checking for fixes"
        }
        if (!manual && !shouldCheck()) {
            return RuntimeUpdateResult.Current(preferences.getString("last-source", "").orEmpty(), acceptedSequence())
        }
        preferences.edit().putLong("last-check-at", System.currentTimeMillis()).apply()
        val descriptor = embedded.descriptor()
        val keys = descriptor.trustedRuntimeKeys.associate { key -> key.keyId to trustedKey(key) }
        var lastError: Exception? = null
        for (manifestUrl in policy.runtimeManifestUrls) {
            try {
                val manifestOrigin = ClientPolicyParser.canonicalOrigin(manifestUrl)
                require(manifestOrigin in policy.artifactOrigins) { "Runtime manifest origin is not approved" }
                val text = readUrl(manifestUrl, 64 * 1024).toString(Charsets.UTF_8)
                val update = RuntimeUpdateManifestVerifier.verify(
                    text, keys, descriptor.protocolVersion, installedVersionCode(), policy.artifactOrigins
                )
                if (update.sequence <= maxOf(acceptedSequence(), floor)) {
                    require(update.sequence <= healthySequence()) {
                        "This signed runtime update previously failed its health check and will not be retried"
                    }
                    preferences.edit().putString("last-source", manifestUrl).putString("last-error", "").apply()
                    return RuntimeUpdateResult.Current(manifestUrl, acceptedSequence())
                }
                require(update.version != embedded.inspect().current) { "Runtime update sequence changed without a new version" }
                val artifact = download(update)
                check(preferences.edit().putLong("accepted-sequence", update.sequence).commit()) {
                    "Runtime replay-protection state could not be persisted"
                }
                embedded.installHotfix(artifact, update.sha256)
                preferences.edit()
                    .putLong("healthy-sequence", update.sequence)
                    .putString("last-source", manifestUrl)
                    .putString("last-key-id", update.keyId)
                    .putString("last-error", "")
                    .apply()
                return RuntimeUpdateResult.Installed(manifestUrl, update)
            } catch (error: Exception) {
                lastError = error
            }
        }
        val message = lastError?.message ?: "No runtime update source was reachable"
        preferences.edit().putString("last-error", message.take(240)).apply()
        throw IllegalStateException(message, lastError)
    }

    fun acceptedSequence(): Long = preferences.getLong("accepted-sequence", 0)
    fun healthySequence(): Long = preferences.getLong("healthy-sequence", 0)
    fun lastCheckAt(): Long = preferences.getLong("last-check-at", 0)
    fun lastSource(): String = preferences.getString("last-source", "").orEmpty()
    fun lastKeyId(): String = preferences.getString("last-key-id", "").orEmpty()
    fun lastError(): String = preferences.getString("last-error", "").orEmpty()

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun trustedKey(key: EmbeddedRuntimeKey): ByteArray {
        val bytes = context.assets.open("agent-fleet/${key.file}").use { it.readBytes() }
        require(bytes.sha256() == key.sha256) { "Trusted runtime key asset verification failed" }
        val text = bytes.toString(Charsets.US_ASCII)
        val encoded = text.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").filterNot(Char::isWhitespace)
        val der = Base64.decode(encoded, Base64.DEFAULT)
        val id = der.sha256().take(32)
        require(id == key.keyId) { "Trusted runtime key ID verification failed" }
        return der
    }

    private fun download(update: RuntimeUpdate): File {
        val directory = File(context.cacheDir, "agent-fleet-runtime-updates").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete() }
        val destination = File(directory, "wtmux-${update.sequence}-${update.version}.tar")
        val connection = openHttps(update.artifactUrl)
        try {
            require(connection.responseCode in 200..299) { "Runtime download failed (${connection.responseCode})" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong == update.size) { "Runtime download size changed" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32L * 1024L * 1024L) { "Runtime download is too large" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(total == update.size && digest.digest().toHex() == update.sha256) { "Runtime download verification failed" }
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun readUrl(value: String, maximum: Int): ByteArray {
        val connection = openHttps(value)
        try {
            require(connection.responseCode in 200..299) { "Runtime update check failed (${connection.responseCode})" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maximum) { "Runtime update manifest is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(value: String): HttpURLConnection {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null)
        return (URL(value).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            useCaches = false
        }
    }
}

internal fun shouldPreserveVerifiedRuntime(
    status: EmbeddedRuntimeStatus,
    acceptedSequence: Long,
    healthySequence: Long
): Boolean = status.supported && status.usable &&
    status.baseline == status.embeddedBaseline &&
    status.current.isNotBlank() && status.current != status.baseline &&
    acceptedSequence > 0 && acceptedSequence == healthySequence

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
