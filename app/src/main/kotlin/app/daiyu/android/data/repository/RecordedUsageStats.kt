package app.daiyu.android.data.repository

import app.daiyu.android.domain.usage.RecordedUsageSummary

/** Current retained assistant-message coverage, not lifetime consumption or a provider bill.
 * Empty/legacy/imported rows without usage do not imply zero-token requests. */
internal data class RecordedUsageStats(
    val summary: RecordedUsageSummary,
    val totalAssistantMessages: Int,
    val inspectedAssistantMessages: Int,
    val messagesWithoutCheckpoint: Int,
    val messagesWithoutUsage: Int,
    val unreadableCheckpoints: Int,
    val deferredCheckpoints: Int,
    val totalPreparations: Int = 0,
    val inspectedPreparations: Int = 0,
    val preparationsWithoutUsage: Int = 0,
    val unreadablePreparations: Int = 0,
) {
    val scanComplete: Boolean get() = inspectedAssistantMessages == totalAssistantMessages &&
        unreadableCheckpoints == 0 && deferredCheckpoints == 0 &&
        inspectedPreparations == totalPreparations && unreadablePreparations == 0
}
