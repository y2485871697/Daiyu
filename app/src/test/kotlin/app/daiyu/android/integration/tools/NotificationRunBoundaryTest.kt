package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.PlatformReadBackend
import app.daiyu.android.platform.PlatformReadTool
import org.junit.Assert.*
import org.junit.Test

/** Authorization/ledger tests use a synthetic backend; no Android access. */
class NotificationRunBoundaryTest {
    private class Fixture(enabled: Boolean = true,
        val tool: PlatformReadTool = PlatformReadTool.CROSS_APP_NOTIFICATIONS) {
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = runnerOwner(message)
        val authorization = TaskAuthorization("run", "run")
        val capabilities = SessionCapabilities("run", if (enabled) setOf(tool.capability, PlatformReadTool.TIME.capability) else emptySet())
        var denied: String? = null
        var reads = 0
        var notificationReads = 0
        var duringRead: (() -> Unit)? = null
        private var recording = true
        private var connected = true
        private var epoch = 0L
        private var notificationEpoch: Long? = null
        fun recording(enabled: Boolean) { recording = enabled; epoch++ }
        fun connected(enabled: Boolean) { connected = enabled; epoch++ }
        val resources = RunDeviceResources(BatteryTool { error("legacy") }, object : PlatformReadBackend {
            override fun admission(tool: PlatformReadTool): String? {
                if (tool != PlatformReadTool.CROSS_APP_NOTIFICATIONS && tool != PlatformReadTool.NOTIFICATION_HISTORY) return null
                denied?.let { return it }
                if (!connected) return "notification_listener_not_connected"
                if (tool == PlatformReadTool.NOTIFICATION_HISTORY && !recording) return "notification_history_not_recording"
                // Synthetic counterpart of AndroidPlatformReads' run-frozen notification epoch.
                val frozen = notificationEpoch
                if (frozen == null) notificationEpoch = epoch
                else if (frozen != epoch) return "notification_access_changed"
                return null
            }
            override fun read(tool: PlatformReadTool, arguments: ToolArguments, cancelled: () -> Boolean): String {
                reads++
                val notification = tool == PlatformReadTool.CROSS_APP_NOTIFICATIONS || tool == PlatformReadTool.NOTIFICATION_HISTORY
                if (notification) notificationReads++
                duringRead?.invoke()
                return if (notification) """{"ok":true,"data":{"items":["synthetic-private"]}}"""
                    else """{"ok":true,"data":{"value":"public"}}"""
            }
        })
        val ledger = InMemoryToolLedgerStore()
        val dispatcher = ToolDispatcher(AndroidToolRegistry.create(resources).snapshot(), capabilities, ToolLedger(ledger))
        val call = ToolInvocation(ToolCallId("notification-call"), tool.toolName, "{}")
        init { resources.bind(owner, authorization, capabilities) }
        fun dispatch(task: TaskAuthorization = authorization, cancelled: () -> Boolean = { false }) = dispatcher.dispatchOwned(task, listOf(call), owner, cancelled).single().outcome
    }
    private val notificationTools = listOf(PlatformReadTool.CROSS_APP_NOTIFICATIONS, PlatformReadTool.NOTIFICATION_HISTORY)

    private fun assertEarlierReadFencesCachedNotification(f: Fixture, code: String, change: () -> Unit) {
        val original = f.dispatch() as DispatchOutcome.Confirmed
        assertFalse(original.replayed)
        assertTrue(original.payload.contains("synthetic-private"))
        val originalEntry = f.ledger.transaction { it.single() }
        var callbacks = 0
        f.duringRead = { callbacks++; change() }
        val first = ToolInvocation(ToolCallId("fresh-time"), PlatformReadTool.TIME.toolName, "{}")

        // Both plans are admitted before A's callback; only delivery-time admission can fence B.
        val results = f.dispatcher.dispatchOwned(f.authorization, listOf(first, f.call), f.owner)
        assertEquals(listOf(first.id, f.call.id), results.map { it.callId })
        val fresh = results.first().outcome as DispatchOutcome.Confirmed
        assertFalse(fresh.replayed)
        assertFalse(fresh.payload.contains("synthetic-private"))
        assertEquals(DispatchOutcome.NotExecuted(code), results.last().outcome)
        assertEquals(1, callbacks)
        assertEquals(2, f.reads)
        assertEquals(1, f.notificationReads)
        assertEquals(originalEntry, f.ledger.transaction { rows -> rows.single { it.callId == f.call.id.value } })
        assertEquals(2, f.ledger.transaction { it.size })
        f.duringRead = null
        assertEquals(DispatchOutcome.NotExecuted(code), f.dispatch())
        assertEquals(2, f.reads)
        assertEquals(originalEntry, f.ledger.transaction { rows -> rows.single { it.callId == f.call.id.value } })
    }

