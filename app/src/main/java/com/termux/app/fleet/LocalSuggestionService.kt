package com.termux.app.fleet

import android.app.Service
import android.content.pm.ApplicationInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class LocalSuggestionService : Service() {
    companion object {
        const val ACTION_SHUTDOWN = "com.yaakovch.fleet.LOCAL_SUGGESTION_SHUTDOWN"
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
    private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var activeRequest = ""
    @Volatile private var debugFakeOutput: String? = null
    private val idleShutdown = Runnable { shutdownProcess() }
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_GENERATE -> generate(message)
                MSG_CANCEL -> cancel(message.data.getString(KEY_REQUEST_ID).orEmpty())
                else -> super.handleMessage(message)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder {
        debugFakeOutput = if (isDebuggableBuild()) intent?.getStringExtra(KEY_DEBUG_FAKE_OUTPUT)?.take(8 * 1024) else null
        return incoming.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        debugFakeOutput = null
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) shutdownProcess()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(idleShutdown)
        conversation?.runCatching { cancelProcess() }
        conversation?.runCatching { close() }
        conversation = null
        engine?.runCatching { close() }
        engine = null
        worker.shutdownNow()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun generate(message: Message) {
        val requestId = message.data.getString(KEY_REQUEST_ID).orEmpty().take(128)
        val prompt = message.data.getString(KEY_PROMPT).orEmpty()
        if (requestId.isBlank() || prompt.isBlank() || prompt.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
            reply(message.replyTo, requestId, "", "Suggestion request is invalid.")
            return
        }
        main.removeCallbacks(idleShutdown)
        conversation?.runCatching { cancelProcess() }
        activeRequest = requestId
        val recipient = message.replyTo
        worker.submit {
            val result = runCatching {
                debugFakeOutput?.takeIf { isDebuggableBuild() } ?: run {
                    val activeEngine = loadEngine()
                    val activeConversation = activeEngine.createConversation()
                    conversation = activeConversation
                    val response = activeConversation.sendMessage(prompt)
                    response.toString()
                }
            }
            conversation?.runCatching { close() }
            conversation = null
            main.post {
                if (activeRequest == requestId) {
                    activeRequest = ""
                    result.onSuccess { reply(recipient, requestId, it, "") }
                        .onFailure { reply(recipient, requestId, "", "Local model could not generate suggestions.") }
                    main.postDelayed(idleShutdown, IDLE_MILLIS)
                }
            }
        }
    }

    private fun cancel(requestId: String) {
        if (requestId.isBlank() || requestId == activeRequest) {
            activeRequest = ""
            conversation?.runCatching { cancelProcess() }
            main.removeCallbacks(idleShutdown)
            main.postDelayed(idleShutdown, IDLE_MILLIS)
        }
    }

    @Synchronized private fun loadEngine(): Engine {
        engine?.let { return it }
        val model = LocalSuggestionModel.file(this)
        if (!LocalSuggestionPreferences.enabled(this) || !model.isFile || model.length() != LocalSuggestionModel.SIZE) {
            throw IllegalStateException("The verified local model is not enabled.")
        }
        val cache = File(cacheDir, "local-llm").apply { mkdirs() }.absolutePath
        fun create(backend: Backend): Engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = backend, cacheDir = cache)).also { it.initialize() }
        val loaded = runCatching { create(Backend.GPU()) }.getOrElse { create(Backend.CPU()) }
        engine = loaded
        return loaded
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

class LocalSuggestionClient(
    private val context: Context,
    private val debugFakeOutput: String? = null
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private var bound = false
    private var requestId = ""
    private var callback: ((Result<List<String>>) -> Unit)? = null
    private val timeout = Runnable { finish(Result.failure(IllegalStateException("Local model timed out."))) }
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != LocalSuggestionService.MSG_RESULT) return super.handleMessage(message)
            val id = message.data.getString(LocalSuggestionService.KEY_REQUEST_ID).orEmpty()
            if (id != requestId) return
            val error = message.data.getString(LocalSuggestionService.KEY_ERROR).orEmpty()
            val output = message.data.getString(LocalSuggestionService.KEY_RESULT).orEmpty()
            if (error.isNotBlank()) finish(Result.failure(IllegalStateException(error)))
            else {
                val values = parseLocalSuggestions(output)
                finish(if (values.isEmpty()) Result.failure(IllegalStateException("Local model returned no usable suggestions.")) else Result.success(values))
            }
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            sendRequest()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (requestId.isNotBlank()) finish(Result.failure(IllegalStateException("Local model process disconnected.")))
        }
    }

    fun generate(prompt: String, onResult: (Result<List<String>>) -> Unit) {
        closeRequest()
        val useDebugFake = app.isDebuggableBuild() && debugFakeOutput != null
        if (!useDebugFake && !LocalSuggestionPreferences.enabled(app)) {
            onResult(Result.failure(IllegalStateException("Enable the verified local model in More first.")))
            return
        }
        requestId = UUID.randomUUID().toString()
        pendingPrompt = prompt
        callback = onResult
        main.postDelayed(timeout, 30_000L)
        val intent = Intent(app, LocalSuggestionService::class.java).apply {
            if (useDebugFake) putExtra(LocalSuggestionService.KEY_DEBUG_FAKE_OUTPUT, debugFakeOutput)
        }
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(Result.failure(IllegalStateException("Local model process could not start.")))
    }

    fun cancel() {
        val id = requestId
        if (id.isNotBlank()) runCatching {
            service?.send(Message.obtain(null, LocalSuggestionService.MSG_CANCEL).apply {
                data = Bundle().apply { putString(LocalSuggestionService.KEY_REQUEST_ID, id) }
            })
        }
        closeRequest()
    }

    override fun close() { cancel() }

    private fun sendRequest() {
        val id = requestId
        val pending = callback
        if (id.isBlank() || pending == null) return
        val prompt = pendingPrompt
        runCatching {
            service?.send(Message.obtain(null, LocalSuggestionService.MSG_GENERATE).apply {
                replyTo = incoming
                data = Bundle().apply {
                    putString(LocalSuggestionService.KEY_REQUEST_ID, id)
                    putString(LocalSuggestionService.KEY_PROMPT, prompt)
                }
            }) ?: throw IllegalStateException("Local model process is unavailable.")
        }.onFailure { finish(Result.failure(it)) }
    }

    private var pendingPrompt = ""

    private fun closeRequest() {
        main.removeCallbacks(timeout)
        requestId = ""
        callback = null
        pendingPrompt = ""
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service = null
    }

    private fun finish(result: Result<List<String>>) {
        val complete = callback ?: return
        closeRequest()
        complete(result)
    }
}

private fun Context.isDebuggableBuild(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
