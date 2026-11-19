package app.daiyu.android.ui.screens.chat.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A moving four-video window keeps work shared by consecutive viewports. */
internal class MessageVideoThumbnailPrefetch(
    private val scope: CoroutineScope,
    private val load: suspend (MessageVideoUi) -> Unit,
) {
    private val jobs = mutableMapOf<MessageVideoUi, Job>()

    fun update(videos: List<MessageVideoUi>) {
        val wanted = videos.distinct().take(4).toSet()
        jobs.keys.filter { it !in wanted }.forEach { jobs.remove(it)?.cancel() }
        for (video in wanted) jobs.getOrPut(video) { scope.launch { load(video) } }
    }
}
