package com.termux.app.fleet

import android.app.Service
import android.content.BroadcastReceiver
import android.content.pm.ApplicationInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

internal data class LocalSuggestionGeneration(
    val sequence: Long,
    val requestId: String
)

internal data class LocalSuggestionOwnedGeneration<T>(
    val generation: LocalSuggestionGeneration,
    val value: T
)

internal data class LocalSuggestionGenerationStart<T>(
    val generation: LocalSuggestionGeneration,
    val superseded: LocalSuggestionOwnedGeneration<T>?
)

/**
 * Owns one generation at a time. Taking a generation is the only way to
 * complete it, so late worker/service callbacks cannot complete a successor.
 */
internal class LocalSuggestionGenerationOwner<T> {
    private var sequence = 0L
    private var active: LocalSuggestionOwnedGeneration<T>? = null

    @Synchronized
    fun begin(requestId: String, value: T): LocalSuggestionGenerationStart<T> {
        require(requestId.isNotBlank())
        val previous = active
        sequence++
        val generation = LocalSuggestionGeneration(sequence, requestId)
        active = LocalSuggestionOwnedGeneration(generation, value)
        return LocalSuggestionGenerationStart(generation, previous)
    }

    @Synchronized
    fun current(generation: LocalSuggestionGeneration): T? =
        active?.takeIf { it.generation == generation }?.value

    @Synchronized
    fun current(requestId: String): LocalSuggestionOwnedGeneration<T>? =
        active?.takeIf { it.generation.requestId == requestId }

    @Synchronized
    fun finish(generation: LocalSuggestionGeneration): T? {
        val owned = active?.takeIf { it.generation == generation } ?: return null
        active = null
        return owned.value
    }

    @Synchronized
    fun cancel(requestId: String? = null): LocalSuggestionOwnedGeneration<T>? {
        val owned = active ?: return null
        if (!requestId.isNullOrBlank() && owned.generation.requestId != requestId) return null
        active = null
        return owned
    }
}

