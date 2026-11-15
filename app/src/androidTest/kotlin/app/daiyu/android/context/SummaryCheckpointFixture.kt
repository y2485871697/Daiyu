package app.daiyu.android.context

internal fun summaryCheckpointFixture(): String = SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- retained summary fact" }
