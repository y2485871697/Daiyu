package app.daiyu.android

import android.graphics.Bitmap
import app.daiyu.android.ui.screens.chat.conversation.WorkStepOwnerUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ToolScreenshotPreviewCacheTest {
    private val key = ToolScreenshotPreviewCache.Key(WorkStepOwnerUi("conversation", "run", "message"), "call", 1, 2)
    private fun bitmap() = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)

    @Test fun verifiedPreviewRetainsExactBitmapForMultipleReaders() {
        val cache = ToolScreenshotPreviewCache()
        val image = bitmap()
        cache.put(key, image, false)
        assertSame(image, cache[key]!!.image)
        assertSame(cache[key], cache[key])
        assertFalse(cache[key]!!.cleared)
    }
    @Test fun everyOwnerComponentCallAndRevisionMustMatch() {
        val cache = ToolScreenshotPreviewCache()
        cache.put(key, bitmap(), false)
        for (other in listOf(key.copy(owner = key.owner.copy(conversationId = "other")),
            key.copy(owner = key.owner.copy(runId = "other")), key.copy(owner = key.owner.copy(messageId = "other")),
            key.copy(callId = "other"), key.copy(dataRevision = 2), key.copy(screenshotRevision = 3))) {
            assertNull(cache[other])
        }
    }
    @Test fun clearedResultSurvivesReentryWithoutImageOrRetry() {
        val cache = ToolScreenshotPreviewCache()
        cache.put(key, null, true)
        assertTrue(cache[key]!!.cleared)
        assertNull(cache[key]!!.image)
    }
    @Test fun failedReadIsNotCachedAndCanBeRetried() {
        val cache = ToolScreenshotPreviewCache()
        cache.put(key, null, false)
        assertNull(cache[key])
        val image = bitmap()
        cache.put(key, image, false)
        assertSame(image, cache[key]!!.image)
    }
    @Test fun evictionIsBoundedAndDoesNotRecycleBorrowedImages() {
        val cache = ToolScreenshotPreviewCache()
        val image = bitmap()
        cache.put(key, image, false)
        val borrowed = cache[key]!!
        repeat(16) { cache.put(key.copy(callId = "call-$it"), bitmap(), false) }
        assertNull(cache[key])
        assertFalse(borrowed.image!!.isRecycled)
        assertEquals(4, borrowed.image.width)
    }
    @Test fun readsUpdateLruOrderAndClearDropsAllReferencesWithoutRecycling() {
        val cache = ToolScreenshotPreviewCache()
        val image = bitmap()
        cache.put(key, image, false)
        repeat(15) { cache.put(key.copy(callId = "call-$it"), null, true) }
        assertNotNull(cache[key])
        cache.put(key.copy(callId = "last"), null, true)
        assertNotNull(cache[key])
        assertNull(cache[key.copy(callId = "call-0")])
        cache.clear()
        assertNull(cache[key])
        assertNull(cache[key.copy(callId = "last")])
        assertFalse(image.isRecycled)
    }
}
