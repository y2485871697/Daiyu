package app.daiyu.android.balance

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** In-memory OkHttp calls only: no real DNS, sockets, credentials, provider requests or fees. */
class BalanceHttpQueryTest {
    private class Fixture(var body: String, var code: Int = 200, var deferred: Boolean = false) : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request, this).also { calls += it }
    }
    private class FakeCall(private val request: Request, private val fixture: Fixture) : Call {
        private var executed = false
        private var cancelled = false
        private lateinit var callback: Callback
        override fun request() = request
        override fun execute(): Response = throw IOException("Synchronous fixture disabled")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callback = responseCallback
            if (!fixture.deferred) reply()
        }
        fun reply() {
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(fixture.code).message("fixture").body(fixture.body.toResponseBody("application/json".toMediaType())).build())
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(request, fixture)
    }
    private fun config(option: BalanceOption? = null) = BalanceConfiguration("account", "https://example.test/v1", "balance-key-only", "/balance", "CNY", option)
    private fun decode(text: String, option: BalanceOption? = null) = BalanceResponseDecoder.decode(text.toByteArray(), config(option), 123)

    @Test fun presetRequestsResolveIndependentlyAndUseOnlyExplicitBalanceCredentials() = runBlocking {
        val fixture = Fixture("""{"data":{"quota":50000000}}""")
        val configuration = config(BalanceOption(true, BalanceOption.PRESET_NEW_API, accessToken = "dedicated-override"))
            .copy(customHeaders = mapOf("New-Api-User" to "123"))
        val result = BalanceHttpQuery(fixture).query(configuration) as BalanceResult.Available
        assertEquals("CNY 100", result.money.display())
        val request = fixture.calls.single().request()
        assertEquals("GET", request.method)
        assertEquals("https://example.test/api/user/self", request.url.toString())
        assertEquals("Bearer dedicated-override", request.header("Authorization"))
        assertEquals("123", request.header("New-Api-User"))
        assertEquals("no-store", request.header("Cache-Control"))
        assertNull(request.body)
    }

    @Test fun scalarPrecisionZeroAndDeepSeekExtractionAreActualMoneyNotTokenEstimation() {
        assertEquals("CNY 0", (decode("""{"balance":0}""") as BalanceResult.Available).money.display())
        val exact = decode("""{"balance":12345678901234567890.123456789012}""") as BalanceResult.Available
        assertEquals("12345678901234567890.123456789012", exact.money.amount.toPlainString())
        val deep = decode("""{"balance_infos":[{"total_balance":"128.50"}]}""", BalanceOption(true, BalanceOption.PRESET_DEEPSEEK)) as BalanceResult.Available
        assertEquals("CNY 128.5", deep.money.display())
        assertEquals(123L, deep.checkedAtMillis)
        val fraction = decode("""{"balance":" +.5 "}""") as BalanceResult.Available
        assertEquals("CNY 0.5", fraction.money.display())
        val expression = decode("{}", BalanceOption(true, apiPath = "/balance", resultPath = "1 / 3")) as BalanceResult.Available
        assertEquals("0.3333333333333333", expression.money.amount.toPlainString())
        assertEquals(BalanceResult.Unknown, decode("""{"balance":null}"""))
        assertEquals(BalanceResult.Unknown, decode("""{"token_usage":99999}"""))
        assertEquals(BalanceResult.Failed(BalanceFailure.INVALID_RESPONSE), decode("""{"balance":"N/A"}"""))
    }

    @Test fun invalidAmbiguousUnboundedAndNonfiniteResponsesNeverProjectAsAmounts() {
        val invalid = listOf("""{"balance":1,"balance":2}""", """{"balance":true}""", """{"balance":1} trailing""",
            """{"balance":NaN}""", """{"balance":1e999}""", """{"balance":{"secret":"private-response"}}""")
        invalid.forEach { assertEquals(BalanceResult.Failed(BalanceFailure.INVALID_RESPONSE), decode(it)) }
        assertEquals(BalanceResult.Failed(BalanceFailure.INVALID_RESPONSE),
            BalanceResponseDecoder.decode(byteArrayOf(0xC3.toByte(), 0x28), config(), 123))
        assertEquals(BalanceResult.Failed(BalanceFailure.RESPONSE_TOO_LARGE),
            BalanceResponseDecoder.decode(ByteArray(BalanceHttpQuery.MAX_RESPONSE_BYTES + 1), config(), 123))
        assertEquals(BalanceResult.Failed(BalanceFailure.INVALID_RESPONSE),
            decode("""{"data":{"quota":1}}""", BalanceOption(true, resultPath = "data.quota / 0", apiPath = "/balance")))
    }

    @Test fun httpErrorsAndRedirectAreFixedSafeKindsAndDoNotExposeBody() = runBlocking {
        for ((code, failure) in listOf(401 to BalanceFailure.AUTHORIZATION, 403 to BalanceFailure.AUTHORIZATION,
            429 to BalanceFailure.RATE_LIMITED, 302 to BalanceFailure.HTTP, 503 to BalanceFailure.HTTP)) {
            val fixture = Fixture("private-response-secret", code)
            val result = BalanceHttpQuery(fixture).query(config())
            assertEquals(BalanceResult.Failed(failure), result)
            assertEquals(1, fixture.calls.size)
            assertFalse(result.toString().contains("private-response-secret"))
        }
        val tooLarge = Fixture("x".repeat(BalanceHttpQuery.MAX_RESPONSE_BYTES + 1))
        assertEquals(BalanceResult.Failed(BalanceFailure.RESPONSE_TOO_LARGE), BalanceHttpQuery(tooLarge).query(config()))
    }

    @Test fun cancellationReachesUnderlyingCallAndLateCallbackCannotPublish() = runBlocking {
        val fixture = Fixture("""{"balance":999}""", deferred = true)
        var published = false
        val operation = launch { BalanceHttpQuery(fixture).query(config()); published = true }
        yield()
        assertEquals(1, fixture.calls.size)
        operation.cancelAndJoin()
        assertTrue(fixture.calls.single().isCanceled())
        fixture.calls.single().reply()
        assertFalse(published)
    }

    @Test fun stoppedManualForegroundOwnerCancelsActualCallAndRejectsLateCallbackThenRestarts() = runBlocking {
        val fixture = Fixture("""{"balance":12.34}""")
        val disk = object : BalancePersistence {
            var bytes: ByteArray? = null
            var writes = 0
            override fun read() = bytes?.clone()
            override fun write(record: ByteArray?) { bytes = record?.clone(); writes++ }
        }
        val runtime = BalanceRuntime(ProviderBalanceStore(disk), BalanceHttpQuery(fixture))
        runtime.save(config())
        runtime.manualRefresh()
        val checkedAt = runtime.state.value.project().checkedAtMillis
        val owner = BalanceForegroundOwner(runtime, poll = false)
        try {
            owner.start(this)
            fixture.deferred = true
            val request = checkNotNull(owner.requestRefresh())
            yield()
            assertEquals(2, fixture.calls.size)
            owner.stop() // Same path as settings ON_STOP; no composition disposal/poller required.
            request.join()
            assertTrue(request.isCancelled)
            assertTrue(fixture.calls.last().isCanceled())
            assertFalse(runtime.state.value.refreshing)
            val writes = disk.writes
            fixture.body = """{"balance":999999}"""
            fixture.calls.last().reply()
            assertEquals(writes, disk.writes)
            assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
            assertNull(owner.requestRefresh())
            fixture.deferred = false
            fixture.code = 503
            owner.start(this)
            checkNotNull(owner.requestRefresh()).join()
            assertEquals(3, fixture.calls.size)
            assertEquals(BalanceResult.Failed(BalanceFailure.HTTP), runtime.state.value.result)
            assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
            assertEquals(checkedAt, runtime.state.value.project().checkedAtMillis)
        } finally { owner.close() }
    }

    @Test fun explicitlyBlankDedicatedCredentialsRetainUnauthenticatedProviderSupportWithoutFallback() = runBlocking {
        val fixture = Fixture("""{"balance":12}""")
        val result = BalanceHttpQuery(fixture).query(config().copy(apiKey = ""))
        assertTrue(result is BalanceResult.Available)
        assertNull(fixture.calls.single().request().header("Authorization"))
    }

    @Test fun disabledConfigurationDoesNotIssueCallAndClientHasBoundedNoFollowupPolicy() = runBlocking {
        val fixture = Fixture("{}")
        val disabled = config(BalanceOption(false, BalanceOption.PRESET_DEEPSEEK))
        assertEquals(BalanceResult.Unknown, BalanceHttpQuery(fixture).query(disabled))
        assertTrue(fixture.calls.isEmpty())
        assertTrue(runCatching { config().copy(endpoint = "http://example.test").validate() }.isFailure)
        val client = BalanceHttpQuery.newClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(15_000, client.callTimeoutMillis)
        assertEquals(5_000, client.readTimeoutMillis)
        assertTrue(client.interceptors.isEmpty()) // no logging interceptor
        assertNull(client.cache)
    }
}
