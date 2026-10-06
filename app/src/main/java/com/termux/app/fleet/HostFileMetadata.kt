package com.termux.app.fleet

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class HostFileMetadata(val name: String, val size: Long, val modifiedAt: String, val revision: String, val mediaKind: String) {
    companion object {
        fun parse(value: JSONObject): HostFileMetadata {
            require(value.keys().asSequence().toSet() == setOf("protocolVersion", "name", "size", "modifiedAt", "revision", "mediaKind")) { "Host returned invalid file metadata." }
            require(value.get("protocolVersion") is Int && value.getInt("protocolVersion") == 1
                && (value.get("size") is Int || value.get("size") is Long)
                && listOf("name", "modifiedAt", "revision", "mediaKind").all { value.get(it) is String }) { "Host returned invalid file metadata." }
            val result = HostFileMetadata(value.getString("name"), value.getLong("size"), value.getString("modifiedAt"), value.getString("revision"), value.getString("mediaKind"))
            require(value.getInt("protocolVersion") == 1 && result.name.isNotBlank() && result.name.length <= 255 && result.name !in listOf(".", "..") && !Regex("[/\\\\\\x00-\\x1f\\x7f]").containsMatchIn(result.name)
                && result.size in 0..2_147_483_648L && runCatching { java.time.OffsetDateTime.parse(result.modifiedAt) }.isSuccess && result.revision.matches(Regex("[a-f0-9]{64}")) && result.mediaKind in setOf("image", "pdf", "html", "markdown", "text", "other")) { "Host returned invalid file metadata." }
            return result
        }
    }
}

internal fun verifyHostFileArtifact(file: File, size: Long, expectedSha256: String): Boolean {
    if (!file.isFile || file.length() != size || !expectedSha256.matches(Regex("[a-f0-9]{64}"))) return false
    val modified = file.lastModified()
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
    return file.length() == size && file.lastModified() == modified && digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256
}
