package app.daiyu.android.context

internal fun summaryCheckpointFixture(): String = SummaryCheckpoint.marker + "\n" +
    SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- retained summary fact" }
