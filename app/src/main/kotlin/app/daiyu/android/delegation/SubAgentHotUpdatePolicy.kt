package app.daiyu.android.delegation

/** Existing stable IDs keep their role and task semantics; additions do not retarget them. */
internal fun ConversationSubAgentConfig.acceptsModelHotUpdate(next: ConversationSubAgentConfig): Boolean =
    enabled == next.enabled && parallelLimits.all { (model, limit) -> next.parallelLimits[model] == limit } &&
        legacyParallelLimits.all { (model, limit) -> next.legacyParallelLimits[model] == limit } && profiles.all { previous ->
        val current = next.profiles.singleOrNull { it.id == previous.id } ?: return@all false
        previous.copy(providerId = current.providerId, modelId = current.modelId,
            reasoning = current.reasoning, reasoningByModel = current.reasoningByModel,
            gptSpeedByModel = current.gptSpeedByModel) == current
    }
