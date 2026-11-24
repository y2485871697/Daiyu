package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.haptics.*
import app.daiyu.android.haptics.*
import java.util.concurrent.CopyOnWriteArrayList

class ProductionBodyHapticsTest : WiredActivityTestCase() {
    override val requiresWindowDraw = true

    private val times = CopyOnWriteArrayList<Long>()
    private val prefs = "body-port-${System.nanoTime()}"
    private val runtimeField = ChatApplication::class.java.getDeclaredField("hapticsRuntime").apply { isAccessible = true }
    private var original: HapticsRuntime? = null
    private var recording: HapticsRuntime? = null

    override fun createFixture(): RunControlFixture {
        val context = instrumentation.targetContext
        val app = context.applicationContext as ChatApplication
        mainThread {
            original = app.hapticsRuntime
            val runtime = HapticsRuntime(LocalHapticSettingsStore(context.getSharedPreferences(prefs, 0))) {
                object : HapticPort {
                    override fun play(request: HapticRequest): HapticDelivery {
                        if (request.generation && request.kind == HapticKind.TICK) times.add(android.os.SystemClock.uptimeMillis())
                        return HapticDelivery.PLAYED
                    }
                    override fun cancel() = Unit
                }
            }
            recording = runtime
            runtime.installForeground(app)
            runtimeField.set(app, runtime)
        }
        return super.createFixture()
    }

    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally {
            val context = instrumentation.targetContext
            mainThread {
                original?.let { runtimeField.set(context.applicationContext, it) }
                original = null
                recording?.close()
                recording = null
            }
            context.deleteSharedPreferences(prefs)
        }
    }

    fun testCompletedTransportStillTicksWhilePaintingButScrollingSettledBodyIsSilent() {
        val session = fixture.session("长段落继续逐字显示。".repeat(80) + "正文结束标记", holdSeconds = 120)
        try {
            setDraft("正文收尾触感")
            val send = node("chat-send")
            awaitNodeWindowFocus(send)
            send.performClick()
            ui.waitUntil(10_000) { session.entered.count == 0L }
            node("chat-control").assertExists()
            session.release.countDown()
            ui.waitUntil(10_000) { fixture.controller.active == null }
            val before = times.size
            ui.waitUntil(10_000) { times.size >= before + 2 }
            ui.waitUntil(20_000) { ui.onAllNodesWithTag("chat-send").fetchSemanticsNodes().size == 1 }
            val settled = times.size
            node("chat-messages").performTouchInput { swipeDown() }
            node("chat-messages").performTouchInput { swipeUp() }
            ui.waitForIdle()
            assertEquals("Scrolling settled body must not replay generation ticks", settled, times.size)
        } finally { session.release.countDown() }
    }

    fun testActualSendPaintsBodyAndDeliversGenerationTicksToMotorPort() {
        val session = fixture.session("结束", holdSeconds = 120)
        try {
            val draft = "正文触感验证"
            setDraft(draft)
            assertDraft(draft)
            val button = node("chat-send")
            awaitNodeWindowFocus(button)
            button.assertIsDisplayed().assertIsEnabled().assertContentDescriptionEquals("发送").performClick()
            try {
                ui.waitUntil(10_000) { session.entered.count == 0L }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                val pending = fixture.controller.textSendState()
                throw AssertionError("Send clicked with matching draft and enabled button; " +
                    "ready=${fixture.controller.ready}, preparing=${pending != null}, " +
                    "receipt=${pending?.result?.javaClass?.simpleName}, active=${fixture.controller.active != null}, " +
                    "modelOpens=${fixture.opens.get()}, persistedDraftMatches=${fixture.database.draft() == draft}", failure)
            }
            val send = checkNotNull(session.sink.get())
            repeat(4) { n ->
                val marker = "正文第${n}段"
                val before = times.size
                send(ModelEvent.TextDelta("body", "\n\n" + "实验文字".repeat(16) + marker))
                try { ui.waitUntil(10_000) { ui.onAllNodesWithText(marker, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() } } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Missing burst $n, ticks=${times.size}, tree=${ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 24)}", failure)
                }
                try { ui.waitUntil(5_000) { times.size >= before + 2 } }
                catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Burst $n reached its marker but motor ticks went from $before to ${times.size}", failure)
                }
            }
            assertTrue("Four separately painted bursts must reach the motor", times.size >= 8)
        } finally {
            session.release.countDown()
        }
    }
}
