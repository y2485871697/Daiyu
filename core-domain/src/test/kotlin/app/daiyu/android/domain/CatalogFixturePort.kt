package app.daiyu.android.domain

import app.daiyu.android.domain.*

/** Synthetic transports explicitly accept catalog input; real provider fixtures must forward it. */
internal fun interface CatalogFixturePort : ModelPort {
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession = open(history)
}
