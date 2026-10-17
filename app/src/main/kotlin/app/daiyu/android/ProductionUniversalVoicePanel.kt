package app.daiyu.android

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.voice.control.*
import top.yukonga.miuix.kmp.basic.TextButton

/** The only entry is an explicit in-app control panel. Opening/selecting never acquires audio. */
@Composable
internal fun ProductionUniversalVoicePanel(
    host: ActivityUniversalVoiceHost,
    configuration: VoiceConfiguration?,
    configure: () -> Unit,
    start: (VoiceControlProfile, VoiceControlLimits) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val state by host.state.collectAsState()
    val profiles = remember(configuration?.revision) {
        configuration?.let { config -> listOf(
            VoiceControlProfile("${config.revision}:configured", 0, "会话独立配置：${config.dictation} 识别 + ${config.engine} 正文"),
            VoiceControlProfile("${config.revision}:offline", 0, "本机离线识别 + 会话独立 ${config.engine} 正文（需已启用语音包）"),
        ) }.orEmpty()
    }
    var selected by remember(profiles) { mutableStateOf(profiles.firstOrNull()) }
    var limits by remember { mutableStateOf(VoiceControlLimits()) }
    TextButton(text = if (expanded) "收起 UNIVERSAL 手动语音面板（结束本次）" else "打开 UNIVERSAL 手动持续语音面板",
        modifier = Modifier.testTag("voice-universal-open"), onClick = {
            if (expanded) state.permitId?.let { host.stop(it) }
            expanded = !expanded
        })
    if (expanded) androidx.compose.ui.window.Dialog(onDismissRequest = {
        state.permitId?.let { host.stop(it) }; expanded = false
    }) {
        androidx.compose.material3.Surface {
            androidx.compose.foundation.layout.Column(Modifier
                .heightIn(max = 600.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                top.yukonga.miuix.kmp.basic.Text("需先选择已保存会话并清空草稿；个人音色使用独立有限级联授权，本入口不复用其许可。")
                VoiceControlPanel(state, profiles, selected, limits,
                    onSelectProfile = { selected = it }, onLimitsChange = { limits = it },
                    onConfigureProfile = { expanded = false; configure() }, onStart = start, onStop = host::stop,
                    modifier = Modifier.testTag("voice-universal-control"))
                TextButton(text = "关闭面板并结束本次", onClick = {
                    state.permitId?.let { host.stop(it) }; expanded = false
                })
            }
        }
    }
}
