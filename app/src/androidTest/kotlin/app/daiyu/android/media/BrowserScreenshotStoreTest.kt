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

    fun testDirectorySyncFailureNeverPublishesSuccessfulReceipt() = verifyDirectorySyncFailure()

    private fun verifyDirectorySyncFailure() = fixture { files, source, binding, store ->
        val receipt = store.freeze(binding, source.path)
        for (failAt in 1..4) {
            var syncs = 0
            val failing = BrowserScreenshotStore(files, source.parentFile!!.parentFile!!) {
                syncs++; if (syncs == failAt) throw java.io.IOException("injected directory sync failure")
            }
            assertTrue(runCatching { failing.freeze(binding, source.path) }.isFailure)
            assertEquals(failAt, syncs)
        }
        val synced = mutableListOf<File>()
        val retry = BrowserScreenshotStore(files, source.parentFile!!.parentFile!!) { synced.add(it) }
        val retried = retry.freeze(binding, source.path)
        assertEquals(4, synced.size)
        assertEquals(files, synced[0])
        assertEquals(File(files, "browser-call-screenshots"), synced[1])
        assertEquals(synced[2], synced[3].parentFile)
        assertTrue(source.readBytes().contentEquals(store.read(binding, retried)))
        assertTrue(source.readBytes().contentEquals(store.read(binding, receipt)))
    }

    fun testDeleteConversationOnlyRemovesItsOwnOriginals() = verifyDeleteConversation()

    private fun verifyDeleteConversation() = fixture { _, source, binding, store ->
        val receipt = store.freeze(binding, source.path)
        val other = binding.copy(conversation = "other")
        val otherReceipt = store.freeze(other, source.path)
        store.deleteConversation(binding.conversation)
        store.deleteConversation(binding.conversation)
        assertTrue(runCatching { store.read(binding, receipt) }.isFailure)
        assertTrue(source.readBytes().contentEquals(store.read(other, otherReceipt)))
    }

    fun testDeleteConversationRejectsSymlinkWithoutTouchingTarget() = verifyDeleteSymlink()

    private fun verifyDeleteSymlink() = fixture { files, source, binding, store ->
        val receipt = store.freeze(binding, source.path)
        val root = File(files, "browser-call-screenshots")
        val directory = checkNotNull(root.listFiles()).single()
        val outside = File(files, "outside")
        check(directory.renameTo(outside))
        java.nio.file.Files.createSymbolicLink(directory.toPath(), outside.toPath())
        assertTrue(runCatching { store.deleteConversation(binding.conversation) }.isFailure)
        check(directory.delete()); check(outside.renameTo(directory))
        assertTrue(source.readBytes().contentEquals(store.read(binding, receipt)))
    }

    fun testDeleteRetryStillRequiresDirectorySync() = verifyDeleteRetrySync()

    private fun verifyDeleteRetrySync() = fixture { files, source, binding, store ->
        store.freeze(binding, source.path)
        val root = File(files, "browser-call-screenshots")
        var syncs = 0
        val failing = BrowserScreenshotStore(files, source.parentFile!!.parentFile!!) { directory ->
            assertEquals(root, directory)
            syncs++
            error("injected sync failure")
        }
        repeat(2) { assertTrue(runCatching { failing.deleteConversation(binding.conversation) }.isFailure) }
        assertEquals(2, syncs)
        BrowserScreenshotStore(files, source.parentFile!!.parentFile!!) { directory ->
            assertEquals(root, directory)
            syncs++
        }.deleteConversation(binding.conversation)
        assertEquals(3, syncs)
    }

    fun testOrphanCleanupRecoversEarlierFailureAndKeepsRetainedConversations() = verifyOrphanCleanup()

    private fun verifyOrphanCleanup() = fixture { files, source, binding, store ->
        store.freeze(binding, source.path)
        val retained = binding.copy(conversation = "retained")
        val receipt = store.freeze(retained, source.path)
        val root = File(files, "browser-call-screenshots")
        val orphan = checkNotNull(root.listFiles()).single { entry ->
            entry.name == java.security.MessageDigest.getInstance("SHA-256")
                .digest(binding.conversation.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        val outside = File(files, "held-original")
        check(orphan.renameTo(outside))
        java.nio.file.Files.createSymbolicLink(orphan.toPath(), outside.toPath())
        assertTrue(runCatching { store.deleteConversation(binding.conversation) }.isFailure)
        check(orphan.delete()); check(outside.renameTo(orphan))
        store.deleteOrphanConversations(setOf(retained.conversation))
        assertTrue(source.readBytes().contentEquals(store.read(retained, receipt)))
        store.deleteOrphanConversations(emptySet())
        assertTrue(checkNotNull(File(files, "browser-call-screenshots").listFiles()).isEmpty())
    }

    private fun fixture(body: (File, File, BrowserScreenshotStore.Binding, BrowserScreenshotStore) -> Unit) {
        val root = File(context.cacheDir, "browser-sidecar-test-${UUID.randomUUID()}").canonicalFile
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
        val receipt = store.freeze(binding, source.path)
        assertTrue(source.readBytes().contentEquals(store.read(binding, receipt)))
        val foreign = File(source.parentFile, "snapshot_124.jpg").apply { writeBytes(source.readBytes()) }
        assertTrue(runCatching { store.freeze(binding, foreign.path) }.isFailure)
        val linked = File(source.parentFile, "screenshot_125.jpg")
        java.nio.file.Files.createSymbolicLink(linked.toPath(), source.toPath())
        assertTrue(runCatching { store.freeze(binding, linked.path) }.isFailure)
        source.writeText("not jpeg")
        assertTrue(runCatching { store.freeze(binding, source.path) }.isFailure)
    }
}
