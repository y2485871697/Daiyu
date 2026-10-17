package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ModelSettingsSaveOwnerTest {
    @Test fun revokedOwnerRejectsQueuedWork() {
        val owner = ModelSettingsSaveOwner()
        val calls = AtomicInteger()
        owner.revoke()
        assertThrows(IllegalStateException::class.java) { owner.commit { calls.incrementAndGet() } }
        assertEquals(0, calls.get())
    }

    @Test fun admittedWorkFinishesExactlyOnceAfterRevocation() {
        val owner = ModelSettingsSaveOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                owner.commit {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    calls.incrementAndGet()
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.revoke()
        } finally {
            release.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertEquals(1, calls.get())
        assertThrows(IllegalStateException::class.java) { owner.commit { calls.incrementAndGet() } }
        assertEquals(1, calls.get())
    }

    @Test fun persistenceFailureIsNotRetriedOrReplaced() {
        val owner = ModelSettingsSaveOwner()
        val expected = IllegalStateException("persistence failed")
        val calls = AtomicInteger()
        val actual = assertThrows(IllegalStateException::class.java) {
            owner.commit { calls.incrementAndGet(); throw expected }
        }
        assertSame(expected, actual)
        assertEquals(1, calls.get())
    }
}
