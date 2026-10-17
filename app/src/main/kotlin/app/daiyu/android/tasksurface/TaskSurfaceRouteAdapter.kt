package app.daiyu.android.tasksurface

/** Small admission-before-allocation adapter shared by GUI and VD preparation.
 * It allocates NO lease/session, asks NO question, and performs NO Android I/O itself.
 * Background support must mean the EXACT already-supported operation (e.g. the explicit R08
 * plan), not merely that a virtual display can exist. R08 approval remains independently required. */
internal class TaskSurfaceRouteAdapter(
    private val run: TaskSurfaceRunDecision,
    private val ownerCurrentAndAdmissionOpen: () -> Boolean,
) {
    data class Request(val owner: Any, val targetPackage: String, val backgroundSupported: Boolean)
    sealed interface Preparation<out T> {
        data class Prepared<T>(val value: T) : Preparation<T>
        data class NotPrepared(val decision: TaskSurfaceDecision) : Preparation<Nothing>
    }

    fun decision(request: Request): TaskSurfaceDecision {
        if (request.owner !== run.owner || !ownerCurrentAndAdmissionOpen())
            return TaskSurfaceDecision.NotExecuted("TASK_SURFACE_OWNER_OR_ADMISSION_REVOKED", "本次未执行；原运行已失效或正在排空。")
        val route = run.decision(request.targetPackage, request.backgroundSupported)
        return if (route is TaskSurfaceDecision.Ready && route.selfTarget != null)
            route.copy(selfTarget = route.selfTarget.guardedBy(ownerCurrentAndAdmissionOpen))
        else route
    }

    /** User location question, NOT ordinary ask_user and NOT whole-plan authorization. */
    fun askFromUser(request: Request): TaskSurfaceRunDecision.LocationQuestion? {
        if (decision(request) !== TaskSurfaceDecision.AskRequired) return null
        return run.askFromUser(request.targetPackage)
    }
    fun answerFromUser(request: Request, original: TaskSurfaceRunDecision.LocationQuestion, answer: TaskSurface?): Boolean {
        if (request.owner !== run.owner || !ownerCurrentAndAdmissionOpen()) {
            // Owner loss dismisses the original question permanently, rather than allowing retry.
            run.answerFromUser(original, null)
            return false
        }
        return run.answerFromUser(original, answer)
    }

    /** Callbacks must retain their existing owner/auth/Unknown/drain checks at the I/O boundary.
     * Exactly ONE selected backend is invoked. No rejection/exception is caught for fallback.
     * In particular a background NotExecuted must not call foreground acquire/global action,
     * screenshot, launch, or replace an existing retained session. */
    fun <T> prepare(request: Request, foreground: (TaskSurfaceSelfTarget?) -> T, background: () -> T): Preparation<T> {
        val route = decision(request)
        if (route !is TaskSurfaceDecision.Ready) return Preparation.NotPrepared(route)
        return Preparation.Prepared(when (route.surface) {
            TaskSurface.FOREGROUND -> foreground(route.selfTarget)
            TaskSurface.BACKGROUND -> background()
        })
    }
}
