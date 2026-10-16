package app.daiyu.android.haptics

import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.RunControlFixture
import app.daiyu.android.awaitControl
import app.daiyu.android.controlMain
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Controller + production Activity host wiring, with a provider that ignores cancellation.
 * No manual Run.stop, synthetic renderer progress, reflection or terminal-status substitution. */
@Suppress("DEPRECATION")
class ControllerHapticsStopTest : InstrumentationTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Port : HapticPort {
        val plays = AtomicInteger()
        val cancels = AtomicInteger()
        override fun play(request: HapticRequest): HapticDelivery {
            plays.incrementAndGet()
            return HapticDelivery.PLAYED
        }
        override fun cancel() { cancels.incrementAndGet() }
    }

    fun testOwnedStopRevokesPendingTicksAndQueuedReasoningBeforeWorkerExits() =
        stopRace(unqualified = false, background = false)

    fun testUnqualifiedStopRevokesPendingTicksAndQueuedReasoningBeforeWorkerExits() =
        stopRace(unqualified = true, background = false)

    fun testBackgroundStopFencesAnAlreadyDueTickBeforeMainThreadDisposal() =
        stopRace(unqualified = false, background = true)

    private fun stopRace(unqualified: Boolean, background: Boolean) {
        val f = RunControlFixture(instrumentation.targetContext)
        val beforePause = f.session(releaseOnCancel = false)
        val old = f.session(releaseOnCancel = false)
        val ports = mutableListOf<Port>()
        val events = mutableListOf<ModelEvent>()
        val failure = AtomicReference<Throwable?>()
        val stopped = CountDownLatch(1)
        var runtime: HapticsRuntime? = null
        var host: HapticsActivityHost? = null
        var subscription: AutoCloseable? = null
        var owner: Message? = null
        var stoppedPlays = -1
        var stoppedCancels = -1
        try {
            awaitControl("controller ready") { f.controller.ready }
            controlMain {
                val conversation = checkNotNull(f.controller.send(null, "hold provider"))
                owner = checkNotNull(f.controller.active)
                val lifecycle = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
                val r = HapticsRuntime(LocalHapticSettingsStore(f.application.getSharedPreferences(
                    "stop-haptics", 0))) { Port().also { ports += it } }
                runtime = r
                host = HapticsActivityHost(r, View(f.application), lifecycle, f.controller).also {
                    // Genuine stream/background path; visible renderer capability remains unused.
                    it.sync(conversation.id.value, chatVisible = false, settingsVisible = false)
                }
                subscription = f.controller.observeReasoning { _, event ->
                    events += event
                    if (event is ModelEvent.ReasoningDelta && event.itemId == "pending") {
                        try {
                            // Host's earlier listener has now queued 36 real background ticks.
                            // This accepted event is posted behind this callback, before stop.
                            checkNotNull(old.sink.get())(ModelEvent.ReasoningFinal("queued", ""))
                            val stop = {
                                if (unqualified) f.controller.stop()
                                else assertTrue(f.controller.stop(checkNotNull(owner)))
                            }
                            if (background) {
                                // Make the tick older than Session's main-thread detach message.
                                // Blocking main here is deliberate: the revocation must not wait for it.
                                SystemClock.sleep(45)
                                val workerFailure = AtomicReference<Throwable?>()
                                val worker = Thread {
                                    try { stop() } catch (t: Throwable) { workerFailure.set(t) }
                                }.apply { isDaemon = true; start() }
                                worker.join(2000)
                                assertFalse("stop waited for main", worker.isAlive)
                                workerFailure.get()?.let { throw it }
                            } else stop()
                            assertEquals(RunStatus.RUNNING, f.controller.active?.status)
                            assertEquals(1L, old.release.count) // no terminal worker result is possible
                            stoppedPlays = ports.sumOf { it.plays.get() }
                            stoppedCancels = ports.sumOf { it.cancels.get() }
                            assertTrue("accepted stop cancels the playing owner", stoppedCancels > 0)
                        } catch (t: Throwable) { failure.set(t) }
                        finally { stopped.countDown() }
                    }
                }
            }
            assertTrue(beforePause.entered.await(5, TimeUnit.SECONDS))
            controlMain {
                // Pause is not a lifetime fence: already accepted, queued reasoning still reaches
                // this Run. The transport's old sink is correctly invalidated by pause itself.
                checkNotNull(beforePause.sink.get())(ModelEvent.ReasoningFinal("warmup", ""))
                assertTrue(f.controller.pause(checkNotNull(owner)))
            }
            awaitControl("real reasoning completion played while pause requested") {
                ports.sumOf { it.plays.get() } == 1
            }
            controlMain {
                assertEquals(0, ports.sumOf { it.cancels.get() })
                assertTrue(f.controller.resume(checkNotNull(owner)))
            }
            beforePause.release.countDown()
            assertTrue("resumed transport entered", old.entered.await(5, TimeUnit.SECONDS))
            controlMain {
                checkNotNull(old.sink.get())(ModelEvent.ReasoningDelta("pending", "abcdefghijklmnopqrstuvwxyz0123456789"))
            }
            assertTrue("stop callback ran", stopped.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            // Drain queued reasoning, the already scheduled tick and detach, but not the provider.
            SystemClock.sleep(150)
            controlMain {
                assertEquals(RunStatus.RUNNING, f.controller.active?.status)
                assertEquals(1L, old.release.count)
                assertFalse("queued pre-stop reasoning was delivered", events.any {
                    it is ModelEvent.ReasoningFinal && it.itemId == "queued"
                })
                assertEquals(stoppedPlays, ports.sumOf { it.plays.get() })
                assertEquals(stoppedCancels, ports.sumOf { it.cancels.get() })
                // A newly mounted host must not resurrect the fenced RUNNING snapshot either.
                host?.sync(null, chatVisible = false, settingsVisible = false)
                host?.sync(checkNotNull(owner).conversationId.value, false, false)
            }
            old.release.countDown()
            awaitControl("old provider really exited") { f.controller.active == null }

            val next = f.session(releaseOnCancel = false)
            controlMain {
                val conversation = f.database.conversations().single()
                assertNotNull(f.controller.send(conversation, "next run"))
            }
            assertTrue(next.entered.await(5, TimeUnit.SECONDS))
            awaitControl("new run observer mounted") { f.controller.active != null }
            controlMain {
                // A stale control identity must neither fence nor cancel the replacement Run.
                assertFalse(f.controller.stop(checkNotNull(owner)))
                assertEquals(stoppedCancels, ports.sumOf { it.cancels.get() })
                checkNotNull(next.sink.get())(ModelEvent.ReasoningFinal("new-run", ""))
            }
            awaitControl("replacement run still plays") { ports.sumOf { it.plays.get() } > stoppedPlays }
            next.release.countDown()
            awaitControl("replacement provider exits") { f.controller.active == null }
        } finally {
            beforePause.release.countDown()
            old.release.countDown()
            controlMain { subscription?.close(); host?.close(); runtime?.close() }
            f.application.getSharedPreferences("stop-haptics", 0).edit().clear().commit()
            f.close()
        }
    }
}
