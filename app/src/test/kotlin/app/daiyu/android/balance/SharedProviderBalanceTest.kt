package app.daiyu.android.balance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SharedProviderBalanceTest {
    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone(); writes++ }
    }
    private fun configuration(account: String = "primary") = BalanceConfiguration(
        account, "https://balance.example.test", "balance-only", "", "CNY",
        BalanceOption(enabled = true, apiPath = "/balance", resultPath = "balance", accessToken = "shared-token"),
        mapOf("X-Account" to "shared"))
    private fun amount(value: String, time: Long) = BalanceResult.Available(BalanceMoney(value.toBigDecimal(), "CNY"), time)

    @Test fun matchingEffectiveQueriesIgnoreSlotLabelsButKeepCredentialsAndParsingIsolated() {
        val a = configuration()
        assertTrue(a.sharesQueryWith(a.copy(accountId = "other-slot", apiKey = "unused-key",
            customHeaders = mapOf("x-account" to "shared"))))
        val different = listOf(
            a.copy(endpoint = "https://other.example.test"),
            a.copy(option = a.option!!.copy(accessToken = "different-token")),
            a.copy(option = a.option!!.copy(userId = "another-user")),
            a.copy(option = a.option!!.copy(resultPath = "other")),
            a.copy(option = a.option!!.copy(apiPath = "/another")),
            a.copy(option = a.option!!.copy(enabled = false)),
            a.copy(customHeaders = mapOf("X-Account" to "other")),
            a.copy(currency = "USD"),
            a.copy(option = null, amountPointer = "/balance"),
        )
        different.forEach { assertFalse(a.sharesQueryWith(it)) }
    }

    @Test fun sharedSuccessPersistsWithoutChangingRevisionAndComputesOneDelta() {
        val disk = Memory()
        val store = ProviderBalanceStore(disk)
        val a = configuration()
        store.save(a)
        val revision = store.state.value.revision
        assertTrue(store.acceptSharedResult(revision, a.copy(accountId = "different-slot"), amount("8.22", 10)))
        assertTrue(store.acceptSharedResult(revision, a, amount("8.09", 20)))
        assertEquals(revision, store.state.value.revision)
        assertEquals("primary", store.state.value.accountId)
        assertEquals("CNY 8.09", store.state.value.project().balanceText)
        assertEquals("-0.13".toBigDecimal(), store.state.value.lastChange!!.delta)
        val writes = disk.writes
        val change = store.state.value.lastChange
        assertFalse(store.acceptSharedResult(revision, a, amount("8.09", 20)))
        assertFalse(store.acceptSharedResult(revision, a, amount("999", 20)))
        assertFalse(store.acceptSharedResult(revision, a, amount("999", 19)))
        assertEquals(writes, disk.writes)
        assertEquals(change, store.state.value.lastChange)
        val restored = ProviderBalanceStore(disk)
        assertEquals(a, restored.configurationForEditing())
        assertEquals("CNY 8.09", restored.state.value.project().balanceText)
        assertNull(restored.state.value.lastChange)
    }

    @Test fun oldRevisionChangedIdentityDeletedAndDisabledRecipientsRejectWithoutWriting() {
        val disk = Memory()
        val store = ProviderBalanceStore(disk)
        val a = configuration()
        store.save(a)
        val oldRevision = store.state.value.revision
        store.save(a)
        var writes = disk.writes
        assertFalse(store.acceptSharedResult(oldRevision, a, amount("1", 1)))
        assertFalse(store.acceptSharedResult(store.state.value.revision, a.copy(apiKey = "different",
            option = a.option!!.copy(accessToken = "")), amount("1", 1)))
        assertEquals(writes, disk.writes)
        store.clear()
        writes = disk.writes
        assertFalse(store.acceptSharedResult(oldRevision, a, amount("1", 1)))
        assertEquals(writes, disk.writes)
        store.save(a.copy(option = a.option!!.copy(enabled = false)))
        writes = disk.writes
        assertFalse(store.acceptSharedResult(store.state.value.revision, a, amount("1", 1)))
        assertEquals(writes, disk.writes)
    }

    @Test fun anOlderSharedResultDoesNotCancelANewerPendingQuery() = runBlocking {
        val disk = Memory()
        val store = ProviderBalanceStore(disk)
        val a = configuration()
        store.save(a)
        val operation = launch { CompletableDeferred<Unit>().await() }
        try {
            val pending = checkNotNull(store.beginManualRefresh(operation))
            val writes = disk.writes
            assertFalse(store.acceptSharedResult(store.state.value.revision, a, amount("1", pending.startedAtMillis - 1)))
            assertTrue(operation.isActive)
            assertTrue(store.isCurrent(pending))
            assertTrue(store.state.value.refreshing)
            assertEquals(writes, disk.writes)
            store.cancel(pending)
        } finally { operation.cancelAndJoin() }
    }

    @Test fun refreshOfOneSlotUpdatesAnUnselectedMatchingSlotAndKeepsOtherAccountsIsolated() = runBlocking {
        val sharedSlot = UUID.randomUUID().toString()
        val otherSlot = UUID.randomUUID().toString()
        val calls = AtomicInteger()
        val time = AtomicLong(System.currentTimeMillis() + 1000)
        val host = ProviderBalanceRuntimeHost { slot ->
            BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
                calls.incrementAndGet()
                amount(if (slot == "primary") "8.09" else "8.22", time.incrementAndGet())
            })
        }
        try {
            val primary = host.get("primary")
            val unselected = host.get(sharedSlot)
            val other = host.get(otherSlot)
            primary.save(configuration())
            unselected.save(configuration(sharedSlot))
            other.save(configuration(otherSlot).copy(option = configuration().option!!.copy(accessToken = "other-token")))
            val revision = unselected.state.value.revision
            assertTrue(primary.manualRefresh())
            withTimeout(5000) { unselected.state.first { it.project().balanceText == "CNY 8.09" } }
            assertEquals(primary.state.value.successful, unselected.state.value.successful)
            assertEquals(revision, unselected.state.value.revision)
            assertNull(other.state.value.successful)
            assertEquals(1, calls.get())
            assertFalse(unselected.isPolling)
            assertEquals(configuration(sharedSlot), unselected.store.configurationForEditing())
            other.store.save(configuration(otherSlot)) // Controller also writes directly through the store.
            assertTrue(primary.manualRefresh())
            withTimeout(5000) { other.state.first { it.successful == primary.state.value.successful } }
            assertEquals(2, calls.get())
        } finally { host.close() }
    }

    @Test fun sharedSuccessCancelsAndFencesAnOlderUncooperativeReply() = runBlocking {
        val sharedSlot = UUID.randomUUID().toString()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disks = mutableMapOf<String, Memory>()
        val now = System.currentTimeMillis() + 10_000
        val host = ProviderBalanceRuntimeHost { slot ->
            BalanceRuntime(ProviderBalanceStore(Memory().also { synchronized(disks) { disks[slot] = it } }), BalanceQuery {
                if (slot == sharedSlot) withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); amount("999", now + 1)
                } else amount("8.09", now)
            })
        }
        try {
            val primary = host.get("primary")
            val secondary = host.get(sharedSlot)
            primary.save(configuration())
            secondary.save(configuration(sharedSlot))
            val old = launch { secondary.manualRefresh() }
            try {
                withTimeout(5000) { entered.await() }
                assertTrue(primary.manualRefresh())
                withTimeout(5000) { secondary.state.first { it.project().balanceText == "CNY 8.09" } }
                val writes = synchronized(disks) { checkNotNull(disks[sharedSlot]).writes }
                release.complete(Unit)
                withTimeout(5000) { old.join() }
                assertEquals("CNY 8.09", secondary.state.value.project().balanceText)
                assertEquals(writes, synchronized(disks) { checkNotNull(disks[sharedSlot]).writes })
            } finally { release.complete(Unit); old.cancelAndJoin() }
        } finally { host.close() }
    }
    @Test fun removalDrainsSharingAndAnOldRuntimeCannotBroadcastAfterDeletion() = runBlocking {
        val slot = UUID.randomUUID().toString()
        val sequence = AtomicInteger()
        val time = AtomicLong(System.currentTimeMillis() + 1000)
        val host = ProviderBalanceRuntimeHost {
            BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
                amount(sequence.incrementAndGet().toString(), time.incrementAndGet())
            })
        }
        try {
            val source = host.get("primary")
            val recipient = host.get(slot)
            source.save(configuration())
            recipient.save(configuration(slot))
            assertTrue(source.manualRefresh())
            withTimeout(5000) { recipient.state.first { it.project().balanceText == "CNY 1" } }
            host.remove("primary")
            assertNull(host.cached("primary"))
            assertFalse(source.state.value.configured)
            source.save(configuration())
            assertTrue(source.manualRefresh())
            assertEquals("CNY 2", source.state.value.project().balanceText)
            assertEquals("CNY 1", recipient.state.value.project().balanceText)
            assertNotSame(source, host.get("primary"))
        } finally { host.close() }
    }

    @Test fun closingDuringInitializationCannotRepublishAReadyRuntimeOrReopenTheHost() = runBlocking {
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
            withTimeout(5000) { entered.await() }
            host.close()
            release.complete(Unit)
            assertTrue(withTimeout(5000) { reader.await() })
            assertNull(host.cached("primary"))
            try { host.get("primary"); fail("closed host") } catch (_: IllegalStateException) { }
        } finally { release.complete(Unit); reader.cancelAndJoin(); host.close() }
    }

}
