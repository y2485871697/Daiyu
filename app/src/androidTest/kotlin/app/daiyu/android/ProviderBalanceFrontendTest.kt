package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.balance.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.*
import top.yukonga.miuix.kmp.basic.Text

class ProviderBalanceFrontendTest : WiredActivityTestCase() {
    private val title = "测试提供商"
    private var draft by mutableStateOf(ModelSettingsDraftUi(), referentialEqualityPolicy())
    private var pageEnabled by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var tester by mutableStateOf<ProviderBalanceTester?>(null)
    private lateinit var latestUi: SettingsConnectionTestUi
    private lateinit var owner: TestOwner
    private val success = BalanceResult.Available(BalanceMoney("12.34".toBigDecimal(), "CNY"), 1234L)
    private class Request(val draft: ModelSettingsDraftUi, val cancelled: () -> Boolean, val deliver: (BalanceResult) -> Unit)
    private class Fake {
        val requests = mutableListOf<Request>()
        val tester: ProviderBalanceTester = { value, cancelled, deliver ->
            requests += Request(value, cancelled, deliver); true
        }
        val last get() = requests.last()
    }
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun validDraft(): ModelSettingsDraftUi {
        val option = BalanceOption(enabled = true, apiPath = "/balance", resultPath = "balance")
        return ModelSettingsDraftUi(providerName = title, providerEnabled = true,
            endpoint = "https://chat.invalid/v1", apiKey = "provider-key", balanceOption = option)
    }
    private fun show(fake: Fake, dark: Boolean = false, initial: ModelSettingsDraftUi =
        ModelSettingsDraftUi(providerName = title, providerEnabled = true)) {
        mainThread {
            draft = initial; pageEnabled = true
            activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    key(dark) { ModelSettingsScreen(draft, { draft = it }, {}, {}, {}, pageTitle = title,
                        providerName = title, showCatalog = false, enabled = pageEnabled, testBalance = fake.tester) }
                }
            }
        }
        ui.waitForIdle()
    }
    private fun edit(tag: String, value: String) {
        reveal(tag, title).performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(value))) }
        ui.waitForIdle()
    }
    private fun preset(id: String) {
        reveal("provider-balance-preset", title).performClick()
        node("provider-balance-preset:$id").performClick(); ui.waitForIdle()
    }
    private fun start() { reveal("provider-balance-test", title).assertIsEnabled().performClick(); ui.waitForIdle() }
    private fun deliver(request: Request, result: BalanceResult = success) {
        mainThread { request.deliver(result) }; ui.waitForIdle()
    }
    private fun awaitReceipt(expected: String) {
        ui.mainClock.advanceTimeByFrame()
        ui.waitUntil(5_000) { ui.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText(expected).assertExists()
    }
    private fun noReceipt() { ui.onAllNodesWithText("仅本次测试", substring = true).assertCountEquals(0) }

    fun testFourTemplatesUseProviderCredentialsAndOnlyDisplayTestReceiptsInBothThemes() {
        val saved = fixture.settings.load()
        val registry = fixture.controller.providers.snapshot()
        for (dark in listOf(false, true)) {
            val fake = Fake(); show(fake, dark)
            reveal("settings-switch:启用此 Provider", title).assertIsOn().performClick()
            assertFalse(draft.providerEnabled)
            reveal("settings-switch:余额", title).assertIsOff().performClick()
            reveal("provider-balance-test", title).assertIsNotEnabled()
            preset("sub2api")
            assertEquals("/usage", draft.balanceOption.apiPath); assertEquals("remaining", draft.balanceOption.resultPath)
            preset("deepseek")
            assertEquals("/user/balance", draft.balanceOption.apiPath)
            assertEquals("balance_infos[0].total_balance", draft.balanceOption.resultPath)
            preset("custom"); edit("provider-balance-path", "/my/balance")
            assertEquals("/my/balance", draft.balanceOption.apiPath)
            preset("new_api"); edit("provider-balance-token", "independent-token")
            reveal("provider-balance-test", title).assertIsNotEnabled()
            mainThread { draft = draft.copy(endpoint = "https://provider.invalid/v1", apiKey = "provider-key") }
            ui.waitForIdle()
            node("provider-balance-account").assertDoesNotExist()
            node("provider-balance-endpoint").assertDoesNotExist()
            node("provider-balance-key").assertDoesNotExist()
            node("provider-balance-currency").assertDoesNotExist()
            val before = draft
            start()
            assertEquals(1, fake.requests.size); assertSame(before, fake.last.draft)
            val config = checkNotNull(fake.last.draft.providerBalanceConfiguration())
            assertEquals("balance-preview", config.accountId); assertEquals("https://provider.invalid/v1", config.endpoint)
            assertEquals("provider-key", config.apiKey); assertEquals("independent-token", config.resolvedToken())
            assertTrue(config.customHeaders.isEmpty())
            reveal("provider-balance-test", title).assertIsNotEnabled()
            deliver(fake.last)
            awaitReceipt("余额测试成功：CNY 12.34（仅本次测试，未保存）")
            start(); deliver(fake.last, BalanceResult.Failed(BalanceFailure.AUTHORIZATION))
            awaitReceipt("余额查询未授权；请检查独立密钥（仅本次测试，未保存）")
            assertSame(before, draft)
            reveal("settings-switch:余额", title).performClick()
            node("provider-balance-test").assertDoesNotExist()
            assertEquals(saved, fixture.settings.load()); assertEquals(registry, fixture.controller.providers.snapshot())
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testEditingOrDisablingBalanceCancelsReceiptsWithoutSaving() {
        val saved = fixture.settings.load(); val fake = Fake(); show(fake, initial = validDraft())
        start(); val old = fake.last
        edit("provider-balance-path", "/changed-balance")
        assertTrue(old.cancelled()); deliver(old); noReceipt()
        start(); val disabled = fake.last
        reveal("settings-switch:余额", title).performClick(); ui.waitForIdle()
        assertFalse(checkNotNull(draft.providerBalanceConfiguration()).queryEnabled)
        assertTrue(disabled.cancelled()); deliver(disabled); noReceipt()
        node("provider-balance-test").assertDoesNotExist()
        assertEquals(saved, fixture.settings.load()); assertEquals(0, fixture.opens.get())
    }

    private fun coordinator(fake: Fake) {
        mainThread {
            draft = validDraft(); pageEnabled = true; mounted = true; tester = fake.tester
            owner = TestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED }
            activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (mounted) {
                        val state = rememberProviderBalanceTest(draft, pageEnabled, tester)
                        SideEffect { latestUi = state }
                        Text(state.status ?: "idle")
                    }
                }
            }
        }
        ui.waitForIdle()
        awaitCoordinator()
    }
    private fun awaitCoordinator() {
        ui.waitUntil(timeoutMillis = 5_000) {
            var ready = false
            mainThread { ready = ::latestUi.isInitialized }
            ready
        }
    }
    private fun startCoordinator(fake: Fake): Request {
        mainThread { latestUi.start() }; ui.waitForIdle(); assertTrue(latestUi.running)
        return fake.last
    }
    fun testEqualFreshDraftDisableAndTesterRemovalRejectOldReceipts() {
        val fake = Fake(); coordinator(fake)
        val first = startCoordinator(fake)
        mainThread { draft = draft.copy() }; ui.waitForIdle()
        assertTrue(first.cancelled()); deliver(first); assertNull(latestUi.status)
        val second = startCoordinator(fake)
        mainThread { pageEnabled = false }; ui.waitForIdle()
        assertTrue(second.cancelled()); deliver(second); assertNull(latestUi.status)
        val count = fake.requests.size
        mainThread { latestUi.start() }; assertEquals(count, fake.requests.size)
        mainThread { pageEnabled = true }; ui.waitForIdle()
        val third = startCoordinator(fake)
        mainThread { tester = null }; ui.waitForIdle()
        assertTrue(third.cancelled()); deliver(third); assertNull(latestUi.status)
    }
    fun testPauseAndDisposalCannotBorrowResumedOrRemountedVisit() {
        val fake = Fake(); coordinator(fake)
        val paused = startCoordinator(fake)
        mainThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }; ui.waitForIdle()
        assertTrue(paused.cancelled()); deliver(paused); assertNull(latestUi.status)
        val count = fake.requests.size
        mainThread { latestUi.start() }; assertEquals(count, fake.requests.size)
        mainThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }; ui.waitForIdle()
        val disposed = startCoordinator(fake); val stale = latestUi
        mainThread { mounted = false }; ui.waitForIdle()
        assertTrue(disposed.cancelled())
        mainThread { mounted = true }
        ui.waitUntil(timeoutMillis = 5_000) {
            var remounted = false
            mainThread { remounted = latestUi !== stale }
            remounted
        }
        val before = fake.requests.size
        mainThread { stale.start() }; assertEquals(before, fake.requests.size)
        deliver(disposed); assertNull(latestUi.status)
        val expected = "余额测试成功：CNY 12.34（仅本次测试，未保存）"
        val fresh = startCoordinator(fake)
        assertFalse(fresh.cancelled())
        deliver(fresh)
        ui.waitUntil(timeoutMillis = 5_000) {
            var completed = false
            mainThread { completed = !latestUi.running && latestUi.status == expected }
            completed
        }
        mainThread { assertEquals(expected, latestUi.status) }
        assertEquals(0, fixture.opens.get())
    }
}
