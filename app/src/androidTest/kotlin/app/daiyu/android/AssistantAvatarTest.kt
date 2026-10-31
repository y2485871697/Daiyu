package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.ui.components.DefaultAssistantAvatar
import kotlinx.coroutines.*

@Suppress("DEPRECATION")
class AssistantAvatarTest : InstrumentationTestCase() {
    fun testConcurrentAndRemountedAvatarsShareOneSampledBitmap() = runBlocking {
        val context = instrumentation.targetContext
        val images = (1..40).map { async { DefaultAssistantAvatar.load(context) } }.awaitAll()
        val first = checkNotNull(images.first())
        assertTrue(first.width in 256..511)
        assertTrue(first.height in 256..511)
        images.forEach { assertSame(first, it) }
        repeat(100) { assertSame(first, DefaultAssistantAvatar.load(context)) }
        assertSame(first, DefaultAssistantAvatar.image.value)
    }
}
