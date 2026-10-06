package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

internal data class HostFilePreviewUiState(val metadata: HostFileMetadata? = null, val file: File? = null,
    val sha256: String = "", val message: String = "Inspecting current host file…", val busy: Boolean = false, val excerpt: Boolean = false)

open class HostFilePreviewActivity : ComponentActivity() {
    private var state by mutableStateOf(HostFilePreviewUiState())
    private var confirmation by mutableStateOf<HostFileMetadata?>(null)
    private val executor = Executors.newSingleThreadExecutor()
    private var cancellation = FleetDownloadCancellation()
    private var generation = 0
    private var foreground = false
    private var started = false
    private lateinit var directory: File
    private lateinit var source: HostFilePreviewSource
    private var session: FleetSession? = null
    private val reference get() = intent.getStringExtra("reference").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = createFileSource()
        File(cacheDir, "host-file-previews").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }?.forEach(File::deleteRecursively)
        directory = File(cacheDir, "host-file-previews/${UUID.randomUUID()}").apply { mkdirs() }
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Text(state.metadata?.name ?: "Host file preview", style = MaterialTheme.typography.titleLarge)
                Text(intent.getStringExtra("hostId").orEmpty(), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !state.busy, onClick = ::refresh) { Text(if (state.file == null && started) "Retry" else "Refresh") }
                    if (state.busy) TextButton(onClick = ::cancelTransfer) { Text("Cancel") }
                    TextButton(enabled = state.file != null && !state.busy, onClick = ::save) { Text("Save") }
                    TextButton(enabled = state.file != null && !state.busy, onClick = ::openExternal) { Text("Open") }
                    TextButton(onClick = { finish() }) { Text("Close") }
                }
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                if (state.excerpt) Text("First 1 MiB shown. Save keeps the complete file.", style = MaterialTheme.typography.bodySmall)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                HostFilePreviewBody(state, Modifier.weight(1f).fillMaxWidth())
            }
            confirmation?.let { metadata -> AlertDialog(onDismissRequest = { confirmation = null; state = state.copy(message = "Transfer cancelled. Retry to fetch the current file.") },
                title = { Text("Fetch ${metadata.name}?") }, text = { Text("This file is ${"%.1f".format(metadata.size / 1048576.0)} MiB. It will be copied privately for preview.") },
                confirmButton = { TextButton(onClick = { confirmation = null; fetch(metadata) }) { Text("Fetch file") } },
                dismissButton = { TextButton(onClick = { confirmation = null; state = state.copy(message = "Transfer cancelled. Retry to fetch the current file.") }) { Text("Cancel") } }) }
        } } }
    }

    override fun onStart() { super.onStart(); foreground = true; if (!started) { started = true; refresh() } }
    override fun onStop() {
        foreground = false
        generation++
        cancellation.cancel()
        confirmation = null
        if (state.busy) state = state.copy(busy = false, message = if (state.file == null) "Transfer interrupted in the background. Retry to fetch the current file." else "Preview paused")
        super.onStop()
    }
    override fun onDestroy() {
        cancellation.cancel(); executor.shutdown()
        executor.executeOrCleanup { directory.deleteRecursively() }
        super.onDestroy()
    }

    private fun refresh() {
        if (!foreground || state.busy) return
        val ticket = ++generation
        cancellation.cancel(); cancellation = FleetDownloadCancellation()
        val ownedCancellation = cancellation
        state = HostFilePreviewUiState(busy = true)
        executor.execute {
            try {
                directory.listFiles()?.forEach(File::delete)
                val current = source.selectSession(intent.getStringExtra("sessionId").orEmpty(), intent.getStringExtra("hostId").orEmpty(), intent.getStringExtra("internalName").orEmpty(), ownedCancellation)
                if (ownedCancellation.isCancelled()) return@execute
                session = current
                val metadata = source.inspect(current, reference, ownedCancellation)
                runOnUiThread {
                    if (!valid(ticket)) return@runOnUiThread
                    state = state.copy(metadata = metadata, busy = false)
                    if (metadata.size > 50L * 1024 * 1024) confirmation = metadata else fetch(metadata)
                }
            } catch (error: Exception) { failure(ticket, error) }
        }
    }

    private fun fetch(metadata: HostFileMetadata) {
        val current = session ?: return
        if (!foreground) return
        val ticket = generation
        val ownedCancellation = cancellation
        state = state.copy(busy = true, message = "Fetching current host file…")
        executor.execute {
            try {
                val result = source.fetch(current, reference, metadata, ownedCancellation, directory) { progress ->
                    runOnUiThread { if (valid(ticket)) state = state.copy(message = progress.message) }
                }
                if (result.status != "completed") error("Transfer interrupted. Retry to fetch the current file.")
                val file = File(requireNotNull(result.path))
                runOnUiThread { if (valid(ticket)) state = HostFilePreviewUiState(metadata, file, result.sha256.orEmpty(), "Verified current host file", excerpt = metadata.mediaKind in setOf("text", "markdown") && metadata.size > 1024 * 1024) else file.delete() }
            } catch (error: Exception) { directory.listFiles()?.forEach(File::delete); failure(ticket, error) }
        }
    }

    private fun save() = useArtifact { file, metadata, sha256 ->
        val saved = saveHostFileToDownloads(applicationContext, file, metadata.name, metadata.size, sha256)
        "Saved to ${saved.location}"
    }
    protected open fun createFileSource(): HostFilePreviewSource = RuntimeHostFilePreviewSource(applicationContext)
    private fun cancelTransfer() {
        ++generation; cancellation.cancel(); state = state.copy(file = null, busy = false, message = "Transfer cancelled. Retry to fetch the current file.")
        executor.execute { directory.listFiles()?.forEach(File::delete) }
    }
    private fun openExternal() = useArtifact { file, metadata, sha256 ->
        val root = File(cacheDir, "host-file-external").apply { mkdirs() }
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }?.forEach(File::deleteRecursively)
        val lease = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val copy = File(lease, metadata.name)
        file.copyTo(copy, overwrite = false)
        require(verifyHostFileArtifact(copy, metadata.size, sha256)) { "External file failed integrity verification." }
        val uri = FileProvider.getUriForFile(this, "$packageName.agentfleet.images", copy)
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, hostFileMime(metadata.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runOnUiThread { runCatching { startActivity(open) }.onFailure { lease.deleteRecursively(); Toast.makeText(this, "No application can open this file.", Toast.LENGTH_LONG).show() } }
        "Opened in another application"
    }
    private fun useArtifact(action: (File, HostFileMetadata, String) -> String) {
        val captured = state
        val file = captured.file ?: return
        val metadata = captured.metadata ?: return
        if (captured.busy) return
        val ticket = generation
        state = captured.copy(busy = true)
        executor.execute {
            try {
                require(verifyHostFileArtifact(file, metadata.size, captured.sha256)) { "Preview failed integrity verification. Refresh to retry." }
                val message = action(file, metadata, captured.sha256)
                runOnUiThread { if (valid(ticket)) state = state.copy(busy = false, message = message) }
            } catch (error: Exception) { failure(ticket, error) }
        }
    }
    private fun valid(ticket: Int) = foreground && !isFinishing && generation == ticket
    private fun failure(ticket: Int, error: Exception) { runOnUiThread { if (valid(ticket)) state = state.copy(busy = false, message = error.message.orEmpty().take(400).ifBlank { "Host file is unavailable. Retry to fetch the current file." }) } }

    companion object {
        @JvmStatic fun open(context: Context, session: FleetSession, reference: String) {
            if (HostFileReferences.target(reference, true) == null) return
            context.startActivity(Intent(context, HostFilePreviewActivity::class.java).putExtra("hostId", session.hostId).putExtra("sessionId", session.id)
                .putExtra("internalName", session.internalName).putExtra("reference", reference))
        }
    }
}

