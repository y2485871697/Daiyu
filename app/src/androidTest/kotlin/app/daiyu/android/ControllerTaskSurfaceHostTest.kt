package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.gui.GuiOperation
import app.daiyu.android.integration.tools.*
import app.daiyu.android.tasksurface.*
import java.util.concurrent.atomic.AtomicInteger

/** Real Controller entry/send gates with the production decision/store and memory-only settings.
 * No accessibility grant, display process, model request or actual user preference is touched.
 * These sources must be run by the integrating agent; their presence is not execution evidence. */
@Suppress("DEPRECATION")
class ControllerTaskSurfaceHostTest : InstrumentationTestCase() {
    private fun gui(f: RunControlFixture, target: String? = null): ControllerGuiEntry {
        lateinit var entry: ControllerGuiEntry
        controlMain {
            entry = checkNotNull(f.controller.prepareGuiSend(null, ChatController.TextSendOwner(null, 3, 5),
                "unchanged location draft", GuiOperation.HOME, null, target))
        }
        return entry
    }
    private fun vd(f: RunControlFixture, targets: List<String> = emptyList()): ControllerVirtualDisplayEntry {
        lateinit var entry: ControllerVirtualDisplayEntry
        val writes = listOf(R08VirtualDisplayWrite("start", R08VirtualDisplayAction.Start)) +
            targets.mapIndexed { index, target -> R08VirtualDisplayWrite("launch-$index",
                R08VirtualDisplayAction.Launch(target, "$target/.MainActivity")) }
        controlMain {
            entry = checkNotNull(f.controller.prepareVirtualDisplaySend(null, ChatController.TextSendOwner(null, 3, 5),
                "unchanged location draft", writes, listOf(R08VirtualDisplayRead("status"))))
        }
        return entry
    }
    private fun assertNoAcceptance(f: RunControlFixture) {
        assertEquals(0, f.opens.get())
        assertTrue(f.database.conversations().isEmpty())
        assertNull(f.controller.textSendState())
    }

