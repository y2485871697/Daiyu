package app.daiyu.android.integration.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.openminis.BrowserRunConstructionException
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import java.util.concurrent.CompletableFuture

/** Advertising never creates a WebView. First admitted execution opens this run's one host. */
internal class RunOrdinaryBrowserTools private constructor(
    private val context: Context,
    owner: RunExecutionOwner,
    authorization: TaskAuthorization,
    private val assistant: AssistantProfile,
    assistantMessageId: MessageId,
    settings: BrowserSettings,
    private val opened: (CatalogBrowserBinding) -> Unit,
    private val childScope: app.daiyu.android.domain.delegation.ChildRunScope?,
    parentBrowserPermission: BrowserDefaultPermission?,
 ) : RunOwnedToolScope(owner, authorization) {
    constructor(context: Context, owner: RunExecutionOwner, authorization: TaskAuthorization,
        assistant: AssistantProfile, assistantMessageId: MessageId, settings: BrowserSettings,
        opened: (CatalogBrowserBinding) -> Unit) : this(context, owner, authorization, assistant,
        assistantMessageId, settings, opened, null, null)

    companion object {
        fun child(context: Context, owner: RunExecutionOwner, authorization: TaskAuthorization,
            assistant: AssistantProfile, assistantMessageId: MessageId, settings: BrowserSettings,
            scope: app.daiyu.android.domain.delegation.ChildRunScope,
            parentPermission: BrowserDefaultPermission): RunOrdinaryBrowserTools =
            RunOrdinaryBrowserTools(context, owner, authorization, assistant, assistantMessageId,
                settings, {}, scope, parentPermission)
    }

    private val grant = BrowserGrant(BrowserOwner(owner.runId.value,
        if (childScope == null) BrowserOwnerKind.MAIN else BrowserOwnerKind.CHILD), settings.access)
    private val permission = if (childScope == null) {
        require(parentBrowserPermission == null)
        BrowserDefaultPermission.main(grant)
    } else {
        require(owner.runId.value == childScope.owner.childRunId && owner.conversationId.value == childScope.owner.conversationId &&
            assistantMessageId.value == childScope.owner.assistantMessageId && authorization === childScope.authorization)
        BrowserDefaultPermission.child(grant, childScope, checkNotNull(parentBrowserPermission))
    }
    private val preparation = if (childScope == null) BrowserRunPreparation.prepare(
        BrowserRunIdentity(owner.conversationId, owner.runId, assistant.id, assistantMessageId),
        settings, grant, permission.actions, defaultPermission = permission) else
        BrowserRunPreparation.prepareChild(childScope, assistant.id, settings, grant, permission.actions,
            defaultPermission = permission)
    private val confirmed = preparation.confirm(preparation.preview)
    private val main = Handler(Looper.getMainLooper())
    private var opening: CompletableFuture<CatalogBrowserBinding?>? = null
    @Volatile private var binding: CatalogBrowserBinding? = null
    @Volatile private var partial: BrowserRunConstructionException? = null
    @Volatile private var partialClose: BrowserCloseResult? = null
    init {
        retain(AutoCloseable {
            confirmed.revoke()
            binding?.close()
            partial?.let { partialClose = it.closeWithResult() }
        }, ::observeDrain)
    }
    override val tools = if (settings.access == BrowserAccess.DISABLED) emptyList() else listOf(false, true).map { write ->
        object : RegisteredTool {
            override val definition = RunBrowserTools.definition(write, permission.actions)
            override fun prepare(arguments: ToolArguments): PreparedTool {
                RunBrowserTools.validate(arguments.canonicalJson(), write)
                require(arguments.text("action") in permission.actions) { "browser_action_not_authorized" }
                val execute: (ToolExecutionContext) -> ExecutionReceipt = { invocation ->
                    val host = ensureOpen()
                    if (host == null || !live() || invocation.cancelled()) ExecutionReceipt.NotExecuted("browser_run_unavailable")
                    else {
                        confirmed.checkInvocation(owner, assistant.id, host.session, definition.name, invocation.invocation.arguments)
                        RunBrowserTools.executeBound(host.session, invocation, host.screenshotStore, write)
                    }
                }
                return if (write) this@RunOrdinaryBrowserTools.write(RunBrowserTools.writeEffect(grant, arguments.canonicalJson()), execute) else read(execute)
            }
        }
    }
    private fun ensureOpen(): CatalogBrowserBinding? {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "browser_worker_required" }
        var start = false
        val future = synchronized(this) {
            opening ?: CompletableFuture<CatalogBrowserBinding?>().also { opening = it; start = true }
        }
        if (start) {
            val root = try { ControllerBrowserPreparation.preparePrivateRootOnWorker(context) }
            catch (failure: Exception) { future.completeExceptionally(failure); throw failure }
            if (!main.post {
                if (!live()) future.complete(null)
                else try {
                    val host = CatalogBrowserBinding.open(context, root, assistant, confirmed)
                    binding = host
                    if (!live()) host.close()
                    future.complete(host)
                } catch (failure: Exception) {
                    partial = failure as? BrowserRunConstructionException
                    confirmed.revoke()
                    partial?.let { partialClose = it.closeWithResult() }
                    future.completeExceptionally(failure)
                }
            }) future.complete(null)
        }
        val host = future.get() ?: return null
        if (!live()) return null
        host.bindOwner(owner, authorization)
        opened(host)
        return host
    }
    override fun markUnknown() { super.markUnknown(); confirmed.revoke() }
    private fun observeDrain(done: (RunCatalogCloseResult) -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 30_000L
        val poll = object : Runnable {
            override fun run() {
                val future = synchronized(this@RunOrdinaryBrowserTools) { opening }
                val result = when {
                    future == null -> RunCatalogCloseResult.Drained
                    !future.isDone -> null
                    binding != null -> binding!!.closeResult()
                    partial != null -> partialClose?.let { status ->
                        when {
                            status.successful -> RunCatalogCloseResult.Drained
                            status.failures.isNotEmpty() -> RunCatalogCloseResult.Unknown("browser_partial_close_failed")
                            else -> null
                        }
                    }
                    else -> RunCatalogCloseResult.Drained
                }
                if (result != null) done(result)
                else if (SystemClock.uptimeMillis() >= deadline || !main.postDelayed(this, 50L))
                    done(RunCatalogCloseResult.Unknown("browser_close_unconfirmed"))
            }
        }
        poll.run()
    }
}
