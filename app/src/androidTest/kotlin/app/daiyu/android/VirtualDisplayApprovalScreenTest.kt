package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.ToolDispatchResult
import app.daiyu.android.integration.tools.*
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import app.daiyu.android.vdapproval.VirtualDisplayApprovalScreen

/** Isolated Compose surface, not MainActivity routing and never a root/process test. */
@Suppress("DEPRECATION")
class VirtualDisplayApprovalScreenTest : WiredActivityTestCase() {
    private val visible = mutableStateOf<VirtualDisplayApprovalRequest?>(null)
    private val decisions = mutableListOf<Pair<VirtualDisplayApprovalRequest, Boolean>>()
    private var acceptCallback = true
    private val decisionCallback = mutableStateOf<(VirtualDisplayApprovalRequest, Boolean) -> Boolean>(
        { original, accepted ->
            decisions.add(original to accepted)
            acceptCallback && visible.value === original
        })

    override fun setUp() {
        super.setUp()
        mainThread {
            activity.setContent {
                MaterialTheme {
                    visible.value?.let { request ->
                        VirtualDisplayApprovalScreen(request, decisionCallback.value)
                    }
                }
            }
        }
    }

    private fun request(generation: String): VirtualDisplayApprovalRequest {
        var issued: RunExecutionOwner? = null
        val message = Message(MessageId("screen-assistant"), ConversationId("screen-conversation"), RunId("screen-run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val batch = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { issued = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> =
                error("screen test never dispatches")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = Unit
            override fun cancel() = Unit
        } }, object : ConversationStore { override fun save(message: Message) = Unit },
            BatteryTool { error("screen test never reads battery") }, RunnerOptions(receiptBatchExecutor = batch)) {}
        runner.run(message, emptyList())
        return VirtualDisplayApprovalRequest(R08VirtualDisplayPlan.prepare(checkNotNull(issued),
            SessionCapabilities("screen-session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY)), generation,
            listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start),
                R08VirtualDisplayWrite("initialize", R08VirtualDisplayAction.Initialize),
                R08VirtualDisplayWrite("tap", R08VirtualDisplayAction.Tap(12, 34))),
            listOf(R08VirtualDisplayRead("status"))))
    }

    private fun revealText(text: String) {
        node("vd-approval-details").scrollToDescendant(hasText(text))
        ui.onAllNodesWithText(text).onFirst().assertExists()
    }

    fun testEveryBindingAndOperationIsVisibleAndOpeningDoesNotDecide() {
        val request = request("screen-generation")
        mainThread { visible.value = request }
        assertTrue(decisions.isEmpty())
        listOf("session: screen-session", "task: screen-run", "conversation: screen-conversation",
            "run: screen-run", "assistantMessage: screen-assistant", "runOwner: ${request.plan.owner.ownerId}",
            "bindingKey: ${request.plan.bindingKey}", "generation: screen-generation").forEach(::revealText)
        request.rows.forEach { row ->
            revealText("WRITE ${row.stepId} · ${row.operation}")
            revealText("规范生效参数: ${row.effectiveParameters}")
            revealText("target: ${row.exactEffect.target}")
            revealText("change: ${row.exactEffect.change}")
        }
        revealText("READ status · status")
        assertTrue(decisions.isEmpty())
        node("vd-approval-confirm").performClick()
        assertEquals(1, decisions.size)
        assertSame(request, decisions.single().first)
        assertTrue(decisions.single().second)
        node("vd-approval-confirm").assertIsNotEnabled()
    }

    fun testReplacementAndLeavingDenyOriginalRequestsNotTheNewOne() {
        val first = request("first")
        val second = request("second")
        mainThread { visible.value = first }
        ui.waitForIdle()
        mainThread { visible.value = second }
        ui.waitForIdle()
        assertEquals(1, decisions.size)
        assertSame(first, decisions[0].first)
        assertFalse(decisions[0].second)
        mainThread { visible.value = null }
        ui.waitForIdle()
        assertEquals(2, decisions.size)
        assertSame(second, decisions[1].first)
        assertFalse(decisions[1].second)
    }

    fun testRequestAndSessionReplacementKeepsConfirmWithItsOwner() = assertSessionCallbackOwnership("confirm")

    fun testRequestAndSessionReplacementKeepsRejectWithItsOwner() = assertSessionCallbackOwnership("reject")

    fun testRequestAndSessionReplacementKeepsBackWithItsOwner() = assertSessionCallbackOwnership("back")

    fun testRequestAndSessionReplacementKeepsRemovalWithItsOwner() = assertSessionCallbackOwnership("remove")

    private fun assertSessionCallbackOwnership(action: String) {
        val first = request("first-session")
        val second = request("second-session")
        val firstSessionDecisions = mutableListOf<Pair<VirtualDisplayApprovalRequest, Boolean>>()
        val secondSessionDecisions = mutableListOf<Pair<VirtualDisplayApprovalRequest, Boolean>>()
        val firstCallback: (VirtualDisplayApprovalRequest, Boolean) -> Boolean = { original, accepted ->
            firstSessionDecisions.add(original to accepted)
            original === first
        }
        val secondCallback: (VirtualDisplayApprovalRequest, Boolean) -> Boolean = { original, accepted ->
            secondSessionDecisions.add(original to accepted)
            original === second
        }
        mainThread { visible.value = first; decisionCallback.value = firstCallback }
        ui.waitForIdle()
        assertTrue(firstSessionDecisions.isEmpty())
        assertTrue(secondSessionDecisions.isEmpty())

        // One recomposition replaces BOTH the request and its owning session callback.
        mainThread { visible.value = second; decisionCallback.value = secondCallback }
        ui.waitForIdle()
        assertEquals(listOf(first to false), firstSessionDecisions)
        assertTrue("Old disposal must not reach the new session", secondSessionDecisions.isEmpty())

        // A callback-only update must not detach the current request from its original owner.
        mainThread { decisionCallback.value = firstCallback }
        ui.waitForIdle()
        when (action) {
            "confirm" -> node("vd-approval-confirm").performClick()
            "reject" -> node("vd-approval-reject").performClick()
            "back" -> mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            "remove" -> mainThread { visible.value = null }
            else -> error("Unknown test action")
        }
        ui.waitForIdle()
        assertEquals(listOf(first to false), firstSessionDecisions)
        assertEquals(listOf(second to (action == "confirm")), secondSessionDecisions)
        if (action != "remove") {
            node("vd-approval-confirm").assertIsNotEnabled()
            mainThread { visible.value = null }
            ui.waitForIdle()
            // Disposal still belongs to the same session after an explicit answer.
            assertEquals(listOf(second to (action == "confirm"), second to false), secondSessionDecisions)
        }
        assertEquals(listOf(first to false), firstSessionDecisions)
    }

    fun testRejectAndExpiredCallbackHaveNoImplicitConfirmation() {
        val request = request("expired")
        mainThread { visible.value = request; acceptCallback = false }
        node("vd-approval-reject").performClick()
        assertSame(request, decisions.single().first)
        assertFalse(decisions.single().second)
        node("vd-approval-obsolete").assertExists()
        node("vd-approval-confirm").assertIsNotEnabled()
    }
}
