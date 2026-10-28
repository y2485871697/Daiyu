package app.daiyu.android

import app.daiyu.android.balance.BalancePersistence
import app.daiyu.android.balance.BalanceQuery
import app.daiyu.android.balance.BalanceRuntime
import app.daiyu.android.balance.ProviderBalanceRuntimeHost
import app.daiyu.android.balance.ProviderBalanceStore

internal fun fixtureProviderBalances() = ProviderBalanceRuntimeHost(
    draftQuery = BalanceQuery { error("Unexpected fixture balance request") },
    factory = { BalanceRuntime(ProviderBalanceStore(object : BalancePersistence {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }), BalanceQuery { error("Unexpected fixture balance request") }) })
