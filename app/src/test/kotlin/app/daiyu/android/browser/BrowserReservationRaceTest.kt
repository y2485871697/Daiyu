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
}
