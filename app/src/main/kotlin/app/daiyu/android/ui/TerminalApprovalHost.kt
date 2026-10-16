package app.daiyu.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ControllerTerminalPreparation
import app.daiyu.android.TerminalDraftSnapshot
import app.daiyu.android.TerminalRunPreview
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.integration.tools.TerminalPtyLaunch
import app.daiyu.android.integration.tools.TerminalPtyStep
import app.daiyu.android.model.ProviderSnapshot
import org.json.JSONObject
import java.util.Base64

/** Pure UI leaf: no owner, permission request, persistence, worker, PTY, or auto-confirm on mount.
 * Parent supplies an observed state and retains preparation through cleanup/UNKNOWN after disposal.
 * Callbacks prepare/confirm ONLY; they must not send, execute a step, or clear the current draft.
 */
@Composable
internal fun TerminalApprovalHost(
    draft: TerminalDraftSnapshot,
    assistant: AssistantProfile,
    provider: ProviderSnapshot,
    preparation: ControllerTerminalPreparation?,
    state: ControllerTerminalPreparation.State?,
    onPrepare: () -> Boolean,
    onConfirm: (ControllerTerminalPreparation, TerminalRunPreview, TerminalDraftSnapshot) -> Boolean,
    onCancel: (ControllerTerminalPreparation) -> Unit,
    modifier: Modifier = Modifier,
    problem: String? = null,
) {
    var error by remember(preparation) { mutableStateOf<String?>(null) }
    val cancel by rememberUpdatedState(onCancel)
    val callbacks = remember { TerminalApprovalCallbackGate() }
    val callbackState = callbacks.update(preparation, draft, assistant, provider, state)
    DisposableEffect(callbacks) { onDispose { callbacks.close() } }
    val matches = preparation == null || (preparation.draft == draft &&
        preparation.assistant == assistant && preparation.provider == provider)
    DisposableEffect(preparation) { onDispose { preparation?.let { cancel(it) } } }
    LaunchedEffect(preparation, draft, assistant, provider) {
        if (!matches) preparation?.let { cancel(it) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("终端仅按本次完整计划审批；准备和确认均不启动进程、不发送聊天。")
        Button(enabled = preparation == null, modifier = Modifier.testTag("terminal-prepare"), onClick = {
            if (!callbacks.prepare(callbackState, onPrepare)) error = "终端计划不可用；未启动或自动重试。"
        }) { Text("准备终端审批（不启动）") }
        if (preparation == null) (problem ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (preparation != null && state != ControllerTerminalPreparation.State.CONFIRMED &&
        state != ControllerTerminalPreparation.State.TRANSFERRED) {
        val sections = remember(preparation) { terminalApprovalSections(preparation) }
        val canConfirm = matches && state == ControllerTerminalPreparation.State.PREVIEW &&
            preparation.state == ControllerTerminalPreparation.State.PREVIEW
        AlertDialog(
            onDismissRequest = { cancel(preparation) },
            title = { Text("终端审批：${state?.name ?: "等待状态"}（不启动）") },
            text = {
                Column {
                    Text("以下是完整审批内容。长内容按连续编号分页，无省略。Base64 为原始字节，不是 shell 文本预览。")
                    LazyColumn(Modifier.heightIn(max = 520.dp).testTag("terminal-approval-pages"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sections, key = { it.id }) { section ->
                            SelectionContainer { Text(section.text, Modifier.testTag(section.id)) }
                        }
                        item {
                            Text("仅授权列出的有限步骤与读页。输入交付和命令完成不等同；UNKNOWN 不重发。" +
                                "取消不撤销已发生的副作用；父 owner 必须保留资源直到实际清理完成。")
                        }
                        if (!matches) item {
                            Text("草稿／助手／供应商已变更，原预览失效。", color = MaterialTheme.colorScheme.error)
                        }
                        (problem ?: error)?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error) } }
                    }
                }
            },
            confirmButton = {
                Button(enabled = canConfirm, modifier = Modifier.testTag("terminal-confirm"), onClick = {
                    if (!callbacks.confirm(callbackState, onConfirm)) error = "预览失效或已经处理；未启动或重复授权。"
                }) { Text("仅确认以上精确计划（不启动）") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.testTag("terminal-cancel"), onClick = { cancel(preparation) }) {
                    Text("取消／停止准备")
                }
            },
        )
    }
}

internal data class TerminalApprovalSection(val id: String, val text: String)

/** Complete, reversible presentation. No take(), ellipsis, redacted launch values, or digest-only approval.
 * Slices bound Compose paragraph size, not approval size. Joining slices recovers every character.
 * Provider credentials are intentionally NOT launch parameters and are never displayed.
 */
