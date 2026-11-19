package app.daiyu.android.ui.media

import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.ui.screens.chat.conversation.MessageVideoThumbnailPrefetch
import app.daiyu.android.ui.screens.chat.conversation.MessageVideoUi
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaPreviewVideoPrefetchTest {
    private fun video(): MessageVideoUi {
        val owner = MessageVideoOwner("chat", "message")
        return MessageVideoUi(owner, MessageVideoReference(owner, UUID.randomUUID().toString(),
            "video/mp4", 4, "a".repeat(64), 1_000, 32, 24))
    }

    @Test fun movingWindowKeepsIntersectingLoadsAndCancelsOnlyDepartedReferences() = runTest {
        val first = video(); val retained = video(); val next = video()
        val starts = mutableMapOf<MessageVideoUi, Int>()
        val cancelled = mutableSetOf<MessageVideoUi>()
        val prefetch = MessageVideoThumbnailPrefetch(backgroundScope) { item ->
            starts[item] = (starts[item] ?: 0) + 1
            try { awaitCancellation() } finally { cancelled += item }
        }
        prefetch.update(listOf(first, retained)); runCurrent()
        prefetch.update(listOf(retained, next)); runCurrent()
        assertEquals(mapOf(first to 1, retained to 1, next to 1), starts)
        assertEquals(setOf(first), cancelled)
        prefetch.update(emptyList()); runCurrent()
        assertEquals(setOf(first, retained, next), cancelled)
    }

    @Test fun exactReferenceWindowIsDeduplicatedAndLimitedToFour() = runTest {
        val videos = List(6) { video() }
        val starts = mutableListOf<MessageVideoUi>()
        val prefetch = MessageVideoThumbnailPrefetch(backgroundScope) { starts += it }
        prefetch.update(listOf(videos[0], videos[0]) + videos.drop(1)); runCurrent()
        assertEquals(videos.take(4), starts)
        prefetch.update(videos.take(4)); runCurrent()
        assertEquals(4, starts.size)
    }
}
