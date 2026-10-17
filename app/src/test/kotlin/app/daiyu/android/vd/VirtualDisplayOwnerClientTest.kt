package app.daiyu.android.vd

import android.content.ContextWrapper
import app.daiyu.android.vd.runtime.OwnerOperationIdentity
import app.daiyu.android.vd.runtime.OwnerProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Executors

/** Pure scripted transport; never invokes su, sockets, display APIs or installed APK code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class VirtualDisplayOwnerClientTest {
    private val generation = "journal_generation_7"
    private val binding = OwnerRunBinding("session", "task", "conversation", "run", "message", "owner")
    private val dummySecret = "0123456789abcdef0123456789abcdef"

    private class ScriptedTransport(val response: (JSONObject) -> String) : OwnerTransport {
        val requests = mutableListOf<JSONObject>()
        var disconnected = 0
        override fun exchange(line: String): String {
            val request = JSONObject(line)
            requests.add(request)
            return response(request)
        }
        override fun disconnect() { disconnected++ }
        override fun toString() = "ScriptedTransport"
    }

    private fun client(t: OwnerTransport) = VirtualDisplayOwnerClient(generation, binding, OwnerCredential(dummySecret), t)
    private fun <T> worker(block: () -> T): T {
        val e = Executors.newSingleThreadExecutor()
        try { return e.submit<T> { block() }.get() } finally { e.shutdownNow() }
    }

    private fun lookupResponse(request: JSONObject, found: Boolean = true): String {
        val op = request.getString("op")
        val body = JSONObject().put("v", 2).put("op", op).put("ok", true)
            .put("receiptOnly", true).put("executed", false).put("found", found)
        if (found) body.put("receipt", receipt(request))
        return body.toString()
    }

    // Invoke the authoritative Java implementation, not OwnerV2Contract (the adapter under test).
    // Reflection is limited to package-private/private pure contract methods; no owner is started.
    private fun javaCall(type: Class<*>, name: String, vararg arguments: Any): Any {
        val method = type.getDeclaredMethod(name, *arguments.map { it.javaClass }.toTypedArray())
        method.isAccessible = true
        return method.invoke(null, *arguments)!!
    }

    private val oracleBinding: OwnerOperationIdentity.RunBinding by lazy {
        javaCall(OwnerOperationIdentity::class.java, "bindingFromTrustedHost",
            "session", "task", "conversation", "run", "message", "owner") as OwnerOperationIdentity.RunBinding
    }

    private fun javaParameters(op: String, input: JSONObject): JSONObject =
        javaCall(OwnerProtocol::class.java, "businessParameters", op, input) as JSONObject

    private fun javaIdentity(id: String, op: String, input: JSONObject): OwnerOperationIdentity =
        javaCall(Class.forName("app.daiyu.android.vd.runtime.OwnerReceiptCoordinator"), "identity",
            generation, id, op, oracleBinding, javaParameters(op, input)) as OwnerOperationIdentity

    private fun receipt(request: JSONObject, state: String = "STARTED"): JSONObject {
        val id = request.optString("operationId", "")
        val op = if (id.isEmpty()) "" else request.optString("operation", request.getString("op"))
        val digest = if (id.isEmpty())
            javaCall(OwnerOperationIdentity::class.java, "generationDigest", generation, oracleBinding)
        else javaIdentity(id, op, request.getJSONObject("parameters")).digest
        val r = JSONObject().put("generation", generation).put("operationId", id).put("operation", op)
            .put("generationReceipt", id.isEmpty()).put("digest", digest)
            .put("bindingDigest", javaCall(OwnerOperationIdentity::class.java, "bindingDigest", oracleBinding))
            .put("state", state)
        if (state in setOf("SUCCEEDED", "UNKNOWN", "FAILED"))
            r.put("result", JSONObject().put("ok", state == "SUCCEEDED")
                .put("released", state == "SUCCEEDED" && (id.isEmpty() || op == "release")))
        return r
    }

    // Wire fixtures mirror VirtualDisplayOwner and OwnerHandoff success fields, wrapped exactly
    // as OwnerReceiptCoordinator. They validate parsing, not actual display/task execution.
    private fun successResponse(request: JSONObject, reused: Boolean = false): JSONObject {
        val op = request.getString("op")
        val out = OwnerProtocol.ok(op, null).put("generation", generation)
            .put("receiptOnly", false).put("receipt", receipt(request, "SUCCEEDED"))
        when (op) {
            "status" -> out.put("ready", true).put("displayId", 4).put("uniqueId", "virtual:owner")
                .put("name", "owner").put("width", 640).put("height", 480).put("densityDpi", 160)
                .put("flags", 0).put("ownerPackage", "android")
                .put("frameCount", 0).put("hasFrame", false).put("frameTimestampNs", 0L)
                .put("session", "active").put("sourceState", "empty").put("sourceEmpty", true)
                .put("sourceTaskCount", 0).put("sourcePackages", JSONArray())
                .put("retainedTaskIds", JSONArray()).put("liveTaskIds", JSONArray()).put("goneTaskIds", JSONArray())
                .put("finishing", false).put("handoffComplete", false).put("releaseAttempted", false)
                .put("mutationUncertain", false).put("supported", JSONArray(OwnerProtocol.SUPPORTED_OPS.toList()))
                .put("missing", JSONArray())
            "launch" -> {
                out.put("taskIds", JSONArray().put(12)).put("launched", true).put("displayId", 4)
                if (reused) out.put("reused", true).put("reusedTaskId", 12)
                else out.put("exitCode", 0).put("output", "")
            }
            "input" -> out.put("executed", true).put("kind", request.getJSONObject("parameters").getString("kind"))
                .put("displayId", 4).put("exitCode", 0)
            "snapshot" -> {
                out.put("displayId", 4).put("frameCount", 1).put("timestampNs", 3_000_000_000L)
                    .put("format", "png").put("width", 1).put("height", 1)
                if (request.getJSONObject("parameters").getBoolean("include")) {
                    val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aG1cAAAAASUVORK5CYII="
                    out.put("data", png).put("bytes", java.util.Base64.getDecoder().decode(png).size)
                }
            }
            "handoff" -> {
                val selected = request.getJSONObject("parameters").getJSONArray("taskIds")
                out.put("handedOff", true).put("sourceEmpty", true).put("cleanupOnly", selected.length() == 0)
                    .put("keptTaskIds", JSONArray(selected.toString()))
                    .put("removedTaskIds", JSONArray()).put("goneTaskIds", JSONArray())
                    .put("focusSamples", JSONArray().put("OK").put("OK"))
            }
            "release" -> out.put("released", true).put("displayId", 4).put("uniqueId", "virtual:owner")
            else -> error("unsupported fixture operation")
        }
        return out
    }

    @Test fun constructorDoesNotStartOrTouchContext() {
        val host = AndroidVirtualDisplayOwnerHost(ContextWrapper(null))
        assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED, host.observe().lifecycle)
        assertNull(host.client)
        assertFalse(host.observe().stopped)
    }

    @Test fun lookupBeforeInitializeIsReceiptOnlyAndHasStableBinding() = worker {
        val t = ScriptedTransport { lookupResponse(it, false) }
        val c = client(t)
        val response = c.lookupGeneration()
        assertEquals(OwnerReply.Outcome.REPLIED, response.outcome)
        assertEquals(true, response.payloadCopy()!!.get("receiptOnly"))
        assertEquals(false, response.payloadCopy()!!.get("found"))
        assertFalse(response.payloadCopy()!!.has("receipt"))
        assertEquals(false, response.payloadCopy()!!.get("executed"))
        assertEquals(listOf("receipt"), t.requests.map { it.getString("op") })
        assertEquals(generation, t.requests.single().getString("generation"))
        assertEquals(6, t.requests.single().getJSONObject("binding").length())
        assertFalse(c.toString().contains(dummySecret))
        assertFalse(response.toString().contains(dummySecret))
    }

    @Test fun originalAndLookupUseSameExternalIdentityAndFrozenDefaults() = worker {
        val t = ScriptedTransport { lookupResponse(it) }
        val c = client(t)
        val input = JSONObject()
        val first = c.execute("operation_5", "snapshot", input)
        input.put("maxBytes", 1) // cannot mutate the already transmitted snapshot
        val second = c.lookup("operation_5", "snapshot", JSONObject())
        assertEquals(OwnerReply.Outcome.REPLIED, first.outcome)
        assertEquals(OwnerReply.Outcome.REPLIED, second.outcome)
        assertEquals(listOf("snapshot", "receipt"), t.requests.map { it.getString("op") })
        assertEquals("operation_5", t.requests[1].getString("operationId"))
        assertEquals("snapshot", t.requests[1].getString("operation"))
        assertEquals(6 * 1024 * 1024, t.requests[0].getJSONObject("parameters").getInt("maxBytes"))
        assertEquals(t.requests[0].getJSONObject("parameters").toString(), t.requests[1].getJSONObject("parameters").toString())
        var rejected = false
        try { c.execute("operation_5", "snapshot", JSONObject()) } catch (_: OwnerClientException) { rejected = true }
        assertTrue(rejected)
        assertEquals(2, t.requests.size)
    }

    @Test fun initializeIsExplicitAndOneShot() = worker {
        val t = ScriptedTransport { r ->
            JSONObject().put("v", 2).put("op", "initialize").put("ok", true).put("generation", generation)
                .put("initialized", true).put("displayId", 4).put("uniqueId", "virtual:owner")
                .put("receipt", receipt(r)).toString()
        }
        val c = client(t)
        assertTrue(t.requests.isEmpty())
        assertEquals(OwnerReply.Outcome.REPLIED, c.initialize().outcome)
        var rejected = false
        try { c.initialize() } catch (_: OwnerClientException) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, t.requests.size)
    }

    @Test fun allBusinessSuccessFixturesMatchIndependentJavaContract() = worker {
        val cases = listOf(
            Triple("status", JSONObject(), false),
            Triple("launch", JSONObject().put("component", "example.app/.Main"), false),
            Triple("launch", JSONObject().put("component", "example.app/.Main").put("displayId", 4), true),
            Triple("input", JSONObject().put("kind", "tap").put("x", 1).put("y", 2), false),
            Triple("input", JSONObject().put("kind", "swipe").put("x1", 1).put("y1", 2)
                .put("x2", 3).put("y2", 4), false),
            Triple("input", JSONObject().put("kind", "key").put("keyCode", 4), false),
            Triple("input", JSONObject().put("kind", "text").put("text", "hello"), false),
            Triple("snapshot", JSONObject(), false),
            Triple("snapshot", JSONObject().put("include", false).put("displayId", 4), false),
            Triple("handoff", JSONObject().put("taskIds", JSONArray()), false),
            Triple("handoff", JSONObject().put("taskIds", JSONArray()).put("displayId", 4), false),
            Triple("handoff", JSONObject().put("taskIds", JSONArray().put(12)), false),
            Triple("handoff", JSONObject().put("taskIds", JSONArray().put(23).put(12)).put("displayId", 4), false),
            Triple("release", JSONObject(), false),
        )
        for ((index, case) in cases.withIndex()) {
            val (op, input, reused) = case
            val id = "success_$index"
            val t = ScriptedTransport { successResponse(it, reused).toString() }
            val reply = client(t).execute(id, op, input)
            assertEquals(op, OwnerReply.Outcome.REPLIED, reply.outcome)
            assertEquals(0, t.disconnected)
            val request = t.requests.single()
            val parameters = request.getJSONObject("parameters")
            assertEquals(javaParameters(op, input).toString(), parameters.toString())
            val payload = reply.payloadCopy()!!
            assertEquals(false, payload.get("receiptOnly"))
            assertFalse(payload.has("found"))
            assertEquals(op == "input", payload.has("executed"))
            val result = payload.getJSONObject("receipt")
            val oracle = javaIdentity(id, op, input)
            assertEquals(oracle.digest, result.getString("digest"))
            assertEquals(oracle.bindingDigest, result.getString("bindingDigest"))
            assertEquals("SUCCEEDED", result.getString("state"))
            assertEquals(op == "release", result.getJSONObject("result").getBoolean("released"))
        }
    }

    @Test fun handoffSuccessPreservesExactSelectionAndAllowsCleanup() = worker {
        for (selected in listOf(emptyList<Int>(), listOf(12), listOf(23, 12))) {
            val t = ScriptedTransport { request ->
                successResponse(request).put("removedTaskIds", JSONArray().put(34))
                    .put("goneTaskIds", JSONArray().put(45)).toString()
            }
            val reply = client(t).execute("handoff_valid", "handoff",
                JSONObject().put("taskIds", JSONArray(selected)))
            assertEquals(selected.toString(), OwnerReply.Outcome.REPLIED, reply.outcome)
            val payload = reply.payloadCopy()!!
            assertEquals(JSONArray(selected).toString(), payload.getJSONArray("keptTaskIds").toString())
            assertEquals(selected.isEmpty(), payload.getBoolean("cleanupOnly"))
            assertEquals(0, t.disconnected)
        }
    }

    @Test fun contradictoryHandoffSuccessIsUnknownAndNeverReplayed() = worker {
        val changes: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "missing kept" to { o -> o.remove("keptTaskIds") },
            "no delivery" to { o -> o.put("keptTaskIds", JSONArray()) },
            "partial delivery" to { o -> o.put("keptTaskIds", JSONArray().put(12)) },
            "foreign delivery" to { o -> o.put("keptTaskIds", JSONArray().put(12).put(99)) },
            "extra delivery" to { o -> o.put("keptTaskIds", JSONArray().put(12).put(23).put(99)) },
            "reordered delivery" to { o -> o.put("keptTaskIds", JSONArray().put(23).put(12)) },
            "duplicate kept" to { o -> o.put("keptTaskIds", JSONArray().put(12).put(23).put(12)) },
            "zero kept" to { o -> o.put("keptTaskIds", JSONArray().put(0).put(23)) },
            "negative kept" to { o -> o.put("keptTaskIds", JSONArray().put(-1).put(23)) },
            "selected removed" to { o -> o.put("removedTaskIds", JSONArray().put(12)) },
            "selected gone" to { o -> o.put("goneTaskIds", JSONArray().put(23)) },
            "zero removed" to { o -> o.put("removedTaskIds", JSONArray().put(0)) },
            "negative gone" to { o -> o.put("goneTaskIds", JSONArray().put(-1)) },
            "duplicate removed" to { o -> o.put("removedTaskIds", JSONArray().put(34).put(34)) },
            "duplicate gone" to { o -> o.put("goneTaskIds", JSONArray().put(45).put(45)) },
            "cleanup flag for delivery" to { o -> o.put("cleanupOnly", true) },
        )
        for ((label, change) in changes) {
            val t = ScriptedTransport { request -> successResponse(request).apply(change).toString() }
            val c = client(t)
            val reply = c.execute("handoff_invalid", "handoff",
                JSONObject().put("taskIds", JSONArray().put(12).put(23)))
            assertEquals(label, OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertNull(label, reply.payloadCopy())
            assertEquals(label, 1, t.disconnected)
            assertEquals(label, OwnerReply.Outcome.UNKNOWN, c.lookupGeneration().outcome)
            assertEquals(label, listOf("handoff"), t.requests.map { it.getString("op") })
        }
    }

    @Test fun invalidSelectionsCannotBeCertifiedByHandoffSuccess() = worker {
        for (selected in listOf(JSONArray().put(12).put(12), JSONArray().put(0), JSONArray().put(-1))) {
            val t = ScriptedTransport { successResponse(it).toString() }
            val reply = client(t).execute("handoff_bad_selection", "handoff",
                JSONObject().put("taskIds", selected))
            assertEquals(selected.toString(), OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertNull(reply.payloadCopy())
            assertEquals(1, t.disconnected)
            assertEquals(1, t.requests.size)
        }
    }

    @Test fun cleanupOnlyCannotClaimDeliveryOrUseFalseCleanupFlag() = worker {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { o -> o.put("cleanupOnly", false) },
            { o -> o.put("keptTaskIds", JSONArray().put(12)) },
        )
        for (change in changes) {
            val t = ScriptedTransport { request -> successResponse(request).apply(change).toString() }
            val reply = client(t).execute("handoff_bad_cleanup", "handoff",
                JSONObject().put("taskIds", JSONArray()))
            assertEquals(OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertNull(reply.payloadCopy())
            assertEquals(1, t.disconnected)
        }
    }

    @Test fun omittedSelectionCannotBeCertifiedAsCleanupSuccess() = worker {
        val t = ScriptedTransport { request ->
            OwnerProtocol.ok("handoff", null).put("generation", generation).put("receiptOnly", false)
                .put("receipt", receipt(request, "SUCCEEDED"))
                .put("handedOff", true).put("sourceEmpty", true).put("cleanupOnly", true)
                .put("keptTaskIds", JSONArray()).put("removedTaskIds", JSONArray())
                .put("goneTaskIds", JSONArray()).put("focusSamples", JSONArray()).toString()
        }
        val reply = client(t).execute("handoff_missing_selection", "handoff", JSONObject())
        assertEquals(OwnerReply.Outcome.UNKNOWN, reply.outcome)
        assertNull(reply.payloadCopy())
        assertEquals(1, t.disconnected)
    }

    @Test fun handoffMissingSelectionPreflightFailureRemainsAReply() = worker {
        val t = ScriptedTransport { request ->
            OwnerProtocol.fail("handoff", "HANDOFF_PREFLIGHT_FAILED", "preflight:selection:IllegalStateException")
                .put("generation", generation).put("receiptOnly", false)
                .put("receipt", receipt(request, "UNKNOWN")).toString()
        }
        val reply = client(t).execute("handoff_preflight_failure", "handoff", JSONObject())
        assertEquals(OwnerReply.Outcome.REPLIED, reply.outcome)
        assertEquals(false, reply.payloadCopy()!!.getBoolean("ok"))
        assertEquals(0, t.disconnected)
    }

    @Test fun lookupTerminalReceiptsRespectJavaSummarySemantics() = worker {
        for (op in listOf("generation", "status", "release")) {
            for (state in listOf("RESERVED", "STARTED", "UNKNOWN", "SUCCEEDED")) {
                val t = ScriptedTransport { request ->
                    JSONObject(lookupResponse(request)).put("receipt", receipt(request, state)).toString()
                }
                val c = client(t)
                val reply = if (op == "generation") c.lookupGeneration()
                    else c.lookup("lookup_1", op, JSONObject())
                assertEquals("$op/$state", OwnerReply.Outcome.REPLIED, reply.outcome)
                assertEquals(false, reply.payloadCopy()!!.get("executed"))
                assertEquals(0, t.disconnected)
                assertEquals(listOf("receipt"), t.requests.map { it.getString("op") })
            }
        }
    }

    @Test fun contradictoryUnknownAndUnsupportedFailedReceiptsAreUnknown() = worker {
        for (op in listOf("generation", "status", "release")) {
            for ((state, ok) in listOf("UNKNOWN" to true, "FAILED" to false, "FAILED" to true)) {
                val t = ScriptedTransport { request ->
                    val r = receipt(request, state)
                    r.getJSONObject("result").put("ok", ok)
                    JSONObject(lookupResponse(request)).put("receipt", r).toString()
                }
                val c = client(t)
                val reply = if (op == "generation") c.lookupGeneration()
                    else c.lookup("lookup_2", op, JSONObject())
                assertEquals("$op/$state/$ok", OwnerReply.Outcome.UNKNOWN, reply.outcome)
                assertNull(reply.payloadCopy())
                assertEquals(1, t.disconnected)
                // No automatic initialize, retry, release or additional lookup after uncertainty.
                assertEquals(OwnerReply.Outcome.UNKNOWN, c.lookupGeneration().outcome)
                assertEquals(1, t.requests.size)
            }
        }
    }

    @Test fun normalFailureCannotCarrySuccessfulUnknownSummary() = worker {
        for (summaryOk in listOf(false, true)) {
            val t = ScriptedTransport { request ->
                val r = receipt(request, "UNKNOWN")
                r.getJSONObject("result").put("ok", summaryOk)
                OwnerProtocol.fail("status", "INTERNAL", "unconfirmed")
                    .put("generation", generation).put("receiptOnly", false).put("receipt", r).toString()
            }
            val reply = client(t).execute("failure_1", "status", JSONObject())
            assertEquals(if (summaryOk) OwnerReply.Outcome.UNKNOWN else OwnerReply.Outcome.REPLIED, reply.outcome)
            assertEquals(if (summaryOk) 1 else 0, t.disconnected)
        }
    }

    @Test fun initializeReceiptOnlyRequiresAnExistingGeneration() = worker {
        for (found in listOf(false, true)) {
            val t = ScriptedTransport { lookupResponse(it, found) }
            val reply = client(t).initialize()
            assertEquals(if (found) OwnerReply.Outcome.REPLIED else OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertEquals(if (found) 0 else 1, t.disconnected)
            assertEquals(listOf("initialize"), t.requests.map { it.getString("op") })
        }
    }

    @Test fun succeededGenerationAndReleaseMustReportReleased() = worker {
        for (op in listOf("generation", "status", "release")) {
            val t = ScriptedTransport { request ->
                val r = receipt(request, "SUCCEEDED")
                r.getJSONObject("result").put("released", op == "status")
                JSONObject(lookupResponse(request)).put("receipt", r).toString()
            }
            val c = client(t)
            val reply = if (op == "generation") c.lookupGeneration() else c.lookup("lookup_3", op, JSONObject())
            assertEquals(OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertNull(reply.payloadCopy())
            assertEquals(1, t.disconnected)
        }
    }

    @Test fun actualStatusFrameTimestampSupportsNonnegativeLongRange() = worker {
        // This baseline has frameTimestampNs/frameCount, not heartbeatTime/heartbeatCount/state.
        for (timestamp in listOf(0L, 3_000_000_000L, Long.MAX_VALUE, -1L, -2L)) {
            val t = ScriptedTransport { request ->
                successResponse(request).put("frameTimestampNs", timestamp)
                    .put("hasFrame", timestamp != 0L).put("frameCount", if (timestamp == 0L) 0 else 1).toString()
            }
            val reply = client(t).execute("status_range", "status", JSONObject())
            assertEquals("timestamp=$timestamp",
                if (timestamp >= 0) OwnerReply.Outcome.REPLIED else OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertEquals(if (timestamp >= 0) 0 else 1, t.disconnected)
        }
    }

    @Test fun lostResponsePoisonsOnlyTransportWithoutReleaseOrReplay() = worker {
        val t = ScriptedTransport { throw IOException("untrusted diagnostics") }
        val c = client(t)
        assertEquals(OwnerReply.Outcome.UNKNOWN, c.execute("input_1", "input",
            JSONObject().put("kind", "tap").put("x", 1).put("y", 2)).outcome)
        assertEquals(OwnerReply.Outcome.UNKNOWN, c.lookupGeneration().outcome)
        assertEquals(listOf("input"), t.requests.map { it.getString("op") })
        assertEquals(1, t.disconnected)
    }

    @Test fun invalidPayloadRejectedBeforeTransport() = worker {
        val t = ScriptedTransport { lookupResponse(it) }
        val c = client(t)
        for (input in listOf(JSONObject().put("timeout", 1), JSONObject().put("displayId", "3"))) {
            var rejected = false
            try { c.execute("invalid_1", "status", input) } catch (e: OwnerClientException) {
                rejected = true
                assertEquals("owner request invalid", e.message)
                assertNull(e.cause)
            }
            assertTrue(rejected)
        }
        assertTrue(t.requests.isEmpty())
    }

    @Test fun malformedAndMismatchedResponsesAreUnknownAndNotExposed() = worker {
        val changes: List<(JSONObject) -> String> = listOf(
            { r -> JSONObject(lookupResponse(r)).put("v", 1).toString() },
            { r -> JSONObject(lookupResponse(r)).put("op", "release").toString() },
            { r -> JSONObject(lookupResponse(r)).put("ok", "true").toString() },
            { r -> JSONObject(lookupResponse(r)).put("extension", true).toString() },
            { r -> JSONObject(lookupResponse(r)).apply { getJSONObject("receipt").put("digest", "wrong") }.toString() },
            { r -> JSONObject(lookupResponse(r)).apply { getJSONObject("receipt").put("generation", "foreign") }.toString() },
            { r -> JSONObject(lookupResponse(r)).apply { getJSONObject("receipt").put("state", "SUCCEEDED") }.toString() },
            { _ -> "{\"v\":2,\"op\":\"receipt\",\"ok\":true,\"ok\":false}" },
            { _ -> "{'v':2}" },
            { r -> lookupResponse(r) + "{}" },
            { _ -> JSONObject().put("v", 2).put("op", "receipt").put("ok", false).put("error", "INTERNAL")
                .put("message", dummySecret).toString() },
            { _ -> "{\"v\":2,\"op\":\"receipt\",\"ok\":false,\"error\":\"INTERNAL\",\"message\":\"" +
                dummySecret.map { "\\u%04x".format(it.code) }.joinToString("") + "\"}" },
        )
        for (change in changes) {
            val t = ScriptedTransport(change)
            val reply = client(t).lookupGeneration()
            assertEquals(OwnerReply.Outcome.UNKNOWN, reply.outcome)
            assertNull(reply.payloadCopy())
            assertEquals(1, t.disconnected)
            assertFalse(reply.toString().contains(dummySecret))
        }
    }

    @Test fun protocolFailureIsNotFabricatedExecutionSuccess() = worker {
        val t = ScriptedTransport {
            JSONObject().put("v", 2).put("op", "receipt").put("ok", false)
                .put("error", "RECEIPT_UNAVAILABLE").put("message", "lookup unconfirmed").toString()
        }
        val r = client(t).lookupGeneration()
        assertEquals(OwnerReply.Outcome.REPLIED, r.outcome)
        assertEquals(false, r.payloadCopy()!!.get("ok"))
        assertFalse(r.payloadCopy()!!.has("executed"))
        assertEquals(0, t.disconnected)
    }
}
