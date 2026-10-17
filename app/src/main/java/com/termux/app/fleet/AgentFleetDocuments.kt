package com.termux.app.fleet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

internal val AGENT_FLEET_DOCUMENT_EXTENSIONS = setOf(
    "md", "markdown", "txt", "text", "log", "json", "jsonl", "csv", "tsv", "yaml", "yml", "toml",
    "xml", "html", "htm", "pdf", "doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp"
)

internal data class AgentFleetImportedAttachment(val file: File, val documentName: String? = null)

internal fun importAgentFleetDocument(input: InputStream, name: String, directory: File,
    maxBytes: Long = AGENT_FLEET_MAX_IMAGE_BYTES): File {
    val safe = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(180)
    require(safe.substringAfterLast('.', "").lowercase() in AGENT_FLEET_DOCUMENT_EXTENSIONS) {
        "Choose an image or a supported document, such as Markdown, text or PDF."
    }
    if (!directory.isDirectory && !directory.mkdirs()) throw AgentFleetImageException("Attachment storage is unavailable.")
    val output = File(directory, "${UUID.randomUUID()}-$safe")
    try {
        FileOutputStream(output).use { stream ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw AgentFleetImageException("Files must be smaller than 20 MB.")
                stream.write(buffer, 0, count)
            }
            if (total == 0L) throw AgentFleetImageException("The selected file is empty.")
        }
        return output
    } catch (error: Exception) {
        output.delete()
        throw error
    }
}

internal fun importAgentFleetAttachment(context: Context, uri: Uri, imagesOnly: Boolean = false): AgentFleetImportedAttachment {
    val suppliedName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
    val mime = context.contentResolver.getType(uri).orEmpty()
    val name = if ('.' in suppliedName) suppliedName else when (mime) {
        "text/plain" -> "${suppliedName.ifBlank { "document" }}.txt"
        "text/markdown", "text/x-markdown" -> "${suppliedName.ifBlank { "document" }}.md"
        "application/pdf" -> "${suppliedName.ifBlank { "document" }}.pdf"
        else -> suppliedName
    }
    val extension = name.substringAfterLast('.', "").lowercase()
    if (imagesOnly || extension !in AGENT_FLEET_DOCUMENT_EXTENSIONS) {
        return AgentFleetImportedAttachment(AgentFleetComposer.copyImage(context, uri))
    }
    val root = context.filesDir.parentFile ?: error("App data directory is unavailable")
    val directory = File(root, "files/home/.cache/agent-fleet/attachments")
    val file = context.contentResolver.openInputStream(uri).use { stream ->
        if (stream == null) throw AgentFleetImageException("The selected file is no longer available.")
        importAgentFleetDocument(stream, name, directory)
    }
    return AgentFleetImportedAttachment(file, name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(180))
}

internal fun parseSuccessfulAgentFleetAttachmentOutput(output: AgentFleetProcessOutput): String {
    if (output.exitCode != 0 || output.stdoutTruncated || output.stderrTruncated)
        throw AgentFleetImageException("The host returned incomplete attachment upload output. Retry after refreshing the session.")
    val path = output.stdout.removeSuffix("\n").removeSuffix("\r")
    val extension = path.substringAfterLast('.', "").lowercase()
    if (!Regex("\\.wtmux/attachments/[A-Za-z0-9._-]{1,255}").matches(path) ||
        extension !in AGENT_FLEET_DOCUMENT_EXTENSIONS) {
        throw AgentFleetImageException("The host returned an invalid attachment path.")
    }
    return path
}
