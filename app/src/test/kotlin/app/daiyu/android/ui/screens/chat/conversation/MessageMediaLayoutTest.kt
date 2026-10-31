package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class MessageMediaLayoutTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test fun longTextAndEveryNonMediaSourceAreHardGroupingBarriers() {
        // false may be long text, blank text, a tool, or a thought: none is filtered out.
        val media = listOf(false, true, true, false, true, false, false, true, true, false)
        val ranges = messageContentRanges(media)
        assertEquals(listOf(0..0, 1..2, 3..3, 4..4, 5..5, 6..6, 7..8, 9..9), ranges)
        assertEquals(media.indices.toList(), ranges.flatMap { it.toList() })
        val longText = "正文正常展开，不以窄图片作为气泡宽度。".repeat(500)
        val sources = listOf(longText, "image", longText, "image", "image", "tool", "thought", longText)
        val grouped = messageContentRanges(sources.map { it == "image" })
        assertEquals(sources, grouped.flatMap { range -> range.map { sources[it] } })
        assertEquals(listOf(0..0, 1..1, 2..2, 3..4, 5..5, 6..6, 7..7), grouped)
        assertTrue(messageContentRanges(emptyList()).isEmpty())
    }

    @Test fun singlePortraitFitsDecodedRatioWithNoFullWidthBackingCell() {
        val layout = messageMediaGeometry(320f, listOf(0.5f))
        val frame = layout.frames.single()
        near(0f, frame.x); near(0f, frame.y)
        near(180f, frame.width); near(360f, frame.height)
        near(frame.width, layout.width)
        near(0.5f, frame.width / frame.height)
        // Portrait media's 180dp width is not passed to any neighbouring text entry.
        assertTrue(layout.width < 320f)
    }

    @Test fun singleLandscapeAndPanoramaRespectBothCapsWithoutCropping() {
        for (ratio in listOf(1f, 4f / 3f, 16f / 9f, 12f, 0.08f)) {
            val layout = messageMediaGeometry(280f, listOf(ratio))
            val frame = layout.frames.single()
            assertTrue(frame.width <= 280f)
            assertTrue(frame.height <= 360f)
            near(ratio, frame.width / frame.height)
            near(frame.width, layout.width)
            near(frame.height, layout.height)
        }
    }

    @Test fun twoPortraitsSitSideBySideEightDpApartInSourceOrder() {
        val layout = messageMediaGeometry(320f, listOf(0.5f, 0.5f))
        val (first, second) = layout.frames
        near(first.y, second.y)
        near(8f, second.x - first.x - first.width)
        near(0.5f, first.width / first.height)
        near(0.5f, second.width / second.height)
        near(second.x + second.width, layout.width)
        assertTrue(layout.height <= 216f)
    }

    @Test fun twoLandscapeImagesUseVerticalStrip() {
        val layout = messageMediaGeometry(320f, listOf(16f / 9f, 4f / 3f))
        val (first, second) = layout.frames
        near(0f, first.x); near(0f, second.x)
        near(8f, second.y - first.y - first.height)
        near(16f / 9f, first.width / first.height)
        near(4f / 3f, second.width / second.height)
    }

    @Test fun slightlyLandscapePairStillUsesVerticalStrip() {
        val (first, second) = messageMediaGeometry(320f, listOf(1.01f, 1.2f)).frames
        near(0f, first.x); near(0f, second.x)
        near(8f, second.y - first.y - first.height)
    }

    @Test fun fourImagesAreCompactTwoColumnRowMajorGrid() {
        val ratios = listOf(1f, 0.75f, 1.8f, 1f)
        val layout = messageMediaGeometry(320f, ratios)
        val (a, b, c, d) = layout.frames
        near(a.y, b.y); near(c.y, d.y)
        near(a.x, c.x); near(b.x, d.x)
        near(8f, b.x - a.x - a.width)
        near(8f, d.x - c.x - c.width)
        near(8f, c.y - maxOf(a.height, b.height))
        layout.frames.forEachIndexed { index, frame ->
            near(ratios[index], frame.width / frame.height)
            assertTrue(frame.x >= 0f && frame.y >= 0f)
            assertTrue(frame.x + frame.width <= layout.width + 0.01f)
            assertTrue(frame.y + frame.height <= layout.height + 0.01f)
        }
    }

    @Test fun unknownAndInvalidDimensionsHaveFiniteNonLeakingPlaceholders() {
        val layout = messageMediaGeometry(240f, listOf(null, 0f, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(4, layout.frames.size)
        assertTrue(layout.frames.all { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f })
        layout.frames.forEach { near(4f / 3f, it.width / it.height) }
        assertEquals(MessageMediaGeometry(0f, 0f, emptyList()), messageMediaGeometry(320f, emptyList()))
        assertTrue(messageMediaGeometry(Float.POSITIVE_INFINITY, listOf(1f)).width <= 320f)
    }
}
