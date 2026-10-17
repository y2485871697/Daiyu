package app.daiyu.android

import androidx.compose.ui.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Production Main navigation and real Controller preparation. No root, owner or transport is
 * opened by these tests. Accepted/lease behavior is covered by ControllerVirtualDisplayParentTest. */
class VirtualDisplayMainActivityTest : WiredActivityTestCase() {
    private val text = "只在本次批准的副屏运行\n保留原始正文  "
    private fun invoke(name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)

    private fun prepare(): ControllerVirtualDisplayEntry {
        setDraft(text)
        node("vd-prepare-entry").performScrollTo().performClick()
        node("r08-plan-freeze").performClick()
        return checkNotNull(fixture.controller.virtualDisplayEntry())
    }

    fun testPreparationIsExplicitExclusiveAndSidebarSynchronouslyRevokesIt() {
        val entry = prepare()
        assertNull(entry.owner); assertNull(entry.session); assertNull(entry.catalog)
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        node("browser-approval-entry").assertIsNotEnabled()
        node("generation-approval-entry").assertIsNotEnabled()
        mainThread {
            invoke("openSidebar")
            assertTrue(entry.revoked)
            assertNull(fixture.controller.virtualDisplayEntry())
        }
        backToChat(activity)
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
    }

    fun testSelectionAbaDoesNotRestoreOldPreparationOrCancelReplacement() {
        val entry = prepare()
        mainThread { activity.select(null) }
        assertTrue(entry.revoked)
        assertNull(fixture.controller.virtualDisplayEntry())
        val next = prepare()
        mainThread {
            assertFalse(fixture.controller.cancelVirtualDisplay(entry))
            assertSame(next, fixture.controller.virtualDisplayEntry())
            assertFalse(next.revoked)
            invoke("submitVirtualDisplay", entry)
        }
        assertFalse(next.submitted)
        assertEquals(0, fixture.opens.get())
        assertDraft(text)
    }

    fun testRecreationFencesQueuedSubmissionBeforeFactoryAndKeepsRejectedDraft() {
        val entry = prepare()
        val executor = ChatController::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(fixture.controller) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            node("vd-submit").performScrollTo().performClick()
            assertTrue(entry.submitted)
            activity = recreateActivity(activity)
            assertTrue(entry.revoked)
            assertNull(entry.owner); assertNull(entry.session)
            release.countDown()
            ui.waitUntil(5_000) { fixture.controller.virtualDisplayEntry() == null }
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.conversations().isEmpty())
            assertDraft(text)
        } finally { release.countDown() }
    }

    fun testSettingsNavigationDropsOnlyOriginalMountAndDoesNotSend() {
        val entry = prepare()
        openSettings()
        assertTrue(entry.revoked)
        assertNull(fixture.controller.virtualDisplayEntry())
        assertEquals(0, fixture.opens.get())
        assertEquals(text, fixture.database.draft())
    }
}
