package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostFileReferenceTest {
    @Test fun canonicalFixturesMatchEveryClient() {
        fun fixture(name: String) = JSONObject(requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() })
        val golden = fixture("host-file-behavior-v1.json")
        val targets = golden.getJSONArray("targets")
        repeat(targets.length()) { index ->
            val row = targets.getJSONObject(index)
            assertEquals(row.getString("value"), if (row.isNull("target")) null else row.getString("target"), HostFileReferences.target(row.getString("value"), row.getBoolean("explicit")))
        }
        val extractions = golden.getJSONArray("extractions")
        repeat(extractions.length()) { index ->
            val row = extractions.getJSONObject(index); val expected = row.getJSONArray("targets")
            assertEquals((0 until expected.length()).map { expected.getString(it) }, HostFileReferences.extract(row.getString("text")).map { it.target })
        }
        val terminalRows = golden.getJSONArray("terminalRows")
        repeat(terminalRows.length()) { index ->
            val test = terminalRows.getJSONObject(index)
            val raw = test.getJSONArray("rows")
            val rows = (0 until raw.length()).map { val row = raw.getJSONObject(it); HostFileRow(row.getString("text"), row.optBoolean("wrapped")) }
            val targets = test.getJSONArray("targets")
            assertEquals(test.getString("name"), (0 until targets.length()).map { targets.getString(it) }, HostFileRowReferences.extract(rows).map { it.target })
        }
        assertTrue(HostFileRowReferences.extract(listOf(HostFileRow("x".repeat(8193)))).isEmpty())
        assertTrue(HostFileRowReferences.extract(listOf(HostFileRow("(" + "/a".repeat(2048) + ")"))).isEmpty())
        assertEquals("report.pdf", HostFileMetadata.parse(fixture("contracts/linked-file-v1.json")).name)
        assertTrue(runCatching { HostFileMetadata.parse(fixture("contracts/linked-file-unknown-field-v1.json")) }.isFailure)
    }
    @Test fun pathsKeepTheirOriginatingHostSyntax() {
        assertEquals(listOf("/tmp/report.pdf", "./reports/a b.html", "C:\\out\\result.png", "file:///home/me/a.txt"),
            HostFileReferences.extract("See /tmp/report.pdf, \"./reports/a b.html\" and C:\\out\\result.png then file:///home/me/a.txt").map { it.target })
        assertTrue(HostFileReferences.extract("https://example.com/private/file.txt and normal words").isEmpty())
    }
    @Test fun authoritiesAndMalformedReferencesAreRejected() {
        listOf("file://remote/tmp/x", "file:///tmp/%00x", "file:relative", "file:///tmp/%ff", "//server/share/a", "javascript:alert(1)", "/tmp/a\n.txt").forEach { assertNull(it, HostFileReferences.target(it, true)) }
    }
    @Test fun localArtifactMutationCannotBeSavedWithTheOriginalDigest() {
        val file = File.createTempFile("host-file-test", ".txt")
        try {
            val bytes = "verified".toByteArray(); file.writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertTrue(verifyHostFileArtifact(file, bytes.size.toLong(), digest))
            file.writeText("replaced")
            assertFalse(verifyHostFileArtifact(file, bytes.size.toLong(), digest))
        } finally { file.delete() }
    }
}
