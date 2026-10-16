package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.runtime.UiDispatcher
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Internal seam for deterministic host tests; production always uses the fixed BrowserUseManager. */
internal interface BlankBrowserResource {
    /** Thread-safe, bounded snapshot. False while any native callback/operation can still target
     * upstream's untagged deferreds. The Android implementation must track those callbacks. */
    val isIdle: Boolean get() = true
    /** Runs after allocation has an owner, so partial initialization remains destroyable. */
    fun initialize() {}
    suspend fun execute(input: BrowserActionInput): BrowserActionResult
    /** UI only. Update callback/history lease, without replacing the page or manager. */
    fun rebind(lease: BrowserTabLease) {}
    fun destroy()
}

/** No integer-id fallback or pool. Idle ownership transitions preserve the allocation and page;
 * any uncertain command/cancellation quarantines the allocation permanently. */
internal class LeaseBrowserHost(
    private val sessions: BrowserSessionRegistry,
    private val ui: UiDispatcher,
    dispatcher: CoroutineDispatcher,
    private val createBlank: (BrowserTabLease, (String) -> Unit, () -> Boolean) -> BlankBrowserResource,
    private val createTimeoutMillis: Long = 5_000,
    private val operationTimeoutMillis: Long = 8_000,
) : AsyncBrowserBackend, BrowserTabLifecycle {
    private class Slot(val allocation: BrowserTabLease) {
        var lease = allocation
        var suspended = false
        var needsRebind = false
        var operation = 0L
        var retired = false
        var resource: BlankBrowserResource? = null
        var invalidation: AutoCloseable? = null
        var pending: CompletableFuture<BrowserExecutionResult>? = null
        var job: Job? = null
        val created = CompletableFuture<Unit>()
        // Unlike created (which retirement settles early), this tracks the real factory frame.
        var making = true
        var activeExecutions = 0
        var cleanup: (() -> Unit)? = null
        var cleanupQueued = false
        var cleanupFailure: Exception? = null
        var nativeCleanupFailure: Exception? = null
    }
    private val lock = Any()
    private val slots = mutableMapOf<BrowserTabLease, Slot>()
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        require(createTimeoutMillis in 1..60_000 && operationTimeoutMillis in 1..60_000)
    }

    override val supportsTakeover: Boolean = true

    override fun create(lease: BrowserTabLease) {
        val slot = Slot(lease)
        synchronized(lock) {
            check(!closed) { "BROWSER_OPENMINIS_CLOSED" }
            check(lease !in slots) { "BROWSER_OPENMINIS_RESOURCE_EXISTS" }
            slots[lease] = slot
        }
        var dispatched = false
        try {
            val subscription = sessions.observeOwnership(lease,
                beforeTakeover = {
                    synchronized(lock) {
                        check(!slot.retired && !slot.suspended && !slot.needsRebind) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
                        check(slot.pending == null && slot.job == null) { "BROWSER_TAB_ACTIVE" }
                        check(slot.resource?.isIdle == true) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" }
                    }
                },
                changed = { changed, takenOver ->
                    synchronized(lock) {
                        slot.lease = changed
                        slot.suspended = takenOver
                        slot.needsRebind = true
                    }
                },
                closed = { retire(slot, "BROWSER_LEASE_INVALIDATED") },
            )
            val closeNow = synchronized(lock) {
                if (slot.retired) true else { slot.invalidation = subscription; false }
            }
            if (closeNow) subscription.close()
            if (ui.isUiThread()) {
                dispatched = true
                makeBlank(slot)
            } else {
                ui.post { makeBlank(slot) }
                dispatched = true
                slot.created.get(createTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            // On UI, makeBlank reports construction errors through the same future (no waiting).
            slot.created.join()
        } catch (failure: Exception) {
            if (!dispatched) synchronized(lock) { slot.making = false }
            retire(slot, "BROWSER_OPENMINIS_CREATE_FAILED")
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        }
    }

    private fun makeBlank(slot: Slot) {
        var unattached: BlankBrowserResource? = null
        try {
            check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
            sessions.publish(slot.lease) { checkLive(slot) }
            // Only a blank WebView. Manager init (including its cookie changes) is NOT lifecycle.
            unattached = createBlank(slot.lease, { reason -> retire(slot, reason) }, {
                isLive(slot)
            })
            checkNotNull(unattached).initialize()
            synchronized(lock) {
                check(!slot.retired) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
                slot.resource = unattached
                unattached = null
            }
            slot.created.complete(Unit)
        } catch (failure: Exception) {
            try { unattached?.destroy() } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
                synchronized(lock) { recordNativeFailure(slot, cleanup) }
            }
            slot.created.completeExceptionally(failure)
            retire(slot, "BROWSER_OPENMINIS_CREATE_FAILED")
        } finally {
            synchronized(lock) { slot.making = false }
        }
    }

    override fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult> {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        // Pure support validation must precede slot mutation and UI enqueue.
        val input = OpenMinisCommandMapper.map(command)
        // Called UNDER the registry's admission lock: state + enqueue ONLY. Never launch here.
        val pending = CompletableFuture<BrowserExecutionResult>()
        val slot = synchronized(lock) {
            val value = slots.values.firstOrNull { it.lease == lease }
                ?: error("BROWSER_OPENMINIS_RESOURCE_MISSING")
            check(!value.retired && !value.suspended && !value.needsRebind && value.resource != null) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
            check(value.pending == null && value.job == null) { "BROWSER_OPENMINIS_BUSY" }
            value.operation++
            value.pending = pending
            value
        }
        try {
            ui.post { start(slot, command, input, pending) }
        } catch (failure: Exception) {
            // No WebView work even on rejection. Retire via another bounded enqueue;
            // if the dispatcher is permanently dead, disposal cannot be guaranteed.
            try { retire(slot, "BROWSER_OPENMINIS_DISPATCH_REJECTED") }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            pending.completeExceptionally(failure)
            throw failure
        }
        return pending
    }

    private fun start(slot: Slot, command: BrowserCommand, input: BrowserActionInput,
                      pending: CompletableFuture<BrowserExecutionResult>) {
        // Never read the mutable execution lease from a late continuation.
        val (lease, operation) = synchronized(lock) { slot.lease to slot.operation }
        try {
            check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
            val timeout = (command.arguments["timeout_ms"] as Int?)?.toLong()
                ?.coerceAtMost(operationTimeoutMillis) ?: operationTimeoutMillis
            // The native prefix through its first suspension runs UNDER the same reentrant fence
            // as permission/ownership changes. No check-then-call gap and no waits in the fence.
            val job = sessions.startNative(lease, command.action) {
                checkLive(slot)
                val resource = synchronized(lock) {
                    check(slot.pending === pending && slot.operation == operation && !slot.suspended && !slot.needsRebind) { "BROWSER_OPENMINIS_BUSY" }
                    checkNotNull(slot.resource)
                }
                synchronized(lock) { slot.activeExecutions++ }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val answer = withTimeout(timeout) { resource.execute(input) }
                        val result = OpenMinisCommandMapper.result(command, answer)
                        sessions.publish(lease) {
                            checkLive(slot)
                            synchronized(lock) {
                                check(slot.pending === pending && slot.operation == operation) { "BROWSER_OPENMINIS_STALE_OPERATION" }
                                slot.pending = null
                                slot.job = null
                            }
                            pending.complete(result)
                        }
                    } catch (failure: Exception) {
                        // Fixed-source deferreds have no callback token: unknown means never reuse.
                        try { retire(slot, "BROWSER_OPENMINIS_EXECUTION_UNKNOWN") }
                        finally { pending.completeExceptionally(failure) }
                    } finally {
                        synchronized(lock) { slot.activeExecutions-- }
                    }
                }
            }
            val cancelNow = synchronized(lock) {
                if (slot.retired || slot.operation != operation) true
                else {
                    if (slot.pending === pending && !job.isCompleted) slot.job = job
                    false
                }
            }
            if (cancelNow) job.cancel()
        } catch (failure: Exception) {
            try { retire(slot, "BROWSER_OPENMINIS_EXECUTION_UNKNOWN") }
            finally { pending.completeExceptionally(failure) }
        }
    }

    private fun checkLive(slot: Slot) = synchronized(lock) {
        check(!slot.retired && slots[slot.allocation] === slot) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
    }

    override fun cancel(lease: BrowserTabLease) {
        val slot = synchronized(lock) { slots.values.firstOrNull { it.lease == lease } } ?: return
        // A delayed cancel for an earlier generation cannot retire the resumed page.
        sessions.serialized {
            if (synchronized(lock) { slot.lease == lease }) retire(slot, "BROWSER_OPENMINIS_CANCELLED")
        }
    }

    override fun close(lease: BrowserTabLease) {
        val slot = synchronized(lock) { slots[lease] } ?: return
        retire(slot, "BROWSER_OPENMINIS_CLOSED")
    }

    private fun isLive(slot: Slot): Boolean = try {
        sessions.serialized {
            val lease = synchronized(lock) { slot.lease }
            sessions.assertCurrent(lease)
            synchronized(lock) { !slot.retired && !slot.suspended && !slot.needsRebind && slots[slot.allocation] === slot }
        }
    } catch (_: Exception) { false }

    override fun rebind(allocation: BrowserTabLease, lease: BrowserTabLease) {
        val done = CompletableFuture<Unit>()
        val task = {
            try {
                check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
                sessions.publish(lease) {
                    val resource = synchronized(lock) {
                        val slot = slots[allocation] ?: error("BROWSER_OPENMINIS_RESOURCE_MISSING")
                        check(!slot.retired && !slot.suspended && slot.lease == lease) { "BROWSER_STALE_TAB" }
                        check(slot.pending == null && slot.job == null) { "BROWSER_TAB_ACTIVE" }
                        checkNotNull(slot.resource)
                    }
                    // Contract: bounded UI-only lease replacement; no native operations or waits.
                    resource.rebind(lease)
                    synchronized(lock) {
                        val slot = checkNotNull(slots[allocation])
                        check(!slot.retired && slot.lease == lease) { "BROWSER_STALE_TAB" }
                        slot.needsRebind = false
                    }
                }
                done.complete(Unit)
                Unit
            } catch (failure: Exception) {
                done.completeExceptionally(failure)
                Unit
            }
        }
        if (ui.isUiThread()) task() else ui.post(task)
        try { done.get(createTimeoutMillis, TimeUnit.MILLISECONDS) }
        catch (failure: Exception) {
            synchronized(lock) { slots[allocation] }?.let { retire(it, "BROWSER_OPENMINIS_REBIND_UNKNOWN") }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        }
    }

    /** Trusted UI-only bounded read, e.g. current URL for cookie scope. Never integer fallback.
     * read must not navigate, wait, or mutate ownership. */
    fun <T> withResource(lease: BrowserTabLease, read: (BlankBrowserResource) -> T): T {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        return sessions.publish(lease) {
            val resource = synchronized(lock) {
                val slot = slots.values.firstOrNull { it.lease == lease }
                    ?: error("BROWSER_OPENMINIS_RESOURCE_MISSING")
                check(!slot.retired && !slot.suspended && !slot.needsRebind) { "BROWSER_OPENMINIS_RESOURCE_RETIRED" }
                checkNotNull(slot.resource)
            }
            read(resource)
        }
    }

    /** Trusted UI host only, never model-facing. Callback must not wait or perform commands.
     * The full token generation and original allocation must refer to the same idle page. */
    fun withTakenOverResource(
        allocation: BrowserTabLease, tokenLease: BrowserTabLease, block: (BlankBrowserResource) -> Unit,
    ) {
        check(ui.isUiThread()) { "BROWSER_DISPATCH_NOT_UI" }
        sessions.publishTakenOver(tokenLease) {
            val resource = synchronized(lock) {
                val slot = slots[allocation] ?: error("BROWSER_OPENMINIS_RESOURCE_MISSING")
                check(!slot.retired && slot.suspended && slot.lease == tokenLease) { "BROWSER_TAKEOVER_EXPIRED" }
                check(slot.pending == null && slot.job == null) { "BROWSER_TAB_ACTIVE" }
                checkNotNull(slot.resource).also { check(it.isIdle) { "BROWSER_OPENMINIS_NATIVE_ACTIVE" } }
            }
            block(resource)
        }
    }

    /** Seal admission and request every tombstone's cleanup; never wait for the UI queue. */
    fun closeWithResult(): BrowserCloseResult {
        val selected = synchronized(lock) { closed = true; slots.values.toList() }
        selected.forEach { slot ->
            try { retire(slot, "BROWSER_OPENMINIS_CLOSED"); scheduleCleanup(slot) }
            catch (_: Exception) { /* rejected enqueue is retained on the slot */ }
        }
        return closeStatus()
    }

    fun closeStatus(): BrowserCloseResult = synchronized(lock) {
        BrowserCloseResult(closed, buildSet {
            if (slots.values.any { it.making }) add("WEBVIEW_CREATION")
            if (slots.values.any { !it.retired || it.cleanup != null }) add("WEBVIEW_CLEANUP")
            if (slots.values.any { it.activeExecutions != 0 }) add("WEBVIEW_EXECUTION")
        }, buildSet {
            if (slots.values.any { it.cleanupFailure != null }) add("WEBVIEW_CLEANUP_QUEUE")
            if (slots.values.any { it.nativeCleanupFailure != null }) add("WEBVIEW_DESTROY")
        })
    }

    /** Trusted host may retry only rejected enqueue, never replay a native destroy attempt. */
    fun retryCleanup(lease: BrowserTabLease) {
        val slot = synchronized(lock) { slots[lease] } ?: return
        scheduleCleanup(slot)
    }

    fun cleanupFailure(lease: BrowserTabLease): Exception? =
        synchronized(lock) { slots[lease]?.let { it.nativeCleanupFailure ?: it.cleanupFailure } }

    // Called under lock. Terminal native teardown errors survive later successful queue cleanup.
    private fun recordNativeFailure(slot: Slot, failure: Exception) {
        val previous = slot.nativeCleanupFailure
        if (previous == null) slot.nativeCleanupFailure = failure else previous.addSuppressed(failure)
    }

    private fun retire(slot: Slot, reason: String) {
        val pending: CompletableFuture<BrowserExecutionResult>?
        synchronized(lock) {
            if (slot.retired) return
            slot.retired = true
            val resource = slot.resource
            slot.resource = null
            pending = slot.pending
            slot.pending = null
            val job = slot.job
            slot.job = null
            val subscription = slot.invalidation
            slot.invalidation = null
            slot.cleanup = {
                var failure: Exception? = null
                fun attempt(action: () -> Unit) {
                    try { action() } catch (e: Exception) {
                        if (failure == null) failure = e else failure.addSuppressed(e)
                    }
                }
                attempt { subscription?.close() }
                attempt { job?.cancel() }
                attempt { resource?.destroy() }
                synchronized(lock) {
                    slot.cleanup = null
                    slot.cleanupFailure = null
                    if (failure != null) recordNativeFailure(slot, failure)
                    // Keep a small tombstone until this run-owned host is discarded: a cancelled
                    // lease must never be re-created even after its native resource was destroyed.
                }
            }
        }
        val failure = IllegalStateException(reason)
        slot.created.completeExceptionally(failure)
        pending?.completeExceptionally(failure)
        scheduleCleanup(slot)
    }

    private fun scheduleCleanup(slot: Slot) {
        val task = synchronized(lock) {
            if (slot.cleanupQueued) return
            val task = slot.cleanup ?: return
            slot.cleanupQueued = true
            task
        }
        try {
            // Always enqueue, even from UI or registry callbacks. Keep ownership on rejection.
            ui.post(task)
        } catch (failure: Exception) {
            synchronized(lock) {
                slot.cleanupQueued = false
                slot.cleanupFailure = failure
            }
            throw failure
        }
    }
}
