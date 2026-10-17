package app.daiyu.android

import android.test.InstrumentationTestCase

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
        val listener: () -> Unit = { calls++; if (calls == 1) notifyChanged(); Unit }
        onMain {
            fixture.controller.observe(listener)
            repeat(32) { notifyChanged() }
        }
        onMain { assertEquals(2, calls); fixture.controller.unobserve(listener) }
    }

    fun testRemovedObserverDoesNotReceiveQueuedChangeAndReplacementRemainsUsable() {
        var oldCalls = 0; var newCalls = 0
        val old: () -> Unit = { oldCalls++ }
        val replacement: () -> Unit = { newCalls++ }
        onMain {
            fixture.controller.observe(old)
            notifyChanged()
            fixture.controller.unobserve(old)
            fixture.controller.observe(replacement)
        }
        onMain {
            assertEquals(0, oldCalls); assertEquals(1, newCalls)
            notifyChanged()
        }
        onMain { assertEquals(2, newCalls); fixture.controller.unobserve(replacement) }
    }
}
