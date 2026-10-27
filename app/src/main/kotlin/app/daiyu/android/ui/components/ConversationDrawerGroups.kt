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
    data class Dated(val date: LocalDate) : ConversationDrawerSection {
        val label: String get() = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    }
}

internal fun List<ConversationSummaryUi>.groupForDrawer(
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ConversationDrawerGroup> {
    // Preserve the database's newest-first order within and between calendar days.
    val groups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in this) {
        val section = when {
            conversation.isPinned -> ConversationDrawerSection.Pinned
            conversation.isToday -> ConversationDrawerSection.Today
            else -> ConversationDrawerSection.Dated(
                Instant.ofEpochMilli(conversation.updatedAtMillis).atZone(zoneId).toLocalDate(),
            )
        }
        groups.getOrPut(section) { mutableListOf() } += conversation
    }
    return groups.map { (section, items) -> ConversationDrawerGroup(section, items.toList()) }
}
