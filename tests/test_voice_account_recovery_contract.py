from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class VoiceAccountRecoveryContract(unittest.TestCase):
    def test_loading_publishes_recovered_accounts_and_observable_readiness(self):
        source = (SRC / 'ControllerVoiceRuntime.kt').read_text()
        self.assertIn('publish(loaded.recoverPersonalAccounts())', source)
        self.assertIn('val loadingState = loading.asStateFlow()', source)
        self.assertIn('loading.value = value', source)

    def test_updates_reread_durable_envelope_before_merging_instead_of_empty_memory(self):
        source = (SRC / 'ControllerVoiceRuntime.kt').read_text()
        persist = source[source.index('private suspend fun persist('):source.index('fun accountCurrent(')]
        self.assertIn('VoiceProfileEnvelope.decode(vault.load().apiKey).recoverPersonalAccounts()', persist)
        self.assertLess(persist.index('vault.load()'), persist.index('val next = update(saved)'))
        self.assertLess(persist.index('next.accounts.forEach'), persist.index('vault.save('))
        self.assertIn('persist("voice_save_unconfirmed") { saved ->', source)
        self.assertIn('persist("voice_account_save_unconfirmed") { saved ->', source)
        self.assertIn('private companion object { val lock = Any() }', source)
        self.assertGreaterEqual(persist.count('savingContext.ensureActive()'), 2)
        self.assertLess(persist.index('savingContext.ensureActive()'), persist.index('vault.load()'))
        self.assertIn('loadState = LoadState.CANCELLED', persist)

    def test_empty_page_is_only_shown_after_successful_read_and_late_accounts_are_observed(self):
        source = (SRC / 'VoiceSettingsPanel.kt').read_text()
        self.assertIn('runtime.loadingState.collectAsState()', source)
        self.assertIn('LaunchedEffect(personalAccounts, lifecycleState, child, accountLoadState, busy, accountReadError)', source)
        page = source[source.index('if (child == "DOUBAO_VOICES") {'):source.index('if (child == "DOUBAO_ACCOUNT") {')]
        self.assertIn('accountLoadState == ControllerVoiceRuntime.LoadState.LOADED', page)
        self.assertIn('VoicePersonalAccountStatePage(', page)
        self.assertIn('runtime.load()', page)
        self.assertIn('!busy && !accountReadError', page)
        self.assertIn('if (alive.get()) accountReadError = true', page)
        self.assertIn('if (accountReadError) ControllerVoiceRuntime.LoadState.FAILED', page)
        self.assertIn('else if (busy) ControllerVoiceRuntime.LoadState.LOADING', page)
        self.assertIn('val accountConfiguration = accountPage ?: personalAccounts["DOUBAO"]', source)

    def test_failed_read_never_offers_a_replacement_account_form(self):
        source = (SRC / 'VoicePersonalAccountStatePage.kt').read_text()
        self.assertIn('账户读取失败', source)
        self.assertIn('voice-account-retry', source)
        self.assertNotIn('onConfigure', source)
        self.assertNotIn('onAdd =', source)
        self.assertNotIn('API Key', source)
