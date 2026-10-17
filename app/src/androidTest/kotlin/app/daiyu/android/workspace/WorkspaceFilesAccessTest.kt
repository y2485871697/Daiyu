package app.daiyu.android.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.test.InstrumentationTestCase
import app.daiyu.android.platform.PlatformSafGrants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class WorkspaceFilesAccessTest : InstrumentationTestCase() {
    private val tree = Uri.parse("content://workspace.test/tree/root")
    private fun result(uri: String = "content://workspace.test/tree/root/document/file") = JSONObject()
        .put("ok", true).put("data", JSONObject().put("items", JSONArray().put(JSONObject()
            .put("uri", uri).put("name", "shared.txt").put("mime_type", "text/plain"))).put("truncated", false))
    private fun picked(uri: Uri = tree) = Intent().setData(uri).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    private inner class Fixture : AutoCloseable {
        val context = instrumentation.targetContext
        val name = "workspace-access-${UUID.randomUUID()}"
        val reads = mutableListOf<Uri>()
        val queries = AtomicInteger()
        val registry = PlatformSafGrants(context.getSharedPreferences(name, Context.MODE_PRIVATE),
            { reads.toList() }, { if (it !in reads) reads += it }, { reads.remove(it); Unit })
        var query: () -> JSONObject = { result() }
        val access = WorkspaceFilesAccess(registry) { _, _ -> queries.incrementAndGet(); query() }
        fun accept() { assertTrue(access.accept(picked(), true)) }
        override fun close() { context.deleteSharedPreferences(name) }
    }
    fun testSystemGrantAloneCannotRegisterAnAmbientWorkspace() {
        Fixture().use { f ->
            f.reads += tree
            assertThrows(IllegalStateException::class.java) { f.access.files(tree) { false } }
            assertEquals(0, f.queries.get())
            assertTrue(f.access.grants().isEmpty())
        }
    }
    fun testExplicitRegistrationListsOnlyExactTreeAndSurvivesRegistryReopen() {
        Fixture().use { f ->
            f.accept()
            val reopened = PlatformSafGrants(f.context.getSharedPreferences(f.name, Context.MODE_PRIVATE),
                { f.reads.toList() }, { error("must not retake") }, { error("must not release") })
            assertEquals(listOf(tree), reopened.grants())
            val listing = f.access.files(tree) { false }
            assertEquals(1, listing.files.size); assertEquals("shared.txt", listing.files.single().name)
            assertFalse(listing.files.single().directory); assertFalse(listing.truncated)
            assertEquals(1, f.queries.get())
        }
    }
    fun testLostGrantRejectsBeforeReadingProvider() {
        Fixture().use { f ->
            f.accept(); f.reads.clear()
            assertThrows(IllegalStateException::class.java) { f.access.files(tree) { false } }
            assertEquals(0, f.queries.get())
        }
    }
    fun testGrantRevokedDuringProviderReadRejectsTheReturnedRows() {
        Fixture().use { f ->
            f.accept(); f.query = { f.reads.clear(); result() }
            assertThrows(IllegalStateException::class.java) { f.access.files(tree) { false } }
            assertEquals(1, f.queries.get())
        }
    }
    fun testCancellationBeforeAndAfterProviderReadCannotPublishRows() {
        Fixture().use { f ->
            f.accept()
            val cancelled = AtomicBoolean(true)
            assertThrows(InterruptedException::class.java) { f.access.files(tree, cancelled::get) }
            assertEquals(0, f.queries.get())
            cancelled.set(false); f.query = { cancelled.set(true); result() }
            assertThrows(InterruptedException::class.java) { f.access.files(tree, cancelled::get) }
            assertEquals(1, f.queries.get())
        }
    }
    fun testOtherTreeOrAuthorityCannotMasqueradeAsListedDocument() {
        Fixture().use { f ->
            f.accept()
            for (uri in listOf("content://workspace.test/tree/other/document/file", "content://other.test/tree/root/document/file")) {
                f.query = { result(uri) }
                assertThrows(IllegalStateException::class.java) { f.access.files(tree) { false } }
            }
        }
    }
    fun testExplicitRevocationDropsTheApplicationGrantWithoutReadingFiles() {
        Fixture().use { f ->
            f.accept(); f.access.revoke(tree)
            assertTrue(f.reads.isEmpty()); assertTrue(f.access.grants().isEmpty())
            assertEquals(0, f.queries.get())
        }
    }
    fun testRawPathOrMissingPersistableFlagDoesNotRegister() {
        Fixture().use { f ->
            assertFalse(f.access.accept(picked(Uri.parse("file:///data/private")), false))
            assertFalse(f.access.accept(Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), true))
            assertTrue(f.reads.isEmpty()); assertTrue(f.access.grants().isEmpty())
        }
    }
}
