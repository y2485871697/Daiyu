package app.daiyu.android.delegation

/** User-assigned responsibility, not a benchmark or an inferred model capability. */
internal enum class SubAgentTaskTier(val wireValue: String, val label: String, val routingHint: String) {
    SIMPLE("simple", "简单任务", "search, organization, mechanical changes with explicit instructions"),
    REGULAR("regular", "常规任务", "localized fixes and well-scoped feature implementation"),
    COMPLEX("complex", "复杂任务", "cross-module changes, architecture, difficult debugging and edge cases");

    companion object {
        fun fromWireValue(value: String): SubAgentTaskTier? = entries.firstOrNull { it.wireValue == value }
    }
}
