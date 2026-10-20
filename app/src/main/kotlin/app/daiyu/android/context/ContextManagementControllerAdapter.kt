package app.daiyu.android.context

import java.util.concurrent.Executor
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ContextManagementOperation { REFRESH, ARCHIVE, SELECT, COMPRESS }
internal data class ContextManagementState(
    val owner: ContextManagementOwner,
    val snapshot: ContextManagementSnapshot? = null,
    val archive: ContextManagementArchive? = null,
    val operation: ContextManagementOperation? = null,
    val confirmingCompression: Boolean = false,
    val cancellationRequested: Boolean = false,
    val requiresRefresh: Boolean = false,
    val notice: String? = null,
)

/** Controlled UI contract. No fake/default callbacks and no Service access in a composable. */
internal interface ContextManagementControllerAdapter : AutoCloseable {
    val state: StateFlow<ContextManagementState>
    fun refresh()
    fun openArchive()
    fun nextArchivePage()
    fun previousArchivePage()
    fun selectCompression(selection: ContextManagementSelection): Boolean = false
    fun requestCompression()
    fun dismissCompression()
    fun confirmCompression()
    fun cancel()
}

/** Minimal seam to implement INSIDE ChatController. tryReserve must use idleAvailable(),
 * hasLiveChildTasks(owner), reservePreparation(), the exact selection generation and existing
 * owner. Never implement this with an independent lock or a Boolean "idle" from UI state.
 * Parent close/stop/selection invalidation revokes the token and cancels its same cancellation.
 * isCurrent is a short in-memory check, safe on the UI thread; no storage or model I/O. */
internal interface ContextManagementAdmission {
    fun isCurrent(owner: ContextManagementOwner): Boolean
    fun tryReserve(owner: ContextManagementOwner): ContextManagementLease?
}

/** UI-thread confined commands/completions; worker uses the parent's executor. A stale callback
 * cannot display another conversation's archive or release a newer preparation. Neither cancel
 * nor close releases a lease early: only its actual worker exit does so. */
