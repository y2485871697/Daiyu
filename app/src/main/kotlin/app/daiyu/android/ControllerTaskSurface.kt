package app.daiyu.android

import android.os.Looper
import app.daiyu.android.tasksurface.*

/** One location snapshot per ORIGINAL Controller entry. Disk reads never run on Main. Ending a
 * preparation while its read is in flight fences admission immediately; the late run is ended
 * on that worker, not installed as a replacement. No Android API receives the routing sentinel. */
internal class ControllerTaskSurface(
    private val owner: Any,
    private val store: TaskSurfaceStore,
    private val ownPackage: String,
    targetPackage: String?,
    backgroundSupported: Boolean,
    private val unsupportedPlan: Boolean = false,
    private val admission: () -> Boolean
) {
    val request = TaskSurfaceRouteAdapter.Request(owner, targetPackage ?: EXTERNAL_UNSPECIFIED, backgroundSupported)
    @Volatile private var ended = false
    @Volatile private var run: TaskSurfaceRunDecision? = null
    @Volatile var adapter: TaskSurfaceRouteAdapter? = null
        private set
    private var freezing = false

    fun freezeOnWorker(): TaskSurfaceDecision {
        check(Looper.myLooper() !== Looper.getMainLooper())
        val start = synchronized(this) {
            if (ended || freezing || run != null) false else { freezing = true; true }
        }
        if (start) {
            val frozen = store.freeze(owner, ownPackage)
            synchronized(this) {
                run = frozen
                adapter = TaskSurfaceRouteAdapter(frozen) { !ended && admission() }
                if (ended) frozen.end()
            }
        }
        return decision()
    }

    fun decision(): TaskSurfaceDecision {
        if (ended || !admission()) return TaskSurfaceDecision.NotExecuted("LOCATION_OWNER_REVOKED", "原任务位置访问已撤销")
        if (unsupportedPlan) return TaskSurfaceDecision.NotExecuted("VD_SELF_TARGET_UNSUPPORTED", "副屏任务不支持操作应用自身")
        return adapter?.decision(request)
            ?: TaskSurfaceDecision.NotExecuted("LOCATION_NOT_PREPARED", "任务执行位置尚未确定")
    }

    fun ready(surface: TaskSurface): TaskSurfaceDecision.Ready? =
        (decision() as? TaskSurfaceDecision.Ready)?.takeIf { it.surface == surface }

    /** Called only at the original parent's real drain, or proven resource-free rejection. */
    fun end() {
        synchronized(this) {
            ended = true
            run?.end()
        }
    }

    companion object {
        private const val EXTERNAL_UNSPECIFIED = "<external-unspecified>"
    }
}
