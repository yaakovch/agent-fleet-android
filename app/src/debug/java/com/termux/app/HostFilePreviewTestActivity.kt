package com.termux.app

import com.termux.app.fleet.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Uses the production viewer with deterministic host bytes; no live transport. */
class HostFilePreviewTestActivity : HostFilePreviewActivity() {
    override fun createFileSource(): HostFilePreviewSource = Fixture

    object Fixture : HostFilePreviewSource {
        @Volatile var body = "Host preview fixture".toByteArray()
        @Volatile var kind = "text"
        @Volatile var name = "preview-fixture.txt"
        @Volatile var slow = false
        val inspections = AtomicInteger()
        val fetches = AtomicInteger()
        val origins = CopyOnWriteArrayList<String>()
        val directories = CopyOnWriteArrayList<File>()
        fun reset() { body = "Host preview fixture".toByteArray(); kind = "text"; name = "preview-fixture.txt"; slow = false; inspections.set(0); fetches.set(0); origins.clear(); directories.clear() }
        override fun selectSession(id: String, hostId: String, internalName: String, cancellation: FleetDownloadCancellation): FleetSession {
            origins += "$id|$hostId|$internalName"
            require(id == "origin-session" && hostId == "origin-host" && internalName == "managed-origin")
            return FleetSession(id, hostId, internalName, "Fixture", "Fixture", "Fixture", "codex", "linux", "idle", false, null, 0)
        }
        override fun inspect(session: FleetSession, reference: String, cancellation: FleetDownloadCancellation): HostFileMetadata {
            require(reference == "/outside/project/fixture.txt")
            inspections.incrementAndGet()
            return HostFileMetadata(name, body.size.toLong(), "2026-10-06T10:00:00Z", digest(body), kind)
        }
        override fun fetch(session: FleetSession, reference: String, metadata: HostFileMetadata, cancellation: FleetDownloadCancellation,
            directory: File, onProgress: (FleetDownloadState) -> Unit): FleetDownloadState {
            fetches.incrementAndGet(); directories += directory
            val file = File(directory, metadata.name)
            file.writeBytes(body)
            if (slow) {
                while (!cancellation.isCancelledForUi()) Thread.sleep(20)
                file.delete()
                return FleetDownloadState(metadata.name, reference, "cancelled", 0, metadata.size, message = "Transfer cancelled")
            }
            require(metadata.revision == digest(body))
            return FleetDownloadState(metadata.name, reference, "completed", metadata.size, metadata.size, file.path, "Verified", digest(body))
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
