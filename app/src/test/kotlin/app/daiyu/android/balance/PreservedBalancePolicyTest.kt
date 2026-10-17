package app.daiyu.android.balance

import org.junit.Assert.*
import org.junit.Test

/** Offline ports of f001fb82 Fetcher/Coordinator cases, not requests to paid accounts. */
class PreservedBalancePolicyTest {
    private class Memory : BalancePersistence {
        var record: ByteArray? = null
        override fun read() = record?.clone()
        override fun write(record: ByteArray?) { this.record = record?.clone() }
    }
    private fun config(option: BalanceOption? = null) = BalanceConfiguration("account", "https://example.test/v1",
        "dedicated-key", "/balance", "CNY", option)
    private fun success(value: String, time: Long) = BalanceResult.Available(BalanceMoney(value.toBigDecimal(), "CNY"), time)
    private fun ProviderBalanceStore.reply(value: BalanceResult) { assertTrue(complete(beginManualRefresh()!!, value)) }

    @Test fun bracketDottedPathsAndSpacedArithmeticArePreserved() {
        val body = """{"balance_infos":[{"total_balance":"128.50"}],"data":{"total_credits":10.5,"total_usage":2.25,"quota":50000000}}"""
        assertEquals("128.50", ProviderBalanceFetcher.extractValue(body, "balance_infos[0].total_balance"))
        assertEquals("128.50", ProviderBalanceFetcher.extractValue(body, "balance_infos.0.total_balance"))
        assertEquals("8.25", ProviderBalanceFetcher.extractValue(body, "data.total_credits - data.total_usage"))
        assertEquals("100", ProviderBalanceFetcher.extractValue(body, BalanceOption.NEW_API_RESULT_PATH))
    }

    @Test fun expressionAssociativityRemainsTheFrozenRightRecursiveAlgorithm() {
        assertEquals("100", ProviderBalanceFetcher.extractValue("{}", "100 / 2 / 2"))
        assertEquals("7", ProviderBalanceFetcher.extractValue("{}", "10 - 5 - 2"))
        assertEquals("12", ProviderBalanceFetcher.extractValue("{}", "3 * 4"))
        assertEquals("7", ProviderBalanceFetcher.extractValue("{}", "3 + 4"))
        assertTrue(runCatching { ProviderBalanceFetcher.extractValue("{}", "3 / 0") }.isFailure)
        assertTrue(runCatching { ProviderBalanceFetcher.extractValue("not-json", "3") }.isFailure)
        assertTrue(runCatching { ProviderBalanceFetcher.extractValue("{}", "missing[0].value") }.isFailure)
        assertTrue(runCatching { ProviderBalanceFetcher.extractValue("{}", "1e999") }.isFailure)
        assertTrue(runCatching { ProviderBalanceFetcher.extractValue("""{"items":{"0":10}}""", "items[0]") }.isFailure)
        assertEquals("10", ProviderBalanceFetcher.extractValue("""{"items":[10]}""", "items[0]"))
        // RFC6901 compatibility still permits numeric object field names; JSONPath does not.
        assertEquals("10", BalanceJsonSyntax("""{"items":{"0":10}}""").select("/items/0"))
    }

    @Test fun frozenUrlRulesAndExplicitOverridesAreNotReplacedWithExactUrlOnly() {
        fun resolve(base: String, path: String, preset: String = BalanceOption.PRESET_CUSTOM) =
            ProviderBalanceFetcher.resolveBalanceUrl(base, path, preset)
        assertEquals("https://example.test/api/user/self", resolve("https://example.test/v1", "api/user/self", BalanceOption.PRESET_NEW_API))
        assertEquals("https://example.test/api/user/self", resolve("https://example.test/compatible-mode/v1", "/api/user/self"))
        assertEquals("https://example.test/v1/usage", resolve("https://example.test/v1", "/usage", BalanceOption.PRESET_SUB2API))
        assertEquals("https://example.test/user/balance", resolve("https://example.test/v1", "/user/balance", BalanceOption.PRESET_DEEPSEEK))
        assertEquals("https://other.test/query?unit=cny", resolve("https://example.test/v1", "https://other.test/query?unit=cny"))
        assertEquals("https://example.test/v1/user/balance", resolve("https://example.test/v1", "user/balance"))
    }

