package app.daiyu.android.gui

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import app.daiyu.android.tasksurface.TaskSurfaceSelfTarget
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises the real runtime/gate/traversal/callback path, not a second model of its policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class GuiRuntimeScreenshotTest {
    private lateinit var service: GuiAccessibilityService
    private lateinit var session: GuiRunSession
    private lateinit var platform: Platform
    private lateinit var worker: QueuedWorker
    private lateinit var originalAccess: GuiRuntimeAccess
    private lateinit var originalEncoder: Executor

    private class QueuedWorker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll(deliver: Boolean = true) {
            while (tasks.isNotEmpty()) {
                val task = tasks.removeFirst()
                var failure: Throwable? = null
                val thread = Thread { try { task.run() } catch (caught: Throwable) { failure = caught } }
                thread.start(); thread.join(5_000)
                check(!thread.isAlive) { "encoder did not finish" }
                failure?.let { throw AssertionError("encoder task failed", it) }
            }
            if (deliver) shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Frame : GuiCaptureFrame {
        var encoded = 0
        var encodeFailure = false
        var disposalFailure = false
        var disposalRequest: GuiCaptureRequest<GuiScreenshot>? = null
        val disposed = mutableListOf<String>()
        override fun encode(observationId: String): GuiResult<GuiScreenshot> {
            check(Looper.myLooper() !== Looper.getMainLooper())
            encoded++
            if (encodeFailure) error("encoding failed")
            return GuiResult.Value(GuiScreenshot(observationId, 1, 1, byteArrayOf(1)))
        }
        override fun dispose(request: GuiCaptureRequest<GuiScreenshot>) {
            check(Looper.myLooper() === Looper.getMainLooper())
            disposalRequest = request
            request.disposeSuccessResources(
                { disposed += "software"; if (disposalFailure) error("disposal failed") },
                { disposed += "hardware" }, { disposed += "buffer" })
        }
    }

    private class Platform : GuiRuntimeAccess {
        var pkg = "external.app"
        var windowId = 7
        var displayId = 0
        var active = true
        var windowType = AccessibilityWindowInfo.TYPE_APPLICATION
        var nodeActions = 0
        var label = "before"
        var missing = false
        var throwOnSubmit = false
        var permissionEnabled = true
        var screenUnlocked = true
        var rootReads = 0
        val callbacks = mutableListOf<Pair<(GuiCaptureFrame) -> Unit, (Int) -> Unit>>()
        override fun enabled(current: GuiAccessibilityService) = permissionEnabled
        override fun unlocked(current: GuiAccessibilityService) = screenUnlocked
        override fun root(current: GuiAccessibilityService): AccessibilityNodeInfo? {
            rootReads++
            if (missing) return null
            val window = AccessibilityWindowInfo.obtain()
            // Android's hidden setters initialize real public-getter state; traversal/admission
            // still run unchanged. Only the platform source/capture and worker queue are controlled.
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setId", ClassParameter.from(Int::class.javaPrimitiveType!!, windowId))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setType", ClassParameter.from(Int::class.javaPrimitiveType!!, windowType))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setActive", ClassParameter.from(Boolean::class.javaPrimitiveType!!, active))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setDisplayId", ClassParameter.from(Int::class.javaPrimitiveType!!, displayId))
            fun node(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                packageName = pkg; className = "View"; this.text = text
                isEnabled = true; isVisibleToUser = true; isClickable = true
                setBoundsInScreen(Rect(0, 0, 100, 100))
                ReflectionHelpers.setField(this, "mWindowId", windowId)
                shadowOf(this).setAccessibilityWindowInfo(window)
                shadowOf(this).setRefreshReturnValue(true)
                shadowOf(this).setOnPerformActionListener { _, _ -> nodeActions++; true }
            }
            val root = node("root")
            shadowOf(root).addChild(node(label))
            return root
        }
        override fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit) {
            callbacks += success to failure
            if (throwOnSubmit) error("accepted but transport threw")
        }
        fun success(frame: Frame = Frame()): Frame { callbacks.removeAt(0).first(frame); return frame }
    }

    @Before fun setUp() {
        originalAccess = GuiRuntime.access; originalEncoder = GuiRuntime.encoder
        platform = Platform(); worker = QueuedWorker()
        GuiRuntime.access = platform; GuiRuntime.encoder = worker
        service = Robolectric.buildService(GuiAccessibilityService::class.java).create().get()
        GuiRuntime.connected(service)
        session = (GuiRuntime.openForUser(GuiOwner("session", "run")) as GuiResult.Value).value
        session.approveReadsFromUser(GuiReadApproval(3, 1))
    }

    @After fun tearDown() {
        session.close()
        while (platform.callbacks.isNotEmpty()) platform.callbacks.removeAt(0).second(1)
        worker.runAll()
        GuiRuntime.disconnected(service)
        GuiRuntime.access = originalAccess; GuiRuntime.encoder = originalEncoder
    }

    private fun observe(): GuiObservation = (session.previewFromUser() as GuiResult.Value).value
    private fun approve(observation: GuiObservation): GuiAction =
        GuiAction(observation.observationId, GuiOperation.TAP, 1).also {
            assertTrue(session.approveExactActionFromUser(it) is GuiResult.Value)
        }
    private fun screenshot(observation: GuiObservation) = GuiRuntime.screenshot(session.lease, observation.observationId)
    private fun rejected(result: GuiResult<*>, code: String) { assertEquals(GuiResult.Rejected(code), result) }
    private fun assertDisposed(frame: Frame) { assertEquals(listOf("software", "hardware", "buffer"), frame.disposed) }

    private fun openTarget(target: String?, token: TaskSurfaceSelfTarget? = null, surfaceOwner: Any? = null) {
        session.close()
        session = (GuiRuntime.openForUser(GuiOwner("target-session", "target-run"), target, token, surfaceOwner) as GuiResult.Value).value
        assertTrue(session.approveReadsFromUser(GuiReadApproval(3, 1)) is GuiResult.Value)
    }
    private fun openSelf(owner: Any = Any(), current: () -> Boolean = { true }): TaskSurfaceSelfTarget {
        val token = TaskSurfaceSelfTarget.foreground(service.packageName, owner, current)
        platform.pkg = service.packageName
        openTarget(service.packageName, token, owner)
        return token
    }
    private fun assertNoActions() {
        assertEquals(0, platform.nodeActions)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun liveSelfTokenAllowsOnlyTheOwnApplicationTreeExactNodeActionAndScreenshot() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        assertEquals(service.packageName, observation.packageName)
        assertTrue(session.validateExactActionFromUser(action) is GuiResult.Value)
        val future = screenshot(observation); val frame = platform.success(); worker.runAll()
        assertTrue(future.join() is GuiResult.Value); assertDisposed(frame)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun missingTargetRemainsExternalEvenWhenALiveSelfTokenWasSupplied() {
        val owner = Any()
        val token = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { true }
        openTarget(null, token, owner)
        platform.pkg = service.packageName
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        platform.pkg = "external.app"
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun ownPackageCannotOpenWithMissingWrongOwnerWrongPackageOrRevokedToken() {
        session.close()
        val owner = Any()
        val valid = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { true }
        val wrongPackage = TaskSurfaceSelfTarget.foreground("another.app", owner) { true }
        val ended = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { false }
        val guiOwner = GuiOwner("new", "run")
        listOf(
            null to owner, valid to null, valid to Any(), wrongPackage to owner, ended to owner,
        ).forEach { (token, suppliedOwner) ->
            rejected(GuiRuntime.openForUser(guiOwner, service.packageName, token, suppliedOwner), "SELF_APP_TARGET_LOST")
        }
        assertEquals(0, platform.rootReads)
        assertNoActions()
    }

    @Test fun runtimeAlsoRejectsMalformedTrustedTargetsBeforeAcquiringOrReading() {
        session.close()
        listOf("", "external", "external..app", " external.app", "a." + "b".repeat(255)).forEach { target ->
            rejected(GuiRuntime.openForUser(GuiOwner("target", "run"), target), "INVALID_TARGET_PACKAGE_NOT_EXECUTED")
        }
        assertEquals(0, platform.rootReads); assertNoActions()
    }

    @Test fun explicitExternalTargetMatchesExactlyAndDoesNotFallThroughToAnotherOrOwnPackage() {
        openTarget("chosen.app")
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        platform.pkg = service.packageName
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        platform.pkg = "chosen.app"
        val observation = observe()
        platform.pkg = "external.app"
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        rejected(GuiRuntime.validateExactTarget(session.lease, GuiAction(observation.observationId, GuiOperation.TAP, 1)),
            "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun explicitExternalStillRequiresAnActiveDefaultDisplayApplication() {
        openTarget("external.app")
        platform.active = false
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        platform.active = true; platform.windowType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        platform.windowType = AccessibilityWindowInfo.TYPE_APPLICATION; platform.displayId = 1
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfPackageLossWithoutAnEventRefusesOldNodeAndScreenshotWithoutAnyFallback() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.pkg = "external.app"
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        rejected(screenshot(observation).join(), "SELF_APP_TARGET_LOST")
        assertNull(session.lease.observation); assertTrue(session.lease.actions.isEmpty())
        assertEquals(1, session.lease.screenshots)
        // Returning to self does not resurrect the old node/id/approval.
        platform.pkg = service.packageName
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfPackageLossAfterAnEventReportsLossRatherThanExecutingAStaleNode() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.pkg = "external.app"; GuiRuntime.changed(service)
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        rejected(screenshot(observation).join(), "SELF_APP_TARGET_LOST")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfMissingInactiveOverlayAndOtherDisplayAllFailClosed() {
        listOf<(Platform) -> Unit>(
            { it.missing = true }, { it.active = false },
            { it.windowType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }, { it.displayId = 1 },
        ).forEach { loseTarget ->
            platform.missing = false; platform.active = true
            platform.windowType = AccessibilityWindowInfo.TYPE_APPLICATION; platform.displayId = 0
            openSelf()
            val observation = observe(); val action = approve(observation)
            loseTarget(platform)
            rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
            rejected(screenshot(observation).join(), "SELF_APP_TARGET_LOST")
            assertTrue(platform.callbacks.isEmpty()); assertNoActions()
        }
    }

    @Test fun selfTokenAndGuardedAdmissionAreRecheckedOnTheOriginalLease() {
        val owner = Any()
        var current = true
        var admitted = true
        val token = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { current }.guardedBy { admitted }
        platform.pkg = service.packageName
        openTarget(service.packageName, token, owner)
        val observation = observe(); val action = approve(observation)
        admitted = false
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        rejected(session.previewFromUser(), "SELF_APP_TARGET_LOST")
        rejected(screenshot(observation).join(), "SELF_APP_TARGET_LOST")
        admitted = true; current = false // run.end / original surface owner is no longer current
        rejected(session.approveReadsFromUser(GuiReadApproval(1, 1)), "SELF_APP_TARGET_LOST")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfTokenDoesNotBypassScreenLockOrSystemPermissionRevocation() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.screenUnlocked = false
        rejected(GuiRuntime.action(session.lease, action), "SCREEN_LOCKED_NOT_EXECUTED")
        rejected(screenshot(observation).join(), "SCREEN_LOCKED_NOT_EXECUTED")
        platform.screenUnlocked = true; platform.permissionEnabled = false
        rejected(GuiRuntime.action(session.lease, action), "SERVICE_PERMISSION_REVOKED_NOT_EXECUTED")
        assertFalse(session.lease.valid)
        rejected(screenshot(observation).join(), "SERVICE_DISCONNECTED_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfLossWhileEncodingIsQueuedRejectsDeliveryButDrainsTheSameLease() {
        openSelf()
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success()
        platform.pkg = "external.app"
        worker.runAll()
        rejected(future.join(), "SELF_APP_TARGET_LOST"); assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed)
        assertNoActions()
    }

    @Test fun revokedSelfTokenBeforeCallbackDoesNotEncodeOrReleaseBeforeDisposal() {
        var current = true
        openSelf(current = { current })
        val observation = observe(); val future = screenshot(observation)
        current = false
        val frame = platform.success()
        rejected(future.join(), "SELF_APP_TARGET_LOST")
        assertEquals(0, frame.encoded); assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed); assertNoActions()
    }

    @Test fun selfTokenRevocationAfterEncodingStillRejectsMainThreadDelivery() {
        var current = true
        openSelf(current = { current })
        val observation = observe(); val future = screenshot(observation); val frame = platform.success()
        worker.runAll(deliver = false)
        current = false
        shadowOf(Looper.getMainLooper()).idle()
        rejected(future.join(), "SELF_APP_TARGET_LOST"); assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed); assertNoActions()
    }

    @Test fun selfGlobalActionsAreRejectedAtPreparationApprovalValidationAndExecution() {
        openSelf()
        val observation = observe()
        listOf(GuiOperation.BACK, GuiOperation.HOME, GuiOperation.RECENTS, GuiOperation.NOTIFICATIONS).forEach { operation ->
            val action = GuiAction(observation.observationId, operation)
            val code = "SELF_APP_GLOBAL_ACTION_NOT_ALLOWED_NOT_EXECUTED"
            rejected(session.approveExactActionFromUser(action), code)
            rejected(session.validateExactActionFromUser(action), code)
            rejected(GuiRuntime.action(session.lease, action), code)
            rejected(session.prepareExactActionFromUser(operation, ownerCurrent = { true }, onConfirmed = { false }), code)
        }
        assertTrue(session.lease.actions.isEmpty()); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun defaultExternalGlobalRouteRemainsOrdinaryAndDoesNotAcquireASelfException() {
        val observation = observe()
        val action = GuiAction(observation.observationId, GuiOperation.BACK)
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)
        assertEquals(0, platform.nodeActions)
    }

    @Test fun closeAndNewOwnerDoNotInheritSelfParametersAndOldLeaseCannotAffectTheNewOne() {
        openSelf()
        val old = session; val observation = observe(); val action = approve(observation)
        openTarget(null)
        val replacement = session
        rejected(replacement.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        rejected(GuiRuntime.action(old.lease, action), "FOREGROUND_BUSY")
        old.close() // must not clear a replacement lease's parameters
        platform.pkg = "external.app"
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun disconnectAndReconnectDoNotInheritSelfParametersOrReviveOldLease() {
        openSelf()
        val old = session; val observation = observe(); val action = approve(observation)
        GuiRuntime.disconnected(service); GuiRuntime.connected(service)
        rejected(GuiRuntime.action(old.lease, action), "GUI_AUTHORIZATION_REVOKED")
        openTarget(null)
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        platform.pkg = "external.app"
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun selfUnknownStillFencesOriginalSessionAndCannotOpenAReplacementLease() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        val reads = platform.rootReads
        session.onCentralUnknown()
        rejected(session.observe { false }, "GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        rejected(session.action(action) { false }, "GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        rejected(session.screenshot(observation.observationId) { false }, "GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        rejected(GuiRuntime.openForUser(GuiOwner("replacement", "run")), "FOREGROUND_BUSY")
        assertEquals(reads, platform.rootReads); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfCloseWhileEncodingIsQueuedRetainsDrainAndDoesNotLeakNarrowParameters() {
        openSelf()
        val observation = observe(); val future = screenshot(observation); val frame = platform.success()
        session.close(); assertFalse(session.releaseConfirmed)
        rejected(GuiRuntime.openForUser(GuiOwner("replacement", "run")), "FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        worker.runAll()
        rejected(future.join(), "GUI_AUTHORIZATION_REVOKED"); assertDisposed(frame)
        assertTrue(session.releaseConfirmed)
        openTarget(null)
        rejected(session.previewFromUser(), "EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        assertNoActions()
    }

    @Test fun windowChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.windowId++
        rejected(screenshot(observation).join(), "SCREENSHOT_WINDOW_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
        assertEquals(1, session.lease.screenshots)
    }

    @Test fun nodeChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.label = "after"
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun packageChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.pkg = "another.app"
        rejected(screenshot(observation).join(), "SCREENSHOT_WINDOW_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun movedDisplayOrUnavailableTreeCannotBeVerified() {
        val observation = observe(); platform.displayId = 1
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        platform.displayId = 0; platform.missing = true
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun truncatedObservationCannotAuthorizeAPartialTreeMatch() {
        platform.label = "x".repeat(513)
        val observation = observe()
        assertTrue(observation.truncated)
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun failedReplacementRetiresOldIdAndExactActions() {
        val observation = observe(); approve(observation)
        platform.missing = true
        rejected(session.previewFromUser(), "NO_ACTIVE_EXTERNAL_WINDOW")
        assertNull(session.lease.observation)
        assertTrue(session.lease.actions.isEmpty())
        rejected(screenshot(observation).join(), "OBSERVATION_REQUIRED")
        rejected(session.approveExactActionFromUser(GuiAction(observation.observationId, GuiOperation.TAP, 1)), "OBSERVATION_REQUIRED")
    }

    @Test fun successfulCaptureDoesNotPublishAnObservationOrChangeApproval() {
        val observation = observe(); val action = approve(observation)
        val remainingReads = session.lease.reads
        val future = screenshot(observation)
        val frame = platform.success()
        assertFalse(future.isDone)
        assertEquals(0, frame.encoded)
        worker.runAll()
        val image = (future.join() as GuiResult.Value).value
        assertEquals(observation.observationId, image.observationId)
        assertSame(observation, session.lease.observation)
        assertEquals(setOf(action), session.lease.actions)
        assertEquals(remainingReads, session.lease.reads)
        assertEquals(0, session.lease.screenshots)
        assertEquals(3, platform.rootReads) // published, pre-submit, post-encode/pre-delivery
        assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun nodeChangeDuringWorkerQueueIsRejectedAtDeliveryAndResourcesReleased() {
        val observation = observe(); val action = approve(observation)
        val future = screenshot(observation); val frame = platform.success()
        platform.label = "changed without accessibility event"
        worker.runAll()
        rejected(future.join(), "SCREENSHOT_TREE_CHANGED")
        assertSame(observation, session.lease.observation)
        assertEquals(setOf(action), session.lease.actions)
        assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun treeChangeAfterEncodingButBeforeMainDeliveryIsStillRejected() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success()
        worker.runAll(deliver = false)
        assertEquals(1, frame.encoded); assertFalse(future.isDone)
        platform.label = "changed after encoding"
        shadowOf(Looper.getMainLooper()).idle()
        rejected(future.join(), "SCREENSHOT_TREE_CHANGED")
        assertDisposed(frame)
    }

    @Test fun displayChangeBeforeDeliveryCannotBeVerified() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(); platform.displayId = 1
        worker.runAll()
        rejected(future.join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertDisposed(frame)
    }

    @Test fun windowChangeBeforeLateCallbackIsRejectedAtDelivery() {
        val observation = observe(); val future = screenshot(observation)
        platform.windowId++
        val frame = platform.success(); worker.runAll()
        rejected(future.join(), "SCREENSHOT_WINDOW_CHANGED")
        assertDisposed(frame)
    }

    @Test fun closeBeforeLateCallbackKeepsDrainUntilAllResourcesAreDisposed() {
        val observation = observe(); val future = screenshot(observation)
        session.close(); assertFalse(session.releaseConfirmed)
        rejected(GuiRuntime.openForUser(GuiOwner("next", "run")), "FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        val frame = platform.success()
        rejected(future.join(), "GUI_AUTHORIZATION_REVOKED")
        assertEquals(0, frame.encoded); assertTrue(worker.tasks.isEmpty())
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun cancellationWhileEncodingIsQueuedDoesNotReleaseReservationEarly() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success()
        future.cancel(false); session.close()
        assertFalse(session.releaseConfirmed); assertTrue(frame.disposed.isEmpty())
        worker.runAll()
        assertEquals(0, frame.encoded)
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun closeWhileEncodingIsQueuedRejectsDeliveryButStillDrains() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(); session.close()
        assertFalse(session.releaseConfirmed)
        worker.runAll()
        rejected(future.join(), "GUI_AUTHORIZATION_REVOKED")
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun encoderRejectionDisposesAndDoesNotHoldTheSlot() {
        val observation = observe(); val future = screenshot(observation)
        GuiRuntime.encoder = Executor { throw RejectedExecutionException() }
        val frame = platform.success()
        rejected(future.join(), "SCREENSHOT_ENCODING_UNAVAILABLE")
        assertDisposed(frame); session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun encodingExceptionDisposesAndDoesNotHoldTheSlot() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(Frame().apply { encodeFailure = true })
        worker.runAll()
        rejected(future.join(), "SCREENSHOT_UNAVAILABLE")
        assertDisposed(frame); session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun submissionUnknownRetainsReservationUntilLateSuccessDisposal() {
        val observation = observe(); platform.throwOnSubmit = true
        val future = screenshot(observation)
        rejected(future.join(), "SCREENSHOT_DELIVERY_UNCONFIRMED")
        session.close(); assertFalse(session.releaseConfirmed)
        val frame = platform.success()
        assertEquals(0, frame.encoded); assertDisposed(frame)
        assertTrue(session.releaseConfirmed)
        rejected(future.join(), "SCREENSHOT_DELIVERY_UNCONFIRMED")
    }

    @Test fun failedDisposalAttemptsEveryResourceAndCannotManufactureDrain() {
        val observation = observe(); screenshot(observation)
        val frame = platform.success(Frame().apply { disposalFailure = true })
        session.close(); worker.runAll()
        assertDisposed(frame); assertFalse(session.releaseConfirmed)
        frame.disposalFailure = false
        frame.dispose(frame.disposalRequest!!)
        assertTrue(session.releaseConfirmed)
    }
}
