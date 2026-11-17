package app.daiyu.android.domain.runtime

/** Resolved host metadata for one invocation; it does not confirm command execution. */
data class TerminalExecutionTarget(
    val backend: String,
    val uid: Int? = null,
    val guestUid: Int? = null,
    val root: Boolean? = null,
    val hostRoot: Boolean? = null,
)
