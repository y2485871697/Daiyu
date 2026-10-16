package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PtyStartupOwnershipTest {
    private class HeldProcess(hello: Boolean = true) : Process() {
        val exited = CountDownLatch(1)
        var destroyCalls = 0
        var destroyFailure: IOException? = null
        private val input = ByteArrayInputStream(if (hello)
            byteArrayOf('H'.code.toByte(), 0, 0, 0, 5) + "DPTY1".toByteArray()
        else byteArrayOf('E'.code.toByte(), 0, 0, 0, 0))
        var outputCloseCalls = 0
        var outputCloseFailure: IOException? = null
        private val output = object : ByteArrayOutputStream() {
            override fun close() {
                outputCloseCalls++
                outputCloseFailure?.let { throw it }
                super.close()
            }
        }
        override fun getInputStream() = object : java.io.InputStream() {
            override fun read(): Int {
                val next = input.read()
                if (next >= 0) return next
                exited.await()
                return -1
            }
        }
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = exited.await(timeout, unit)
        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException()
            return 0
        }
        override fun isAlive() = exited.count != 0L
        override fun destroy() { destroyCalls++; destroyFailure?.let { throw it } }
        fun finish() { exited.countDown() }
    }

    private class Fixture(hello: Boolean = true) : AutoCloseable {
        val root = Files.createTempDirectory("pty-startup-owner-").toFile()
        val process = HeldProcess(hello)
        var starts = 0
        var configure: (OperationJournal) -> Unit = {}
        var failConstruction = false
        var interrupt = false
        private val helper = File(System.getProperty("java.home"),
            "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        fun open(): PtySession = PtySession.openBound(helper, listOf("/bin/sh"), emptyMap(), root,
            File(root, "journal"), "scope", 24, 80, null,
            startProcess = {
                starts++
                if (interrupt) Thread.currentThread().interrupt()
                process
            },
            createJournal = { directory, scope, binding -> hostJournal(directory, scope, binding = binding) },
            createSession = { process, journal ->
                configure(journal)
                if (failConstruction) throw IllegalStateException("construction failure")
                PtySession(process, journal)
            })
        fun failure(): PtyStartupFailure {
            try { open().close(); error("startup must fail") }
            catch (failure: PtyStartupFailure) { return failure }
        }
        override fun close() {
            Thread.interrupted()
            process.finish()
            root.deleteRecursively()
        }
    }

    @Test fun protocolFailureTransfersOriginalProcessUntilActualTermination() {
        Fixture(hello = false).use { fixture ->
            val failure = fixture.failure()
            assertTrue(failure.message!!.contains("unknown_not_replayed"))
            assertTrue(fixture.process.destroyCalls > 0)
            assertTrue(fixture.process.isAlive)
            assertFalse(failure.cleanup.awaitTermination(1))
            fixture.process.finish()
            assertTrue(failure.cleanup.awaitTermination(1000))
            assertEquals(1, fixture.starts)
        }
    }

    @Test fun journalCommitUnknownRetainsCleanupAndDoesNotReplayStartup() {
        Fixture().use { fixture ->
            fixture.configure = { journal -> journal.beforeSave = { throw IOException("commit failure") } }
            val failure = fixture.failure()
            assertEquals("journal_commit_unknown_not_replayed", failure.message)
            assertFalse(failure.cleanup.awaitTermination(1))
            try { fixture.open().close(); fail("unknown startup cannot be retried") }
            catch (retry: IllegalStateException) { assertFalse(retry is PtyStartupFailure) }
            assertEquals(1, fixture.starts)
            fixture.process.finish()
            assertTrue(failure.cleanup.awaitTermination(1000))
        }
    }

    @Test fun sessionConstructionFailureStillTransfersProcessOwnership() {
        Fixture().use { fixture ->
            fixture.failConstruction = true
            val failure = fixture.failure()
            assertEquals("construction failure", failure.cause!!.message)
            assertEquals(1, fixture.process.destroyCalls)
            assertTrue(fixture.process.outputCloseCalls > 0)
            assertFalse(failure.cleanup.awaitTermination(1))
            fixture.process.finish()
            assertTrue(failure.cleanup.awaitTermination(1000))
        }
    }

    @Test fun interruptionPreservesFlagAndFailedStartupCleanupOwnership() {
        Fixture().use { fixture ->
            fixture.interrupt = true
            val failure = fixture.failure()
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertTrue(failure.cause is InterruptedException)
            assertFalse(failure.cleanup.awaitTermination(1))
            fixture.process.finish()
            assertTrue(failure.cleanup.awaitTermination(1000))
        }
    }

    @Test fun failedDestroyDoesNotReplaceFailureOrLoseCleanupHandle() {
        Fixture().use { fixture ->
            fixture.failConstruction = true
            val close = IOException("destroy failure")
            fixture.process.destroyFailure = close
            val failure = fixture.failure()
            assertEquals("construction failure", failure.cause!!.message)
            assertSame(close, failure.suppressed.single())
            assertFalse(failure.cleanup.awaitTermination(1))
            fixture.process.destroyFailure = null
            failure.cleanup.requestClose()
            fixture.process.finish()
            assertTrue(failure.cleanup.awaitTermination(1000))
        }
    }

    @Test fun constructedSessionDestroyFailureCanRetryThroughCleanupHandle() {
        Fixture().use { fixture ->
            fixture.configure = { journal -> journal.beforeSave = { throw IOException("commit failure") } }
            val close = IOException("destroy failure")
            fixture.process.destroyFailure = close
            val failure = fixture.failure()
            assertEquals("journal_commit_unknown_not_replayed", failure.message)
            assertSame(close, failure.suppressed.single())
            assertFalse(failure.cleanup.awaitTermination(1))
            val before = fixture.process.destroyCalls
            fixture.process.destroyFailure = null
            failure.cleanup.requestClose()
            assertTrue(fixture.process.destroyCalls > before)
            fixture.process.finish()
            assertTrue(failure.cleanup.awaitTermination(1000))
        }
    }

    @Test fun successfulSessionCloseRetriesDestroyAndClosesInputOnFailure() {
        Fixture().use { fixture ->
            val session = fixture.open()
            val destroy = IOException("destroy failure")
            val stdin = IOException("stdin close failure")
            fixture.process.destroyFailure = destroy
            fixture.process.outputCloseFailure = stdin
            try { session.close(); fail("close must report cleanup failure") }
            catch (failure: IOException) {
                assertSame(destroy, failure)
                assertSame(stdin, failure.suppressed.single())
            }
            assertTrue(fixture.process.outputCloseCalls > 0)
            assertFalse(session.awaitTermination(1))
            val before = fixture.process.destroyCalls
            fixture.process.destroyFailure = null
            fixture.process.outputCloseFailure = null
            session.close()
            assertTrue(fixture.process.destroyCalls > before)
            fixture.process.finish()
            assertTrue(session.awaitTermination(1000))
        }
    }

    @Test fun runSessionCloseCanRetryWhileAllRunOperationsStayFenced() {
        Fixture().use { fixture ->
            val owner = PtyRunOwner(app.daiyu.android.domain.ConversationId("conversation"),
                app.daiyu.android.domain.tools.TaskAuthorization("session", "run"), "environment", "generation")
            val session = PtyRunSession(owner, fixture.open())
            val destroy = IOException("destroy failure")
            fixture.process.destroyFailure = destroy
            try { session.close(); fail("close must report cleanup failure") }
            catch (failure: IOException) { assertSame(destroy, failure) }
            assertTrue(fixture.process.outputCloseCalls > 0)
            assertEquals("pty_run_closed", session.read(owner.authorization).getString("error"))
            assertFalse(session.awaitTermination(1))
            val before = fixture.process.destroyCalls
            fixture.process.destroyFailure = null
            session.close()
            assertTrue(fixture.process.destroyCalls > before)
            fixture.process.finish()
            assertTrue(session.awaitTermination(1000))
        }
    }

    @Test fun reservationFailureDoesNotInventProcessCleanupOrStartHelper() {
        Fixture().use { fixture ->
            val journal = File(fixture.root, "journal").apply { mkdirs() }
            File(journal, "scope.json").writeText("malformed")
            try { fixture.open().close(); fail("invalid ledger must prevent launch") }
            catch (failure: IllegalStateException) { assertFalse(failure is PtyStartupFailure) }
            assertEquals(0, fixture.starts)
            assertEquals(0, fixture.process.destroyCalls)
        }
    }
}
