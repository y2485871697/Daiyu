package app.daiyu.android.timeline

internal fun compressionActivitiesByAnchor(
    activities: List<CompressionActivity>,
    timeline: List<ChatTimelineEntry>,
): Map<String?, List<CompressionActivity>> {
    val completed = timeline.filterIsInstance<ChatTimelineEntry.Compression>().mapTo(hashSetOf()) { it.id }
    return activities.filter { it.running && it.id !in completed }.distinctBy { it.id }
        .groupBy { it.anchor?.listKey }
}
