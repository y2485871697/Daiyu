package app.daiyu.android.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private class ReleasePageVisit { var active = true; var request = 0L }

@Composable
internal fun SettingsReleasePage(checker: ReleaseCheck, currentVersion: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val visit = remember { ReleasePageVisit() }
    var job by remember { mutableStateOf<Job?>(null) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReleaseCheckResult?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    fun current() = visit.active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun cancel() {
        visit.request++
        job?.cancel(); job = null
        if (checking) cancelled = true
        checking = false
    }
    DisposableEffect(visit, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose { visit.active = false; cancel(); lifecycle.removeObserver(observer) }
    }
    val back: () -> Unit = { if (current()) { cancel(); onBack() } }
    BackHandler { back() }
    fun open(url: String) {
        if (!current()) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "没有可打开发布页的应用", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(context, "系统拒绝打开发布页", Toast.LENGTH_LONG).show() }
    }
    MiuixScaffoldPage("检查更新", onBack = back, modifier = modifier) {
        item("version") { SettingsNote("当前版本：$currentVersion\n公开发布仓库：y2485871697/Daiyu\n只比较稳定版本号，不比较开发提交；不自动下载或安装。") }
        item("check") {
            SettingsCard {
                SettingsRow(if (checking) "正在检查" else "检查稳定版更新",
                    tag = "release-check", onClick = if (checking) null else ({
                        if (current() && !checking) {
                            val request = ++visit.request
                            checking = true; cancelled = false; result = null
                            job = scope.launch {
                                val checked = try { checker.check(currentVersion) }
                                catch (failure: CancellationException) { throw failure }
                                catch (_: Exception) { ReleaseCheckResult.Failed(ReleaseCheckFailure.NETWORK) }
                                if (current() && request == visit.request) { result = checked; checking = false; job = null }
                            }
                        }
                    }))
                SettingsRow("打开新版发布列表", tag = "release-list", onClick = { open(RELEASE_WEB_URL) })
            }
        }
        if (cancelled) item("cancelled") { SettingsNote("检查已取消；回到前台后可手动重新检查") }
        val checked = result
        if (checked != null) item("result") {
            when (checked) {
                is ReleaseCheckResult.Available -> SettingsCard {
                    SettingsNote(when {
                        checked.comparison == null -> "已获取稳定版本 ${checked.tag}，但版本标识无法比较；请查看发布页"
                        checked.comparison > 0 -> "发现新版本 ${checked.tag}"
                        checked.comparison == 0 -> "当前版本号与公开稳定版 ${checked.tag} 相同"
                        else -> "当前版本号高于公开稳定版 ${checked.tag}"
                    })
                    SettingsRow("查看此版本发布页", onClick = { open(checked.page) })
                }
                is ReleaseCheckResult.Failed -> SettingsNote(when (checked.reason) {
                    ReleaseCheckFailure.NOT_FOUND -> "未找到公开稳定版本，或仓库当前不可访问"
                    ReleaseCheckFailure.RATE_LIMITED -> "GitHub 限流，请稍后手动重试"
                    ReleaseCheckFailure.HTTP -> "发布服务拒绝了本次检查"
                    ReleaseCheckFailure.NETWORK -> "无法连接发布服务，请检查网络后重试"
                    ReleaseCheckFailure.INVALID_RESPONSE -> "发布信息格式无效，未判定版本状态"
                    ReleaseCheckFailure.TOO_LARGE -> "发布信息超过读取上限，未判定版本状态"
                }, error = true)
            }
        }
    }
}
