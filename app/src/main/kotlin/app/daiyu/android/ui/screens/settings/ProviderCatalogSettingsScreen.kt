package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackStack
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.haptics.miuix.TextButton
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.daiyu.android.model.catalog.CatalogImportSession
import app.daiyu.android.model.catalog.RemoteModel
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Complete settings catalog integration, separate from the current-model picker.
 *
 * Host requirements:
 * - persistence is the SAME catalog owned by its settings store, not a temporary adapter;
 * - providerIdentity comes from its persisted registration, never from endpoint/draft text;
 * - fetchCatalog delegates to the existing guarded controller fetch and returns admission status;
 * - onDraftChange/onSave/onBack retain the host's existing authorization and active-model logic.
 *
 * Only checked models enter appendSelected. Fetch success never selects or saves a current model.
 * No draft/credential enters saved state. The callback is marshalled to this composition's UI scope.
 */
@Composable
internal fun <T> ProviderCatalogSettingsScreen(
    draft: ModelSettingsDraftUi,
    onDraftChange: (ModelSettingsDraftUi) -> Unit,
    onSave: (ModelSettingsDraftUi) -> Unit,
    onBack: () -> Unit,
    providerIdentity: String,
    persistence: SavedModelCatalogPersistence<T>,
    createModel: (RemoteModel) -> SavedCatalogModel<T>,
    fetchCatalog: (ModelSettingsDraftUi, () -> Boolean, (List<RemoteModel>?) -> Unit) -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    engineeringUi: Boolean = false,
    errorText: String? = null,
    credentialUnavailable: Boolean = false,
    requestOptionsUnavailable: Boolean = false,
    headersUnavailable: Boolean = false,
    testConnection: ModelConnectionTester? = null,
    localModelActions: LocalCatalogModelActions<T>? = null,
    pageTitle: String = "模型设置",
    twoTabs: Boolean = false,
    providerName: String? = null,
    ownerCurrent: () -> Boolean = { true },
    testBalance: ProviderBalanceTester? = null,
    onDelete: (() -> Unit)? = null,
    pageUi: ProviderPageUiState? = null,
    modelScrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    speechOnly: Boolean = false,
    modelDeletionEnabled: Boolean = enabled,
) {
    val scope = rememberCoroutineScope()
    var state by remember(persistence) { mutableStateOf(CatalogImportSession.State()) }
    val currentFactory by rememberUpdatedState(createModel)
    val session = remember(persistence) { CatalogImportSession(persistence, { currentFactory(it) }) { state = it } }
    DisposableEffect(session, providerIdentity, draft, enabled) {
        onDispose { session.invalidate() }
    }
    val configurationListState = pageUi?.configurationListState ?: rememberLazyListState()
    val modelListState = pageUi?.modelListState ?: rememberLazyListState()
    val modelQuery = pageUi?.modelQuery ?: remember(providerIdentity, persistence) { mutableStateOf("") }
    val selectedTab = pageUi?.modelTab ?: remember(providerIdentity, persistence) { mutableStateOf(false) }
    val selectedModelIds = pageUi?.selectedModelIds ?: remember(providerIdentity, persistence) { mutableStateOf<Set<String>?>(null) }
    var modelTab by selectedTab
    var localRevision by remember { mutableStateOf(0L) }
    val fetch: (ModelSettingsDraftUi) -> Unit = { value ->
            if (ownerCurrent() && enabled && !state.saving) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    // Install the token now, before any queued draft edit/disposal can invalidate it.
                    // begin suspends for the disk/OS-lock read; edits/disposal fence its result.
                    val request = session.begin(providerIdentity)
                    if (request != null) {
                        val accepted = try {
                            fetchCatalog(value, request::isCancelled) { models ->
                                scope.launch { session.fetched(request, models) }
                            }
                        } catch (_: Exception) { false }
                        if (!accepted) session.rejected(request)
                    }
                }
            }
        }
    val tabs: LazyListScope.() -> Unit = {
        if (twoTabs) item(key = "provider-tabs") {
            ProviderSettingsTabs(modelTab, modelDeletionEnabled && !state.saving) { selected ->
                if (ownerCurrent()) { session.invalidate(); selectedModelIds.value = null; modelTab = selected }
            }
        }
    }
    var managingLocal by remember(providerIdentity, persistence) { mutableStateOf(false) }
    PageBackStack(if (managingLocal && localModelActions != null) "local-models" else null) {
        if (managingLocal && localModelActions != null) {
            LocalCatalogModelsScreen(providerIdentity, persistence, localModelActions, modelDeletionEnabled, editingEnabled = enabled, onBack = { selectedModelIds.value = null; managingLocal = false },
                listState = modelListState, queryState = modelQuery, selectionState = selectedModelIds, scrollBehavior = modelScrollBehavior)
            return@PageBackStack
        }
        if (twoTabs && modelTab && localModelActions != null) {
            key(localRevision) { LocalCatalogModelsScreen(providerIdentity, persistence, localModelActions, modelDeletionEnabled && !state.saving, editingEnabled = enabled,
                onBack = onBack, pageTitle = pageTitle, managementEndpoint = draft.endpoint,
                listState = modelListState, queryState = modelQuery, selectionState = selectedModelIds, scrollBehavior = modelScrollBehavior,
                fetchingModels = state.loading, onFetchModels = if (enabled && !state.loading && !state.saving) ({ fetch(draft) }) else null, headerContent = {
                    tabs()
                    item {
                        state.status?.let { SettingsNote(it) }
                    }
                }) }
        } else ModelSettingsScreen(
            pageTitle = pageTitle, headerContent = tabs, showCatalog = !twoTabs, providerName = providerName,
            listState = configurationListState, speechOnly = speechOnly,
            onManageLocalModels = localModelActions?.let { { session.invalidate(); selectedModelIds.value = null; managingLocal = true } },
            engineeringUi = engineeringUi,
            testConnection = testConnection?.let { test -> { value, cancelled, deliver ->
                session.invalidate(); test(value, cancelled, deliver)
            } },
            testBalance = testBalance,
            onDelete = onDelete?.let { delete -> { session.invalidate(); delete() } },
            draft = draft,
            onDraftChange = { session.invalidate(); onDraftChange(it) },
            onSave = { session.invalidate(); onSave(it) },
            onBack = { session.invalidate(); onBack() },
            onFetchCatalog = fetch,
            modifier = modifier,
            errorText = errorText,
            credentialUnavailable = credentialUnavailable,
            requestOptionsUnavailable = requestOptionsUnavailable,
            headersUnavailable = headersUnavailable,
            isFetchingCatalog = state.loading,
            catalogStatusText = state.status,
            catalogForDraft = draft,
            catalogEnabled = enabled && !state.saving,
            enabled = enabled && !state.saving,
            // Deliberately no legacy clickable remote result list: that list changes the active draft.
        )
        state.selection?.let { selection ->
            ProviderModelSelectionDialog(
                selection = selection,
                onSelectionChange = session::select,
                onConfirm = { checked ->
                    if (enabled) session.prepareCommit(checked)?.let { commit ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { session.performCommit(commit) }
                            session.completed(commit, result)
                            localRevision++
                        }
                    }
                },
                onDismiss = session::dismiss,
                enabled = enabled,
                isSaving = state.saving,
                errorText = state.error,
            )
        }
    }
}
