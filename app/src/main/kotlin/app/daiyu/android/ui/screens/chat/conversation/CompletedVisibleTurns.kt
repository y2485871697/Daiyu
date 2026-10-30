package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus

/** Scroll samples inspect only measured visible keys, not the whole loaded history. */
internal fun completedVisibleTurns(
    visibleKeys: Set<Any>,
    turns: Map<String, ChatTurnUi.Assistant>,
    ready: Map<String, ChatTurnUi.Assistant>,
    revealed: Map<String, ChatTurnUi.Assistant>,
): List<ChatTurnUi.Assistant> = visibleKeys.mapNotNull { key ->
    val turn = turns[key] ?: return@mapNotNull null
    turn.takeIf { it.status == RunStatus.COMPLETED && ready[it.listKey] == it && revealed[it.listKey] == it }
}