internal fun terminalApprovalSections(preparation: ControllerTerminalPreparation): List<TerminalApprovalSection> = buildList {
    fun addText(id: String, text: String) {
        var start = 0
        var page = 0
        while (start < text.length) {
            var end = minOf(start + 4096, text.length)
            // Do not turn one displayed Unicode scalar into two replacement glyphs at a page edge.
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            add(TerminalApprovalSection("$id-${page++}", text.substring(start, end)))
            start = end
        }
    }
    fun quote(value: String): String = JSONObject.quote(value)
    val preview = preparation.preview
    val plan = preview.plan
    addText("terminal-identity", "conversation=${preview.identity.conversationId.value}\n" +
        "session=${preview.identity.sessionId}\nrun=${preview.identity.runId.value}\n" +
        "assistantMessage=${preview.identity.assistantMessageId.value}\nassistant=${preview.identity.assistantId}\n" +
        "environment=${plan.environmentId}\ngeneration=${plan.generation}\nscope=${plan.sessionScope}\n" +
        "journal=${quote(plan.journalDirectory.path)}\nrows=${plan.rows}\ncolumns=${plan.columns}\n" +
        "editorGeneration=${preparation.draft.owner.editorGeneration}\ndraftRevision=${preparation.draft.owner.draftRevision}")
    addText("terminal-draft", "完整草稿（JSON 字符串）：\n${quote(preparation.draft.text)}")
    addText("terminal-profile", "冻结助手：${quote(preparation.assistant.name)}\n" +
        "prompt=${quote(preparation.assistant.prompt)}\nskills=${preparation.assistant.enabledSkillIds.map(::quote)}")
    addText("terminal-provider", "provider=${preparation.provider.protocol}\nendpoint=${quote(preparation.provider.endpoint)}\n" +
        "model=${quote(preparation.provider.model)}\nresponsesPath=${quote(preparation.provider.responsesPath)}")
    val argv: List<String>
    val environment: Map<String, String>
    when (val launch = plan.launch) {
        is TerminalPtyLaunch.Android -> {
            addText("terminal-launch", "launch=android app UID\ncwd=${quote(launch.cwd.path)}")
            argv = launch.argv
            environment = launch.environment
        }
        is TerminalPtyLaunch.Linux -> {
            addText("terminal-launch", "launch=linux（已安装可信选择；不下载／不安装）\n" +
                "environment=${launch.expected.environmentId}\ngeneration=${launch.expected.generation}\n" +
                "host cwd=${quote(launch.hostWorkingDirectory.path)}\ntemp=${quote(launch.tempDirectory.path)}\n" +
                "guest cwd 及所有 bind 参数包含在以下完整 argv 中；guest uid=0 不是 Android root。")
            argv = launch.argv
            environment = launch.environment
        }
    }
    addText("terminal-argv-count", "argv count=${argv.size}；每个参数独立显示为 JSON 字符串")
    argv.forEachIndexed { index, argument -> addText("terminal-argv-$index", "argv[$index]=${quote(argument)}") }
    addText("terminal-env-count", "environment count=${environment.size}；完整环境（非追加到隐式环境）")
    environment.toSortedMap().entries.forEachIndexed { index, entry ->
        addText("terminal-env-$index", "${quote(entry.key)}=${quote(entry.value)}")
    }
    plan.steps.forEachIndexed { index, step ->
        val description = when (step) {
            is TerminalPtyStep.Open -> "open persistent PTY rows=${plan.rows} columns=${plan.columns}"
            is TerminalPtyStep.Input -> "input requestId=${step.requestId} inputSequence=${step.sequence} bytes=${step.size}"
            is TerminalPtyStep.Resize -> "resize requestId=${step.requestId} controlSequence=${step.sequence} rows=${step.rows} columns=${step.columns}"
            is TerminalPtyStep.Interrupt -> "interrupt requestId=${step.requestId} controlSequence=${step.sequence}"
        }
        addText("terminal-step-$index", "step ${index + 1}/${plan.steps.size} id=${step.id}\n$description")
        if (step is TerminalPtyStep.Input) {
            val bytes = step.bytes()
            bytes.asList().chunked(1024).forEachIndexed { page, chunk ->
                val offset = page * 1024
                addText("terminal-input-$index-$page", "input bytes [$offset,${offset + chunk.size}) Base64（每页独立解码）：\n" +
                    Base64.getEncoder().encodeToString(chunk.toByteArray()))
            }
        }
        val effect = plan.effects.getValue(step.id)
        addText("terminal-effect-$index", "exact effect\nnamespace=${effect.namespace}\noperation=${effect.operation}\n" +
            "target=${effect.target}\nchange=${effect.change}")
    }
    addText("terminal-read-count", "read pages=${plan.reads.size}（仅以下 offset/limit；不授权无限读取）")
    plan.reads.forEachIndexed { index, read ->
        addText("terminal-read-$index", "read ${index + 1}/${plan.reads.size} id=${read.id} offset=${read.offset} limit=${read.limit}")
    }
}

/** A synchronous fence for callbacks retained by a disposed or replaced composition. */
internal class TerminalApprovalCallbackGate {
    internal class Snapshot(val preparation: ControllerTerminalPreparation?, val draft: TerminalDraftSnapshot,
        val assistant: AssistantProfile, val provider: ProviderSnapshot,
        val state: ControllerTerminalPreparation.State?)
    private var current: Snapshot? = null
    private var closed = false
    fun update(preparation: ControllerTerminalPreparation?, draft: TerminalDraftSnapshot,
        assistant: AssistantProfile, provider: ProviderSnapshot,
        state: ControllerTerminalPreparation.State?): Snapshot =
        Snapshot(preparation, draft, assistant, provider, state).also { current = it }
    fun prepare(expected: Snapshot, action: () -> Boolean): Boolean =
        !closed && current === expected && expected.preparation == null && action()
    fun confirm(expected: Snapshot,
        action: (ControllerTerminalPreparation, TerminalRunPreview, TerminalDraftSnapshot) -> Boolean): Boolean {
        val p = expected.preparation ?: return false
        if (closed || current !== expected || expected.state != ControllerTerminalPreparation.State.PREVIEW ||
            p.state != ControllerTerminalPreparation.State.PREVIEW || p.isCancelled ||
            p.draft != expected.draft || p.assistant != expected.assistant || p.provider != expected.provider) return false
        return action(p, p.preview, expected.draft)
    }
    fun close() { closed = true; current = null }
}
