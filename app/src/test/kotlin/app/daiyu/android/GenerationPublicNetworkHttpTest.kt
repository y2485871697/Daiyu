package app.daiyu.android

import app.daiyu.android.integration.tools.GenerationDownloadAuthorization
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Offline source fixtures only. Even the route test uses a socket that throws BEFORE OS I/O. */
class GenerationPublicNetworkHttpTest {
    private fun dns(resolve: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = resolve(hostname)
    }
    private fun v4(vararg bytes: Int) = InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
    private fun v6(vararg prefix: Int) = InetAddress.getByAddress(ByteArray(16).also { out ->
        prefix.forEachIndexed { index, byte -> out[index] = byte.toByte() }
        out[15] = 1
    })
    private fun rejects(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
    private val owner = GenerationAssetOwner("account", GenerationAttemptBinding("conversation", "assistant",
        "run", "call", "generate_images"))

    @Test fun rejectsNonGlobalAndTransitionAddressesWithoutResolvingNames() {
        for (address in listOf(v4(0, 1, 2, 3), v4(10, 0, 0, 1), v4(100, 64, 1, 1),
            v4(127, 0, 0, 1), v4(169, 254, 169, 254), v4(172, 16, 0, 1), v4(192, 168, 1, 1),
            v4(192, 0, 0, 9), v4(192, 0, 2, 1), v4(192, 88, 99, 1), v4(198, 18, 0, 1),
            v4(198, 51, 100, 1), v4(203, 0, 113, 1), v4(224, 0, 0, 1), v4(255, 255, 255, 255),
            v6(), v6(0xfc), v6(0xfe, 0x80), v6(0xff), v6(0x00, 0x64, 0xff, 0x9b),
            v6(0x20, 0x01, 0, 0), v6(0x20, 0x01, 0x0d, 0xb8), v6(0x20, 0x02), v6(0x3f, 0xfe), v6(0x3f, 0xff)))
            assertFalse(address.hostAddress, GenerationPinnedPublicDns.isPublicAddress(address))
        assertTrue(GenerationPinnedPublicDns.isPublicAddress(v4(8, 8, 8, 8)))
        assertTrue(GenerationPinnedPublicDns.isPublicAddress(v6(0x26, 0x06, 0x47, 0)))
    }

    @Test fun mixedEmptyExcessiveAndFailedAnswersAreStickyDenials() {
        for (answer in listOf(emptyList(), listOf(v4(8, 8, 8, 8), v4(127, 0, 0, 1)),
            List(65) { v4(8, 8, 8, 8) })) {
            var calls = 0
            val dns = GenerationPinnedPublicDns("cdn.example", dns { calls++; answer })
            rejects { dns.lookup("cdn.example") }; rejects { dns.lookup("cdn.example") }
            assertEquals(1, calls)
        }
        var calls = 0
        val dns = GenerationPinnedPublicDns("cdn.example", dns { calls++; throw IOException("private resolver details") })
        val failure = runCatching { dns.lookup("cdn.example") }.exceptionOrNull()!!
        assertEquals("generation_dns_denied", failure.message); assertNull(failure.cause)
        rejects { dns.lookup("cdn.example") }; assertEquals(1, calls)
    }

    @Test fun pinsOriginalWholeSetAndNeverResolvesAnotherHostOrRebindingAnswer() {
        var calls = 0
        val dns = GenerationPinnedPublicDns("cdn.example", dns {
            calls++
            if (calls == 1) listOf(v4(8, 8, 8, 8), v4(1, 1, 1, 1)) else listOf(v4(127, 0, 0, 1))
        })
        assertEquals(0, calls)
        rejects { dns.lookup("other.example") }; assertEquals(0, calls)
        val pinned = dns.lookup("cdn.example")
        assertEquals(1, pinned.size); assertEquals(v4(8, 8, 8, 8), pinned.single())
        assertSame(pinned, dns.lookup("cdn.example")); assertEquals(1, calls)
        rejects { (pinned as MutableList<InetAddress>).clear() }
    }

    @Test fun okhttpActualRouteUsesValidatedAddressWithoutPreflightDnsOrRetry() {
        var lookups = 0
        val client = GenerationPublicNetworkHttp.clientFor("cdn.example", dns {
            lookups++; listOf(v4(8, 8, 8, 8), v4(1, 1, 1, 1))
        })
        val destinations = mutableListOf<InetSocketAddress>()
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    destinations.add(endpoint as InetSocketAddress)
                    throw IOException("offline stop before connect")
                }
            }
            override fun createSocket(h: String, p: Int): Socket = error("no host lookup")
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("no host lookup")
            override fun createSocket(h: InetAddress, p: Int): Socket = error("no direct connect")
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("no direct connect")
        }
        val routed = client.newBuilder().socketFactory(sockets).build()
        assertEquals(0, lookups)
        rejects { routed.newCall(Request.Builder().url("https://cdn.example/media?signature=secret").build()).execute() }
        assertEquals(1, lookups); assertEquals(1, destinations.size)
        assertEquals(v4(8, 8, 8, 8), destinations.single().address)
        assertEquals(443, destinations.single().port)
        routed.connectionPool.evictAll()
    }

    @Test fun rejectsMixedAnswerBeforeAnySocketIsCreated() {
        var sockets = 0
        val client = GenerationPublicNetworkHttp.clientFor("cdn.example", dns {
            listOf(v4(8, 8, 8, 8), v4(10, 0, 0, 1))
        }).newBuilder().socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket { sockets++; error("must not connect") }
            override fun createSocket(h: String, p: Int): Socket = error("unexpected")
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int): Socket = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unexpected")
        }).build()
        rejects { client.newCall(Request.Builder().url("https://cdn.example/media").build()).execute() }
        assertEquals(0, sockets)
    }

    @Test fun dedicatedClientsDoNotInheritCookiesAuthProxyPoolOrTlsOverrides() {
        val a = GenerationPublicNetworkHttp.clientFor("cdn.example", dns { error("no DNS at construction") })
        val b = GenerationPublicNetworkHttp.clientFor("cdn.example", dns { error("no DNS at construction") })
        assertNotSame(a.connectionPool, b.connectionPool)
        assertEquals(Proxy.NO_PROXY, a.proxy)
        assertSame(CookieJar.NO_COOKIES, a.cookieJar); assertNull(a.cache)
        assertSame(Authenticator.NONE, a.authenticator); assertSame(Authenticator.NONE, a.proxyAuthenticator)
        assertFalse(a.followRedirects); assertFalse(a.followSslRedirects); assertFalse(a.retryOnConnectionFailure)
        assertEquals(listOf(Protocol.HTTP_1_1), a.protocols)
        assertEquals(30_000, a.connectTimeoutMillis); assertEquals(120_000, a.readTimeoutMillis)
        assertEquals(120_000, a.writeTimeoutMillis); assertEquals(180_000, a.callTimeoutMillis)
        assertTrue(a.interceptors.isEmpty()); assertEquals(1, a.networkInterceptors.size)
        // The platform verifier is retained, not an always-true lambda.
        assertEquals(OkHttpDefaults.hostnameVerifier, a.hostnameVerifier)
    }

    private object OkHttpDefaults { val hostnameVerifier = okhttp3.OkHttpClient().hostnameVerifier }

    @Test fun boundedStreamingReadsOneOverflowByteAndChecksCancellation() {
        val cancellation = GenerationCancellation()
        assertArrayEquals(byteArrayOf(1, 2), GenerationPublicNetworkHttp.readBounded(
            ByteArrayInputStream(byteArrayOf(1, 2)), 2, cancellation))
        val source = ByteArrayInputStream(ByteArray(100))
        rejects { GenerationPublicNetworkHttp.readBounded(source, 4, cancellation) }
        assertEquals(95, source.available())
        cancellation.cancel()
        val untouched = ByteArrayInputStream(byteArrayOf(1))
        assertTrue(runCatching { GenerationPublicNetworkHttp.readBounded(untouched, 4, cancellation) }
            .exceptionOrNull() is GenerationCancelledException)
        assertEquals(1, untouched.available())
    }

    @Test fun invalidUrlsAndAlreadyCancelledCallsHaveRedactedFailureAndDrainWithoutDns() {
        val transport = GenerationPublicNetworkHttp()
        for (url in listOf("http://cdn.example/file", "https://user:secret@cdn.example/file",
            "https://127.0.0.1/file", "https://[::1]/file", "https://cdn.example/file#secret",
            "https://cdn.example.local/file", "https://cdn.example./file", "https://2130706433/file")) {
            val cancellation = GenerationCancellation()
            val failure = runCatching { transport.execute(GenerationHttpRequest("GET", url,
                emptyMap(), null, 10), cancellation) }.exceptionOrNull()!!
            assertEquals("generation_secure_transport_failed", failure.message); assertNull(failure.cause)
            assertTrue(cancellation.snapshot().isDrained)
        }
        val cancelled = GenerationCancellation().apply { cancel() }
        assertTrue(runCatching { transport.execute(GenerationHttpRequest("GET", "https://cdn.example/file",
            emptyMap(), null, 10), cancelled) }.exceptionOrNull() is GenerationCancelledException)
        assertTrue(cancelled.snapshot().isDrained)
    }

    @Test fun allNonSuccessResponsesCloseBeforeTheyCanTriggerOkhttpFollowUp() {
        for (status in listOf(301, 302, 307, 308, 401, 407, 408, 421, 429, 500, 503)) {
            var closed = false
            var aborted = false
            val source = object : okio.ForwardingSource(okio.Buffer().writeUtf8("private error body")) {
                override fun close() { assertTrue(aborted); closed = true; super.close() }
            }
            val buffered = source.buffer()
            val body = object : okhttp3.ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = -1L
                override fun source() = buffered
            }
            val response = okhttp3.Response.Builder().request(Request.Builder()
                .url("https://cdn.example/file?signature=secret").build()).protocol(Protocol.HTTP_1_1)
                .code(status).message("private error").header("Retry-After", "0")
                .header("Location", "https://other.example/credential-target").body(body).build()
            val failure = runCatching {
                GenerationPublicNetworkHttp.rejectFollowUp(response, abort = { aborted = true })
            }.exceptionOrNull()
            assertTrue(failure is GenerationHttpStatusException)
            assertEquals(status, (failure as GenerationHttpStatusException).statusCode)
            assertEquals("generation_http_status", failure.message); assertNull(failure.cause)
            assertTrue(closed)
        }
    }

    @Test fun responseSourceCloseFailureIsNotHiddenByOkhttpCloseQuietly() {
        val source = object : okio.ForwardingSource(okio.Buffer()) {
            override fun close() { throw IOException("synthetic source close failure") }
        }.buffer()
        val response = okhttp3.Response.Builder().request(Request.Builder().url("https://cdn.example/file").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(object : okhttp3.ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        val cancellation = GenerationCancellation()
        val execution = cancellation.begin()
        val close = GenerationResponseClose(response)
        execution.register(close)
        assertTrue(runCatching { execution.release(close) }.exceptionOrNull() is IOException)
        assertEquals(1, cancellation.snapshot().cleanupFailures)
        execution.finish()
        assertTrue(cancellation.snapshot().isDrained)
        assertEquals(1, cancellation.snapshot().cleanupFailures)
    }

    @Test fun resourcesCannotEnableDownloadsWithPolicyAloneAndExtraPolicyOnlyNarrows() {
        fun resources(http: GenerationPublicNetworkHttp? = null) = ControllerGenerationResources("account",
            { error("no DB") }, { "account" }, File("not-created"), http)
        val allow = GenerationDownloadAuthorization { _, _ -> true }
        assertFalse(resources().generationNetwork(allow).authorization.allows(owner, "https://cdn.example/media"))
        val http = GenerationPublicNetworkHttp()
        val enabled = resources(http)
        assertSame(http, enabled.generationNetwork().http)
        assertTrue(enabled.generationNetwork().authorization.allows(owner, "https://cdn.example/media"))
        assertFalse(enabled.generationNetwork(GenerationDownloadAuthorization { _, _ -> false })
            .authorization.allows(owner, "https://cdn.example/media"))
        assertFalse(enabled.generationNetwork(GenerationDownloadAuthorization { _, _ -> error("deny") })
            .authorization.allows(owner, "https://cdn.example/media"))
        assertFalse(enabled.generationNetwork(allow).authorization.allows(owner, "https://127.0.0.1/media"))
    }
}
