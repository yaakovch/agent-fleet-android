package com.termux.app.fleet

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ConversationEventBatch(val frames: List<ConversationFrame.Event>, val items: List<ConversationItem>)

internal fun reduceConversationEvents(frames: List<ConversationFrame.Event>) =
    ConversationEventBatch(frames.toList(), mergeConversationItems(emptyList(), frames.map { it.item }))

/** One ordered worker per stream binding; ordinary content publishes at vsync. */
internal class ConversationFramePublisher(
    private val frame: (ConversationFrame) -> Unit,
    private val batch: (ConversationEventBatch) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val events = mutableListOf<ConversationFrame.Event>()
    private var provider: ProviderState? = null
    private var flushScheduled = false
    // Only the main thread touches publications and frameScheduled.
    private val publications = ArrayDeque<() -> Unit>()
    private var frameScheduled = false

    fun enqueue(value: ConversationFrame) {
        worker.execute {
            val incoming = when (value) {
                is ConversationFrame.Snapshot -> value.providerState
                is ConversationFrame.Event -> value.providerState
                is ConversationFrame.Status -> value.providerState
                else -> null
            }
            val authorityChanged = incoming != null && (incoming.mutationsAllowed != provider?.mutationsAllowed ||
                incoming.confidence != provider?.confidence || incoming.reasonCode != provider?.reasonCode)
            if (incoming != null) provider = incoming
            val urgent = value !is ConversationFrame.Event || authorityChanged ||
                value.item.kind in setOf("question", "approval", "error", "fallback") || value.item.state == "error"
            if (urgent) {
                flushEvents()
                publish(true) { frame(value) }
            } else {
                events += value as ConversationFrame.Event
                if (events.size >= 256) flushEvents()
                else if (!flushScheduled) {
                    flushScheduled = true
                    worker.schedule({ flushScheduled = false; flushEvents() }, 8, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    fun finish(complete: () -> Unit) = worker.execute { flushEvents(); publish(true, complete) }

    private fun flushEvents() {
        if (events.isEmpty()) return
        val reduced = reduceConversationEvents(events)
        events.clear()
        publish(false) { batch(reduced) }
    }

    private fun publish(immediate: Boolean, apply: () -> Unit) {
        main.post {
            publications.addLast(apply)
            if (immediate) drain()
            else if (!frameScheduled) {
                frameScheduled = true
                Choreographer.getInstance().postFrameCallback { frameScheduled = false; drain() }
            }
        }
    }

    private fun drain() {
        Snapshot.withMutableSnapshot {
            while (publications.isNotEmpty()) publications.removeFirst().invoke()
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "native-frame-reducer").apply { isDaemon = true }
        }
    }
}