class LocalSuggestionService : Service() {
    companion object {
        const val ACTION_SHUTDOWN = "com.yaakovch.fleet.LOCAL_SUGGESTION_SHUTDOWN"
        internal const val ACTION_KEEP_WARM = "com.yaakovch.fleet.LOCAL_SUGGESTION_KEEP_WARM"
        const val MSG_GENERATE = 1
        const val MSG_CANCEL = 2
        const val MSG_RESULT = 3
        const val KEY_REQUEST_ID = "requestId"
        const val KEY_PROMPT = "prompt"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        internal const val KEY_DEBUG_FAKE_OUTPUT = "debugFakeOutput"
        private const val IDLE_MILLIS = 60_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val engineOwner = LocalSuggestionEngineOwner(::createEngine) { it.close() }
    private val generations = LocalSuggestionGenerationOwner<ServiceGeneration>()
    private val idleShutdown = Runnable { shutdownProcess() }
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHUTDOWN) shutdownProcess()
        }
    }
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_GENERATE -> generate(message)
                MSG_CANCEL -> cancel(message.data.getString(KEY_REQUEST_ID).orEmpty())
                else -> super.handleMessage(message)
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            shutdownReceiver,
            IntentFilter(ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        return incoming.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        generations.cancel()?.value?.cancelInference()
        main.removeCallbacks(idleShutdown)
        main.postDelayed(idleShutdown, IDLE_MILLIS)
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            shutdownProcess()
        } else {
            main.removeCallbacks(idleShutdown)
            main.postDelayed(idleShutdown, IDLE_MILLIS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(shutdownReceiver) }
        main.removeCallbacks(idleShutdown)
        generations.cancel()?.value?.cancelInference()
        engineOwner.close()
        worker.shutdownNow()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun generate(message: Message) {
        val requestId = message.data.getString(KEY_REQUEST_ID).orEmpty().take(128)
        val prompt = message.data.getString(KEY_PROMPT).orEmpty()
        if (requestId.isBlank() || prompt.isBlank() || prompt.toByteArray(Charsets.UTF_8).size > LOCAL_SUGGESTION_MAX_PROMPT_BYTES) {
            reply(message.replyTo, requestId, "", "Suggestion request is invalid.")
            return
        }
        main.removeCallbacks(idleShutdown)
        val request = ServiceGeneration(
            recipient = message.replyTo,
            fakeOutput = if (isDebuggableBuild()) {
                message.data.getString(KEY_DEBUG_FAKE_OUTPUT)?.take(8 * 1024)
            } else {
                null
            }
        )
        val started = generations.begin(requestId, request)
        started.superseded?.let { superseded ->
            superseded.value.cancelInference()
            reply(
                superseded.value.recipient,
                superseded.generation.requestId,
                "",
                "Local model request was superseded."
            )
        }
        worker.submit {
            if (generations.current(started.generation) !== request) return@submit
            var activeConversation: Conversation? = null
            val result = runCatching {
                request.fakeOutput ?: run {
                    val activeEngine = loadEngine()
                    ensureActive(started.generation, request)
                    val created = activeEngine.createConversation()
                    activeConversation = created
                    request.conversation = created
                    ensureActive(started.generation, request)
                    val response = created.sendMessage(prompt)
                    response.toString()
                }
            }
            activeConversation?.runCatching { close() }
            if (request.conversation === activeConversation) request.conversation = null
            main.post {
                if (generations.finish(started.generation) != null) {
                    result.onSuccess { reply(request.recipient, started.generation.requestId, it, "") }
                        .onFailure {
                            reply(
                                request.recipient,
                                started.generation.requestId,
                                "",
                                "Local model could not generate suggestions."
                            )
                        }
                    main.postDelayed(idleShutdown, IDLE_MILLIS)
                }
            }
        }
    }

    private fun cancel(requestId: String) {
        generations.cancel(requestId)?.let { canceled ->
            canceled.value.cancelInference()
            reply(canceled.value.recipient, canceled.generation.requestId, "", "Local model request was canceled.")
            main.removeCallbacks(idleShutdown)
            main.postDelayed(idleShutdown, IDLE_MILLIS)
        }
    }

    private fun ensureActive(generation: LocalSuggestionGeneration, request: ServiceGeneration) {
        if (generations.current(generation) !== request) {
            throw CancellationException("Local model generation was superseded.")
        }
    }

    private data class ServiceGeneration(
        val recipient: Messenger?,
        val fakeOutput: String?,
        @Volatile var conversation: Conversation? = null
    ) {
        fun cancelInference() {
            conversation?.runCatching { cancelProcess() }
        }
    }

    private fun loadEngine(): Engine = engineOwner.get()

    private fun createEngine(): Engine {
        val model = LocalSuggestionModel.file(this)
        if (!LocalSuggestionPreferences.enabled(this) || !model.isFile || model.length() != LocalSuggestionModel.SIZE) {
            throw IllegalStateException("The verified local model is not enabled.")
        }
        val cache = File(cacheDir, "local-llm").apply { mkdirs() }.absolutePath
        fun create(backend: Backend): Engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = backend, cacheDir = cache)).also { it.initialize() }
        return runCatching { create(Backend.GPU()) }.getOrElse { create(Backend.CPU()) }
    }

    private fun reply(recipient: Messenger?, requestId: String, result: String, error: String) {
        if (recipient == null) return
        val response = Message.obtain(null, MSG_RESULT).apply {
            data = Bundle().apply {
                putString(KEY_REQUEST_ID, requestId)
                putString(KEY_RESULT, result.take(8 * 1024))
                putString(KEY_ERROR, error.take(300))
            }
        }
        runCatching { recipient.send(response) }
    }

    private fun shutdownProcess() {
        stopSelf()
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 100L)
    }
}

