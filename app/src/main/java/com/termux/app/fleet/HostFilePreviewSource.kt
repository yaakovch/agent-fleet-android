package com.termux.app.fleet

import android.content.Context
import java.io.File

interface HostFilePreviewSource {
    fun selectSession(id: String, hostId: String, internalName: String, cancellation: FleetDownloadCancellation): FleetSession
    fun inspect(session: FleetSession, reference: String, cancellation: FleetDownloadCancellation): HostFileMetadata
    fun fetch(session: FleetSession, reference: String, metadata: HostFileMetadata, cancellation: FleetDownloadCancellation,
        directory: File, onProgress: (FleetDownloadState) -> Unit): FleetDownloadState
}

internal class RuntimeHostFilePreviewSource(context: Context) : HostFilePreviewSource {
    private val runtime = FleetRuntime(context)
    override fun selectSession(id: String, hostId: String, internalName: String, cancellation: FleetDownloadCancellation): FleetSession {
        val snapshot = runtime.loadSnapshot()
        require(!cancellation.isCancelledForUi()) { "Transfer interrupted. Retry to fetch the current file." }
        val session = snapshot.sessions.firstOrNull { it.id == id && it.hostId == hostId && it.internalName == internalName }
            ?: error("The originating session is no longer available.")
        val host = snapshot.hosts.firstOrNull { it.id == hostId && it.status == "healthy" }
            ?: error("The originating host is offline. Retry when it is available.")
        require("files.linked.v1" in host.capabilities) { "Update this host to enable file previews." }
        return session
    }
    override fun inspect(session: FleetSession, reference: String, cancellation: FleetDownloadCancellation) = runtime.inspectHostFile(session, reference, cancellation)
    override fun fetch(session: FleetSession, reference: String, metadata: HostFileMetadata, cancellation: FleetDownloadCancellation,
        directory: File, onProgress: (FleetDownloadState) -> Unit) = runtime.fetchHostFile(session, reference, metadata, cancellation, directory, onProgress)
}
