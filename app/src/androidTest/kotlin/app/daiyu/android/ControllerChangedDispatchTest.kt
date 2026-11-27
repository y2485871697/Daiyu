package app.daiyu.android

import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class ControllerChangedDispatchTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext)
        awaitControl("controller ready") { fixture.controller.ready }
        instrumentation.waitForIdleSync()
    }
    override fun tearDown() {
        try { fixture.close() } finally { super.tearDown() }
    }
    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
        instrumentation.waitForIdleSync()
    }
    private fun notifyChanged() = ChatController::class.java.getDeclaredMethod("notifyChanged")
        .apply { isAccessible = true }.invoke(fixture.controller)

    fun testQueuedStateChangesCoalesceWithoutCoalescingReentrantChanges() {
        var calls = 0
        val delivered = CountDownLatch(2)
        val listener: () -> Unit = {
            calls++
            if (calls == 1) notifyChanged()
            delivered.countDown()
        }
        onMain {
            fixture.controller.observe(listener)
            repeat(32) { notifyChanged() }
            assertEquals(0, calls)
        }
        assertTrue("original and reentrant changes were both delivered", delivered.await(5, TimeUnit.SECONDS))
        onMain { assertEquals(2, calls); fixture.controller.unobserve(listener) }
    }

    fun testRemovedObserverDoesNotReceiveQueuedChangeAndReplacementRemainsUsable() {
        var oldCalls = 0; var newCalls = 0
        val first = CountDownLatch(1)
        val both = CountDownLatch(2)
        val old: () -> Unit = { oldCalls++ }
        val replacement: () -> Unit = { newCalls++; first.countDown(); both.countDown() }
        onMain {
            fixture.controller.observe(old)
            notifyChanged()
            fixture.controller.unobserve(old)
            fixture.controller.observe(replacement)
        }
        assertTrue("replacement received the queued change", first.await(5, TimeUnit.SECONDS))
        onMain {
            assertEquals(0, oldCalls); assertEquals(1, newCalls)
            notifyChanged()
        }
        assertTrue("replacement received the next change", both.await(5, TimeUnit.SECONDS))
        onMain {
            assertEquals(0, oldCalls); assertEquals(2, newCalls)
            fixture.controller.unobserve(replacement)
        }
    }
}
