package app.daiyu.android.browser

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.daiyu.android.BrowserDraftOwner
import app.daiyu.android.BrowserDraftSnapshot
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.ControllerChildBrowserPlan
import app.daiyu.android.ui.ChildBrowserApprovalHost
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.BrowserApprovalHost
import app.daiyu.android.ui.app.AgentAppTheme
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** The approval surface only. No constructor, WebView, tab, permission prompt or network. */
class BrowserApprovalHostTest : WiredActivityTestCase() {
    private val conversation = Conversation(ConversationId("browser-ui"), "title", "", 0)
    private val originalDraft = BrowserDraftSnapshot(BrowserDraftOwner(conversation.id, 1, 2), "exact editor text")
    private val assistant = AssistantProfile("ui-profile", "UI profile", "exact frozen instructions")
    private val settings = BrowserSettings(BrowserAccess.FULL)
    private val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid", "fixture-model", "synthetic-key", false))

    fun testFullPreferenceDoesNotEnableOrConfirmAnythingOnMount() {
        val calls = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserApprovalHost(originalDraft, assistant, settings, null, null,
                        onPrepare = { _, _ -> calls.incrementAndGet(); false },
                        onConfirm = { _, _, _ -> calls.incrementAndGet(); false }, onCancel = {})
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("browser-enable").assertIsOff()
        ui.onNodeWithTag("browser-confirm").assertDoesNotExist()
        assertEquals(0, calls.get())
    }

    fun testDisplaysEntireRawWriteAndRequiresExplicitConfirmationOfOriginalPreview() {
        val raw = JSONObject().put("action", "execute_js").put("tab_id", 0)
            .put("script", "/* " + "whole-parameter-".repeat(80) + "UNIQUE-END */").toString()
        val p = ControllerBrowserPreparation.prepare(conversation, originalDraft, assistant, provider, settings,
            BrowserGrant(BrowserOwner("ui-run", BrowserOwnerKind.MAIN), BrowserAccess.FULL),
            setOf("execute_js", "list_tabs"), listOf(raw))
        val phase = mutableStateOf(p.state)
        val confirmations = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserApprovalHost(originalDraft, assistant, settings, p, phase.value,
                        onPrepare = { _, _ -> false }, onConfirm = { target, displayed, draft ->
                            assertSame(p, target)
                            assertSame(p.preview, displayed)
                            confirmations.incrementAndGet()
                            p.confirm(displayed, draft).also { phase.value = p.state }
                        }, onCancel = { it.cancel(); phase.value = it.state })
                }
            }
        }
        ui.waitForIdle()
        assertEquals(0, confirmations.get())
        assertEquals(ControllerBrowserPreparation.State.PREVIEW, p.state)
        ui.onNodeWithTag("browser-write-1", useUnmergedTree = true).performScrollTo().assertTextEquals(raw)
        ui.onNodeWithTag("browser-confirm").assertIsEnabled().performClick()
        ui.waitForIdle()
        assertEquals(1, confirmations.get())
        assertEquals(ControllerBrowserPreparation.State.CONFIRMED, p.state)
        ui.onNodeWithTag("browser-confirm").assertIsNotEnabled()
    }

    fun testIndependentChildExactFetchApprovalDoesNotFollowParentClickOrFullPreference() {
        val p = ControllerBrowserPreparation.prepare(conversation, originalDraft, assistant, provider, settings,
            BrowserGrant(BrowserOwner("ui-child-parent", BrowserOwnerKind.MAIN), BrowserAccess.FULL), setOf("list_tabs"))
        val raw = """ {"action":"fetch","tab_id":0,"url":"https://fixture.invalid/child-file"} """
        val child = ControllerChildBrowserPlan.prepare(p, "independent child prompt", setOf("fetch"), listOf(raw))
        assertTrue(p.attachChild(child))
        assertFalse(p.confirm(p.preview, originalDraft))
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildBrowserApprovalHost(p) { _, _, _, _ -> error("already frozen") }
                }
            }
        }
        ui.waitForIdle()
        assertFalse(child.confirmed)
        ui.onNodeWithTag("child-browser-review").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("child-browser-write-1", useUnmergedTree = true).performScrollTo().assertTextEquals(raw)
        ui.onNodeWithTag("child-browser-confirm").performClick()
        ui.waitForIdle()
        assertTrue(child.confirmed)
        assertTrue(p.confirm(p.preview, originalDraft))
        p.cancel()
    }

    fun testSameTextWithNewRevisionInvalidatesPreviewWithoutConfirmation() {
        val p = ControllerBrowserPreparation.prepare(conversation, originalDraft, assistant, provider, settings,
            BrowserGrant(BrowserOwner("ui-run", BrowserOwnerKind.MAIN), BrowserAccess.FULL), setOf("list_tabs"))
        val draft = mutableStateOf(originalDraft)
        val phase = mutableStateOf(p.state)
        val confirmations = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserApprovalHost(draft.value, assistant, settings, p, phase.value,
                        onPrepare = { _, _ -> false },
                        onConfirm = { _, _, _ -> confirmations.incrementAndGet(); false },
                        onCancel = { it.cancel(); phase.value = it.state })
                }
            }
        }
        ui.waitForIdle()
        mainThread { draft.value = originalDraft.copy(owner = originalDraft.owner.copy(draftRevision = 3)) }
        ui.waitForIdle()
        ui.onNodeWithTag("browser-confirm").assertIsNotEnabled()
        assertEquals(0, confirmations.get())
        assertTrue(p.isCancelled)
    }
}
