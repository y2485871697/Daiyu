package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

internal val RetainedProviderConfigurationForm =
    SemanticsPropertyKey<Pair<Any, LazyListState>>("RetainedProviderConfigurationForm")

/**
 * Arguments for the one live configuration form. Registration and authoritative save readback may
 * replace the editor/catalog owner, but must not replace the form's composition or scroll owner.
 * This is in-memory presentation data only; it grants no save or request authority.
 */
internal class ProviderConfigurationForm(
    val draft: ModelSettingsDraftUi,
    val onDraftChange: (ModelSettingsDraftUi) -> Unit,
    val onSave: (ModelSettingsDraftUi) -> Unit,
    val onFetchCatalog: (ModelSettingsDraftUi) -> Unit,
    val onBack: () -> Unit,
    val listState: LazyListState,
    val modifier: Modifier = Modifier,
    val errorText: String? = null,
    val credentialUnavailable: Boolean = false,
    val requestOptionsUnavailable: Boolean = false,
    val headersUnavailable: Boolean = false,
    val isFetchingCatalog: Boolean = false,
    val catalogStatusText: String? = null,
    val catalogForDraft: ModelSettingsDraftUi? = null,
    val catalogEnabled: Boolean = true,
    val enabled: Boolean = true,
    val engineeringUi: Boolean = false,
    val testConnection: ModelConnectionTester? = null,
    val onManageLocalModels: (() -> Unit)? = null,
    val pageTitle: String = "模型设置",
    val headerContent: LazyListScope.() -> Unit = {},
    val showCatalog: Boolean = true,
    val providerName: String? = null,
    val testBalance: ProviderBalanceTester? = null,
    val onDelete: (() -> Unit)? = null,
    val speechOnly: Boolean = false,
    val onOAuthLogin: (() -> Unit)? = null,
    val oauthLoginRunning: Boolean = false,
) {
    @Composable
    fun Content() {
        val identity = remember { Any() }
        val formModifier = if (engineeringUi) modifier.semantics {
            this[RetainedProviderConfigurationForm] = identity to listState
        } else modifier
        ModelSettingsScreen(
            draft = draft, onDraftChange = onDraftChange, onSave = onSave,
            onFetchCatalog = onFetchCatalog, onBack = onBack, listState = listState,
            modifier = formModifier, errorText = errorText, credentialUnavailable = credentialUnavailable,
            requestOptionsUnavailable = requestOptionsUnavailable, headersUnavailable = headersUnavailable,
            isFetchingCatalog = isFetchingCatalog, catalogStatusText = catalogStatusText,
            catalogForDraft = catalogForDraft, catalogEnabled = catalogEnabled, enabled = enabled,
            engineeringUi = engineeringUi, testConnection = testConnection,
            onManageLocalModels = onManageLocalModels, pageTitle = pageTitle, headerContent = headerContent,
            showCatalog = showCatalog, providerName = providerName, testBalance = testBalance,
            onDelete = onDelete, speechOnly = speechOnly, onOAuthLogin = onOAuthLogin,
            oauthLoginRunning = oauthLoginRunning,
        )
    }
}
