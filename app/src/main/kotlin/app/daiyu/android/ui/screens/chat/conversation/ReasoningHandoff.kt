package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import app.daiyu.android.domain.ReportedReasoning

/** 记录已完成展示的思考项，供后续内容按顺序入场。 */
@Stable
internal class ReasoningHandoff(initial: List<ReportedReasoning>) {
    private val completed = mutableStateMapOf<String, Boolean>().apply {
        initial.filter { it.activeSinceNanos == null }.forEach { put(it.itemId, true) }
    }
    fun ready(reported: ReportedReasoning): Boolean = completed[reported.itemId] == true
    fun ready(reported: List<ReportedReasoning>): Boolean = reported.all(::ready)
    fun complete(reported: ReportedReasoning) { if (!ready(reported)) completed[reported.itemId] = true }
}
