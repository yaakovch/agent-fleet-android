package com.termux.app.fleet

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFleetCameraStagingTest {
    @Test
    fun restoresAValidDestinationBeforeTheCameraCreatesIt() {
        val cache = Files.createTempDirectory("agent-fleet-camera-cache").toFile()
        try {
            val root = File(cache, "agent-fleet-camera").apply { mkdirs() }
            val destination = File(root, "${UUID.randomUUID()}.jpg")

            assertEquals(
                destination.canonicalFile,
                AgentFleetCameraStaging.restore(cache, destination.absolutePath)
            )
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun restoresAnExistingRegularCameraOutput() {
        val cache = Files.createTempDirectory("agent-fleet-camera-cache").toFile()
        try {
            val destination = File(
                File(cache, "agent-fleet-camera").apply { mkdirs() },
                "${UUID.randomUUID()}.jpg"
            ).apply { writeText("jpeg") }

            assertEquals(
                destination.canonicalFile,
                AgentFleetCameraStaging.restore(cache, destination.absolutePath)
            )
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun rejectsOutsideMalformedAndSymbolicLinkDestinations() {
        val cache = Files.createTempDirectory("agent-fleet-camera-cache").toFile()
        try {
            val root = File(cache, "agent-fleet-camera").apply { mkdirs() }
            val outside = File(cache, "${UUID.randomUUID()}.jpg")
            val malformed = File(root, "capture.jpg")
            val target = File(root, "target.jpg").apply { writeText("jpeg") }
            val link = File(root, "${UUID.randomUUID()}.jpg")
            Files.createSymbolicLink(link.toPath(), target.toPath())

            listOf(outside, malformed, link).forEach { candidate ->
                assertThrows(IllegalArgumentException::class.java) {
                    AgentFleetCameraStaging.restore(cache, candidate.absolutePath)
                }
            }
        } finally {
            cache.deleteRecursively()
        }
    }
}
