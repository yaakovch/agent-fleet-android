package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory

data class AgentFleetUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val certificateSha256: String,
    val size: Long
)

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    object Downloading : UpdateUiState()
    data class Current(val versionName: String) : UpdateUiState()
    data class Available(val update: AgentFleetUpdate) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

object AgentFleetUpdateManifestParser {
    private const val MAX_MANIFEST_CHARS = 32_768
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$")

    fun parse(text: String): AgentFleetUpdate {
        require(text.length <= MAX_MANIFEST_CHARS) { "Update manifest is too large" }
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "Unsupported update manifest" }
        val versionCode = root.getLong("versionCode")
        val versionName = root.getString("versionName")
        val apkUrl = root.getString("apkUrl")
        val apkSha256 = root.getString("apkSha256").lowercase()
        val certificateSha256 = root.getString("certificateSha256").lowercase()
        val size = root.getLong("size")
        require(versionCode > 0) { "Invalid update version" }
        require(VERSION.matches(versionName)) { "Invalid update version name" }
        require(Uri.parse(apkUrl).scheme == "https") { "Update APK must use HTTPS" }
        require(SHA256.matches(apkSha256)) { "Invalid update checksum" }
        require(SHA256.matches(certificateSha256)) { "Invalid signing certificate" }
        require(size in 1..MAX_APK_BYTES) { "Invalid update size" }
        return AgentFleetUpdate(versionCode, versionName, apkUrl, apkSha256, certificateSha256, size)
    }

    const val MAX_APK_BYTES = 300L * 1024L * 1024L
}

class AgentFleetUpdateManager(private val context: Context) {
    @Suppress("DEPRECATION")
    fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    fun installedVersionName(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun check(manifestUrl: String, allowedOrigins: Set<String> = emptySet()): AgentFleetUpdate? {
        requireHttps(manifestUrl, "Update manifest")
        if (allowedOrigins.isNotEmpty()) {
            require(ClientPolicyParser.canonicalOrigin(manifestUrl) in allowedOrigins) { "App update manifest origin is not approved" }
        }
        val text = readUrl(manifestUrl, 32_768).toString(Charsets.UTF_8)
        val update = AgentFleetUpdateManifestParser.parse(text)
        if (allowedOrigins.isNotEmpty()) {
            require(ClientPolicyParser.canonicalOrigin(update.apkUrl) in allowedOrigins) { "App update APK origin is not approved" }
        }
        require(update.certificateSha256 == installedCertificateSha256()) {
            "Update is signed by a different certificate"
        }
        return update.takeIf { it.versionCode > installedVersionCode() }
    }

    fun downloadAndVerify(update: AgentFleetUpdate, allowedOrigins: Set<String> = emptySet()): File {
        requireHttps(update.apkUrl, "Update APK")
        if (allowedOrigins.isNotEmpty()) {
            require(ClientPolicyParser.canonicalOrigin(update.apkUrl) in allowedOrigins) { "App update APK origin is not approved" }
        }
        require(update.certificateSha256 == installedCertificateSha256()) {
            "Update is signed by a different certificate"
        }
        val directory = File(context.cacheDir, "agent-fleet-updates").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete() }
        val target = File(directory, "agent-fleet-${update.versionCode}.apk")
        val connection = openHttps(update.apkUrl)
        try {
            require(connection.responseCode in 200..299) { "Update download failed (${connection.responseCode})" }
            val declared = connection.contentLengthLong
            require(declared < 0 || declared == update.size) { "Update size does not match manifest" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= AgentFleetUpdateManifestParser.MAX_APK_BYTES) { "Update APK is too large" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(total == update.size) { "Downloaded update is incomplete" }
            require(digest.digest().toHex() == update.apkSha256) { "Update checksum verification failed" }
            verifyArchive(target, update)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".agentfleet.images", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, update: AgentFleetUpdate) {
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not an APK")
        require(info.packageName == context.packageName) { "Update package name does not match" }
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(code == update.versionCode && code > installedVersionCode()) { "Update version does not match" }
        require(info.versionName == update.versionName) { "Update version name does not match" }
        val certificate = packageCertificateSha256(info)
        require(certificate == update.certificateSha256 && certificate == installedCertificateSha256()) {
            "Update certificate verification failed"
        }
    }

    @Suppress("DEPRECATION")
    private fun installedCertificateSha256(): String {
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return packageCertificateSha256(context.packageManager.getPackageInfo(context.packageName, flags))
    }

    @Suppress("DEPRECATION")
    private fun packageCertificateSha256(info: android.content.pm.PackageInfo): String {
        val bytes = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val signingInfo = requireNotNull(info.signingInfo) { "APK has no signing information" }
            val signers = if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
            require(signers.size == 1) { "APK must have one signing certificate" }
            signers[0].toByteArray()
        } else {
            val signatures = requireNotNull(info.signatures) { "APK has no signing information" }
            require(signatures.size == 1) { "APK must have one signing certificate" }
            signatures[0].toByteArray()
        }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
        return MessageDigest.getInstance("SHA-256").digest(certificate.encoded).toHex()
    }

    private fun readUrl(url: String, maxBytes: Int): ByteArray {
        val connection = openHttps(url)
        try {
            require(connection.responseCode in 200..299) { "Update check failed (${connection.responseCode})" }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "Update manifest is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
        }

    private fun requireHttps(url: String, label: String) {
        require(Uri.parse(url).scheme == "https") { "$label must use HTTPS" }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