private fun java.util.concurrent.ExecutorService.executeOrCleanup(cleanup: () -> Unit) {
    // Shutdown retains already queued work. Cleanup waits behind any reader that owns a file.
    Thread { runCatching { awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS) }; cleanup() }.apply { isDaemon = true }.start()
}

@Composable private fun HostFilePreviewBody(state: HostFilePreviewUiState, modifier: Modifier) {
    val file = state.file ?: return
    val metadata = state.metadata ?: return
    when (metadata.mediaKind) {
        "image" -> {
            var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
            var zoom by remember(file) { mutableStateOf(1f) }
            LaunchedEffect(file) { bitmap = withContext(Dispatchers.IO) { runCatching { val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, options)
                var sample = 1; while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() } }
            bitmap?.let { image -> Column(modifier) {
                Text("Zoom ${"%.0f".format(zoom * 100)}%")
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..4f)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val width = maxWidth * zoom
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                        Image(image.asImageBitmap(), metadata.name, Modifier.requiredWidth(width).height(width * image.height / maxOf(1, image.width)))
                    }
                }
            } } ?: if (metadata.name.endsWith(".svg", true) && metadata.size <= 16L * 1024 * 1024) HostFileHtml(file, modifier, imageOnly = true)
                else Text("Image preview is unavailable. Save or open it externally.", modifier)
        }
        "pdf" -> HostFilePdf(file, modifier)
        "html" -> if (metadata.size <= 16L * 1024 * 1024) HostFileHtml(file, modifier) else Text("This HTML file exceeds the 16 MiB preview limit. Save or open it externally.", modifier)
        "text", "markdown" -> {
            var text by remember(file) { mutableStateOf("") }
            LaunchedEffect(file) { text = withContext(Dispatchers.IO) { file.inputStream().use { input -> val buffer = ByteArray(minOf(metadata.size, 1024L * 1024).toInt()); var used = 0
                while (used < buffer.size) { val count = input.read(buffer, used, buffer.size - used); if (count < 0) break; used += count }; String(buffer, 0, used, Charsets.UTF_8) } } }
            if (metadata.mediaKind == "markdown") Column(modifier.verticalScroll(rememberScrollState())) { MarkdownText(text) }
            else SelectionContainer(modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodyMedium) }
        }
        else -> Text("Save this file or open it in another application.", modifier)
    }
}

