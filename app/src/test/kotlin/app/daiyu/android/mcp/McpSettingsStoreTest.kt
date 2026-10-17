package app.daiyu.android.mcp

import app.daiyu.android.testing.HostAtomicFileShadow
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [HostAtomicFileShadow::class])
class McpSettingsStoreTest {
    @Test fun privateEnvelopeIsEncryptedRandomizedAndDoesNotRestoreApprovalAcrossAbaOrCiphertextRollback() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        store.clear(owner)
        val blank = store.snapshot()
        val server = McpServer("test", "synthetic fixture", "https://private.invalid/mcp", "synthetic-secret")
        val first = store.save(listOf(server), owner, blank)
        val file = File(context.noBackupFilesDir, "mcp-private-v1.bin")
        val ciphertext = file.readBytes()
        val raw = ciphertext.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(server.bearer)); assertFalse(raw.contains(server.endpoint)); assertFalse(raw.contains(server.label))
        assertFalse(first.toString().contains("synthetic")); assertFalse(server.toString().contains("synthetic"))
        val reopened = McpSettingsStore(context) { key }
        assertEquals(server.bearer, reopened.snapshot().servers.single().bearer)
        assertTrue(store.isCurrent(first))
        val second = reopened.save(listOf(server), owner, reopened.snapshot())
        assertFalse(ciphertext.contentEquals(file.readBytes()))
        assertFalse(store.isCurrent(first)); assertTrue(store.isCurrent(second))
        // Even restoring the older valid encrypted file cannot revive an in-memory approval.
        file.writeBytes(ciphertext)
        val restored = reopened.snapshot()
        assertEquals(first.revision, restored.revision)
        assertFalse(store.isCurrent(first)); assertFalse(store.isCurrent(second)); assertTrue(store.isCurrent(restored))
        assertThrows(IllegalStateException::class.java) { store.save(emptyList(), owner, first) }
        store.clear(owner)
    }
    @Test fun revokedPageAndCorruptConfigFailClosedAndClearIsExplicitRecoveryOnly() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        store.clear(owner)
        val saved = store.save(listOf(McpServer("test", "fixture", "https://private.invalid/mcp", "secret")), owner, store.snapshot())
        owner.revoke()
        assertThrows(IllegalStateException::class.java) { store.save(emptyList(), owner, saved) }
        assertEquals(1, store.snapshot().servers.size)
        File(context.noBackupFilesDir, "mcp-private-v1.bin").writeText("corrupt synthetic fixture")
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
        assertFalse(store.isCurrent(saved))
        assertThrows(IllegalStateException::class.java) { store.clear(owner) }
        val fresh = McpSettingsOwner()
        assertTrue(store.clear(fresh).servers.isEmpty())
        assertTrue(store.snapshot().servers.isEmpty())
    }
    @Test fun urlCredentialsQueriesRedirectTargetsAndOversizedOrAmbiguousJsonAreNotAccepted() {
        for (url in listOf("http://host.invalid/mcp", "https://user:pass@host.invalid/mcp", "https://host.invalid/mcp?token=secret", "https://host.invalid/mcp#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { McpServer("test", "fixture", url, "") }
        }
        for (json in listOf("{\"x\":1,\"x\":2}", "{x:1}", "{\"x\":NaN}", "{\"x\":1} trailing", "{\"x\":\"\\ud800\"}", "{\"x\":01}")) {
            assertThrows(RuntimeException::class.java) { McpJson.objectValue(json) }
        }
        assertEquals("{\"a\":1,\"b\":2}", McpJson.canonical(McpJson.objectValue(" { \"b\" : 2, \"a\" : 1 } ")))
        assertThrows(IllegalArgumentException::class.java) { McpJson.objectValue("{\"x\":\"${"a".repeat(65536)}\"}") }
        val secret = "quote\"secret"
        val echoed = org.json.JSONObject().put("content", org.json.JSONArray().put(org.json.JSONObject().put("text", "echo=$secret")))
        assertFalse(McpJson.canonical(McpJson.redact(echoed, secret)).contains("secret"))
    }
}
