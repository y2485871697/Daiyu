package app.daiyu.android.title

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.screens.settings.SettingsModelSource
import app.daiyu.android.ui.screens.settings.SettingsModelPicker
import java.util.UUID

/** Key draft is remember-only, scoped to this visit, never Bundle/rememberSaveable. */
@Composable
internal fun TitleModelSettingsScreen(owner: TitleModelSettingsEditor, ownerCurrent: () -> Boolean, onBack: () -> Unit, source: SettingsModelSource) {
    fun emptyDraft() = TitleModelDraft(TitleModelBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
    var draft by remember(owner) { mutableStateOf(emptyDraft()) }
    var settings by remember(owner) { mutableStateOf(TitleModelSettings()) }
    var busy by remember(owner) { mutableStateOf(true) }
    var saving by remember(owner) { mutableStateOf(false) }
    var notice by remember(owner) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var picker by remember(owner) { mutableStateOf(false) }
    var modelLabel by remember(owner) { mutableStateOf(source.label("title")) }
    var selectedKey by remember(owner) { mutableStateOf(source.key("title")) }
    fun consume(result: TitleModelSettingsEditor.Result, saved: Boolean) {
        if (!ownerCurrent() || !owner.isCurrent(result)) return
        busy = false
        saving = false
        if (result.failed) notice = "本地配置读取/保存未确认；未发送请求，不自动重试"
        else if (!result.revoked) {
            result.settings?.let { settings = it }
            if (saved) { draft = emptyDraft(); notice = null; modelLabel = source.label("title"); selectedKey = source.key("title") }
        }
    }
    fun enqueue(work: TitleModelSettingsEditor.Work?, saved: Boolean) {
        if (work == null) return
        busy = true
        saving = saved
        scope.launch {
            val result = withContext(Dispatchers.IO) { work.runWorker() }
            consume(result, saved)
        }
    }
    LaunchedEffect(owner) {
        val work = owner.prepareLoad() ?: return@LaunchedEffect
        consume(withContext(Dispatchers.IO) { work.runWorker() }, false)
    }
    DisposableEffect(owner) {
        onDispose { owner.revoke(); draft = emptyDraft() }
    }
    TitleModelSettingsPanel(settings, draft, { if (ownerCurrent() && !busy) draft = it },
        onSave = { if (ownerCurrent()) enqueue(owner.prepareSave(it), true) },
        onDisable = { if (ownerCurrent()) enqueue(owner.prepareDisable(), true) },
        busy = busy, saving = saving, notice = notice, onBack = { if (ownerCurrent()) onBack() },
        modelLabel = modelLabel, onChooseModel = { if (ownerCurrent() && !busy) picker = true })
    if (picker) SettingsModelPicker("标题模型", source, selectedKey,
        onSelect = { choice ->
            if (ownerCurrent() && !busy) {
                picker = false
                val binding = TitleModelBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                enqueue(owner.prepareSelectProfile(
                    profile = { source.withModel(choice) { TitleModelProfile(binding, it.copy(batteryEnabled = false, deviceStatusEnabled = false, requestOptions = app.daiyu.android.model.ProviderRequestOptions())) } },
                    saved = { source.remember("title", choice) }), true)
            }
        }, onDismiss = { picker = false })
}
