package app.daiyu.android

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.host.BrowserToolTakeoverToken
import app.daiyu.android.browser.session.BrowserTabLease
import app.daiyu.android.domain.Message
import app.daiyu.android.integration.tools.CatalogBrowserBinding
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Holds only the original run browser. Opening a visit neither sends nor acquires a tab. */
internal class ControllerBrowserVisual(
    val owner: Message,
    private val binding: CatalogBrowserBinding,
    private val ownerCurrent: () -> Boolean,
    private val pauseOriginal: () -> Boolean,
    private val originalPaused: () -> Boolean,
    private val resumeOriginal: () -> Boolean,
    private val stopOriginal: () -> Boolean,
    private val attachVisible: (BrowserToolTakeoverToken, ViewGroup) -> CatalogBrowserBinding.VisibleAttachment =
        binding::attachVisible,
) {
    internal data class State(
        val live: Boolean = false, val lease: BrowserTabLease? = null,
        val takenOver: Boolean = false, val waiting: Boolean = false, val mounted: Boolean = false,
        val unknown: Boolean = false, val notice: String = "",
        val tabs: List<BrowserTabLease> = emptyList(),
        val page: app.daiyu.android.browser.openminis.BrowserVisiblePage? = null,
    )
    internal class Visit internal constructor(private val controller: ControllerBrowserVisual) : AutoCloseable {
        internal val active = AtomicBoolean(true)
        val owner: Message get() = controller.owner
        fun snapshot() = controller.snapshot(this)
        fun takeover(lease: BrowserTabLease) = controller.takeover(this, lease)
        fun mount(parent: ViewGroup) = controller.mount(this, parent)
        fun unmount(parent: ViewGroup) = controller.unmount(this, parent)
        fun navigate(url: String) = controller.navigate(this, url)
        fun newTab() = controller.newTab(this)
        fun restore() = controller.restore(this)
        fun closeTab() = controller.closeTab(this)
        fun stop() = controller.stop(this)
        override fun close() = controller.leave(this)
    }
    private class Mount(val visit: Visit, val token: BrowserToolTakeoverToken, val parent: ViewGroup) {
        var attachment: CatalogBrowserBinding.VisibleAttachment? = null
        var closeStarted = false
    }
    private class Pending(val visit: Visit, val lease: BrowserTabLease?, val create: Boolean = false) {
        var admitted = false
    }
    private val pending = AtomicReference<Pending?>()
    private val main = Handler(Looper.getMainLooper())
    private val revoked = AtomicBoolean()
    private val currentVisit = AtomicReference<Visit?>()
    private val mounted = AtomicReference<Mount?>()
    @Volatile private var token: BrowserToolTakeoverToken? = null
    @Volatile private var unknown = false
    @Volatile private var notice = "打开页面不会发送消息。接管只使用本会话原浏览器；网站登录状态可能共享。"
    private val failedAttachments = mutableListOf<CatalogBrowserBinding.VisibleAttachment>()
    val automationHeld: Boolean get() = pending.get() != null || token != null || unknown

    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "browser_visible_main_required" }
    private fun current(visit: Visit) = !revoked.get() && visit.active.get() && currentVisit.get() === visit && ownerCurrent()
    fun openVisit(): Visit? {
        requireMain()
        if (revoked.get() || !ownerCurrent()) return null
        val opened = Visit(this)
        currentVisit.getAndSet(opened)?.close()
        return if (current(opened)) opened else { opened.close(); null }
    }
    private fun snapshot(visit: Visit): State {
        requireMain()
        if (!current(visit)) return State(unknown = unknown, notice = "原浏览器访问已卸载或运行已结束；不会自动恢复或重开。")
        advanceTakeover(visit)
        val held = token
        val mount = mounted.get()
        val page = if (!unknown && mount?.visit === visit && !mount.closeStarted) try {
            mount.attachment?.page()
        } catch (_: Exception) { markUnknown("页面状态读取未确认，请停止原任务后检查。"); null } else null
        return State(true, held?.lease ?: binding.session.currentTab, held != null, pending.get()?.visit === visit,
            mount?.visit === visit && mount.token === held && mount.attachment != null && !mount.closeStarted,
            unknown, notice, binding.session.visibleTabs, page)
    }
    private fun takeover(visit: Visit, lease: BrowserTabLease) {
        requireMain()
        if (!current(visit) || unknown || token != null || pending.get() != null) return
        if (binding.session.currentTab != lease) { notice = "原标签已变化；未接管。"; return }
        val request = Pending(visit, lease)
        pending.set(request)
        val accepted = try { pauseOriginal() } catch (_: Exception) { false }
        if (!accepted) {
            pending.compareAndSet(request, null)
            notice = "原任务暂停未受理；未接管。"
            return
        }
        notice = "等待原任务到达暂停边界；尚未接管，原工具批次允许正常收尾。"
        advanceTakeover(visit)
    }
    private fun newTab(visit: Visit) {
        requireMain()
        if (!current(visit) || unknown || pending.get() != null) return
        if (binding.session.visibleTabs.size >= 3) { notice = "最多打开三个标签；未新建。"; return }
        val request = Pending(visit, binding.session.currentTab, create = true)
        pending.set(request)
        val accepted = try { originalPaused() || pauseOriginal() } catch (_: Exception) { false }
        if (!accepted) {
            pending.compareAndSet(request, null)
            notice = "原任务暂停未受理；未新建标签。"
            return
        }
        notice = "等待原任务暂停后新建空白标签；不会自动继续任务。"
        advanceTakeover(visit)
    }

    private fun createTakenOverTab(visit: Visit) {
        if (binding.session.visibleTabs.size >= 3) { notice = "最多打开三个标签；未新建。"; return }
        val old = token
        if (old != null) {
            mounted.get()?.let(::detach)
            if (!current(visit) || unknown) return
            val released = binding.session.releaseTakeover(old)
            if (token === old) token = null
            check(binding.session.currentTab == released) { "BROWSER_STALE_TAB" }
        }
        if (!current(visit) || unknown) return
        val before = binding.session.visibleTabs.toSet()
        when (val outcome = binding.session.execute(mapOf("action" to "new_tab"))) {
            is BrowserOutcome.NotExecuted -> notice = "新建标签未执行；原任务保持暂停。"
            BrowserOutcome.Unknown -> markUnknown("新建标签结果 UNKNOWN；不重试或恢复自动化。")
            is BrowserOutcome.Confirmed -> {
                if (!current(visit)) return
                val created = checkNotNull(binding.session.currentTab)
                check(created !in before && created.owner == binding.session.grant.owner)
                check(org.json.JSONObject(outcome.result.text).getInt("tabId") == created.tabId)
                val acquired = binding.session.takeover(created)
                token = acquired
                if (acquired.cleanupFailure != null) markUnknown("新标签接管 UNKNOWN；保留原会话清理链，不重试。")
                else notice = "已在本会话新建空白标签；旧标签保留，任务未自动继续。"
            }
        }
    }

    private fun advanceTakeover(visit: Visit) {
        val request = pending.get()?.takeIf { it.visit === visit } ?: return
        if (!current(visit) || unknown) { pending.compareAndSet(request, null); return }
        if (request.admitted || !originalPaused()) return
        if (pending.get() !== request) return
        if (binding.session.currentTab != request.lease) {
            pending.compareAndSet(request, null)
            notice = "暂停期间原标签已变化；未接管。请明确重新选择。"
            return
        }
        if (request.create && token != null) {
            try {
                if (mounted.get()?.attachment?.isLoading == true) {
                    notice = "原标签仍在加载；加载结束后才新建。"
                    return
                }
            } catch (_: Exception) {
                pending.compareAndSet(request, null)
                markUnknown("原标签状态未确认；未新建标签。")
                return
            }
        }
        request.admitted = true
        try {
            if (request.create) {
                createTakenOverTab(visit)
                return
            }
            val acquired = binding.session.takeover(requireNotNull(request.lease))
            token = acquired
            if (acquired.cleanupFailure != null) markUnknown("接管失效处理 UNKNOWN；保留原资源，请停止原任务核实。")
            else notice = "已接管原标签。返回或后台只卸载页面，不自动恢复任务。"
        } catch (failure: Exception) {
            if (!request.create && failure is IllegalStateException && failure.message in setOf(
                    "BROWSER_TAB_ACTIVE", "BROWSER_OPENMINIS_NATIVE_ACTIVE", "BROWSER_STALE_TAB",
                    "BROWSER_TAB_MISSING", "BROWSER_TAKEOVER_UNSUPPORTED")) {
                notice = "原标签未满足接管条件；未接管，原任务保持暂停。"
            } else markUnknown("接管结果 UNKNOWN；保留原绑定，不重试或创建替代标签。")
        } finally { pending.compareAndSet(request, null) }
    }
    private fun mount(visit: Visit, parent: ViewGroup) {
        requireMain()
        val held = token ?: return
        if (!current(visit) || unknown) return
        val previous = mounted.get()
        if (previous?.visit === visit && previous.parent === parent && previous.token === held) return
        previous?.let(::detach)
        if (!current(visit) || unknown) return
        val attempt = Mount(visit, held, parent)
        mounted.set(attempt)
        try {
            attempt.attachment = attachVisible(held, parent)
            if (!current(visit) || mounted.get() !== attempt) detach(attempt)
        } catch (_: Exception) { markUnknown("原标签挂载 UNKNOWN；不重试或创建替代 WebView。") }
    }
    private fun unmount(visit: Visit, parent: ViewGroup) {
        requireMain()
        mounted.get()?.takeIf { it.visit === visit && it.parent === parent }?.let(::detach)
    }
    private fun detach(original: Mount) {
        requireMain()
        mounted.compareAndSet(original, null)
        if (original.closeStarted) return
        val attachment = original.attachment ?: return
        original.closeStarted = true
        try { attachment.close() } catch (_: Exception) {
            failedAttachments.add(attachment)
            markUnknown("卸载结果 UNKNOWN；禁止恢复或重新挂载，请停止原任务。")
        }
    }
    private fun navigate(visit: Visit, input: String) {
        requireMain()
        if (!current(visit) || unknown || pending.get() != null) return
        val original = mounted.get()?.takeIf { it.visit === visit && it.token === token && !it.closeStarted } ?: return
        val url = input.trim()
        if (url.isEmpty() || url.length > 4096 || url.any { it.code < 32 || it.code == 127 } ||
            !app.daiyu.android.browser.openminis.OpenMinisNavigationPolicy.permits(url)) {
            notice = "请输入允许的完整 HTTP(S) 地址；未导航。"; return
        }
        try { original.attachment?.navigate(url); notice = "已请求导航；不代表加载完成，未发送聊天消息。" }
        catch (_: Exception) { markUnknown("人工导航 UNKNOWN；不会重发或恢复自动化。") }
    }
    private fun restore(visit: Visit) {
        requireMain()
        if (!current(visit) || unknown || pending.get() != null) return
        val held = token ?: return
        try {
            if (mounted.get()?.attachment?.isLoading == true) {
                notice = "原标签仍在加载；尚未卸载或归还，请等加载结束后明确归还。"
                return
            }
        } catch (_: Exception) { markUnknown("原标签状态未确认；未归还，请停止原任务核实。"); return }
        mounted.get()?.let(::detach)
        if (!current(visit) || unknown) return
        try { binding.session.releaseTakeover(held); if (token === held) token = null }
        catch (_: Exception) { markUnknown("浏览器归还未确认；未继续原任务，不重试旧工具。可停止原任务核实。"); return }
        if (!current(visit)) return
        val resumed = try { resumeOriginal() } catch (_: Exception) { false }
        notice = if (resumed) "原浏览器已归还，已请求继续同一任务。" else "原浏览器已归还，原任务未确认继续；请返回聊天核实。"
    }
    private fun closeTab(visit: Visit) {
        requireMain()
        if (!current(visit) || unknown || pending.get() != null) return
        val held = token ?: return
        try {
            // Explicit close accepts a loading attachment; it never makes the tab model-ready.
            binding.session.closeTab(held)
            if (token === held) token = null
            mounted.get()?.let(::detach)
            notice = "已请求关闭原标签；不是物理排空证明，任务未自动继续。"
        } catch (_: Exception) { markUnknown("关闭标签 UNKNOWN；保留原资源，不重试或替换。") }
    }
    private fun stop(visit: Visit) {
        requireMain()
        if (!current(visit)) return
        val accepted = try { stopOriginal() } catch (_: Exception) { false }
        if (!accepted) notice = "原任务停止未确认；原资源仍由原清理链持有。"
    }
    private fun markUnknown(value: String) { unknown = true; notice = value }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else if (!main.post { action() }) markUnknown("卸载队列未受理；保留原 attachment，由原 host 清理。")
    }
    private fun leave(visit: Visit) {
        visit.active.set(false)
        pending.get()?.takeIf { it.visit === visit }?.let { pending.compareAndSet(it, null) }
        currentVisit.compareAndSet(visit, null)
        val original = mounted.get()?.takeIf { it.visit === visit } ?: return
        onMain { detach(original) }
    }
    fun revoke() {
        revoked.set(true)
        pending.set(null)
        currentVisit.getAndSet(null)?.active?.set(false)
        val original = mounted.get() ?: return
        onMain { detach(original) }
    }
}
