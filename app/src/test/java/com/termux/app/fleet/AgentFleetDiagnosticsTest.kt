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
    fun hostResourceAttentionProducesConnectionHealthSummary() {
        val doctor = FleetDoctorResult(
            "host", "2026-07-27T00:00:00Z", "attention",
            listOf(
                FleetDoctorCheck("tmux", "healthy", "tmux is available", "tmux 3.4"),
                FleetDoctorCheck(
                    "resource-budget", "attention", "Connection resources need review",
                    "status probes 0 · conversation streams 4 · duplicate candidates 3"
                )
            )
        )
        val presentation = hostDiagnosticPresentation(doctor)
        assertEquals("Connection resources need review", presentation.first)
        assertTrue(presentation.second.contains("duplicate candidates 3"))
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
    fun creationMetadataDropsMessagesIdentifiersAndUnknownCodes() {
        val now = 1_791_547_866_063L
        val event = AgentFleetDiagnosticEvent("private-id", "private-date", now,
            "session.create.windows", "failure", 12L, "TIMEOUT", "private transcript", "private-host", "private-session")
        val body = creationOperationsNdjson(listOf(event, event.copy(code = "secret-token")), now)
        val rows = body.trim().lines().map { org.json.JSONObject(it) }
        assertEquals("timeout", rows[0].getString("code"))
        assertEquals(6, rows[0].length())
        assertEquals("operation_failed", rows[1].getString("code"))
        assertFalse(body.contains("private"))
        assertFalse(body.contains("secret"))
        assertFalse(body.contains("message"))
    }

    @Test
    fun journalStorageFailureDoesNotBreakTheReportedOperation() {
        val directory = Files.createTempDirectory("diagnostic-unwritable-parent").toFile()
        val blocker = File(directory, "parent").apply { writeText("not a directory") }
        val journal = AgentFleetDiagnosticJournal(File(blocker, "events.ndjson"))
        val event = journal.record("session.create.windows", "healthy")
        assertEquals("healthy", event.status)
        assertTrue(journal.events().isEmpty())
    }

    @Test
    fun creationMetadataBoundsAgeCountAndDuration() {
        val now = 1_791_547_866_063L
        val event = AgentFleetDiagnosticEvent("id", "unused", now, "session.create.linux", "pending",
            Long.MAX_VALUE, "", "")
        val body = creationOperationsNdjson(listOf(event.copy(operation = "secret"),
            event.copy(epochMs = now - AgentFleetDiagnosticJournal.MAX_EVENT_AGE_MS - 1),
            event.copy(epochMs = now + 60_001)) + List(220) { event }, now)
        assertEquals(200, body.trim().lines().size)
        assertEquals(86_400_000L, org.json.JSONObject(body.trim().lines().first()).getLong("durationMs"))
    }

    @Test
    fun exportedArchiveContainsLayeredReportAndCreationCodesWithoutPrivateContent() {
        val context: Context = RuntimeEnvironment.getApplication()
        val journal = AgentFleetDiagnosticJournal(context)
        val secret = "secret-fixture-abcdefghijklmnopqrstuvwxyz0123456789"
        journal.record(
            "session.create", "failure", code = "timeout",
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
            assertEquals(setOf("diagnostics-v2.json", "operations-v1.ndjson"), zip.entries().asSequence().map { it.name }.toSet())
            val content = zip.entries().asSequence().joinToString("\n") { entry -> zip.getInputStream(entry).bufferedReader().readText() }
            assertTrue(content.contains("\"schemaVersion\": 2"))
            val event = org.json.JSONObject(zip.getInputStream(zip.getEntry("operations-v1.ndjson")).bufferedReader().readText().trim())
            assertEquals("session.create", event.getString("operation"))
            assertEquals("timeout", event.getString("code"))
            assertFalse(event.has("message"))
            assertFalse(event.has("hostId"))
            assertFalse(event.has("sessionId"))
            assertFalse(report.exportPreview().contains(secret))
            assertFalse(report.exportPreview().contains("private/project"))
            assertFalse(content.contains(secret))
            assertFalse(content.contains("/home/person"))
            assertFalse(content.contains("report.pdf"))
        }
    }
}
