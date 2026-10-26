package app.daiyu.android.delegation

/** Stable IDs retain their duties; every other configuration field may hot-update. */
internal fun ConversationSubAgentConfig.acceptsModelHotUpdate(next: ConversationSubAgentConfig): Boolean =
    next.profiles.map { it.id }.distinct().size == next.profiles.size && profiles.all { previous ->
        next.profiles.singleOrNull { it.id == previous.id }?.role == previous.role
    }