    fun testLateLocationReadIsFencedWithoutBlockingMainOrRefreezing() {
        val reads = AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = TaskSurfaceStore(object : TaskSurfacePersistence {
            @Volatile private var record: String? = null
            override fun read(): String? {
                if (block.get()) {
                    reads.incrementAndGet(); entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return record
            }
            override fun write(record: String) { this.record = record }
        })
        check(store.save(TaskSurfacePreference.FOREGROUND))
        block.set(true)
        val owner = Any()
        val surface = ControllerTaskSurface(owner, store, "app.daiyu.android", null, false) { current.get() }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val frozen = worker.submit<TaskSurfaceDecision> { surface.freezeOnWorker() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Main can end the resource-free original parent while disk read is blocked.
            controlMain { current.set(false); surface.end() }
            release.countDown()
            assertTrue(frozen.get(5, java.util.concurrent.TimeUnit.SECONDS) is TaskSurfaceDecision.NotExecuted)
            assertSame(owner, surface.request.owner)
            assertNull(surface.ready(TaskSurface.FOREGROUND))
            assertTrue(worker.submit<TaskSurfaceDecision> { surface.freezeOnWorker() }
                .get(5, java.util.concurrent.TimeUnit.SECONDS) is TaskSurfaceDecision.NotExecuted)
            assertEquals(1, reads.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    fun testDirectGuiOpenCannotBypassUnfrozenOrPendingAskAndOldAnswerCannotReviveOwner() {
        val f = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { f.controller.ready }
            check(f.taskSurfaces.save(TaskSurfacePreference.ASK))
            val old = gui(f)
            controlMain { assertFalse(f.controller.openPreparedGui(old)) }
            assertSame(TaskSurfaceDecision.AskRequired, old.taskSurface.freezeOnWorker())
            val adapter = checkNotNull(old.taskSurface.adapter)
            assertSame(old, old.taskSurface.request.owner)
            val question = checkNotNull(adapter.askFromUser(old.taskSurface.request))
            controlMain { assertFalse(f.controller.openPreparedGui(old)) }
            assertNull(old.session)
            assertNoAcceptance(f)
            controlMain { assertTrue(f.controller.cancelGui(old)) }
            assertFalse(adapter.answerFromUser(old.taskSurface.request, question, TaskSurface.FOREGROUND))
            val next = gui(f)
            assertSame(TaskSurfaceDecision.AskRequired, next.taskSurface.freezeOnWorker())
            controlMain {
                assertFalse(f.controller.openPreparedGui(old))
                assertFalse(f.controller.cancelGui(old))
                assertSame(next, f.controller.guiEntry())
                assertFalse(next.revoked)
                f.controller.cancelGui(next)
            }
            assertNoAcceptance(f)
        } finally { controlMain { f.close() } }
    }

    fun testGuiBackgroundPreferenceAndExplicitBackgroundAnswerNeverOpenForegroundBackend() {
        val f = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { f.controller.ready }
            check(f.taskSurfaces.save(TaskSurfacePreference.BACKGROUND))
            val preferred = gui(f)
            assertTrue(preferred.taskSurface.freezeOnWorker() is TaskSurfaceDecision.NotExecuted)
            controlMain {
                assertFalse(f.controller.openPreparedGui(preferred))
                assertNull(preferred.session)
                f.controller.cancelGui(preferred)
            }
            check(f.taskSurfaces.save(TaskSurfacePreference.ASK))
            val asked = gui(f)
            assertSame(TaskSurfaceDecision.AskRequired, asked.taskSurface.freezeOnWorker())
            val adapter = checkNotNull(asked.taskSurface.adapter)
            val question = checkNotNull(adapter.askFromUser(asked.taskSurface.request))
            assertTrue(adapter.answerFromUser(asked.taskSurface.request, question, TaskSurface.BACKGROUND))
            assertTrue(asked.taskSurface.decision() is TaskSurfaceDecision.NotExecuted)
            controlMain {
                assertFalse(f.controller.openPreparedGui(asked))
                assertNull(asked.session)
                f.controller.cancelGui(asked)
            }
            assertNoAcceptance(f)
        } finally { controlMain { f.close() } }
    }

    fun testSelfDoesNotConsumeAskAndPreferenceChangesNeverRefreezeOriginalRun() {
        val f = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { f.controller.ready }
            check(f.taskSurfaces.save(TaskSurfacePreference.ASK))
            val self = gui(f, f.application.packageName)
            val ready = self.taskSurface.freezeOnWorker() as TaskSurfaceDecision.Ready
            assertNotNull(ready.selfTarget)
            val adapter = checkNotNull(self.taskSurface.adapter)
            assertNull(adapter.askFromUser(self.taskSurface.request))
            assertNull(self.session)
            assertNull(f.controller.virtualDisplayEntry())
            controlMain { f.controller.cancelGui(self) }
            // A subsequent ordinary external run still asks; self did not write the base setting.
            val external = gui(f)
            assertSame(TaskSurfaceDecision.AskRequired, external.taskSurface.freezeOnWorker())
            check(f.taskSurfaces.save(TaskSurfacePreference.FOREGROUND))
            assertSame(TaskSurfaceDecision.AskRequired, external.taskSurface.freezeOnWorker())
            val route = checkNotNull(external.taskSurface.adapter)
            val question = checkNotNull(route.askFromUser(external.taskSurface.request))
            assertTrue(route.answerFromUser(external.taskSurface.request, question, TaskSurface.FOREGROUND))
            check(f.taskSurfaces.save(TaskSurfacePreference.BACKGROUND))
            assertEquals(TaskSurface.FOREGROUND, external.taskSurface.ready(TaskSurface.FOREGROUND)?.surface)
            assertSame(route, external.taskSurface.adapter)
            controlMain { f.controller.cancelGui(external) }
            assertNoAcceptance(f)
        } finally { controlMain { f.close() } }
    }

    fun testVdDirectSendRequiresOriginalBackgroundDecisionAndNeverConvertsForeground() {
        val allocations = AtomicInteger()
        val f = RunControlFixture(instrumentation.targetContext,
            virtualDisplayFactory = ControllerVirtualDisplayFactory { _, _, _, _, _, _, _ ->
                allocations.incrementAndGet(); error("location rejection must precede factory")
            })
        try {
            awaitControl("controller ready") { f.controller.ready }
            val foreground = vd(f, listOf("example.external"))
            controlMain {
                assertFalse(f.controller.sendVirtualDisplay(foreground, ChatController.TextSendTicket()) { error("not accepted") })
            }
            assertEquals(TaskSurface.FOREGROUND, (foreground.taskSurface.freezeOnWorker() as TaskSurfaceDecision.Ready).surface)
            controlMain {
                assertFalse(f.controller.sendVirtualDisplay(foreground, ChatController.TextSendTicket()) { error("not accepted") })
                f.controller.cancelVirtualDisplay(foreground)
            }
            check(f.taskSurfaces.save(TaskSurfacePreference.ASK))
            val asked = vd(f)
            assertSame(TaskSurfaceDecision.AskRequired, asked.taskSurface.freezeOnWorker())
            val route = checkNotNull(asked.taskSurface.adapter)
            val question = checkNotNull(route.askFromUser(asked.taskSurface.request))
            controlMain {
                assertFalse(f.controller.sendVirtualDisplay(asked, ChatController.TextSendTicket()) { error("not accepted") })
            }
            assertTrue(route.answerFromUser(asked.taskSurface.request, question, null))
            check(f.taskSurfaces.save(TaskSurfacePreference.BACKGROUND))
            assertTrue(asked.taskSurface.decision() is TaskSurfaceDecision.NotExecuted)
            controlMain {
                assertFalse(f.controller.sendVirtualDisplay(asked, ChatController.TextSendTicket()) { error("cancelled") })
                f.controller.cancelVirtualDisplay(asked)
            }
            assertEquals(0, allocations.get())
            assertNoAcceptance(f)
        } finally { controlMain { f.close() } }
    }

    fun testVdWholeSelfOrMixedPlanRejectsWithoutAskFactoryModelOrAcceptance() {
        val allocations = AtomicInteger()
        val f = RunControlFixture(instrumentation.targetContext,
            virtualDisplayFactory = ControllerVirtualDisplayFactory { _, _, _, _, _, _, _ ->
                allocations.incrementAndGet(); error("self plan has no VD backend")
            })
        try {
            awaitControl("controller ready") { f.controller.ready }
            check(f.taskSurfaces.save(TaskSurfacePreference.ASK))
            for (targets in listOf(listOf(f.application.packageName), listOf("example.external", f.application.packageName))) {
                val entry = vd(f, targets)
                assertTrue(entry.taskSurface.freezeOnWorker() is TaskSurfaceDecision.NotExecuted)
                assertNull(entry.taskSurface.ready(TaskSurface.BACKGROUND))
                controlMain {
                    assertFalse(f.controller.sendVirtualDisplay(entry, ChatController.TextSendTicket()) { error("not accepted") })
                    assertNull(entry.owner); assertNull(entry.session)
                    f.controller.cancelVirtualDisplay(entry)
                }
            }
            assertEquals(0, allocations.get())
            assertNoAcceptance(f)
        } finally { controlMain { f.close() } }
    }
}
