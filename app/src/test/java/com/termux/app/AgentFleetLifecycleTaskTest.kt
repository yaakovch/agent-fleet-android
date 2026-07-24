package com.termux.app

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFleetLifecycleTaskTest {
    @Test
    fun acceptsWorkWhileActivityExecutorIsOpen() {
        val executor = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(1)
        try {
            assertTrue(executor.executeLifecycleTask { completed.countDown() })
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun ignoresAConcurrentExecutorShutdown() {
        assertFalse(RejectingExecutor().executeLifecycleTask { error("must not run") })
    }

    private class RejectingExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("activity destroyed")
        }

        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }
}
