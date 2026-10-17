package app.daiyu.android.integration.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.daiyu.android.ControllerBrowserPreparation
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Retained BEFORE prepareOnWorker. The native session belongs to this resource unit, not to
 * the model worker or borrowed catalog. Its physical drain is the capacity-release evidence.
 */
internal class CatalogChildBrowserAssembly(
    private val context: Context?,
    private val session: CatalogChildBrowserSession,
    private val ledger: ToolLedger,
    private val openForTest: (() -> Boolean)? = null,
) : ChildToolAssembly {
    // JVM transport seam only; production always uses the worker helper + actual Android host.
    internal constructor(session: CatalogChildBrowserSession, ledger: ToolLedger, open: () -> Boolean) :
        this(null, session, ledger, open)
    private val closed = AtomicBoolean(false)
    override val scope: ChildRunScope = session.scope
    private val catalog = object : CatalogTool, ReceiptBatchToolExecutor {
        private var owner: RunExecutionOwner? = null
        private var bridge: CatalogModelBridge? = null
        @Synchronized override fun bindOwner(owner: RunExecutionOwner) {
            check(!closed.get()) { "child_closed" }
            this.owner?.let { require(it === owner) { "child_owner_replaced" }; return }
            // Runner's actual opaque token, before definitions; no manual dispatch or fake owner.
            val leaf = session.bindOwner(owner)
            require(leaf.taskAuthorization === scope.authorization)
            val registry = ToolRegistry()
            leaf.tools.forEach { registry.register(it) }
            bridge = CatalogModelBridge(registry.snapshot(), scope.session, ledger,
                ConversationId(scope.owner.conversationId), RunId(scope.owner.childRunId), scope.authorization)
                .also { it.bindOwner(owner) }
            this.owner = owner
        }
        override val definitions: List<app.daiyu.android.domain.ToolDefinition> get() = checkNotNull(bridge) { "child_owner_not_bound" }.definitions
        override fun execute(invocation: ToolInvocation): String = error("whole_batch_dispatch_required")
        override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> =
            checkNotNull(bridge).execute(message, invocations) { closed.get() || cancelled() }
        override fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart =
            checkNotNull(bridge).start(message, invocations) { closed.get() || cancelled() }
    }
    override val tools: CatalogTool get() = catalog
    override val receipts: ReceiptBatchToolExecutor get() = catalog

    override fun prepareOnWorker(): Boolean {
        openForTest?.let { return !closed.get() && it() && !closed.get() }
        val context = checkNotNull(context)
        check(Looper.myLooper() != Looper.getMainLooper()) { "browser_worker_required" }
        if (closed.get()) return false
        val root = ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
        if (closed.get()) return false
        val finished = CountDownLatch(1)
        var opened = false
        val posted = Handler(Looper.getMainLooper()).post {
            try { if (!closed.get()) opened = session.openOnMain(context, root) }
            finally { finished.countDown() }
        }
        if (!posted) return false
        try { finished.await() } catch (_: InterruptedException) {
            close() // Posted closure retains this assembly and session through late construction.
            Thread.currentThread().interrupt()
            return false
        }
        return opened && !closed.get()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) session.close()
    }
    override fun observeClose(observer: (ChildToolDrain) -> Unit) {
        // Observation ONLY. Polling must not retry close, reopen a host or GET again.
        Thread({
            try {
                while (true) {
                    when (val result = session.closeResult()) {
                        RunCatalogCloseResult.Drained -> { observer(ChildToolDrain.Drained); return@Thread }
                        is RunCatalogCloseResult.Unknown -> { observer(ChildToolDrain.Unknown(result.reason)); return@Thread }
                        null -> Thread.sleep(25)
                    }
                }
            } catch (_: Exception) { observer(ChildToolDrain.Unknown("browser_child_drain_observation_failed")) }
        }, "child-browser-drain").apply { isDaemon = true; start() }
    }
}
