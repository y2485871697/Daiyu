package app.daiyu.android.tasksurface

/** A location preference is NOT a GUI permission, an R08 plan approval, or ask_user. */
internal enum class TaskSurfacePreference { FOREGROUND, BACKGROUND, ASK }
internal enum class TaskSurface { FOREGROUND, BACKGROUND }

internal sealed interface TaskSurfaceSettings {
    data class Available(val preference: TaskSurfacePreference) : TaskSurfaceSettings
    data object Unavailable : TaskSurfaceSettings
}

internal sealed interface TaskSurfaceDecision {
    data class Ready(val surface: TaskSurface, val selfTarget: TaskSurfaceSelfTarget? = null) : TaskSurfaceDecision
    data object AskRequired : TaskSurfaceDecision
    data object AskPending : TaskSurfaceDecision
    data class NotExecuted(val code: String, val message: String) : TaskSurfaceDecision
}

/** Only a current foreground route can mint this narrow target parameter. Not an authorization.
 * A future self-tree adapter must still check the original GUI lease/owner/authorization/fence.
 * Do not remove externalRoot's own-package rejection or make this a process-wide switch. */
internal class TaskSurfaceSelfTarget private constructor(
    val packageName: String,
    private val owner: Any,
    private val current: () -> Boolean,
) {
    fun isCurrent(originalOwner: Any, targetPackage: String): Boolean =
        originalOwner === owner && targetPackage == packageName && current()
    internal fun guardedBy(admission: () -> Boolean) =
        TaskSurfaceSelfTarget(packageName, owner) { current() && admission() }
    companion object {
        internal fun foreground(packageName: String, owner: Any, current: () -> Boolean) =
            TaskSurfaceSelfTarget(packageName, owner, current)
    }
}

/** Retain ONE instance on the existing preparation/run owner until its actual end/drain.
 * Never recreate it to retry ASK, recover Unknown, or replace a revoked GUI/VD session.
 * Settings are copied once; changing the base preference cannot change an existing run. */
internal class TaskSurfaceRunDecision(
    val owner: Any,
    snapshot: TaskSurfaceSettings,
    private val ownPackage: String,
) {
    class LocationQuestion internal constructor(internal val run: TaskSurfaceRunDecision)
    private val preference = (snapshot as? TaskSurfaceSettings.Available)?.preference
    private var selected = when (preference) {
        TaskSurfacePreference.FOREGROUND -> TaskSurface.FOREGROUND
        TaskSurfacePreference.BACKGROUND -> TaskSurface.BACKGROUND
        else -> null
    }
    private var question: LocationQuestion? = null
    private var cancelled = false
    private var ended = false

    init { require(ownPackage.isNotBlank()) }

    @Synchronized fun decision(targetPackage: String, backgroundSupported: Boolean): TaskSurfaceDecision {
        if (ended) return notExecuted("TASK_SURFACE_RUN_ENDED")
        if (preference == null) return notExecuted("TASK_SURFACE_SETTINGS_UNAVAILABLE")
        if (targetPackage.isBlank()) return notExecuted("TASK_SURFACE_TARGET_REQUIRED")
        // Effective route only: never mutate selected, cancelled, question or the durable preference.
        if (targetPackage == ownPackage) return TaskSurfaceDecision.Ready(TaskSurface.FOREGROUND,
            TaskSurfaceSelfTarget.foreground(ownPackage, owner) { selfTargetCurrent() })
        if (cancelled) return notExecuted("TASK_SURFACE_ASK_CANCELLED")
        return when (selected) {
            TaskSurface.FOREGROUND -> TaskSurfaceDecision.Ready(TaskSurface.FOREGROUND)
            TaskSurface.BACKGROUND -> if (backgroundSupported) TaskSurfaceDecision.Ready(TaskSurface.BACKGROUND)
                else TaskSurfaceDecision.NotExecuted("TASK_SURFACE_BACKGROUND_UNSUPPORTED",
                    "后台执行不可用，本次未执行。请不要清理应用；不会切换到前台执行。")
            null -> if (question == null) TaskSurfaceDecision.AskRequired else TaskSurfaceDecision.AskPending
        }
    }

    /** Explicit trusted location-dialog entry only. Ordinary model/tool requests call decision().
     * A self-package target consumes no question. A question is issued at most once per run. */
    @Synchronized fun askFromUser(targetPackage: String): LocationQuestion? {
        if (decision(targetPackage, backgroundSupported = false) !== TaskSurfaceDecision.AskRequired) return null
        return LocationQuestion(this).also { question = it }
    }

    /** null means cancel/dismiss/owner loss. A cancelled run can never ask or default again. */
    @Synchronized fun answerFromUser(original: LocationQuestion, answer: TaskSurface?): Boolean {
        if (ended || original.run !== this || question !== original || cancelled || selected != null) return false
        if (answer == null) cancelled = true else selected = answer
        return true
    }

    /** Mark ended only when the parent actually ends; do not clear this state on dialog dismissal. */
    @Synchronized fun end() { ended = true }

    @Synchronized private fun selfTargetCurrent(): Boolean = !ended && preference != null

    private fun notExecuted(code: String) = TaskSurfaceDecision.NotExecuted(code, "本次未执行；未申请前台或后台执行资源。")
}
