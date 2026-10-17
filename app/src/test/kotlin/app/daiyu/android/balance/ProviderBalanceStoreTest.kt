package app.daiyu.android.balance

import org.junit.Assert.*
import org.junit.Test

class ProviderBalanceStoreTest {
    private class MemoryPersistence : BalancePersistence {
        var bytes: ByteArray? = null
        var writes = 0
        var fail = false
        override fun read(): ByteArray? { check(!fail); return bytes?.clone() }
        override fun write(record: ByteArray?) { check(!fail); writes++; bytes = record?.clone() }
    }
    private fun config(account: String = "account-A") = BalanceConfiguration(account,
        "https://balance.example.test/account/balance", "balance-key-only", "/balance", "CNY")
    private fun amount(value: String = "0.00") = BalanceResult.Available(BalanceMoney(value.toBigDecimal(), "CNY"), 1000)

    @Test fun loadSaveAndProjectionDoNotQueryAndZeroIsNotUnknown() {
        val disk = MemoryPersistence()
        val store = ProviderBalanceStore(disk)
        assertNull(store.state.value.project().balanceText)
        store.save(config())
        assertEquals(BalanceResult.Unknown, store.state.value.result)
        assertFalse(store.state.value.refreshing)
        val ticket = store.beginManualRefresh()!!
        assertNull(store.beginManualRefresh())
        assertTrue(store.complete(ticket, amount()))
        assertEquals("CNY 0", store.state.value.project().balanceText)
        val reopened = ProviderBalanceStore(disk)
        assertEquals(store.state.value, reopened.state.value)
        assertEquals(2, disk.writes)
    }

    @Test fun editsOfSameAccountAndSameKeyFenceLateResponseAndDoNotWriteIt() {
        val disk = MemoryPersistence()
        val store = ProviderBalanceStore(disk)
        store.save(config())
        val old = store.beginManualRefresh()!!
        val revision = store.state.value.revision
        store.save(config())
        assertNotEquals(revision, store.state.value.revision)
        val writes = disk.writes
        assertFalse(store.complete(old, amount("999")))
        assertEquals(writes, disk.writes)
        assertNull(store.state.value.project().balanceText)
    }

    @Test fun accountChangeDeletionReloadAndCancellationFenceReplies() {
        val disk = MemoryPersistence()
        val store = ProviderBalanceStore(disk)
        store.save(config())
        val beforeSwitch = store.beginManualRefresh()!!
        store.save(config("account-B"))
        assertFalse(store.complete(beforeSwitch, amount()))
        val beforeClear = store.beginManualRefresh()!!
        store.clear()
        assertFalse(store.complete(beforeClear, amount()))
        store.save(config())
        val beforeReload = store.beginManualRefresh()!!
        store.reload()
        assertFalse(store.complete(beforeReload, amount()))
        val cancelled = store.beginManualRefresh()!!
        store.cancel(cancelled)
        val current = store.beginManualRefresh()!!
        assertFalse(store.complete(cancelled, amount()))
        assertTrue(store.complete(current, amount()))
        assertFalse(store.complete(current, amount("999")))
    }

    @Test fun cancelledOwnerCannotBlockImmediateNewOwnerOrAdmitLateResult() {
        val store = ProviderBalanceStore(MemoryPersistence())
        store.save(config())
        val owner = kotlinx.coroutines.Job()
        val old = store.beginManualRefresh(owner)!!
        owner.cancel()
        val next = store.beginManualRefresh()!!
        assertFalse(store.complete(old, amount("999")))
        store.cancel(old)
        assertTrue(store.complete(next, amount("3")))
        assertEquals("CNY 3", store.state.value.project().balanceText)
    }

    @Test fun foreignStoreTicketCannotPublishAndErrorsNeverBecomeMoney() {
        val store = ProviderBalanceStore(MemoryPersistence())
        val other = ProviderBalanceStore(MemoryPersistence())
        store.save(config()); other.save(config())
        val ticket = store.beginManualRefresh()!!
        assertFalse(other.complete(ticket, amount()))
        assertTrue(store.complete(ticket, BalanceResult.Failed(BalanceFailure.AUTHORIZATION)))
        assertNull(store.state.value.project().balanceText)
        assertTrue(store.state.value.project().statusText.contains("未授权"))
        assertFalse(store.state.value.toString().contains("balance-key-only"))
        assertFalse(config().toString().contains("balance-key-only"))
    }

    @Test fun persistenceFailureFailsClosedWithoutRawErrorOrCredentialFallback() {
        val disk = MemoryPersistence()
        val store = ProviderBalanceStore(disk)
        store.save(config())
        val ticket = store.beginManualRefresh()!!
        disk.fail = true
        assertFalse(store.complete(ticket, amount()))
        assertFalse(store.state.value.configured)
        assertNull(store.configurationForEditing())
        assertEquals(BalanceResult.Failed(BalanceFailure.STORAGE), store.state.value.result)
        assertNull(store.beginManualRefresh())
        disk.fail = false
        store.save(config("recovered"))
        assertTrue(store.state.value.configured)
    }
}
