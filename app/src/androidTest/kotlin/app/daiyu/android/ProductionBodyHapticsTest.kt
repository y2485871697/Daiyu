package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.haptics.*
import app.daiyu.android.haptics.*
import java.util.concurrent.CopyOnWriteArrayList

class ProductionBodyHapticsTest : WiredActivityTestCase() {
    override val requiresWindowDraw = true

    fun testActualSendPaintsBodyAndDeliversGenerationTicksToMotorPort() {
        val context = instrumentation.targetContext
        val prefs = "body-port-${System.nanoTime()}"
        val times = CopyOnWriteArrayList<Long>()
        val field = MainActivity::class.java.getDeclaredField("hapticsHost").apply { isAccessible = true }
        val session = fixture.session("结束", holdSeconds = 120)
        var runtime: HapticsRuntime? = null
        try {
            mainThread {
                (field.get(activity) as HapticsActivityHost).close()
                val testRuntime = HapticsRuntime(LocalHapticSettingsStore(context.getSharedPreferences(prefs, 0))) {
                    object : HapticPort {
                        override fun play(request: HapticRequest): HapticDelivery {
                            if (request.generation && request.kind == HapticKind.TICK) times.add(android.os.SystemClock.uptimeMillis())
                            return HapticDelivery.PLAYED
                        }
                        override fun cancel() = Unit
                    }
                }
                runtime = testRuntime
                field.set(activity, HapticsActivityHost(testRuntime, activity.window.decorView, activity, fixture.controller))
            }
            setDraft("正文触感验证")
            node("chat-send").performClick()
            ui.waitUntil(10_000) { session.entered.count == 0L }
            val send = checkNotNull(session.sink.get())
            repeat(4) { n ->
                val marker = "正文第${n}段"
                val before = times.size
                send(ModelEvent.TextDelta("body", "\n\n" + "实验文字".repeat(16) + marker))
                try { ui.waitUntil(10_000) { ui.onAllNodesWithText(marker, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() } } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Missing burst $n, ticks=${times.size}, tree=${ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 24)}", failure)
                }
                ui.waitUntil(5_000) { times.size >= before + 2 }
            }
            assertTrue("Four separately painted bursts must reach the motor", times.size >= 8)
        } finally {
            session.release.countDown()
            mainThread { (field.get(activity) as? HapticsActivityHost)?.close(); field.set(activity, null); runtime?.close() }
            context.deleteSharedPreferences(prefs)
        }
    }
}
