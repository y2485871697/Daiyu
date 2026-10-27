package app.daiyu.android.ui.components

import app.daiyu.android.ui.model.ConversationModeUi
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationSummaryUi
import app.daiyu.android.ui.model.filterForFolder
import app.daiyu.android.ui.model.searchResults
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationDrawerGroupsTest {
    private val utc = ZoneId.of("UTC")

    private fun row(id: String, instant: String, label: String = instant) = ConversationSummaryUi(
        id, id, "", label, updatedAtMillis = Instant.parse(instant).toEpochMilli(), mode = ConversationModeUi.Chat,
    )

    private fun dated(date: String) = ConversationDrawerSection.Dated(LocalDate.parse(date))

    @Test fun sameDayUsesOneHeadingDespiteDifferentExactTimeLabels() {
        val rows = listOf(row("new", "2026-10-26T20:34:00Z", "2026/10/26 20:34"),
            row("old", "2026-10-26T08:14:00Z", "2026/10/26 08:14"))
        val groups = rows.groupForDrawer(utc)
        assertEquals(listOf(dated("2026-10-26")), groups.map { it.section })
        assertEquals(rows, groups.single().items)
        assertFalse((groups.single().section as ConversationDrawerSection.Dated).label.contains(":"))
        assertEquals("2026/10/26 20:34", rows.first().timeLabel)
    }

    @Test fun midnightSeparatesDaysWhileKeepingNewestFirstAndStableTies() {
        val rows = listOf(row("new", "2026-10-27T00:00:00Z"),
            row("tie-a", "2026-10-26T23:59:59Z"), row("tie-b", "2026-10-26T23:59:59Z"))
        val groups = rows.groupForDrawer(utc)
        assertEquals(listOf(dated("2026-10-27"), dated("2026-10-26")), groups.map { it.section })
        assertEquals(listOf("tie-a", "tie-b"), groups.last().items.map { it.id })
    }

    @Test fun localDateRatherThanUtcDateControlsTheBucket() {
        val rows = listOf(row("new", "2026-10-26T16:00:00Z"), row("old", "2026-10-26T15:59:59Z"))
        assertEquals(1, rows.groupForDrawer(utc).size)
        assertEquals(listOf(dated("2026-10-27"), dated("2026-10-26")),
            rows.groupForDrawer(ZoneId.of("Asia/Shanghai")).map { it.section })
    }

    @Test fun utcMidnightDoesNotSplitOneLocalDay() {
        val rows = listOf(row("new", "2026-10-27T01:00:00Z"), row("old", "2026-10-26T23:00:00Z"))
        assertEquals(listOf(dated("2026-10-26")),
            rows.groupForDrawer(ZoneId.of("America/Los_Angeles")).map { it.section })
    }

    @Test fun daylightSavingRepeatedHourAndLongDayStayInOneBucket() {
        val rows = listOf(row("late", "2026-11-02T04:59:00Z"), row("second-hour", "2026-11-01T06:30:00Z"),
            row("first-hour", "2026-11-01T05:30:00Z"), row("early", "2026-11-01T04:00:00Z"))
        val groups = rows.groupForDrawer(ZoneId.of("America/New_York"))
        assertEquals(listOf(dated("2026-11-01")), groups.map { it.section })
        assertEquals(rows, groups.single().items)
    }

    @Test fun identicalDisplayLabelsCannotMergeDifferentMonthsOrYears() {
        val rows = listOf(row("new-year", "2027-01-01T12:00:00Z", "same label"),
            row("previous-year", "2026-12-31T12:00:00Z", "same label"),
            row("previous-month", "2026-11-30T12:00:00Z", "same label"))
        assertEquals(listOf(dated("2027-01-01"), dated("2026-12-31"), dated("2026-11-30")),
            rows.groupForDrawer(utc).map { it.section })
    }

    @Test fun pinnedAndCapturedTodayRemainSeparateWithoutDuplicatingRows() {
        val rows = listOf(row("pin", "2026-10-26T12:00:00Z").copy(isPinned = true, isToday = true),
            row("today", "2026-10-26T11:00:00Z").copy(isToday = true), row("dated", "2026-10-25T12:00:00Z"))
        val groups = rows.groupForDrawer(utc)
        assertEquals(listOf(ConversationDrawerSection.Pinned, ConversationDrawerSection.Today, dated("2026-10-25")),
            groups.map { it.section })
        assertEquals(rows, groups.flatMap { it.items })
    }

    @Test fun disjointRunsMergeWithoutReorderingTheirRows() {
        val rows = listOf(row("first", "2026-10-26T12:00:00Z"), row("middle", "2026-10-25T12:00:00Z"),
            row("last", "2026-10-26T11:00:00Z"))
        val groups = rows.groupForDrawer(utc)
        assertEquals(listOf(dated("2026-10-26"), dated("2026-10-25")), groups.map { it.section })
        assertEquals(listOf("first", "last"), groups.first().items.map { it.id })
    }

    @Test fun folderAndSearchFilteringDoNotLeaveEmptyDateHeadings() {
        val rows = listOf(row("match-new", "2026-10-26T12:00:00Z").copy(folderId = "folder"),
            row("match-old", "2026-10-26T08:00:00Z").copy(folderId = "folder"),
            row("other", "2026-10-25T12:00:00Z").copy(folderId = "folder"),
            row("match-unfiled", "2026-10-24T12:00:00Z"))
        val state = ConversationPaneUiState(rows.filterForFolder("folder"), null, "match")
        val groups = state.searchResults().groupForDrawer(utc)
        assertEquals(listOf(dated("2026-10-26")), groups.map { it.section })
        assertEquals(listOf("match-new", "match-old"), groups.single().items.map { it.id })
        assertEquals(2, state.copy(searchQuery = "").searchResults().groupForDrawer(utc).size)
        assertEquals(0, state.copy(searchQuery = "missing").searchResults().groupForDrawer(utc).size)
    }

    @Test fun emptyInputHasNoGroups() {
        assertEquals(emptyList<ConversationDrawerGroup>(), emptyList<ConversationSummaryUi>().groupForDrawer(utc))
    }
}
