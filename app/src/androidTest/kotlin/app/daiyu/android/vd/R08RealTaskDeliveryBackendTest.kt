package app.daiyu.android.vd

import android.test.InstrumentationTestCase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in backend proof, not Main/UI/parent-lease acceptance. Unknown operations are never replayed. */
class R08RealTaskDeliveryBackendTest : InstrumentationTestCase() {
    fun testExplicitNonemptyTaskHandoffReleaseAndOriginalOwnerDrain() { verifyDelivery() }

    private fun verifyDelivery() {
        if (InstrumentationRegistry.getArguments().getString("r08TaskDeliveryOptIn") != "true") return
        val target = instrumentation.targetContext
        assertEquals("app.daiyu.android.synthetic", target.packageName)
        assertEquals("app.daiyu.android.synthetic.test", instrumentation.context.packageName)
        val generation = "r08_" + UUID.randomUUID().toString().replace("-", "")
        val root = File(target.noBackupFilesDir, "r08-task-delivery")
        check(root.isDirectory || root.mkdirs())
        val unresolved = File(root, "UNRESOLVED.json")
        check(unresolved.createNewFile()) { "previous_r08_delivery_unresolved_no_new_owner" }
        val directory = File(root, generation)
        check(directory.mkdir())
        fun record(label: String, value: JSONObject) {
            val file = File(directory, "$label.json")
            check(file.createNewFile()) { "r08_evidence_already_exists" }
            sync(file, value)
        }
        val metadata = JSONObject().put("generation", generation).put("production_delivery", false)
            .put("backend_only", true).put("main_ui_and_parent_lease_measured", false)
            .put("response_evidence", "complete_validated_payload_not_byte_exact_wire")
        sync(unresolved, metadata)
        record("metadata", metadata)
        val host = AndroidVirtualDisplayOwnerHost(target)
        var client: VirtualDisplayOwnerClient? = null
        fun observe(label: String): AndroidVirtualDisplayOwnerHost.Observation = host.observe().also { value ->
            record(label, JSONObject().put("lifecycle", value.lifecycle.name).put("stopped", value.stopped)
                .put("owner_pid", value.ownerPid ?: JSONObject.NULL)
                .put("launcher_exit", value.launcherExit ?: JSONObject.NULL)
                .put("owner_exit_observed", value.ownerExitObserved))
        }
        try {
            record("start-attempt", JSONObject().put("generation", generation))
            val start = host.start(generation, OwnerRunBinding("r08-backend", "task-delivery", "r08-conversation",
                generation, "r08-message", "r08-owner"))
            record("start-result", JSONObject().put("start", start.name))
            assertEquals(AndroidVirtualDisplayOwnerHost.Start.READY, start)
            val originalClient = checkNotNull(host.client)
            client = originalClient
            val original = observe("original-owner")
            assertNotNull(original.ownerPid)
            assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, original.lifecycle)
            fun call(label: String, parameters: JSONObject, operation: String? = null): JSONObject {
                record("$label-attempt", JSONObject().put("parameters", parameters).put("max_attempts", 1))
                val reply = if (operation == null) originalClient.initialize()
                    else originalClient.execute(label, operation, parameters)
                val payload = reply.payloadCopy()
                record("$label-response", JSONObject().put("outcome", reply.outcome.name)
                    .put("payload", payload ?: JSONObject.NULL))
                assertEquals("$label must not be replayed after UNKNOWN", OwnerReply.Outcome.REPLIED, reply.outcome)
                val body = checkNotNull(payload)
                assertEquals(operation ?: "initialize", body.getString("op"))
                assertEquals(generation, body.getString("generation"))
                assertEquals(true, body.get("ok"))
                assertFalse(body.optBoolean("receiptOnly", false))
                assertFalse(body.optBoolean("mutationUncertain", false))
                val receipt = body.getJSONObject("receipt")
                assertEquals(generation, receipt.getString("generation"))
                assertEquals(if (operation == null) "STARTED" else "SUCCEEDED", receipt.getString("state"))
                assertEquals(operation == null, receipt.get("generationReceipt"))
                assertEquals(if (operation == null) "" else label, receipt.getString("operationId"))
                assertEquals(operation ?: "", receipt.getString("operation"))
                if (operation != null) {
                    val result = receipt.getJSONObject("result")
                    assertEquals(true, result.get("ok"))
                    assertEquals(operation == "release", result.get("released"))
                }
                return body
            }
            val initialized = call("initialize", JSONObject())
            assertEquals(true, initialized.get("initialized"))
            val display = initialized.getInt("displayId")
            val uniqueId = initialized.getString("uniqueId")
            assertTrue(display > 0 && uniqueId.isNotEmpty())
            fun status(label: String): JSONObject = call(label, JSONObject(), "status").also { body ->
                assertEquals(display, body.getInt("displayId"))
                assertEquals(uniqueId, body.getString("uniqueId"))
                assertEquals(true, body.get("ready"))
                assertEquals(false, body.get("mutationUncertain"))
                assertEquals(false, body.get("releaseAttempted"))
            }
            val empty = status("status_before_launch")
            assertEquals(true, empty.get("sourceEmpty"))
            assertEquals(0, empty.getInt("sourceTaskCount"))
            val launched = call("launch_helper", JSONObject().put("displayId", display)
                .put("package", instrumentation.context.packageName)
                .put("component", "app.daiyu.android.synthetic.test/app.daiyu.android.vd.R08TaskDeliveryHelperActivity")
                .put("flags", 0).put("categories", JSONArray()), "launch")
            assertEquals(true, launched.get("launched"))
            assertEquals(0, launched.getInt("exitCode"))
            assertFalse(launched.optBoolean("reused", false))
            val selected = ids(launched, "taskIds")
            assertEquals(1, selected.size)
            val occupied = status("status_after_launch")
            assertEquals(false, occupied.get("sourceEmpty"))
            assertEquals(1, occupied.getInt("sourceTaskCount"))
            assertEquals(selected, ids(occupied, "retainedTaskIds"))
            assertEquals(selected, ids(occupied, "liveTaskIds"))
            assertTrue(ids(occupied, "goneTaskIds").isEmpty())
            val handedOff = call("handoff_kept", JSONObject().put("displayId", display)
                .put("taskIds", JSONArray(selected)), "handoff")
            assertEquals(true, handedOff.get("handedOff"))
            assertEquals(true, handedOff.get("sourceEmpty"))
            assertEquals(false, handedOff.get("cleanupOnly"))
            assertEquals(selected, ids(handedOff, "keptTaskIds"))
            assertTrue(ids(handedOff, "removedTaskIds").isEmpty())
            assertTrue(ids(handedOff, "goneTaskIds").isEmpty())
            val handed = status("status_after_handoff")
            assertEquals(true, handed.get("sourceEmpty"))
            assertEquals(0, handed.getInt("sourceTaskCount"))
            assertEquals(true, handed.get("handoffComplete"))
            assertEquals(false, handed.get("handoffCleanupOnly"))
            val released = call("release_original", JSONObject().put("displayId", display), "release")
            assertEquals(true, released.get("released"))
            assertEquals(display, released.getInt("displayId"))
            assertEquals(uniqueId, released.getString("uniqueId"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var drained = host.observe()
            while (drained.lifecycle != AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED && System.nanoTime() < deadline) {
                Thread.sleep(50)
                drained = host.observe()
            }
            drained = observe("after-release")
            assertEquals(original.ownerPid, drained.ownerPid)
            assertEquals(AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, drained.lifecycle)
            assertTrue(drained.stopped && drained.ownerExitObserved)
            assertEquals(0, drained.launcherExit ?: -1)
            record("PASS", metadata.put("selected_task_ids", JSONArray(selected)).put("original_owner_drained", true))
            check(unresolved.delete()) { "r08_pass_recorded_fence_removal_failed" }
        } catch (failure: Throwable) {
            try {
                record("FAIL", JSONObject().put("failure_class", failure.javaClass.name).put("replay_permitted", false))
                observe("failure-observation")
            } catch (diagnostic: Throwable) { if (diagnostic !== failure) failure.addSuppressed(diagnostic) }
            throw failure
        } finally {
            // Closing transport is not drain proof. No implicit release, shutdown, kill or new owner.
            client?.disconnectTransport()
        }
    }

    private fun ids(body: JSONObject, key: String): List<Int> {
        val values = body.getJSONArray(key)
        return (0 until values.length()).map { index ->
            val raw = values.get(index)
            require(raw is Int || raw is Long)
            val id = (raw as Number).toLong()
            require(id in 1..Int.MAX_VALUE.toLong())
            id.toInt()
        }.also { require(it.size == it.distinct().size) }
    }

    private fun sync(file: File, value: JSONObject) {
        FileOutputStream(file).use { output ->
            output.write((value.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }
}
