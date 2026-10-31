package app.daiyu.android.ui.media

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaPreviewStateTest {
    private fun item(key: String, mime: String = "image/png") =
        ChatMediaPreviewItem(key, mime, "$key.png", { null }, { null })

    @Test
    fun galleryDeduplicatesByOpaqueKeyAndKeepsFirstAuthorizedCallbacks() {
        val first = item("owner-a")
        val duplicate = item("owner-a", "video/mp4")
        val spacedKey = item(" owner-a ")
        val actual = chatMediaPreviewGallery(listOf(item(" "), first, duplicate, spacedKey))
        assertEquals(listOf("owner-a", " owner-a "), actual.map { it.key })
        assertSame(first, actual[0])
        assertSame(spacedKey, actual[1])
    }

    @Test
    fun mixedGallerySelectionUsesDeduplicatedIndexAndRejectsUnknownKey() {
        val photo = item("photo")
        val video = item("video", "video/mp4")
        val gallery = listOf(photo, photo, video)
        val selected = requireNotNull(chatMediaPreviewSelection(gallery, "video"))
        assertEquals(1, selected.initialIndex)
        assertSame(video, selected.gallery[selected.initialIndex])
        assertNull(chatMediaPreviewSelection(gallery, "missing"))
        assertNull(chatMediaPreviewSelection(emptyList(), "photo"))
    }

    @Test
    fun scaleOneLeavesSinglePointerToPagerButPinchAndZoomedPanConsume() {
        assertFalse(chatMediaPreviewConsumesGesture(1f, 1))
        assertTrue(chatMediaPreviewConsumesGesture(1f, 2))
        assertTrue(chatMediaPreviewConsumesGesture(1.001f, 1))
        assertTrue(chatMediaPreviewPagerEnabled(1f, false, 2))
        assertFalse(chatMediaPreviewPagerEnabled(1f, true, 2))
        assertFalse(chatMediaPreviewPagerEnabled(2f, false, 2))
        assertFalse(chatMediaPreviewPagerEnabled(1f, false, 1))
    }

    @Test
    fun zoomClampsAtFiveAndReturningToOneResetsPan() {
        val enlarged = chatMediaPreviewTransform(ChatMediaPreviewTransform(), 10f, Offset(30f, -20f))
        assertEquals(5f, enlarged.scale, 0f)
        assertEquals(Offset(30f, -20f), enlarged.offset)
        val dragged = chatMediaPreviewTransform(enlarged, 1f, Offset(4f, 8f))
        assertEquals(Offset(34f, -12f), dragged.offset)
        val reset = chatMediaPreviewTransform(dragged, 0.01f, Offset(4f, 8f))
        assertEquals(ChatMediaPreviewTransform(), reset)
        assertTrue(chatMediaPreviewPagerEnabled(reset.scale, false, 2))
    }

    @Test
    fun videoOnlyPlaysOnCurrentForegroundPage() {
        assertTrue(chatMediaPreviewVideoShouldPlay(true, true))
        assertFalse(chatMediaPreviewVideoShouldPlay(false, true))
        assertFalse(chatMediaPreviewVideoShouldPlay(true, false))
        assertFalse(chatMediaPreviewVideoShouldPlay(false, false))
    }
}
