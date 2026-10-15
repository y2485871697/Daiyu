package app.daiyu.android.browser

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.cookie.BrowserCookieBackend
import app.daiyu.android.browser.cookie.CookieStore
import app.daiyu.android.browser.runtime.UiDispatcher
import app.daiyu.android.browser.session.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class BrowserCookieBackendTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Ui : UiDispatcher {
        val tasks = ArrayDeque<() -> Unit>()
        var active = false
        override fun isUiThread() = active
        override fun post(task: () -> Unit) { tasks.add(task) }
        fun drain() {
            while (tasks.isNotEmpty()) {
                active = true
                try { tasks.removeFirst()() } finally { active = false }
            }
        }
    }
    private class Store(val ui: Ui) : CookieStore {
        var raw = "sid=supersecret; other=hidden"
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        val callbacks = ArrayDeque<(Boolean) -> Unit>()
        override fun getCookie(url: String): String {
            check(ui.active); reads.add(url); return raw
        }
        override fun setCookie(url: String, header: String, callback: (Boolean) -> Unit) {
            check(ui.active); writes.add(url to header); callbacks.add(callback)
        }
        fun reply(accepted: Boolean) {
            ui.post { callbacks.removeFirst()(accepted) }
            ui.drain()
        }
    }
    private class Fixture(access: BrowserAccess = BrowserAccess.FULL) : AutoCloseable {
        val owner = BrowserOwner("cookie-main", BrowserOwnerKind.MAIN)
        val child = BrowserOwner("cookie-child", BrowserOwnerKind.CHILD)
        val sessions = BrowserSessionRegistry().apply {
            open(BrowserGrant(owner, access)); open(BrowserGrant(child, BrowserAccess.FULL))
        }
        val lease = sessions.newTab(owner)
        val childLease = sessions.newTab(child)
        val root: Path = Files.createTempDirectory("cookie-test")
        val ui = Ui()
        val io = Queue()
        val store = Store(ui)
        var page: String? = "https://example.com/private?not-returned"
        var allowed: (BrowserTabLease, String) -> Boolean = { _, _ -> true }
        var duringMapping: (() -> Unit)? = null
        val backend = BrowserCookieBackend(sessions, ui, store, { page }, root,
            { _, path -> duringMapping?.invoke(); path.toString() },
            { lease, _, name -> allowed(lease, name) }, io)
        fun call(action: String = "get_cookies", args: Map<String, Any?> = emptyMap(), tab: BrowserTabLease = lease) =
            backend.execute(BrowserCommand(action, tab.tabId, args), tab)
        fun pump() { ui.drain(); io.drain(); ui.drain(); io.drain() }
        fun countFiles(): Long = Files.list(root).use { it.count() }
        override fun close() {
            backend.close(); pump()
            Files.list(root).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(root)
        }
    }
    private fun failed(future: CompletableFuture<*>) {
        assertTrue("future must be terminal", future.isDone)
        assertTrue("future must not succeed", future.isCompletedExceptionally)
        try { future.join(); fail("expected failure") } catch (e: java.util.concurrent.CompletionException) {
            assertEquals("COOKIE_UNAVAILABLE", e.cause?.message)
            assertNull(e.cause?.cause)
        }
    }

    @Test fun executeOnlyPostsAndOffloadHasExactSecretBytesButSummaryHasNoValues() {
        Fixture().use { f ->
            f.store.raw = "sid=O'Brien"
            val result = f.call()
            assertTrue(f.store.reads.isEmpty()); assertEquals(0L, f.countFiles()); assertFalse(result.isDone)
            f.pump()
            val receipt = result.join()
            assertEquals(listOf("https://example.com/"), f.store.reads)
            assertTrue(receipt.text.contains("Names: sid"))
            assertFalse(receipt.text.contains("O'Brien")); assertFalse(receipt.text.contains("private?"))
            val file = java.nio.file.Paths.get(receipt.filePath!!)
            assertEquals("export BROWSER_COOKIE_0_NAME='sid'\nexport BROWSER_COOKIE_0_VALUE='O'\\''Brien'\n", Files.readString(file))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
            f.backend.cancel(f.lease); f.io.drain()
            assertEquals(0L, f.countFiles())
        }
    }
    @Test fun keywordsAreStrictAndMatchOnlyAuthorizedNames() {
        Fixture().use { f ->
            f.allowed = { _, name -> name != "other" }
            val exact = f.call(args = mapOf("keywords" to listOf("SID"), "fuzzy" to false))
            f.pump(); assertTrue(exact.join().text.contains("Names: sid"))
            val fuzzy = f.call(args = mapOf("keywords" to listOf("i"), "fuzzy" to true))
            f.pump(); assertTrue(fuzzy.join().text.contains("Names: sid"))
            val unmatched = f.call(args = mapOf("keywords" to listOf("i")))
            f.pump(); assertTrue(unmatched.join().text.contains("Cookies available: 0"))
            for (args in listOf(mapOf("keywords" to "sid"), mapOf("keywords" to listOf("sid", 3)),
                mapOf("keywords" to List(33) { "sid" }), mapOf("fuzzy" to "true"), mapOf("origin" to "https://evil.test"))) {
                val bad = f.call(args = args); f.pump(); failed(bad)
            }
        }
    }
    @Test fun tenantsAndWholeGenerationsAreFencedAndCancellationDoesNotTouchOtherOwner() {
        Fixture().use { f ->
            f.allowed = { lease, name -> lease.owner == f.owner && name == "sid" }
            val main = f.call(); val child = f.call(tab = f.childLease)
            f.pump()
            assertTrue(main.join().text.contains("Names: sid"))
            assertTrue(child.join().text.contains("Cookies available: 0"))
            f.backend.cancel(f.lease); f.io.drain()
            assertEquals(1L, f.countFiles())
            f.sessions.closeTab(f.lease)
            val replacement = f.sessions.newTab(f.owner)
            val fresh = f.call(tab = replacement)
            f.backend.cancel(f.lease)
            f.pump(); assertFalse(fresh.isCompletedExceptionally)
            val stale = f.call(tab = f.lease); f.pump(); failed(stale)
        }
    }
    @Test fun cancelBeforeStartAndAfterWriteFencesLatePublishAndLeavesNoFiles() {
        Fixture().use { f ->
            val before = f.call(); f.backend.cancel(f.lease); f.pump(); failed(before)
            assertTrue(f.store.reads.isEmpty())
            val after = f.call(); f.ui.drain(); f.io.drain()
            assertEquals(1L, f.countFiles()); assertFalse(after.isDone)
            f.backend.cancel(f.lease); f.pump(); failed(after)
            assertEquals(0L, f.countFiles())
        }
    }
    @Test fun invalidationDuringMappingAndNavigationBeforePublishDeleteOffload() {
        for (takeover in listOf(false, true)) Fixture().use { f ->
            f.duringMapping = {
                if (takeover) f.sessions.releaseTakeover(f.sessions.takeover(f.lease)) else f.page = "https://other.test/"
            }
            val result = f.call(); f.pump(); failed(result)
            assertEquals(0L, f.countFiles())
        }
    }
    @Test fun readOnlyAndClosedBackendNeverTouchCookieStore() {
        Fixture(BrowserAccess.READ_ONLY).use { f ->
            val result = f.call(); f.pump(); failed(result); assertTrue(f.store.reads.isEmpty())
        }
        Fixture().use { f ->
            f.backend.close(); val result = f.call(); failed(result)
            assertTrue(f.store.reads.isEmpty())
        }
    }
    @Test fun setIsHostOnlyStrictAndWaitsForTrueCallback() {
        Fixture().use { f ->
            val result = f.call("set_cookies", mapOf("cookies" to """[{"name":"sid","value":"secret","domain":"example.com","secure":true,"http_only":true,"sameSite":"Lax","expires":0}]"""))
            f.ui.drain(); assertFalse(result.isDone)
            assertEquals("https://example.com/", f.store.writes.single().first)
            assertEquals("sid=secret; Path=/; Secure; HttpOnly; SameSite=Lax; Expires=Thu, 01 Jan 1970 00:00:00 GMT", f.store.writes.single().second)
            f.store.reply(true); f.io.drain()
            assertEquals("Cookies updated: 1. Values withheld.", result.join().text)
            val rejected = f.call("set_cookies", mapOf("cookies" to """[{"name":"sid","value":"anothersecret"}]"""))
            f.ui.drain(); f.store.reply(false); f.io.drain(); failed(rejected)
            assertEquals(2, f.store.writes.size)
        }
    }
    @Test fun invalidSetNeverPartiallyStartsAndNoSecretAppearsInFailure() {
        Fixture().use { f ->
            val invalid = listOf(
                "[]", "[{\"name\":\"sid\",\"value\":\"secret\",\"domain\":\".example.com\"}]",
                """[{"name":"sid","value":"secret"},{"name":"x","value":"x; injected=y"}]""",
                """[{"name":"sid","value":"secret\r\nX-Evil: yes"}]""",
                """[{"name":"sid","value":"secret","http_only":true,"httpOnly":false}]""",
                """[{"name":"sid","name":"duplicate","value":"secret"}]""",
                """[{"name":"sid","value":"secret","expires":1.5}]""",
                """[{"name":"sid","value":"secret","expires":253402300800}]""",
                """[{"name":"sid","value":"secret","unknown":true}]"""
            )
            for (json in invalid) {
                val result = f.call("set_cookies", mapOf("cookies" to json)); f.pump(); failed(result)
            }
            val nonString = f.call("set_cookies", mapOf("cookies" to listOf(mapOf("name" to "sid", "value" to "secret"))))
            f.pump(); failed(nonString)
            assertTrue(f.store.writes.isEmpty())
        }
    }
    @Test fun cancellingReturnedFutureAndClosingDuringInflightWorkAreNonblocking() {
        Fixture().use { f ->
            val cancelled = f.call()
            assertTrue(cancelled.cancel(false))
            f.pump(); assertTrue(cancelled.isCancelled)
            assertEquals(0L, f.countFiles()); assertTrue(f.store.reads.isEmpty())
            val pending = f.call("set_cookies", mapOf("cookies" to """[{"name":"sid","value":"secret"}]"""))
            f.ui.drain()
            f.backend.close()
            // close neither invokes CookieManager nor awaits the outstanding callback.
            assertEquals(1, f.store.writes.size)
            f.pump(); failed(pending)
            f.store.reply(true); f.pump(); failed(pending)
            assertEquals(0L, f.countFiles())
        }
    }
    @Test fun lateSetCallbackAfterTakeoverCannotContinueBatchOrSucceed() {
        Fixture().use { f ->
            val result = f.call("set_cookies", mapOf("cookies" to """[{"name":"sid","value":"secret"},{"name":"other","value":"hidden"}]"""))
            f.ui.drain(); assertEquals(1, f.store.writes.size)
            f.sessions.releaseTakeover(f.sessions.takeover(f.lease)); f.io.drain(); failed(result)
            f.store.reply(true); f.pump(); failed(result)
            assertEquals(1, f.store.writes.size)
        }
    }
    @Test fun cookieReadRechecksInsideNativeFenceAfterUrlResolverInvalidates() {
        Fixture().use { f ->
            var invalidate = true
            val backend = BrowserCookieBackend(f.sessions, f.ui, f.store, {
                if (invalidate) {
                    invalidate = false
                    f.sessions.closeTab(f.lease)
                }
                f.page
            }, f.root, { _, path -> path.toString() }, { _, _, _ -> true }, f.io)
            try {
                val result = backend.execute(BrowserCommand("get_cookies", f.lease.tabId), f.lease)
                f.pump()
                failed(result)
                assertTrue("stale URL must not start CookieManager read", f.store.reads.isEmpty())
            } finally { backend.close(); f.pump() }
        }
    }

    @Test fun cookieNameAuthorizationReceivesOriginBeforeName() {
        Fixture().use { f ->
            val seen = mutableListOf<Pair<String, String>>()
            val backend = BrowserCookieBackend(f.sessions, f.ui, f.store, { f.page }, f.root,
                { _, path -> path.toString() }, { _, origin, name ->
                    seen.add(origin to name)
                    origin == "https://example.com/" && name == "sid"
                }, f.io)
            try {
                val result = backend.execute(BrowserCommand("get_cookies", f.lease.tabId), f.lease)
                f.pump()
                assertFalse(result.isCompletedExceptionally)
                assertEquals(listOf("https://example.com/" to "sid", "https://example.com/" to "other"), seen)
                assertTrue(result.join().text.contains("sid"))
                assertFalse(result.join().text.contains("other"))
            } finally { backend.close(); f.pump() }
        }
    }

}
