package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeOneShotProcessOwnerTest {
    @Test
    fun completedProcessCollectsBothPipesWithinTheDeadline() {
        val process = TestProcess(
            stdout = ByteArrayInputStream("stdout".toByteArray()),
            stderr = ByteArrayInputStream("stderr".toByteArray()),
            initiallyAlive = false
        )
        val owner = NativeOneShotProcessOwner()

        val result = owner.collect(owner.begin(1, TimeUnit.SECONDS), process)

        assertEquals(0, result.exitCode)
        assertEquals("stdout", result.stdout)
        assertEquals("stderr", result.stderr)
        assertFalse(result.timedOut)
        assertFalse(result.cancelled)
        assertFalse(result.stdoutTruncated)
        assertFalse(result.stderrTruncated)
        assertEquals(0, process.destroyCalls.get())
        assertTrue(process.stdinClosed.get())
    }

    @Test
    fun closesStdinBeforeWaitingSoAnEofDrivenProcessCanExit() {
        val process = TestProcess(
            stdout = ByteArrayInputStream("done".toByteArray()),
            stderr = ByteArrayInputStream(ByteArray(0)),
            initiallyAlive = true,
            exitOnStdinClose = true
        )
        val owner = NativeOneShotProcessOwner()

        val result = owner.collect(owner.begin(1, TimeUnit.SECONDS), process)

        assertTrue(process.stdinClosed.get())
        assertFalse(result.timedOut)
        assertFalse(result.cancelled)
        assertEquals(0, result.exitCode)
        assertEquals("done", result.stdout)
        assertEquals(0, process.destroyCalls.get())
    }

    @Test
    fun silentLivingProcessTimesOutEscalatesAndJoinsReaders() {
        val stdout = BlockingInputStream()
        val stderr = BlockingInputStream()
        val process = TestProcess(stdout, stderr, initiallyAlive = true, gracefulDestroyExits = false)
        val owner = NativeOneShotProcessOwner(cleanupTimeoutMillis = 1_000, gracefulStopMillis = 20)

        val result = owner.collect(owner.begin(80, TimeUnit.MILLISECONDS), process)

        assertTrue(result.timedOut)
        assertFalse(result.cancelled)
        assertEquals(-1, result.exitCode)
        assertTrue(process.destroyCalls.get() >= 2)
        assertFalse(process.isAlive)
        assertTrue(process.stdinClosed.get())
        assertTrue(stdout.closed.get())
        assertTrue(stderr.closed.get())
        assertEquals(0, stdout.activeReaders.get())
        assertEquals(0, stderr.activeReaders.get())
    }

    @Test
    fun endlessOutputIsDiscardedWithoutGrowingPastTheConfiguredLimit() {
        val stdout = RepeatingInputStream("0123456789abcdef".toByteArray())
        val stderr = BlockingInputStream()
        val process = TestProcess(stdout, stderr, initiallyAlive = true, gracefulDestroyExits = false)
        val owner = NativeOneShotProcessOwner(
            stdoutLimitBytes = 1_024,
            stderrLimitBytes = 128,
            cleanupTimeoutMillis = 1_000,
            gracefulStopMillis = 20
        )

        val result = owner.collect(owner.begin(80, TimeUnit.MILLISECONDS), process)

        assertTrue(result.timedOut)
        assertTrue(result.stdoutTruncated)
        assertEquals(1_024, result.stdout.toByteArray(Charsets.UTF_8).size)
        assertEquals(0, stdout.activeReaders.get())
        assertEquals(0, stderr.activeReaders.get())
    }

    @Test
    fun timeoutAlsoCoversPipeDrainAfterTheProcessHasExited() {
        val stdout = BlockingInputStream()
        val stderr = BlockingInputStream()
        val process = TestProcess(stdout, stderr, initiallyAlive = false)
        val owner = NativeOneShotProcessOwner(cleanupTimeoutMillis = 1_000, gracefulStopMillis = 20)

        val result = owner.collect(owner.begin(80, TimeUnit.MILLISECONDS), process)

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(stdout.closed.get())
        assertTrue(stderr.closed.get())
        assertEquals(0, stdout.activeReaders.get())
        assertEquals(0, stderr.activeReaders.get())
    }

    @Test
    fun cancellationStopsAnOwnedProcessAndUnblocksItsCollector() {
        val stdout = BlockingInputStream()
        val stderr = BlockingInputStream()
        val process = TestProcess(stdout, stderr, initiallyAlive = true, gracefulDestroyExits = false)
        val owner = NativeOneShotProcessOwner(cleanupTimeoutMillis = 1_000, gracefulStopMillis = 20)
        val result = AtomicReference<NativeOneShotProcessResult>()
        val collector = Thread {
            result.set(owner.collect(owner.begin(30, TimeUnit.SECONDS), process))
        }
        collector.start()
        assertTrue(stdout.readerStarted.await(1, TimeUnit.SECONDS))
        assertTrue(stderr.readerStarted.await(1, TimeUnit.SECONDS))

        owner.cancelAll()
        collector.join(2_000)

        assertFalse(collector.isAlive)
        assertTrue(result.get().cancelled)
        assertFalse(result.get().timedOut)
        assertTrue(process.destroyCalls.get() >= 2)
        assertEquals(0, stdout.activeReaders.get())
        assertEquals(0, stderr.activeReaders.get())
    }

    @Test
    fun cancellationInvalidatesAProcessThatFinishesStartingLater() {
        val stdout = BlockingInputStream()
        val stderr = BlockingInputStream()
        val process = TestProcess(stdout, stderr, initiallyAlive = true, gracefulDestroyExits = false)
        val owner = NativeOneShotProcessOwner(cleanupTimeoutMillis = 1_000, gracefulStopMillis = 20)
        val ticket = owner.begin(30, TimeUnit.SECONDS)

        owner.cancelAll()
        val result = owner.collect(ticket, process)

        assertTrue(result.cancelled)
        assertFalse(result.timedOut)
        assertTrue(process.destroyCalls.get() >= 2)
        assertTrue(stdout.closed.get())
        assertTrue(stderr.closed.get())
        assertEquals(0, stdout.activeReaders.get())
        assertEquals(0, stderr.activeReaders.get())
    }

    private class BlockingInputStream : InputStream() {
        val readerStarted = CountDownLatch(1)
        val activeReaders = AtomicInteger()
        val closed = AtomicBoolean()
        private val lock = Object()

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readerStarted.countDown()
            activeReaders.incrementAndGet()
            try {
                synchronized(lock) {
                    while (!closed.get()) lock.wait()
                }
                return -1
            } finally {
                activeReaders.decrementAndGet()
            }
        }

        override fun close() {
            closed.set(true)
            synchronized(lock) {
                lock.notifyAll()
            }
        }
    }

    private class RepeatingInputStream(private val value: ByteArray) : InputStream() {
        val activeReaders = AtomicInteger()
        private val closed = AtomicBoolean()

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed.get()) return -1
            activeReaders.incrementAndGet()
            return try {
                val count = minOf(length, value.size)
                value.copyInto(buffer, offset, 0, count)
                count
            } finally {
                activeReaders.decrementAndGet()
            }
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class TestProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
        initiallyAlive: Boolean,
        private val gracefulDestroyExits: Boolean = true,
        private val exitOnStdinClose: Boolean = false
    ) : Process() {
        private val alive = AtomicBoolean(initiallyAlive)
        val destroyCalls = AtomicInteger()
        val stdinClosed = AtomicBoolean()
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() {
                stdinClosed.set(true)
                if (exitOnStdinClose) alive.set(false)
                super.close()
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            while (alive.get()) Thread.sleep(5)
            return 0
        }

        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            val calls = destroyCalls.incrementAndGet()
            if (gracefulDestroyExits || calls >= 2) alive.set(false)
        }
    }
}
