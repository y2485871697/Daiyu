package app.daiyu.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import app.daiyu.android.balance.*
import app.daiyu.android.storage.ProviderRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real MainActivity/settings/back/top-bar wiring with a process-owned, isolated in-memory P05
 * host. No user balance namespace, Keystore, real network or paid model API is used by this fixture.
 * System backgrounding deliberately leaves settings composed: cancellation must be ON_STOP based.
 */
class BalanceProductionActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private lateinit var originalHost: ProviderBalanceRuntimeHost
    private lateinit var testHost: ProviderBalanceRuntimeHost
    private lateinit var runtime: BalanceRuntime
    private val draftCalls = AtomicInteger()
    private val draftConfiguration = AtomicReference<BalanceConfiguration>()
    private val calls = AtomicInteger()
    private val hold = AtomicBoolean()
    private val fail = AtomicBoolean()
    private val entered = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()

    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }

    override fun setUp() {
        try {
            val app = instrumentation.targetContext.applicationContext as ChatApplication
            originalHost = app.providerBalanceHost
            testHost = ProviderBalanceRuntimeHost(factory = { slotId ->
                check(slotId == ProviderRegistry.PRIMARY)
                BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
                    calls.incrementAndGet()
                    if (hold.get()) try {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    } finally { cancelled.complete(Unit) }
                    if (fail.get()) BalanceResult.Failed(BalanceFailure.NETWORK)
                    else BalanceResult.Available(BalanceMoney("12.34".toBigDecimal(), "CNY"), 1234L)
                })
            }, draftQuery = BalanceQuery { configuration ->
                draftCalls.incrementAndGet(); draftConfiguration.set(configuration)
                BalanceResult.Available(BalanceMoney("56.78".toBigDecimal(), "CNY"), 5678L)
            })
            runtime = runBlocking {
                testHost.get(ProviderRegistry.PRIMARY).also {
                    it.save(BalanceConfiguration("test-balance-account", "https://example.test", "balance-only-key",
                        "", "CNY", BalanceOption(enabled = true, apiPath = "/balance", resultPath = "balance")))
                }
            }
            mainThread { app.providerBalanceHost = testHost }
            super.setUp()
            assertEquals(ProviderRegistry.PRIMARY, fixture.controller.providers.activeEditor().slotId)
            ui.waitUntil(timeoutMillis = 5000) { runtime.state.value.successful != null }
        } catch (failure: Throwable) {
            try { cleanupFixture() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun restoreHost() {
        try {
            if (::originalHost.isInitialized) mainThread {
                (instrumentation.targetContext.applicationContext as ChatApplication).providerBalanceHost = originalHost
            }
        } finally { if (::testHost.isInitialized) testHost.close() }
    }

    override fun cleanupFixture() {
        super.cleanupFixture()
        restoreHost()
    }

    private fun openBalance() {
        openSettings()
        val open = retainedSettingsEntry("balance")
        mainThread { open() }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("独立余额设置").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText("独立余额设置").assertIsDisplayed()
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodesWithText("独立余额账户标识").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun querySaved() {
        ui.onNodeWithText("测试已保存的余额查询").performScrollTo().assertIsEnabled().performClick()
    }

    private fun assertTopBarMoney() {
        node("chat-provider-balance").assertIsDisplayed().assertHasNoClickAction()
        ui.onNode(hasText("CNY 12.34") and hasAnyAncestor(hasTestTag("chat-provider-balance")),
            useUnmergedTree = true).assertIsDisplayed()
    }

    fun testSettingsRouteBackAndActivityRecreationRetainTheSameProcessRuntime() {
        assertTopBarMoney()
        openBalance()
        val revision = runtime.state.value.revision
        activity = recreateActivity(activity)
        ui.onNodeWithText("独立余额设置").assertIsDisplayed()
        assertSame(runtime, runBlocking { testHost.get(ProviderRegistry.PRIMARY) })
        assertEquals(revision, runtime.state.value.revision)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        node("page:设置").assertIsDisplayed()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertTopBarMoney()
    }

    fun testProviderDraftUsesIndependentInjectedQueryWithoutSavingOrReplacingTopBar() {
        val savedBalance = runtime.store.configurationForEditing()
        val revision = runtime.state.value.revision
        val savedChat = fixture.settings.load()
        openModels()
        reveal("provider-balance-account").performTextReplacement("unsaved-balance-account")
        reveal("provider-balance-endpoint").performTextReplacement("https://unsaved-balance.invalid")
        reveal("provider-balance-key").performTextReplacement("unsaved-independent-key")
        reveal("provider-balance-header-add").performClick()
        reveal("provider-balance-header-name:0").performTextReplacement("X-Draft-Balance")
        reveal("provider-balance-header-value:0").performTextReplacement("unsaved-header")
        reveal("provider-balance-test").assertIsEnabled().performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("余额测试成功：CNY 56.78（仅本次测试，未保存）").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, draftCalls.get())
        val seen = checkNotNull(draftConfiguration.get())
        assertEquals("unsaved-balance-account", seen.accountId)
        assertEquals("https://unsaved-balance.invalid", seen.endpoint)
        assertEquals("unsaved-independent-key", seen.apiKey)
        assertEquals("unsaved-independent-key", seen.resolvedToken())
        assertEquals(mapOf("X-Draft-Balance" to "unsaved-header"), seen.customHeaders)
        assertEquals(savedBalance, runtime.store.configurationForEditing())
        assertEquals(revision, runtime.state.value.revision)
        assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
        assertEquals(savedChat, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testFailureKeepsActualTopBarAmountAndControllerErrorUsesSeparateStatus() {
        fail.set(true)
        runBlocking { withTimeout(5000) { runtime.manualRefresh() } }
        assertEquals(BalanceResult.Failed(BalanceFailure.NETWORK), runtime.state.value.result)
        assertTopBarMoney()
        mainThread {
            ChatController::class.java.getDeclaredField("problem").apply { isAccessible = true }
                .set(fixture.controller, "余额槽之外的会话错误")
            MainActivity::class.java.getDeclaredMethod("publishChat").apply { isAccessible = true }.invoke(activity)
        }
        node("chat-status").assertTextEquals("余额槽之外的会话错误")
        assertTopBarMoney()
        assertFalse(node("chat-provider-balance").fetchSemanticsNode().config.toString()
            .contains("余额槽之外的会话错误"))
    }

    fun testBackgroundWithoutPageDisposalCancelsManualAndForegroundCanQueryAgain() {
        openBalance()
        hold.set(true)
        querySaved()
        runBlocking { withTimeout(5000) { entered.await() } }
        mainThread { assertTrue(activity.moveTaskToBack(true)) }
        runBlocking { withTimeout(5000) { cancelled.await() } }
        ui.waitUntil(timeoutMillis = 5000) {
            var stopped = false
            instrumentation.runOnMainSync {
                stopped = activity.lifecycle.currentState == Lifecycle.State.CREATED &&
                    !activity.isFinishing && !activity.isDestroyed && !runtime.state.value.refreshing
            }
            stopped
        }
        assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
        assertFalse(runtime.isPolling)
        hold.set(false)
        fail.set(true)
        mainThread {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        ui.waitUntil(timeoutMillis = 5000) { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        ui.onNodeWithText("独立余额设置").performScrollTo().assertIsDisplayed()
        ui.waitUntil(timeoutMillis = 5000) { !runtime.state.value.refreshing }
        val before = calls.get()
        querySaved()
        ui.waitUntil(timeoutMillis = 5000) { calls.get() > before && !runtime.state.value.refreshing }
        assertEquals(BalanceResult.Failed(BalanceFailure.NETWORK), runtime.state.value.result)
        assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
    }
}
