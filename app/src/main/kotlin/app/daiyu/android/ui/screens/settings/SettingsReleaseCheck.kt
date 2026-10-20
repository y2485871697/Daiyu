package app.daiyu.android.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.components.UsageBody as Text
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private class ReleaseCheckVisit { var active = true; var request = 0L }
internal data class SettingsReleaseUi(val checking: Boolean, val check: () -> Unit)

@Composable
internal fun rememberSettingsReleaseCheck(
    checker: ReleaseCheck,
    currentVersion: String,
    ownerCurrent: () -> Boolean,
    onNotice: (String) -> Unit,
): SettingsReleaseUi {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val visit = remember(checker) { ReleaseCheckVisit() }
    val currentOwner by rememberUpdatedState(ownerCurrent)
    val enabled = ownerCurrent()
    val notify by rememberUpdatedState(onNotice)
    var job by remember(visit) { mutableStateOf<Job?>(null) }
    var checking by remember(visit) { mutableStateOf(false) }
    var available by remember(visit) { mutableStateOf<ReleaseCheckResult.Available?>(null) }
    fun current() = visit.active && currentOwner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun cancel() {
        visit.request++
        job?.cancel(); job = null
        checking = false
        available = null
    }
    DisposableEffect(visit, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose { visit.active = false; cancel(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled) { if (!enabled) cancel() }
    val check: () -> Unit = {
        if (current() && !checking) {
            val request = ++visit.request
            checking = true; available = null
            job = scope.launch {
                val result = try { checker.check(currentVersion) }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { ReleaseCheckResult.Failed(ReleaseCheckFailure.NETWORK) }
                if (current() && request == visit.request) {
                    checking = false; job = null
                    when (result) {
                        is ReleaseCheckResult.Available -> {
                            if (result.comparison != null && result.comparison <= 0) notify("已是最新版本")
                            else available = result
                        }
                        is ReleaseCheckResult.Failed -> notify(when (result.reason) {
                            ReleaseCheckFailure.NOT_FOUND -> "未找到公开稳定版本，或仓库当前不可访问"
                            ReleaseCheckFailure.RATE_LIMITED -> "GitHub 限流，请稍后重试"
                            ReleaseCheckFailure.HTTP -> "发布服务拒绝了本次检查"
                            ReleaseCheckFailure.NETWORK -> "无法连接发布服务，请检查网络后重试"
                            ReleaseCheckFailure.INVALID_RESPONSE -> "发布信息格式无效，未判定版本状态"
                            ReleaseCheckFailure.TOO_LARGE -> "发布信息超过读取上限，未判定版本状态"
                        })
                    }
                }
            }
        }
    }
    val release = available
    if (release != null && enabled) {
        UsageGlassDialog(title = if (release.comparison == null) "版本信息" else "发现新版本",
            onDismiss = { available = null }) {
            Text(if (release.comparison == null) "已获取稳定版本 ${release.tag}，当前版本标识无法比较，请查看发布信息。"
                else "发现新版本 ${release.tag}", Modifier.testTag("release-result"))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantPreviewButton("稍后", { available = null }, Modifier.weight(1f).testTag("release-dismiss"))
                AssistantPreviewButton("查看更新", {
                    if (current() && available == release) {
                        available = null
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page))
                                .addCategory(Intent.CATEGORY_BROWSABLE))
                        } catch (_: ActivityNotFoundException) { notify("没有可打开发布页的应用") }
                        catch (_: SecurityException) { notify("系统拒绝打开发布页") }
                    }
                }, Modifier.weight(1f).testTag("release-open"), primary = true)
            }
        }
    }
    return SettingsReleaseUi(checking, check)
}
