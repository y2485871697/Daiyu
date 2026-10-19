package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.domain.delegation.ChildTaskPauseState
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.domain.delegation.RetainedChildTask

internal data class SubAgentStatusRow(val owner: ChildRunOwner, val agent: String, val status: String,
    val completed: Boolean = false, val model: String = "模型未记录")

internal fun subAgentStatusRows(records: List<ChildTaskRecord>, live: List<RetainedChildTask>): List<SubAgentStatusRow> {
    val current = live.associateBy { it.record.owner }
    val merged = records.associateByTo(linkedMapOf()) { it.owner }
    live.forEach { merged[it.record.owner] = it.record }
    return merged.values.map { record ->
        val task = current[record.owner]
        val status = when {
            task?.journalUnconfirmed == true -> "状态未确认"
            record.status == ChildTaskStatus.COMPLETED -> "已完成"
            record.status == ChildTaskStatus.NOT_EXECUTED -> "未执行"
            record.status == ChildTaskStatus.UNKNOWN -> "结果未知"
            task != null && !task.settled && task.stopRequested -> "停止中"
            task != null && !task.settled && task.pauseState == ChildTaskPauseState.PAUSED -> "已暂停"
            task != null && !task.settled && task.pauseState == ChildTaskPauseState.PENDING -> "暂停中"
            record.status == ChildTaskStatus.QUEUED -> "排队中"
            else -> "执行中"
        }
        SubAgentStatusRow(record.owner, record.display?.agentName ?: record.profileId, status,
            record.status == ChildTaskStatus.COMPLETED && task?.journalUnconfirmed != true,
            record.display?.modelName ?: "模型未记录")
    }
}
