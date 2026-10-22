package app.daiyu.android.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.voice.offline.OfflineSpeechPack
import app.daiyu.android.voice.offline.SpeechInputPolicy
import app.daiyu.android.voice.offline.SpeechModelManifest
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun OfflineSpeechPackCard(context: Context, ownerCurrent: () -> Boolean) {
    val pack by OfflineSpeechPack.state.collectAsStateWithLifecycle()
    LaunchedEffect(context) { OfflineSpeechPack.initialize(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirm by remember { mutableStateOf(false) }
    val owner by rememberUpdatedState(ownerCurrent)
    fun canAct() = owner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    SettingsCard {
        SettingsRow(when { pack.downloading -> "取消下载"; pack.ready -> "语音包已就绪"; else -> "下载语音包" },
            tag = if (pack.downloading) "offline-speech-cancel-download" else "offline-speech-download",
            summary = when {
                pack.checking -> "正在校验语音包…"
                pack.error -> "下载未完成或校验失败，点击重试。"
                pack.downloading -> "${(pack.downloadedBytes * 100 / SpeechModelManifest.totalBytes).coerceIn(0, 100)}%"
                else -> "中英双语 · 约 ${SpeechModelManifest.totalBytes / 1_000_000} MB · 下载后离线使用"
            }, showChevron = !pack.ready, onClick = if (pack.checking || pack.ready) null else ({
                if (canAct()) { if (pack.downloading) OfflineSpeechPack.cancelDownload() else confirm = true }
            }))
        if (pack.downloading) androidx.compose.material3.LinearProgressIndicator(
            progress = { (pack.downloadedBytes.toFloat() / SpeechModelManifest.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
    }
    if (confirm) DaiyuConfirmDialog("下载语音包",
        "将从 Hugging Face 下载约 ${SpeechModelManifest.totalBytes / 1_000_000} MB 的语音模型，可能产生流量。下载不会开启麦克风，也不会自动启用听写。引擎为 Apache-2.0；模型权重授权尚未明确，请确认符合你的使用场景。",
        "下载", false, onConfirm = {
            confirm = false
            if (canAct()) OfflineSpeechPack.download(context)
        }, onDismiss = { confirm = false })
}

@Composable
internal fun OfflineSpeechSettingsScreen(context: Context, onBack: () -> Unit, ownerCurrent: () -> Boolean) {
    val pack by OfflineSpeechPack.state.collectAsStateWithLifecycle()
    var about by remember { mutableStateOf(false) }
    SettingsScaffoldPage("离线语音", onBack, modifier = Modifier.testTag("offline-speech-settings")) {
        item { OfflineSpeechPackCard(context, ownerCurrent) }
        item {
            SettingsCard {
                SettingsSwitchRow("离线听写", pack.enabled,
                    enabled = pack.enabled || SpeechInputPolicy.canEnable(pack.ready, pack.checking, pack.downloading),
                    tag = "offline-speech-enable") { if (ownerCurrent()) OfflineSpeechPack.setEnabled(context, it) }
            }
            SettingsNote("识别在本机完成；文字填入草稿，不会自动发送。")
        }
        item { SettingsCard { SettingsRow("关于离线语音", onClick = { if (ownerCurrent()) about = true }) } }
    }
    if (about) WindowDialog(show = true, title = "关于离线语音", onDismissRequest = { about = false }) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            SettingsNote("引擎：sherpa-ncnn（Apache-2.0）。识别模型支持中文和英语，不随应用分发；模型权重授权尚未明确。")
            SettingsNote("仅主动点击麦克风才会录音；识别不联网、不保存音频。切换页面或进入后台会停止听写。")
            SettingsNote("第三方许可随应用提供，详见设置中的开源许可。")
            TextButton("关闭", onClick = { about = false })
        }
    }
}
