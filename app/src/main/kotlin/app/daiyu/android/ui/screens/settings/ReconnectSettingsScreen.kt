package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import app.daiyu.android.ui.haptics.material.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.daiyu.android.model.reconnect.ReconnectDurationPreset
import app.daiyu.android.model.reconnect.ReconnectSettings
import app.daiyu.android.model.reconnect.ReconnectSettingsSaveOwner
import app.daiyu.android.model.reconnect.ReconnectSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Global preference editor only: no model/session/tool is created here.
 * A host that leaves the page before Compose disposal must synchronously revoke saveOwner.
 * Each visit must use a fresh token; ownerCurrent is a main-thread UI guard, not an IO gate. */
@Composable
internal fun ReconnectSettingsScreen(
    store: ReconnectSettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ownerCurrent: () -> Boolean = { true },
    saveOwner: ReconnectSettingsSaveOwner? = null,
) {
    val owner = remember(store, saveOwner) { saveOwner ?: ReconnectSettingsSaveOwner() }
    val page = remember(store, owner) { ReconnectSettingsPage() }
    val currentOwner by rememberUpdatedState(ownerCurrent)
    var draft by remember(store, owner) { mutableStateOf(ReconnectSettings()) }
    var durationConfirmed by remember(store, owner) { mutableStateOf(false) }
    var busy by remember(store, owner) { mutableStateOf(true) }
    var notice by remember(store, owner) { mutableStateOf<String?>(null) }
    var failed by remember(store, owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canAct = { page.active && owner.isActive && currentOwner() }
    fun saveImmediately(requested: ReconnectSettings) {
        if (!canAct() || busy) return
        val previous = draft
        draft = requested
        busy = true
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.save(requested, owner) }
            }
            if (canAct()) {
                result.onSuccess {
                    draft = it
                    durationConfirmed = true
                    failed = false
                }.onFailure {
                    val restored = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
                    if (!canAct()) return@launch
                    draft = restored.getOrElse { previous }
                    durationConfirmed = restored.isSuccess
                    failed = true
                    notice = if (restored.isSuccess) "保存失败，已恢复为上次保存的设置。"
                        else "保存失败，无法确认已保存的设置；请重新选择时长。"
                }
                busy = false
            }
        }
    }
    DisposableEffect(page) {
        page.active = true
        onDispose { page.active = false; owner.revoke() }
    }
    val leave: () -> Unit = { owner.revoke(); page.active = false; onBack() }
    BackHandler(onBack = leave)
    LaunchedEffect(store, owner) {
        val result = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
        if (canAct()) {
            result.onSuccess { draft = it; durationConfirmed = true }.onFailure {
                failed = true
                notice = "无法确认已保存的重连设置；请明确选择并重新保存。不会因此启用自动恢复。"
            }
            busy = false
        }
    }
    SettingsScaffoldPage(title = "错误重连", onBack = leave, modifier = modifier) {
        item(key = "reconnect-global") {
            SettingsCard {
                ReconnectDurationPreset.entries.forEach { duration ->
                    SettingsRow(duration.label, tag = "reconnect-duration:${duration.wireId}",
                        onClick = if (!busy) ({
                            saveImmediately(draft.copy(duration = duration))
                        }) else null,
                        trailing = { RadioButton(selected = durationConfirmed && draft.duration == duration, onClick = null, enabled = !busy) })
                }

            }
        }
        notice?.let { text -> item(key = "reconnect-notice") { SettingsNote(text, error = failed) } }
    }
}

private class ReconnectSettingsPage { var active = false }
