package app.daiyu.android

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.voice.control.*
import app.daiyu.android.ui.haptics.miuix.TextButton

/** The only entry is an explicit in-app control panel. Opening/selecting never acquires audio. */
@Composable
internal fun ProductionUniversalVoicePanel(
    host: ActivityUniversalVoiceHost,
    configuration: VoiceConfiguration?,
    configure: () -> Unit,
    start: (VoiceControlProfile, VoiceControlLimits) -> Unit,
    openRequested: Boolean = false,
    onCloseRequest: () -> Unit = {},
    showEntry: Boolean = true,
    cascadeEntry: @Composable () -> Unit = {},
) {
    var locallyExpanded by remember { mutableStateOf(false) }
    val expanded = locallyExpanded || openRequested
    val state by host.state.collectAsState()
    val profiles = remember(configuration?.revision) {
        configuration?.let { config -> listOf(
            VoiceControlProfile("${config.revision}:configured", 0, "会话独立配置：${config.dictation} 识别 + ${config.engine} 正文"),
            VoiceControlProfile("${config.revision}:offline", 0, "本机离线识别 + 会话独立 ${config.engine} 正文（需已下载语音包）"),
        ) }.orEmpty()
    }
    var selected by remember(profiles) { mutableStateOf(profiles.firstOrNull()) }
    var limits by remember { mutableStateOf(VoiceControlLimits()) }
    if (showEntry) TextButton(text = if (expanded) "结束语音对话" else "语音对话",
        modifier = Modifier.testTag("voice-universal-open"), onClick = {
            if (expanded) state.permitId?.let { host.stop(it) }
            locallyExpanded = !expanded
            if (expanded) onCloseRequest()
        })
    if (expanded) app.daiyu.android.ui.components.DaiyuDialog(onDismissRequest = {
        state.permitId?.let { host.stop(it) }; locallyExpanded = false; onCloseRequest()
    }) {
        androidx.compose.material3.Surface {
            androidx.compose.foundation.layout.Column(Modifier
                .heightIn(max = 600.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                top.yukonga.miuix.kmp.basic.Text("需先选择已保存会话并清空草稿；个人音色使用独立有限级联授权，本入口不复用其许可。")
                VoiceControlPanel(state, profiles, selected, limits,
                    onSelectProfile = { selected = it }, onLimitsChange = { limits = it },
                    onConfigureProfile = { locallyExpanded = false; onCloseRequest(); configure() }, onStart = start, onStop = host::stop,
                    modifier = Modifier.testTag("voice-universal-control"))
                cascadeEntry()
                TextButton(text = "关闭面板并结束本次", onClick = {
                    state.permitId?.let { host.stop(it) }; locallyExpanded = false; onCloseRequest()
                })
            }
        }
    }
}
