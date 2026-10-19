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
import app.daiyu.android.vdapproval.*

/** UI identity/lifecycle only. This fixture never approves or executes a real owner operation. */
@Suppress("DEPRECATION")
class R08CleanupApprovalScreenTest : WiredActivityTestCase() {
    private val visible = mutableStateOf<R08CleanupApprovalRequest?>(null)
    private val decisions = mutableListOf<Pair<R08CleanupApprovalRequest, Boolean>>()
    private val callback = mutableStateOf<(R08CleanupApprovalRequest, Boolean) -> Boolean>({ req, accepted ->
        decisions += req to accepted
        visible.value === req
    })
    override fun setUp() {
        super.setUp()
        mainThread {
            activity.setContent {
                MaterialTheme { visible.value?.let { R08CleanupApprovalScreen(it, callback.value) } }
            }
        }
    }

    private fun request(generation: String): R08CleanupApprovalRequest {
        var issued: RunExecutionOwner? = null
        val message = Message(MessageId("cleanup-assistant"), ConversationId("cleanup-conversation"), RunId("cleanup-run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val batches = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { issued = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> =
                error("UI test never dispatches")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = Unit
            override fun cancel() = Unit
        } }, object : ConversationStore { override fun save(message: Message) = Unit },
            BatteryTool { error("UI test never reads battery") }, RunnerOptions(receiptBatchExecutor = batches)) {}
            .run(message, emptyList())
        return R08CleanupApprovalRequest(R08VirtualDisplayPlan.prepare(checkNotNull(issued),
            SessionCapabilities("cleanup-session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY)), generation,
            listOf(R08VirtualDisplayWrite("host_cleanup_release", R08VirtualDisplayAction.Release))))
    }

    fun testShowingDoesNotDecideAndConfirmationOnlyAnswersOriginalRequestOnce() {
        val req = request("original-generation")
        mainThread { visible.value = req }
        ui.onNodeWithText("已停止运行：额外释放审批").assertExists()
        assertTrue(decisions.isEmpty())
        node("r08-cleanup-confirm").performClick()
        assertEquals(listOf(req to true), decisions)
        node("r08-cleanup-confirm").assertIsNotEnabled()
        mainThread { visible.value = null }
        ui.waitForIdle()
        assertEquals(listOf(req to true, req to false), decisions)
    }

    fun testReplacingRequestAndCallbackKeepsDisposalAndAnswerWithOriginalOwner() {
        val a = request("a")
        val b = request("b")
        val aDecisions = mutableListOf<Pair<R08CleanupApprovalRequest, Boolean>>()
        val bDecisions = mutableListOf<Pair<R08CleanupApprovalRequest, Boolean>>()
        val aCallback: (R08CleanupApprovalRequest, Boolean) -> Boolean = { req, accepted ->
            aDecisions += req to accepted; req === a
        }
        val bCallback: (R08CleanupApprovalRequest, Boolean) -> Boolean = { req, accepted ->
            bDecisions += req to accepted; req === b
        }
        mainThread { visible.value = a; callback.value = aCallback }
        ui.waitForIdle()
        mainThread { visible.value = b; callback.value = bCallback }
        ui.waitForIdle()
        assertEquals(listOf(a to false), aDecisions)
        assertTrue(bDecisions.isEmpty())
        // A callback-only recomposition cannot redirect the existing request to a different owner.
        mainThread { callback.value = aCallback }
        ui.waitForIdle()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        assertEquals(listOf(a to false), aDecisions)
        assertEquals(listOf(b to false), bDecisions)
        node("r08-cleanup-confirm").assertIsNotEnabled()
    }
}
