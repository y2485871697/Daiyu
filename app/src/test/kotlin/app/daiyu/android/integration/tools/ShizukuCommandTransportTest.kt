package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.shizuku.*
import app.daiyu.android.terminal.TerminalConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuCommandTransportTest {
    // Model the Android File target semantics even when this fake-backend test runs on Windows.
    private class AndroidFile(private val androidPath: String) : File(androidPath) {
        override fun getPath(): String = androidPath
        override fun isAbsolute(): Boolean = androidPath.startsWith('/')
    }
    private class Backend(uid: Int = 2000) : ShizukuBackend {
        override val identity = ShizukuIdentity(uid, "service", "permission")
        val commands = mutableListOf<ShizukuCommand>()
        var output = "真实 Shizuku\n".toByteArray()
        var exitCode = 0
        var dispatch: ShizukuDispatch? = null
        var page: ((Int, Int) -> ShizukuCommandPage)? = null
        var closeResult: ShizukuCloseResult = ShizukuCloseResult.Drained
        var closeCallback: ((ShizukuCloseResult) -> Unit)? = null
        var delayedClose = false
        var closes = 0
        var polls = 0
        override fun execute(command: ShizukuCommand): ShizukuDispatch {
            commands.add(command)
            return dispatch ?: ShizukuDispatch.Dispatched(ShizukuCommandHandle(command.requestId, identity))
        }
        override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int): ShizukuCommandPage {
            polls++
            return page?.invoke(offset, limit) ?: ShizukuCommandPage(ShizukuOutcome.Completed(exitCode),
                output.copyOfRange(offset, minOf(offset + limit, output.size)), offset,
                minOf(offset + limit, output.size), output.size, false, true)
        }
        override fun cancel(handle: ShizukuCommandHandle) = ShizukuOutcome.Unknown("cancelled")
        override fun close() { closes++ }
        override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) {
            close(); if (delayedClose) closeCallback = onResult else onResult(closeResult)
        }
    }
    private val config = TerminalConfiguration(backend = "shizuku", workingDirectory = AndroidFile("/work 'quoted'"),
        environment = mapOf("TEST" to "a'b\nc", "PATH" to "/system/bin"))
    private class Fixture(val configuration: TerminalConfiguration, val uid: Int? = 2000, val backend: Backend = Backend()) {
        val owner = runnerOwner(Message(MessageId("m"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.RUNNING))
        val task = TaskAuthorization("s", "r")
        var connects = 0
        var terminals = 0
        var permit = true
        var connection: () -> ShizukuBackend? = { backend }
        val effects = mutableListOf<WriteEffect>()
        val leaf = RunCommandTools(configuration, owner, task, null,
            approve = { _, effect, _ -> effects.add(effect); permit },
            create = { _, frozen -> RunCommandTools.transport(frozen, uid, { connects++; connection() }) {
                terminals++; error("must not construct app/root terminal")
            } }, shizukuUid = uid)
        private val registry = ToolRegistry().also { r -> leaf.tools().forEach { r.register(it) } }
        private val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("s", setOf(RunCommandTools.CAPABILITY)),
            ToolLedger(InMemoryToolLedgerStore()))
        fun call(id: String = "call", command: String = "id -u") = dispatcher.dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId(id), "terminal_exec", JSONObject().put("command", command)
                .put("environment", "android").toString())), owner) { false }.single().outcome
        fun read(job: String) = dispatcher.dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId("read-$job"), "terminal_read", JSONObject().put("job_id", job).toString())), owner) { false }
            .single().outcome as DispatchOutcome.Confirmed
    }
    @Test fun invalidAndroidCwdIsRejectedWithoutConnecting() {
        var connects = 0
        for (path in listOf("relative", "/invalid\u0000cwd")) {
            assertThrows(IllegalArgumentException::class.java) {
                ShizukuCommandTransport(config.copy(workingDirectory = AndroidFile(path)), 2000) {
                    connects++; Backend()
                }
            }
        }
        assertEquals(0, connects)
    }
    @Test fun ordinaryCommandUsesRealShizukuAdapterAndOriginalLedgerForBothUids() {
        for (uid in listOf(0, 2000)) {
            val f = Fixture(config, uid, Backend(uid))
            val result = f.call() as DispatchOutcome.Confirmed
            val json = JSONObject(result.payload)
            assertEquals("shizuku", json.getString("backend")); assertEquals(uid, json.getInt("uid"))
            assertEquals("真实 Shizuku\n", json.getString("output"))
            assertEquals(1, f.connects); assertEquals(0, f.terminals); assertEquals(1, f.backend.commands.size)
            assertTrue(f.task.allowedWrites.isEmpty())
            assertEquals("terminal.command", f.effects.single().namespace)
            assertEquals("aWQgLXU=", f.effects.single().change)
            assertTrue((f.call("same-effect") as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.backend.commands.size)
            f.leaf.close()
        }
    }
    @Test fun deniedConsentDoesNotCreateAnyBackend() {
        val f = Fixture(config); f.permit = false
        assertTrue(f.call() is DispatchOutcome.NotExecuted)
        assertEquals(0, f.connects); assertEquals(0, f.terminals)
    }
    @Test fun connectionFailureAndMissingPermissionNeverUseAppOrRootShell() {
        for (uid in listOf<Int?>(null, 2000)) {
            val f = Fixture(config, uid); f.connection = { throw IllegalStateException("revoked") }
            assertTrue(f.call() is DispatchOutcome.NotExecuted)
            assertEquals(if (uid == null) 0 else 1, f.connects)
            assertEquals(0, f.terminals); assertTrue(f.backend.commands.isEmpty())
            assertTrue(f.call("again", "whoami") is DispatchOutcome.NotExecuted)
            assertEquals(if (uid == null) 0 else 1, f.connects)
            f.leaf.close()
        }
    }
    @Test fun wrongServiceUidClosesWithoutDispatchOrFallback() {
        val f = Fixture(config, 2000, Backend(0))
        assertTrue(f.call() is DispatchOutcome.NotExecuted)
        assertTrue(f.backend.commands.isEmpty()); assertEquals(1, f.backend.closes); assertEquals(0, f.terminals)
    }
    @Test fun scriptKeepsExactCwdEnvironmentAndUserCommand() {
        val f = Fixture(config); f.call(command = "printf '%s' \"\$TEST\"")
        assertEquals("export PATH='/system/bin'\nexport TEST='a'\\''b\nc'\ncd '/work '\\''quoted'\\''' || exit 125\nprintf '%s' \"\$TEST\"\n",
            f.backend.commands.single().script)
    }
    @Test fun grantSeparatesModesUidsCwdAndEnvironment() {
        fun effect(configuration: TerminalConfiguration, uid: Int? = null): WriteEffect {
            val f = Fixture(configuration, uid)
            f.permit = false; f.call(); return f.effects.single()
        }
        val effects = listOf(effect(config, 0), effect(config, 2000),
            effect(config.copy(backend = "android")), effect(config.copy(backend = "android", root = true)),
            effect(config.copy(workingDirectory = AndroidFile("/other")), 2000),
            effect(config.copy(environment = mapOf("TEST" to "different")), 2000))
        assertEquals(effects.size, effects.toSet().size)
    }
    @Test fun unknownDispatchReturnsOwnedJobAndDiagnosticsWithoutReconnectOrResend() {
        val f = Fixture(config); f.backend.dispatch = ShizukuDispatch.Unknown("unknown", "receipt_lost")
        val result = f.call() as DispatchOutcome.Unknown
        val job = result.code.substringAfter("job_id=")
        assertTrue(job.matches(Regex("[a-f0-9-]{36}")))
        assertTrue(f.call("different", "touch /must-not-run") is DispatchOutcome.NotExecuted)
        val read = JSONObject(f.read(job).payload)
        assertTrue(read.getBoolean("ok")); assertEquals("unknown", read.getString("execution"))
        assertEquals("receipt_lost", read.getString("reason")); assertEquals(2000, read.getInt("uid"))
        assertEquals(1, f.backend.commands.size); assertEquals(1, f.connects); assertEquals(0, f.backend.polls)
    }
    @Test fun utf8AcrossPagesIsDecodedAfterAssemblyAndNonzeroExitIsAnError() {
        val f = Fixture(config); val text = "中🙂".repeat(4000)
        f.backend.output = text.toByteArray(); f.backend.exitCode = 17
        val result = f.call() as DispatchOutcome.Confirmed
        assertEquals(text, JSONObject(result.payload).getString("output"))
        assertEquals(17, JSONObject(result.payload).getInt("exit_code"))
        assertTrue(result.isError); assertTrue(f.backend.polls > 1)
    }
    @Test fun invalidIdentityPageAndIncompleteOutputAreUnknownAndNeverResent() {
        for (failure in 0..2) {
            val f = Fixture(config)
            when (failure) {
                0 -> f.backend.dispatch = ShizukuDispatch.Dispatched(ShizukuCommandHandle("foreign", f.backend.identity))
                1 -> f.backend.page = { _, _ -> ShizukuCommandPage(ShizukuOutcome.Completed(0), byteArrayOf(1), 4, 5, 5, false, true) }
                2 -> f.backend.page = { offset, _ -> ShizukuCommandPage(ShizukuOutcome.Completed(0), byteArrayOf(), offset, offset, offset, false, false) }
            }
            assertTrue(f.call() is DispatchOutcome.Unknown)
            assertTrue(f.call("again", "another") is DispatchOutcome.NotExecuted)
            assertEquals(1, f.backend.commands.size)
        }
    }
    @Test fun partialOutputRemainsReadableAfterUnknownAndClose() {
        val real = Backend(); val output = ByteArray(16384) { 'x'.code.toByte() }
        real.page = { offset, _ -> if (offset == 0) ShizukuCommandPage(ShizukuOutcome.Running, output, 0, output.size, output.size, false, false)
            else ShizukuCommandPage(ShizukuOutcome.Unknown("permission_revoked"), byteArrayOf(), offset, offset, offset, false, false) }
        val transport = ShizukuCommandTransport(config, 2000) { real }
        val opened = transport.execute("terminal_open", JSONObject())
        val result = transport.execute("terminal_exec", JSONObject().put("session_id", opened.getString("session_id"))
            .put("command", "echo").put("request_id", "request").put("mode", "sync").put("timeout_ms", 1000))
        transport.close()
        val polls = real.polls
        val read = transport.read(result.getString("job_id"), 0, 65536)
        assertEquals(16384, read.getString("output").length); assertEquals("unknown", read.getString("execution"))
        assertFalse(read.getBoolean("output_complete")); assertEquals(polls, real.polls)
        assertFalse(transport.read("foreign", 0, 1).getBoolean("ok"))
    }
    @Test fun closeWaitsForLateConnectionAndRealDrainConfirmation() {
        val real = Backend(); real.delayedClose = true
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = ShizukuCommandTransport(config, 2000) { started.countDown(); release.await(); real }
        val worker = Thread { transport.execute("terminal_open", JSONObject()) }; worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS)); transport.close()
        assertFalse(transport.awaitTermination(5)); release.countDown(); worker.join(2000)
        assertFalse(worker.isAlive); assertEquals(1, real.closes); assertFalse(transport.awaitTermination(5))
        real.closeCallback!!(ShizukuCloseResult.Drained); assertTrue(transport.awaitTermination(2000))
    }
    @Test fun openingAfterCloseDoesNotAcquireANewBackend() {
        var connects = 0
        val transport = ShizukuCommandTransport(config, 2000) { connects++; Backend() }
        transport.close()
        assertTrue(transport.awaitTermination(10))
        assertFalse(transport.execute("terminal_open", JSONObject()).getBoolean("ok"))
        assertEquals(0, connects)
    }
    @Test fun unknownCleanupIsNotDrained() {
        val real = Backend(); real.closeResult = ShizukuCloseResult.Unknown("unconfirmed")
        val transport = ShizukuCommandTransport(config, 2000) { real }
        transport.execute("terminal_open", JSONObject()); transport.close()
        assertFalse(transport.awaitTermination(5))
    }
    @Test fun androidShizukuAsyncAndDaemonPlansAreRejected() {
        for (mode in listOf("async", "daemon")) {
            try {
                TerminalJobPlan(ConversationId("c"), RunId("r"), TerminalPtyLaunch.Android(listOf("/system/bin/sh"), config.environment,
                    checkNotNull(config.workingDirectory)), "id -u", mode, androidConfiguration = config)
                fail("Shizuku job accepted")
            } catch (failure: IllegalArgumentException) { assertEquals("android_shizuku_jobs_not_supported", failure.message) }
        }
    }
}
