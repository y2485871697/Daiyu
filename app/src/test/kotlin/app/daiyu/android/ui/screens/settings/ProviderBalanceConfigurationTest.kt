package app.daiyu.android.ui.screens.settings

import app.daiyu.android.balance.BalanceConfiguration
import app.daiyu.android.balance.BalanceOption
import org.junit.Assert.*
import org.junit.Test

class ProviderBalanceConfigurationTest {
    private val option = BalanceOption(enabled = true, preset = BalanceOption.PRESET_NEW_API)
    private fun draft() = ModelSettingsDraftUi(endpoint = " https://provider.invalid/v1 ", apiKey = "provider-key",
        balanceOption = option, balanceConfiguration = BalanceConfiguration("legacy-account", "https://old.invalid",
            "old-key", "", "CNY", option))

    @Test fun usesCurrentProviderAndStableSlotInsteadOfIndependentCredentials() {
        val value = checkNotNull(draft().providerBalanceConfiguration("provider-1"))
        value.validate()
        assertEquals("provider-1", value.accountId)
        assertEquals("https://provider.invalid/v1", value.endpoint)
        assertEquals("provider-key", value.apiKey)
        assertEquals("provider-key", value.resolvedToken())
        assertEquals("https://provider.invalid/api/user/self", value.resolvedEndpoint())
        assertEquals("CNY", value.currency)
        val changed = checkNotNull(draft().copy(endpoint = "https://changed.invalid/v1", apiKey = "new-key")
            .providerBalanceConfiguration("provider-1"))
        assertEquals("https://changed.invalid/v1", changed.endpoint)
        assertEquals("new-key", changed.resolvedToken())
        assertNotEquals(value, changed)
    }

    @Test fun optionalTokenOverridesCurrentKeyAndClearingRestoresIt() {
        val draft = draft().copy(balanceOption = option.copy(accessToken = "access-token"))
        assertEquals("access-token", draft.providerBalanceConfiguration()!!.resolvedToken())
        assertEquals("provider-key", draft.copy(balanceOption = option).providerBalanceConfiguration()!!.resolvedToken())
    }

    @Test fun rejectsCrossOriginPathsBeforeSendingProviderCredentials() {
        val value = checkNotNull(draft().copy(balanceOption = option.copy(preset = BalanceOption.PRESET_CUSTOM,
            apiPath = "https://other.invalid/balance", resultPath = "balance")).providerBalanceConfiguration())
        assertThrows(IllegalArgumentException::class.java) { value.validateProviderBinding() }
        draft().providerBalanceConfiguration()!!.validateProviderBinding()
    }

    @Test fun legacyPointerSurvivesUnrelatedProviderEditAndRebindsCredentials() {
        val legacy = BalanceConfiguration("legacy", "https://old.invalid/balance?kind=quota",
            "old-key", "/data/balance", "CNY")
        val draft = draft().copy(providerName = "renamed", balanceConfiguration = legacy,
            balanceOption = BalanceOption(enabled = true))
        val value = checkNotNull(draft.providerBalanceConfiguration("provider-1"))
        value.validateProviderBinding()
        assertNull(value.option)
        assertEquals("/data/balance", value.amountPointer)
        assertEquals("https://provider.invalid/balance?kind=quota", value.endpoint)
        assertEquals("provider-key", value.apiKey)
        val converted = checkNotNull(draft.copy(balanceOption = option).providerBalanceConfiguration())
        converted.validateProviderBinding()
        assertEquals(option, converted.option)
    }

    @Test fun disabledRetainsTemplateWithoutCreatingAnUntouchedAccount() {
        assertNull(ModelSettingsDraftUi().providerBalanceConfiguration())
        val disabled = checkNotNull(draft().copy(balanceOption = option.copy(enabled = false)).providerBalanceConfiguration())
        disabled.validate()
        assertFalse(disabled.queryEnabled)
        assertEquals(BalanceOption.PRESET_NEW_API, disabled.option!!.preset)
        val unfinished = checkNotNull(draft().copy(balanceOption = BalanceOption(enabled = false))
            .providerBalanceConfiguration())
        unfinished.validateProviderBinding()
        assertFalse(unfinished.queryEnabled)
    }
}
