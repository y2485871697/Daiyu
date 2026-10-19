package app.daiyu.android.media

import android.test.AndroidTestCase
import android.graphics.Bitmap
import java.io.File
import java.util.UUID
import org.json.JSONObject

class BrowserScreenshotStoreTest : AndroidTestCase() {
    fun testFrozenOriginalSurvivesSourceChangeAndReopen() = verifyFrozen()
    fun testEveryOwnerAndInvocationFieldAndReceiptAreRequired() = verifyIsolation()
    fun testForeignPathSymlinkAndInvalidJpegAreRejected() = verifyInvalid()

    private fun fixture(body: (File, File, BrowserScreenshotStore.Binding, BrowserScreenshotStore) -> Unit) {
        val root = File(context.cacheDir, "browser-sidecar-test-${UUID.randomUUID()}")
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val source = File(cache, "browser_screenshots/screenshot_123.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            val binding = BrowserScreenshotStore.Binding("conversation", "message", "run", "call", "browser_read", "{\"action\":\"screenshot\"}")
            body(files, source, binding, BrowserScreenshotStore(files, cache))
        } finally { bitmap.recycle(); root.deleteRecursively() }
    }
    private fun verifyFrozen() = fixture { files, source, binding, store ->
        val bytes = source.readBytes(); val receipt = store.freeze(binding, source.path)
        source.writeText("not an image")
        val reopened = BrowserScreenshotStore(files, source.parentFile!!.parentFile!!)
        assertTrue(bytes.contentEquals(reopened.read(binding, receipt)))
        val thumbnail = reopened.thumbnail(binding, receipt)
        assertNotNull(thumbnail); assertEquals(3, thumbnail!!.width); thumbnail.recycle()
    }
    private fun verifyIsolation() = fixture { _, source, binding, store ->
        val receipt = store.freeze(binding, source.path)
        val alternatives = listOf(binding.copy(conversation="other"), binding.copy(message="other"),
            binding.copy(run="other"), binding.copy(call="other"), binding.copy(tool="browser_write"),
            binding.copy(arguments="{\"action\":\"screenshot\",\"tab_id\":1}"))
        for (wrong in alternatives) assertTrue(runCatching { store.read(wrong, receipt) }.isFailure)
        val forged = JSONObject(receipt.toString()).put("asset", UUID.randomUUID().toString())
        assertTrue(runCatching { store.read(binding, forged) }.isFailure)
        val wrongSize = JSONObject(receipt.toString()).put("width", 4)
        assertTrue(runCatching { store.read(binding, wrongSize) }.isFailure)
        val jpg = source.parentFile!!.parentFile!!.parentFile!!.walkTopDown().first { it.name.endsWith(".jpg") && it != source }
        jpg.writeBytes(byteArrayOf(1,2,3,4))
        assertTrue(runCatching { store.read(binding, receipt) }.isFailure)
    }
    private fun verifyInvalid() = fixture { _, source, binding, store ->
        val foreign = File(source.parentFile, "snapshot_124.jpg").apply { writeBytes(source.readBytes()) }
        assertTrue(runCatching { store.freeze(binding, foreign.path) }.isFailure)
        val linked = File(source.parentFile, "screenshot_125.jpg")
        java.nio.file.Files.createSymbolicLink(linked.toPath(), source.toPath())
        assertTrue(runCatching { store.freeze(binding, linked.path) }.isFailure)
        source.writeText("not jpeg")
        assertTrue(runCatching { store.freeze(binding, source.path) }.isFailure)
    }
}
