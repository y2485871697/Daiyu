package app.daiyu.android.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.voice.offline.OfflineSpeechPack
import app.daiyu.android.voice.offline.SpeechInputPolicy
import app.daiyu.android.voice.offline.SpeechModelManifest

/** Separate from system/cloud voice settings. Opening this page never downloads or records. */
@Composable
internal fun OfflineSpeechSettingsScreen(
    context: Context,
    onBack: () -> Unit,
    ownerCurrent: () -> Boolean,
) {
    val pack by OfflineSpeechPack.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    val owner by rememberUpdatedState(ownerCurrent)
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { alive[0] = true; onDispose { alive[0] = false } }
    fun canAct() = alive[0] && owner()
    MiuixScaffoldPage(title = "离线语音", onBack = { if (canAct()) onBack() },
        modifier = Modifier.testTag("offline-speech-settings")) {
        item(key = "offline-pack") {
            SettingsSectionTitle("中文 / English 听写")
            SettingsCard {
                SettingsRow("模型状态", summary = when {
                    pack.checking -> "正在校验模型文件…"
                    pack.downloading -> "已下载 ${pack.downloadedBytes / 1024 / 1024} / ${SpeechModelManifest.totalBytes / 1024 / 1024} MiB"
                    pack.ready -> "已就绪，可在本机识别中文和英语"
                    pack.error -> "下载或校验失败，请重试"
                    else -> "尚未下载，约 ${SpeechModelManifest.totalBytes / 1024 / 1024} MiB"
                })
                if (!pack.ready && !pack.checking && !pack.downloading) {
                    SettingsRow(if (confirm) "确认下载官方模型" else "下载离线语音模型",
                        tag = "offline-speech-download", summary = if (confirm)
                            "从 Hugging Face 下载，可能产生流量；不会启用麦克风"
                        else "首次使用需要下载模型，完成后仍需启用听写",
                        onClick = {
                            if (canAct()) {
                                if (confirm) { confirm = false; OfflineSpeechPack.download(context) }
                                else confirm = true
                            }
                        })
                    if (confirm) SettingsRow("取消下载确认", onClick = { if (canAct()) confirm = false })
                }
                if (pack.downloading) SettingsRow("取消下载", tag = "offline-speech-cancel-download",
                    onClick = { if (canAct()) OfflineSpeechPack.cancelDownload() })
                SettingsSwitchRow("离线听写", checked = pack.enabled,
                    enabled = pack.enabled || SpeechInputPolicy.canEnable(pack.ready, pack.checking, pack.downloading),
                    tag = "offline-speech-enable",
                    summary = if (pack.enabled) "点击麦克风开始听写" else "模型就绪后可启用") { value ->
                    if (canAct()) OfflineSpeechPack.setEnabled(context, value)
                }
            }
            SettingsNote("仅主动点击麦克风才会录音。识别不联网、不保存音频；结果填入草稿，不会自动发送。")
            SettingsNote("编辑草稿、切换页面、进入后台或再次点击会取消听写。8 秒无文本自动停止，单次最长 60 秒。")
        }
        item(key = "offline-license") {
            SettingsCard {
                SettingsRow("诊断详情", summary = if (diagnosticsExpanded) "收起模型来源、校验与许可" else "展开模型来源、校验与许可",
                    tag = "offline-speech-diagnostics", onClick = { if (canAct()) diagnosticsExpanded = !diagnosticsExpanded })
                if (diagnosticsExpanded) {
                    SettingsNote("引擎：k2-fsa/sherpa-ncnn v2.1.15（Apache-2.0）。16 kHz 单声道 PCM、CPU 原生识别，不调用系统 SpeechRecognizer。语音模型与聊天模型设置互相独立。")
                    SettingsNote("模型：csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13。官方固定版本 README metadata 声明 Apache-2.0；不是本应用原创模型，权重不随 APK 分发。")
                    SettingsNote("模型 revision：${SpeechModelManifest.REVISION}。七文件大小与 SHA-256 固定在 SpeechModelManifest；模型保存在应用私有免备份目录。")
                    SettingsNote("完整许可、KISS FFT 归属和固定模型卡随 APK 位于 assets/licenses/sherpa-ncnn/，源码见 THIRD_PARTY/sherpa-ncnn。")
                }
            }
        }
    }
}
