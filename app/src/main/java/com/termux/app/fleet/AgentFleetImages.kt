package com.termux.app.fleet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread

internal class AgentFleetImageException(message: String) : IllegalArgumentException(message)

internal fun importAgentFleetImage(
    input: InputStream,
    mimeType: String?,
    directory: File,
    maxBytes: Long = AGENT_FLEET_MAX_IMAGE_BYTES
): File {
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw AgentFleetImageException("Image storage is unavailable.")
    }
    directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - IMAGE_CACHE_RETENTION_MS }
        ?.forEach(File::delete)
    val source = File(directory, "${UUID.randomUUID()}.source")
    try {
        copyBounded(input, source, maxBytes)
        val extension = detectAgentFleetImageExtension(source.readHeader())
        if (extension != null) {
            val output = File(directory, "${UUID.randomUUID()}.$extension")
            if (!source.renameTo(output)) {
                source.copyTo(output)
                source.delete()
            }
            return output
        }
        if (mimeType?.startsWith("image/") != true) {
            throw AgentFleetImageException("The selected file is not a supported image.")
        }
        return normalizeAgentFleetImage(source, directory, maxBytes)
    } catch (error: Exception) {
        source.delete()
        throw when (error) {
            is AgentFleetImageException -> error
            else -> AgentFleetImageException("The selected image could not be read.")
        }
    }
}

private fun copyBounded(input: InputStream, output: File, maxBytes: Long) {
    FileOutputStream(output).use { stream ->
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw AgentFleetImageException("Images must be smaller than 20 MB.")
            stream.write(buffer, 0, count)
        }
        if (total == 0L) throw AgentFleetImageException("The selected image is empty.")
    }
}

private fun normalizeAgentFleetImage(source: File, directory: File, maxBytes: Long): File {
    val bitmap = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > MAX_NORMALIZED_IMAGE_DIMENSION) {
                    val scale = MAX_NORMALIZED_IMAGE_DIMENSION.toDouble() / longest
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
            }
        } else {
            BitmapFactory.decodeFile(source.absolutePath)
        }
    } catch (_: Exception) {
        null
    } ?: throw AgentFleetImageException("This image format is not supported on this phone.")

    source.delete()
    val prefersPng = bitmap.hasAlpha()
    var output = File(directory, "${UUID.randomUUID()}.${if (prefersPng) "png" else "jpg"}")
    try {
        FileOutputStream(output).use { stream ->
            val format = if (prefersPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            if (!bitmap.compress(format, if (prefersPng) 100 else 90, stream)) {
                throw AgentFleetImageException("The selected image could not be converted.")
            }
        }
        if (output.length() > maxBytes && prefersPng) {
            output.delete()
            output = File(directory, "${UUID.randomUUID()}.jpg")
            FileOutputStream(output).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) {
                    throw AgentFleetImageException("The selected image could not be converted.")
                }
            }
        }
        if (output.length() == 0L || output.length() > maxBytes) {
            throw AgentFleetImageException("The converted image is larger than 20 MB.")
        }
        return output
    } catch (error: Exception) {
        output.delete()
        throw error
    } finally {
        bitmap.recycle()
    }
}

internal fun detectAgentFleetImageExtension(header: ByteArray): String? = when {
    header.size >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "png"
    header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "jpg"
    header.size >= 12 && header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        header.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
    else -> null
}

private fun File.readHeader(): ByteArray = inputStream().use { input ->
    val header = ByteArray(16)
    val count = input.read(header)
    if (count <= 0) byteArrayOf() else header.copyOf(count)
}

internal data class AgentFleetProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

