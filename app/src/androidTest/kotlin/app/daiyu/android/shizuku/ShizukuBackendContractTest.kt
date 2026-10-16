package app.daiyu.android.shizuku

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.test.InstrumentationTestCase
import app.daiyu.android.terminal.OperationJournal
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Android Binder/Bundle/journal contracts; no installed Shizuku server or permission prompt. */
@Suppress("DEPRECATION")
class ShizukuBackendContractTest : InstrumentationTestCase() {
    fun testConnectRequiresExplicitUidWithoutKotlinDefault() {
        val type = ShizukuAuthorization::class.java
        assertEquals(ShizukuConnection::class.java,
            type.getDeclaredMethod("connect", Int::class.javaPrimitiveType!!).returnType)
        assertFalse(type.declaredMethods.any { it.name == "connect" && it.parameterTypes.isEmpty() })
        assertFalse(type.declaredMethods.any { it.name == "connect\$default" })
    }
    private open class Endpoint : IShizukuExecutor.Stub() {
        val executions = AtomicInteger()
        var malformed = false
        var crash = false
        var malformedClose = false
        val closeCalls = AtomicInteger()
        override fun attach(owner: IBinder, grant: String) = Bundle()
        override fun execute(service: String, grant: String, uid: Int, id: String, script: ByteArray, timeout: Long): Bundle {
            executions.incrementAndGet()
            if (crash) throw android.os.RemoteException("fixture disconnect after dispatch")
            return Bundle().apply { putString("status", "dispatched"); putString("request_id", id) }
        }
        override fun poll(service: String, grant: String, id: String, offset: Int, limit: Int) = Bundle().apply {
            putString("state", "completed"); if (!malformed) putInt("exit_code", 7)
            putByteArray("bytes", byteArrayOf()); putInt("offset", offset); putInt("next_offset", offset)
            putInt("retained_bytes", offset); putBoolean("truncated", false); putBoolean("output_complete", true)
        }
        override fun cancel(service: String, grant: String, id: String) = Bundle().apply { putBoolean("requested", true) }
        override fun closeExecution(service: String, grant: String) = Bundle().apply {
            closeCalls.incrementAndGet()
            if (!malformedClose) putBoolean("drained", true)
        }
        override fun destroy() {}
    }
    private fun fixture(permission: () -> Boolean = { true }, test: (BinderShizukuBackend, Endpoint) -> Unit) {
        val directory = File(instrumentation.targetContext.cacheDir, "shizuku-contract-${UUID.randomUUID()}")
        val remote = Endpoint(); val identity = ShizukuIdentity(2000, "service", "grant")
        val backend = BinderShizukuBackend(identity, remote, OperationJournal(directory, "scope"), { true }, permission, {})
        try { test(backend, remote) } finally { backend.close(); directory.deleteRecursively() }
    }
    fun testProviderHasOfficialCrossUserProtectionAndAppAuthority() {
        val context = instrumentation.targetContext
        val provider = context.packageManager.getProviderInfo(ComponentName(context, "rikka.shizuku.ShizukuProvider"), 0)
        assertEquals("${context.packageName}.shizuku", provider.authority)
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.readPermission)
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.writePermission)
        assertTrue(provider.exported); assertFalse(provider.multiprocess)
    }
    fun testDispatchIsDurablyAtMostOnceAndActualNonzeroExitRetained() = fixture { backend, remote ->
        val command = ShizukuCommand("one", "exit 7")
        val first = backend.execute(command) as ShizukuDispatch.Dispatched
        assertTrue(backend.execute(command) is ShizukuDispatch.Dispatched); assertEquals(1, remote.executions.get())
        assertEquals(ShizukuOutcome.Completed(7), backend.poll(first.handle).outcome)
        assertTrue(backend.execute(command.copy(script = "exit 0")) is ShizukuDispatch.NotExecuted)
        assertEquals(1, remote.executions.get())
    }
    fun testPermissionLossBeforeDispatchNeverCallsEndpointAndFencesScope() = fixture({ false }) { backend, remote ->
        assertEquals(ShizukuDispatch.NotExecuted("shizuku_permission_revoked"), backend.execute(ShizukuCommand("one", "x")))
        assertEquals(ShizukuDispatch.NotExecuted("shizuku_scope_revoked"), backend.execute(ShizukuCommand("two", "x")))
        assertEquals(0, remote.executions.get())
    }
    fun testLostDispatchReceiptStaysUnknownAndNoRetry() = fixture { backend, remote ->
        remote.crash = true
        assertTrue(backend.execute(ShizukuCommand("one", "x")) is ShizukuDispatch.Unknown)
        assertTrue(backend.execute(ShizukuCommand("one", "x")) is ShizukuDispatch.NotExecuted)
        assertEquals(1, remote.executions.get())
    }
    fun testMissingExitCodeDoesNotBecomeFalseSuccessAndForeignHandleRejected() = fixture { backend, remote ->
        val handle = (backend.execute(ShizukuCommand("one", "x")) as ShizukuDispatch.Dispatched).handle
        try { backend.poll(ShizukuCommandHandle("one", handle.identity.copy())); fail("foreign identity accepted") }
        catch (_: IllegalArgumentException) {}
        remote.malformed = true; assertTrue(backend.poll(handle).outcome is ShizukuOutcome.Unknown)
    }
    private fun await(engine: ShizukuCommandEngine, id: String): ShizukuCommandPage {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (true) {
            val page = engine.page(id, 0, 65536)
            if (page.outcome !is ShizukuOutcome.Running) return page
            check(System.nanoTime() < end); Thread.sleep(10)
        }
    }
    fun testRealAndroidShellOutputAndExitWithoutPretendingPrivilegedIdentity() {
        ShizukuCommandEngine().use { engine ->
            engine.submit("actual", "printf 'real-shell'; exit 9".toByteArray(), 5000)
            val page = await(engine, "actual")
            assertEquals(ShizukuOutcome.Completed(9), page.outcome); assertEquals("real-shell", String(page.bytes)); assertTrue(page.outputComplete)
        }
        assertTrue(android.os.Process.myUid() > 2000)
    }
    fun testRealShellTimeoutUnknownAndAppUidCannotAttachAsPrivilegedService() {
        ShizukuCommandEngine().use { engine ->
            engine.submit("timed", "exec /system/bin/sleep 20".toByteArray(), 100)
            assertTrue(await(engine, "timed").outcome is ShizukuOutcome.Unknown)
        }
        val service = ShizukuUserService()
        try { service.attach(Binder(), "grant"); fail("app UID accepted as Shizuku") }
        catch (_: IllegalStateException) {} finally { service.destroy() }
    }
    fun testRemoteDrainReceiptIsRequiredBeforeCleanupSuccess() = fixture { backend, remote ->
        val done = CountDownLatch(1)
        var result: ShizukuCloseResult? = null
        backend.closeWithResult { result = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(ShizukuCloseResult.Drained, result)
        assertEquals(1, remote.closeCalls.get())
        assertTrue(backend.execute(ShizukuCommand("after-close", "x")) is ShizukuDispatch.NotExecuted)
    }

    fun testMalformedCleanupReceiptStaysUnknown() = fixture { backend, remote ->
        remote.malformedClose = true
        val done = CountDownLatch(1)
        var result: ShizukuCloseResult? = null
        backend.closeWithResult { result = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result is ShizukuCloseResult.Unknown)
        assertEquals(1, remote.closeCalls.get())
    }

    fun testRevokedPermissionDoesNotIssueCleanupRpcOrClaimDrain() = fixture({ false }) { backend, remote ->
        val done = CountDownLatch(1)
        var result: ShizukuCloseResult? = null
        backend.closeWithResult { result = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result is ShizukuCloseResult.Unknown)
        assertEquals(0, remote.closeCalls.get())
    }

    fun testRemoteDrainDoesNotIgnoreLocalRpcStillInFlight() {
        val executing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requestedClose = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val directory = File(instrumentation.targetContext.cacheDir, "shizuku-close-${UUID.randomUUID()}")
        val endpoint = object : Endpoint() {
            override fun execute(service: String, grant: String, uid: Int, id: String, script: ByteArray, timeout: Long): Bundle {
                executing.countDown()
                while (release.count != 0L) {
                    try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* Binder may ignore interruption. */ }
                }
                return super.execute(service, grant, uid, id, script, timeout)
            }
            override fun closeExecution(service: String, grant: String): Bundle {
                requestedClose.countDown()
                return super.closeExecution(service, grant)
            }
        }
        val backend = BinderShizukuBackend(ShizukuIdentity(2000, "service", "grant"), endpoint,
            OperationJournal(directory, "scope"), { true }, { true }, {})
        var result: ShizukuCloseResult? = null
        val caller = Thread { backend.execute(ShizukuCommand("held", "x")) }.apply { isDaemon = true }
        try {
            caller.start()
            assertTrue(executing.await(5, TimeUnit.SECONDS))
            backend.closeWithResult { result = it; closed.countDown() }
            assertTrue(requestedClose.await(5, TimeUnit.SECONDS))
            assertEquals(1L, closed.count)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(ShizukuCloseResult.Drained, result)
            caller.join(5000)
            assertFalse(caller.isAlive)
        } finally { release.countDown(); backend.close(); caller.join(5000); directory.deleteRecursively() }
    }

    fun testRealAndroidShellCleanupDrainsOwnedProcessAndIo() {
        val engine = ShizukuCommandEngine()
        try {
            engine.submit("closing-shell", "printf ready; exec /system/bin/sleep 20".toByteArray(), 30000)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (engine.page("closing-shell", 0, 16).bytes.isEmpty()) {
                check(System.nanoTime() < deadline)
                Thread.sleep(5)
            }
            engine.close()
            val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!engine.isDrained()) {
                check(System.nanoTime() < closeDeadline)
                Thread.sleep(5)
            }
            assertTrue(engine.isDrained())
            assertTrue(engine.page("closing-shell", 0, 16).outcome is ShizukuOutcome.Unknown)
        } finally { engine.close() }
    }

}
