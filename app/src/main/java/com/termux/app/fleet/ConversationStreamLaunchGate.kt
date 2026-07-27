package com.termux.app.fleet

/**
 * Serializes a controller's asynchronous conversation-stream launch.
 *
 * ProcessBuilder.start() runs off the main thread. Without a launch-in-progress
 * owner, two main-thread callbacks can both observe a null Process and launch
 * duplicate streams. The ticket also keeps a cancelled, slow launch from
 * clearing or replacing a newer launch.
 */
internal class ConversationStreamLaunchGate {
    private var nextTicket = 0L
    private var activeTicket: Long? = null

    @Synchronized
    fun begin(eligible: Boolean): Long? {
        if (!eligible || activeTicket != null) return null
        return (++nextTicket).also { activeTicket = it }
    }

    @Synchronized
    fun promote(ticket: Long, eligible: Boolean, register: () -> Unit): Boolean {
        if (activeTicket != ticket) return false
        if (!eligible) {
            activeTicket = null
            return false
        }
        register()
        return true
    }

    @Synchronized
    fun finish(ticket: Long, unregister: () -> Unit = {}) {
        if (activeTicket != ticket) return
        unregister()
        activeTicket = null
    }

    @Synchronized
    fun cancel(unregister: () -> Unit = {}) {
        unregister()
        activeTicket = null
    }
}