    @Test fun presetsPreserveCustomPathsAndFillOnlyEditableBlanks() {
        val custom = BalanceOption(true, apiPath = "/usage", resultPath = "remaining", userId = "old-id", accessToken = "private-token")
        val newApi = BalanceOption.applyPreset(BalanceOption.PRESET_NEW_API, custom)
        assertEquals("/usage", newApi.apiPath)
        assertEquals("remaining", newApi.resultPath)
        assertEquals(BalanceOption.NEW_API_PATH, newApi.resolved().apiPath)
        assertEquals(BalanceOption.NEW_API_RESULT_PATH, newApi.resolved().resultPath)
        assertEquals(custom, BalanceOption.applyPreset(BalanceOption.PRESET_CUSTOM, newApi))
        val template = BalanceOption(true, BalanceOption.PRESET_NEW_API, BalanceOption.NEW_API_PATH, BalanceOption.NEW_API_RESULT_PATH)
        assertEquals("", BalanceOption.applyPreset(BalanceOption.PRESET_CUSTOM, template).apiPath)
        val sub = BalanceOption.applyPreset(BalanceOption.PRESET_SUB2API, custom)
        assertEquals("/v2/usage", sub.copy(apiPath = "/v2/usage").resolved().apiPath)
        assertEquals("remaining", sub.copy(resultPath = "").resolved().resultPath)
        val deep = BalanceOption.applyPreset(BalanceOption.PRESET_DEEPSEEK, custom)
        assertEquals("balance_infos[0].total_balance", deep.resolved().resultPath)
        assertFalse(newApi.toString().contains("private-token"))
    }

    @Test fun refreshFailureCancellationKeepMoneyAndOnlySuccessAdvancesTime() {
        val disk = Memory()
        val store = ProviderBalanceStore(disk)
        store.save(config())
        store.reply(success("13.52", 100))
        assertNull(store.state.value.lastChange)
        val ticket = store.beginManualRefresh()!!
        assertEquals("CNY 13.52", store.state.value.project().balanceText)
        assertTrue(store.state.value.refreshing)
        assertTrue(store.complete(ticket, BalanceResult.Failed(BalanceFailure.NETWORK)))
        assertEquals("CNY 13.52", store.state.value.project().balanceText)
        assertEquals(100L, store.state.value.project().checkedAtMillis)
        assertEquals("CNY 13.52", ProviderBalanceStore(disk).state.value.project().balanceText)
        val cancelled = store.beginManualRefresh()!!
        store.cancel(cancelled)
        assertEquals("CNY 13.52", store.state.value.project().balanceText)
        store.reply(success("13", 200))
        val first = store.state.value.lastChange!!
        assertEquals(0, "-0.52".toBigDecimal().compareTo(first.delta))
        store.reply(success("11.48", 300))
        val second = store.state.value.lastChange!!
        assertEquals(0, "-1.52".toBigDecimal().compareTo(second.delta))
        assertTrue(second.seq > first.seq)
        store.reply(success("11.480", 400))
        assertEquals(second, store.state.value.lastChange)
        val restored = ProviderBalanceStore(disk)
        assertNull(restored.state.value.lastChange) // no animation replay after process restart
        store.save(config().copy(accountId = "new-account"))
        assertNull(store.state.value.project().balanceText)
        store.reply(success("1", 500))
        assertNull(store.state.value.lastChange) // never compare across accounts
    }

    @Test fun optionsHeadersAndDedicatedTokenRoundTripWithoutPublicCredentialProjection() {
        val disk = Memory()
        val store = ProviderBalanceStore(disk)
        val value = config(BalanceOption(true, BalanceOption.PRESET_NEW_API, accessToken = "private-override"))
            .copy(customHeaders = mapOf("New-Api-User" to "user-123"))
        store.save(value)
        val restored = ProviderBalanceStore(disk)
        assertEquals(value, restored.configurationForEditing())
        assertEquals("private-override", restored.configurationForEditing()!!.resolvedToken())
        assertEquals("https://example.test/api/user/self", value.resolvedEndpoint())
        assertFalse(restored.state.value.toString().contains("private-override"))
        assertFalse(restored.state.value.toString().contains("user-123"))
        assertFalse(value.toString().contains("private-override"))
        assertTrue(runCatching { value.copy(customHeaders = mapOf("Authorization" to "unsafe")).validate() }.isFailure)
        store.save(value.copy(option = value.option!!.copy(enabled = false)))
        assertNull(store.beginManualRefresh())
        assertFalse(store.state.value.project().canRefresh)
        store.save(config(BalanceOption(enabled = false)).copy(apiKey = ""))
        assertNull(store.beginManualRefresh()) // disabled custom may retain blank paths, as in the frozen option
        assertTrue(store.state.value.configured)
    }
}
