package app.daiyu.android.timeline

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.RunStatus

/** Presentation only: keep the persisted reconnect evidence and transport state untouched. */
internal fun withStoppedRuns(messages: List<Message>, timeline: List<ChatTimelineEntry>): List<ChatTimelineEntry> {
    val stoppedOwners = messages.indices.filterTo(hashSetOf()) {
        messages[it].role == Message.ASSISTANT_ROLE && messages[it].status == RunStatus.STOPPED
    }
    if (stoppedOwners.isEmpty()) return timeline.filterNot { it is ChatTimelineEntry.Stopped }
    val expanded = MessageHistory.expandWithSources(messages)
    val logical = expanded.map { it.message }
    val counts = hashMapOf<Triple<String, String, String>, Int>()
    val anchors = logical.map { message ->
        val owner = Triple(message.conversationId.value, message.runId.value, message.id.value)
        val segment = counts[owner] ?: 0
        counts[owner] = segment + 1
        TimelineAnchor(owner.first, owner.second, owner.third, segment)
    }
    val ownerByAnchor = anchors.indices.associate { anchors[it].listKey to expanded[it].sourcePhysicalIndex }
    val lastByOwner = expanded.indices.associateBy { expanded[it].sourcePhysicalIndex }
    val stops = stoppedOwners.mapNotNull { owner ->
        lastByOwner[owner]?.let { index ->
            val anchor = anchors[index]
            ChatTimelineEntry.Stopped("stopped:${anchor.listKey}", anchor, messages[owner].createdAt ?: 0L)
        }
    }
    return timeline.filterNot { entry ->
        entry is ChatTimelineEntry.Stopped || entry is ChatTimelineEntry.Reconnect && entry.status in setOf("running", "stopped") &&
            ownerByAnchor[entry.anchor.listKey] in stoppedOwners
    } + stops
}
