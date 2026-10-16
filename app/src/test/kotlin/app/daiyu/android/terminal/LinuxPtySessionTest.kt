package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinuxPtySessionTest {
    private class HeldProcess(hello: Boolean = true, private val exitFrame: Boolean = false) : Process() {
        private val exited = CountDownLatch(1)
        var destroyCalls = 0
        var waits = 0
        var destroyFailure: IOException? = null
        var waitFailure: RuntimeException? = null
        var waitAction: ((Long, TimeUnit) -> Boolean)? = null
        private val frames = ByteArrayInputStream(
            (if (hello) byteArrayOf(72, 0, 0, 0, 5) + "DPTY1".toByteArray()
            else byteArrayOf(69, 0, 0, 0, 0)) +
                (if (exitFrame) byteArrayOf(88, 0, 0, 0, 4, 0, 0, 0, 0) else byteArrayOf()))
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = object : java.io.InputStream() {
            override fun read(): Int {
                val next = frames.read()
                if (next >= 0) return next
                if (!exitFrame) exited.await()
                return -1
            }
        }
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waits++
            waitFailure?.let { throw it }
            return waitAction?.invoke(timeout, unit) ?: exited.await(timeout, unit)
        }
        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException()
            return 0
        }
        override fun isAlive() = exited.count != 0L
        override fun destroy() { destroyCalls++; destroyFailure?.let { throw it } }
        fun finish() = exited.countDown()
    }

    private class Fixture(hello: Boolean = true, exitFrame: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("linux-pty-session-").toFile()
        private val rootfs = File(root, "rootfs").apply { mkdirs() }
        val proot = File(root, "fixture-launcher").apply {
            writeText("#!/bin/sh\nexit 0\n")
            check(setExecutable(true))
        }
        val loader = File(root, "fixture-loader").apply { writeText("test-only loader") }
        val mount = File(root, "shared").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        val cwd = File(root, "cwd").apply { mkdirs() }
        private val selection = File(root, "selection")
        val store: LinuxEnvironmentStore
        val other: LinuxEnvironmentStore
        val installation: LinuxInstallation
        val owner = PtyRunOwner(ConversationId("conversation"), TaskAuthorization("session", "run"), "env", "one")
        val expected = LinuxGenerationRef("env", "one")
        val process = HeldProcess(hello, exitFrame)
        val helper = File(System.getProperty("java.home"), "bin/java")
        var starts = 0
        var commitFailure = false
        var constructionFailure = false
        var startFailure: IOException? = null
        var configuration: TerminalConfiguration? = null
        var builder: ProcessBuilder? = null

        init {
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("test-only guest shell; not a real rootfs")
            installation = LinuxInstallation("fixture", "env", "one", rootfs, proot, hash(proot),
                loader, hash(loader), "/workspace", mapOf(mount.path to "/workspace/shared"))
            store = LinuxEnvironmentStore(selection).apply { select(installation) }
            other = LinuxEnvironmentStore(selection)
        }

        fun open(owner: PtyRunOwner = this.owner, expected: LinuxGenerationRef = this.expected): LinuxPtySession =
            LinuxPtySession.openConfigured(owner, store, expected, temp, cwd) { config ->
                configuration = config
                val backend = checkNotNull(config.linux)
                val session = PtySession.openBound(helper, backend.shellArgv,
                    backend.environment + config.environment, checkNotNull(config.workingDirectory),
                    File(root, "journal"), "scope", 31, 97, owner.binding(File(root, "journal")),
                    startProcess = { launched ->
                        starts++
                        builder = launched
                        startFailure?.let { throw it }
                        process
                    },
                    createJournal = { directory, scope, binding -> hostJournal(directory, scope, binding = binding) },
                    createSession = { launched, journal ->
                        if (constructionFailure) throw IOException("construction failed")
                        if (commitFailure) journal.beforeSave = { throw IOException("commit failed") }
                        PtySession(launched, journal)
                    })
                PtyRunSession(owner, session)
            }

        fun startupFailure(): LinuxPtyStartupFailure {
            try { open(); error("startup must fail") }
            catch (failure: LinuxPtyStartupFailure) { return failure }
        }

        fun assertPinned() {
            try { other.clearSelection(); fail("generation is still owned by helper") }
            catch (failure: IllegalStateException) { assertEquals("linux_environment_in_use", failure.message) }
            try { other.select(installation.copy(generation = "two")); fail("cannot replace a live generation") }
            catch (failure: IllegalStateException) { assertEquals("linux_environment_in_use", failure.message) }
        }

        fun assertReleased() {
            other.select(installation.copy(generation = "two"))
            assertEquals("two", store.status().getString("generation"))
        }

        override fun close() {
            process.finish()
            root.deleteRecursively()
        }
    }

    private fun fixture(hello: Boolean = true, exitFrame: Boolean = false, test: (Fixture) -> Unit) {
        assumeTrue("Linux store uses Unix directory fsync", System.getProperty("os.name").startsWith("Linux"))
        Fixture(hello, exitFrame).use(test)
    }

    // Latches establish ordering; these timeouts only bound a regression/deadlock, not scheduling.
    private class TestCall<T>(action: () -> T) {
        private val task = FutureTask<T> { action() }
        init { Thread(task, "linux-pty-test").apply { isDaemon = true; start() } }
        fun await(): T = try { task.get(5, TimeUnit.SECONDS) }
        catch (failure: ExecutionException) { throw checkNotNull(failure.cause) }
    }

    private fun awaitSignal(signal: CountDownLatch) {
        assertTrue("worker did not reach the expected boundary", signal.await(5, TimeUnit.SECONDS))
    }

    @Test fun sameLeaseBuildsLaunchAndOriginalAuthorizationDelegatesAllOperations() = fixture { f ->
        val session = f.open()
        try {
            val backend = checkNotNull(f.configuration!!.linux)
            assertEquals("env:one", backend.environmentId)
            assertEquals(f.installation.sharedMounts, backend.sharedMounts)
            assertEquals(listOf(f.helper.path, "31", "97", "--") + backend.shellArgv, f.builder!!.command())
            assertEquals(backend.environment, f.builder!!.environment())
            assertEquals(f.cwd, f.builder!!.directory())
            assertEquals(f.loader.path, backend.environment["PROOT_LOADER"])
            assertEquals(f.temp.canonicalPath, backend.environment["PROOT_TMP_DIR"])
            assertTrue(backend.shellArgv.contains("${f.mount.path}:/workspace/shared"))
            val copy = TaskAuthorization(f.owner.authorization.sessionId, f.owner.authorization.taskId)
            val denied = listOf(session.read(copy), session.write(copy, "denied-write", 1, byteArrayOf(1)),
                session.resize(copy, "denied-resize", 1, 20, 90), session.interrupt(copy, "denied-interrupt", 2))
            denied.forEach { assertEquals("pty_run_owner_mismatch", it.getString("error")) }
            assertTrue(session.read(f.owner.authorization).getBoolean("ok"))
            assertTrue(session.write(f.owner.authorization, "write", 1, byteArrayOf(1)).getBoolean("ok"))
            assertTrue(session.resize(f.owner.authorization, "resize", 1, 20, 90).getBoolean("ok"))
            assertTrue(session.interrupt(f.owner.authorization, "interrupt", 2).getBoolean("ok"))
            f.assertPinned()
        } finally {
            session.close()
            f.process.finish()
            assertTrue(session.awaitTermination(1000))
        }
    }

    @Test fun closeOnlyFencesAndTimeoutKeepsLeaseUntilOneConfirmedExit() = fixture { f ->
        val session = f.open()
        session.close()
        assertEquals("pty_run_closed", session.read(f.owner.authorization).getString("error"))
        assertEquals("pty_run_closed", session.write(f.owner.authorization, "late-w", 2, byteArrayOf()).getString("error"))
        assertEquals("pty_run_closed", session.resize(f.owner.authorization, "late-r", 3, 24, 80).getString("error"))
        assertEquals("pty_run_closed", session.interrupt(f.owner.authorization, "late-i", 4).getString("error"))
        assertFalse(session.awaitTermination(1))
        f.assertPinned()
        f.process.finish()
        f.assertPinned() // Even a completed Process needs explicit confirmation by the owner.
        assertTrue(session.awaitTermination(1000))
        val waits = f.process.waits
        f.store.acquire(f.expected).use {
            assertTrue(session.awaitTermination(1))
            session.close()
            assertEquals(waits, f.process.waits)
            f.assertPinned() // Repeated joins must not release another owner's lease.
        }
        f.assertReleased()
    }

    @Test fun exitFrameAndEofDoNotReleaseBeforeHelperProcessExit() = fixture(exitFrame = true) { f ->
        val session = f.open()
        assertFalse(session.awaitTermination(1))
        f.assertPinned()
        f.process.finish()
        assertTrue(session.awaitTermination(1000)) // Natural exit does not require close first.
        session.close()
        f.assertReleased()
    }

    @Test fun failedCloseAndFailedWaitRetainLeaseAndRemainRetryable() = fixture { f ->
        val session = f.open()
        val closeFailure = IOException("destroy failed")
        f.process.destroyFailure = closeFailure
        try { session.close(); fail("close failed") }
        catch (failure: IOException) { assertSame(closeFailure, failure) }
        assertEquals("pty_run_closed", session.read(f.owner.authorization).getString("error"))
        f.assertPinned()
        val waitFailure = IllegalStateException("wait unknown")
        f.process.waitFailure = waitFailure
        try { session.awaitTermination(1); fail("wait failed") }
        catch (failure: IllegalStateException) { assertSame(waitFailure, failure) }
        f.assertPinned()
        f.process.waitFailure = null
        assertFalse(session.awaitTermination(1))
        f.process.destroyFailure = null
        session.close()
        assertTrue(f.process.destroyCalls >= 2)
        f.process.finish()
        assertTrue(session.awaitTermination(1000))
        f.assertReleased()
    }

    @Test fun overlappingShortJoinReturnsBeforeLongWaitAndFalseCanBeRetried() = fixture { f ->
        val session = f.open()
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        f.process.waitAction = { timeout, unit ->
            assertEquals(30_000L, timeout)
            assertEquals(TimeUnit.MILLISECONDS, unit)
            entered.countDown()
            allowReturn.await()
            false
        }
        val first = TestCall { session.awaitTermination(30_000) }
        try {
            awaitSignal(entered)
            // Must finish while the first wait is still gated, not queue behind its budget.
            assertFalse(TestCall { session.awaitTermination(1) }.await())
            assertEquals(1, f.process.waits)
            f.assertPinned()
        } finally {
            allowReturn.countDown()
            assertFalse(first.await())
        }
        f.assertPinned()
        f.process.waitAction = null
        session.close()
        f.process.finish()
        assertTrue(session.awaitTermination(1000))
        assertEquals(2, f.process.waits)
        f.assertReleased()
    }

    @Test fun closeCanFenceDuringWaitButCannotReleaseBeforeConfirmedExit() = fixture { f ->
        val session = f.open()
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        f.process.waitAction = { _, _ ->
            entered.countDown()
            allowReturn.await()
            !f.process.isAlive()
        }
        val first = TestCall { session.awaitTermination(30_000) }
        try {
            awaitSignal(entered)
            TestCall { session.close() }.await()
            assertTrue(f.process.destroyCalls > 0)
            assertEquals("pty_run_closed", session.read(f.owner.authorization).getString("error"))
            f.assertPinned()
            f.process.finish()
            f.assertPinned() // Process exit alone, even after close, is not a completed join.
        } finally {
            f.process.finish()
            allowReturn.countDown()
            assertTrue(first.await())
        }
        assertTrue(session.awaitTermination(1))
        assertEquals(1, f.process.waits)
        f.assertReleased()
    }

    @Test fun underlyingWaitReentryDoesNotInvokeWaitOrReleaseAgain() = fixture { f ->
        val session = f.open()
        f.process.waitAction = { _, _ ->
            assertEquals("only the outer call may enter Process.waitFor", 1, f.process.waits)
            assertFalse(session.awaitTermination(1))
            assertEquals(1, f.process.waits)
            f.assertPinned()
            f.process.finish()
            true
        }
        assertTrue(session.awaitTermination(1000))
        f.store.acquire(f.expected).use {
            assertTrue(session.awaitTermination(1))
            session.close()
            assertEquals(1, f.process.waits)
            f.assertPinned()
        }
        f.assertReleased()
    }

    @Test fun throwingWaitAfterOverlapRetainsLeaseAndResetsSingleFlight() = fixture { f ->
        val session = f.open()
        val entered = CountDownLatch(1)
        val allowThrow = CountDownLatch(1)
        val waitFailure = IllegalStateException("wait failed after overlap")
        f.process.waitAction = { _, _ ->
            entered.countDown()
            allowThrow.await()
            throw waitFailure
        }
        val first = TestCall { runCatching { session.awaitTermination(30_000) } }
        try {
            awaitSignal(entered)
            assertFalse(TestCall { session.awaitTermination(1) }.await())
            assertEquals(1, f.process.waits)
            f.assertPinned()
        } finally {
            allowThrow.countDown()
            assertSame(waitFailure, first.await().exceptionOrNull())
        }
        f.assertPinned()
        f.process.waitAction = { _, _ -> false }
        assertFalse(session.awaitTermination(1))
        f.assertPinned()
        f.process.waitAction = null
        session.close()
        f.process.finish()
        assertTrue(session.awaitTermination(1000))
        assertEquals(3, f.process.waits)
        f.assertReleased()
    }

    @Test fun startupCleanupSharesSingleFlightAndCloseDoesNotReleaseDuringWait() = fixture(hello = false) { f ->
        val cleanup = f.startupFailure().cleanup
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        f.process.waitAction = { _, _ ->
            entered.countDown()
            allowReturn.await()
            !f.process.isAlive()
        }
        val first = TestCall { cleanup.awaitTermination(30_000) }
        try {
            awaitSignal(entered)
            assertFalse(TestCall { cleanup.awaitTermination(1) }.await())
            TestCall { cleanup.close() }.await()
            assertEquals(1, f.process.waits)
            f.assertPinned()
            f.process.finish()
            f.assertPinned()
        } finally {
            f.process.finish()
            allowReturn.countDown()
            assertTrue(first.await())
        }
        assertTrue(cleanup.awaitTermination(1))
        assertEquals(1, f.process.waits)
        f.assertReleased()
    }

    @Test fun leaseReleaseRunsOutsideStateLockAndHasOnlyOneOwner() = fixture { f ->
        val lease = f.store.acquire(f.expected)
        val entered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val waits = AtomicInteger()
        val releases = AtomicInteger()
        lateinit var termination: LinuxLeaseUntilExit
        termination = LinuxLeaseUntilExit(AutoCloseable {
            assertEquals(1, releases.incrementAndGet())
            assertFalse(termination.awaitTermination(1)) // Reentry during close is also single-flight.
            entered.countDown()
            allowRelease.await()
            lease.close()
        }) { waits.incrementAndGet(); true }
        val first = TestCall { termination.awaitTermination(30_000) }
        try {
            awaitSignal(entered)
            assertFalse(TestCall { termination.awaitTermination(1) }.await())
            assertEquals(1, waits.get())
            assertEquals(1, releases.get())
            f.assertPinned()
        } finally {
            allowRelease.countDown()
            try { assertTrue(first.await()) } finally { lease.close() }
        }
        f.store.acquire(f.expected).use {
            assertTrue(termination.awaitTermination(1))
            assertEquals(1, waits.get())
            assertEquals(1, releases.get())
            f.assertPinned()
        }
        f.assertReleased()
    }

    @Test fun throwingLeaseCloseNeverReportsReleasedAndCanBeRetried() = fixture { f ->
        val lease = f.store.acquire(f.expected)
        val closeFailure = IOException("lease close failed")
        var waits = 0
        var releases = 0
        val termination = LinuxLeaseUntilExit(AutoCloseable {
            releases++
            if (releases == 1) throw closeFailure
            lease.close()
        }) { waits++; true }
        try {
            try { termination.awaitTermination(1000); fail("release must report its failure") }
            catch (failure: IOException) { assertSame(closeFailure, failure) }
            assertEquals(1, waits)
            assertEquals(1, releases)
            f.assertPinned()
            assertTrue(termination.awaitTermination(1000))
            assertEquals(2, waits)
            assertEquals(2, releases)
            f.store.acquire(f.expected).use {
                assertTrue(termination.awaitTermination(1))
                assertEquals(2, releases)
                f.assertPinned()
            }
            f.assertReleased()
        } finally { lease.close() }
    }

    @Test fun protocolStartupFailureTransfersCleanupAndLeaseAndNeverReopens() = fixture(hello = false) { f ->
        val failure = f.startupFailure()
        assertTrue(failure.cause is PtyStartupFailure)
        assertFalse(failure.cleanup.awaitTermination(1))
        f.assertPinned()
        try { f.open(); fail("same scope may not relaunch") }
        catch (retry: IllegalStateException) { assertFalse(retry is LinuxPtyStartupFailure) }
        assertEquals(1, f.starts)
        f.assertPinned() // Retrying did not release the first failure's lease.
        failure.cleanup.requestClose()
        f.process.finish()
        assertTrue(failure.cleanup.awaitTermination(1000))
        f.store.acquire(f.expected).use {
            assertTrue(failure.cleanup.awaitTermination(1))
            failure.cleanup.close()
            f.assertPinned()
        }
        f.assertReleased()
    }

    @Test fun commitUnknownFailureKeepsGenerationAndOriginalCleanupFailure() = fixture { f ->
        f.commitFailure = true
        val closeFailure = IOException("destroy failed")
        f.process.destroyFailure = closeFailure
        val failure = f.startupFailure()
        assertEquals("journal_commit_unknown_not_replayed", failure.message)
        assertSame(closeFailure, failure.cause!!.suppressed.single())
        f.assertPinned()
        try { failure.cleanup.requestClose(); fail("cleanup close failed") }
        catch (close: IOException) { assertSame(closeFailure, close) }
        assertFalse(failure.cleanup.awaitTermination(1))
        f.assertPinned()
        f.process.destroyFailure = null
        failure.cleanup.requestClose()
        f.process.finish()
        assertTrue(failure.cleanup.awaitTermination(1000))
        f.assertReleased()
    }

    @Test fun constructionFailureStillReturnsOriginalProcessWithLease() = fixture { f ->
        f.constructionFailure = true
        val failure = f.startupFailure()
        assertEquals("construction failed", failure.cause!!.cause!!.message)
        assertFalse(failure.cleanup.awaitTermination(1))
        f.assertPinned()
        f.process.finish()
        assertTrue(failure.cleanup.awaitTermination(1000))
        f.assertReleased()
    }

    @Test fun cleanupWaitExceptionDoesNotReleaseGeneration() = fixture(hello = false) { f ->
        val failure = f.startupFailure()
        f.process.waitFailure = IllegalStateException("wait unknown")
        try { failure.cleanup.awaitTermination(1); fail("wait failed") }
        catch (expected: IllegalStateException) { assertEquals("wait unknown", expected.message) }
        f.assertPinned()
        f.process.waitFailure = null
        f.process.finish()
        assertTrue(failure.cleanup.awaitTermination(1000))
        f.assertReleased()
    }

    @Test fun staleSelectionAndMismatchedOwnerNeverLaunchOrLeak() = fixture { f ->
        try { f.open(expected = LinuxGenerationRef("env", "stale")); fail("stale generation") }
        catch (failure: IllegalStateException) { assertEquals("linux_generation_mismatch", failure.message) }
        for (owner in listOf(
            PtyRunOwner(f.owner.conversationId, f.owner.authorization, "other", "one"),
            PtyRunOwner(f.owner.conversationId, f.owner.authorization, "env", "other"))) {
            try { f.open(owner = owner); fail("owner generation mismatch") }
            catch (failure: IllegalStateException) { assertEquals("linux_pty_owner_generation_mismatch", failure.message) }
        }
        assertEquals(0, f.starts)
        f.assertReleased()
    }

    @Test fun failedStartWithoutProcessReleasesLeaseWithoutInventingCleanup() = fixture { f ->
        f.startFailure = IOException("process not started")
        try { f.open(); fail("launch must fail") }
        catch (failure: IllegalStateException) { assertFalse(failure is LinuxPtyStartupFailure) }
        assertEquals(1, f.starts)
        assertEquals(0, f.process.destroyCalls)
        f.assertReleased()
    }

    @Test fun invalidWaitBudgetNeverReleasesTheOwnedGeneration() = fixture { f ->
        val session = f.open()
        for (budget in listOf(0L, -1L, 30_001L)) {
            try { session.awaitTermination(budget); fail("unbounded or invalid wait") }
            catch (_: IllegalArgumentException) { }
        }
        f.assertPinned()
        session.close()
        f.process.finish()
        assertTrue(session.awaitTermination(1000))
        f.assertReleased()
    }

    @Test fun publicOpenReleasesOnDefinitePreProcessFailure() = fixture { f ->
        try {
            LinuxPtySession.open(f.owner, f.store, f.expected, File(f.root, "missing-helper"),
                f.temp, f.cwd, File(f.root, "journal"), "scope")
            fail("missing helper")
        } catch (failure: IllegalArgumentException) { assertEquals("pty_native_unavailable", failure.message) }
        f.assertReleased()
    }

    @Test fun configurationFailureAndJournalReservationFailureReleaseWithoutLaunch() = fixture { f ->
        assertTrue(f.temp.delete())
        try { f.open(); fail("missing temporary directory") }
        catch (_: IllegalArgumentException) { }
        assertTrue(f.temp.mkdir())
        val journal = File(f.root, "journal").apply { mkdirs() }
        File(journal, "scope.json").writeText("malformed")
        try { f.open(); fail("invalid journal") }
        catch (failure: IllegalStateException) { assertFalse(failure is LinuxPtyStartupFailure) }
        assertEquals(0, f.starts)
        f.assertReleased()
    }

    companion object {
        private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
}