@Composable private fun HostFilePdf(file: File, modifier: Modifier) {
    val owner = remember(file) { HostFilePdfOwner() }
    var count by remember(file) { mutableStateOf(0) }
    var failed by remember(file) { mutableStateOf(false) }
    var zoom by remember(file) { mutableStateOf(1f) }
    var pageInput by remember(file) { mutableStateOf("1") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(file) { runCatching { withContext(Dispatchers.IO) { owner.open(file) } }.onSuccess { count = it }.onFailure { failed = true } }
    DisposableEffect(file) { onDispose { owner.close() } }
    if (failed) { Text("PDF preview is unavailable. Save or open it externally.", modifier); return }
    if (count == 0) { Text("Loading PDF…", modifier); return }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pageInput, onValueChange = { pageInput = it.filter(Char::isDigit).take(6) }, label = { Text("Page of $count") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { val page = (pageInput.toIntOrNull() ?: 1).coerceIn(1, count); pageInput = page.toString(); scope.launch { list.animateScrollToItem(page - 1) } }) { Text("Go") }
        }
        Text("Zoom ${"%.0f".format(zoom * 100)}%")
        Slider(zoom, onValueChange = { zoom = it }, valueRange = 1f..3f)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val pageWidth = maxWidth * zoom
        LazyColumn(Modifier.fillMaxSize(), state = list) { items((0 until count).toList(), key = { it }) { index ->
        var bitmap by remember(file, index) { mutableStateOf<Bitmap?>(null) }
        var pageFailed by remember(file, index) { mutableStateOf(false) }
        LaunchedEffect(file, index) { runCatching { withContext(Dispatchers.IO) { owner.render(index) } }.onSuccess { bitmap = it }.onFailure { pageFailed = true } }
        Text("Page ${index + 1}")
        bitmap?.let { image -> Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Image(image.asImageBitmap(), "Page ${index + 1}", Modifier.requiredWidth(pageWidth).height(pageWidth * image.height / maxOf(1, image.width)).padding(vertical = 8.dp))
        } } ?: Text(if (pageFailed) "Page could not be rendered" else "Loading page…")
    } } } }
}

