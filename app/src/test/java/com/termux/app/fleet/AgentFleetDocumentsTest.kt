package com.termux.app.fleet

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentFleetDocumentsTest {
    @Test fun preservesTinyTextUnicodeAndBinaryDocumentsWithoutImageConversion() {
        val directory = Files.createTempDirectory("fleet-documents").toFile()
        try {
            for ((name, bytes) in listOf("Plan.md" to "# שלום 🙂\r\n".toByteArray(),
                "tiny.txt" to byteArrayOf(72, 105), "data.csv" to "a,b\n1,2\n".toByteArray(),
                "report.pdf" to byteArrayOf(37, 80, 68, 70, 0, -1), "report.docx" to byteArrayOf(80, 75, 0, -1))) {
                val file = importAgentFleetDocument(ByteArrayInputStream(bytes), name, directory)
                assertTrue(file.name.endsWith(name))
                assertArrayEquals(bytes, file.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun refusesEmptyOversizedAndUnsupportedSelectionsWithoutPartialFiles() {
        val directory = Files.createTempDirectory("fleet-document-failures").toFile()
        try {
            for ((name, bytes, max) in listOf(Triple("empty.txt", byteArrayOf(), 20L),
                Triple("big.md", ByteArray(21), 20L), Triple("program.exe", byteArrayOf(1, 2), 20L))) {
                assertThrows(IllegalArgumentException::class.java) {
                    importAgentFleetDocument(ByteArrayInputStream(bytes), name, directory, max)
                }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsAbsoluteNestedMultilineWrongKindAndTruncatedHostReceipts() {
        val valid = ".wtmux/attachments/Plan-abc.md\n"
        assertEquals(valid.trimEnd(), parseSuccessfulAgentFleetAttachmentOutput(AgentFleetProcessOutput(0, valid, "")))
        for (path in listOf("/project/$valid", ".wtmux/attachments/nested/file.md\n", "$valid$valid",
            ".wtmux/attachments/file.exe\n", ".wtmux/images/file.md\n", ".wtmux/attachments/file.md\r\nnoise")) {
            assertThrows(AgentFleetImageException::class.java) {
                parseSuccessfulAgentFleetAttachmentOutput(AgentFleetProcessOutput(0, path, ""))
            }
        }
        assertThrows(AgentFleetImageException::class.java) {
            parseSuccessfulAgentFleetAttachmentOutput(AgentFleetProcessOutput(0, valid, "", stdoutTruncated = true))
        }
    }
}
