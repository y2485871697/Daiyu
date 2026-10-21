package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.vd.AndroidVirtualDisplayOwnerHost
import app.daiyu.android.vd.OwnerRunBinding
import app.daiyu.android.vd.VirtualDisplayOwnerClient
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real MainActivity routing/callbacks + Controller + SQLite + Runner + durable lease store.
 * Only the non-launching process host is substituted. In particular, stale actions below invoke
 * Main's SAME callback factory used by Dialog.onDismissRequest and vd-cancel, not
 * controller.cancelVirtualDisplay(oldEntry). Like retainedControls in ComposeActivityTestCase,
 * retain the host capability rather than a detached Compose ClickableNode wrapper.
 * Source coverage only: this is not evidence of execution on an emulator or privileged device. */
@Suppress("DEPRECATION")
class MainVirtualDisplayCallbackTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private val leaseId = UUID.randomUUID().toString()
    private val gate = Semaphore(1)
    private val starts = AtomicInteger()
    private val physicalUnknown = AtomicBoolean()
    private val observations = AtomicInteger()
    private val initializedOwner = AtomicBoolean()
    private val loseCleanupReply = AtomicBoolean()
    private val cleanupSends = AtomicInteger()
    private val taskObservations = AtomicInteger()
    private val handoffSends = AtomicInteger()
    private var handedOffIds: List<Int> = emptyList()
    private val host = object : ControllerVirtualDisplayRuntime.Host {
        override var client: VirtualDisplayOwnerClient? = null
        override fun start(generation: String, binding: OwnerRunBinding): AndroidVirtualDisplayOwnerHost.Start {
            starts.incrementAndGet()
            if (!initializedOwner.get()) throw IOException("Main callback test must never launch an owner")
            client = VirtualDisplayOwnerClient(generation, binding,
                app.daiyu.android.vd.OwnerCredential("0123456789abcdef0123456789abcdef"), object : app.daiyu.android.vd.OwnerTransport {
                    override fun exchange(line: String): String {
                        app.daiyu.android.vd.runtime.OwnerProtocol.parseWireRequest(line)
                        val request = org.json.JSONObject(line)
                        val operation = request.getString("op")
                        val initialize = operation == "initialize"
                        if (operation == "release") {
                            cleanupSends.incrementAndGet()
                            if (loseCleanupReply.get()) throw IOException("Main cleanup response lost")
                        }
                        val id = if (initialize) "" else request.getString("operationId")
                        val contract = app.daiyu.android.vd.runtime.OwnerV2Contract
                        val receipt = org.json.JSONObject().put("generation", generation).put("operationId", id)
                            .put("operation", if (initialize) "" else operation).put("generationReceipt", initialize)
                            .put("digest", if (initialize) contract.generationDigest(generation, binding.trusted)
                                else contract.identity(generation, id, operation, binding.trusted, request.getJSONObject("parameters")))
                            .put("bindingDigest", contract.bindingDigest(binding.trusted))
                            .put("state", if (initialize) "STARTED" else "SUCCEEDED")
                        val body = app.daiyu.android.vd.runtime.OwnerProtocol.ok(operation, null)
                            .put("generation", generation).put("receipt", receipt)
                            .put("displayId", 7).put("uniqueId", "virtual:main-test")
                        if (initialize) body.put("initialized", true)
                        else {
                            receipt.put("result", org.json.JSONObject().put("ok", true).put("released", operation == "release"))
                            body.put("receiptOnly", false)
                            when (operation) {
                                "status" -> {
                                    taskObservations.incrementAndGet()
                                    body.put("ready", true).put("name", "main-test").put("width", 640).put("height", 960)
                                        .put("densityDpi", 240).put("flags", 0).put("ownerPackage", "app.daiyu.android")
                                        .put("frameCount", 0).put("hasFrame", false).put("frameTimestampNs", 0L)
                                        .put("session", "active").put("sourceState", "occupied").put("sourceEmpty", false)
                                        .put("sourceTaskCount", 2).put("sourcePackages", org.json.JSONArray())
                                        .put("retainedTaskIds", org.json.JSONArray(listOf(12, 23)))
                                        .put("liveTaskIds", org.json.JSONArray(listOf(12, 23))).put("goneTaskIds", org.json.JSONArray())
                                        .put("finishing", false).put("handoffComplete", false).put("releaseAttempted", false)
                                        .put("mutationUncertain", false)
                                        .put("supported", org.json.JSONArray(app.daiyu.android.vd.runtime.OwnerProtocol.SUPPORTED_OPS.toList()))
                                        .put("missing", org.json.JSONArray())
                                }
                                "handoff" -> {
                                    body.remove("displayId"); body.remove("uniqueId")
                                    handoffSends.incrementAndGet()
                                    val ids = request.getJSONObject("parameters").getJSONArray("taskIds")
                                    handedOffIds = (0 until ids.length()).map { ids.getInt(it) }
                                    body.put("handedOff", true).put("sourceEmpty", true).put("keptTaskIds", ids)
                                        .put("removedTaskIds", org.json.JSONArray(listOf(12, 23).filter { it !in handedOffIds }))
                                        .put("goneTaskIds", org.json.JSONArray()).put("cleanupOnly", handedOffIds.isEmpty())
                                        .put("focusSamples", org.json.JSONArray())
                                }
                                "release" -> body.put("released", true)
                            }
                        }
                        return body.toString()
                    }
                    override fun disconnect() = Unit
                })
            return AndroidVirtualDisplayOwnerHost.Start.READY
        }
        override fun observe(): AndroidVirtualDisplayOwnerHost.Observation {
            observations.incrementAndGet()
            return when {
                physicalUnknown.get() -> AndroidVirtualDisplayOwnerHost.Observation(
                    AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN, false, null, null, false)
                starts.get() == 0 -> AndroidVirtualDisplayOwnerHost.Observation(
                    AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED, false, null, null, false)
                else -> AndroidVirtualDisplayOwnerHost.Observation(
                    AndroidVirtualDisplayOwnerHost.Lifecycle.DRAINED, true, 0, 4242, true)
            }
        }
    }

    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext,
        virtualDisplayFactory = ControllerVirtualDisplayFactory { context, owner, caps, writes, reads, parent, retention ->
            val root = context.cacheDir.canonicalFile.toPath().resolve("main-vd-callback-$leaseId")
            ControllerVirtualDisplayRuntime.assemble(owner, caps, writes, reads, parent, retention,
                ControllerVirtualDisplayRuntime.LeaseStore({ root }, gate)) { host }
        }).also { check(it.taskSurfaces.save(app.daiyu.android.tasksurface.TaskSurfacePreference.BACKGROUND)) }

    override fun tearDown() {
        try {
            // Business assertions may intentionally retain UNKNOWN; the fake host now observes exit.
            physicalUnknown.set(false)
            val entry = fixture.controller.virtualDisplayEntry()
            if (entry != null) {
                mainThread { fixture.controller.cancelVirtualDisplay(entry) }
                awaitControl("fixture original catalog locally drained") {
                    fixture.controller.virtualDisplayEntry() == null ||
                        entry.session?.let { session ->
                            entry.catalog?.virtualDisplayCleanupLocallyDrained(session) == true
                        } == true
                }
                mainThread {
                    if (fixture.controller.virtualDisplayEntry() === entry)
                        assertTrue(fixture.controller.observeVirtualDisplayClose(entry))
                }
                awaitControl("fixture original run and display cleanup drained") {
                    fixture.controller.virtualDisplayEntry() == null && fixture.controller.active == null
                }
                assertEquals(1, gate.availablePermits())
            }
        } finally { super.tearDown() }
    }

    private fun invokeMain(name: String, vararg arguments: Any?): Any? {
        val method = MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == arguments.size
        }
        return try { method.apply { isAccessible = true }.invoke(activity, *arguments) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }
    private fun editor() = invokeMain("getVirtualDisplayEditor") as ControllerVirtualDisplayEntry?
    private fun request() = invokeMain("getVirtualDisplayRequest") as VirtualDisplayApprovalRequest?

    @Suppress("UNCHECKED_CAST")
    private fun retainCancel(entry: ControllerVirtualDisplayEntry, request: VirtualDisplayApprovalRequest?): () -> Unit =
        invokeMain("virtualDisplayCancelAction", entry, request) as () -> Unit

    private fun prepare(text: String): ControllerVirtualDisplayEntry {
        setDraft(text)
        node("vd-prepare-entry").assertIsDisplayed().assertIsEnabled().performClick()
        // Real R08 editor visit freezes inputs before the unchanged Controller prepare seam.
        node("r08-plan-freeze").assertExists().performClick()
        awaitControl("retained background location decision") {
            fixture.controller.virtualDisplayEntry()?.taskSurface?.ready(app.daiyu.android.tasksurface.TaskSurface.BACKGROUND) != null
        }
        node("vd-cancel").assertExists()
        var prepared: ControllerVirtualDisplayEntry? = null
        mainThread { prepared = editor() }
        return checkNotNull(prepared)
    }
    private fun submit(entry: ControllerVirtualDisplayEntry): VirtualDisplayApprovalRequest {
        awaitControl("same entry ready for its background backend") {
            entry.taskSurface.ready(app.daiyu.android.tasksurface.TaskSurface.BACKGROUND) != null
        }
        node("vd-submit").performClick()
        awaitTextSendResult(activity)
        awaitControl("Main displays the exact accepted-run request") {
            editor() === entry && request() != null
        }
        node("vd-approval-confirm").assertExists()
        var displayed: VirtualDisplayApprovalRequest? = null
        mainThread { displayed = request() }
        return checkNotNull(displayed)
    }
    private fun awaitDrain() {
        awaitControl("original run/catalog/session physically drained") {
            fixture.controller.virtualDisplayEntry() == null && fixture.controller.active == null && editor() == null
        }
        ui.waitForIdle()
        assertEquals(1, gate.availablePermits())
    }
    private fun assertCurrent(entry: ControllerVirtualDisplayEntry, displayed: VirtualDisplayApprovalRequest?) {
        assertSame(entry, editor())
        assertSame(entry, fixture.controller.virtualDisplayEntry())
        assertSame(displayed, request())
        assertFalse("stale Main callback must not revoke B", entry.revoked)
    }

    fun testRealMainEditorParametersReachOriginalPrepareWithoutAcceptanceOrAuthorization() {
        setDraft("edited finite plan")
        node("vd-prepare-entry").performClick()
        mainThread {
            assertNull(editor()); assertNull(fixture.controller.virtualDisplayEntry())
            assertEquals(1, gate.availablePermits())
        }
        node("r08-plan-rows").scrollToDescendant(hasTestTag("r08-add-TAP"))
        node("r08-add-TAP").assertIsDisplayed().performClick()
        node("r08-plan-rows").scrollToDescendant(hasTestTag("r08-plan-2-x"))
        node("r08-plan-2-x").performTextReplacement("31")
        node("r08-plan-rows").scrollToDescendant(hasTestTag("r08-plan-2-y"))
        node("r08-plan-2-y").performTextReplacement("47")
        node("r08-plan-freeze").performClick()
        mainThread {
            val entry = checkNotNull(editor())
            assertSame(entry, fixture.controller.virtualDisplayEntry())
            assertEquals(app.daiyu.android.integration.tools.R08VirtualDisplayAction.Tap(31, 47), entry.writes.last().action)
            assertFalse(entry.submitted); assertFalse(entry.accepted)
            assertNull(entry.owner); assertNull(entry.authorization); assertNull(entry.session)
            assertEquals(1, gate.availablePermits())
        }
        assertDraft("edited finite plan")
        assertEquals(0, fixture.opens.get()); assertEquals(0, starts.get())
        node("vd-cancel").performClick()
    }

    fun testOldFrozenAndCancelCallbacksCannotChangeNewPlanVisitOrDraft() {
        setDraft("old plan draft")
        node("vd-prepare-entry").performClick()
        lateinit var old: Any
        mainThread {
            old = checkNotNull(invokeMain("getVirtualDisplayPlanVisit"))
            invokeMain("cancelVirtualDisplayPlan", old)
            assertNull(invokeMain("getVirtualDisplayPlanVisit"))
            assertNull(fixture.controller.virtualDisplayEntry())
        }
        assertDraft("old plan draft")
        ui.waitForIdle()
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("chat-draft") and isEnabled())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        setDraft("new plan draft")
        node("vd-prepare-entry").performClick()
        mainThread {
            val newer = checkNotNull(invokeMain("getVirtualDisplayPlanVisit"))
            assertNotSame(old, newer)
            val frozen = app.daiyu.android.vdapproval.R08FrozenPlan.freeze(listOf(
                app.daiyu.android.vdapproval.R08PlanEditorRow(app.daiyu.android.vdapproval.R08PlanAction.START),
                app.daiyu.android.vdapproval.R08PlanEditorRow(app.daiyu.android.vdapproval.R08PlanAction.INITIALIZE)))
            invokeMain("freezeVirtualDisplayPlan", old, frozen)
            invokeMain("cancelVirtualDisplayPlan", old)
            assertSame(newer, invokeMain("getVirtualDisplayPlanVisit"))
            assertNull(editor()); assertNull(fixture.controller.virtualDisplayEntry())
        }
        node("r08-plan-freeze").assertExists()
        node("chat-draft").assertIsNotEnabled()
        node("r08-plan-freeze").performClick()
        mainThread { assertEquals("new plan draft", editor()?.text) }
        assertDraft("new plan draft")
        assertEquals(0, fixture.opens.get()); assertEquals(0, starts.get())
    }

    fun testPlanNavigationCancellationNeverPreparesOrClearsDraft() {
        setDraft("plan before navigation")
        node("vd-prepare-entry").performClick()
        mainThread { activity.select(null) }
        node("r08-plan-freeze").assertDoesNotExist()
        mainThread {
            assertNull(invokeMain("getVirtualDisplayPlanVisit")); assertNull(editor())
            assertNull(fixture.controller.virtualDisplayEntry())
        }
        assertDraft("plan before navigation")
        assertEquals(0, fixture.opens.get()); assertEquals(0, starts.get())
    }

    fun testOldMainStopCallbackCannotCancelNewPreparedPendingOrApprovedRun() = staleMainAction(dialog = false)

    fun testOldMainDialogDismissCannotCancelNewPreparedPendingOrApprovedRun() = staleMainAction(dialog = true)

    private fun staleMainAction(dialog: Boolean) {
        val first = prepare("Main run A")
        val firstRequest = submit(first)
        lateinit var oldAction: () -> Unit
        lateinit var dismissFirst: () -> Unit
        mainThread {
            oldAction = retainCancel(first, if (dialog) firstRequest else null)
            dismissFirst = retainCancel(first, firstRequest)
            // A current Dialog must still work, and cancellation must fence before hiding A.
            dismissFirst()
            assertTrue(first.revoked)
            assertNull(editor()); assertNull(request())
        }
        awaitDrain()

        val second = prepare("Main run B")
        assertNotSame(first, second)
        mainThread {
            oldAction()
            assertCurrent(second, null)
            assertFalse(second.submitted)
        }
        val secondRequest = submit(second)
        assertNotSame(firstRequest, secondRequest)
        val retained = second.session
        mainThread {
            oldAction()
            assertCurrent(second, secondRequest)
            assertSame(retained, second.session)
            assertSame(secondRequest, fixture.controller.virtualDisplayApproval(second))
        }
        // B remains approvable through the actual Main Dialog after the old A callback fires.
        val model = fixture.session("B still running")
        node("vd-approval-confirm").performClick()
        assertTrue(model.entered.await(5, TimeUnit.SECONDS))
        awaitControl("B receives its exact authorization") { second.authorization != null }
        val authorization = second.authorization
        val messages = fixture.database.messages(second.conversationId)
        mainThread {
            oldAction()
            assertCurrent(second, null)
            assertSame(retained, second.session)
            assertSame(authorization, second.authorization)
            assertEquals(1L, model.cancelled.count)
            assertEquals(0, gate.availablePermits())
        }
        assertEquals(messages, fixture.database.messages(second.conversationId))
        assertEquals(1, fixture.opens.get())
        assertEquals(0, starts.get())
        // The new stop capability, unlike A's retained capability, still cancels B.
        node("vd-cancel").performClick()
        awaitDrain()
        assertEquals(0L, model.cancelled.count)
    }

    fun testMainDismissAlsoRequiresTheOriginalRequestForTheSameEntry() {
        val entry = prepare("same entry, replaced request")
        val original = submit(entry)
        val replacement = VirtualDisplayApprovalRequest(original.plan)
        lateinit var oldDismiss: () -> Unit
        mainThread {
            oldDismiss = retainCancel(entry, original)
            // Same plan and entry, different request object: model a superseding Dialog frame.
            invokeMain("setVirtualDisplayRequest", replacement)
            oldDismiss()
            assertCurrent(entry, replacement)
            assertSame(original, fixture.controller.virtualDisplayApproval(entry))
            invokeMain("setVirtualDisplayRequest", original)
            oldDismiss()
            assertTrue(entry.revoked)
            assertNull(editor()); assertNull(request())
        }
        awaitDrain()
    }

    private fun closeIntoUnknown(): ControllerVirtualDisplayEntry {
        val entry = prepare("held physical observation")
        submit(entry)
        val model = fixture.session("held until Main stop")
        node("vd-approval-confirm").performClick()
        assertTrue(model.entered.await(5, TimeUnit.SECONDS))
        physicalUnknown.set(true)
        node("vd-cancel").performClick()
        awaitControl("UNKNOWN retains the exact run, session and lease") {
            fixture.controller.virtualDisplayEntry() === entry && entry.revoked &&
                fixture.controller.problem?.startsWith("副屏清理结果 UNKNOWN") == true
        }
        assertEquals(0, gate.availablePermits())
        assertNotNull(entry.session)
        assertTrue(observations.get() > 0)
        return entry
    }
    private fun observeDrain() {
        physicalUnknown.set(false)
        node("vd-observe-close").performClick()
        awaitDrain()
        assertEquals(0, starts.get())
    }

    private fun initializedCleanupEntry(): Pair<ControllerVirtualDisplayEntry, VirtualDisplayApprovalRequest> {
        initializedOwner.set(true); physicalUnknown.set(true)
        val entry = prepare("Main initialized original owner")
        val original = submit(entry)
        val model = fixture.session("initialized plan finished")
        node("vd-approval-confirm").performClick()
        assertTrue(model.entered.await(5, TimeUnit.SECONDS))
        // Feed the retained model event sink while next() is deliberately held. The original
        // Runner collects and dispatches this WHOLE batch, including its final ledger feedback;
        // no direct Session/leaf execute or fake parentDrained=true is used here.
        val sink = checkNotNull(model.sink.get())
        entry.writes.forEachIndexed { index, write ->
            sink(app.daiyu.android.domain.ModelEvent.ToolReady(app.daiyu.android.domain.ToolInvocation(
                app.daiyu.android.domain.ToolCallId("main-step-$index"),
                app.daiyu.android.integration.tools.R08VirtualDisplayCatalogLeaf.WRITE_TOOL,
                org.json.JSONObject().put("step_id", write.id).toString())))
        }
        model.release.countDown()
        awaitControl("initialized Runner returned and original catalog locally drained") {
            entry.revoked && entry.catalog?.virtualDisplayCleanupLocallyDrained(checkNotNull(entry.session)) == true &&
                fixture.controller.problem?.contains("UNKNOWN") == true
        }
        mainThread { invokeMain("cancelVirtualDisplayEditor", entry); assertNull(editor()) }
        assertEquals(1, starts.get()); assertEquals(0, cleanupSends.get())
        return entry to original
    }

    private fun openInitializedCleanup(): app.daiyu.android.vdapproval.R08CleanupApprovalRequest {
        node("vd-cleanup-entry").assertIsEnabled().performClick()
        node("r08-cleanup-confirm").assertDoesNotExist()
        assertEquals(0, cleanupSends.get())
        node("vd-cleanup-release-only").assertIsEnabled().performClick()
        var request: app.daiyu.android.vdapproval.R08CleanupApprovalRequest? = null
        awaitControl("Main worker result published to the original cleanup owner") {
            val visit = invokeMain("getVirtualDisplayCleanupVisit") ?: return@awaitControl false
            request = visit.javaClass.getDeclaredMethod("getRequest").apply { isAccessible = true }.invoke(visit)
                as app.daiyu.android.vdapproval.R08CleanupApprovalRequest?
            request != null
        }
        node("r08-cleanup-confirm").assertExists()
        return checkNotNull(request)
    }

    private fun openDeliveryApproval(): app.daiyu.android.vdapproval.R08CleanupApprovalRequest {
        node("vd-cleanup-entry").assertIsEnabled().performClick()
        assertEquals(0, taskObservations.get())
        assertEquals(0, handoffSends.get())
        node("vd-cleanup-observe-tasks").assertIsEnabled().performClick()
        awaitUniqueTag("vd-delivery-task-23")
        node("vd-delivery-task-12").assertIsOff()
        node("vd-delivery-task-23").assertIsOff()
        node("vd-cleanup-freeze-delivery").assertIsNotEnabled()
        node("vd-delivery-task-23").performClick()
        node("vd-cleanup-freeze-delivery").assertIsEnabled().performClick()
        awaitUniqueTag("r08-cleanup-confirm")
        var request: app.daiyu.android.vdapproval.R08CleanupApprovalRequest? = null
        mainThread {
            val visit = checkNotNull(invokeMain("getVirtualDisplayCleanupVisit"))
            request = visit.javaClass.getDeclaredMethod("getRequest").apply { isAccessible = true }.invoke(visit)
                as app.daiyu.android.vdapproval.R08CleanupApprovalRequest
        }
        return checkNotNull(request)
    }

    fun testRealMainSelectsOneOwnedTaskAndOnlyExplicitApprovalSendsHandoffThenRelease() {
        val (entry, original) = initializedCleanupEntry()
        val beforeObserve = observations.get()
        val messages = fixture.database.messages(entry.conversationId)
        val request = openDeliveryApproval()
        assertTrue(request.deliversTasks)
        assertEquals(listOf(23), request.selectedTaskIds)
        assertSame(original.plan.owner, request.review.plan.owner)
        assertSame(original.plan.capabilities, request.review.plan.capabilities)
        assertEquals(original.plan.generation, request.review.plan.generation)
        assertEquals(1, taskObservations.get()); assertEquals(0, handoffSends.get()); assertEquals(0, cleanupSends.get())
        node("r08-cleanup-confirm").performClick()
        awaitControl("selected tasks and release confirmed without physical observation") {
            val visit = invokeMain("getVirtualDisplayCleanupVisit") ?: return@awaitControl false
            val status = visit.javaClass.getDeclaredMethod("getStatus").apply { isAccessible = true }.invoke(visit) as String
            status.startsWith("所选非空任务已确认交付，Release 已确认")
        }
        assertEquals(listOf(23), handedOffIds)
        assertEquals(1, handoffSends.get()); assertEquals(1, cleanupSends.get()); assertEquals(1, starts.get())
        assertEquals(beforeObserve, observations.get()); assertEquals(0, gate.availablePermits())
        assertSame(entry, fixture.controller.virtualDisplayEntry())
        assertEquals(messages, fixture.database.messages(entry.conversationId))
        physicalUnknown.set(false)
        mainThread { assertTrue(fixture.controller.observeVirtualDisplayClose(entry)) }
        awaitDrain()
    }

    fun testRealMainExplicitEmptyHandoffCleansWithoutClaimingTaskDelivery() {
        val (entry, original) = initializedCleanupEntry()
        val beforeObserve = observations.get()
        val messages = fixture.database.messages(entry.conversationId)
        node("vd-cleanup-entry").assertIsEnabled().performClick()
        assertEquals(0, taskObservations.get())
        assertEquals(0, handoffSends.get())
        node("vd-cleanup-observe-tasks").assertIsEnabled().performClick()
        awaitUniqueTag("vd-delivery-task-23")
        node("vd-delivery-task-12").assertIsOff()
        node("vd-delivery-task-23").assertIsOff()
        node("vd-cleanup-freeze-delivery").assertIsNotEnabled()
        node("vd-cleanup-freeze-empty").assertIsEnabled().performClick()
        awaitUniqueTag("r08-cleanup-confirm")
        mainThread {
            val visit = checkNotNull(invokeMain("getVirtualDisplayCleanupVisit"))
            val request = visit.javaClass.getDeclaredMethod("getRequest").apply { isAccessible = true }
                .invoke(visit) as app.daiyu.android.vdapproval.R08CleanupApprovalRequest
            assertTrue(request.hasHandoff)
            assertTrue(request.cleanupOnly)
            assertFalse(request.deliversTasks)
            assertTrue(request.selectedTaskIds.isEmpty())
            assertSame(original.plan.owner, request.review.plan.owner)
            assertEquals(original.plan.generation, request.review.plan.generation)
        }
        assertEquals(1, taskObservations.get())
        assertEquals(0, handoffSends.get())
        assertEquals(0, cleanupSends.get())
        node("r08-cleanup-confirm").performClick()
        awaitControl("empty task cleanup confirmed without task delivery or physical observation") {
            val visit = invokeMain("getVirtualDisplayCleanupVisit") ?: return@awaitControl false
            val status = visit.javaClass.getDeclaredMethod("getStatus").apply { isAccessible = true }
                .invoke(visit) as String
            status.startsWith("未交付任务；清理与 Release 已确认")
        }
        assertTrue(handedOffIds.isEmpty())
        assertEquals(1, handoffSends.get())
        assertEquals(1, cleanupSends.get())
        assertEquals(1, starts.get())
        assertEquals(beforeObserve, observations.get())
        assertEquals(0, gate.availablePermits())
        assertSame(entry, fixture.controller.virtualDisplayEntry())
        assertEquals(messages, fixture.database.messages(entry.conversationId))
        physicalUnknown.set(false)
        mainThread { assertTrue(fixture.controller.observeVirtualDisplayClose(entry)) }
        awaitDrain()
    }

    fun testRealMainClosingFrozenDeliveryNeverSendsAndStaleConfirmationCannotGrant() {
        val (entry, _) = initializedCleanupEntry()
        val beforeObserve = observations.get()
        val request = openDeliveryApproval()
        node("vd-cleanup-close").performClick()
        mainThread { assertFalse(checkNotNull(entry.session).answerCleanupFromUi(request, true)) }
        assertEquals(1, taskObservations.get()); assertEquals(0, handoffSends.get()); assertEquals(0, cleanupSends.get())
        assertEquals(beforeObserve, observations.get()); assertEquals(0, gate.availablePermits())
    }

    fun testRealMainExplicitCleanupApprovalUsesOriginalOwnerAndRetainsConfirmedUntilPhysicalObserve() =
        mainCleanupRelease(unknown = false)

    fun testRealMainCleanupUnknownNeverResendsAfterCloseOrAnotherExplicitVisit() = mainCleanupRelease(unknown = true)

    private fun mainCleanupRelease(unknown: Boolean) {
        loseCleanupReply.set(unknown)
        val (entry, original) = initializedCleanupEntry()
        val session = entry.session
        val authorization = entry.authorization
        val beforeObserve = observations.get()
        val messages = fixture.database.messages(entry.conversationId)
        val cleanup = openInitializedCleanup()
        assertSame(original.plan.owner, cleanup.review.plan.owner)
        assertSame(original.plan.capabilities, cleanup.review.plan.capabilities)
        assertEquals(original.plan.generation, cleanup.review.plan.generation)
        assertEquals(0, cleanupSends.get()) // opening/publishing grants nothing
        node("r08-cleanup-confirm").performClick()
        awaitControl("Main shows exact cleanup result without retry") {
            val visit = invokeMain("getVirtualDisplayCleanupVisit") ?: return@awaitControl false
            val status = visit.javaClass.getDeclaredMethod("getStatus").apply { isAccessible = true }.invoke(visit) as String
            status.startsWith(if (unknown) "收尾 UNKNOWN" else "Release 已确认")
        }
        assertEquals(1, cleanupSends.get()); assertEquals(beforeObserve, observations.get())
        assertSame(session, entry.session); assertSame(authorization, entry.authorization)
        assertSame(entry, fixture.controller.virtualDisplayEntry()); assertEquals(0, gate.availablePermits())
        assertEquals(messages, fixture.database.messages(entry.conversationId))
        node("vd-cleanup-close").performClick()
        node("vd-cleanup-entry").performClick()
        node("vd-cleanup-release-only").performClick()
        awaitControl("another visit cannot recreate an answered/attempted cleanup") {
            val visit = invokeMain("getVirtualDisplayCleanupVisit") ?: return@awaitControl false
            val status = visit.javaClass.getDeclaredMethod("getStatus").apply { isAccessible = true }.invoke(visit) as String
            status.startsWith("暂不可释放")
        }
        assertEquals(1, cleanupSends.get()); assertEquals(1, starts.get())
        assertEquals(beforeObserve, observations.get()); assertEquals(0, gate.availablePermits())
        physicalUnknown.set(false)
        mainThread { assertTrue(fixture.controller.observeVirtualDisplayClose(entry)) }
        awaitDrain()
        assertEquals(1, gate.availablePermits())
        assertEquals(1, cleanupSends.get())
        assertEquals(messages, fixture.database.messages(entry.conversationId))
    }

    fun testRealMainCleanupRejectionOnlyDeniesOriginalRequestWithoutWriteOrLeaseRelease() {
        val (entry, _) = initializedCleanupEntry()
        val beforeObserve = observations.get()
        val request = openInitializedCleanup()
        node("r08-cleanup-reject").performClick()
        mainThread {
            assertNull(invokeMain("getVirtualDisplayCleanupVisit"))
            assertFalse(checkNotNull(entry.session).answerCleanupFromUi(request, true))
        }
        assertEquals(0, cleanupSends.get()); assertEquals(beforeObserve, observations.get())
        assertSame(entry, fixture.controller.virtualDisplayEntry()); assertEquals(0, gate.availablePermits())
    }

    fun testExplicitCleanupIsIndependentOfOldEditorAndStaleCloseCannotRetireReplacementVisit() {
        val entry = closeIntoUnknown()
        val beforeObserve = observations.get()
        var old: Any? = null
        node("vd-cleanup-entry").assertIsEnabled().performClick()
        node("vd-cleanup-release-only").performClick()
        awaitControl("explicit cleanup unavailable without initialized evidence") {
            val visit = invokeMain("getVirtualDisplayCleanupVisit") ?: return@awaitControl false
            old = visit
            val status = visit.javaClass.getDeclaredMethod("getStatus").apply { isAccessible = true }.invoke(visit) as String
            status.startsWith("暂不可释放")
        }
        mainThread {
            assertNull(editor()) // cleanup is not owned by the dead TextSendOwner/editor
            invokeMain("openVirtualDisplayCleanup", entry)
            val newer = checkNotNull(invokeMain("getVirtualDisplayCleanupVisit"))
            assertNotSame(old, newer)
            invokeMain("closeVirtualDisplayCleanup", old)
            assertSame(newer, invokeMain("getVirtualDisplayCleanupVisit"))
            assertSame(entry, fixture.controller.virtualDisplayEntry())
        }
        assertEquals(beforeObserve, observations.get()) // no physical probe on prepare/replacement
        node("vd-cleanup-close").performClick()
        mainThread { assertNull(invokeMain("getVirtualDisplayCleanupVisit")) }
        assertEquals(0, starts.get()); assertEquals(0, gate.availablePermits())
        assertSame(entry, fixture.controller.virtualDisplayEntry())
    }

    fun testCleanupNavigationOnlyClosesUiVisitAndNeverSendsOrObserves() {
        val entry = closeIntoUnknown()
        val beforeObserve = observations.get()
        node("vd-cleanup-entry").performClick()
        mainThread { activity.select(entry.conversation ?: fixture.database.conversations().single()) }
        mainThread {
            assertNull(invokeMain("getVirtualDisplayCleanupVisit"))
            assertSame(entry, fixture.controller.virtualDisplayEntry())
        }
        assertEquals(beforeObserve, observations.get())
        assertEquals(0, starts.get()); assertEquals(0, gate.availablePermits())
    }

    fun testLaterDrainedClearsTheOriginalUnknownNotice() {
        closeIntoUnknown()
        observeDrain()
        assertNull(fixture.controller.problem)
    }

    fun testLaterDrainedClearsGenericNoticeFromTheSameCleanupChain() {
        val entry = closeIntoUnknown()
        mainThread {
            // Exercise the generic close-callback publication too, not only the VD prefix.
            // Physical drainage still goes through the real catalog/session observation below.
            val method = ChatController::class.java.getDeclaredMethod("reportCleanupProblem",
                app.daiyu.android.integration.tools.RunCatalogTools::class.java, String::class.java)
            synchronized(fixture.controller) {
                method.apply { isAccessible = true }.invoke(fixture.controller, checkNotNull(entry.catalog),
                    "运行清理结果 UNKNOWN；原资源保留，不自动重连或重发，请核实设备状态")
            }
        }
        observeDrain()
        assertNull(fixture.controller.problem)
    }

    fun testLaterDrainedPreservesNewErrorEvenWithTheSameText() = preserveNewProblem(sameText = true)

    fun testLaterDrainedPreservesDifferentNewErrorWithTheSamePrefix() = preserveNewProblem(sameText = false)

    private fun preserveNewProblem(sameText: Boolean) {
        closeIntoUnknown()
        val newer = if (sameText) checkNotNull(fixture.controller.problem)
            else "副屏清理结果：另一个新错误，不能由旧链清除"
        mainThread {
            // Publish through the actual setter, NOT a reflective field write which bypasses
            // publication identity. Equal text is still a different error publication.
            val setter = ChatController::class.java.getDeclaredMethod("setProblem", String::class.java)
            setter.apply { isAccessible = true }.invoke(fixture.controller, newer)
        }
        observeDrain()
        assertEquals(newer, fixture.controller.problem)
    }
}
