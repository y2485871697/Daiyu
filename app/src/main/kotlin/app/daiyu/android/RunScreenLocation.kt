package app.daiyu.android

import app.daiyu.android.tasksurface.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal interface RunScreenLocationUi {
    fun show(question: RunScreenLocation.Question)
    fun dismiss(question: RunScreenLocation.Question)
}

/** Accepted-run location question. It is independent of the interactive ask_user switch. */
internal class RunScreenLocation(private val owner: Any, store: TaskSurfaceStore,
    ownPackage: String, private val current: () -> Boolean,
    private val present: (Question) -> Unit) : AutoCloseable {
    class Question internal constructor(val location: RunScreenLocation,
        internal val adapter: TaskSurfaceRouteAdapter, internal val request: TaskSurfaceRouteAdapter.Request,
        internal val original: TaskSurfaceRunDecision.LocationQuestion) {
        private val answered = CompletableFuture<Unit>()
        fun answer(surface: TaskSurface?) {
            adapter.answerFromUser(request, original, surface)
            answered.complete(Unit)
        }
        fun whenAnswered(callback: () -> Unit) { answered.whenComplete { _, _ -> callback() } }
        internal fun await(cancelled: () -> Boolean) {
            while (!answered.isDone) {
                if (cancelled() || !location.current()) { answer(null); return }
                try { answered.get(100, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) { }
            }
        }
    }
    private val frozen = store.freeze(owner, ownPackage)
    private val adapter = TaskSurfaceRouteAdapter(frozen, current)
    @Volatile private var question: Question? = null
    fun route(target: String?, backgroundSupported: Boolean, cancelled: () -> Boolean): TaskSurfaceDecision {
        val request = TaskSurfaceRouteAdapter.Request(owner, target ?: "<external-unspecified>", backgroundSupported)
        var route = adapter.decision(request)
        if (route === TaskSurfaceDecision.AskRequired) {
            val original = adapter.askFromUser(request) ?: return adapter.decision(request)
            val pending = Question(this, adapter, request, original).also { question = it }
            if (!current() || cancelled()) pending.answer(null) else present(pending)
            pending.await(cancelled)
            route = adapter.decision(request)
        }
        return route
    }
    override fun close() { question?.answer(null); frozen.end() }
}
