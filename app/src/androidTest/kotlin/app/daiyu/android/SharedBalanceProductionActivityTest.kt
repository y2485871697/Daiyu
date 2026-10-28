package app.daiyu.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import app.daiyu.android.balance.*
import app.daiyu.android.storage.ProviderRegistry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real Activity ownership, without selecting the second provider or making model/network calls. */
class SharedBalanceProductionActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private lateinit var originalHost: ProviderBalanceRuntimeHost
    private lateinit var testHost: ProviderBalanceRuntimeHost
    private lateinit var secondary: String
    private lateinit var disabled: String
    private val amount = AtomicReference("8.22")
    private val time = AtomicLong(System.currentTimeMillis() + 1000)
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }

    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext,
        providerBalanceHost = testHost).also { fixture ->
        val providers = fixture.controller.providers
        val registered = providers.registerConfigured(providers.snapshot().revision, "共享余额",
            fixture.settings.load(), protocolChangeAuthorized = false, enabled = true, admit = {})
        secondary = registered.providers.single { it.slotId != ProviderRegistry.PRIMARY }.slotId
        val withDisabled = providers.registerConfigured(registered.revision, "未启用但可查余额",
            fixture.settings.load(), protocolChangeAuthorized = false, enabled = false, admit = {})
        disabled = withDisabled.providers.single { it.slotId != ProviderRegistry.PRIMARY && it.slotId != secondary }.slotId
    }

    override fun setUp() {
        try {
            val app = instrumentation.targetContext.applicationContext as ChatApplication
            originalHost = app.providerBalanceHost
            testHost = ProviderBalanceRuntimeHost { slot ->
                BalanceRuntime(ProviderBalanceStore(Memory()).also {
                    it.save(BalanceConfiguration(slot, "https://balance.example.test", "balance-only-key", "", "CNY",
                        BalanceOption(enabled = true, apiPath = "/balance", resultPath = "balance")))
                }, BalanceQuery {
                    calls.computeIfAbsent(slot) { AtomicInteger() }.incrementAndGet()
                    val value = amount.get().toBigDecimal() + if (slot == ProviderRegistry.PRIMARY) java.math.BigDecimal.ZERO else "0.10".toBigDecimal()
                    BalanceResult.Available(BalanceMoney(value, "CNY"), time.incrementAndGet())
                })
            }
            mainThread { app.providerBalanceHost = testHost }
            super.setUp()
        } catch (failure: Throwable) {
            try { cleanupFixture() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally {
            try {
                if (::originalHost.isInitialized) mainThread {
                    (instrumentation.targetContext.applicationContext as ChatApplication).providerBalanceHost = originalHost
                }
            } finally { if (::testHost.isInitialized) testHost.close() }
        }
    }

    private fun invoke(name: String) = mainThread {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    fun testUnselectedProvidersRefreshIndependentlyAndGlobalPollingSurvivesBackground() {
        val primary = runBlocking { testHost.get(ProviderRegistry.PRIMARY) }
        val other = runBlocking { testHost.get(secondary) }
        val disabledRuntime = runBlocking { testHost.get(disabled) }
        ui.waitUntil(5000) { testHost.isPolling && primary.state.value.successful != null &&
            other.state.value.successful != null && disabledRuntime.state.value.successful != null }
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
        assertTrue(calls.getValue(secondary).get() > 0)
        assertTrue(calls.getValue(disabled).get() > 0)
        assertEquals("8.22", primary.state.value.amountValue?.toPlainString())
        assertEquals("8.32", other.state.value.amountValue?.toPlainString())
        invoke("refreshConfirmedModelLabel")
        assertSame(primary, runBlocking { testHost.get(ProviderRegistry.PRIMARY) })

        amount.set("8.09")
        invoke("openChatModels")
        ui.waitUntil(5000) { primary.state.value.amountValue?.toPlainString() == "8.09" &&
            other.state.value.amountValue?.toPlainString() == "8.19" }
        ui.waitUntil(5000) {
            ui.onAllNodesWithTag("chat-model-picker").fetchSemanticsNodes().singleOrNull()?.config
                ?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) == "模型目录读取完成"
        }
        for ((slot, value) in listOf(ProviderRegistry.PRIMARY to "8.09", secondary to "8.19")) {
            ui.onNodeWithTag("chat-model-list", useUnmergedTree = true)
                .scrollToDescendant(hasTestTag("chat-model-provider-balance:$slot"))
            ui.onNode(hasText(value) and hasAnyAncestor(hasTestTag("chat-model-provider-balance:$slot")),
                useUnmergedTree = true).assertIsDisplayed()
        }
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
        assertEquals(0, fixture.opens.get())
        invoke("dismissChatModels")
        mainThread { assertTrue(activity.moveTaskToBack(true)) }
        ui.waitUntil(5000) { activity.lifecycle.currentState == Lifecycle.State.CREATED }
        assertTrue(testHost.isPolling)
        val before = calls.getValue(disabled).get()
        amount.set("7.99")
        ui.waitUntil(35_000) { calls.getValue(disabled).get() > before &&
            disabledRuntime.state.value.amountValue?.toPlainString() == "8.09" }
        mainThread {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        ui.waitUntil(5000) { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        assertTrue(testHost.isPolling)
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
        assertEquals(0, fixture.opens.get())
    }

    fun testChatCompletionAndStopRefreshAllProvidersButPauseAndDeltasDoNot() {
        ui.waitUntil(5000) { calls.size == 3 && testHost.isPolling &&
            listOf(ProviderRegistry.PRIMARY, secondary, disabled).all { testHost.cached(it)?.state?.value?.refreshing == false } }
        val session = fixture.session(held = true, holdSeconds = 30)
        val resumed = fixture.session(text = "恢复后的回答", held = true, holdSeconds = 30)
        checkNotNull(fixture.controller.send(null, "余额结束刷新"))
        assertTrue(session.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val baseline = calls.mapValues { it.value.get() }
        val owner = checkNotNull(fixture.controller.active)
        checkNotNull(session.sink.get())(app.daiyu.android.domain.ModelEvent.TextDelta("body", "增量"))
        ui.waitForIdle()
        assertEquals(baseline, calls.mapValues { it.value.get() })
        mainThread { assertTrue(fixture.controller.pause(owner)) }
        assertTrue(session.cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        awaitControl("paused boundary") {
            fixture.controller.runState(owner)?.phase == app.daiyu.android.domain.runtime.ExecutionPhase.PAUSED
        }
        assertEquals(baseline, calls.mapValues { it.value.get() })
        mainThread { assertTrue(fixture.controller.resume(owner)) }
        assertTrue(resumed.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        amount.set("7.22")
        resumed.release.countDown()
        ui.waitUntil(5000) { baseline.all { (slot, count) -> calls.getValue(slot).get() > count } }
        awaitControl("completion cleanup") { fixture.controller.active == null && !fixture.controller.dataBusy }

        val completed = fixture.database.messages(owner.conversationId).last().second
        assertEquals(app.daiyu.android.domain.RunStatus.COMPLETED, completed.status)
        assertTrue(completed.blocks.filterIsInstance<app.daiyu.android.domain.MessageBlock.Text>()
            .joinToString("") { it.text }.contains("恢复后的回答"))

        val stopped = fixture.session(held = true, holdSeconds = 30, releaseOnCancel = false)
        checkNotNull(fixture.controller.send(null, "停止也补刷"))
        assertTrue(stopped.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val beforeStop = calls.mapValues { it.value.get() }
        mainThread { assertTrue(fixture.controller.stopMain(checkNotNull(fixture.controller.active))) }
        ui.waitUntil(5000) { beforeStop.all { (slot, count) -> calls.getValue(slot).get() > count } }
        assertEquals(1L, stopped.release.count)
        val stoppedOwner = checkNotNull(fixture.controller.active)
        stopped.release.countDown()
        awaitControl("stop cleanup") { fixture.controller.active == null && !fixture.controller.dataBusy }
        assertEquals(app.daiyu.android.domain.RunStatus.STOPPED,
            fixture.database.messages(stoppedOwner.conversationId).last().second.status)
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
    }
}
