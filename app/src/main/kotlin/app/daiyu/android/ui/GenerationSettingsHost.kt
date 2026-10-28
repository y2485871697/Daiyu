package app.daiyu.android.ui

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.GenerationSettingsEditor
import app.daiyu.android.ui.screens.settings.SettingsScaffoldPage
import app.daiyu.android.ui.screens.settings.GenerationSettingsScreen
import app.daiyu.android.ui.screens.settings.SettingsNote
import app.daiyu.android.ui.haptics.miuix.TextButton

/** Remember in the settings route, not rememberSaveable. The editor itself never owns a draft;
 * each Host mounting creates its own terminally revocable in-memory Page.
 */
@Composable
internal fun rememberGenerationSettingsEditor(): GenerationSettingsEditor {
    val context = LocalContext.current.applicationContext
    return remember(context) { GenerationSettingsEditor(context) }
}

/** The Main route needs only this host (and its remembered editor); no Controller/catalog wiring.
 * ownerCurrent must be a fast, thread-safe predicate for this exact route visit, not just a route
 * name (A -> B -> A must not revive A). It is checked again under the store lock on the worker.
 * onBack should remove this route; both UI/system back revoke first, without disk I/O or waiting.
 */
@Composable
internal fun GenerationSettingsHost(
    editor: GenerationSettingsEditor,
    onBack: () -> Unit,
    ownerCurrent: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Freeze this visit's predicate. An old queued worker must never borrow a replacement
    // visit's callback through rememberUpdatedState, even if its parent forgets to key it.
    val page = remember(editor, lifecycle) { editor.openPage(ownerCurrent) }
    DisposableEffect(page, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) page.revoke()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) page.revoke()
        onDispose {
            page.revoke()
            lifecycle.removeObserver(observer)
        }
    }
    SideEffect { page.checkOwner() }
    LaunchedEffect(page) { page.start() }
    val leave = { page.revoke(); onBack() }
    BackHandler(onBack = leave)

    val state = page.state
    Column(modifier.fillMaxSize().navigationBarsPadding().testTag("generation-settings-host")) {
        val draft = state.draft
        if (draft != null) {
            GenerationSettingsScreen(
                draft = draft,
                onDraftChange = page::edit,
                onSave = { page.save() },
                onBack = leave,
                modifier = Modifier.weight(1f),
                errorText = state.errorText,
                credentialUnavailableIds = state.credentialUnavailableIds,
                incompleteSave = state.incompleteSave,
                enabled = !state.loading && !state.saving,
            )
        } else {
            // No placeholder/default form for corrupt/unconfirmed/unavailable source reads.
            SettingsScaffoldPage(title = "独立生成模型设置", onBack = leave, modifier = Modifier.weight(1f), compactTitle = true) {
                item {
                    SettingsNote(if (state.loading) "正在后台读取生成配置…"
                        else state.errorText ?: "此设置页面已失效，请返回后重新打开。", error = state.failure != null)
                }
            }
        }
        if (state.saving) SettingsNote("正在后台保存并核验回读；离开页面不会取消已准入的保存。")
        if (state.saved) SettingsNote("生成配置已保存并核验回读；未运行或授权任何生成。")
        if (!state.loading) {
            TextButton(
                text = "重新加载（放弃未保存修改）",
                onClick = page::reload,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("generation-settings-reload"),
            )
        }
    }
}
