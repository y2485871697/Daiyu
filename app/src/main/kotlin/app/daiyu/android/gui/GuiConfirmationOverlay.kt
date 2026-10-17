package app.daiyu.android.gui

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Service-owned, touchable but NOT focusable. No Activity/Compose transition is needed to confirm.
 * Every target event, including events while the overlay is shown, still invalidates generation.
 * Only preparation renders. After final observation the view is static until rejection/close.
 */
internal class GuiConfirmationOverlay(
    private val service: GuiAccessibilityService,
    val session: GuiRunSession,
    private val operation: GuiOperation,
    private val input: String?,
    private val ownerCurrent: () -> Boolean,
    private val onNotExecuted: (String) -> Unit,
    private val onRevoked: () -> Unit,
    private val deliver: (GuiConfirmedDelivery) -> Boolean,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val ticket = GuiConfirmationTicket()
    private val panel = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 12, 16, 12)
        setBackgroundColor(Color.rgb(30, 30, 30))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private var attached = false
    private var disposed = false
    private var delivered = false
    private var reportedNotExecuted = false
    private var reportedRevoked = false
    private var closeCode = "CONFIRMATION_CLOSED_NOT_EXECUTED"
    private fun active(): Boolean = !disposed && session.preparationAvailable() && try { ownerCurrent() } catch (_: Exception) { false }
    private val watchdog = object : Runnable {
        override fun run() {
            if (disposed || delivered) return
            if (!active()) { revoke(); return }
            GuiRuntime.main.postDelayed(this, 250)
        }
    }
    fun show() {
        require((operation == GuiOperation.INPUT) == (input != null))
        require(input == null || input.length <= 4096)
        renderPicker("请先切到要操作的外部应用，再点读取。此浮层不抢焦点；不授予系统权限、不执行动作。")
        windows.addView(panel, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM; title = "Daiyu 精确动作确认" })
        attached = true
        GuiRuntime.main.post(watchdog)
    }
    private fun label(parent: LinearLayout, value: String) {
        parent.addView(TextView(service).apply { text = value; setTextColor(Color.WHITE); textSize = 14f })
    }
    private fun button(parent: LinearLayout, title: String, callback: () -> Unit) {
        parent.addView(Button(service).apply {
            text = title
            // Do not mutate enabled/text/selection when clicked after final observation.
            setOnClickListener {
                try { callback() }
                catch (_: Exception) { revoke("CONFIRMATION_ABORTED_BEFORE_TRANSFER_NOT_EXECUTED") }
            }
        })
    }
    private fun body(): LinearLayout {
        val content = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(service).apply { addView(content) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (service.resources.displayMetrics.density * 240).toInt()))
        return content
    }
    private fun cancelButton(epoch: Long) = button(panel, "取消 / 撤销后续授权（不保证撤回已交付动作）") {
        if (ticket.current(epoch)) revoke()
    }
    private fun renderPicker(message: String) {
        if (disposed) return
        val epoch = ticket.next()
        panel.removeAllViews()
        label(body(), "$message\n动作：$operation\n完整输入：${input ?: "（无）"}\n" +
            "继续将读取整个有界 UI 树，而非仅所选节点；确认后整个有界观察和完整输入将发送给当前模型，可能进入任务或会话记录。请先移开敏感内容。")
        button(panel, "读取当前外部目标并选择") { if (ticket.current(epoch) && active()) readChoices() }
        cancelButton(epoch)
    }
    private fun readChoices() {
        session.approveReadsFromUser(GuiReadApproval(1, 0))
        when (val result = session.previewFromUser()) {
            is GuiResult.Value -> {
                val observation = result.value
                if (observation.truncated) { renderPicker("未执行：目标树超过安全边界，不能精确确认。"); return }
                val epoch = ticket.next()
                panel.removeAllViews()
                val content = body()
                label(content, "实际应用：${observation.packageName} / window=${observation.windowId}\n选择真实目标；这不是最终授权。")
                val global = operation in setOf(GuiOperation.BACK, GuiOperation.HOME, GuiOperation.RECENTS, GuiOperation.NOTIFICATIONS)
                if (global) button(content, "选择全局动作 $operation") {
                    if (ticket.current(epoch) && active()) stage(observation, null)
                } else observation.nodes.filter { node ->
                    !node.password && node.enabled && when (operation) {
                        GuiOperation.TAP -> node.clickable
                        GuiOperation.LONG_PRESS -> node.longClickable
                        GuiOperation.INPUT -> node.editable
                        else -> node.scrollable
                    }
                }.forEach { node ->
                    button(content, "节点 ${node.id} · ${node.text} · ${node.description}\n${node.className} · ${node.viewId}\n${node.bounds}") {
                        if (ticket.current(epoch) && active()) stage(observation, node.id)
                    }
                }
                button(panel, "重新读取（撤销旧预览）") { if (ticket.current(epoch) && active()) readChoices() }
                cancelButton(epoch)
            }
            is GuiResult.Rejected -> renderPicker("未执行：${result.code}")
            is GuiResult.Unknown -> revoke()
        }
    }
    private fun stage(reference: GuiObservation, nodeId: Int?) {
        val epoch = ticket.next()
        val node = nodeId?.let { id -> reference.nodes.single { it.id == id } }
        panel.removeAllViews()
        label(body(), "确认唯一真实目标\n应用：${reference.packageName}\n窗口：${reference.windowId}\n" +
            "目标：${node?.let { "${it.className}\nviewId=${it.viewId}\n文字=${it.text}\n描述=${it.description}\nbounds=${it.bounds}" } ?: "该应用窗口的全局动作"}\n" +
            "动作：$operation\n完整输入（${input?.length ?: 0} UTF-16 字符）：\n${input ?: "（无）"}\n" +
            "确认后整个有界观察（不只是所选节点）及完整输入将发送给当前模型，可能进入任务或会话记录。" +
            "同一有界观察及精确动作交给本次新任务；模型不可重新观察后沿用授权。10 秒过期，变更须重确认。")
        var finalObservation: GuiObservation? = null
        var finalAction: GuiAction? = null
        button(panel, "确认以上精确动作并交给本次任务") {
            if (!ticket.current(epoch) || !active()) return@button
            val observation = finalObservation ?: return@button
            val action = finalAction ?: return@button
            val checked = session.validateExactActionFromUser(action)
            if (checked !is GuiResult.Value) { renderPicker("未执行：目标变化或过期，请重新确认。"); return@button }
            if (session.approveExactActionFromUser(action) !is GuiResult.Value) {
                renderPicker("未执行：旧观察已失效，请重新确认。"); return@button
            }
            val writes = session.freezeAuthorizationFromUser()
            // No view changes, navigation, dismissal, or observe between final capture and dispatch.
            delivered = true
            GuiRuntime.main.removeCallbacks(watchdog)
            val accepted = try { deliver(GuiConfirmedDelivery(session, observation, action, writes)) }
            catch (_: Exception) {
                // A throwing parent may already have queued the task: revoke, never assert not-executed.
                revoke("CONFIRMATION_TRANSFER_UNCONFIRMED")
                return@button
            }
            if (!accepted) {
                delivered = false // explicit refusal proves there was no parent handoff
                revoke("CONFIRMATION_TRANSFER_REJECTED_NOT_EXECUTED")
            }
        }
        cancelButton(epoch)
        // Render first; queued window/content events are NOT filtered. Any late event makes this stale.
        GuiRuntime.main.postDelayed({
            if (!ticket.current(epoch) || !active()) return@postDelayed
            try {
                session.approveReadsFromUser(GuiReadApproval(1, 0))
                val result = session.previewFromUser()
                if (result !is GuiResult.Value) { renderPicker("未执行：无法取得最终外部目标，请重新读取。"); return@postDelayed }
                when (val match = GuiExactTarget.unique(reference, nodeId, result.value)) {
                    is GuiResult.Value -> {
                        finalObservation = result.value
                        finalAction = GuiAction(result.value.observationId, operation, match.value?.id, input)
                        // No remaining read grant: delivery itself contains this approved observation.
                        session.approveReadsFromUser(GuiReadApproval(0, 0))
                    }
                    else -> renderPicker("未执行：展示目标与最终真实目标不唯一匹配，请重新确认。")
                }
            } catch (_: Exception) { revoke("FINAL_OBSERVATION_UNAVAILABLE_NOT_EXECUTED") }
        }, 350)
        GuiRuntime.main.postDelayed({
            if (ticket.current(epoch) && !delivered && !disposed) revoke("CONFIRMATION_EXPIRED_NOT_EXECUTED")
        }, 10_350)
    }
    /** Only newly subscribed LOCAL click/focus/selection events from this exact overlay may be
     * excluded. No window/content/text/scroll event is excluded, even from our own package.
     * A real external target can never share this attached overlay window ID/type/package. */
    @Suppress("DEPRECATION")
    fun isLocalInteraction(event: android.view.accessibility.AccessibilityEvent): Boolean {
        if (!attached || disposed || event.packageName?.toString() != service.packageName ||
            event.eventType !in setOf(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED,
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED,
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)) return false
        val own = panel.createAccessibilityNodeInfo()
        val same = try { own.windowId >= 0 && own.windowId == event.windowId } finally { own.recycle() }
        if (!same) return false
        val windows = service.windows
        return try { windows.any { it.id == event.windowId &&
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY } }
        finally { windows.forEach { it.recycle() } }
    }
    fun revoke(code: String = "CONFIRMATION_CANCELLED_OR_SERVICE_LOST_NOT_EXECUTED") {
        closeCode = code
        try { session.close() } finally {
            // Explicit overlay cancellation/service loss must reach the ORIGINAL parent even
            // after delivery. This is revocation, never a claim that an action did not execute.
            if (!reportedRevoked) {
                reportedRevoked = true
                try { onRevoked() } catch (_: Exception) { /* admission is already fenced */ }
            }
        }
    }
    fun dispose(): Boolean {
        disposed = true; ticket.close(); GuiRuntime.main.removeCallbacks(watchdog)
        if (attached) {
            try { windows.removeViewImmediate(panel); attached = false }
            catch (_: Exception) {
                if (!panel.isAttachedToWindow) attached = false
                else return false // cleanup failure is not a drain receipt; close can be retried
            }
        }
        if (!delivered && !reportedNotExecuted) {
            reportedNotExecuted = true
            val code = closeCode
            GuiRuntime.main.post { try { onNotExecuted(code) } catch (_: Exception) { /* already revoked */ } }
        }
        return true
    }
}
