package app.daiyu.android.ui.screens.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.domain.delegation.*
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

// Expose the exact callback for retained-callback tests, not a rebindable ClickableNode wrapper.
internal val ChildTaskCommandDialogAction = SemanticsPropertyKey<() -> Unit>("ChildTaskCommandDialogAction")

internal fun childCommandNeedsAcknowledgement(record: ChildTaskRecord, kind: ChildTaskCommandKind): Boolean =
    record.status == ChildTaskStatus.UNKNOWN || (kind == ChildTaskCommandKind.REPLACE &&
        record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING))

@Composable
internal fun ChildTaskCommandDialog(source: ChildTaskRecord, kind: ChildTaskCommandKind, busy: Boolean,
    dialogToken: Any, onDismiss: () -> Unit, onConfirm: (ChildTaskCommand) -> Unit) {
    val key = source.owner to kind
    val callId = remember(dialogToken, key, source.revision) { "records:${UUID.randomUUID()}" }
    var instruction by remember(dialogToken, key, source.revision) { mutableStateOf(if (kind == ChildTaskCommandKind.APPEND) "" else source.prompt) }
    var profile by remember(dialogToken, key, source.revision) { mutableStateOf(source.profileId) }
    var consumed by remember(dialogToken, key, source.revision) { mutableStateOf(false) }
    val dismiss = {
        if (!busy) {
            // A retained click must lose consent synchronously, not only after disposal.
            consumed = true
            onDismiss()
        }
    }
    val risk = childCommandNeedsAcknowledgement(source, kind)
    val valid = instruction.isNotBlank() && instruction.length <= 4096 && instruction.toByteArray().size <= 16384 &&
        profile.isNotBlank() && profile.length <= 200
    val confirm = {
        if (!busy && !consumed && valid) {
            consumed = true
            onConfirm(ChildTaskCommand(callId, source, kind, profile, instruction, acknowledgePossibleExecution = risk))
        }
    }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(when (kind) {
            ChildTaskCommandKind.APPEND -> "定向追加指令"
            ChildTaskCommandKind.REPLACE -> "替换为新任务"
            ChildTaskCommandKind.REDISPATCH -> "失败任务明确新派"
        }) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("目标 ${source.owner.childRunId} · 修订 ${source.revision}。只接受完整身份与此修订，不改变原输入或结果。新请求可能产生模型费用。")
                if (kind == ChildTaskCommandKind.APPEND)
                    Text("等待原任务成功并且旧传输真正排空后，以原任务输入和结果作为上下文发送追加指令。这是关联的后续请求，不会修改旧请求。原结果不明确时不会自动新派。")
                else Text("旧任务保留，新的请求保留来源关联；替换会请求停止旧任务，但必须等其真正排空后才能发送。")
                if (risk) Text("重要：旧请求可能已经执行或已计费，即使没有结果或请求停止也不能撤销。新派可能重复执行及产生重复费用；仅本次确认允许新请求。",
                    modifier = Modifier.testTag("child-command-execution-warning"))
                if (source.status == ChildTaskStatus.NOT_EXECUTED) Text("旧任务明确未执行；本次操作明确派发一个新请求。")
                OutlinedTextField(profile, { if (!busy && !consumed) profile = it }, label = { Text("已绑定的子模型配置 ID") }, enabled = !busy && !consumed)
                OutlinedTextField(instruction, { if (!busy && !consumed) instruction = it }, label = { Text("本次指令") }, enabled = !busy && !consumed,
                    modifier = Modifier.testTag("child-command-instruction"))
            }
        }, confirmButton = {
            TextButton(if (risk) "确认可能已执行，仍新派一次" else "确认派发新请求", enabled = valid && !busy && !consumed,
                modifier = Modifier.testTag("child-command-confirm").semantics {
                    this[ChildTaskCommandDialogAction] = confirm
                }, onClick = confirm)
        }, dismissButton = {
            TextButton("取消", onClick = dismiss, enabled = !busy,
                modifier = Modifier.testTag("child-command-cancel").semantics {
                    this[ChildTaskCommandDialogAction] = dismiss
                })
        })
}
