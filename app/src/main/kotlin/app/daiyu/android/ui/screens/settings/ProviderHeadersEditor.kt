package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable

@Composable
internal fun ProviderHeadersEditor(draft: ModelSettingsDraftUi, enabled: Boolean,
    onDraftChange: (ModelSettingsDraftUi) -> Unit,
) = ProviderInlineFields(draft, enabled, headers = true, onDraftChange = onDraftChange)