internal class LocalSuggestionEngineOwner<T : Any>(
    private val create: () -> T,
    private val closeValue: (T) -> Unit
) : AutoCloseable {
    private var value: T? = null
    private var closed = false

    @Synchronized
    fun get(): T {
        check(!closed) { "Local suggestion engine owner is closed" }
        value?.let { return it }
        return create().also { value = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        value?.let { runCatching { closeValue(it) } }
        value = null
    }
}

class LocalSuggestionClient(
    private val context: Context,
    private val debugFakeOutput: String? = null
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generations = LocalSuggestionGenerationOwner<ClientGeneration>()
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != LocalSuggestionService.MSG_RESULT) return super.handleMessage(message)
            val id = message.data.getString(LocalSuggestionService.KEY_REQUEST_ID).orEmpty()
            val owned = generations.current(id) ?: return
            val error = message.data.getString(LocalSuggestionService.KEY_ERROR).orEmpty()
            val output = message.data.getString(LocalSuggestionService.KEY_RESULT).orEmpty()
            val result = if (error.isNotBlank()) {
                Result.failure(IllegalStateException(error))
            } else {
                runCatching { parseLocalSuggestions(output) }.fold(
                    onSuccess = { values ->
                        if (values.isEmpty()) {
                            Result.failure(IllegalStateException("Local model returned no usable suggestions."))
                        } else {
                            Result.success(values)
                        }
                    },
                    onFailure = { Result.failure(it) }
                )
            }
            finish(owned.generation, result, cancelRemote = false)
        }
    })

    fun generate(prompt: String, onResult: (Result<List<String>>) -> Unit) {
        val useDebugFake = app.isDebuggableBuild() && debugFakeOutput != null
        if (!useDebugFake && !LocalSuggestionPreferences.enabled(app)) {
            onResult(Result.failure(IllegalStateException("Enable the verified local model in More first.")))
            return
        }
        val requestId = UUID.randomUUID().toString()
        val request = ClientGeneration(prompt, onResult)
        val started = generations.begin(requestId, request)
        request.connection = connection(started.generation)
        request.timeout = Runnable {
            finish(
                started.generation,
                Result.failure(IllegalStateException("Local model timed out.")),
                cancelRemote = true
            )
        }
        started.superseded?.let { superseded ->
            complete(
                superseded,
                Result.failure(CancellationException("Local model request was superseded.")),
                cancelRemote = true
            )
        }
        if (generations.current(started.generation) !== request) return
        main.postDelayed(request.timeout, 30_000L)
        val intent = Intent(app, LocalSuggestionService::class.java)
        // The isolated service is otherwise bound-only and Android destroys it as soon as this
        // request unbinds, making its 60-second engine-idle window ineffective. Start it only when
        // a real request exists; the service stops itself after the warm window, while app
        // background and Off mode stop it earlier without ever creating an unused service.
        runCatching {
            app.startService(
                Intent(app, LocalSuggestionService::class.java)
                    .setAction(LocalSuggestionService.ACTION_KEEP_WARM)
            )
        }
        val bound = runCatching {
            app.bindService(intent, request.connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            finish(started.generation, Result.failure(it), cancelRemote = false)
            return
        }
        request.bound = bound
        if (generations.current(started.generation) !== request) {
            if (bound) runCatching { app.unbindService(request.connection) }
            return
        }
        if (!bound) {
            finish(
                started.generation,
                Result.failure(IllegalStateException("Local model process could not start.")),
                cancelRemote = false
            )
        }
    }

    fun cancel() = cancelActive("Local model request was canceled.")

    override fun close() { cancel() }

    private fun connection(generation: LocalSuggestionGeneration) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val request = generations.current(generation) ?: return
            if (binder == null) {
                finish(
                    generation,
                    Result.failure(IllegalStateException("Local model process is unavailable.")),
                    cancelRemote = false
                )
                return
            }
            request.service = Messenger(binder)
            sendRequest(generation, request)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val request = generations.current(generation) ?: return
            request.service = null
            finish(
                generation,
                Result.failure(IllegalStateException("Local model process disconnected.")),
                cancelRemote = false
            )
        }
    }

    private fun sendRequest(generation: LocalSuggestionGeneration, request: ClientGeneration) {
        if (generations.current(generation) !== request || request.sent) return
        request.sent = true
        runCatching {
            request.service?.send(Message.obtain(null, LocalSuggestionService.MSG_GENERATE).apply {
                replyTo = incoming
                data = Bundle().apply {
                    putString(LocalSuggestionService.KEY_REQUEST_ID, generation.requestId)
                    putString(LocalSuggestionService.KEY_PROMPT, request.prompt)
                    if (app.isDebuggableBuild()) {
                        debugFakeOutput?.let {
                            putString(LocalSuggestionService.KEY_DEBUG_FAKE_OUTPUT, it.take(8 * 1024))
                        }
                    }
                }
            }) ?: throw IllegalStateException("Local model process is unavailable.")
        }.onFailure { finish(generation, Result.failure(it), cancelRemote = true) }
    }

    private fun cancelActive(message: String) {
        val owned = generations.cancel() ?: return
        complete(
            owned,
            Result.failure(CancellationException(message)),
            cancelRemote = true
        )
    }

    private fun finish(
        generation: LocalSuggestionGeneration,
        result: Result<List<String>>,
        cancelRemote: Boolean
    ) {
        val request = generations.finish(generation) ?: return
        complete(LocalSuggestionOwnedGeneration(generation, request), result, cancelRemote)
    }

    private fun complete(
        owned: LocalSuggestionOwnedGeneration<ClientGeneration>,
        result: Result<List<String>>,
        cancelRemote: Boolean
    ) {
        val request = owned.value
        main.removeCallbacks(request.timeout)
        if (cancelRemote && request.sent) runCatching {
            request.service?.send(Message.obtain(null, LocalSuggestionService.MSG_CANCEL).apply {
                data = Bundle().apply {
                    putString(LocalSuggestionService.KEY_REQUEST_ID, owned.generation.requestId)
                }
            })
        }
        if (request.bound) runCatching { app.unbindService(request.connection) }
        request.bound = false
        request.service = null
        runCatching { request.callback(result) }
    }

    private class ClientGeneration(
        val prompt: String,
        val callback: (Result<List<String>>) -> Unit
    ) {
        lateinit var connection: ServiceConnection
        lateinit var timeout: Runnable
        var service: Messenger? = null
        var bound = false
        var sent = false
    }
}

private fun Context.isDebuggableBuild(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
