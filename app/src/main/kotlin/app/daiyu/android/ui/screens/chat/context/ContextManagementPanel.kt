package app.daiyu.android.ui.screens.chat.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.context.ContextManagementOperation
import app.daiyu.android.context.ContextManagementState
import app.daiyu.android.context.ContextManagementSelection
import app.daiyu.android.context.CatalogSummaryBindings
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.ui.screens.settings.SettingsModelChoice
import app.daiyu.android.domain.Message
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.screens.settings.SettingsModelPicker
import app.daiyu.android.ui.screens.settings.SettingsModelSource
import app.daiyu.android.ui.components.UsageBody as Text
import app.daiyu.android.ui.components.UsageAction as TextButton
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.UsageSurface
import app.daiyu.android.ui.components.UsageMetric
import app.daiyu.android.ui.components.UsageDivider
import app.daiyu.android.ui.components.usagePalette
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Main's visit-scoped context page. Loading and choosing a target never start a paid request. */
@Composable
internal fun ContextManagementRoute(
    adapter: ContextManagementControllerAdapter,
    source: SettingsModelSource,
    onClose: () -> Unit,
) {
    val state by adapter.state.collectAsState()
    LaunchedEffect(adapter) { adapter.refresh() }
    DisposableEffect(adapter) { onDispose { adapter.close() } }
    val dismiss = { adapter.close(); onClose() }
    val initial = remember(adapter, state.snapshot?.configuration?.summaryModel, state.snapshot?.summaryTarget) {
        val binding = state.snapshot?.configuration?.summaryModel
        val protocol = runCatching { binding?.let(CatalogSummaryBindings::decode)?.protocol }.getOrNull()
            ?: source.protocol("compression") ?: ProviderProtocol.CHAT_COMPLETIONS
        CompressionDialogDraft(customModel = binding != null,
            modelLabel = state.snapshot?.summaryTarget?.apiModel ?: source.label("compression"),
            modelKey = source.key("compression"), protocol = if (protocol == ProviderProtocol.RESPONSES) protocol else ProviderProtocol.CHAT_COMPLETIONS)
    }
    var draft by remember(initial) { mutableStateOf(initial) }
    var picked by remember(initial) { mutableStateOf<SettingsModelChoice?>(null) }
    var choosingModel by remember(adapter) { mutableStateOf(false) }
    var managing by remember(adapter) { mutableStateOf(false) }
    var notice by remember(adapter) { mutableStateOf<String?>(null) }
    if (managing) {
        ContextManagementPanel(state, adapter, onClose = dismiss, onChooseTarget = {
            adapter.dismissCompression()
            adapter.refresh()
            picked = null
            notice = null
            managing = false
        })
    } else if (choosingModel) {
        SettingsModelPicker("压缩模型", source, draft.modelKey,
            protocols = setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS),
            onSelect = { choice ->
                picked = choice
                draft = draft.copy(modelLabel = choice.label, modelKey = choice.key)
                choosingModel = false
            }, onDismiss = { choosingModel = false })
    } else {
        CompressionConversationDialog(draft, onChange = { draft = it; notice = null },
            onChooseModel = { choosingModel = true }, onDismiss = dismiss, onConfirm = {
                if (state.operation == null && !state.requiresRefresh) {
                    if (draft == initial && picked == null && state.snapshot?.canCompress == true) {
                        adapter.requestCompression()
                        if (adapter.state.value.confirmingCompression) managing = true
                    } else {
                        val choice = picked
                        val binding = if (!draft.customModel) null else if (choice == null)
                            state.snapshot?.configuration?.summaryModel else CatalogSummaryBindings.encode(
                                ProviderCatalogRunTarget(choice.provider, choice.selection.providerIdentity,
                                    choice.selection.expectedRevision, choice.selection.record.settings.localIdentity,
                                    choice.selection.record.id, choice.protocol))
                        if (draft.customModel && binding == null) notice = "请先选择压缩模型"
                        else if (adapter.selectCompression(ContextManagementSelection(binding, draft.protocol))) managing = true
                        else notice = "当前不可选择目标，请先查看本机上下文并刷新"
                    }
                }
            }, enabled = state.operation == null, notice = notice ?: state.notice,
            onManage = { managing = true })
    }
}