internal fun collectAgentFleetProcess(process: Process, timeoutSeconds: Long): AgentFleetProcessOutput {
    var stdout = ""
    var stderr = ""
    val stdoutReader = thread(name = "agent-fleet-image-stdout", isDaemon = true) {
        stdout = process.inputStream.readBoundedText()
    }
    val stderrReader = thread(name = "agent-fleet-image-stderr", isDaemon = true) {
        stderr = process.errorStream.readBoundedText()
    }
    if (!process.waitForCompat(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForciblyCompat()
        stdoutReader.join(1_000)
        stderrReader.join(1_000)
        throw AgentFleetImageException("Image upload timed out. Check the connection and retry.")
    }
    stdoutReader.join(1_000)
    stderrReader.join(1_000)
    return AgentFleetProcessOutput(process.exitValue(), stdout, stderr)
}

private fun InputStream.readBoundedText(): String {
    val buffer = ByteArray(4 * 1024)
    val output = StringBuilder()
    try {
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.length < MAX_PROCESS_OUTPUT_CHARS) {
                output.append(String(buffer, 0, minOf(count, MAX_PROCESS_OUTPUT_CHARS - output.length), Charsets.UTF_8))
            }
        }
    } catch (_: IOException) {
        // Android may close a completed Process pipe from another thread.
    }
    return output.toString()
}

internal fun agentFleetImageUploadFailure(stderr: String, exitCode: Int): String {
    val cleaned = stderr.replace(ANSI_ESCAPE, "")
    val normalized = cleaned.lowercase()
    val missingCommand = COMMAND_NOT_FOUND.find(cleaned)?.groupValues?.getOrNull(1)
    return when {
        "unknown host" in normalized || "config has no machines" in normalized ->
            "Image destination is not configured. Refresh sessions and retry."
        "transfer_rejected" in normalized ->
            "The host rejected the selected image destination. Refresh the session and retry."
        "permission denied" in normalized || "publickey" in normalized ->
            "The host rejected the image connection. Refresh pairing and retry."
        "could not resolve hostname" in normalized || "network is unreachable" in normalized ||
            "connection timed out" in normalized || "connection refused" in normalized ||
            "broken pipe" in normalized || "connection reset" in normalized || "connection closed" in normalized ->
            "The host could not be reached. Check the connection and retry."
        "unsupported image extension" in normalized -> "The selected image format is not supported."
        exitCode == 127 && "wtmux-host-runtime" in normalized ->
            "The stable host runtime is unavailable. Refresh the host setup and retry."
        exitCode == 127 && missingCommand != null ->
            "Image upload needs the missing '$missingCommand' command. Repair the runtime and retry."
        exitCode == 127 ->
            "The image transfer command is unavailable on the phone or host. Repair the runtime, refresh the session, and retry."
        exitCode == 255 ->
            "The image connection was lost. Refresh the session and retry."
        else -> "Image upload failed (wtmux exit $exitCode). Retry after refreshing the session."
    }
}

internal fun shouldRetryAgentFleetImageUpload(output: AgentFleetProcessOutput, attempt: Int): Boolean {
    if (attempt != 0) return false
    val detail = output.stderr.lowercase()
    return output.exitCode == 127 || output.exitCode == 255 ||
        "broken pipe" in detail || "connection reset" in detail || "connection timed out" in detail ||
        "connection refused" in detail || "network is unreachable" in detail
}

internal fun parseAgentFleetImagePath(stdout: String): String {
    val path = stdout.lineSequence().map(String::trim).lastOrNull {
        it.startsWith(".wtmux/images/") || it.contains("/.wtmux/images/")
    } ?: throw AgentFleetImageException("The host did not confirm the image upload. Retry after refreshing the session.")
    if (path.length > 512 || '\u0000' in path || '\n' in path || '\r' in path) {
        throw AgentFleetImageException("The host returned an invalid image path.")
    }
    return path
}

internal const val AGENT_FLEET_MAX_IMAGE_BYTES = 20L * 1024 * 1024
private const val MAX_NORMALIZED_IMAGE_DIMENSION = 4096
private const val MAX_PROCESS_OUTPUT_CHARS = 64 * 1024
private const val IMAGE_CACHE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val ANSI_ESCAPE = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
private val COMMAND_NOT_FOUND = Regex("(?i)([A-Za-z0-9._+-]{1,64}): (?:command )?not found")
