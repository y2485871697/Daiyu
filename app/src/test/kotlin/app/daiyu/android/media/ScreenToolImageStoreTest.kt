package app.daiyu.android.media

import android.graphics.Bitmap
import app.daiyu.android.gui.GuiScreenshot
import app.daiyu.android.gui.GuiScreenToolFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30])
class ScreenToolImageStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun image(): GuiScreenshot {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return GuiScreenshot("capture", 4, 3, bytes, observationBound = false, visualPackage = "sample.app")
    }
    private fun write(store: ScreenToolImageStore, image: GuiScreenshot, conversation: String = "c", call: String = "call") =
        store.receipt(conversation, "r", call, image, GuiScreenToolFactory.screenshotJson(image))

    @Test fun onePrivatePngAndSmallReceiptAreIdempotentAndBoundToOwner() {
        val root = folder.newFolder(); val store = ScreenToolImageStore(root); val image = image()
        val receipt = write(store, image)
        assertFalse(receipt.contains("data_base64")); assertTrue(receipt.length < 1024)
        assertEquals(receipt, write(store, image)); assertEquals(1, root.walkTopDown().count { it.extension == "png" })
        assertArrayEquals(image.png, store.read("c", "call", receipt, "r"))
        assertNull(store.read("other", "call", receipt)); assertNull(store.read("c", "other", receipt))
        assertNull(store.read("c", "call", receipt, "other"))
        assertNotNull(NativeScreenImage.parse("screen", """{"operation":"screenshot"}""", receipt) { store.read("c", "call", it) })
    }
    @Test fun damagedFileAndTamperedReferenceCannotBecomeInput() {
        val root = folder.newFolder(); val store = ScreenToolImageStore(root); val receipt = write(store, image())
        val changed = JSONObject(receipt).apply { getJSONObject("image_asset").put("sha256", "0".repeat(64)) }.toString()
        assertNull(store.read("c", "call", changed))
        root.walkTopDown().single { it.extension == "png" }.writeBytes(ByteArray(40))
        assertNull(store.read("c", "call", receipt))
    }
    @Test fun theSameCallCannotPublishDifferentPixels() {
        val root = folder.newFolder(); val store = ScreenToolImageStore(root); val image = image(); write(store, image)
        val changed = image.copy(png = image.png.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertThrows(IllegalArgumentException::class.java) { write(store, changed) }
        assertEquals(1, root.walkTopDown().count { it.extension == "png" })
    }
    @Test fun deletionAndOrphanCleanupPreserveRetainedConversation() {
        val root = folder.newFolder(); val store = ScreenToolImageStore(root); val image = image()
        val first = write(store, image); val second = write(store, image, "keep")
        store.deleteOrphanConversations(setOf("keep"))
        assertNull(store.read("c", "call", first)); assertArrayEquals(image.png, store.read("keep", "call", second))
        store.deleteConversation("keep"); assertEquals(0, root.walkTopDown().count { it.extension == "png" })
    }
    @Test fun symlinkAtAssetFileIsRejectedWithoutReadingOutside() {
        val root = folder.newFolder(); val store = ScreenToolImageStore(root); val receipt = write(store, image())
        val asset = root.walkTopDown().single { it.extension == "png" }
        val outside = folder.newFile(); outside.writeBytes(asset.readBytes()); assertTrue(asset.delete())
        Files.createSymbolicLink(asset.toPath(), outside.toPath())
        assertNull(store.read("c", "call", receipt)); assertTrue(outside.exists())
    }
}
