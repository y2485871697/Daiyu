package app.daiyu.android

import android.graphics.Bitmap
import android.util.LruCache
import app.daiyu.android.ui.screens.chat.conversation.WorkStepOwnerUi

/** Controller-owned, verified display results. Eviction drops references, never recycles a UI-borrowed bitmap. */
internal class ToolScreenshotPreviewCache {
    data class Key(val owner: WorkStepOwnerUi, val callId: String, val dataRevision: Long, val screenshotRevision: Long)
    data class Preview(val image: Bitmap?, val cleared: Boolean)

    private val entries = object : LruCache<Key, Preview>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Preview): Int = maxOf(512 * 1024, value.image?.allocationByteCount ?: 0)
    }

    operator fun get(key: Key): Preview? = entries.get(key)
    fun put(key: Key, image: Bitmap?, cleared: Boolean) {
        if (image != null || cleared) entries.put(key, Preview(image, cleared))
    }
    fun clear() = entries.evictAll()
}
