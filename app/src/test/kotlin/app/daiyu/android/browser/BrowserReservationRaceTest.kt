package app.daiyu.android.browser

import app.daiyu.android.browser.download.BrowserDownloadCoordinator
import app.daiyu.android.browser.download.BrowserPathMapper
import app.daiyu.android.browser.session.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BrowserReservationRaceTest {
    @Test fun cancellationDuringInitializationDoesNotLeakOrReturnTicket() {
        for (closeOwner in listOf(false, true)) {
            val root = Files.createTempDirectory("browser-reserve-race")
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val owner = BrowserOwner("race", BrowserOwnerKind.MAIN)
            val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, BrowserAccess.FULL)) }
            val lease = sessions.newTab(owner)
            val mapper = BrowserPathMapper(root)
            val downloads = BrowserDownloadCoordinator(sessions, mapper, 1024, 1) {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
                Files.createFile(it)
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val pending = worker.submit<Boolean> {
                    try { downloads.reserve(lease); true } catch (_: IllegalStateException) { false }
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (closeOwner) sessions.close(owner) else downloads.cancelLease(lease)
                resume.countDown()
                assertFalse(pending.get(3, TimeUnit.SECONDS))
                Files.list(root).use { paths -> assertEquals(0L, paths.count()) }
                if (!closeOwner) {
                    val next = downloads.reserve(lease)
                    downloads.cancel(next)
                    Files.list(root).use { paths -> assertEquals(0L, paths.count()) }
                }
            } finally {
                resume.countDown()
                worker.shutdownNow()
                Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
    @Test fun concurrentCancellationSerializesCleanupOfTheSameReservation() {
        val root = Files.createTempDirectory("browser-cleanup-race")
        val owner = BrowserOwner("cleanup-race", BrowserOwnerKind.MAIN)
        val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, BrowserAccess.FULL)) }
        val lease = sessions.newTab(owner)
        val downloads = BrowserDownloadCoordinator(sessions, BrowserPathMapper(root), 1024, 1)
        val ticket = downloads.reserve(lease)
        val entriesField = BrowserDownloadCoordinator::class.java.getDeclaredField("entries").apply { isAccessible = true }
        val entry = checkNotNull((entriesField.get(downloads) as Map<*, *>)[ticket.id])
        val workers = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(2)
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        try {
            val pending = synchronized(entry) {
                val tasks = listOf<() -> Unit>({ downloads.cancel(ticket) }, { sessions.close(owner) }).map { action ->
                    workers.submit {
                        threads.add(Thread.currentThread())
                        started.countDown()
                        action()
                    }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (threads.any { it.state != Thread.State.BLOCKED } && tasks.none { it.isDone } && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertTrue("both cleanup callers must wait for the reservation monitor", threads.all { it.state == Thread.State.BLOCKED })
                assertTrue(tasks.none { it.isDone })
                Files.list(root).use { assertEquals(1L, it.count()) }
                tasks
            }
            pending.forEach { it.get(3, TimeUnit.SECONDS) }
            Files.list(root).use { assertEquals(0L, it.count()) }
            val nextOwner = BrowserOwner("after-cleanup", BrowserOwnerKind.MAIN)
            sessions.open(BrowserGrant(nextOwner, BrowserAccess.FULL))
            downloads.cancel(downloads.reserve(sessions.newTab(nextOwner)))
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally {
            workers.shutdownNow()
            check(workers.awaitTermination(3, TimeUnit.SECONDS))
            root.toFile().deleteRecursively()
        }
    }

    @Test fun failedCleanupRetainsReservationUntilRetryDeletesTheRemainingFiles() {
        val root = Files.createTempDirectory("browser-cleanup-retry")
        val owner = BrowserOwner("cleanup-retry", BrowserOwnerKind.MAIN)
        val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, BrowserAccess.FULL)) }
        val lease = sessions.newTab(owner)
        val mapper = BrowserPathMapper(root)
        val downloads = BrowserDownloadCoordinator(sessions, mapper, 1024, 1)
        val ticket = downloads.reserve(lease)
        val staging = mapper.privateFile("${ticket.id}.part")
        try {
            Files.delete(staging)
            Files.createDirectory(staging)
            val child = Files.write(staging.resolve("child"), byteArrayOf(1))
            try { downloads.cancel(ticket); fail("nonempty staging cleanup succeeded") }
            catch (_: java.nio.file.DirectoryNotEmptyException) { }
            try { downloads.reserve(lease); fail("failed cleanup released the reservation slot") }
            catch (e: IllegalStateException) { assertEquals("BROWSER_DOWNLOAD_LIMIT", e.message) }
            Files.delete(child)
            downloads.retryCleanup(ticket)
            Files.list(root).use { assertEquals(0L, it.count()) }
            downloads.cancel(downloads.reserve(lease))
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally {
            sessions.close(owner)
            root.toFile().deleteRecursively()
        }
    }

}