private class HostFilePdfOwner {
    private var renderer: PdfRenderer? = null
    private var closed = false
    @Synchronized fun open(file: File): Int {
        check(!closed)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val current = try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
        renderer = current
        return current.pageCount
    }
    @Synchronized fun render(index: Int): Bitmap {
        check(!closed)
        return requireNotNull(renderer).openPage(index).use { page ->
            val scale = minOf(2.0, 1600.0 / maxOf(1, page.width), 4096.0 / maxOf(1, page.height))
            Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE); page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
    @Synchronized fun close() { closed = true; renderer?.close(); renderer = null }
}

@Composable private fun HostFileHtml(file: File, modifier: Modifier, imageOnly: Boolean = false) {
    val context = LocalContext.current
    var html by remember(file) { mutableStateOf<String?>(null) }
    var webView by remember(file) { mutableStateOf<WebView?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) { runCatching { withContext(Dispatchers.IO) {
        if (imageOnly) "<img style=\"max-width:100%\" src=\"data:image/svg+xml;base64,${android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)}\">" else file.readText(Charsets.UTF_8)
    } }.onSuccess { html = it }.onFailure { failed = true } }
    DisposableEffect(file) { onDispose { webView?.apply { stopLoading(); loadUrl("about:blank"); destroy() }; webView = null } }
    html?.let { body -> AndroidView(modifier = modifier, factory = {
        WebView(context).apply {
            webView = this
            settings.javaScriptEnabled = !imageOnly
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.blockNetworkLoads = true; settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    if (request?.url?.scheme in setOf("data", "blob", "about")) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            loadDataWithBaseURL(null, "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' blob:; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'\">$body", "text/html", "UTF-8", null)
        }
    }) }
    if (failed) Text("Preview is unavailable. Save or open it externally.", modifier)
}

internal fun hostFileMime(name: String): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

internal fun saveHostFileToDownloads(context: Context, file: File, name: String, size: Long, sha256: String): AgentFleetPublishedDownload {
    require(verifyHostFileArtifact(file, size, sha256)) { "Preview failed integrity verification." }
    if (android.os.Build.VERSION.SDK_INT >= 29) return publishAgentFleetDownload(context, file, name, sha256)
    val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
    val stem = name.substringBeforeLast('.', name); val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    val temporary = File(downloads, ".host-file-save-${UUID.randomUUID()}.part")
    try {
    FileOutputStream(temporary).use { output -> file.inputStream().use { it.copyTo(output, 64 * 1024) }; output.fd.sync() }
    require(verifyHostFileArtifact(temporary, size, sha256)) { "Saved file failed integrity verification." }
    for (index in 0..9999) {
        val destination = File(downloads, if (index == 0) name else "$stem ($index)$extension")
        if (!destination.createNewFile()) continue
        try {
            require(temporary.renameTo(destination)) { "Downloads could not publish the complete file." }
            require(verifyHostFileArtifact(destination, size, sha256)) { "Saved file failed integrity verification." }
            return publishAgentFleetDownload(context, destination, destination.name)
        } catch (error: Exception) { destination.delete(); throw error }
    }
    error("No unused Downloads file name is available.")
    } finally { temporary.delete() }
}
