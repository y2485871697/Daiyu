package app.daiyu.android

import android.content.Intent
import androidx.compose.ui.test.*
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
                    BalanceResult.Available(BalanceMoney(amount.get().toBigDecimal(), "CNY"), time.incrementAndGet())
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
    private fun bindings(): Map<*, *> {
        var value: Map<*, *> = emptyMap<Any, Any>()
        mainThread {
            value = MainActivity::class.java.getDeclaredField("balanceLifecycles").apply { isAccessible = true }
                .get(activity) as Map<*, *>
        }
        return value
    }

    fun testUnselectedSharedProviderRefreshesAndPollingOwnersSurviveModelLabelReadsAndBackground() {
        val primary = runBlocking { testHost.get(ProviderRegistry.PRIMARY) }
        val other = runBlocking { testHost.get(secondary) }
        ui.waitUntil(5000) { primary.isPolling && other.isPolling && primary.state.value.successful != null && other.state.value.successful != null }
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
        assertTrue(calls.getValue(secondary).get() > 0)
        val originalBindings = bindings()
        invoke("refreshConfirmedModelLabel")
        ui.waitUntil(5000) { bindings().size == 2 }
        originalBindings.forEach { (slot, binding) -> assertSame(binding, bindings()[slot]) }

        amount.set("8.09")
        invoke("openChatModels")
        ui.waitUntil(5000) { primary.state.value.amountValue?.toPlainString() == "8.09" && other.state.value.amountValue?.toPlainString() == "8.09" }
        for (slot in listOf(ProviderRegistry.PRIMARY, secondary)) {
            ui.waitUntil(5000) { ui.onAllNodesWithTag("chat-model-provider-balance:$slot", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasText("8.09") and hasAnyAncestor(hasTestTag("chat-model-provider-balance:$slot")),
                useUnmergedTree = true).assertIsDisplayed()
        }
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
        assertEquals(0, fixture.opens.get())
        invoke("dismissChatModels")
        mainThread { assertTrue(activity.moveTaskToBack(true)) }
        ui.waitUntil(5000) { activity.lifecycle.currentState == Lifecycle.State.CREATED && !primary.isPolling && !other.isPolling }
        mainThread {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        ui.waitUntil(5000) { primary.isPolling && other.isPolling }
        assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
        assertEquals(0, fixture.opens.get())
    }
}
