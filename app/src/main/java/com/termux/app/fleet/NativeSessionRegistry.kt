package com.termux.app.fleet

import android.content.Context
import androidx.compose.runtime.RememberObserver

internal const val MAX_RETAINED_NATIVE_SESSIONS = 4

/** A retained controller/view holder owned by [NativeSessionRegistry]. */
internal interface RetainedNativeSession {
    fun onStart()
    fun onStop()
    fun close()
}

internal fun interface RetainedNativeSessionFactory<T : RetainedNativeSession> {
    /** Construction must bind the target but must not start foreground work. */
    fun create(target: String, host: NativeSessionHost): T
}

internal data class NativeSessionRegistryState(
    val entries: Int,
    val activeTargets: Set<String>,
    val runningTargets: Set<String>
)

/**
 * Activity-scoped owner for retained Native session controllers and their Compose state.
 *
 * An entry is active while one current lease owns its visual surface. Re-acquiring a target
 * supersedes the previous lease and refreshes the forwarding host without restarting the entry.
 * Releasing the current lease stops foreground work but retains the entry until deterministic
 * inactive-LRU eviction or registry destruction.
 */
internal class NativeSessionRegistry<T : RetainedNativeSession>(
    context: Context,
    private val maximumEntries: Int = MAX_RETAINED_NATIVE_SESSIONS
) {
    private data class Entry<T : RetainedNativeSession>(
        val target: String,
        val session: T,
        val host: ForwardingNativeSessionHost,
        var activeToken: Long,
        var lastTouchedSequence: Long,
        var running: Boolean = false
    )

    private data class ManagedSlot(
        val ownerToken: Long,
        val target: String,
        val leaseToken: Long
    )

    private val applicationContext = context.applicationContext
    private val entries = linkedMapOf<String, Entry<T>>()
    private val managedSlots = linkedMapOf<String, ManagedSlot>()
    private var foreground = false
    private var destroyed = false
    private var sequence = 0L

    init {
        require(maximumEntries in 1..MAX_RETAINED_NATIVE_SESSIONS) {
            "Native session registry capacity must be between 1 and $MAX_RETAINED_NATIVE_SESSIONS"
        }
    }

    fun acquire(
        target: String,
        delegate: NativeSessionHost,
        factory: RetainedNativeSessionFactory<T>
    ): NativeSessionLease<T> {
        validateTarget(target)
        check(!destroyed) { "Native session registry is destroyed" }
        val token = nextSequence()
        val existing = entries[target]
        if (existing != null) {
            existing.activeToken = token
            existing.lastTouchedSequence = token
            existing.host.update(token, delegate)
            if (foreground) start(existing)
            managedSlots.entries.removeAll { it.value.target == target }
            return NativeSessionLease(this, target, token)
        }

        val eviction = if (entries.size >= maximumEntries) {
            entries.values
                .asSequence()
                .filter { it.activeToken == INACTIVE_TOKEN }
                .minWithOrNull(compareBy<Entry<T>>({ it.lastTouchedSequence }, { it.target }))
                ?: error("All $maximumEntries retained Native sessions are active")
        } else {
            null
        }

        val host = ForwardingNativeSessionHost(applicationContext).also { it.update(token, delegate) }
        val session = try {
            factory.create(target, host)
        } catch (error: Throwable) {
            host.clear()
            throw error
        }
        if (eviction != null) evict(eviction)
        val entry = Entry(target, session, host, token, token)
        entries[target] = entry
        try {
            if (foreground) start(entry)
        } catch (error: Throwable) {
            entries.remove(target)
            host.clear()
            runCatching(session::close)
            throw error
        }
        managedSlots.entries.removeAll { it.value.target == target }
        return NativeSessionLease(this, target, token)
    }

    /**
     * Prepares a transactional Compose pane-slot assignment.
     *
     * Compose calculates a replacement before it forgets the previous remembered value. The
     * candidate is therefore kept outside the retained registry until [RememberObserver.onRemembered].
     * If composition is abandoned, only the candidate is closed and the committed pane remains
     * active. A committed replacement can use its prior pane as the capacity slot without an
     * acquire-before-dispose overflow.
     */
    fun manage(
        slot: String,
        target: String,
        delegate: NativeSessionHost,
        factory: RetainedNativeSessionFactory<T>,
        beforeStart: (T) -> Unit = {}
    ): ManagedNativeSessionLease<T> {
        validateSlot(slot)
        validateTarget(target)
        check(!destroyed) { "Native session registry is destroyed" }
        val expectedPrevious = managedSlots[slot]
        val existing = entries[target]
        if (existing == null && entries.size >= maximumEntries
            && entries.values.none { it.activeToken == INACTIVE_TOKEN }
            && expectedPrevious?.let { previous ->
                previous.target != target && entries[previous.target]?.activeToken == previous.leaseToken
            } != true
        ) {
            error("All $maximumEntries retained Native sessions are active")
        }

        val leaseToken = nextSequence()
        val ownerToken = nextSequence()
        val candidateHost: ForwardingNativeSessionHost
        val candidateSession: T
        val ownsCandidate: Boolean
        if (existing == null) {
            candidateHost = ForwardingNativeSessionHost(applicationContext).also {
                it.update(leaseToken, delegate)
            }
            val created = try {
                factory.create(target, candidateHost)
            } catch (error: Throwable) {
                candidateHost.clear()
                throw error
            }
            try {
                beforeStart(created)
            } catch (error: Throwable) {
                candidateHost.clear()
                runCatching(created::close)
                throw error
            }
            candidateSession = created
            ownsCandidate = true
        } else {
            candidateHost = existing.host
            candidateSession = existing.session
            ownsCandidate = false
        }
        var candidateClosed = false
        fun cleanupCandidate() {
            if (!ownsCandidate || candidateClosed) return
            candidateClosed = true
            candidateHost.clear()
            runCatching(candidateSession::close)
        }

        return ManagedNativeSessionLease(
            target = target,
            session = candidateSession,
            commit = {
                try {
                    commitManaged(
                        slot = slot,
                        target = target,
                        ownerToken = ownerToken,
                        leaseToken = leaseToken,
                        delegate = delegate,
                        expectedPrevious = expectedPrevious,
                        candidateHost = candidateHost,
                        candidateSession = candidateSession,
                        ownsCandidate = ownsCandidate,
                        beforeStart = beforeStart
                    )
                } catch (error: Throwable) {
                    cleanupCandidate()
                    throw error
                }
            },
            forget = { releaseManaged(slot, target, ownerToken) },
            abandon = ::cleanupCandidate
        )
    }

    private fun commitManaged(
        slot: String,
        target: String,
        ownerToken: Long,
        leaseToken: Long,
        delegate: NativeSessionHost,
        expectedPrevious: ManagedSlot?,
        candidateHost: ForwardingNativeSessionHost,
        candidateSession: T,
        ownsCandidate: Boolean,
        beforeStart: (T) -> Unit
    ) {
        check(!destroyed) { "Native session registry is destroyed" }
        val currentPrevious = managedSlots[slot]
        check(currentPrevious == null || currentPrevious == expectedPrevious) {
            "Native session registry slot changed during composition"
        }
        val replacedEntry = currentPrevious
            ?.takeIf { it.target != target }
            ?.let { assignment ->
                entries[assignment.target]?.takeIf { it.activeToken == assignment.leaseToken }
            }
        val existing = entries[target]
        if (ownsCandidate) {
            check(existing == null) { "Native session target changed during composition" }
            val eviction = if (entries.size >= maximumEntries) {
                entries.values
                    .asSequence()
                    .filter { it.activeToken == INACTIVE_TOKEN }
                    .minWithOrNull(compareBy<Entry<T>>({ it.lastTouchedSequence }, { it.target }))
                    ?: replacedEntry
                    ?: error("All $maximumEntries retained Native sessions are active")
            } else {
                null
            }
            val entry = Entry(target, candidateSession, candidateHost, leaseToken, leaseToken)
            if (foreground) start(entry)
            try {
                if (replacedEntry != null) deactivate(replacedEntry)
                if (eviction != null) evict(eviction)
                entries[target] = entry
            } catch (error: Throwable) {
                runCatching { stop(entry) }
                throw error
            }
        } else {
            check(existing?.session === candidateSession && existing.host === candidateHost) {
                "Native session target changed during composition"
            }
            beforeStart(candidateSession)
            if (replacedEntry != null) deactivate(replacedEntry)
            existing.activeToken = leaseToken
            existing.lastTouchedSequence = leaseToken
            existing.host.update(leaseToken, delegate)
            if (foreground) start(existing)
        }
        managedSlots.entries.removeAll { (key, value) -> key == slot || value.target == target }
        managedSlots[slot] = ManagedSlot(ownerToken, target, leaseToken)
    }

    fun onActivityStart() {
        check(!destroyed) { "Native session registry is destroyed" }
        if (foreground) return
        foreground = true
        entries.values.forEach { entry ->
            if (entry.activeToken != INACTIVE_TOKEN) start(entry)
        }
    }

    fun onActivityStop() {
        if (destroyed || !foreground) return
        foreground = false
        entries.values.forEach(::stop)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        foreground = false
        val retained = entries.values.toList()
        entries.clear()
        managedSlots.clear()
        retained.forEach { entry ->
            entry.running = false
            entry.host.clear()
            entry.session.close()
        }
    }

    fun state(): NativeSessionRegistryState = NativeSessionRegistryState(
        entries = entries.size,
        activeTargets = entries.values
            .filter { it.activeToken != INACTIVE_TOKEN }
            .mapTo(linkedSetOf()) { it.target },
        runningTargets = entries.values
            .filter { it.running }
            .mapTo(linkedSetOf()) { it.target }
    )

    internal fun release(target: String, token: Long) {
        if (destroyed) return
        val entry = entries[target] ?: return
        if (entry.activeToken != token) return
        entry.activeToken = INACTIVE_TOKEN
        entry.lastTouchedSequence = nextSequence()
        entry.host.clear(token)
        stop(entry)
    }

    internal fun releaseManaged(slot: String, target: String, ownerToken: Long) {
        if (destroyed) return
        val assignment = managedSlots[slot]
            ?.takeIf { it.ownerToken == ownerToken && it.target == target }
            ?: return
        managedSlots.remove(slot)
        release(assignment.target, assignment.leaseToken)
    }

    internal fun session(target: String): T = checkNotNull(entries[target]?.session) {
        "Retained Native session is no longer available"
    }

    private fun start(entry: Entry<T>) {
        if (entry.running || entry.activeToken == INACTIVE_TOKEN) return
        entry.running = true
        try {
            entry.session.onStart()
        } catch (error: Throwable) {
            entry.running = false
            throw error
        }
    }

    private fun stop(entry: Entry<T>) {
        if (!entry.running) return
        entry.running = false
        entry.session.onStop()
    }

    private fun deactivate(entry: Entry<T>) {
        if (entry.activeToken == INACTIVE_TOKEN) return
        val token = entry.activeToken
        entry.activeToken = INACTIVE_TOKEN
        entry.lastTouchedSequence = nextSequence()
        entry.host.clear(token)
        stop(entry)
    }

    private fun evict(entry: Entry<T>) {
        check(entry.activeToken == INACTIVE_TOKEN && !entry.running) {
            "Only an inactive Native session may be evicted"
        }
        check(entries.remove(entry.target) === entry) { "Native session eviction target changed" }
        entry.host.clear()
        entry.session.close()
    }

    private fun nextSequence(): Long {
        sequence = Math.addExact(sequence, 1L)
        return sequence
    }

    private fun validateTarget(target: String) {
        require(target.isNotBlank() && target.length <= MAX_TARGET_CHARACTERS && target.none(Char::isISOControl)) {
            "Native session registry target is invalid"
        }
    }

    private fun validateSlot(slot: String) {
        require(slot.isNotBlank() && slot.length <= MAX_SLOT_CHARACTERS && slot.none(Char::isISOControl)) {
            "Native session registry slot is invalid"
        }
    }

    private companion object {
        const val INACTIVE_TOKEN = 0L
        const val MAX_SLOT_CHARACTERS = 256
        const val MAX_TARGET_CHARACTERS = 4_096
    }
}

