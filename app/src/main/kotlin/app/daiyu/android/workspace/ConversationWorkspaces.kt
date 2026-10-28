package app.daiyu.android.workspace

/** Saved choices and the active choice travel with the conversation's existing binding snapshot. */
internal data class ConversationWorkspaces(
    val bindings: List<ConversationWorkspaceBinding>,
    val activeIndex: Int,
) {
    init {
        require(if (bindings.isEmpty()) activeIndex == -1 else activeIndex in bindings.indices) {
            "workspace_binding_invalid"
        }
        require(bindings.distinct().size == bindings.size) { "workspace_binding_invalid" }
    }

    val active: ConversationWorkspaceBinding
        get() = bindings.getOrNull(activeIndex) ?: ConversationWorkspaceBinding.Private()

    fun add(binding: ConversationWorkspaceBinding): ConversationWorkspaces {
        val existing = bindings.indexOf(binding)
        return if (existing >= 0) copy(activeIndex = existing)
        else ConversationWorkspaces(bindings + binding, bindings.size)
    }

    fun replace(index: Int, binding: ConversationWorkspaceBinding): ConversationWorkspaces {
        require(index in bindings.indices) { "workspace_binding_invalid" }
        require(bindings.withIndex().none { it.index != index && it.value == binding }) { "workspace_duplicate" }
        return copy(bindings = bindings.mapIndexed { position, old -> if (position == index) binding else old })
    }

    fun select(index: Int): ConversationWorkspaces {
        require(index in bindings.indices) { "workspace_binding_invalid" }
        return copy(activeIndex = index)
    }

    fun remove(indices: Set<Int>): ConversationWorkspaces {
        require(indices.isNotEmpty() && indices.all { it in bindings.indices }) { "workspace_binding_invalid" }
        val remaining = bindings.filterIndexed { index, _ -> index !in indices }
        val nextIndex = when {
            remaining.isEmpty() -> -1
            activeIndex in indices -> 0
            else -> activeIndex - indices.count { it < activeIndex }
        }
        return ConversationWorkspaces(remaining, nextIndex)
    }
}
