package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import app.daiyu.android.domain.ReportedReasoning

/** UI ordering only; never delays a runner or replays a tool. */
@Stable
internal class ReasoningHandoff(initial: List<ReportedReasoning>) {
    private val completed = mutableStateMapOf<String, Boolean>().apply {
        initial.filter { it.activeSinceNanos == null }.forEach { put(it.itemId, true) }
    }
    fun ready(reported: ReportedReasoning): Boolean = completed[reported.itemId] == true
    fun ready(reported: List<ReportedReasoning>): Boolean = reported.all(::ready)
    fun complete(reported: ReportedReasoning) { if (!ready(reported)) completed[reported.itemId] = true }
}