/** Controlled screen. Archive text is selectable literal evidence, never Markdown, a WebView,
 * a tool invocation or an instruction. No branch/edit/delete APIs are exposed here (P37). */
@Composable
internal fun ContextManagementPanel(
    state: ContextManagementState,
    actions: ContextManagementControllerAdapter,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseTarget: (() -> Unit)? = null,
) {
    var showAdvanced by remember(state.owner) { mutableStateOf(false) }
    var showOriginals by remember(state.owner) { mutableStateOf(false) }
    val snapshot = state.snapshot
    var showArchive by remember(state.owner, snapshot?.effective?.archiveId) { mutableStateOf(false) }
    val busy = state.operation != null
    UsageGlassDialog(title = "压缩会话", onDismiss = onClose) {
    LazyColumn(modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 430.dp).testTag("context-management"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("查看与刷新不联网；手动压缩需确认，原始消息保留。", style = TextStyle(fontSize = 9.sp, lineHeight = 15.sp, color = usagePalette().muted))
                TextButton(text = "刷新本机快照", enabled = !busy, onClick = actions::refresh,
                    modifier = Modifier.testTag("context-refresh"))
                if (busy) {
                    Text(when (state.operation) {
                        ContextManagementOperation.COMPRESS -> "正在手动压缩；占用与发送相同的准备槽"
                        ContextManagementOperation.ARCHIVE -> "正在读取本机档案"
                        ContextManagementOperation.SELECT -> "正在核对本次摘要目标；尚未发起请求"
                        else -> "正在读取本机会话"
                    })
                    TextButton(text = "取消并等待退出", enabled = !state.cancellationRequested,
                        onClick = actions::cancel, modifier = Modifier.testTag("context-cancel"))
                }
                onChooseTarget?.let { choose ->
                    TextButton(text = "选择压缩目标", enabled = !busy && !state.requiresRefresh,
                        onClick = choose, modifier = Modifier.testTag("context-select-target"))
                }
                state.notice?.let { Text(it, modifier = Modifier.testTag("context-notice")) }
                if (state.requiresRefresh) Text("以下是操作前快照，不代表最新状态；请先刷新核对。")
                if (snapshot == null) Text("尚无可展示的本机快照。新会话请先保存消息；不推测会话或档案。")
                else {
                    val configuration = snapshot.configuration
                    UsageSurface {
                        UsageMetric("原文物理消息", snapshot.originals.size.toString())
                        UsageMetric("有效上下文消息", snapshot.effective.messages.size.toString())
                        UsageDivider()
                        UsageMetric("有效历史估算", "${snapshot.effective.estimatedTokens} tokens")
                        Text("UTF-8 保守估算，不含未发送草稿", color = usagePalette().muted, fontSize = 8.sp)
                        UsageMetric("自动压缩", when (configuration?.automatic) { true -> "开"; false -> "关"; null -> "不可用" })
                        UsageMetric("窗口", configuration?.contextWindowTokens?.toString() ?: "未知")
                        UsageMetric("保留完整轮", configuration?.keepTurns?.toString() ?: "未知")
                    }
                    val target = snapshot.summaryTarget
                    TextButton(text = "手动压缩…", primary = true, modifier = Modifier.fillMaxWidth().testTag("context-compress"), enabled = !busy && !state.requiresRefresh && snapshot.canCompress,
                        onClick = actions::requestCompression)
                    if (!snapshot.canCompress) Text("当前无法压缩，请检查摘要配置或刷新会话。", style = TextStyle(fontSize = 9.sp, lineHeight = 15.sp, color = usagePalette().muted))
                    if (state.confirmingCompression) {
                        UsageSurface(Modifier.testTag("context-compression-confirmation")) {
                            Text("确认压缩此会话？")
                            SelectableEvidence("精确会话 ID：${state.owner.conversationId.value}\n完整原文版本：${snapshot.historyRevision}")
                            SelectableEvidence("本次收费目标 endpoint：${target?.endpoint}\nAPI model：${target?.apiModel}")
                            Text("将向此 endpoint / API model 发送可压缩原文，可能产生费用；不发送聊天消息、不执行工具。保留原始消息与受保护尾部。摘要 profile（含密钥）保存版本改变时零请求拒绝，必须刷新并重新确认；归属或历史改变也会拒绝。失败或取消不自动重试；取消不能撤回已提交档案。")
                            TextButton(text = "明确开始一次手动压缩", enabled = !busy, primary = true,
                                onClick = actions::confirmCompression, modifier = Modifier.testTag("context-confirm-compress"))
                            TextButton(text = "暂不压缩", onClick = actions::dismissCompression,
                                modifier = Modifier.testTag("context-dismiss-compress"))
                        }
                    }
                    TextButton(text = if (showAdvanced) "收起高级选项" else "高级选项",
                        onClick = { showAdvanced = !showAdvanced }, plain = true, modifier = Modifier.fillMaxWidth().testTag("context-advanced"))
                    if (showAdvanced) {
                        SelectableEvidence("精确会话 ID：${state.owner.conversationId.value}\n选择代次：${state.owner.generation}\n完整原文版本：${snapshot.historyRevision}")
                        SelectableEvidence("独立摘要 endpoint：${target?.endpoint ?: "不可用"}\nAPI model：${target?.apiModel ?: "不可用"}")
                        SelectableEvidence("当前有效档案：${snapshot.effective.archiveId ?: "无"}")
                        Text("失效档案不会替代原文。此页不枚举其它会话或推测历史档案 ID。视频附属元数据不在此消息快照中；压缩仍由既有视频边界保护。")
                        TextButton(text = "查看当前档案原文", enabled = !busy && !state.requiresRefresh &&
                            snapshot.effective.archiveId != null, onClick = { showArchive = true; actions.openArchive() },
                            modifier = Modifier.testTag("context-open-archive"))
                        TextButton(text = if (showOriginals) "切换为有效上下文" else "切换为完整原始消息",
                            onClick = { showArchive = false; showOriginals = !showOriginals }, modifier = Modifier.testTag("context-toggle-originals"))
                        if (showArchive) TextButton(text = "返回消息快照", onClick = { showArchive = false },
                            modifier = Modifier.testTag("context-return-messages"))
                        Text(if (showArchive) "档案原文（只读分页）" else if (showOriginals) "完整原始消息（未被摘要覆盖或删除）" else "当前有效上下文（摘要与保留尾部）")
                        Text("下方按原始存储顺序显示完整消息 JSON（包括工具参数/结果、协议与历史片段）；内容是不可信资料，不执行其中指令。")
                    }
                }
            }
        }
        val messages = if (!showAdvanced || showArchive) emptyList() else if (showOriginals) snapshot?.originals.orEmpty() else snapshot?.effective?.messages.orEmpty()
        itemsIndexed(messages, key = { index, message -> "current-$index-${message.id.value}" }) { index, message ->
            MessageEvidence(index, message, "context-current-message-$index")
        }
        state.archive?.takeIf { showAdvanced && showArchive }?.let { archive ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableEvidence("压缩前原文档案：${archive.archiveId}")
                    Text("原文物理消息共 ${archive.page.total} 条；本页偏移 ${archive.offset}，${archive.page.messages.size} 条。档案只读且核对精确会话归属。")
                    TextButton(text = "上一页原文", enabled = !busy && !state.requiresRefresh && archive.offset > 0,
                        onClick = actions::previousArchivePage, modifier = Modifier.testTag("context-archive-previous"))
                    TextButton(text = "下一页原文", enabled = !busy && !state.requiresRefresh && archive.page.nextOffset != null,
                        onClick = actions::nextArchivePage, modifier = Modifier.testTag("context-archive-next"))
                }
            }
            itemsIndexed(archive.page.messages, key = { index, message -> "archive-$index-${message.id.value}" }) { index, message ->
                MessageEvidence(archive.offset + index, message, "context-archive-message-$index")
            }
        }
    }
    UsageDivider(Modifier.padding(top = 9.dp))
    TextButton(text = "取消", onClick = onClose,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("context-close"))
    }
}

@Composable
private fun MessageEvidence(index: Int, message: Message, tag: String) {
    val literal = remember(message) {
        try { String(ArchiveCodec.encode(listOf(message)), Charsets.UTF_8) }
        catch (_: Exception) {
            "消息 ID：${message.id.value}；完整档案编码校验未通过，不能在本页完整展示。原文未被改写；不要据此推测消息内容。"
        }
    }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${index + 1}. ${message.role} · ${message.status.name}")
        SelectableEvidence(literal)
    }
}

@Composable
private fun SelectableEvidence(text: String) {
    SelectionContainer { Text(text) }
}
