package com.termux.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.AgentFleetDiagnosticCheck
import com.termux.app.fleet.AgentFleetDiagnosticReport
import com.termux.app.fleet.ConversationAnswer
import com.termux.app.fleet.ConversationItem
import com.termux.app.fleet.ConversationQuestion
import com.termux.app.fleet.ConversationQuestionOption
import com.termux.app.fleet.ConversationTask
import com.termux.app.fleet.DiagnosticStatus
import com.termux.app.fleet.DiagnosticsUiState
import com.termux.app.fleet.EmbeddedRuntimeStatus
import com.termux.app.fleet.FleetAttention
import com.termux.app.fleet.FleetDirectoryListing
import com.termux.app.fleet.FleetDownloadCancellation
import com.termux.app.fleet.FleetDownloadState
import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.FleetUnavailableException
import com.termux.app.fleet.FleetLoadState
import com.termux.app.fleet.FleetRepositoryEntry
import com.termux.app.fleet.FleetRepositoryPage
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.NativeSessionScreen
import com.termux.app.fleet.NativeSessionUiState
import com.termux.app.fleet.ToolPresentation
import com.termux.app.fleet.ToolPresentationBlock
import com.termux.app.fleet.UpdateUiState
import com.termux.app.fleet.AndroidWorkspaceState
import com.termux.app.fleet.WorkspaceTerminalBroker
import com.termux.app.fleet.emptyWorkspaceLayout
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sessionsRepositoryErrorRetryAndDownloadAreUsable() {
        val attempts = AtomicInteger()
        compose.setContent {
            FixtureApp(
                onListRepository = { _, _, _, _, callback ->
                    if (attempts.incrementAndGet() == 1) callback(Result.failure(FleetUnavailableException("Host temporarily offline", "host_offline")))
                    else callback(Result.success(repositoryPage))
                },
                onDownloadRepository = { _, entry, callback ->
                    callback(FleetDownloadState(entry.name, entry.relativePath, "running", 4, 12, message = "Downloading · 33%"))
                    FleetDownloadCancellation()
                }
            )
        }
        compose.onNodeWithTag("session-more-gaming:wtmux").performClick()
        compose.onNodeWithText("Download a file").performClick()
        compose.onNodeWithTag("repository-error").assertIsDisplayed()
        compose.onNodeWithTag("repository-retry").performClick()
        compose.onNodeWithTag("repository-entry-README.md").assertIsDisplayed().performClick()
        compose.onNodeWithTag("repository-download-state").assertIsDisplayed()
        compose.onNodeWithText("Downloading · 33%").assertIsDisplayed()
        assertEquals(2, attempts.get())
    }

    @Test
    fun wideWorkspaceAssignsFromTheVerticalSessionRail() {
        val state = mutableStateOf(AndroidWorkspaceState(emptyWorkspaceLayout()))
        val broker = WorkspaceTerminalBroker(ApplicationProvider.getApplicationContext())
        compose.setContent {
            AgentFleetTheme(darkTheme = true) {
                DesktopWorkspaceScreen(
                    snapshot = snapshot,
                    sessions = snapshot.sessions,
                    state = state.value,
                    broker = broker,
                    onStateChange = { state.value = it },
                    onMoreSession = {},
                    onRefresh = {}
                )
            }
        }
        compose.onNodeWithTag("desktop-session-search").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.runOnIdle {
            assertEquals(session.id, com.termux.app.fleet.workspacePanes(state.value.layout.root).single().sessionId)
        }
        compose.onNodeWithTag("workspace-pane-${state.value.layout.focusedPaneId}").assertIsDisplayed()
        broker.close()
    }

    @Test
    fun permanentRepositoryFailureDoesNotOfferRetry() {
        compose.setContent {
            FixtureApp(
                onListRepository = { _, _, _, _, callback ->
                    callback(Result.failure(FleetUnavailableException(
                        "Repository path is unavailable for this session", "repository_unavailable"
                    )))
                }
            )
        }
        compose.onNodeWithTag("session-more-gaming:wtmux").performClick()
        compose.onNodeWithText("Download a file").performClick()
        compose.onNodeWithTag("repository-error").assertIsDisplayed()
        compose.onNodeWithText("Repository path is unavailable", substring = true).assertIsDisplayed()
        compose.onAllNodes(hasTestTag("repository-retry")).assertCountEquals(0)
    }

    @Test
    fun diagnosticsRequiresPreviewBeforeShare() {
        val exports = AtomicInteger()
        val report = diagnosticReport()
        compose.setContent { FixtureApp(diagnosticsUi = DiagnosticsUiState(report = report), onExportDiagnostics = { exports.incrementAndGet() }) }
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-screen").performScrollToNode(hasTestTag("open-diagnostics-button"))
        compose.onNodeWithTag("open-diagnostics-button").performClick()
        compose.onNodeWithTag("diagnostics-report").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-export").performClick()
        compose.onNodeWithText("Privacy: metadata only.", substring = true, ignoreCase = true).assertIsDisplayed()
        assertEquals(0, exports.get())
        compose.onNodeWithTag("diagnostics-share").performClick()
        assertEquals(1, exports.get())
    }

    @Test
    fun onePlanQuestionSubmitsExactlyOnceOnTap() {
        val submissions = mutableListOf<List<ConversationAnswer>>()
        compose.setContent { NativeFixture(listOf(question("q1", "Choose a direction")), submissions::add) }
        compose.onNodeWithTag("question-option-q1-a").performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals(listOf("a"), submissions.single().single().choiceIds)
        }
    }

    @Test
    fun threePlanQuestionsAdvanceByTapAndSubmitOnceAtEnd() {
        val submissions = mutableListOf<List<ConversationAnswer>>()
        val questions = listOf(
            question("q1", "Which host should run this?"),
            question("q2", "Which rollout channel should be used?"),
            question("q3", "This deliberately long final question verifies that the action sheet remains scrollable and tap-to-answer still works when the prompt occupies several lines on a phone-sized screen."),
        )
        compose.setContent { NativeFixture(questions, submissions::add) }
        compose.onNodeWithTag("question-option-q1-a").performClick()
        compose.onNodeWithTag("question-page").assertTextContains("2 of 3")
        compose.onNodeWithTag("question-option-q2-b").performClick()
        compose.onNodeWithTag("question-page").assertTextContains("3 of 3")
        compose.onNodeWithTag("question-option-q3-a").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            assertEquals(listOf("q1", "q2", "q3"), submissions.single().map { it.questionId })
            assertEquals(listOf(listOf("a"), listOf("b"), listOf("a")), submissions.single().map { it.choiceIds })
        }
    }

    @Test
    fun staleQuestionDoesNotReplaceTheComposerWithAnActionPrompt() {
        val newerTool = ConversationItem(
            id = "tool-after-question", kind = "tool", timestamp = "2026-07-16T01:01:00Z", role = "assistant",
            title = "Tool completed", text = "", detail = "", state = "complete", tool = "exec",
            attachments = emptyList(), choices = emptyList()
        )
        val staleQuestion = ConversationItem(
            id = "stale-question", kind = "question", timestamp = "2026-07-16T01:00:00Z", role = "assistant",
            title = "Answer needed", text = "", detail = "", state = "pending", tool = "question",
            attachments = emptyList(), choices = emptyList(), revision = "stale-revision",
            questions = listOf(question("spec", "Do a SPEC first?"))
        )
        compose.setContent {
            NativeStateFixture(NativeSessionUiState(
                "Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live",
                items = listOf(newerTool, staleQuestion)
            ))
        }

        compose.onAllNodes(hasTestTag("native-pending-action")).assertCountEquals(0)
    }

    @Test
    fun groupedToolsTasksAndDetailsExposeUsefulSemantics() {
        val tools = (1..10).map { index ->
            ConversationItem(
                id = "tool-$index", kind = "tool", timestamp = "2026-07-15T00:00:0${index % 10}Z",
                role = "assistant", title = "Read source $index", text = "", detail = "", state = "completed", tool = "exec",
                attachments = emptyList(), choices = emptyList(), action = "read", target = "source-$index",
                presentation = ToolPresentation(
                    "Read source $index", "exec · completed", 2,
                    listOf(ToolPresentationBlock("Command", "terminal", "rg source-$index")),
                    listOf(ToolPresentationBlock("Result", "terminal", "matched source $index"))
                )
            )
        }
        val tasks = ConversationItem(
            id = "tasks", kind = "task_list", timestamp = "2026-07-15T00:01:00Z", role = "assistant",
            title = "", text = "Implementation", detail = "", state = "running", tool = "",
            attachments = emptyList(), choices = emptyList(), tasks = listOf(
                ConversationTask("1", "Inspect", "Inspecting", "Read sources", "completed"),
                ConversationTask("2", "Implement", "Implementing diagnostics", "Compose UI", "in_progress"),
                ConversationTask("3", "Verify", "Verifying", "Emulator", "pending")
            )
        )
        compose.setContent { NativeStateFixture(NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live", items = tools + tasks)) }
        compose.onNodeWithTag("tool-group-tool-1").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithTag("tool-details-tool-1").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Command").assertIsDisplayed()
        compose.onNodeWithText("rg source-1").assertIsDisplayed()
    }

    @Test
    fun activeLimitCardDisappearsWhenAttentionIsNoLongerCurrent() {
        val attention = FleetAttention("attention-1", "gaming", "gaming:wtmux", "codex", "2026-07-15T10:00:00Z", "offered")
        val state = mutableStateOf(NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live", attention = attention))
        compose.setContent { NativeStateFixture(state.value) }
        compose.onNodeWithText("Codex usage limit reached").assertIsDisplayed()
        compose.runOnIdle { state.value = NativeSessionUiState("Fixture", "gaming", "wtmux-main", adapter = "codex", connection = "Live") }
        compose.onNodeWithText("Codex usage limit reached").assertIsNotDisplayed()
    }

    private fun question(id: String, prompt: String) = ConversationQuestion(
        id, "Decision", prompt, "single", true, false,
        listOf(ConversationQuestionOption("a", "Recommended", "Safe default"), ConversationQuestionOption("b", "Alternative", "Different tradeoff"))
    )

    @androidx.compose.runtime.Composable
    private fun NativeFixture(questions: List<ConversationQuestion>, onQuestion: (List<ConversationAnswer>) -> Unit) {
        val item = ConversationItem(
            id = "question-group", kind = "question", timestamp = "2026-07-15T00:00:00Z", role = "assistant",
            title = "Plan questions", text = "", detail = "", state = "pending", tool = "codex",
            attachments = emptyList(), choices = emptyList(), revision = "revision-1", questions = questions
        )
        NativeStateFixture(
            NativeSessionUiState(
                "Plan fixture", "gaming", "wtmux-main", adapter = "codex", interactionMode = "plan",
                connection = "Live", items = listOf(item), focusQuestionId = item.id, focusQuestionSerial = 1
            ),
            onQuestion = { _, answers -> onQuestion(answers) }
        )
    }

    @androidx.compose.runtime.Composable
    private fun NativeStateFixture(
        state: NativeSessionUiState,
        onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit = { _, _ -> }
    ) {
        AgentFleetTheme(darkTheme = true) {
            NativeSessionScreen(
                state = state,
                aiComposer = true,
                onToggleTerminal = {},
                onRetry = {},
                onLoadOlder = {},
                onApproval = { _, _ -> },
                onQuestion = onQuestion,
                onShellCommand = {},
                onShellKey = {},
                onDirectory = {},
                onRefreshDirectory = {},
                onControlC = {},
                onCloseSession = {},
                onKillSession = {},
                onScheduleContinue = {},
                onDismissAttention = {}
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun FixtureApp(
        diagnosticsUi: DiagnosticsUiState = DiagnosticsUiState(),
        onListRepository: (FleetSession, String, Boolean, String, (Result<FleetRepositoryPage>) -> Unit) -> Unit = { _, _, _, _, callback -> callback(Result.success(repositoryPage)) },
        onDownloadRepository: (FleetSession, FleetRepositoryEntry, (FleetDownloadState) -> Unit) -> FleetDownloadCancellation = { _, _, _ -> FleetDownloadCancellation() },
        onExportDiagnostics: (AgentFleetDiagnosticReport) -> Unit = {}
    ) {
        AgentFleetTheme(darkTheme = true) {
            AgentFleetApp(
                fleetState = FleetLoadState.Ready(snapshot),
                recentSessions = snapshot.sessions,
                pendingPairInvitation = null,
                onPairInvitationHandled = {},
                pendingSharedImages = emptyList(),
                updateState = UpdateUiState.Idle,
                updateManifestUrl = "https://updates.example.test/manifest.json",
                runtimeUi = RuntimeUiState(runtimeStatus, detail = "Ready"),
                diagnosticsUi = diagnosticsUi,
                diagnosticError = null,
                onSharedImagesHandled = {},
                onRefresh = {},
                onOpenSession = {},
                onOpenSessionWithImages = { _, _ -> },
                onCreateSession = { _, _, _, _, _, _ -> },
                onListDirectory = { _, _, _, callback -> callback(Result.success(FleetDirectoryListing("linux", "/home", null, emptyList(), emptyList(), false))) },
                onCreateDirectory = { _, _, _, _, callback -> callback(Result.success("/home/new")) },
                onListRepository = onListRepository,
                onSearchRepository = { _, _, _, callback -> callback(Result.success(repositoryPage)) },
                onDownloadRepository = onDownloadRepository,
                onCloseRepository = {},
                onOpenDownload = {},
                onRenameSession = { _, _ -> },
                onScheduleContinue = { _, _ -> },
                onScheduleAttention = { _, _, _ -> },
                onDismissAttention = {},
                onCancelSchedule = {},
                onKillSession = {},
                onCopyAttachCommand = {},
                onPairInvitation = {},
                onCheckUpdate = {},
                onInstallUpdate = {},
                onRepairRuntime = {},
                onCheckRuntime = {},
                onRollbackRuntime = {},
                onRestoreBaseline = {},
                onOpenAppearance = {},
                onRunDiagnostics = {},
                onCopyDiagnostics = {},
                onExportDiagnostics = onExportDiagnostics,
                onCopyDiagnosticError = {},
                onDiagnosticErrorHandled = {},
                onOpenClassicTerminal = {}
            )
        }
    }

    private fun diagnosticReport() = AgentFleetDiagnosticReport(
        "2026-07-15T00:00:00Z", "test", "16", "Emulator", "x86_64", "fixture", "revision-1",
        listOf(AgentFleetDiagnosticCheck("runtime", "Built-in runtime", DiagnosticStatus.Healthy, "Ready")), emptyList()
    )

    companion object {
        private val session = FleetSession(
            "gaming:wtmux", "gaming", "wtmux-main", "wtmux", "Diagnostics", "wtmux", "codex", "linux", "active", false,
            "2026-07-15T00:00:00Z", 0, "/home/user/projects/wtmux", "project"
        )
        private val snapshot = FleetSnapshot(
            "revision-1", "2026-07-15T00:00:00Z",
            listOf(FleetHost("gaming", "Gaming desktop", "healthy", "wsl", "2026-07-15T00:00:00Z", emptySet())),
            listOf(session), emptyList(), emptyList()
        )
        private val repositoryPage = FleetRepositoryPage(
            "wtmux", "", null,
            listOf(FleetRepositoryEntry("README.md", "README.md", "file", 12, "2026-07-15T00:00:00Z", false, false)),
            null, false
        )
        private val runtimeStatus = EmbeddedRuntimeStatus(
            true, true, false, "fixture", "fixture", "fixture", "", 0, 6, emptyList(), "Ready"
        )
    }
}
