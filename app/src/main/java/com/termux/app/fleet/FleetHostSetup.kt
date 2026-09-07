package com.termux.app.fleet

import android.content.Context
import org.json.JSONObject

data class TailnetHost(
    val nodeId: String, val name: String, val address: String, val platform: String,
    val online: Boolean, val hostId: String
)

data class TailnetHostReview(
    val reviewId: String, val name: String, val address: String, val username: String,
    val runtimePresent: Boolean, val tmuxPresent: Boolean
)

interface FleetHostSetup {
    fun discover(): List<TailnetHost>
    fun review(nodeId: String, username: String): TailnetHostReview
    fun pair(reviewId: String): String
    fun repair(hostId: String)
}

class RuntimeFleetHostSetup(context: Context) : FleetHostSetup {
    private val runtime = EmbeddedRuntimeManager(context.applicationContext)

    override fun discover(): List<TailnetHost> = parseTailnetHosts(runtime.hostSetup(listOf("discover")))

    override fun review(nodeId: String, username: String): TailnetHostReview {
        require(nodeId.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        require(username.matches(Regex("[a-z_][a-z0-9_-]{0,63}"))) { "Enter the Linux account name used on this host" }
        return parseTailnetHostReview(runtime.hostSetup(listOf("review", "--node-id", nodeId, "--username", username)))
    }

    override fun pair(reviewId: String): String {
        require(reviewId.matches(Regex("[a-f0-9]{32}")))
        val result = setupResult(runtime.hostSetup(listOf("pair", "--review-id", reviewId)))
        require(result.getString("status") == "paired")
        return result.safeSetupString("hostId", 64)
    }

    override fun repair(hostId: String) {
        require(hostId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        val (bundle, sha256) = runtime.hostRepairArtifact()
        val result = setupResult(runtime.hostSetup(listOf("repair", "--host-id", hostId,
            "--bundle", bundle.absolutePath, "--sha256", sha256)))
        require(result.getString("status") == "repaired" && result.getString("hostId") == hostId)
        if (result.optJSONArray("missingTools")?.let { it.length() > 0 } == true) {
            throw IllegalStateException("Runtime repaired. Install tmux on this host to enable sessions.")
        }
    }
}

private fun setupResult(text: String): JSONObject {
    require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Host setup result is too large" }
    return JSONObject(text).also { require(it.getInt("schemaVersion") == 1) }
}

internal fun parseTailnetHosts(text: String): List<TailnetHost> {
    val nodes = setupResult(text).getJSONArray("nodes")
    require(nodes.length() <= 256)
    val result = List(nodes.length()) { index ->
        val node = nodes.getJSONObject(index)
        require(node.get("online") is Boolean)
        TailnetHost(node.safeSetupString("nodeId", 160), node.safeSetupString("name", 128),
            node.safeSetupString("address", 253), node.getString("platform").also { require(it in setOf("linux", "windows")) },
            node.getBoolean("online"), node.safeSetupString("hostId", 64, empty = true))
    }
    require(result.map { it.nodeId }.distinct().size == result.size)
    return result
}

internal fun parseTailnetHostReview(text: String): TailnetHostReview {
    val value = setupResult(text)
    val prepared = value.getJSONObject("prepared")
    val record = prepared.getJSONObject("record")
    require(prepared.get("runtimePresent") is Boolean && prepared.get("tmuxPresent") is Boolean)
    return TailnetHostReview(value.safeSetupString("reviewId", 32).also { require(it.matches(Regex("[a-f0-9]{32}"))) },
        record.safeSetupString("name", 128), record.safeSetupString("tailscaleNode", 253),
        record.safeSetupString("linuxUsername", 64), prepared.getBoolean("runtimePresent"), prepared.getBoolean("tmuxPresent"))
}

private fun JSONObject.safeSetupString(key: String, maximum: Int, empty: Boolean = false): String =
    getString(key).also { require(it.length <= maximum && (empty || it.isNotEmpty()) && it.none(Char::isISOControl)) }