internal class ControlledContextManagementAdapter(
    owner: ContextManagementOwner,
    private val admission: ContextManagementAdmission,
    private val backend: ContextManagementBackend,
    private val worker: Executor,
    private val ui: Executor,
) : ContextManagementControllerAdapter {
    private val mutable = MutableStateFlow(ContextManagementState(owner))
    override val state: StateFlow<ContextManagementState> = mutable.asStateFlow()
    private var closed = false
    private var confirmedSnapshot: ContextManagementSnapshot? = null
    private class Active(val lease: ContextManagementLease, val operation: ContextManagementOperation)
    private var active: Active? = null

    override fun refresh() {
        launch(ContextManagementOperation.REFRESH, { backend.snapshot(it) }) { snapshot ->
            mutable.value = mutable.value.copy(snapshot = snapshot, archive = null, requiresRefresh = false,
                notice = when {
                    snapshot.configuration == null -> "压缩配置不可用；本机会话仍可查看，请明确重新保存设置"
                    snapshot.summaryTarget == null -> "摘要目标或版本不可用；本机会话仍可查看，请重新保存独立摘要 profile 后刷新"
                    else -> null
                })
        }
    }

    override fun openArchive() = loadArchive(0)
    override fun nextArchivePage() {
        val offset = mutable.value.archive?.page?.nextOffset ?: return
        loadArchive(offset)
    }
    override fun previousArchivePage() {
        val offset = mutable.value.archive?.offset ?: return
        if (offset > 0) loadArchive(maxOf(0, offset - ContextManagementWorker.PAGE_SIZE))
    }
    private fun loadArchive(offset: Int) {
        val state = mutable.value
        val snapshot = state.snapshot ?: return
        if (state.requiresRefresh || snapshot.effective.archiveId == null) return
        launch(ContextManagementOperation.ARCHIVE, { backend.archive(it, snapshot, offset) }) { archive ->
            mutable.value = mutable.value.copy(archive = archive, notice = null)
        }
    }

    override fun selectCompression(selection: ContextManagementSelection): Boolean {
        val state = mutable.value
        val expected = state.snapshot ?: return false
        if (!available() || state.requiresRefresh) return false
        return launch(ContextManagementOperation.SELECT, { lease ->
            backend.selectCompression(lease, expected, selection).also { require(it.canCompress) }
        }) { selected ->
            confirmedSnapshot = selected
            mutable.value = mutable.value.copy(snapshot = selected, archive = null, requiresRefresh = false,
                confirmingCompression = true, notice = null)
        }
    }

    override fun requestCompression() {
        val state = mutable.value
        if (!available() || state.requiresRefresh || state.snapshot?.canCompress != true) return
        confirmedSnapshot = state.snapshot
        mutable.value = state.copy(confirmingCompression = true, notice = null)
    }
    override fun dismissCompression() {
        if (active == null) {
            confirmedSnapshot = null
            mutable.value = mutable.value.copy(confirmingCompression = false)
        }
    }
    override fun confirmCompression() {
        val state = mutable.value
        val expected = confirmedSnapshot ?: return
        if (!available()) {
            dismissCompression()
            return
        }
        if (!state.confirmingCompression || state.requiresRefresh || !expected.canCompress ||
            state.snapshot !== expected) return
        launch(ContextManagementOperation.COMPRESS, { backend.compress(it, expected) }) { receipt ->
            mutable.value = mutable.value.copy(requiresRefresh = true, archive = null,
                notice = if (receipt.archiveId == null) "没有可安全压缩的完整历史前缀；未发起摘要请求。请刷新查看。"
                else "压缩已提交，原始消息保留。档案 ID：${receipt.archiveId}；请刷新查看当前上下文。")
        }
    }

    override fun cancel() {
        val pending = active ?: return
        mutable.value = mutable.value.copy(cancellationRequested = true,
            notice = "正在取消并等待准备退出；不会自动重试。已提交的档案不会被撤回。")
        // Must be outside the controller monitor: callbacks cancel an active summary transport.
        pending.lease.cancellation.cancel()
    }
    override fun close() {
        if (closed) return
        closed = true
        confirmedSnapshot = null
        active?.lease?.cancellation?.cancel()
        // No owner-scoped contents remain renderable after this page visit is disposed.
        mutable.value = ContextManagementState(mutable.value.owner, notice = "上下文页面已关闭")
    }

    private fun available(): Boolean = !closed && active == null && admission.isCurrent(mutable.value.owner)

    private fun <T> launch(operation: ContextManagementOperation,
        work: (ContextManagementLease) -> T, success: (T) -> Unit): Boolean {
        if (!available()) return false
        confirmedSnapshot = null // Refresh/archive/consume invalidates the previous consent.
        val lease = admission.tryReserve(mutable.value.owner)
        if (lease == null) {
            mutable.value = mutable.value.copy(confirmingCompression = false,
                notice = "当前会话正在发送、准备或执行任务；未开始操作，请稍后刷新")
            return false
        }
        if (lease.owner != mutable.value.owner) {
            lease.finish()
            mutable.value = mutable.value.copy(confirmingCompression = false, requiresRefresh = true,
                notice = "准备归属不匹配；未开始操作，请重新打开页面")
            return false
        }
        val pending = Active(lease, operation)
        active = pending
        mutable.value = mutable.value.copy(operation = operation, confirmingCompression = false,
            cancellationRequested = false, notice = null)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            worker.execute {
                started.set(true)
                val result = try {
                    lease.cancellation.check()
                    lease.checkCurrent()
                    Result.success(work(lease))
                } catch (failure: Exception) { Result.failure<T>(failure) }
                finally { lease.finish() }
                ui.execute publish@ {
                    if (active !== pending) return@publish
                    active = null
                    if (closed) return@publish
                    if (!admission.isCurrent(mutable.value.owner)) {
                        mutable.value = ContextManagementState(mutable.value.owner,
                            notice = "会话已切换或失效；请重新打开上下文页面")
                        return@publish
                    }
                    mutable.value = mutable.value.copy(operation = null, cancellationRequested = false)
                    if (operation == ContextManagementOperation.SELECT && lease.cancellation.isCancelled) {
                        mutable.value = mutable.value.copy(confirmingCompression = false, requiresRefresh = true,
                            notice = "本次目标选择已取消；未发起摘要或聊天请求。请刷新后重新选择。")
                        return@publish
                    }
                    result.fold(onSuccess = success, onFailure = { failure ->
                        val mutation = operation == ContextManagementOperation.COMPRESS
                        mutable.value = mutable.value.copy(requiresRefresh = true,
                            notice = when {
                                operation == ContextManagementOperation.SELECT ->
                                    "摘要目标不可用、已取消或上下文已变化；未发起摘要或聊天请求。请刷新后重新选择。"
                                mutation && failure is SummaryProfileChangedException ->
                                    "摘要目标或 profile 版本已改变；未发起请求。请刷新目标并重新明确确认费用。"
                                mutation && failure is CancellationException ->
                                    "准备因取消退出；本操作未改写原始消息。档案提交结果请只刷新核对，不自动重试。"
                                mutation -> "压缩结果未确认；本操作未改写原始消息。请只刷新核对，不自动重试或回退聊天模型。"
                                else -> "本机上下文或档案不可用或已变化；请刷新。未发起摘要或聊天请求。"
                            })
                    })
                }
            }
        } catch (failure: Exception) {
            // A direct executor can propagate a started task's failure; never finish it twice.
            if (started.get()) throw failure
            // Executor rejection occurs before a worker starts: there is no summary to retry.
            active = null
            lease.finish()
            mutable.value = mutable.value.copy(operation = null, confirmingCompression = false,
                notice = "准备工作线程不可用；未开始操作")
            return false
        }
        return true
    }
}
