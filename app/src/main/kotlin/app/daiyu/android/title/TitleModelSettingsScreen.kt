package app.daiyu.android.title

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.daiyu.android.ui.components.MiuixScaffoldPage
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Key draft is remember-only, scoped to this visit, never Bundle/rememberSaveable. */
@Composable
fun TitleModelSettingsScreen(owner: TitleModelSettingsEditor, ownerCurrent: () -> Boolean, onBack: () -> Unit) {
    fun emptyDraft() = TitleModelDraft(TitleModelBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
    var draft by remember(owner) { mutableStateOf(emptyDraft()) }
    var settings by remember(owner) { mutableStateOf(TitleModelSettings()) }
    var busy by remember(owner) { mutableStateOf(true) }
    var notice by remember(owner) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun consume(result: TitleModelSettingsEditor.Result, saved: Boolean) {
        if (!ownerCurrent() || !owner.isCurrent(result)) return
        busy = false
        if (result.failed) notice = "本地配置读取/保存未确认；未发送请求，不自动重试"
        else if (!result.revoked) {
            result.settings?.let { settings = it }
            if (saved) { draft = emptyDraft(); notice = "本地配置已保存；未发送供应商请求" }
        }
    }
    fun enqueue(work: TitleModelSettingsEditor.Work?, saved: Boolean) {
        if (work == null) return
        busy = true
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
    MiuixScaffoldPage(title = "标题模型", onBack = { if (ownerCurrent()) onBack() },
        modifier = Modifier.testTag("page:标题模型")) {
        item(key = "title-model-editor") {
            TitleModelSettingsPanel(settings, draft, { if (ownerCurrent() && !busy) draft = it },
                onSave = { if (ownerCurrent()) enqueue(owner.prepareSave(it), true) },
                onDisable = { if (ownerCurrent()) enqueue(owner.prepareDisable(), true) },
                busy = busy, notice = notice)
        }
    }
}
