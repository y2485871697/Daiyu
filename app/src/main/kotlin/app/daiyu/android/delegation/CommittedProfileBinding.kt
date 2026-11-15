package app.daiyu.android.delegation

/** Consumes every committed edge; later compatible presets never erase an earlier revocation. */
internal class CommittedProfileBinding(start: ConversationSubAgentPreferences.RuntimeStart,
    initial: SubAgentProfile, private val sameModel: (SubAgentProfile, SubAgentProfile) -> Boolean =
        { a, b -> a.providerId == b.providerId && a.modelId == b.modelId }) {
    data class View(val revision: Long, val config: ConversationSubAgentConfig, val profile: SubAgentProfile)
    private var cursor: ConversationSubAgentPreferences.RuntimeCursor? = start.cursor()
    private val role = initial.role
    private var assigned = initial
    private var config = start.config
    private var revision = start.revision
    private var revoked = false
    @Synchronized fun current(): View? {
        if (revoked) return null
        cursor!!.drain { edge ->
            if (!revoked) {
                val next = edge.after
                val replacement = if (next == null || edge.kind == ConversationSubAgentPreferences.CommitKind.RESET) null
                else if (edge.kind == ConversationSubAgentPreferences.CommitKind.APPLY_PRESET)
                    next.profiles.filter { it.role == role }.sortedWith(compareBy<SubAgentProfile> { it.id != assigned.id }.thenBy { it.id })
                        .firstOrNull { sameModel(assigned, it) }
                else next.profiles.singleOrNull { it.id == assigned.id && it.role == role }
                if (replacement == null) revoked = true
                else { assigned = replacement; config = checkNotNull(next); revision = edge.revision }
            }
        }
        if (revoked) { cursor = null; return null }
        return View(revision, config, assigned)
    }
}
