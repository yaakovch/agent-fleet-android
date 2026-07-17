package com.termux.app.fleet

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal data class AgentFleetPublishedDownload(
    val name: String,
    val location: String,
    val size: Long
)

internal fun createAgentFleetDownloadDirectory(context: Context): File {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        require((downloads.exists() || downloads.mkdirs()) && downloads.isDirectory && downloads.canWrite()) {
            "Android Downloads is not writable. Grant storage access from the terminal first."
        }
        return downloads
    }
    val directory = File(context.cacheDir, "agent-fleet-downloads/${UUID.randomUUID()}")
    require(directory.mkdirs() && directory.isDirectory && directory.canWrite()) {
        "Private download staging is unavailable."
    }
    return directory
}

internal fun cleanupAgentFleetDownloadDirectory(context: Context, directory: File) {
    val root = File(context.cacheDir, "agent-fleet-downloads")
    if (directory.parentFile == root) directory.deleteRecursively()
    root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DOWNLOAD_STAGING_MAX_AGE_MS }
        ?.forEach(File::deleteRecursively)
}

internal fun publishAgentFleetDownload(context: Context, source: File, requestedName: String): AgentFleetPublishedDownload {
    require(source.isFile && source.canRead()) { "The downloaded file is unavailable." }
    require(requestedName.isNotBlank() && requestedName.length <= 255 && '/' !in requestedName && '\\' !in requestedName) {
        "The downloaded file name is invalid."
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        MediaScannerConnection.scanFile(context, arrayOf(source.absolutePath), null, null)
        return AgentFleetPublishedDownload(requestedName, source.absolutePath, source.length())
    }

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForDownload(requestedName))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Android Downloads did not create the file.")
    try {
        resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                source.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                output.fd.sync()
            }
        } ?: error("Android Downloads did not open the file.")
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        require(resolver.update(uri, published, null, null) == 1) { "Android Downloads did not publish the file." }
        val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { requestedName }
        return AgentFleetPublishedDownload(actualName, uri.toString(), source.length())
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

internal fun verifyAgentFleetDownloads(context: Context): Pair<String, String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val downloads = createAgentFleetDownloadDirectory(context)
        val probe = File(downloads, ".agent-fleet-diagnostic-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(probe).use { output ->
                output.write(DOWNLOAD_PROBE)
                output.fd.sync()
            }
            require(probe.readBytes().contentEquals(DOWNLOAD_PROBE)) { "Downloads write verification failed." }
        } finally {
            probe.delete()
        }
        return "Downloads is writable" to "A bounded create, fsync, read, and cleanup probe passed."
    }

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "agent-fleet-diagnostic-${UUID.randomUUID()}.tmp")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Android Downloads provider is unavailable.")
    try {
        resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                output.write(DOWNLOAD_PROBE)
                output.fd.sync()
            }
        } ?: error("Android Downloads provider is not writable.")
        require(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "Android Downloads could not publish a file."
        }
        val body = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Android Downloads could not reopen a file.")
        require(body.contentEquals(DOWNLOAD_PROBE)) { "Android Downloads read verification failed." }
        return "Downloads is writable" to "The Android Downloads provider passed create, fsync, publish, read, and cleanup checks."
    } finally {
        resolver.delete(uri, null, null)
    }
}

private fun mimeTypeForDownload(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private val DOWNLOAD_PROBE = "probe".toByteArray(Charsets.UTF_8)
private const val DOWNLOAD_STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
