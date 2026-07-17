package com.termux.app.fleet

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentFleetDiagnosticsTest {
    @Test
    fun localShellProbeDoesNotLoadUserOrSystemProfiles() {
        assertEquals(
            listOf("/prefix/bin/bash", "--noprofile", "--norc", "-c", "printf agent-fleet-diagnostic-ok"),
            localShellDiagnosticCommand(File("/prefix/bin/bash"))
        )
    }

    @Test
    fun sanitizerRemovesCredentialsInvitationsAndPaths() {
        val secret = "super-secret-value-12345678901234567890"
        val value = safeDiagnosticText(
            "token=$secret password=hunter2 Authorization:BearerValue " +
                "wtmux://pair?token=$secret /home/person/private/repository/file.txt C:\\Users\\person\\secret.txt"
        )
        assertFalse(value.contains(secret))
        assertFalse(value.contains("hunter2"))
        assertFalse(value.contains("wtmux://"))
        assertFalse(value.contains("/home/person"))
        assertFalse(value.contains("C:\\Users"))
        assertTrue(value.contains("[redacted]") || value.contains("[invitation]"))
    }

    @Test
    fun journalRotatesByCountAndAgeAndSurvivesMalformedRecords() {
        var now = 1_720_000_000_000L
        val directory = Files.createTempDirectory("agent-fleet-diagnostics").toFile()
        val file = File(directory, "events.ndjson")
        val journal = AgentFleetDiagnosticJournal(file) { now }
        repeat(AgentFleetDiagnosticJournal.MAX_EVENTS + 12) { index ->
            journal.record("repository.list", "healthy", index.toLong(), message = "event $index")
        }
        assertEquals(AgentFleetDiagnosticJournal.MAX_EVENTS, journal.events().size)
        assertTrue(file.length() <= AgentFleetDiagnosticJournal.MAX_JOURNAL_BYTES)

        now += AgentFleetDiagnosticJournal.MAX_EVENT_AGE_MS + 1
        journal.record("fleet.refresh", "healthy", message = "new event")
        assertEquals(listOf("fleet.refresh"), journal.events().map { it.operation })
        file.appendText("not-json\n")
        assertEquals(1, journal.events().size)
    }

    @Test
    fun exportedArchiveContainsOnlyTheTwoVersionedMetadataFiles() {
        val context: Context = RuntimeEnvironment.getApplication()
        val journal = AgentFleetDiagnosticJournal(context)
        val secret = "secret-fixture-abcdefghijklmnopqrstuvwxyz0123456789"
        journal.record(
            "repository.download", "failure", code = "download_failed",
            message = "token=$secret failed at /home/person/private/project/report.pdf",
            hostId = "gaming", sessionId = "gaming:wtmux"
        )
        val runner = AgentFleetDiagnosticsRunner(
            context, EmbeddedRuntimeManager(context), ClientPolicyStore(context), FleetRuntime(context), journal
        )
        val report = AgentFleetDiagnosticReport(
            diagnosticIsoUtc(System.currentTimeMillis()), "test", "16", "emulator", "x86_64", "fixture", "revision",
            listOf(AgentFleetDiagnosticCheck("runtime", "Runtime", DiagnosticStatus.Healthy, "Ready")), journal.events()
        )
        val archive = runner.export(report)
        ZipFile(archive).use { zip ->
            assertEquals(setOf("diagnostics.json", "events.ndjson"), zip.entries().asSequence().map { it.name }.toSet())
            val content = zip.entries().asSequence().joinToString("\n") { entry -> zip.getInputStream(entry).bufferedReader().readText() }
            assertTrue(content.contains(DIAGNOSTICS_SCHEMA))
            assertFalse(content.contains(secret))
            assertFalse(content.contains("/home/person"))
            assertFalse(content.contains("report.pdf"))
        }
    }
}
