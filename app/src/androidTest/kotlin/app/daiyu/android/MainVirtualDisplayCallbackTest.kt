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
    private val leaseId = UUID.randomUUID().toString()
    private val gate = Semaphore(1)
    private val starts = AtomicInteger()
    private val physicalUnknown = AtomicBoolean()
    private val observations = AtomicInteger()
    private val host = object : ControllerVirtualDisplayRuntime.Host {
        override val client: VirtualDisplayOwnerClient? = null
        override fun start(generation: String, binding: OwnerRunBinding): AndroidVirtualDisplayOwnerHost.Start {
            starts.incrementAndGet()
            throw IOException("Main callback test must never launch an owner")
        }
        override fun observe(): AndroidVirtualDisplayOwnerHost.Observation {
            observations.incrementAndGet()
            return AndroidVirtualDisplayOwnerHost.Observation(
                if (physicalUnknown.get()) AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN
                else AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED, false, null, null, false)
        }
    }

    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext,
        virtualDisplayFactory = ControllerVirtualDisplayFactory { context, owner, caps, writes, reads, parent, retention ->
            val root = context.cacheDir.canonicalFile.toPath().resolve("main-vd-callback-$leaseId")
            ControllerVirtualDisplayRuntime.assemble(owner, caps, writes, reads, parent, retention,
                ControllerVirtualDisplayRuntime.LeaseStore({ root }, gate)) { host }
        })

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
        node("vd-prepare-entry").assertIsEnabled().performClick()
        node("vd-cancel").assertExists()
        var prepared: ControllerVirtualDisplayEntry? = null
        mainThread { prepared = editor() }
        return checkNotNull(prepared)
    }
    private fun submit(entry: ControllerVirtualDisplayEntry): VirtualDisplayApprovalRequest {
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