    @Test fun mixedBatchCachedNotificationIsFencedWhenEarlierReadClosesResources() {
        for (tool in notificationTools) {
            val f = Fixture(tool = tool)
            assertEarlierReadFencesCachedNotification(f, "run_closed") { f.resources.close() }
        }
    }

    @Test fun mixedBatchCachedNotificationIsFencedWhenEarlierReadRevokesAccess() {
        for (tool in notificationTools) {
            for (reason in listOf("notification_access_required", "notification_listener_not_connected")) {
                val f = Fixture(tool = tool)
                assertEarlierReadFencesCachedNotification(f, reason) { f.denied = reason }
                // Denied delivery leaves the original receipt intact and never rereads the backend.
                f.denied = null
                val replay = f.dispatch() as DispatchOutcome.Confirmed
                assertTrue(replay.replayed)
                assertTrue(replay.payload.contains("synthetic-private"))
                assertEquals(2, f.reads)
            }
        }
    }

    @Test fun mixedBatchCancellationDoesNotDeliverCachedNotificationOrRewriteReceipt() {
        for (tool in notificationTools) {
            val f = Fixture(tool = tool)
            assertTrue(f.dispatch() is DispatchOutcome.Confirmed)
            val original = f.ledger.transaction { it.single() }
            var cancelled = false
            f.duringRead = { cancelled = true }
            val fresh = ToolInvocation(ToolCallId("fresh-time"), PlatformReadTool.TIME.toolName, "{}")
            val results = f.dispatcher.dispatchOwned(f.authorization, listOf(fresh, f.call), f.owner, { cancelled })
            assertEquals(DispatchOutcome.NotExecuted("batch_stopped_before_execution"), results.last().outcome)
            assertEquals(1, f.notificationReads)
            assertEquals(original, f.ledger.transaction { rows -> rows.single { it.callId == f.call.id.value } })
        }
    }

    @Test fun mixedBatchRecordingOffOnStillRejectsCachedReceiptFromOldEpoch() {
        for (tool in notificationTools) {
            val f = Fixture(tool = tool)
            assertEarlierReadFencesCachedNotification(f, "notification_access_changed") {
                f.recording(false)
                f.recording(true)
            }
        }
    }

    @Test fun mixedBatchDisconnectReconnectStillRejectsCachedReceiptFromOldEpoch() {
        for (tool in notificationTools) {
            val f = Fixture(tool = tool)
            assertEarlierReadFencesCachedNotification(f, "notification_access_changed") {
                f.connected(false)
                f.connected(true)
            }
        }
    }

    @Test fun systemGrantCannotEnableRunCapabilityOrReplaceExactAuthorization() {
        val off = Fixture(false)
        assertEquals(DispatchOutcome.NotExecuted("capability_disabled"), off.dispatch()); assertEquals(0, off.reads)
        val f = Fixture()
        assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"), f.dispatch(TaskAuthorization("run", "run")))
        assertEquals(0, f.reads)
    }
    @Test fun cachedReceiptIsFencedAfterRevocationDisconnectAndStop() {
        val f = Fixture()
        assertTrue(f.dispatch() is DispatchOutcome.Confirmed)
        assertTrue((f.dispatch() as DispatchOutcome.Confirmed).replayed)
        for (reason in listOf("notification_access_required", "notification_listener_not_connected")) {
            f.denied = reason
            assertEquals(DispatchOutcome.NotExecuted(reason), f.dispatch())
        }
        f.denied = null; f.resources.close()
        assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch())
        assertEquals(1, f.reads)
    }
    @Test fun stopDuringSnapshotDoesNotPublishOldPrivatePayload() {
        val f = Fixture(); f.duringRead = { f.resources.close() }
        assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch())
        assertEquals(1, f.reads)
        assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch())
    }
    @Test fun revocationDuringSnapshotAndPreExecutionCancellationDoNotPublish() {
        val f = Fixture(); f.duringRead = { f.denied = "notification_access_required" }
        assertEquals(DispatchOutcome.NotExecuted("notification_access_required"), f.dispatch())
        val cancelled = Fixture()
        assertTrue(cancelled.dispatch(cancelled = { true }) is DispatchOutcome.NotExecuted)
        assertEquals(0, cancelled.reads)
    }
}
