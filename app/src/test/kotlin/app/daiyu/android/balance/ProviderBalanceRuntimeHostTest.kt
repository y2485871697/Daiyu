package app.daiyu.android.balance

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProviderBalanceRuntimeHostTest {
    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }
    private fun configuration(account: String, legacy: Boolean = false) = BalanceConfiguration(
        account, "https://$account.invalid", "$account-balance-only-key", "/money", "CNY",
        if (legacy) null else BalanceOption(enabled = true, apiPath = "/balance", resultPath = "money", accessToken = "$account-token"),
        mapOf("X-Balance-Account" to account))

    @Test fun exactSlotsRetainIndependentFullConfigurationsAndNeverQueryOnOpenOrSave() = runBlocking {
        val records = ConcurrentHashMap<String, Memory>()
        val calls = AtomicInteger()
        fun host() = ProviderBalanceRuntimeHost { slot ->
            BalanceRuntime(ProviderBalanceStore(records.computeIfAbsent(slot) { Memory() }),
                BalanceQuery { calls.incrementAndGet(); BalanceResult.Unknown })
        }
        val other = UUID.randomUUID().toString()
        val a = configuration("account-a")
        val b = configuration("account-b", legacy = true)
        val first = host()
        try {
            val primary = first.get("primary")
            val secondary = first.get(other)
            assertSame(primary, first.get("primary"))
            assertNotSame(primary, secondary)
            assertNull(primary.store.configurationForEditing())
            assertNull(secondary.store.configurationForEditing())
            primary.save(a); secondary.save(b)
            assertEquals(a, primary.store.configurationForEditing())
            assertEquals(b, secondary.store.configurationForEditing())
            assertSame(secondary, first.cached(other))
            assertEquals(0, calls.get())
            assertFalse(primary.isPolling)
            assertFalse(secondary.isPolling)
        } finally { first.close() }
        val replacement = host()
        try {
            assertEquals(a, replacement.get("primary").store.configurationForEditing())
            assertEquals(b, replacement.get(other).store.configurationForEditing())
            assertNull(replacement.get(other).store.configurationForEditing()!!.option)
            assertEquals(0, calls.get())
            replacement.get("primary").clear()
            assertEquals(b, replacement.get(other).store.configurationForEditing())
        } finally { replacement.close() }
    }

    @Test fun draftQueryUsesOnlyExplicitIndependentCredentialsWithoutCreatingOrWritingRuntime() = runBlocking {
        val requests = mutableListOf<BalanceConfiguration>()
        val host = ProviderBalanceRuntimeHost(draftQuery = BalanceQuery {
            requests += it
            BalanceResult.Available(BalanceMoney("1.25".toBigDecimal(), "CNY"), 1L)
        }, factory = { error("a draft query must never create or replace a saved account") })
        try {
            val draft = configuration("draft-only")
            assertTrue(host.queryDraft(draft) is BalanceResult.Available)
            assertEquals(listOf(draft), requests)
            assertNull(host.cached("primary"))
            try {
                host.queryDraft(draft.copy(option = draft.option!!.copy(enabled = false)))
                fail("disabled draft")
            } catch (_: IllegalStateException) { }
            try {
                host.queryDraft(draft.copy(endpoint = ""))
                fail("invalid draft")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, requests.size)
        } finally { host.close() }
    }

    @Test fun displayNamesPathsAndUnknownIdsCannotCreateProviderNamespaces() = runBlocking {
        val calls = AtomicInteger()
        val host = ProviderBalanceRuntimeHost { calls.incrementAndGet(); error("invalid slots must not initialize") }
        try {
            for (id in listOf("", "Display name", "../primary", "PRIMARY", "provider-balance")) {
                try { host.get(id); fail("invalid provider slot") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, calls.get())
        } finally { host.close() }
    }
}
