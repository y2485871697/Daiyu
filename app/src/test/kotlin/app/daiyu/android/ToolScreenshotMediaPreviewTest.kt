package app.daiyu.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolScreenshotMediaPreviewTest {
    private fun png(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val image = Bitmap.createBitmap(240, 600, Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        return ByteArrayOutputStream().use { stream ->
            check(image.compress(format, 100, stream))
            image.recycle(); stream.toByteArray()
        }
    }
    @Test fun displayDecodesAnIndependentFullResolutionImage() = runBlocking {
        val source = png()
        ToolScreenshotMediaPreview("key", "image/png", { true }) { ToolScreenshotOriginal(source, "image/png") }.use { preview ->
            val first = preview.item.loadImage(4096)!!.asAndroidBitmap()
            val second = preview.item.loadImage(4096)!!.asAndroidBitmap()
            assertNotSame(first, second)
            assertEquals(240, first.width); assertEquals(600, first.height)
            assertEquals("屏幕截图.png", preview.item.displayName)
            preview.close()
            assertFalse(first.isRecycled); assertFalse(second.isRecycled)
            first.recycle(); second.recycle()
        }
    }
    @Test fun displayHonorsTheOrdinaryViewerEdgeBudget() = runBlocking {
        val bytes = png()
        ToolScreenshotMediaPreview("key", "image/png", { true }) { ToolScreenshotOriginal(bytes, "image/png") }.use { preview ->
            val image = preview.item.loadImage(64)!!.asAndroidBitmap()
            assertTrue(maxOf(image.width, image.height) <= 64); image.recycle()
        }
    }
    @Test fun originalBytesAndIndependentStreamsArePreservedExactly() = runBlocking {
        val bytes = png()
        ToolScreenshotMediaPreview("key", "image/png", { true }) { ToolScreenshotOriginal(bytes, "image/png") }.use { preview ->
            preview.item.openOriginal()!!.use { first ->
                preview.item.openOriginal()!!.use { second ->
                    assertNotSame(first.stream, second.stream)
                    assertArrayEquals(bytes, first.stream.readBytes())
                    assertArrayEquals(bytes, second.stream.readBytes())
                    assertEquals("image/png", first.mimeType)
                }
            }
        }
    }
    @Test fun closingRevokesExistingStreamsAndFutureDecodes() = runBlocking {
        val bytes = png(); var reads = 0
        val preview = ToolScreenshotMediaPreview("key", "image/png", { true }) {
            reads++; ToolScreenshotOriginal(bytes, "image/png")
        }
        val original = preview.item.openOriginal()!!
        preview.close()
        try { original.stream.read(); fail("closed preview must revoke reads") } catch (_: IOException) { }
        assertNull(preview.item.loadImage(4096)); assertNull(preview.item.openOriginal()); assertEquals(1, reads)
    }
    @Test fun staleRevisionRejectsBeforeAndAfterOriginalRead() = runBlocking {
        val bytes = png(); var valid = false; var reads = 0
        ToolScreenshotMediaPreview("key", "image/png", { valid }) {
            reads++; valid = false; ToolScreenshotOriginal(bytes, "image/png")
        }.use { preview ->
            assertNull(preview.item.loadImage(4096)); assertEquals(0, reads)
            valid = true
            assertNull(preview.item.loadImage(4096)); assertEquals(1, reads)
            valid = true
            assertNull(preview.item.openOriginal()); assertEquals(2, reads)
        }
    }
    @Test fun mismatchedMimeAndMalformedImagesCannotDisplay() = runBlocking {
        ToolScreenshotMediaPreview("key", "image/png", { true }) {
            ToolScreenshotOriginal(png(), "image/jpeg")
        }.use { preview ->
            assertNull(preview.item.loadImage(4096)); assertNull(preview.item.openOriginal())
        }
        ToolScreenshotMediaPreview("key", "image/png", { true }) {
            ToolScreenshotOriginal(byteArrayOf(1, 2, 3), "image/png")
        }.use { assertNull(it.item.loadImage(4096)) }
    }
    @Test fun browserOriginalPreservesJpegBytesAndName() = runBlocking {
        val bytes = png(Bitmap.CompressFormat.JPEG)
        ToolScreenshotMediaPreview("browser", "image/jpeg", { true }) { ToolScreenshotOriginal(bytes, "image/jpeg") }.use { preview ->
            assertEquals("浏览器截图.jpg", preview.item.displayName)
            preview.item.openOriginal()!!.use { original ->
                assertEquals("image/jpeg", original.mimeType)
                assertArrayEquals(bytes, original.stream.readBytes())
            }
            val image = preview.item.loadImage(4096)!!.asAndroidBitmap()
            assertEquals(240, image.width); image.recycle()
        }
    }
    @Test fun cancellationRemainsCancellationInsteadOfAReadFailure() = runBlocking {
        val cancellation = CancellationException("cancelled")
        ToolScreenshotMediaPreview("key", "image/png", { true }) { throw cancellation }.use { preview ->
            try { preview.item.loadImage(4096); fail("must cancel") }
            catch (caught: CancellationException) { assertSame(cancellation, caught) }
        }
    }
}
