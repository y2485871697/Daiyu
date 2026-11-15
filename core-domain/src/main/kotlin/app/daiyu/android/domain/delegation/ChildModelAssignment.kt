package app.daiyu.android.domain.delegation

/** A task-local binding: replacing it never mutates a sibling's saved definition. */
internal class ChildModelAssignment(profileId: String, initial: ChildModelDefinition) {
    @Volatile private var target = initial
    @Volatile var profileId: String = profileId
        private set
    val role: ChildModelRole = initial.role
    val definition = ChildModelDefinition(initial.poolKey, initial.parallelLimit, initial.display,
        latest = { target.current() }, selection = Any(), valid = { target.isValid() },
        admissionAllowed = { target.acceptsNewTask() }, role = role) { target.create() }
        .identified(profileId, initial.modelIdentity.orEmpty(), initial.groupToken)

    fun reassign(profileId: String, next: ChildModelDefinition) {
        require(next.role == role) { "child_role_mismatch" }
        check(next.isValid() && next.acceptsNewTask()) { "child_profile_unavailable" }
        target = next
        this.profileId = profileId
    }
}
