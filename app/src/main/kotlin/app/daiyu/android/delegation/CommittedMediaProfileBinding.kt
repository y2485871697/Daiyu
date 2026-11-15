package app.daiyu.android.delegation

/** Media never bridges presets or revives after a committed deletion or role change. */
internal class CommittedMediaProfileBinding(
    start: ConversationSubAgentPreferences.RuntimeStart,
    private val original: SubAgentProfile,
) {
    private val cursor = start.cursor()
    private var valid = start.config.profiles.any { it.id == original.id && it.role == original.role }
    @Synchronized fun current(): Boolean {
        if (!valid) return false
        cursor.drain { edge ->
            if (edge.kind != ConversationSubAgentPreferences.CommitKind.EDIT ||
                edge.after?.profiles?.none { it.id == original.id && it.role == original.role } != false)
                valid = false
        }
        return valid
    }
}
