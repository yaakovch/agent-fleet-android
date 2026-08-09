package com.termux.app.fleet

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal data class NativeOneShotProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean
)

internal class NativeOneShotProcessOwner(
    private val stdoutLimitBytes: Int = 512 * 1024,
    private val stderrLimitBytes: Int = 64 * 1024,
    private val cleanupTimeoutMillis: Long = 2_000,
    private val gracefulStopMillis: Long = 200
) {
    internal class Ticket internal constructor(
        internal val id: Long,
        internal val epoch: Long,
        internal val deadlineNanos: Long
    )

    private class RunningProcess(
        val process: Process,
        val stdout: BoundedBytes,
        val stderr: BoundedBytes
    ) {
        val cancelled = AtomicBoolean()
        lateinit var stdoutReader: Thread
        lateinit var stderrReader: Thread
    }

    private class BoundedBytes(maximum: Int) {
        private val bytes = ByteArray(maximum)
        private var size = 0

        @Volatile
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(source: ByteArray, count: Int) {
            val copyCount = minOf(count, bytes.size - size)
            if (copyCount > 0) {
                source.copyInto(bytes, destinationOffset = size, startIndex = 0, endIndex = copyCount)
                size += copyCount
            }
            if (copyCount < count) truncated = true
        }

        @Synchronized
        fun text(): String = String(bytes, 0, size, Charsets.UTF_8)
    }

    private val lock = Any()
    private var epoch = 0L
    private var nextTicketId = 0L
    private val active = mutableMapOf<Long, RunningProcess>()

    init {
        require(stdoutLimitBytes >= 0) { "stdoutLimitBytes must not be negative" }
        require(stderrLimitBytes >= 0) { "stderrLimitBytes must not be negative" }
        require(cleanupTimeoutMillis > 0) { "cleanupTimeoutMillis must be positive" }
        require(gracefulStopMillis >= 0) { "gracefulStopMillis must not be negative" }
    }

    fun begin(timeout: Long, unit: TimeUnit): Ticket {
        val now = System.nanoTime()
        val duration = unit.toNanos(timeout).coerceAtLeast(0)
        val deadline = if (duration > Long.MAX_VALUE - now) Long.MAX_VALUE else now + duration
        return synchronized(lock) {
            Ticket(++nextTicketId, epoch, deadline)
        }
    }

    fun collect(ticket: Ticket, process: Process): NativeOneShotProcessResult {
        val running = RunningProcess(
            process = process,
            stdout = BoundedBytes(stdoutLimitBytes),
            stderr = BoundedBytes(stderrLimitBytes)
        )
        running.stdoutReader = readerThread(
            name = "native-session-action-stdout",
            input = process.inputStream,
            output = running.stdout
        )
        running.stderrReader = readerThread(
            name = "native-session-action-stderr",
            input = process.errorStream,
            output = running.stderr
        )

        if (!register(ticket, running)) {
            running.cancelled.set(true)
            stopAndJoin(running)
            return NativeOneShotProcessResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                cancelled = true,
                stdoutTruncated = false,
                stderrTruncated = false
            )
        }

        return try {
            // One-shot commands never receive input. Closing stdin before the
            // wait lets commands that read until EOF complete normally.
            process.outputStream.close()
            running.stdoutReader.start()
            running.stderrReader.start()

            val processFinished = waitForUntil(process, ticket.deadlineNanos)
            val readersFinished = processFinished &&
                joinUntil(running.stdoutReader, ticket.deadlineNanos) &&
                joinUntil(running.stderrReader, ticket.deadlineNanos)
            val cancelled = running.cancelled.get()
            val timedOut = !cancelled && (!processFinished || !readersFinished)

            if (cancelled || timedOut) {
                stopAndJoin(running)
            }

            val exitCode = if (!cancelled && !timedOut && !process.isAliveCompat()) {
                runCatching { process.exitValue() }.getOrDefault(-1)
            } else {
                -1
            }
            NativeOneShotProcessResult(
                exitCode = exitCode,
                stdout = running.stdout.text(),
                stderr = running.stderr.text(),
                timedOut = timedOut,
                cancelled = cancelled,
                stdoutTruncated = running.stdout.truncated,
                stderrTruncated = running.stderr.truncated
            )
        } catch (error: Throwable) {
            stopAndJoin(running)
            throw error
        } finally {
            closePipes(running.process)
            finish(ticket, running)
        }
    }

    fun cancelAll() {
        val processes = synchronized(lock) {
            epoch++
            active.values.toList()
        }
        processes.forEach { running ->
            running.cancelled.set(true)
            signalCancellation(running)
        }
    }

    private fun register(ticket: Ticket, running: RunningProcess): Boolean = synchronized(lock) {
        if (ticket.epoch != epoch || active.containsKey(ticket.id)) {
            false
        } else {
            active[ticket.id] = running
            true
        }
    }

    private fun finish(ticket: Ticket, running: RunningProcess) {
        synchronized(lock) {
            if (active[ticket.id] === running) active.remove(ticket.id)
        }
    }

    private fun readerThread(name: String, input: InputStream, output: BoundedBytes): Thread =
        thread(name = name, isDaemon = true, start = false) {
            input.use { stream ->
                val chunk = ByteArray(8 * 1024)
                try {
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        if (count > 0) output.append(chunk, count)
                    }
                } catch (_: IOException) {
                    // Closing an Android Process pipe during exit or cancellation is EOF.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

    private fun signalCancellation(running: RunningProcess) {
        runCatching { running.process.destroy() }
        if (running.process.isAliveCompat()) runCatching { running.process.destroyForciblyCompat() }
        closePipes(running.process)
        running.stdoutReader.interrupt()
        running.stderrReader.interrupt()
    }

    private fun stopAndJoin(running: RunningProcess) {
        val cleanupDeadline = deadlineAfter(cleanupTimeoutMillis, TimeUnit.MILLISECONDS)
        runCatching { running.process.destroy() }
        if (running.process.isAliveCompat() && gracefulStopMillis > 0) {
            waitForUntil(
                running.process,
                minOf(cleanupDeadline, deadlineAfter(gracefulStopMillis, TimeUnit.MILLISECONDS))
            )
        }
        if (running.process.isAliveCompat()) runCatching { running.process.destroyForciblyCompat() }
        closePipes(running.process)
        if (running.process.isAliveCompat()) waitForUntil(running.process, cleanupDeadline)
        running.stdoutReader.interrupt()
        running.stderrReader.interrupt()
        joinUntil(running.stdoutReader, cleanupDeadline)
        joinUntil(running.stderrReader, cleanupDeadline)
        if (running.process.isAliveCompat()) runCatching { running.process.destroyForciblyCompat() }
    }

    private fun closePipes(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun waitForUntil(process: Process, deadlineNanos: Long): Boolean {
        if (!process.isAliveCompat()) return true
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return false
        return runCatching { process.waitForCompat(remaining, TimeUnit.NANOSECONDS) }.getOrDefault(false)
    }

    private fun joinUntil(reader: Thread, deadlineNanos: Long): Boolean {
        if (!reader.isAlive) return true
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return false
        val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
        val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
        runCatching { reader.join(millis, nanos) }
        return !reader.isAlive
    }

    private fun deadlineAfter(value: Long, unit: TimeUnit): Long {
        val now = System.nanoTime()
        val duration = unit.toNanos(value).coerceAtLeast(0)
        return if (duration > Long.MAX_VALUE - now) Long.MAX_VALUE else now + duration
    }
}
