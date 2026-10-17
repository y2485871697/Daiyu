package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.gui.*
import app.daiyu.android.ui.components.MiuixScaffoldPage

/** Independent settings/approval host. The default page grants nothing to any model.
 * An optional unbound session MUST come from a user-opened, reserved run owned by the parent.
 * onConfirmed must retain that same session in the original run lifecycle; false closes it.
 */
@Composable
internal fun GuiSettingsPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    session: GuiRunSession? = null,
    pendingAction: GuiAction? = null,
    ownerCurrent: () -> Boolean = { true },
    onObservation: (GuiObservation) -> Unit = {},
    onConfirmed: (GuiRunSession, Set<WriteEffect>) -> Boolean = { _, _ -> false },
    onExactConfirmed: (GuiConfirmedDelivery) -> Boolean = { false },
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOwner by rememberUpdatedState(ownerCurrent)
    var connected by remember { mutableStateOf(GuiRuntime.isConnected()) }
    var enabled by remember { mutableStateOf(GuiSystemSettings.enabledBySystem(context)) }
    var notice by remember(session) { mutableStateOf<String?>(null) }
    var frozen by remember(session) { mutableStateOf(false) }
    val page = remember(session) { GuiSettingsPage() }
    fun active() = page.active && currentOwner()
    fun refresh() {
        connected = GuiRuntime.isConnected()
        enabled = GuiSystemSettings.enabledBySystem(context)
    }
    fun describe(result: GuiResult<*>): String = when (result) {
        is GuiResult.Value -> "已记录本次明确选择；不代表已执行操作。"
        is GuiResult.Rejected -> "未执行：${result.code}"
        is GuiResult.Unknown -> "结果未知，不会自动重试：${result.code}"
    }
    val leave = {
        page.active = false
        if (!page.transferred) session?.close()
        onBack()
    }
    BackHandler(onBack = leave)
    DisposableEffect(page, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && active()) refresh() }
        lifecycle.addObserver(observer)
        onDispose {
            page.active = false
            if (!page.transferred) session?.close()
            lifecycle.removeObserver(observer)
        }
    }
    MiuixScaffoldPage(title = "无障碍与屏幕授权", onBack = leave, modifier = modifier) {
        item(key = "gui-system") {
            SettingsSectionTitle("系统服务（不是 Agent 授权）")
            SettingsCard {
                SettingsRow(title = if (connected) "服务已连接" else "服务未连接 · 不执行任何操作",
                    summary = if (enabled) "系统已启用；断开后旧会话和旧授权失效，不自动恢复任务。" else "系统尚未启用。请阅读风险并自行决定是否开启。",
                    tag = "gui-status")
                SettingsRow(title = "前往系统无障碍设置", summary = "可能读取个人信息、操作其他应用。仅你可在系统页面明确开启或关闭。",
                    tag = "gui-open-system", onClick = { if (active()) notice = describe(GuiSystemSettings.openFromUser(context)) })
                SettingsRow(title = "刷新服务状态", tag = "gui-refresh", onClick = { if (active()) refresh() })
                SettingsRow(title = "请求系统重新绑定", summary = "仅系统已启用时由你显式请求；系统可以拒绝，不保证连接或持续存活。无 root 保活、不点击权限、不静默启用。",
                    tag = "gui-rebind", onClick = { if (active()) { notice = describe(GuiSystemSettings.requestRebindFromUser(context)); refresh() } })
                SettingsRow(title = "服务重绑与能力边界", summary = "不提供强制保活、提权或后台自动观察。锁屏/其他会话占用时拒绝；截图仅 API 30+ 且另行授权。",
                    tag = "gui-boundaries")
            }
        }
        item(key = "gui-task") {
            SettingsSectionTitle("本次任务的明确授权")
            SettingsCard {
                SettingsRow(title = "屏幕与输入的发送范围",
                    summary = "本页不会读取屏幕或准备任务。具体任务确认前会读取整个有界 UI 树，而非仅所选节点；确认后整个有界观察和完整输入将发送给当前模型，可能进入任务或会话记录。请在实际读取前移开敏感内容。",
                    tag = "gui-disclosure")
                if (session == null) SettingsRow(title = "当前未申请屏幕工具", summary = "开启系统服务不会自动把屏幕能力交给模型。需从具体任务发起本次有限授权。", tag = "gui-no-run")
                else {
                    SettingsRow(title = "当前会话 ${session.identity.sessionId} / 任务 ${session.identity.runId}",
                        summary = "授权本身不落盘；UI 树/明确截图会作为本次工具结果提供给模型，并可能进入任务记录。页面失主、任务结束、服务断开即撤销授权。最多 128 节点、16 层、16 KiB 文本，密码节点文本隐藏。", tag = "gui-run")
                    SettingsRow(title = "允许本次 Agent 读取一次 UI 树（不含截图）", tag = "gui-grant-observe",
                        onClick = if (!frozen) ({ if (active()) notice = describe(session.approveReadsFromUser(GuiReadApproval(1, 0))) }) else null)
                    SettingsRow(title = "允许一次 UI 树与一次明确请求的截图", summary = "仅有此选择仍不会截图；工具必须单独请求。截图可能含敏感信息，将作为本次工具结果提供给模型并可能留在任务记录；不绕过系统安全窗口。",
                        tag = "gui-grant-screenshot", onClick = if (!frozen) ({ if (active()) notice = describe(session.approveReadsFromUser(GuiReadApproval(1, 1))) }) else null)
                    SettingsRow(title = "精确操作必须在外部目标上通过静态浮层确认",
                        summary = "本设置页不再读取预览后切页授权。父任务先保留准备资源，再打开服务托管的非抢焦浮层；选择真实目标、展示完整输入后才获取最终观察。启用服务不是写授权。",
                        tag = "gui-exact-boundary")
                    pendingAction?.let { proposal ->
                        SettingsRow(title = "为提议动作打开外部确认浮层",
                            summary = "${proposal.operation} · 完整输入=${proposal.text ?: "无"}。提议中的旧 observation/node 不会沿用，需在真实外部应用重新选择并确认。",
                            tag = "gui-confirm-action", onClick = if (!frozen) ({ if (active()) {
                                val result = session.prepareExactActionFromUser(proposal.operation, proposal.text,
                                    ownerCurrent = { currentOwner() }, onConfirmed = onExactConfirmed)
                                if (result is GuiResult.Value) {
                                    // Transfer preparation BEFORE leaving this page, not after final capture.
                                    page.transferred = true
                                    frozen = true
                                    notice = "浮层已打开；切到外部应用，在浮层内选择和确认。取消会撤权；父任务仍须保留资源直至 drain。"
                                } else notice = describe(result)
                            } }) else null)
                    }
                    SettingsRow(title = "冻结以上授权并交给本次任务", summary = "不会扩大到其他动作或新观察。动作需同一 observation_id 与原调度器精确 WRITE 授权；过期不自动重试。",
                        tag = "gui-freeze", onClick = if (!frozen) ({ if (active()) {
                            val writes = session.freezeAuthorizationFromUser()
                            frozen = true
                            page.transferred = try { onConfirmed(session, writes) } catch (_: Exception) { false }
                            if (!page.transferred) { session.close(); notice = "宿主未接收授权；已撤销，未执行。" }
                            else notice = "已交给本次任务；请在任务界面取消或结束后撤销。"
                        } }) else null)
                    SettingsRow(title = "撤销本次授权", tag = "gui-revoke", onClick = { if (active()) {
                        session.close(); frozen = true; notice = "已撤销本次授权；不会撤销系统服务开关。"
                    } })
                }
                notice?.let { Text(it) }
            }
        }
    }
}
private class GuiSettingsPage { var active = true; var transferred = false }
