package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException

class ImageBatchRunnerRetainedTest {
    @Test fun capsConcurrencyPreservesOrderAndNeverRetriesFailedJobs() {
        val active=AtomicInteger();val maximum=AtomicInteger();val calls=AtomicInteger();val start=CountDownLatch(2)
        val results=ImageBatchRunner.run(5,2,{}) { i ->
            calls.incrementAndGet();val now=active.incrementAndGet();maximum.accumulateAndGet(now,::maxOf)
            start.countDown();assertTrue(start.await(3,TimeUnit.SECONDS))
            try { if(i==1) error("failure"); Thread.sleep((5-i)*5L);i } finally { active.decrementAndGet() }
        }
        assertEquals(5,calls.get());assertEquals(2,maximum.get());assertTrue(results[1].isFailure)
        assertEquals(listOf(0,2,3,4),results.mapNotNull { it.getOrNull() })
    }
    @Test fun cancelledWorkDoesNotStartPendingCalls() {
        val cancelled=AtomicBoolean();val calls=AtomicInteger()
        assertThrows(CancellationException::class.java) {
            ImageBatchRunner.run(5,1,{ if(cancelled.get()) throw CancellationException("stop") }) {
                calls.incrementAndGet();cancelled.set(true);1
            }
        }
        assertEquals(1,calls.get())
    }
    @Test fun invalidSchedulingIsRejectedBeforeWork() {
        var calls=0
        for((n,c) in listOf(0 to 1,11 to 1,1 to 0,1 to 9)) {
            assertThrows(IllegalArgumentException::class.java) { ImageBatchRunner.run(n,c,{}) { calls++ } }
        }
        assertEquals(0,calls)
    }
}
