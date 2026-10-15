package app.daiyu.android.browser

import app.daiyu.android.browser.session.*
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.download.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BrowserHostContractTest {
    private val main = BrowserOwner("a", BrowserOwnerKind.MAIN)
    private val child = BrowserOwner("a", BrowserOwnerKind.CHILD)
    private fun registry(access: BrowserAccess = BrowserAccess.FULL) = BrowserSessionRegistry().apply { open(BrowserGrant(main, access)) }
    private fun rejects(block: () -> Unit) { try { block(); fail("expected rejection") } catch (e: IllegalStateException) {} catch (e: IllegalArgumentException) {} }

    @Test fun grantsStayFrozenAcrossOpenAndClose() {
        val r = registry(BrowserAccess.READ_ONLY)
        r.open(BrowserGrant(main, BrowserAccess.READ_ONLY))
        rejects { r.open(BrowserGrant(main, BrowserAccess.FULL)) }
        r.close(main)
        rejects { r.open(BrowserGrant(main, BrowserAccess.READ_ONLY)) }
    }
    @Test fun disabledDoesNotCreateSession() { rejects { BrowserSessionRegistry().open(BrowserGrant(main, BrowserAccess.DISABLED)) } }
    @Test fun ownersEachHaveThreeTabsAndNoFourth() {
        val r = registry(); r.open(BrowserGrant(child, BrowserAccess.FULL))
        assertEquals(listOf(0,1,2), (1..3).map { r.newTab(main).tabId })
        assertEquals(listOf(0,1,2), (1..3).map { r.newTab(child).tabId })
        rejects { r.newTab(main) }
        r.close(main); r.assertCurrent(r.lease(child, 0))
    }
    @Test fun reusedSlotInvalidatesOldLease() {
        val r = registry(); val old = r.newTab(main); r.closeTab(old)
        val next = r.newTab(main); assertEquals(old.tabId, next.tabId)
        assertNotEquals(old.tabGeneration, next.tabGeneration); rejects { r.assertCurrent(old) }
    }
    @Test fun takeoverAndResumeInvalidateInflightWork() {
        val r = registry(); val lease = r.newTab(main); val token = r.takeover(lease)
        rejects { r.lease(main, 0) }; rejects { r.assertCurrent(lease) }
        val resumed = r.releaseTakeover(token); r.assertCurrent(resumed)
        rejects { r.assertCurrent(lease) }; rejects { r.releaseTakeover(token) }
    }
    @Test fun readOnlyBlocksAllMutationBeforePort() {
        val r = registry(BrowserAccess.READ_ONLY); r.newTab(main); var calls = 0
        val bridge = BrowserHostBridge(r, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult { calls++; return BrowserExecutionResult("ok") }
        })
        for (action in listOf("reload", "go_back", "get_cookies")) rejects { bridge.execute(main, BrowserCommand(action, 0)) }
        rejects { bridge.execute(main, BrowserCommand("execute_js", 0, mapOf("script" to "1"))) }
        rejects { bridge.execute(main, BrowserCommand("navigate", 0, mapOf("url" to "https://example.com"))) }
        assertEquals(0, calls)
        assertEquals("ok", bridge.execute(main, BrowserCommand("get_text", 0)).text)
    }
    @Test fun bridgeRejectsUnknownActionsArgumentsAndTypes() {
        val r = registry(); r.newTab(main)
        val bridge = BrowserHostBridge(r, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult = error("must not execute")
        })
        rejects { bridge.execute(main, BrowserCommand("evil", 0)) }
        rejects { bridge.execute(main, BrowserCommand("get_text", 0, mapOf("script" to "evil"))) }
        rejects { bridge.execute(main, BrowserCommand("get_text", 0, mapOf("offset" to "1"))) }
        rejects { bridge.execute(main, BrowserCommand("get_text", 3)) }
        rejects { bridge.execute(main, BrowserCommand("navigate", 0)) }
    }
    @Test fun lateResultAfterCloseOrTakeoverIsRejected() {
        for (takeover in listOf(false, true)) {
            val r = registry(); r.newTab(main)
            val bridge = BrowserHostBridge(r, object : BrowserExecutionPort {
                override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                    if (takeover) { val token = r.takeover(lease); r.releaseTakeover(token) } else r.close(main)
                    return BrowserExecutionResult("late")
                }
            })
            rejects { bridge.execute(main, BrowserCommand("get_text", 0)) }
        }
    }
    @Test fun closeInvalidatesBeforeSlowCleanupAndDoesNotHoldRegistryLock() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val r = BrowserSessionRegistry { entered.countDown(); check(unblock.await(3, TimeUnit.SECONDS)) }
        r.open(BrowserGrant(main, BrowserAccess.FULL)); val lease = r.newTab(main)
        val worker = thread { r.close(main) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try { rejects { r.assertCurrent(lease) }; r.open(BrowserGrant(child, BrowserAccess.FULL)); r.newTab(child) }
        finally { unblock.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive)
    }
    @Test fun delayedResourceAttachIsCleanedAndOldSlotCannotDestroyNewResource() {
        val r = registry(); val old = r.newTab(main); var oldCleaned = 0; var nextCleaned = 0
        r.closeTab(old); val next = r.newTab(main)
        r.registerCleanup(next) { nextCleaned++ }
        rejects { r.registerCleanup(old) { oldCleaned++ } }
        assertEquals(1, oldCleaned); assertEquals(0, nextCleaned)
        r.closeTab(next); assertEquals(1, nextCleaned)
    }
    @Test fun takeoverCleanupFailureStillReturnsRecoveryToken() {
        val r = registry(); val lease = r.newTab(main); var cleaned = 0
        r.onInvalidated(lease) { error("abort failed") }; r.onInvalidated(lease) { cleaned++ }
        val token = r.takeover(lease); assertNotNull(token.cleanupFailure); assertEquals(1, cleaned)
        r.assertCurrent(r.releaseTakeover(token)); rejects { r.assertCurrent(lease) }
    }
    @Test fun oneCleanupFailureDoesNotSkipOtherResources() {
        val r = registry(); val a = r.newTab(main); val b = r.newTab(main); var cleaned = 0
        r.registerCleanup(a) { error("cleanup failed") }; r.registerCleanup(b) { cleaned++ }
        rejects { r.close(main) }; assertEquals(1, cleaned)
    }
    @Test fun downloadWritesRealBytesAndHashAndClosesStream() {
        val root = Files.createTempDirectory("browser-test")
        try {
            val r = registry(); val lease = r.newTab(main); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root))
            var closed = false
            val input = object : ByteArrayInputStream("abc".toByteArray()) { override fun close() { closed = true; super.close() } }
            val result = d.write(d.reserve(lease), input)
            assertTrue(closed); assertEquals(3L, result.size); assertEquals("abc", String(Files.readAllBytes(result.path)))
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun emptyOversizeAndCancelledDownloadsHaveNoFiles() {
        val root = Files.createTempDirectory("browser-test")
        try {
            val r = registry(); val lease = r.newTab(main); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root), 2)
            rejects { d.write(d.reserve(lease), ByteArrayInputStream(byteArrayOf())) }
            rejects { d.write(d.reserve(lease), ByteArrayInputStream(byteArrayOf(1,2,3))) }
            val ticket = d.reserve(lease); d.cancel(ticket)
            rejects { d.write(ticket, ByteArrayInputStream(byteArrayOf(1))) }
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun downloadCannotFinishAfterOwnerCloseOrTakeover() {
        val root = Files.createTempDirectory("browser-test")
        try {
            for (takeover in listOf(false, true)) {
                val r = registry(); val lease = r.newTab(main); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root))
                val ticket = d.reserve(lease)
                if (takeover) { val t = r.takeover(lease); r.releaseTakeover(t) } else r.close(main)
                rejects { d.write(ticket, ByteArrayInputStream(byteArrayOf(1))) }
                Files.list(root).use { assertEquals(0L, it.count()) }
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun boundedSocketReadIsAbortedByCancelCloseAndTakeover() {
        for (action in listOf("cancel", "close", "takeover")) {
            val root = Files.createTempDirectory("browser-blocking")
            val server = ServerSocket(0); val socket = Socket("127.0.0.1", server.localPort); val peer = server.accept()
            try {
                socket.soTimeout = 2000
                val r = registry(); val lease = r.newTab(main); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root))
                val ticket = d.reserve(lease); val entered = CountDownLatch(1); var failure: Exception? = null
                val stream = object : InputStream() {
                    override fun read(): Int = socket.getInputStream().read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int { entered.countDown(); return socket.getInputStream().read(b, off, len) }
                    override fun close() { socket.close() }
                }
                val worker = thread { try { d.write(ticket, stream) } catch (e: Exception) { failure = e } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                when (action) { "cancel" -> d.cancel(ticket); "close" -> r.close(main); else -> r.takeover(lease) }
                worker.join(3000); assertFalse(worker.isAlive); assertNotNull(failure)
                Files.list(root).use { assertEquals(0L, it.count()) }
            } finally { socket.close(); peer.close(); server.close(); root.toFile().deleteRecursively() }
        }
    }
    @Test fun reservationCloseAndPendingLimitReleaseFilesAndSlots() {
        val root = Files.createTempDirectory("browser-quota")
        try {
            val r = registry(); val lease = r.newTab(main); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root), maxPending = 1)
            val ticket = d.reserve(lease); rejects { d.reserve(lease) }; d.cancel(ticket)
            d.reserve(lease); r.close(main)
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun streamCloseFailureDoesNotLeaveSuccessFile() {
        val root = Files.createTempDirectory("browser-close")
        try {
            val r = registry(); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root)); val ticket = d.reserve(r.newTab(main))
            val input = object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { throw IOException("close") } }
            try { d.write(ticket, input); fail("must fail") } catch (e: IOException) { assertEquals("close", e.message) }
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun oldLeaseCancellationDoesNotTouchReusedSlot() {
        val root = Files.createTempDirectory("browser-old")
        try {
            val r = registry(); val old = r.newTab(main); val d = BrowserDownloadCoordinator(r, BrowserPathMapper(root)); d.reserve(old)
            r.closeTab(old); val next = r.newTab(main); val ticket = d.reserve(next); d.cancelLease(old)
            assertEquals(1L, d.write(ticket, ByteArrayInputStream(byteArrayOf(7))).size)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun portFailuresAndStaleResultsAreUnknownAndNeverRetried() {
        val r = registry(); r.newTab(main); var calls = 0
        val bridge = BrowserHostBridge(r, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                calls++; r.close(main); return BrowserExecutionResult("late")
            }
        })
        assertTrue(bridge.executeSafely(main, BrowserCommand("get_text", 0)) is BrowserOutcome.Unknown)
        assertTrue(bridge.executeSafely(main, BrowserCommand("get_text", 0)) is BrowserOutcome.NotExecuted)
        assertEquals(1, calls)
    }
    @Test fun viewportAndLongArgumentsHaveExactValidation() {
        val r = registry(); r.newTab(main)
        val bridge = BrowserHostBridge(r, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult = BrowserExecutionResult("ok")
        })
        assertEquals("ok", bridge.execute(main, BrowserCommand("get_text", 0, mapOf("offset" to 1L))).text)
        rejects { bridge.execute(main, BrowserCommand("get_text", 0, mapOf("offset" to Long.MAX_VALUE))) }
        rejects { bridge.execute(main, BrowserCommand("set_viewport", 0)) }
        rejects { bridge.execute(main, BrowserCommand("set_viewport", 0, mapOf("viewport_width" to 800))) }
        rejects { bridge.execute(main, BrowserCommand("set_viewport", 0, mapOf("reset" to true,"viewport_width" to 800,"viewport_height" to 600))) }
        bridge.execute(main, BrowserCommand("set_viewport", 0, mapOf("reset" to true)))
        rejects { bridge.execute(main, BrowserCommand("navigate", 0, mapOf("url" to "file:///secret"))) }
        rejects { bridge.execute(main, BrowserCommand("fetch", 0, mapOf("url" to "javascript:alert(1)"))) }
    }
    @Test fun privateAndSharedMappingsRejectTraversalSymlinksAndUnconfiguredMounts() {
        val root = Files.createTempDirectory("browser-test"); val outside = Files.createTempDirectory("outside")
        try {
            val p = BrowserPathMapper(root, mapOf("granted" to outside))
            assertEquals(root.resolve("a"), p.privateFile("a"))
            assertEquals(outside.resolve("b"), p.mappedFile("/workspace/mounts/granted/b"))
            rejects { p.privateFile("../escape") }; rejects { p.privateFile("/absolute") }
            rejects { p.mappedFile("/workspace/mounts/other/b") }; rejects { p.mappedFile("/sdcard/a") }
            Files.createSymbolicLink(root.resolve("link"), outside)
            rejects { p.privateFile("link/a") }
        } finally { root.toFile().deleteRecursively(); outside.toFile().deleteRecursively() }
    }
}
