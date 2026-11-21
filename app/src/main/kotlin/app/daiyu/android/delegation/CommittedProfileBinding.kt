package app.daiyu.android.delegation

/** Consumes every committed edge; later presets never erase an earlier revocation. */
internal class CommittedProfileBinding(start: ConversationSubAgentPreferences.RuntimeStart,
    initial: SubAgentProfile, private val sameModel: (SubAgentProfile, SubAgentProfile) -> Boolean =
        { a, b -> a.modelId == b.modelId }) {
    data class View(val revision: Long, val config: ConversationSubAgentConfig,
        val profile: SubAgentProfile, val candidates: List<SubAgentProfile>)
    private var cursor: ConversationSubAgentPreferences.RuntimeCursor? = start.cursor()
    private val role = initial.role
    private var assigned = initial
    private var config = start.config
    private var admittedIds = config.profiles.map { it.id }.toSet()
    private var candidates = listOf(initial)
    private var preference = initial
    private var revision = start.revision
    private var revoked = false
    private fun preferred(profiles: List<SubAgentProfile>, previous: SubAgentProfile): List<SubAgentProfile> {
        val compatible = profiles.filter { it.role == role }
        val matching = compatible.filter { sameModel(previous, it) }
        return matching.ifEmpty { compatible }
    }
    @Synchronized fun current(): View? {
        if (revoked) return null
        cursor!!.drain { edge ->
            if (!revoked) {
                val next = edge.after
                if (next == null || edge.kind == ConversationSubAgentPreferences.CommitKind.RESET) revoked = true
                else {
                    if (edge.kind == ConversationSubAgentPreferences.CommitKind.APPLY_PRESET)
                        admittedIds = next.profiles.map { it.id }.toSet()
                    val stable = next.profiles.singleOrNull { it.id == assigned.id && it.role == role }
                    val choices = if (stable != null) {
                        preference = stable
                        listOf(stable)
                    } else next.profiles.filter { it.role == role && it.id in admittedIds }
                    val preferred = preferred(choices, preference)
                    val replacement = preferred.firstOrNull { it.id == assigned.id } ?: preferred.firstOrNull()
                    if (replacement == null) revoked = true
                    else { assigned = replacement; candidates = choices; config = next; revision = edge.revision }
                }
            }
        }
        if (revoked) { cursor = null; return null }
        return View(revision, config, assigned, preferred(candidates, preference))
    }
    @Synchronized fun select(profileId: String, expectedRevision: Long? = null): Boolean {
        val view = current() ?: return false
        if (expectedRevision != null && view.revision != expectedRevision) return false
        assigned = view.candidates.singleOrNull { it.id == profileId } ?: return false
        preference = assigned
        return true
    }
}
