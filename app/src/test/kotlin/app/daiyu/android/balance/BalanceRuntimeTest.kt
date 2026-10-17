package app.daiyu.android.balance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BalanceRuntimeTest {
    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone(); writes++ }
    }
    private fun config(account: String = "old") = BalanceConfiguration(account, "https://example.test/balance", "independent", "/balance", "CNY")
    private fun amount(value: String) = BalanceResult.Available(BalanceMoney(value.toBigDecimal(), "CNY"), 1000)

    @Test fun constructionEditingSavingAndStateCollectionDoNotRequest() = runBlocking {
        val calls = AtomicInteger()
        val runtime = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery { calls.incrementAndGet(); amount("0") })
        runtime.state.first()
        runtime.save(config())
        runtime.store.configurationForEditing()
        assertEquals(0, calls.get())
        assertTrue(runtime.manualRefresh())
        assertEquals(1, calls.get())
        assertEquals("CNY 0", runtime.state.value.project().balanceText)
    }

    @Test fun repeatedInFlightRequestsCoalesceAndOwnerCancellationAllowsRestart() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val runtime = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            amount("12")
        })
        runtime.save(config())
        val owner = launch { runtime.manualRefresh() }
        withTimeout(2000) { entered.await() }
        assertFalse(runtime.manualRefresh())
        assertEquals(1, calls.get())
        owner.cancelAndJoin()
        assertFalse(runtime.state.value.refreshing)
        assertTrue(runtime.manualRefresh())
        assertEquals(2, calls.get())
        assertEquals("CNY 12", runtime.state.value.project().balanceText)
    }

    @Test fun independentAccountOwnersDoNotWaitForASlowAccount() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val slow = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery { entered.complete(Unit); never.await(); amount("1") })
        val fast = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery { amount("2") })
        slow.save(config("slow")); fast.save(config("fast"))
        val operation = launch { slow.manualRefresh() }
        try {
            withTimeout(2000) { entered.await() }
            withTimeout(2000) { assertTrue(fast.manualRefresh()) }
            assertEquals("CNY 2", fast.state.value.project().balanceText)
            assertTrue(slow.state.value.refreshing)
        } finally { operation.cancelAndJoin() }
    }

    @Test fun lateUncooperativeOldAccountResultCannotPublishOrWrite() = runBlocking {
        val disk = Memory()
        val entered = CompletableDeferred<Unit>()
        val oldReply = CompletableDeferred<Unit>()
        val runtime = BalanceRuntime(ProviderBalanceStore(disk), BalanceQuery { configuration ->
            if (configuration.accountId == "old") withContext(NonCancellable) {
                entered.complete(Unit)
                oldReply.await()
                amount("999999")
            } else amount("3")
        })
        runtime.save(config())
        val oldOperation = launch { runtime.manualRefresh() }
        withTimeout(2000) { entered.await() }
        runtime.save(config("new"))
        assertNull(runtime.state.value.project().balanceText)
        assertTrue(runtime.manualRefresh())
        val writes = disk.writes
        oldReply.complete(Unit)
        withTimeout(2000) { oldOperation.join() }
        assertEquals(writes, disk.writes)
        assertEquals("new", runtime.state.value.accountId)
        assertEquals("CNY 3", runtime.state.value.project().balanceText)
        assertNull(runtime.state.value.lastChange)
    }

    @Test fun pollerUsesThirtySecondScheduleIsIdempotentAndRestartsWithNewOwner() = runTest {
        val revisions = MutableStateFlow<String?>("a")
        val calls = AtomicInteger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val poller = ProviderBalancePoller({ calls.incrementAndGet() }, { revisions }, 30_000L, dispatcher)
        val first = CoroutineScope(Job() + dispatcher)
        try {
            poller.start(first)
            runCurrent()
            assertEquals(1, calls.get())
            poller.start(first)
            runCurrent()
            assertEquals(1, calls.get())
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(1, calls.get())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, calls.get())
            revisions.value = "changed-credentials"
            runCurrent()
            assertEquals(3, calls.get())
            revisions.value = null // disabled/deleted: no timer requests
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, calls.get())
            first.cancel()
            runCurrent()
            assertFalse(poller.isRunning)
            revisions.value = "b"
            val second = CoroutineScope(Job() + dispatcher)
            try {
                poller.start(second)
                runCurrent()
                assertEquals(4, calls.get())
                poller.stop()
                runCurrent()
                assertFalse(poller.isRunning)
            } finally { second.cancel() }
        } finally { first.cancel(); poller.stop() }
    }

    @Test fun runtimePollerCancellationReallyCancelsQueryAndIsRestartable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val never = CompletableDeferred<Unit>()
        val runtime = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); never.await() }
            amount("2")
        })
        runtime.save(config())
        val first = CoroutineScope(Job() + Dispatchers.Default)
        val second = CoroutineScope(Job() + Dispatchers.Default)
        try {
            runtime.start(first)
            withTimeout(2000) { entered.await() }
            first.coroutineContext[Job]!!.cancelAndJoin()
            assertFalse(runtime.state.value.refreshing)
            assertFalse(runtime.isPolling)
            runtime.start(second)
            withTimeout(2000) { runtime.state.first { it.project().balanceText == "CNY 2" } }
            assertEquals(2, calls.get())
        } finally {
            first.cancel(); second.coroutineContext[Job]!!.cancelAndJoin(); runtime.stop()
        }
    }
}
