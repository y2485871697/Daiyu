package app.daiyu.android.ui.media

import org.junit.Assert.*
import org.junit.Test

class ChatMediaPreviewVideoLayoutTest {
    @Test fun portraitVideoKeepsItsAspectInBothViewportOrientations() {
        for ((width, height) in listOf(1080f to 2400f, 2400f to 1080f)) {
            val frame = chatMediaPreviewVideoFit(width, height, 1080, 2344)
            assertTrue(frame.first <= width + 0.01f)
            assertTrue(frame.second <= height + 0.01f)
            assertEquals(1080f / 2344f, frame.first / frame.second, 0.0001f)
        }
        val landscape = chatMediaPreviewVideoFit(2400f, 1080f, 1080, 2344)
        assertTrue(landscape.first < 500f)
        assertEquals(1080f, landscape.second, 0.01f)
    }
    @Test fun landscapeAndSquareVideosFitWithoutStretchingOrCropping() {
        assertEquals(1080f to 607.5f, chatMediaPreviewVideoFit(1080f, 2400f, 1920, 1080))
        assertEquals(800f to 800f, chatMediaPreviewVideoFit(1200f, 800f, 600, 600))
        assertEquals(1080f to 2400f, chatMediaPreviewVideoFit(1080f, 2400f, 0, 0))
    }
    @Test fun previewRotationMapsGestureAndCutoutInsetsInsteadOfReusingPortraitBottom() {
        assertEquals(listOf(10, 20, 30, 40), chatMediaPreviewVideoInsets(10, 20, 30, 40, false))
        assertEquals(listOf(20, 30, 40, 10), chatMediaPreviewVideoInsets(10, 20, 30, 40, true))
        assertEquals(listOf(0, 0, 48, 0), chatMediaPreviewVideoInsets(0, 0, 0, 48, true))
    }
    @org.junit.Test fun waveEnvelopeMatchesTheReferenceAndFlattensWithoutChangingTrackWidth() {
        org.junit.Assert.assertEquals(6f, chatMediaPreviewVideoWaveAmplitude(0f, 120f, 1f), 0.001f)
        org.junit.Assert.assertEquals(3f, chatMediaPreviewVideoWaveAmplitude(120f, 120f, 1f), 0.001f)
        org.junit.Assert.assertEquals(0f, chatMediaPreviewVideoWaveAmplitude(180f, 120f, 1f), 0.001f)
        org.junit.Assert.assertEquals(0f, chatMediaPreviewVideoWaveAmplitude(0f, 120f, 0f), 0.001f)
    }

}
