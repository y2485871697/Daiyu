package app.daiyu.android.vd

import app.daiyu.android.vd.runtime.OwnerProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Executors

/** Scripted transport only; no launcher, socket, root, or display execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], manifest = Config.NONE)
class OwnerUninitializedShutdownClientTest {
    private class Transport(val reply: (JSONObject) -> String) : OwnerTransport {
        val sent = mutableListOf<JSONObject>()
        var closes = 0
        override fun exchange(line: String): String {
            val r = JSONObject(line)
            OwnerProtocol.parseWireRequest(line)
            sent += r
            return reply(r)
        }
        override fun disconnect() { closes++ }
    }

    private fun client(t: OwnerTransport) = VirtualDisplayOwnerClient("journal_generation",
        OwnerRunBinding("s", "t", "c", "r", "m", "o"),
        OwnerCredential("0123456789abcdef0123456789abcdef"), t)

    private fun success() = OwnerProtocol.ok("shutdown_uninitialized", null)
        .put("initialized", false).put("receiptsClosed", true)

    private fun miss() = OwnerProtocol.ok("receipt", null)
        .put("receiptOnly", true).put("executed", false).put("found", false).toString()

    private fun worker(block: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit { block() }.get() } finally { executor.shutdownNow() }
    }

    @Test fun onlyExplicitShutdownSendsControlEnvelopeAndCannotBeRepeated() = worker {
        val t = Transport { if (it.getString("op") == "receipt") miss() else success().toString() }
        val c = client(t)
        assertTrue(t.sent.isEmpty())
        assertEquals(OwnerReply.Outcome.REPLIED, c.lookupGeneration().outcome)
        assertEquals(listOf("receipt"), t.sent.map { it.getString("op") })
        val result = c.shutdownUninitialized()
        assertEquals(OwnerReply.Outcome.REPLIED, result.outcome)
        assertEquals(false, result.payloadCopy()!!.get("initialized"))
        assertEquals(true, result.payloadCopy()!!.get("receiptsClosed"))
        assertFalse(result.payloadCopy()!!.has("released"))
        val wire = t.sent.last()
        assertEquals(setOf("v", "op", "token"), wire.keys().asSequence().toSet())
        assertThrows(OwnerClientException::class.java) { c.shutdownUninitialized() }
        assertThrows(OwnerClientException::class.java) { c.initialize() }
        assertThrows(OwnerClientException::class.java) { c.execute("release_1", "release", JSONObject()) }
        c.disconnectTransport()
        assertEquals(2, t.sent.size)
    }

    @Test fun lostShutdownReplyRetainsIdentityAndOneShotAfterExplicitReconnect() = worker {
        val lost = Transport { throw IOException("lost response") }
        var c = client(lost)
        assertEquals(OwnerReply.Outcome.UNKNOWN, c.shutdownUninitialized().outcome)
        assertEquals(1, lost.sent.size)
        assertTrue(lost.closes > 0)
        assertEquals(OwnerReply.Outcome.UNKNOWN, c.lookupGeneration().outcome) // no implicit reconnect
        val observing = Transport { miss() }
        c = c.reconnectTransport(observing) // same mechanism used by AndroidVirtualDisplayOwnerHost
        assertEquals("journal_generation", c.generation)
        assertThrows(OwnerClientException::class.java) { c.shutdownUninitialized() }
        assertThrows(OwnerClientException::class.java) { c.initialize() }
        assertEquals(OwnerReply.Outcome.REPLIED, c.lookupGeneration().outcome)
        assertEquals(listOf("receipt"), observing.sent.map { it.getString("op") })
        assertEquals("journal_generation", observing.sent.single().getString("generation"))
        assertEquals("o", observing.sent.single().getJSONObject("binding").getString("runOwner"))
        c.disconnectTransport()
        assertEquals(1, observing.sent.size)
    }

    @Test fun noShutdownFromLookupFailureDisconnectOrInitializationUncertainty() = worker {
        val t = Transport { throw IOException("transport lost") }
        var c = client(t)
        assertEquals(OwnerReply.Outcome.UNKNOWN, c.lookupGeneration().outcome)
        c.disconnectTransport()
        assertEquals(listOf("receipt"), t.sent.map { it.getString("op") })
        val reconnected = Transport { throw IOException("initialization response lost") }
        c = c.reconnectTransport(reconnected)
        assertEquals(OwnerReply.Outcome.UNKNOWN, c.initialize().outcome)
        val observing = Transport { miss() }
        c = c.reconnectTransport(observing)
        assertThrows(OwnerClientException::class.java) { c.shutdownUninitialized() }
        assertTrue(observing.sent.isEmpty())
    }

    @Test fun initializedReconnectStillAllowsOriginalExplicitReleaseProtocol() = worker {
        val initial = Transport { r -> OwnerProtocol.fail(r.getString("op"),
            "SESSION_UNCERTAIN", "test response").toString() }
        val first = client(initial)
        assertEquals(OwnerReply.Outcome.REPLIED, first.initialize().outcome)
        assertEquals(OwnerReply.Outcome.REPLIED, first.execute("release_1", "release", JSONObject()).outcome)
        val next = Transport { r -> OwnerProtocol.fail(r.getString("op"),
            "SESSION_UNCERTAIN", "test response").toString() }
        val reconnected = first.reconnectTransport(next)
        assertThrows(OwnerClientException::class.java) { reconnected.shutdownUninitialized() }
        // Connection-local business guards are unchanged; the server remains the receipt authority.
        assertEquals(OwnerReply.Outcome.REPLIED,
            reconnected.execute("release_1", "release", JSONObject()).outcome)
        assertEquals(listOf("release"), next.sent.map { it.getString("op") })
        assertEquals(OwnerReply.Outcome.UNKNOWN, first.lookupGeneration().outcome)
    }

    @Test fun successSchemaIsExactAndNeverAcceptsReleaseOrReceiptAsShutdownProof() = worker {
        val invalid = listOf(
            success().put("initialized", true),
            success().put("receiptsClosed", false),
            success().put("receiptsClosed", "true"),
            success().put("released", true),
            success().put("generation", "journal_generation"),
            success().put("receipt", JSONObject()),
            success().put("receiptOnly", true).put("executed", false).put("found", false),
            success().apply { remove("receiptsClosed") },
            success().put("extra", true),
            success().put("op", "release"),
        )
        for (body in invalid) {
            val t = Transport { body.toString() }
            val c = client(t)
            assertEquals(OwnerReply.Outcome.UNKNOWN, c.shutdownUninitialized().outcome)
            assertTrue(t.closes > 0)
            assertEquals(1, t.sent.size)
        }
        val denied = Transport { OwnerProtocol.fail("shutdown_uninitialized",
            "SHUTDOWN_UNINITIALIZED_DENIED", "initialization admission closed").toString() }
        assertEquals(OwnerReply.Outcome.REPLIED, client(denied).shutdownUninitialized().outcome)
    }

    @Test fun bothPrivateMarkersStillRequireExitZeroAndExactReadyPidDisappearance() {
        val old = "VD_OWNER_STOPPED released=true receiptsFinal=true"
        val fresh = "VD_OWNER_STOPPED initialized=false receiptsClosed=true"
        assertTrue(AndroidVirtualDisplayOwnerHost.isStoppedMarker(old))
        assertTrue(AndroidVirtualDisplayOwnerHost.isStoppedMarker(fresh))
        for (bad in listOf(fresh + " ", fresh.replace("false", "true"),
            fresh.replace("receiptsClosed=true", "receiptsClosed=false"),
            "VD_OWNER_STOPPED released=false receiptsClosed=true", "VD_OWNER_STOPPED")) {
            assertFalse(AndroidVirtualDisplayOwnerHost.isStoppedMarker(bad))
        }
        for (stopped in listOf(false, true)) for (exit in listOf(null, 0, 1)) for (gone in listOf(false, true)) {
            assertEquals(stopped && exit == 0 && gone,
                AndroidVirtualDisplayOwnerHost.isDrained(stopped, exit, gone))
        }
    }
}
