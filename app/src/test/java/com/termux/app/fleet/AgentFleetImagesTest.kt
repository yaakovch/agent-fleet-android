package com.termux.app.fleet

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentFleetImagesTest {
    @Test
    fun recognizesFormatsFromBytesInsteadOfProviderMime() {
        assertEquals("png", detectAgentFleetImageExtension(PNG))
        assertEquals("jpg", detectAgentFleetImageExtension(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)))
        assertEquals("webp", detectAgentFleetImageExtension("RIFF0000WEBPVP8 ".toByteArray()))
        assertEquals(null, detectAgentFleetImageExtension("not an image".toByteArray()))
    }

    @Test
    fun importsSupportedImageWhenContentProviderOmitsMime() {
        val directory = Files.createTempDirectory("agent-fleet-images").toFile()

        val output = importAgentFleetImage(ByteArrayInputStream(PNG), null, directory)

        assertTrue(output.name.endsWith(".png"))
        assertArrayEquals(PNG, output.readBytes())
    }

    @Test
    fun rejectsOversizeInputAndRemovesTemporaryCopy() {
        val directory = Files.createTempDirectory("agent-fleet-images").toFile()
        try {
            importAgentFleetImage(ByteArrayInputStream(PNG), "image/png", directory, maxBytes = 4)
            fail("oversize input was accepted")
        } catch (error: AgentFleetImageException) {
            assertTrue(error.message.orEmpty().contains("smaller than 20 MB"))
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun parsesPlainWtmuxOutputWithoutJson() {
        assertEquals(
            ".wtmux/images/2026-07-17-a1b2c3d4.png",
            parseAgentFleetImagePath("status line\n.wtmux/images/2026-07-17-a1b2c3d4.png\n")
        )
    }

    @Test
    fun mapsUsefulUploadFailuresWithoutLeakingPaths() {
        assertEquals(
            "Image destination is not configured. Refresh sessions and retry.",
            agentFleetImageUploadFailure("[wtmux][error] unknown host: gaming", 1)
        )
        assertEquals(
            "The host rejected the image connection. Refresh pairing and retry.",
            agentFleetImageUploadFailure("ssh: Permission denied (publickey).", 255)
        )
        assertEquals(
            "Image upload failed (wtmux exit 9). Retry after refreshing the session.",
            agentFleetImageUploadFailure("failure at /data/user/0/private/path", 9)
        )
        assertEquals(
            "Image upload needs the missing 'sha256sum' command. Repair the runtime and retry.",
            agentFleetImageUploadFailure("line 38: sha256sum: command not found", 127)
        )
        assertEquals(
            "The host rejected the selected image destination. Refresh the session and retry.",
            agentFleetImageUploadFailure("[wtmux][error] TRANSFER_REJECTED", 1)
        )
        assertEquals(
            "The stable host runtime is unavailable. Refresh the host setup and retry.",
            agentFleetImageUploadFailure("bash: /home/user/.local/bin/wtmux-host-runtime: command not found", 127)
        )
        assertEquals(
            "The image transfer command is unavailable on the phone or host. Repair the runtime, refresh the session, and retry.",
            agentFleetImageUploadFailure("", 127)
        )
        assertEquals(
            "The image connection was lost. Refresh the session and retry.",
            agentFleetImageUploadFailure("", 255)
        )
        assertEquals(
            "The host could not be reached. Check the connection and retry.",
            agentFleetImageUploadFailure("client_loop: send disconnect: Broken pipe", 255)
        )
    }

    @Test
    fun retriesOnlyOneTransientImageTransferFailure() {
        assertTrue(shouldRetryAgentFleetImageUpload(AgentFleetProcessOutput(255, "", "Broken pipe"), 0))
        assertTrue(shouldRetryAgentFleetImageUpload(AgentFleetProcessOutput(127, "", ""), 0))
        assertTrue(shouldRetryAgentFleetImageUpload(AgentFleetProcessOutput(1, "", "connection reset"), 0))
        assertTrue(!shouldRetryAgentFleetImageUpload(AgentFleetProcessOutput(255, "", "Broken pipe"), 1))
        assertTrue(!shouldRetryAgentFleetImageUpload(AgentFleetProcessOutput(9, "", "invalid request"), 0))
    }

    @Test
    fun imageToolPreflightNamesEveryMissingRuntimeCommand() {
        val bin = Files.createTempDirectory("agent-fleet-image-tools").toFile()
        assertEquals(AGENT_FLEET_IMAGE_TOOL_PACKAGES.keys.toList(), missingAgentFleetImageTools(bin))
        AGENT_FLEET_IMAGE_TOOL_PACKAGES.keys.forEach { name ->
            java.io.File(bin, name).apply { writeText("tool"); setExecutable(true) }
        }
        assertTrue(missingAgentFleetImageTools(bin).isEmpty())
        assertEquals(
            setOf("coreutils", "gawk", "openssh"),
            agentFleetImageToolPackageNames(listOf("sha256sum", "mktemp", "awk", "ssh"))
        )
    }

    @Test(timeout = 10_000)
    fun processCollectorDrainsLargeStderrWithoutDeadlock() {
        val process = ProcessBuilder(
            "/bin/sh", "-c",
            "i=0; while [ \$i -lt 12000 ]; do printf 'diagnostic-line-xxxxxxxx\\n' >&2; i=\$((i+1)); done; exit 7"
        ).start()

        val output = collectAgentFleetProcess(process, 5)

        assertEquals(7, output.exitCode)
        assertTrue(output.stderr.startsWith("diagnostic-line"))
        assertTrue(output.stderr.length <= 64 * 1024)
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00
        )
    }
}
