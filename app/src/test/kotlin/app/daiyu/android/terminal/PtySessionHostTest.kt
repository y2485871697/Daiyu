package app.daiyu.android.terminal

import junit.framework.TestCase
import java.io.File
import java.nio.file.Files

/** Explicit host-only integration test; requires the independently built helper path. */
class PtySessionHostTest : TestCase() {
    fun testQueuedInputCannotCrossUnknownDeliveryFence() = assertQueuedInputFence(false)
    fun testInterruptedWriterCannotRunQueuedInputBeforeUnknownFence() = assertQueuedInputFence(true)

    private fun assertQueuedInputFence(releaseOnInterrupt: Boolean) {
        val root = Files.createTempDirectory("daiyu-pty-fence").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val writerCancelled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val eof = java.util.concurrent.CountDownLatch(1)
        val written = java.io.ByteArrayOutputStream()
        val hello = java.io.ByteArrayInputStream(byteArrayOf('H'.code.toByte(), 0, 0, 0, 5) + "DPTY1".toByteArray())
        val fake = object : Process() {
            @Volatile var alive = true
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int {
                    val byte = hello.read()
                    if (byte >= 0) return byte
                    eof.await(); return -1
                }
            }
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(value: Int) {
                    entered.countDown()
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) {
                            writerCancelled.countDown()
                            if (releaseOnInterrupt) { release.countDown(); break }
                        }
                    }
                    synchronized(written) { written.write(value) }
                }
            }
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int { eof.await(); return 0 }
            override fun exitValue(): Int { if(alive) throw IllegalThreadStateException(); return 0 }
            override fun isAlive() = alive
            override fun destroy() { alive = false; release.countDown(); eof.countDown() }
        }
        val session = PtySession(fake, hostJournal(root, "fence"))
        val first = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>()
        val second = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>()
        val readyDeadline = System.nanoTime() + 1_000_000_000L
        while(session.read().getString("state") != "running" && System.nanoTime() < readyDeadline) Thread.sleep(5)
        val a = Thread { first.set(session.write("first", "AAA".toByteArray(), 1)) }.apply { start() }
        assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(300) // Keep B's own deadline separate from A's deliberate timeout.
        val b = Thread { second.set(session.write("second", "BBB".toByteArray(), 2)) }.apply { start() }
        try {
            assertTrue(writerCancelled.await(4, java.util.concurrent.TimeUnit.SECONDS))
            // Release at the cancellation fence, before A's journal commit consumes B's deadline.
            release.countDown()
            a.join(4000); assertFalse(a.isAlive)
            assertEquals("unknown", first.get().getString("execution"))
            b.join(2000); assertFalse(b.isAlive)
            assertEquals("not_executed", second.get().getString("execution"))
            assertEquals("pty_session_unavailable", second.get().getString("error"))
            val bytes = synchronized(written) { written.toByteArray() }
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("BBB"))
        } finally {
            release.countDown(); session.close(); a.join(1000); b.join(1000); root.deleteRecursively()
        }
    }

    fun testRealPtyPersistsShellAndRejectsStartupReplay() {
        val helperPath = checkNotNull(System.getProperty("daiyu.pty.helper")) { "host PTY helper required" }
        val root = Files.createTempDirectory("daiyu-pty-session").toFile()
        val helper = File(helperPath)
        val session = PtySession.open(helper, listOf("/bin/sh"), mapOf("PATH" to "/usr/bin:/bin", "PS1" to ""), File("/tmp"), root, "owned-session")
        fun awaitText(text: String): String {
            val deadline = System.nanoTime() + 3_000_000_000L
            var output = ""
            while (System.nanoTime() < deadline) {
                output = session.read(0, 65536).getString("stdout")
                if (text in output) return output
                Thread.sleep(10)
            }
            fail("Missing actual PTY result: $text in $output")
            return output
        }
        try {
            val changed = File(root, "different-cwd").apply { mkdirs() }.canonicalPath
            assertTrue(session.write("state", "export DAIYU_TEST=stable; cd '$changed'\n".toByteArray(), 1).getBoolean("helper_input_written"))
            session.write("verify", "printf '\\nREAL:%s:%s\\n' \"\$PWD\" \"\$DAIYU_TEST\"\n".toByteArray(), 2)
            awaitText("REAL:$changed:stable\r\n")
            session.resize("resize", 33, 91, 1)
            session.write("size", "stty size\n".toByteArray(), 3)
            awaitText("33 91\r\n")
            session.resize("back-original", 24, 80, 2)
            session.write("size-original", "stty size\n".toByteArray(), 4)
            awaitText("24 80\r\n")
            session.resize("again-new", 33, 91, 3)
            session.write("size-again", "stty size\n".toByteArray(), 5)
            val deadlineResize=System.nanoTime()+3_000_000_000L
            while(session.read(0,65536).getString("stdout").split("33 91\r\n").size<3 && System.nanoTime()<deadlineResize) Thread.sleep(10)
            assertTrue(session.read(0,65536).getString("stdout").split("33 91\r\n").size>=3)
            try {
                PtySession.open(helper, listOf("/bin/sh"), mapOf("PATH" to "/usr/bin:/bin", "PS1" to ""), File("/tmp"), root, "owned-session")
                fail("same scope must not open a second shell")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("expired"))
            }
            session.write("exit", "exit 9\n".toByteArray(), 6)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (session.read().optString("state") != "exited" && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(9 shl 8, session.read().getInt("wait_status"))
            session.close()
            try {
                PtySession.open(helper, listOf("/bin/sh"), mapOf("PATH" to "/usr/bin:/bin", "PS1" to ""), File("/tmp"), root, "owned-session")
                fail("closed session scope must remain expired")
            } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("expired")) }
        } finally { session.close(); root.deleteRecursively() }
    }
}
