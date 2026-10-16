package app.daiyu.android.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.daiyu.android.*
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.integration.tools.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.app.AgentAppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Pure approval UI; production approve path is exercised but no Runner or open step is invoked. */
class TerminalApprovalHostTest : WiredActivityTestCase() {
    private val conversation = Conversation(ConversationId("terminal-ui"), "title", "", 0)
    private val draft = TerminalDraftSnapshot(TerminalDraftOwner(conversation.id, 1, 2), "exact text")
    private val assistant = AssistantProfile("profile", "name", "frozen instructions")
    private val settings = ModelSettings("https://example.invalid", "fixture-model", "synthetic-key", false)
    private val provider = ProviderSnapshot.capture(settings)
    private fun preparation(): ControllerTerminalPreparation {
        val plan = TerminalPtyPlan(conversation.id, "session", "run", "environment", "generation",
            TerminalPtyLaunch.Android(listOf("/system/bin/sh", "-c", "argument-".repeat(600) + "ARGV-END"),
                mapOf("SPECIAL" to "line1\nline2=ENV-END"), File("/tmp")), File("/tmp/journal"), "scope",
            listOf(TerminalPtyStep.Open("open"), TerminalPtyStep.Input("input", "request", 12,
                ByteArray(2500) { (it % 256).toByte() })),
            listOf(TerminalPtyRead("first", 0, 16384), TerminalPtyRead("last", 16384, 57)))
        return ControllerTerminalPreparation.prepare(instrumentation.targetContext, conversation, draft, assistant, provider, plan)
    }

    fun testMountAndPrepareDoNotImplicitlyConfirmOrStart() {
        val prepares = AtomicInteger()
        val confirms = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings()) {
                    TerminalApprovalHost(draft, assistant, provider, null, null,
                        onPrepare = { prepares.incrementAndGet(); false },
                        onConfirm = { _, _, _ -> confirms.incrementAndGet(); false }, onCancel = {})
                }
            }
        }
        ui.waitForIdle()
        assertEquals(0, prepares.get())
        assertEquals(0, confirms.get())
        ui.onNodeWithTag("terminal-confirm").assertDoesNotExist()
        ui.onNodeWithTag("terminal-prepare").performClick()
        assertEquals(1, prepares.get())
        assertEquals(0, confirms.get())
    }

    fun testAllArgumentInputAndReadPagesAreReachableAndConfirmationUsesOriginalPreview() {
        val p = preparation()
        val state = mutableStateOf(p.state)
        val confirmations = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings()) {
                    TerminalApprovalHost(draft, assistant, provider, p, state.value,
                        onPrepare = { false }, onConfirm = { candidate, displayed, current ->
                            assertSame(p, candidate)
                            assertSame(p.preview, displayed)
                            assertEquals(draft, current)
                            confirmations.incrementAndGet()
                            p.confirm(displayed, current, assistant, provider).also { state.value = p.state }
                        }, onCancel = { it.cancel(); state.value = it.state })
                }
            }
        }
        ui.waitForIdle()
        assertEquals(ControllerTerminalPreparation.State.PREVIEW, p.state)
        val pages = terminalApprovalSections(p)
        listOf("terminal-argv-2-1", "terminal-env-0-0", "terminal-input-1-2-0", "terminal-effect-1-0", "terminal-read-1-0").forEach { tag ->
            ui.onNodeWithTag("terminal-approval-pages").performScrollToNode(hasTestTag(tag))
            ui.onNodeWithTag(tag, useUnmergedTree = true).assertTextEquals(pages.single { it.id == tag }.text)
        }
        assertEquals(0, confirmations.get())
        ui.onNodeWithTag("terminal-confirm").assertIsEnabled().performClick()
        ui.waitForIdle()
        assertEquals(1, confirmations.get())
        assertEquals(ControllerTerminalPreparation.State.CONFIRMED, p.state)
        ui.onNodeWithTag("terminal-confirm").assertDoesNotExist()
        p.cancel()
        // Instrumentation worker, not the application's main thread; no process was constructed.
        assertTrue(p.awaitCleanupOnWorker(100))
    }

    fun testEqualTextNewDraftRevisionInvalidatesWithoutConfirmation() {
        val p = preparation()
        val current = mutableStateOf(draft)
        val phase = mutableStateOf(p.state)
        val confirmations = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings()) {
                    TerminalApprovalHost(current.value, assistant, provider, p, phase.value,
                        onPrepare = { false }, onConfirm = { _, _, _ -> confirmations.incrementAndGet(); false },
                        onCancel = { it.cancel(); phase.value = it.state })
                }
            }
        }
        ui.waitForIdle()
        mainThread { current.value = draft.copy(owner = draft.owner.copy(draftRevision = 3)) }
        ui.waitForIdle()
        ui.onNodeWithTag("terminal-confirm").assertIsNotEnabled()
        assertEquals(0, confirmations.get())
        assertTrue(p.isCancelled)
        assertEquals(ControllerTerminalPreparation.State.DRAINED, p.state)
    }

    fun testProviderChangeAndDisposalCancelWithoutConfirmation() {
        val p = preparation()
        val current = mutableStateOf(provider)
        val mounted = mutableStateOf(true)
        val phase = mutableStateOf(p.state)
        val cancellations = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings()) {
                    if (mounted.value) TerminalApprovalHost(draft, assistant, current.value, p, phase.value,
                        onPrepare = { false }, onConfirm = { _, _, _ -> fail("implicit confirmation"); false },
                        onCancel = { cancellations.incrementAndGet(); it.cancel(); phase.value = it.state })
                }
            }
        }
        ui.waitForIdle()
        mainThread { current.value = ProviderSnapshot.capture(settings.copy(apiKey = "changed-synthetic-key")) }
        ui.waitForIdle()
        ui.onNodeWithTag("terminal-confirm").assertIsNotEnabled()
        assertTrue(p.isCancelled)
        val before = cancellations.get()
        mainThread { mounted.value = false }
        ui.waitForIdle()
        assertTrue(cancellations.get() > before)
    }
}
