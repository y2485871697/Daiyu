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

/** Use the same supplement expansion and occurrence counts as the inert display mapper. */
private fun displayOwnerAnchors(window: app.daiyu.android.storage.MessageDisplayWindow): List<Pair<Int, TimelineAnchor>> {
    val rows = window.rows.map { it.second }
    val supplements = rows.flatMap { row -> row.segments.filter { it.role == Message.USER_ROLE }.map {
        Triple(row.conversationId, row.runId, it.messageId)
    } }.toSet()
    val counts = hashMapOf<Triple<String, String, String>, Int>()
    return buildList {
        rows.forEachIndexed { owner, row ->
            val ids = if (row.segments.isNotEmpty()) row.segments.map { it.messageId }
                else if (Triple(row.conversationId, row.runId, row.id) in supplements) emptyList()
                else listOf(row.id)
            ids.forEach { id ->
                val key = Triple(row.conversationId.value, row.runId.value, id.value)
                val segment = counts[key] ?: 0
                counts[key] = segment + 1
                add(owner to TimelineAnchor(key.first, key.second, key.third, segment))
            }
        }
    }
}

internal fun displayTimelineAnchors(window: app.daiyu.android.storage.MessageDisplayWindow): Set<String> =
    displayOwnerAnchors(window).mapTo(hashSetOf()) { it.second.listKey }

/** Derive display-only stops without hydrating provider history or acquiring action authority. */
internal fun withStoppedDisplayRows(window: app.daiyu.android.storage.MessageDisplayWindow,
    timeline: List<ChatTimelineEntry>): List<ChatTimelineEntry> {
    val rows = window.rows.map { it.second }
    val stopped = rows.indices.filterTo(hashSetOf()) {
        rows[it].role == Message.ASSISTANT_ROLE && rows[it].status == RunStatus.STOPPED
    }
    if (stopped.isEmpty()) return timeline.filterNot { it is ChatTimelineEntry.Stopped }
    val expanded = displayOwnerAnchors(window)
    val ownerByAnchor = expanded.associate { it.second.listKey to it.first }
    val lastByOwner = expanded.associate { it.first to it.second }
    val stops = stopped.mapNotNull { owner -> lastByOwner[owner]?.let { anchor ->
        ChatTimelineEntry.Stopped("stopped:${anchor.listKey}", anchor, rows[owner].createdAt ?: 0L)
    } }
    return timeline.filterNot { entry ->
        entry is ChatTimelineEntry.Stopped || entry is ChatTimelineEntry.Reconnect && entry.status in setOf("running", "stopped") &&
            ownerByAnchor[entry.anchor.listKey] in stopped
    } + stops
}
