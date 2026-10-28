package app.daiyu.android.balance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderBalanceCoordinatorTest {
    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }
    private fun config(id: String) = BalanceConfiguration(id, "https://balance.example.test", "same-key", "", "CNY",
        BalanceOption(enabled = true, apiPath = "/balance", resultPath = "balance"))
    private fun amount(value: String, time: Long = 1000) =
        BalanceResult.Available(BalanceMoney(value.toBigDecimal(), "CNY"), time)

    private inner class Fixture(private val query: suspend (String, BalanceConfiguration, Int) -> BalanceResult = { _, _, _ -> amount("8.22") }) : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(Job() + dispatcher)
        val ids = MutableStateFlow(listOf("primary", "secondary"))
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val runtimes = ConcurrentHashMap<String, BalanceRuntime>()
        fun runtime(id: String) = runtimes.computeIfAbsent(id) {
            BalanceRuntime(ProviderBalanceStore(Memory()).also { it.save(config(id)) }, BalanceQuery { configuration ->
                query(id, configuration, calls.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet())
            })
        }
        val coordinator = ProviderBalanceCoordinator({ ids }, { ids.value }, { runtime(it) }, dispatcher = dispatcher)
        fun count(id: String) = calls[id]?.get() ?: 0
        fun settle(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                scheduler.runCurrent()
                if (condition()) return
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
            assertTrue("balance work did not settle", condition())
        }
        fun idle() = settle { runtimes.values.all { !it.state.value.refreshing } }
        fun start() { coordinator.start(scope); settle { ids.value.all { count(it) > 0 } }; idle() }
        override fun close() { coordinator.close(); scope.cancel(); scheduler.runCurrent() }
    }

    @Test fun allProvidersQueryIndependentlyEvenWhenCredentialsAreShared() {
        Fixture { id, _, _ -> amount(if (id == "primary") "8.09" else "8.22") }.use { f ->
            f.start()
            assertEquals(1, f.count("primary")); assertEquals(1, f.count("secondary"))
            assertEquals("CNY 8.09", f.runtime("primary").state.value.project().balanceText)
            assertEquals("CNY 8.22", f.runtime("secondary").state.value.project().balanceText)
        }
    }

    @Test fun startsOncePollsEveryThirtySecondsAndStopsWithOwner() {
        Fixture().use { f ->
            f.start(); f.coordinator.start(f.scope); f.scheduler.runCurrent()
            assertEquals(1, f.count("primary"))
            f.scheduler.advanceTimeBy(29_999); f.scheduler.runCurrent()
            assertEquals(1, f.count("primary"))
            f.scheduler.advanceTimeBy(1); f.settle { f.count("secondary") == 2 }; f.idle()
            assertEquals(2, f.count("primary"))
            f.scope.cancel(); f.scheduler.runCurrent()
            assertFalse(f.coordinator.isRunning)
            f.scheduler.advanceTimeBy(60_000); f.scheduler.runCurrent()
            assertEquals(2, f.count("primary"))
        }
    }

    @Test fun disabledBalanceAndUnconfiguredProvidersDoNotOpenRequests() {
        Fixture().use { f ->
            f.runtime("primary").store.save(config("primary").copy(option = config("primary").option!!.copy(enabled = false)))
            f.runtime("secondary").store.clear()
            f.coordinator.start(f.scope); f.scheduler.runCurrent()
            f.coordinator.requestRefresh(f.scope); f.scheduler.runCurrent()
            f.scheduler.advanceTimeBy(60_000); f.scheduler.runCurrent()
            assertEquals(0, f.count("primary")); assertEquals(0, f.count("secondary"))
        }
    }

    @Test fun addingProviderOrSavingBalanceConfigurationRefreshesWithoutSelection() {
        Fixture().use { f ->
            f.start()
            f.ids.value = f.ids.value + "third"
            f.settle { f.count("third") == 1 }; f.idle()
            val primaryBefore = f.count("primary")
            f.runtime("third").store.save(config("third").copy(apiKey = "changed-key"))
            f.settle { f.count("third") == 2 }; f.idle()
            assertTrue(f.count("primary") > primaryBefore)
            assertEquals("CNY 8.22", f.runtime("third").state.value.project().balanceText)
        }
    }

    @Test fun manualRefreshRefreshesAllProvidersAndRetainsThePeriodicSchedule() {
        Fixture().use { f ->
            f.start(); f.scheduler.advanceTimeBy(10_000)
            f.coordinator.requestRefresh(f.scope)
            f.settle { f.count("secondary") == 2 }; f.idle()
            assertEquals(2, f.count("primary"))
            f.scheduler.advanceTimeBy(20_000)
            f.settle { f.count("secondary") == 3 }; f.idle()
            assertEquals(3, f.count("primary"))
        }
    }

    @Test fun slowProviderDoesNotBlockOthersAndRepeatedRefreshCoalesces() {
        val release = CompletableDeferred<Unit>()
        Fixture { id, _, _ -> if (id == "primary") { release.await(); amount("1") } else amount("2") }.use { f ->
            try {
                f.coordinator.start(f.scope)
                f.settle { f.runtime("secondary").state.value.successful != null }
                assertTrue(f.runtime("primary").state.value.refreshing)
                repeat(3) { f.coordinator.requestRefresh(f.scope) }; f.scheduler.runCurrent()
                assertEquals(1, f.count("primary"))
                release.complete(Unit); f.idle()
                assertEquals("CNY 1", f.runtime("primary").state.value.project().balanceText)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun failureKeepsPreviousSuccessfulAmountAndTimestamp() {
        Fixture { _, _, call -> if (call == 1) amount("8.22", 1234) else BalanceResult.Failed(BalanceFailure.NETWORK) }.use { f ->
            f.start(); f.coordinator.requestRefresh(f.scope)
            f.settle { f.count("secondary") == 2 }; f.idle()
            for (id in f.ids.value) {
                val state = f.runtime(id).state.value
                assertEquals(BalanceResult.Failed(BalanceFailure.NETWORK), state.result)
                assertEquals("CNY 8.22", state.project().balanceText)
                assertEquals(1234L, state.successful!!.checkedAtMillis)
            }
        }
    }

    @Test fun changedConfigurationCancelsAndFencesAnUncooperativeOldReply() {
        val release = CompletableDeferred<Unit>()
        val oldOperation = AtomicReference<Job>()
        Fixture { id, configuration, _ ->
            if (configuration.apiKey == "same-key") {
                if (id == "primary") oldOperation.set(currentCoroutineContext()[Job])
                withContext(NonCancellable) { release.await(); amount("999") }
            } else amount("8.09")
        }.use { f ->
            try {
                f.coordinator.start(f.scope); f.settle { f.count("primary") == 1 }
                f.runtime("primary").store.save(config("primary").copy(apiKey = "new-key"))
                f.settle { f.runtime("primary").state.value.amountValue?.toPlainString() == "8.09" }
                assertTrue(oldOperation.get().isCancelled)
                release.complete(Unit); f.idle()
                f.settle { oldOperation.get().isCompleted }
                assertEquals("CNY 8.09", f.runtime("primary").state.value.project().balanceText)
                assertNull(f.runtime("primary").state.value.lastChange)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun removingProviderCancelsItsInFlightQuery() {
        val cancelled = CompletableDeferred<Unit>()
        Fixture { id, _, _ ->
            if (id == "secondary") try { CompletableDeferred<Unit>().await(); amount("1") }
            finally { cancelled.complete(Unit) }
            else amount("2")
        }.use { f ->
            f.coordinator.start(f.scope); f.settle { f.count("secondary") == 1 }
            f.ids.value = listOf("primary")
            f.settle { cancelled.isCompleted && !f.runtime("secondary").state.value.refreshing }
            f.scheduler.advanceTimeBy(30_000); f.scheduler.runCurrent()
            assertEquals(1, f.count("secondary"))
        }
    }

    @Test fun resumeUsesTheOriginalFifteenSecondThrottle() {
        Fixture().use { f ->
            f.start(); f.coordinator.onResume(f.scope, 100_000)
            f.settle { f.count("secondary") == 2 }; f.idle()
            f.coordinator.onResume(f.scope, 114_999); f.scheduler.runCurrent()
            assertEquals(2, f.count("secondary"))
            f.coordinator.onResume(f.scope, 115_000)
            f.settle { f.count("secondary") == 3 }; f.idle()
        }
    }

    @Test fun coordinatorRestartsAfterItsActivityOwnerIsDestroyed() {
        Fixture().use { f ->
            f.start(); f.scope.cancel(); f.scheduler.runCurrent()
            assertFalse(f.coordinator.isRunning)
            val next = CoroutineScope(Job() + f.dispatcher)
            try {
                f.coordinator.start(next)
                f.settle { f.count("secondary") == 2 }; f.idle()
                assertTrue(f.coordinator.isRunning)
                f.coordinator.requestRefresh()
                f.settle { f.count("secondary") == 3 }; f.idle()
            } finally { next.cancel(); f.scheduler.runCurrent() }
        }
    }

    @Test fun closingDuringInitializationCannotRepublishOrReopenTheHost() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val host = ProviderBalanceRuntimeHost {
            withContext(NonCancellable) {
                entered.complete(Unit); release.await()
                BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery { BalanceResult.Unknown })
            }
        }
        val reader = async { runCatching { host.get("primary") }.isFailure }
        try {
            withTimeout(5000) { entered.await() }; host.close(); release.complete(Unit)
            assertTrue(withTimeout(5000) { reader.await() }); assertNull(host.cached("primary"))
            try { host.get("primary"); fail("closed host") } catch (_: IllegalStateException) { }
        } finally { release.complete(Unit); reader.cancel(); host.close() }
    }
}
