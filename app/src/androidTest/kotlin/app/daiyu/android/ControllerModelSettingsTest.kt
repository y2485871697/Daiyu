package app.daiyu.android

import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.model.catalog.selectionFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerModelSettingsTest : InstrumentationTestCase() {
    fun testSaveReservesControllerBeforeWaitingForCatalogLease() = blockedMutation(select = false)
    fun testSelectionReservesControllerBeforeWaitingForCatalogLease() = blockedMutation(select = true)

    fun testLocalTicketReservesControllerThenRejectsRevisionChangedWhileWaiting() = blockedMutation(select = true, local = true)

    fun testStaleLocalTicketFailsWithoutChangingConfiguration() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val before = fixture.settings.load()
            val ticket = requireNotNull(fixture.settings.modelCatalogSnapshot().selectionFor(before.model))
            fixture.settings.save(before.copy(model = "new-local-current"))
            val changed = fixture.settings.load()
            val completed = CountDownLatch(1)
            val result = AtomicReference<Boolean>()
            controlMain {
                fixture.controller.selectConfiguredCatalogModel(changed, ticket) {
                    result.set(it); completed.countDown()
                }
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(false, result.get())
            assertEquals(changed, fixture.settings.load())
            assertEquals(0, fixture.opens.get())
        } finally { fixture.close() }
    }

    private fun blockedMutation(select: Boolean, local: Boolean = false) {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<Boolean>()
        var holder: Thread? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val before = fixture.settings.load()
            val ticket = if (local) requireNotNull(fixture.settings.modelCatalogSnapshot().selectionFor(before.model)) else null
            holder = Thread {
                try {
                    fixture.settings.modelCatalog.configurationChange {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                } catch (problem: Throwable) { failure.set(problem) }
            }.apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controlMain {
                val onComplete: (Boolean) -> Unit = { success ->
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    result.set(success)
                    completed.countDown()
                }
                if (ticket != null) fixture.controller.selectConfiguredCatalogModel(before, ticket, onComplete)
                else if (select) fixture.controller.selectConfiguredModel(before, "selected-model", onComplete)
                else fixture.controller.saveModelSettings(before.copy(model = "saved-model"), false, false, onComplete)
                assertTrue(fixture.controller.dataBusy)
                assertEquals(0, fixture.opens.get())
            }
            assertNull(fixture.controller.send(null, "must not start while saving"))
            assertEquals(1L, completed.count)
            release.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertEquals(!local, result.get())
            assertEquals(if (local) before.model else if (select) "selected-model" else "saved-model", fixture.settings.load().model)
            controlMain { assertFalse(fixture.controller.dataBusy) }
        } finally {
            release.countDown()
            holder?.join(10_000)
            fixture.close()
        }
    }
}
