package app.daiyu.android

import app.daiyu.android.media.ImageAttachmentOwner
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ImageSendHandleTest {
    private fun handle() = ImageSendHandle(ImageAttachmentOwner("conversation", "draft"), 42)

    @Test fun cancellationBeforeAcceptancePreventsCommitAndKeepsExactRevision() {
        val handle = handle()
        assertTrue(handle.cancel())
        assertTrue(handle.isCancelled())
        assertFalse(handle.beginCommit())
        assertEquals(42L, handle.revision)
        assertEquals(ImageAttachmentOwner("conversation", "draft"), handle.owner)
    }

    @Test fun acceptanceGateWinsOverLateCancellationAndCannotBeEnteredTwice() {
        val handle = handle()
        assertTrue(handle.beginCommit())
        assertFalse(handle.cancel())
        assertFalse(handle.isCancelled())
        assertFalse(handle.beginCommit())
    }

    @Test fun cancellationReachesSummaryTransportOutsideTheHandleMonitor() {
        val observed = CountDownLatch(1)
        lateinit var handle: ImageSendHandle
        handle = ImageSendHandle(ImageAttachmentOwner("conversation", "draft"), 42) {
            val worker = Thread { handle.isCancelled(); observed.countDown() }
            worker.start()
            assertTrue(observed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            worker.join()
        }
        assertTrue(handle.cancel())
        assertFalse(handle.beginCommit())
    }

    @Test fun racingCancelAndCommitHaveExactlyOneWinner() {
        repeat(100) {
            val handle = handle()
            val start = CountDownLatch(1)
            val wins = AtomicInteger()
            val cancel = Thread { start.await(); if (handle.cancel()) wins.incrementAndGet() }
            val commit = Thread { start.await(); if (handle.beginCommit()) wins.incrementAndGet() }
            cancel.start(); commit.start(); start.countDown()
            cancel.join(); commit.join()
            assertEquals(1, wins.get())
        }
    }
}
