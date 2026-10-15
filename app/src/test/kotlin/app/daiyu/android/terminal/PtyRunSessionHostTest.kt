package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PtyRunSessionHostTest {
    private fun helper(): File {
        val path = System.getProperty("daiyu.pty.helper")
        assumeTrue("host PTY helper required", path != null)
        return File(checkNotNull(path))
    }
    private fun owner(conversation: String = "conversation", run: String = "run", environment: String = "host-test",
                      generation: String = "one") =
        PtyRunOwner(ConversationId(conversation), TaskAuthorization("session", run), environment, generation)
    private fun open(root: File, owner: PtyRunOwner) = PtyRunSession.open(owner, helper(), listOf("/bin/sh"),
        mapOf("PATH" to "/usr/bin:/bin", "PS1" to ""), root, File(root, "journal"), "scope")

    @Test fun testDifferentOwnerRunAndGenerationCannotReuseDurableScope() {
        val root = Files.createTempDirectory("daiyu-pty-bound").toFile()
        val first = open(root, owner())
        try {
            for (other in listOf(owner(conversation = "other"), owner(run = "other"),
                owner(environment = "other"), owner(generation = "two"))) {
                try { open(root, other).close(); fail("scope must reject another trusted binding") }
                catch (expected: IllegalStateException) { assertEquals("operation_scope_binding_mismatch", expected.message) }
            }
        } finally { first.close(); assertTrue(first.awaitTermination(3000)); root.deleteRecursively() }
    }

    @Test fun testOnlyOriginalAuthorizationCanReadOrWriteAndCloseRevokesIt() {
        val root = Files.createTempDirectory("daiyu-pty-caller").toFile()
        val owner = owner()
        val session = open(root, owner)
        try {
            val copied = TaskAuthorization(owner.authorization.sessionId, owner.authorization.taskId)
            assertEquals("pty_run_owner_mismatch", session.read(copied).getString("error"))
            val denied = session.write(copied, "denied", 1, "touch should-not-exist\n".toByteArray())
            assertEquals("not_executed", denied.getString("execution"))
            assertEquals("pty_run_owner_mismatch", denied.getString("error"))
            assertTrue(session.read(owner.authorization).getBoolean("ok"))
            session.close()
            assertEquals("pty_run_closed", session.read(owner.authorization).getString("error"))
            assertEquals("pty_run_closed", session.interrupt(owner.authorization, "late", 1).getString("error"))
            assertTrue(session.awaitTermination(3000))
            assertFalse(File(root, "should-not-exist").exists())
        } finally { session.close(); assertTrue(session.awaitTermination(3000)); root.deleteRecursively() }
    }

    @Test fun testRetryKeepsTrustedEventIdentityAndConflictingPayloadIsRejected() {
        val root = Files.createTempDirectory("daiyu-pty-event").toFile()
        val owner = owner()
        val session = open(root, owner)
        try {
            val bytes = "printf 'event\\n' >> once.txt\n".toByteArray()
            assertTrue(session.write(owner.authorization, "first", 1, bytes).getBoolean("helper_input_written"))
            assertTrue(session.write(owner.authorization, "retry", 1, bytes).getBoolean("replayed_execution"))
            val conflict = session.write(owner.authorization, "conflict", 1, "touch conflict.txt\n".toByteArray())
            assertEquals("not_executed", conflict.getString("execution"))
            assertEquals("operation_event_identity_conflict", conflict.getString("error"))
            val file = File(root, "once.txt")
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("actual helper must execute the first input", file.exists())
            session.close(); assertTrue(session.awaitTermination(3000))
            assertEquals(listOf("event"), file.readLines())
            assertFalse(File(root, "conflict.txt").exists())
        } finally { session.close(); assertTrue(session.awaitTermination(3000)); root.deleteRecursively() }
    }

    @Test fun testCloseWinsAgainstAReadWaitingAtTheLifecycleBoundary() {
        val root = Files.createTempDirectory("daiyu-pty-close-read").toFile()
        val owner = owner()
        val session = open(root, owner)
        val boundary = PtyRunSession::class.java.getDeclaredField("lifecycle").apply { isAccessible = true }.get(session)
        val started = CountDownLatch(1)
        val result = AtomicReference<org.json.JSONObject>()
        var reader: Thread? = null
        try {
            synchronized(boundary) {
                reader = thread(name = "pty-read-close-test") {
                    started.countDown()
                    result.set(session.read(owner.authorization))
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (reader!!.state != Thread.State.BLOCKED && reader!!.isAlive && System.nanoTime() < deadline)
                    Thread.yield()
                assertEquals("read must share the close boundary", Thread.State.BLOCKED, reader!!.state)
                session.close()
                assertNull("a blocked read cannot publish retained output", result.get())
            }
            reader!!.join(1000)
            assertFalse(reader!!.isAlive)
            assertEquals("pty_run_closed", result.get().getString("error"))
        } finally {
            session.close()
            reader?.join(1000)
            assertTrue(session.awaitTermination(3000))
            root.deleteRecursively()
        }
    }

    @Test fun testLegacyUnboundScopeCannotBeClaimedByNewRun() {
        val root = Files.createTempDirectory("daiyu-pty-legacy").toFile()
        val legacy = PtySession.open(helper(), listOf("/bin/sh"), mapOf("PATH" to "/usr/bin:/bin", "PS1" to ""),
            root, File(root, "journal"), "scope")
        legacy.close(); assertTrue(legacy.awaitTermination(3000))
        try {
            try { open(root, owner()).close(); fail("legacy scope is not new run authority") }
            catch (expected: IllegalStateException) { assertEquals("operation_scope_binding_mismatch", expected.message) }
        } finally { root.deleteRecursively() }
    }
}
