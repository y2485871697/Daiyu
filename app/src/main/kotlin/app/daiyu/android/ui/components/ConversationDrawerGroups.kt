package app.daiyu.android.ui.components

import app.daiyu.android.ui.model.ConversationSummaryUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
) {
    val key: String get() = "section-$section"
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data object UnknownOutput : ConversationDrawerSection
    data class Dated(val date: LocalDate) : ConversationDrawerSection {
        val label: String get() = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    }
}

internal fun List<ConversationSummaryUi>.groupForDrawer(
    zoneId: ZoneId = ZoneId.systemDefault(),
    sortByLastOutput: Boolean = false,
    reverse: Boolean = false,
): List<ConversationDrawerGroup> {
    // Date mode keeps the existing update order within each frozen day.
    val ordered = if (sortByLastOutput) sortedByDescending {
        it.lastMessageAtMillis ?: Long.MIN_VALUE
    } else this
    val groups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in ordered) {
        val outputDate = sortByLastOutput && conversation.lastMessageAtMillis != null
        val section = when {
            conversation.isPinned -> ConversationDrawerSection.Pinned
            sortByLastOutput && !outputDate -> ConversationDrawerSection.UnknownOutput
            (if (outputDate) conversation.lastMessageIsToday else conversation.isToday) -> ConversationDrawerSection.Today
            else -> ConversationDrawerSection.Dated(
                Instant.ofEpochMilli(if (outputDate) checkNotNull(conversation.lastMessageAtMillis)
                    else conversation.dateGroupMillis).atZone(zoneId).toLocalDate(),
            )
        }
        groups.getOrPut(section) { mutableListOf() } += conversation
    }
    val grouped = groups.entries.sortedWith(compareBy<Map.Entry<ConversationDrawerSection, MutableList<ConversationSummaryUi>>> {
        when (it.key) {
            ConversationDrawerSection.Pinned -> 0
            ConversationDrawerSection.Today -> 1
            is ConversationDrawerSection.Dated -> if (sortByLastOutput) 1 else 2
            ConversationDrawerSection.UnknownOutput -> 3
        }
    }.thenByDescending {
        if (sortByLastOutput) it.value.first().lastMessageAtMillis ?: Long.MIN_VALUE
        else (it.key as? ConversationDrawerSection.Dated)?.date?.toEpochDay() ?: Long.MIN_VALUE
    })
        .map { (section, items) -> ConversationDrawerGroup(section, items.toList()) }
    if (!reverse) return grouped
    return (grouped.filter { it.section == ConversationDrawerSection.Pinned } +
        grouped.filter { it.section != ConversationDrawerSection.Pinned && it.section != ConversationDrawerSection.UnknownOutput }.asReversed() +
        grouped.filter { it.section == ConversationDrawerSection.UnknownOutput })
        .map {
            it.copy(items = if (sortByLastOutput) it.items.filter { row -> row.lastMessageAtMillis != null }.asReversed() +
                it.items.filter { row -> row.lastMessageAtMillis == null } else it.items.asReversed())
        }
}

internal fun List<ConversationSummaryUi>.filterDrawerStatus(
    running: Boolean,
    completed: Boolean,
): List<ConversationSummaryUi> = if (!running && !completed) this else filter {
    if (it.isActiveRun) running else completed && it.hasCompletionMarker
}
