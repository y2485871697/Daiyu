package app.daiyu.android.ui.screens.chat.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch

/** A bounded lookahead window keeps work shared by consecutive viewports. */
internal class MessageThumbnailPrefetch<T>(
    private val scope: CoroutineScope,
    private val load: suspend (T) -> Unit,
) {
    private val jobs = mutableMapOf<T, Job>()
    private val permits = Semaphore(2)

    fun update(items: List<T>) {
        val wanted = items.distinct().take(4).toSet()
        jobs.keys.filter { it !in wanted }.forEach { jobs.remove(it)?.cancel() }
        for (item in wanted) jobs.getOrPut(item) { scope.launch { permits.withPermit { load(item) } } }
    }
}
