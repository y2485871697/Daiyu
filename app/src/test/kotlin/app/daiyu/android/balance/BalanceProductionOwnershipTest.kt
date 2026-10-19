package app.daiyu.android.balance

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Offline ownership tests. No HTTP client/server, Android Keystore or user namespace is opened. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BalanceProductionOwnershipTest {
    private class Memory : BalancePersistence {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(record: ByteArray?) { bytes = record?.clone() }
    }
    private class Page : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun configuration(enabled: Boolean = true) = BalanceConfiguration(
        "balance-only", "https://example.test", "independent", "", "CNY",
        BalanceOption(enabled = enabled, apiPath = "/balance", resultPath = "balance"))
    private fun money() = BalanceResult.Available(BalanceMoney("12.34".toBigDecimal(), "CNY"), 1000L)

    @Test fun processFactoryIsOnceOffMainAndSurvivesAwaiterRecreation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val callerThread = Thread.currentThread()
        var factoryThread: Thread? = null
        val host = BalanceRuntimeHost({
            factoryThread = Thread.currentThread()
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery { error("must not query") })
        })
        try {
            val oldActivity = launch { host.get() }
            withTimeout(2000) { entered.await() }
            oldActivity.cancelAndJoin()
            release.complete(Unit)
            val replacement = withTimeout(2000) { host.get() }
            assertSame(replacement, host.get())
            assertEquals(1, calls.get())
            assertNotSame(callerThread, factoryThread)
            assertFalse(replacement.isPolling)
        } finally { host.close() }
    }

    @Test fun stoppedButNotDisposedSettingsCancelsManualAndCanQueryAfterRestart() = runBlocking {
        val calls = AtomicInteger()
        val cancelled = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
            when (calls.incrementAndGet()) {
                1 -> money()
                2 -> try { entered.complete(Unit); CompletableDeferred<Unit>().await(); money() }
                    finally { cancelled.complete(Unit) }
                else -> BalanceResult.Failed(BalanceFailure.NETWORK)
            }
        })
        runtime.save(configuration())
        runtime.manualRefresh()
        val page = Page()
        val parent = CoroutineScope(Job() + Dispatchers.Default)
        val binding = BalanceLifecycleBinding(page.lifecycle, parent, runtime, poll = false)
        try {
            page.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            val query = checkNotNull(binding.requestRefresh())
            withTimeout(2000) { entered.await() }
            // No disposal: the very same binding and saved editor remain mounted.
            page.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            withTimeout(2000) { query.join(); cancelled.await() }
            assertFalse(runtime.state.value.refreshing)
            assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
            assertNull(binding.requestRefresh())
            page.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            withTimeout(2000) { checkNotNull(binding.requestRefresh()).join() }
            assertEquals(3, calls.get())
            assertEquals(BalanceResult.Failed(BalanceFailure.NETWORK), runtime.state.value.result)
            assertEquals("CNY 12.34", runtime.state.value.project().balanceText)
            assertEquals(1000L, runtime.state.value.project().checkedAtMillis)
        } finally { binding.close(); parent.cancel() }
    }

    @Test fun pageDisposalAlsoCancelsManualRequestWithoutLifecycleStop() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery {
            entered.complete(Unit); CompletableDeferred<Unit>().await(); money()
        })
        runtime.save(configuration())
        val page = Page()
        page.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val parent = CoroutineScope(Job() + Dispatchers.Default)
        val binding = BalanceLifecycleBinding(page.lifecycle, parent, runtime, poll = false)
        try {
            val request = checkNotNull(binding.requestRefresh())
            withTimeout(2000) { entered.await() }
            binding.close()
            withTimeout(2000) { request.join() }
            assertTrue(request.isCancelled)
            assertFalse(runtime.state.value.refreshing)
        } finally { binding.close(); parent.cancel() }
    }

    @Test fun foregroundPollingRemainsOfflineForDisabledConfiguration() = runTest {
        val calls = AtomicInteger()
        val runtime = BalanceRuntime(ProviderBalanceStore(Memory()), BalanceQuery { calls.incrementAndGet(); money() })
        runtime.save(configuration(enabled = false))
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val owner = BalanceForegroundOwner(runtime, poll = true)
        try {
            owner.start(parent)
            owner.start(parent)
            runCurrent()
            owner.stop()
            assertEquals(0, calls.get())
            assertFalse(owner.isActive)
            owner.start(parent)
            assertTrue(owner.isActive)
        } finally { owner.close(); parent.cancel() }
    }

    @Test fun realHostRouteStateAndAnimatedComposerBalanceSourceContract() {
        fun source(path: String) = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "app/src/main/kotlin/app/daiyu/android/$path") }.first { it.isFile }.readText()
        val main = source("MainActivity.kt")
        assertTrue(main.contains("balanceHost.get()"))
        assertTrue(main.contains("runtime, poll = true"))
        assertTrue(main.contains("FeatureRoute.BALANCE_SETTINGS -> retainedPage = \"balance-settings\""))
        assertTrue(main.contains("BalanceSettingsScreen("))
        assertTrue(main.contains("state.putBoolean(\"balance-page\""))
        assertTrue(main.contains("balanceState = balanceState"))
        assertTrue(main.contains("statusText = controller.problem"))
        assertFalse(main.contains("balanceText = controller.problem"))
        assertTrue(source("ui/FeatureCatalog.kt").contains("FeatureEntry(\"P05\", \"供应商余额\", FeatureRoute.BALANCE_SETTINGS)"))
        assertTrue(source("ui/screens/settings/SettingsCatalog.kt").contains("FeatureRoute.BALANCE_SETTINGS"))
        val chat = source("ui/screens/chat/conversation/ChatScreen.kt")
        assertTrue(chat.contains("ChatTopBar(state.title, state.balanceState"))
        assertTrue(chat.contains("state.statusText?.let"))
        assertTrue(chat.contains(
            "ChatComposer(state.composer, actions, assistant = assistant, balanceState = state.balanceState)"
        ))
        val composer = source("ui/screens/chat/conversation/ChatComposer.kt")
        assertTrue(composer.contains("ProviderBalanceIndicator("))
        assertTrue(composer.contains("state = balanceState, modifier = Modifier.testTag(\"chat-provider-balance\")"))
        assertTrue(composer.contains("animateChanges = true"))
        val screen = source("balance/ui/BalanceSettingsScreen.kt")
        assertTrue(screen.contains("BalanceLifecycleBinding(lifecycle, scope, runtime, poll = false)"))
        assertTrue(screen.contains("manualOwner?.requestRefresh()"))
        assertTrue(screen.contains("onDispose { binding.close()"))
        assertFalse(screen.contains("runtime.manualRefresh()"))
    }
}
