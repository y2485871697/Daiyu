package app.daiyu.android

import android.os.Handler
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.catalog.OpenRouterVideoEvidence
import app.daiyu.android.model.videos.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A route/editor capability supplied by Main, not persisted or reconstructed from a dialog. */
internal data class ControllerVideoEditor(val conversation: String?, val generation: Long,
    val revision: Long, val text: String, val visible: Boolean)

internal interface ControllerVideoUi {
    fun editor(): ControllerVideoEditor
    // The user's send already expresses upload intent. Defaults decide THIS fresh offer only;
    // session authority, preflight, exact token consumption and synchronous revocation still apply.
    fun showRun(prompt: VideoApprovalGate.Prompt, text: String, decision: (Boolean) -> Unit) = decision(true)
    fun showPost(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) = decision(true)
}

/** One parent run. No transport, executor, journal writer or database is created here.
 * The existing preparation worker waits for main-thread decisions; revocation never waits for it.
 * Run authority remains main-thread confined; the atomic fence and POST adapter are cross-thread.
 */
internal class ControllerVideoSession(
    private val main: Handler,
    private val database: ConversationDatabase,
    val provider: ProviderSnapshot,
    val epoch: Long,
    private val runEpoch: Long,
    private val currentEpoch: () -> Long,
    private val settings: () -> ModelSettings,
    private val ui: ControllerVideoUi,
    private val editor: ControllerVideoEditor,
    val snapshot: ControllerVideoHistorySnapshot,
    val assistant: Message,
    private val request: FrozenVideoApprovalRequest,
    private val isParentCurrent: () -> Boolean,
    private val currentAssistant: () -> Message?,
    private val cancelTransport: () -> Unit,
) {
    private val revoked = AtomicBoolean(false)
    private val accepted = AtomicBoolean(false)
    private val approval = VideoUploadApprovalController()
    private var run: VideoUploadApprovalController.Run? = null
    @Volatile private var owner: RunExecutionOwner? = null
    @Volatile var post: ControllerVideoPostApproval? = null; private set
    private var acceptedHistory: ControllerVideoHistorySnapshot? = null
    @Volatile private var frozenPlan: ControllerVideoModelPlan? = null
    fun onFrozenPlan(plan: ControllerVideoModelPlan) {
        check(!isRevoked())
        frozenPlan = plan
    }

    fun isRevoked(): Boolean = revoked.get()

    /** Only the existing RunCatalogTools owner factory may bind the real execution owner. */
    fun bindOwner(value: RunExecutionOwner) {
        check(!isRevoked() && value.owns(assistant))
        synchronized(this) {
            check(owner == null || owner === value)
            owner = value
        }
    }

    /** Gate closure is synchronous even when called from a worker or controller fence. */
    fun revoke() {
        if (!revoked.compareAndSet(false, true)) return
        post?.revoke()
        main.post { approval.revoke(); cancelTransport() }
    }

    private fun checkMain() = check(Thread.currentThread() === main.looper.thread)
    private fun current(): Boolean {
        checkMain()
        if (isRevoked() || !isParentCurrent() || currentEpoch() != epoch ||
            settings() != provider.asModelSettings()) return false
        val now = ui.editor()
        return now.visible && if (!accepted.get()) now == editor else
            (now.conversation == editor.conversation || now.conversation == snapshot.conversation.value)
    }

    /** Wait only on the existing worker, without the controller monitor. */
    fun authorize(proof: OpenRouterVideoEvidence): OpenRouterVideoCapability {
        check(Thread.currentThread() !== main.looper.thread)
        val done = CountDownLatch(1)
        var result: OpenRouterVideoCapability? = null
        main.post {
            try {
                check(current())
                check(snapshot.same(ControllerVideoHistory(database).capture(snapshot.conversation)))
                val prompt = approval.present(request, proof)
                ui.showRun(prompt, request.text) { yes ->
                    try {
                        if (yes && current() && snapshot.same(ControllerVideoHistory(database).capture(snapshot.conversation))) {
                            val ticket = approval.confirm(prompt, request)
                            run = approval.activate(ticket, request)
                            result = approval.capabilityFor(checkNotNull(run), request)
                            post = ControllerVideoPostApproval(object : ControllerVideoPostApprovalHost {
                                override fun postToMain(action: () -> Unit) { main.post { action() } }
                                override fun snapshotOnMain(): ControllerVideoPostAuthority? = authority()
                                override fun showOnMain(prompt: ControllerVideoPostPrompt, decision: (Boolean) -> Unit) {
                                    if (current()) ui.showPost(prompt, decision) else decision(false)
                                }
                                override fun cancelRun() { revoke() }
                            }, ControllerVideoPostAuthority(this, runEpoch, epoch, provider.asModelSettings(),
                                snapshot.rows, snapshot.inputs))
                            if (isRevoked()) post?.revoke()
                        } else revoke()
                    } catch (_: Exception) { revoke() }
                    finally { done.countDown() }
                }
            } catch (_: Exception) { revoke(); done.countDown() }
        }
        while (!done.await(50, TimeUnit.MILLISECONDS)) check(!isRevoked()) { "video_approval_cancelled" }
        check(!isRevoked())
        return checkNotNull(result) { "video_run_not_approved" }
    }

    /** Called after the real outer DB receipt, before scheduling the existing runner. */
    fun accepted(user: Message, inputs: List<MessageVideoInput>) {
        acceptedHistory = snapshot.appended(user, assistant, inputs)
        accepted.set(true)
    }

    private fun authority(): ControllerVideoPostAuthority? {
        checkMain()
        if (!current() || !accepted.get() || owner?.owns(assistant) != true) return null
        approval.capabilityFor(checkNotNull(run), request)
        val actual = ControllerVideoHistory(database).capture(snapshot.conversation)
        val baseline = checkNotNull(acceptedHistory)
        // Only the active assistant may evolve. All other physical rows and ordered sidecars
        // remain the parent's accepted witness, including archived/retained segments.
        if (actual.inputs.map { it.owner to it.references } != baseline.inputs.map { it.owner to it.references } ||
            actual.rows.filterNot { it.id == assistant.id } != baseline.rows.filterNot { it.id == assistant.id }) return null
        val active = actual.rows.singleOrNull { it.id == assistant.id } ?: return null
        if (active.runId != assistant.runId || active.conversationId != assistant.conversationId ||
            active.role != Message.ASSISTANT_ROLE || active != currentAssistant()) return null
        // During tool rounds SQLite's active assistant advances but the model session retains
        // the original open's input. Use the parent-verified actual-open witness, not request data.
        val plan = frozenPlan ?: return null
        return ControllerVideoPostAuthority(this, runEpoch, epoch, provider.asModelSettings(),
            plan.history, actual.inputs)
    }
}

/** Acceptance consumes text only in the original editor, even if a newer draft has equal text. */
internal fun videoEditorReceiptMatches(original: ControllerVideoEditor, current: ControllerVideoEditor,
    request: VideoDraftRequest, receipt: VideoDraftReceipt): Boolean =
    original == current && original.visible && original.conversation == request.conversation &&
        original.text == request.text && videoReceiptMatches(request, receipt) &&
        receipt.outcome == VideoDraftOutcome.ACCEPTED
