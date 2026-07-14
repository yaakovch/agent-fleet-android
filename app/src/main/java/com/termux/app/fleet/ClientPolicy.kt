package com.termux.app.fleet

import android.content.Context
import java.io.File
import java.net.URI
import org.json.JSONObject

data class ClientPolicy(
    val policyRevision: Long,
    val apkManifestUrls: List<String>,
    val runtimeManifestUrls: List<String>,
    val artifactOrigins: Set<String>,
    val checkIntervalSeconds: Long
)

object ClientPolicyParser {
    private val HOST = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    fun parse(text: String): ClientPolicy {
        require(text.toByteArray().size <= 64 * 1024) { "Client policy is too large" }
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == setOf(
            "schemaVersion", "policyRevision", "apkManifestUrls", "runtimeManifestUrls",
            "artifactOrigins", "checkIntervalSeconds"
        )) { "Client policy fields are invalid" }
        require(root.getInt("schemaVersion") == 1) { "Unsupported client policy" }
        val revision = root.getLong("policyRevision").also { require(it > 0) }
        val apk = urlList(root.getJSONArray("apkManifestUrls").let { array -> List(array.length()) { array.getString(it) } })
        val runtime = urlList(root.getJSONArray("runtimeManifestUrls").let { array -> List(array.length()) { array.getString(it) } })
        val origins = root.getJSONArray("artifactOrigins").let { array ->
            require(array.length() in 1..4)
            List(array.length()) { canonicalOrigin(array.getString(it), requireOriginOnly = true) }.toSet()
        }.also { require(it.size == root.getJSONArray("artifactOrigins").length()) }
        val interval = root.getLong("checkIntervalSeconds").also { require(it in 3_600..604_800) }
        return ClientPolicy(revision, apk, runtime, origins, interval)
    }

    fun canonicalOrigin(value: String, requireOriginOnly: Boolean = false): String {
        require(value.length in 1..2048 && '\\' !in value && value.none { it.isISOControl() || it.isWhitespace() }) { "Invalid HTTPS URL" }
        val uri = URI(value)
        require(
            uri.scheme == "https" && uri.host != null && HOST.matches(uri.host) && uri.userInfo == null &&
                uri.fragment == null && uri.port in setOf(-1, 443) && (!requireOriginOnly ||
                ((uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null))
        ) { "Client policy URLs must be credential-free HTTPS" }
        return "https://${uri.host}"
    }

    private fun urlList(values: List<String>): List<String> {
        require(values.size in 1..2 && values.distinct().size == values.size)
        values.forEach { canonicalOrigin(it) }
        return values
    }
}

class ClientPolicyStore(context: Context) {
    private val dataRoot = context.filesDir.parentFile ?: error("Application data directory is unavailable")
    val file: File = File(dataRoot, "files/home/.config/wtmux/client-policy.json")

    fun load(): ClientPolicy? = if (file.isFile) ClientPolicyParser.parse(file.readText(Charsets.UTF_8)) else null
}
