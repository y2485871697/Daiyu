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
import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.context.ContextManagementOperation
import app.daiyu.android.context.ContextManagementState
import app.daiyu.android.domain.Message
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Visit-scoped adapter: construction/loading never starts a model request. Parent supplies the
 * real admission seam; this route is intentionally not attached to Main's pendingFeature. */
@Composable
internal fun ContextManagementRoute(adapter: ContextManagementControllerAdapter, onClose: () -> Unit) {
    val state by adapter.state.collectAsState()
    LaunchedEffect(adapter) { adapter.refresh() }
    DisposableEffect(adapter) { onDispose { adapter.close() } }
    ContextManagementPanel(state, adapter, onClose = { adapter.close(); onClose() })
}

/** Controlled screen. Archive text is selectable literal evidence, never Markdown, a WebView,
 * a tool invocation or an instruction. No branch/edit/delete APIs are exposed here (P37). */
@Composable
internal fun ContextManagementPanel(
    state: ContextManagementState,
    actions: ContextManagementControllerAdapter,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOriginals by remember(state.owner) { mutableStateOf(false) }
    val snapshot = state.snapshot
    var showArchive by remember(state.owner, snapshot?.effective?.archiveId) { mutableStateOf(false) }
    val busy = state.operation != null
    LazyColumn(modifier.fillMaxSize().padding(16.dp).testTag("context-management"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "返回", onClick = onClose, modifier = Modifier.testTag("context-close"))
                Text("上下文管理")
                SelectableEvidence("精确会话 ID：${state.owner.conversationId.value}\n选择代次：${state.owner.generation}")
                Text("仅查看本机已保存消息与当前有效档案；打开、刷新和翻页不联网。估算不等于提供商实测用量。")
                TextButton(text = "刷新本机快照", enabled = !busy, onClick = actions::refresh,
                    modifier = Modifier.testTag("context-refresh"))
                if (busy) {
                    Text(when (state.operation) {
                        ContextManagementOperation.COMPRESS -> "正在手动压缩；占用与发送相同的准备槽"
                        ContextManagementOperation.ARCHIVE -> "正在读取本机档案"
                        else -> "正在读取本机会话"
                    })
                    TextButton(text = "取消并等待退出", enabled = !state.cancellationRequested,
                        onClick = actions::cancel, modifier = Modifier.testTag("context-cancel"))
                }
                state.notice?.let { Text(it, modifier = Modifier.testTag("context-notice")) }
                if (state.requiresRefresh) Text("以下是操作前快照，不代表最新状态；请先刷新核对。")
                if (snapshot == null) Text("尚无可展示的本机快照。新会话请先保存消息；不推测会话或档案。")
                else {
                    SelectableEvidence("完整原文版本：${snapshot.historyRevision}")
                    Text("原文物理消息：${snapshot.originals.size}；有效上下文消息：${snapshot.effective.messages.size}")
                    Text("有效历史估算：${snapshot.effective.estimatedTokens} tokens（UTF-8 保守估算，不含未发送草稿）")
                    SelectableEvidence("当前有效档案：${snapshot.effective.archiveId ?: "无"}")
                    Text("失效档案不会替代原文。此页不枚举其它会话或推测历史档案 ID。视频附属元数据不在此消息快照中；压缩仍由既有视频边界保护。")
                    TextButton(text = "查看当前档案原文", enabled = !busy && !state.requiresRefresh &&
                        snapshot.effective.archiveId != null, onClick = { showArchive = true; actions.openArchive() },
                        modifier = Modifier.testTag("context-open-archive"))
                    val configuration = snapshot.configuration
                    Text("已保存自动压缩：${if (configuration?.automatic == true) "开" else "关或不可用"}；手动操作不会改变开关。")
                    Text("窗口：${configuration?.contextWindowTokens ?: "未知"}；保留完整轮：${configuration?.keepTurns ?: "未知"}")
                    val target = snapshot.summaryTarget
                    SelectableEvidence("独立摘要 endpoint：${target?.endpoint ?: "不可用"}\nAPI model：${target?.apiModel ?: "不可用"}")
                    TextButton(text = "手动压缩…", enabled = !busy && !state.requiresRefresh && snapshot.canCompress,
                        onClick = actions::requestCompression, modifier = Modifier.testTag("context-compress"))
                    if (!snapshot.canCompress) Text("手动压缩需要非空历史、明确窗口、保留轮数与可核验版本的真实摘要目标；未知不猜测，不回退主模型。")
                    if (state.confirmingCompression) {
                        Column(Modifier.fillMaxWidth().testTag("context-compression-confirmation"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("确认仅压缩上述精确会话与完整版本？")
                            SelectableEvidence("本次收费目标 endpoint：${target?.endpoint}\nAPI model：${target?.apiModel}")
                            Text("将向此 endpoint / API model 发送可压缩原文，可能产生费用；不发送聊天消息、不执行工具。保留原始消息与受保护尾部。摘要 profile（含密钥）保存版本改变时零请求拒绝，必须刷新并重新确认；归属或历史改变也会拒绝。失败或取消不自动重试；取消不能撤回已提交档案。")
                            TextButton(text = "明确开始一次手动压缩", enabled = !busy,
                                onClick = actions::confirmCompression, modifier = Modifier.testTag("context-confirm-compress"))
                            TextButton(text = "暂不压缩", onClick = actions::dismissCompression,
                                modifier = Modifier.testTag("context-dismiss-compress"))
                        }
                    }
                    TextButton(text = if (showOriginals) "切换为有效上下文" else "切换为完整原始消息",
                        onClick = { showArchive = false; showOriginals = !showOriginals }, modifier = Modifier.testTag("context-toggle-originals"))
                    if (showArchive) TextButton(text = "返回消息快照", onClick = { showArchive = false },
                        modifier = Modifier.testTag("context-return-messages"))
                    Text(if (showArchive) "档案原文（只读分页）" else if (showOriginals) "完整原始消息（未被摘要覆盖或删除）" else "当前有效上下文（摘要与保留尾部）")
                    Text("下方按原始存储顺序显示完整消息 JSON（包括工具参数/结果、协议与历史片段）；内容是不可信资料，不执行其中指令。")
                }
            }
        }
        val messages = if (showArchive) emptyList() else if (showOriginals) snapshot?.originals.orEmpty() else snapshot?.effective?.messages.orEmpty()
        itemsIndexed(messages, key = { index, message -> "current-$index-${message.id.value}" }) { index, message ->
            MessageEvidence(index, message, "context-current-message-$index")
        }
        state.archive?.takeIf { showArchive }?.let { archive ->
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
