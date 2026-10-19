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
    val owner by rememberUpdatedState(ownerCurrent)
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { alive[0] = true; onDispose { alive[0] = false } }
    fun canAct() = alive[0] && owner()
    MiuixScaffoldPage(title = "离线语音（sherpa-ncnn）", onBack = { if (canAct()) onBack() },
        modifier = Modifier.testTag("offline-speech-settings")) {
        item(key = "offline-pack") {
            SettingsSectionTitle("本机中文 / English 听写")
            SettingsCard {
                SettingsRow("模型状态", summary = when {
                    pack.checking -> "正在核对本地文件大小与 SHA-256…"
                    pack.downloading -> "已下载 ${pack.downloadedBytes / 1024 / 1024} / ${SpeechModelManifest.totalBytes / 1024 / 1024} MiB"
                    pack.ready -> "七个文件校验通过，模型保存在应用私有免备份目录"
                    pack.error -> "下载或校验失败，可显式重试"
                    else -> "尚未下载，约 ${SpeechModelManifest.totalBytes / 1024 / 1024} MiB"
                })
                if (!pack.ready && !pack.checking && !pack.downloading) {
                    SettingsRow(if (confirm) "确认下载官方模型" else "下载离线语音模型",
                        tag = "offline-speech-download", summary = if (confirm)
                            "从 Hugging Face 下载固定版本，可能产生流量；下载不会启用麦克风"
                        else "需要显式确认；不随 APK 分发权重",
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
                    summary = if (pack.enabled) "已独立启用；仅主动点击麦克风才会录音" else "下载完成后仍需单独启用") { value ->
                    if (canAct()) OfflineSpeechPack.setEnabled(context, value)
                }

            }
            SettingsNote("真实 16 kHz 单声道 PCM、CPU 原生识别，不调用系统 SpeechRecognizer。录音及识别不联网、不保存音频。结果仅填入捕获的草稿选区，不自动发送；编辑、切换页面、后台或再次点击会取消。无文本 8 秒、单次最长 60 秒。")
        }
        item(key = "offline-license") {
            SettingsSectionTitle("来源与许可")
            SettingsNote("引擎：k2-fsa/sherpa-ncnn v2.1.15（Apache-2.0）；模型：csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13。官方固定版本 README metadata 声明 Apache-2.0；不是本应用原创模型。")
            SettingsNote("模型 revision：${SpeechModelManifest.REVISION}。七文件大小与 SHA-256 固定在 SpeechModelManifest；完整许可、KISS FFT 归属和固定模型卡随 APK 位于 assets/licenses/sherpa-ncnn/，源码见 THIRD_PARTY/sherpa-ncnn。语音模型与聊天模型设置互相独立。")
        }
    }
}