internal class NativeSessionLease<T : RetainedNativeSession> internal constructor(
    private val registry: NativeSessionRegistry<T>,
    val target: String,
    internal val token: Long
) : AutoCloseable {
    private var released = false

    /** Resolve once while building the AndroidView; the lease itself does not retain the view/controller. */
    val session: T get() = registry.session(target)

    fun release() {
        if (released) return
        released = true
        registry.release(target, token)
    }

    override fun close() = release()
}

internal class ManagedNativeSessionLease<T : RetainedNativeSession> internal constructor(
    val target: String,
    val session: T,
    private val commit: () -> Unit,
    private val forget: () -> Unit,
    private val abandon: () -> Unit
) : RememberObserver, AutoCloseable {
    private var remembered = false
    private var released = false

    override fun onRemembered() {
        if (released || remembered) return
        commit()
        remembered = true
    }

    override fun onForgotten() = release()

    override fun onAbandoned() {
        if (released) return
        released = true
        if (remembered) forget() else abandon()
    }

    fun release() {
        if (released) return
        released = true
        if (remembered) forget() else abandon()
    }

    override fun close() = release()
}

private class ForwardingNativeSessionHost(
    override val nativeContext: Context
) : NativeSessionHost {
    private data class Delegate(val token: Long, val host: NativeSessionHost)

    @Volatile private var delegate: Delegate? = null

    override val nativeInlineComposer: Boolean get() = delegate?.host?.nativeInlineComposer == true

    fun update(token: Long, host: NativeSessionHost) {
        delegate = Delegate(token, host)
    }

    fun clear(token: Long) {
        if (delegate?.token == token) delegate = null
    }

    fun clear() {
        delegate = null
    }

    override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean): Boolean =
        delegate?.host?.sendAgentFleetComposerText(text, appendEnter) == true

    override fun sendAgentFleetControlC(): Boolean = delegate?.host?.sendAgentFleetControlC() == true

    override fun sendAgentFleetKey(key: String): Boolean = delegate?.host?.sendAgentFleetKey(key) == true

    override fun pickAgentFleetImages() {
        delegate?.host?.pickAgentFleetImages()
    }

    override fun pickAgentFleetCamera() {
        delegate?.host?.pickAgentFleetCamera()
    }

    override fun setAgentFleetNativeView(
        nativeAvailable: Boolean,
        nativeView: Boolean,
        automaticTerminal: Boolean,
        aiComposer: Boolean
    ) {
        delegate?.host?.setAgentFleetNativeView(nativeAvailable, nativeView, automaticTerminal, aiComposer)
    }

    override fun closeAgentFleetSessionTab() {
        delegate?.host?.closeAgentFleetSessionTab()
    }
}
