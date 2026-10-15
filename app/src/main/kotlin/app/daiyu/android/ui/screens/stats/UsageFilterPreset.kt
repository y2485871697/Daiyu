package app.daiyu.android.ui.screens.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class UsageTimeBound(
    val date: LocalDate? = null,
) {
    val isSet: Boolean get() = date != null

    fun toMillis(endOfBound: Boolean): Long? {
        val selectedDate = date ?: return null
        val time = if (endOfBound) {
            LocalTime.of(23, 59, 59, 999_000_000)
        } else {
            LocalTime.MIN
        }
        return LocalDateTime.of(selectedDate, time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        fun from(date: LocalDate): UsageTimeBound = UsageTimeBound(date = date)

        fun from(dateTime: LocalDateTime): UsageTimeBound = UsageTimeBound(date = dateTime.toLocalDate())
    }
}

internal enum class UsageFilterPreset {
    Today,
    Last7Days,
    ThisWeek,
    Last30Days,
    ThisMonth,
}

internal fun usageFilterPresetRange(
    preset: UsageFilterPreset,
    today: LocalDate,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): Pair<LocalDateTime, LocalDateTime> {
    val end = today.atTime(23, 59)
    val startDate = when (preset) {
        UsageFilterPreset.Today -> today
        // 周四：上周四 00:00 → 今天 23:59。不是「含今天共 7 个日历日」。
        UsageFilterPreset.Last7Days -> today.minusDays(7)
        // 周四：本周一 00:00 → 今天 23:59。
        UsageFilterPreset.ThisWeek -> today.with(TemporalAdjusters.previousOrSame(weekStart))
        UsageFilterPreset.Last30Days -> today.minusDays(30)
        UsageFilterPreset.ThisMonth -> today.withDayOfMonth(1)
    }
    return startDate.atTime(0, 0) to end
}

internal fun matchingUsageFilterPreset(
    start: UsageTimeBound,
    end: UsageTimeBound,
    today: LocalDate,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
    preferred: UsageFilterPreset? = null,
): UsageFilterPreset? {
    if (!start.isSet || !end.isSet) return null
    val matches = UsageFilterPreset.entries.filter { preset ->
        val (expectedStart, expectedEnd) = usageFilterPresetRange(preset, today, weekStart)
        start.date == expectedStart.toLocalDate() && end.date == expectedEnd.toLocalDate()
    }
    if (preferred != null && preferred in matches) return preferred
    return matches.firstOrNull()
}
