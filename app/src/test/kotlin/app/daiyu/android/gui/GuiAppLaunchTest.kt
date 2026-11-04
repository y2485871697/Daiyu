package app.daiyu.android.gui

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.json.JSONObject
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.daiyu.android.RunScreenLocation
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.runtime.ReceiptBatchToolExecutor
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.RunOrdinaryGuiTools
import app.daiyu.android.tasksurface.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [GuiAppLaunchTest.CopyingWindowNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GuiAppLaunchTest {
    @Implements(AccessibilityNodeInfo::class)
    class CopyingWindowNodeShadow : ShadowAccessibilityNodeInfo() {
        @Implementation
        public override fun getWindow(): AccessibilityWindowInfo? =
            super.getWindow()?.let { AccessibilityWindowInfo.obtain(it) }
    }

    private lateinit var service: GuiAccessibilityService
    private lateinit var originalAccess: GuiRuntimeAccess
    private lateinit var platform: Platform
    private val scopes = mutableListOf<RunOrdinaryGuiTools>()
    private val target = "com.eg.android.AlipayGphone"

    private class Platform : GuiRuntimeAccess {
        var enabled = true
        var unlocked = true
        var rootReads = 0
        var resolutions = 0
        var launches = 0
        var nodeActions = 0
        var launchable = true
        var resolvedPackage: String? = null
        var beforeResolve: (() -> Unit)? = null
        var failure: Exception? = null
        var delivered: Intent? = null
        override fun enabled(current: GuiAccessibilityService) = enabled
        override fun unlocked(current: GuiAccessibilityService) = unlocked
        var rootPackage: String? = null
        var rootWindowType = AccessibilityWindowInfo.TYPE_APPLICATION
        override fun root(current: GuiAccessibilityService): AccessibilityNodeInfo? {
            rootReads++
            val pkg = rootPackage ?: return null
            val window = AccessibilityWindowInfo.obtain()
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setId", ClassParameter.from(Int::class.javaPrimitiveType!!, 7 + launches))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setType", ClassParameter.from(Int::class.javaPrimitiveType!!, rootWindowType))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setActive", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setDisplayId", ClassParameter.from(Int::class.javaPrimitiveType!!, 0))
            return AccessibilityNodeInfo.obtain().apply {
                packageName = pkg; className = "View"; text = "application"
                isEnabled = true; isVisibleToUser = true; isClickable = true
                setBoundsInScreen(Rect(0, 0, 100, 100))
                ReflectionHelpers.setField(this, "mWindowId", 7 + launches)
                shadowOf(this).setAccessibilityWindowInfo(window)
                shadowOf(this).setRefreshReturnValue(true)
                shadowOf(this).setOnPerformActionListener { _, _ -> nodeActions++; true }
            }
        }
        override fun launchIntent(current: GuiAccessibilityService, packageName: String): Intent? {
            resolutions++
            beforeResolve?.invoke()
            return if (!launchable) null else Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(resolvedPackage ?: packageName, "$packageName.MainActivity"))
        }
        override fun startActivity(current: GuiAccessibilityService, intent: Intent) {
            launches++
            failure?.let { throw it }
            delivered = Intent(intent)
            rootPackage = intent.component!!.packageName
        }
        var captures = 0
        var holdCapture = false
        var heldFailure: ((Int) -> Unit)? = null
        override fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit) {
            captures++
            if (holdCapture) { heldFailure = failure; return }
            success(object : GuiCaptureFrame {
                override fun encode(observationId: String) = GuiResult.Value(GuiScreenshot(observationId, 1, 1, byteArrayOf(1)))
                override fun dispose(request: GuiCaptureRequest<GuiScreenshot>) { request.resourcesDisposed() }
            })
        }
    }

    @Before fun setUp() {
        originalAccess = GuiRuntime.access
        platform = Platform()
        GuiRuntime.access = platform
        service = Robolectric.buildService(GuiAccessibilityService::class.java).create().get()
        GuiRuntime.connected(service)
    }
    @After fun tearDown() {
        scopes.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
        GuiRuntime.disconnected(service)
        GuiRuntime.access = originalAccess
    }

    private fun owner(): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = Unit
            override fun cancel() = Unit
        } }, object : ConversationStore { override fun save(message: Message) = Unit }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = executor)) {}.run(Message(MessageId("a"), ConversationId("c"),
            RunId("r"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
    private inner class Fixture(preference: TaskSurfacePreference = TaskSurfacePreference.FOREGROUND,
        answer: TaskSurface? = TaskSurface.FOREGROUND) {
        val owner = owner()
        val auth = TaskAuthorization("s", "r", emptySet())
        var questions = 0
        val location = RunScreenLocation(owner, TaskSurfaceStore(object : TaskSurfacePersistence {
            override fun read() = "task-surface-v1\n${preference.name}\n"
            override fun write(record: String) = error("launch must not change the saved preference")
        }), service.packageName, { true }) { questions++; it.answer(answer) }
        val scope = RunOrdinaryGuiTools(owner, auth, location).also { scopes += it }
        val dispatcher = ToolDispatcher(ToolRegistry().apply { scope.tools.forEach(::register) }.snapshot(),
            SessionCapabilities("s", setOf(GuiScreenToolFactory.CAPABILITY)), ToolLedger(InMemoryToolLedgerStore()))
        fun callTool(name: String, arguments: String, id: String): DispatchOutcome = worker {
            dispatcher.dispatchOwned(auth, listOf(ToolInvocation(ToolCallId(id), name, arguments)), owner) { false }.single().outcome
        }
        fun call(id: String = "launch", task: TaskAuthorization = auth, token: RunExecutionOwner = owner,
            cancelled: () -> Boolean = { false }): DispatchOutcome = worker {
            dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId(id), "launch_app",
                "{\"package_name\":\"$target\"}")), token, cancelled).single().outcome
        }
    }
    private fun <T> worker(block: () -> T): T {
        val future = FutureTask(block)
        val thread = Thread(future).apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        val result = future.get(1, TimeUnit.SECONDS)
        thread.join(1000)
        assertFalse(thread.isAlive)
        return result
    }

    @Test fun relaunchingTheSameAppAfterAnotherAppActuallyLaunchesAgain() {
        val f = Fixture()
        assertTrue(f.call("first") is DispatchOutcome.Confirmed)
        assertTrue(f.callTool("launch_app", "{\"package_name\":\"other.app\"}", "other") is DispatchOutcome.Confirmed)
        assertTrue(f.call("again") is DispatchOutcome.Confirmed)
        assertEquals(3, platform.launches)
        assertEquals(target, platform.rootPackage)
        assertTrue((f.call("again") as DispatchOutcome.Confirmed).replayed)
        assertEquals(3, platform.launches)
    }
    @Test fun omittedTargetStaysBoundToTheObservedApplicationForNodeActionsAndScreenshots() {
        val f = Fixture()
        platform.rootPackage = target
        val observed = f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"$target\"}", "observe") as DispatchOutcome.Confirmed
        val id = JSONObject(observed.payload).getString("observation_id")
        assertTrue(f.callTool("screen", "{\"operation\":\"screenshot\",\"observation_id\":\"$id\"}", "image") is DispatchOutcome.Confirmed)
        assertTrue(f.callTool("screen_action", "{\"operation\":\"tap\",\"node_id\":0,\"observation_id\":\"$id\"}", "tap") is DispatchOutcome.Confirmed)
        assertEquals(1, platform.captures)
    }
    @Test fun recyclingReturnedWindowPreservesTheLaunchFixtureNodeWindow() {
        platform.rootPackage = target
        val root = checkNotNull(platform.root(service))
        try {
            val first = checkNotNull(root.window)
            try { assertEquals(7, first.id) } finally { first.recycle() }
            assertEquals(7, root.windowId)
            val second = checkNotNull(root.window)
            try {
                assertEquals(7, second.id)
                assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, second.type)
                assertTrue(second.isActive)
            } finally { second.recycle() }
        } finally { root.recycle() }
    }

    @Test fun repeatedExternalChooserObservationsSurviveForeignContentAndDeliverOnlyOneTap() {
        val f = Fixture()
        assertTrue(f.call() is DispatchOutcome.Confirmed)
        platform.rootPackage = "com.vivo.appfilter"
        fun observe(id: String): String {
            val result = f.callTool("screen", JSONObject().put("operation", "observe").toString(), id) as DispatchOutcome.Confirmed
            return JSONObject(result.payload).getString("observation_id")
        }
        fun foreignContent() {
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = service.packageName
                ReflectionHelpers.callInstanceMethod<Unit>(this, "setWindowId",
                    ClassParameter.from(Int::class.javaPrimitiveType!!, 7))
            })
        }
        val first = observe("first")
        foreignContent()
        val fresh = observe("fresh")
        assertNotEquals(first, fresh)
        foreignContent()
        val args = JSONObject().put("operation", "tap").put("node_id", 0).put("observation_id", fresh).toString()
        val result = f.callTool("screen_action", args, "tap")
        assertTrue(result.toString(), result is DispatchOutcome.Confirmed)
        assertFalse((result as DispatchOutcome.Confirmed).isError)
        assertEquals(1, platform.launches); assertEquals(1, platform.nodeActions)
        assertTrue((f.callTool("screen_action", args, "tap") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, platform.nodeActions)
        assertEquals(0, platform.captures)
    }

    @Test fun ordinaryLaunchObservesSystemChooserOnlyAfterReleasingTheExplicitAppTarget() {
        val f = Fixture()
        assertTrue(f.call() is DispatchOutcome.Confirmed)
        platform.rootPackage = "system.dialog"
        platform.rootWindowType = AccessibilityWindowInfo.TYPE_SYSTEM
        val targeted = f.callTool("screen", """{"operation":"observe","target_package":"$target"}""", "targeted")
        assertEquals(DispatchOutcome.NotExecuted("TARGET_PACKAGE_MISMATCH_NOT_EXECUTED"), targeted)
        val observed = f.callTool("screen", """{"operation":"observe"}""", "chooser") as DispatchOutcome.Confirmed
        val payload = JSONObject(observed.payload)
        assertEquals("system.dialog", payload.getString("package_name"))
        assertEquals(AccessibilityWindowInfo.TYPE_SYSTEM, payload.getInt("window_type"))
        val args = JSONObject().put("operation", "tap").put("node_id", 0)
            .put("observation_id", payload.getString("observation_id")).toString()
        val delivered = f.callTool("screen_action", args, "choose") as DispatchOutcome.Confirmed
        assertFalse(delivered.isError)
        assertEquals(1, platform.nodeActions)
        assertTrue((f.callTool("screen_action", args, "choose") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, platform.nodeActions); assertEquals(0, platform.captures)
    }

    @Test fun implicitSelfScreenshotRetainsNarrowTargetAndDoesNotAskForExternalLocation() {
        val f = Fixture(TaskSurfacePreference.ASK)
        platform.rootPackage = service.packageName
        assertTrue(f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"${service.packageName}\"}", "self") is DispatchOutcome.Confirmed)
        val image = f.callTool("screen", "{\"operation\":\"screenshot\"}", "visual") as DispatchOutcome.Confirmed
        assertTrue(JSONObject(image.payload).has("capture_id"))
        assertEquals(0, f.questions)
        assertEquals(1, platform.captures)
    }
    @Test fun mismatchedTargetAndObservationNeverReplaceTheHeldSession() {
        val f = Fixture()
        platform.rootPackage = target
        val first = f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"$target\"}", "observe") as DispatchOutcome.Confirmed
        val id = JSONObject(first.payload).getString("observation_id")
        assertEquals(DispatchOutcome.NotExecuted("OBSERVATION_TARGET_MISMATCH_NOT_EXECUTED"),
            f.callTool("screen_action", "{\"operation\":\"tap\",\"node_id\":0,\"observation_id\":\"$id\",\"target_package\":\"other.app\"}", "wrong-target"))
        assertEquals(DispatchOutcome.NotExecuted("OBSERVATION_ID_MISMATCH"),
            f.callTool("screen_action", "{\"operation\":\"tap\",\"node_id\":0,\"observation_id\":\"wrong\"}", "wrong-id"))
        assertTrue(f.callTool("screen_action", "{\"operation\":\"tap\",\"node_id\":0,\"observation_id\":\"$id\"}", "correct") is DispatchOutcome.Confirmed)
    }
    @Test fun revocationRejectsOldObservationAndFreshObservationCanRecoverAfterReconnection() {
        val f = Fixture()
        platform.rootPackage = target
        val first = f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"$target\"}", "before") as DispatchOutcome.Confirmed
        val id = JSONObject(first.payload).getString("observation_id")
        platform.enabled = false
        assertTrue(f.callTool("screen_action", "{\"operation\":\"tap\",\"node_id\":0,\"observation_id\":\"$id\"}", "revoked") is DispatchOutcome.NotExecuted)
        assertTrue(f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"other.app\"}", "still-disabled") is DispatchOutcome.NotExecuted)
        platform.enabled = true
        GuiRuntime.connected(service)
        platform.rootPackage = target
        val fresh = f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"$target\"}", "recovered") as DispatchOutcome.Confirmed
        assertNotEquals(id, JSONObject(fresh.payload).getString("observation_id"))
        assertEquals(DispatchOutcome.NotExecuted("OBSERVATION_ID_MISMATCH"),
            f.callTool("screen_action", "{\"operation\":\"tap\",\"node_id\":0,\"observation_id\":\"$id\"}", "old"))
    }

    @Test fun defaultScreenshotTargetSurvivesRevocationAndPendingCaptureDrain() {
        val f = Fixture()
        platform.rootPackage = target
        val observed = f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"$target\"}", "before-drain") as DispatchOutcome.Confirmed
        val id = JSONObject(observed.payload).getString("observation_id")
        val held = ReflectionHelpers.getField<GuiRunSession>(f.scope, "current")
        platform.holdCapture = true
        assertTrue(GuiRuntime.approveReads(held.lease, GuiReadApproval(0, 1)) is GuiResult.Value)
        val pending = GuiRuntime.screenshot(held.lease, id)
        assertFalse(pending.isDone)
        GuiRuntime.disconnected(service); GuiRuntime.connected(service)
        assertEquals(DispatchOutcome.NotExecuted("FOREGROUND_DRAIN_PENDING_NOT_EXECUTED"),
            f.callTool("screen", "{\"operation\":\"screenshot\"}", "wait-for-drain"))
        platform.heldFailure!!(1)
        assertTrue(held.releaseConfirmed)
        platform.holdCapture = false; platform.rootPackage = "other.app"
        assertEquals(DispatchOutcome.NotExecuted("TARGET_PACKAGE_MISMATCH_NOT_EXECUTED"),
            f.callTool("screen", "{\"operation\":\"screenshot\"}", "after-drain"))
        assertEquals(1, platform.captures)
    }
    @Test fun sessionUnknownCannotBeHiddenByRevocationAndFreshReadOrLaunch() {
        val f = Fixture()
        platform.rootPackage = target
        assertTrue(f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"$target\"}", "before-unknown") is DispatchOutcome.Confirmed)
        val held = ReflectionHelpers.getField<GuiRunSession>(f.scope, "current")
        held.onCentralUnknown()
        GuiRuntime.disconnected(service); GuiRuntime.connected(service)
        val reads = platform.rootReads
        assertEquals(DispatchOutcome.NotExecuted("GUI_PREVIOUS_OPERATION_UNCONFIRMED"),
            f.callTool("screen", "{\"operation\":\"observe\"}", "fresh"))
        assertEquals(DispatchOutcome.NotExecuted("GUI_PREVIOUS_OPERATION_UNCONFIRMED"), f.call("launch-after-unknown"))
        assertEquals(reads, platform.rootReads)
        assertEquals(0, platform.launches)
        assertSame(held, ReflectionHelpers.getField<GuiRunSession>(f.scope, "current"))
    }
    @Test fun advertisedLaunchIsExactWriteAndPreparingNeverTouchesAndroid() {
        val f = Fixture()
        val tool = f.scope.tools.single { it.definition.name == "launch_app" }
        assertEquals(ToolEffect.WRITE, tool.definition.effect)
        assertEquals(GuiScreenToolFactory.CAPABILITY, tool.definition.capability)
        val prepared = tool.prepare(tool.definition.schema.parse("{\"package_name\":\"$target\"}"))
        assertEquals(GuiAppLaunch(target).effect(GuiOwner("s", "r")), prepared.writeEffect)
        assertEquals(0, platform.resolutions)
        assertEquals(0, platform.launches)
        assertEquals(0, f.questions)
        listOf("bad package", "https://example.com", "com.foo;input").forEach { invalid ->
            try { GuiAppLaunch(invalid); fail(invalid) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun nativeLaunchReceiptNeverClaimsTheTargetWindowWasVerified() {
        val f = Fixture()
        val confirmed = f.call() as DispatchOutcome.Confirmed
        val payload = JSONObject(confirmed.payload)
        assertFalse(confirmed.isError)
        assertEquals("PLATFORM_LAUNCH_ACCEPTED_NOT_VERIFIED", payload.getString("status"))
        assertTrue(payload.getBoolean("launch_request_accepted"))
        assertEquals("NOT_VERIFIED", payload.getString("target_window_state"))
        assertTrue(payload.getBoolean("verification_required")); assertFalse(payload.getBoolean("verified_ui_result"))
        assertTrue(payload.getString("next_action").contains("不代表应用界面或目标页面已显示"))
        assertTrue(payload.getString("next_action").contains("窗口读取失败应停止屏幕操作"))
        val replay = f.call() as DispatchOutcome.Confirmed
        assertTrue(replay.replayed); assertEquals(confirmed.payload, replay.payload)
        assertEquals(1, platform.launches); assertEquals(0, platform.rootReads); assertEquals(0, platform.captures)
        platform.rootPackage = null
        assertTrue(f.callTool("screen", """{"operation":"observe"}""", "unreadable") is DispatchOutcome.NotExecuted)
        assertTrue(platform.rootReads > 0); assertEquals(0, platform.nodeActions); assertEquals(0, platform.captures)
        assertEquals(confirmed.payload, (f.call() as DispatchOutcome.Confirmed).payload)
        assertEquals(1, platform.launches)
    }

    @Test fun ordinaryLaunchFromAssistantNeedsNoObservationAndUsesCentralLedgerOnce() {
        val f = Fixture()
        assertTrue(f.call() is DispatchOutcome.Confirmed)
        assertTrue(f.call() is DispatchOutcome.Confirmed)
        assertEquals(1, platform.launches)
        assertEquals(0, platform.rootReads)
        assertEquals(target, platform.delivered!!.component!!.packageName)
        assertTrue(platform.delivered!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
    @Test fun selfHomeRejectionDoesNotPreventNativeLaunchThenFreshExternalObserveInSameDispatcher() {
        val f = Fixture()
        platform.rootPackage = service.packageName
        val self = f.callTool("screen", "{\"operation\":\"observe\",\"target_package\":\"${service.packageName}\"}", "self") as DispatchOutcome.Confirmed
        val selfId = JSONObject(self.payload).getString("observation_id")
        assertTrue(f.callTool("screen_action", "{\"operation\":\"home\",\"observation_id\":\"$selfId\",\"target_package\":\"${service.packageName}\"}", "home") is DispatchOutcome.NotExecuted)
        assertTrue(f.call() is DispatchOutcome.Confirmed)
        val external = f.callTool("screen", "{\"operation\":\"observe\"}", "external") as DispatchOutcome.Confirmed
        val observed = JSONObject(external.payload)
        assertEquals(target, observed.getString("package_name"))
        assertNotEquals(selfId, observed.getString("observation_id"))
        assertEquals(1, platform.launches)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }
    @Test fun externalLaunchStillUsesTheBaseLocationAfterASelfRoute() {
        val f = Fixture(TaskSurfacePreference.BACKGROUND)
        assertTrue(f.location.route(service.packageName, false) { false } is TaskSurfaceDecision.Ready)
        assertTrue(f.call() is DispatchOutcome.NotExecuted)
        assertEquals(0, platform.resolutions)
        assertEquals(0, platform.launches)
    }
    @Test fun askIsAnsweredThroughExistingLocationHostWithoutChangingPreferences() {
        val f = Fixture(TaskSurfacePreference.ASK)
        assertTrue(f.call() is DispatchOutcome.Confirmed)
        assertEquals(1, f.questions)
        assertEquals(1, platform.launches)
    }
    @Test fun cancelledLocationCannotBeBypassedByEarlierSelfRouting() {
        val f = Fixture(TaskSurfacePreference.ASK, answer = null)
        f.location.route(service.packageName, false) { false }
        assertTrue(f.call() is DispatchOutcome.NotExecuted)
        assertEquals(1, f.questions)
        assertEquals(0, platform.resolutions)
        assertEquals(0, platform.launches)
    }
    @Test fun foregroundBusyDoesNotResolveLaunchOrStealAnotherOwner() {
        val held = (GuiRuntime.openForUser(GuiOwner("other", "other")) as GuiResult.Value).value
        try {
            assertTrue(Fixture().call() is DispatchOutcome.NotExecuted)
            assertEquals(0, platform.resolutions)
            assertEquals(0, platform.launches)
        } finally { held.close() }
    }
    @Test fun forgedOwnerAuthorizationCancellationAndClosedScopeNeverLaunch() {
        val f = Fixture()
        assertTrue(f.call("auth", task = TaskAuthorization("s", "r", emptySet())) is DispatchOutcome.NotExecuted)
        assertTrue(f.call("owner", token = owner()) is DispatchOutcome.NotExecuted)
        assertTrue(f.call("cancel", cancelled = { true }) is DispatchOutcome.NotExecuted)
        f.scope.close()
        assertTrue(f.call("closed") is DispatchOutcome.NotExecuted)
        assertEquals(0, platform.launches)
    }
    @Test fun lockAndPermissionRevocationDuringResolutionAreRecheckedBeforeLaunch() {
        val f = Fixture()
        platform.beforeResolve = { platform.unlocked = false }
        assertTrue(f.call("locked") is DispatchOutcome.NotExecuted)
        assertEquals(0, platform.launches)
        f.scope.close()
        platform.unlocked = true
        val permission = Fixture()
        platform.beforeResolve = { platform.enabled = false }
        assertTrue(permission.call("permission") is DispatchOutcome.NotExecuted)
        assertEquals(2, platform.resolutions)
        assertFalse(platform.enabled)
        assertEquals(0, platform.launches)
    }
    @Test fun cancellationDuringPackageResolutionNeverReachesStartActivity() {
        val f = Fixture()
        var cancelled = false
        platform.beforeResolve = { cancelled = true }
        assertTrue(f.call(cancelled = { cancelled }) is DispatchOutcome.NotExecuted)
        assertEquals(1, platform.resolutions)
        assertEquals(0, platform.launches)
    }
    @Test fun missingOrDifferentResolvedApplicationNeverStarts() {
        val f = Fixture()
        platform.launchable = false
        assertTrue(f.call("missing") is DispatchOutcome.NotExecuted)
        platform.launchable = true
        platform.resolvedPackage = "different.app"
        assertTrue(f.call("different") is DispatchOutcome.NotExecuted)
        assertEquals(0, platform.launches)
    }
    @Test fun unknownLaunchIsNotRetriedWithANewCallId() {
        val f = Fixture()
        platform.failure = IllegalStateException("unknown binder delivery")
        assertEquals(DispatchOutcome.Unknown("APPLICATION_LAUNCH_UNCONFIRMED"), f.call("first"))
        assertEquals(1, platform.launches)
        platform.failure = null
        assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), f.call("second"))
        assertEquals(1, platform.resolutions)
        assertEquals(1, platform.launches)
    }
    @Test fun launchRequiresExactLeaseTargetAndRetiresOldObservations() {
        val session = (GuiRuntime.openForUser(GuiOwner("s", "r"), target) as GuiResult.Value).value
        try {
            assertTrue(GuiRuntime.launchApp(session.lease, GuiAppLaunch("different.app")) is GuiResult.Rejected)
            val observation = GuiObservation("old", target, 7, 0, 0, emptyList(), true)
            session.lease.observation = observation
            session.lease.actions += GuiAction("old", GuiOperation.HOME)
            assertTrue(GuiRuntime.launchApp(session.lease, GuiAppLaunch(target)) is GuiResult.Value)
            assertNull(session.lease.observation)
            assertTrue(session.lease.actions.isEmpty())
            assertEquals(1, platform.launches)
        } finally { session.close() }
    }
}
