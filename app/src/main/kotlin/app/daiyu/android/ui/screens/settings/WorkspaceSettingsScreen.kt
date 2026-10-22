package app.daiyu.android.ui.screens.settings

import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.haptics.TouchHaptics
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.platform.PlatformSettingsAccess
import app.daiyu.android.workspace.WorkspaceFilesAccess
import app.daiyu.android.workspace.WorkspaceListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.haptics.miuix.TextButton
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class WorkspaceSettingsSection { ALL, FILES, SHARED }

private class WorkspaceRevoke(val uri: Uri)
private data class WorkspaceDirectoryPosition(val tree: Uri, val index: Int, val offset: Int)

@Composable
internal fun WorkspaceSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier,
    suppliedAccess: WorkspaceFilesAccess? = null, engineeringUi: Boolean = false,
    section: WorkspaceSettingsSection = WorkspaceSettingsSection.ALL, pageTitle: String = "工作区",
    integratedLinux: Boolean = false, rootfs: app.daiyu.android.terminal.LinuxInstallation? = null,
    onOpenTerminal: (() -> Unit)? = null) {
    val context = LocalContext.current
    val access = suppliedAccess ?: remember(context) { WorkspaceFilesAccess(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val visit = remember(access, lifecycle) { AtomicBoolean(true) }
    val epoch = remember(access, lifecycle) { AtomicLong() }
    var callbackEpoch by remember(access, lifecycle) { mutableLongStateOf(0L) }
    var grants by remember(access) { mutableStateOf<List<Uri>>(emptyList()) }
    var loaded by remember(access) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var privateOccupied by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(LinuxFileLocation.WORKSPACE) }
    var fileScope by remember { mutableStateOf(Any()) }
    val renderedScope = fileScope
    val showPrivate = if (integratedLinux) location == LinuxFileLocation.WORKSPACE else section != WorkspaceSettingsSection.SHARED
    val showShared = if (integratedLinux) location == LinuxFileLocation.SHARED else engineeringUi || section == WorkspaceSettingsSection.SHARED
    var notice by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<WorkspaceListing?>(null) }
    var shownTree by remember { mutableStateOf<Uri?>(null) }
    var lastTree by remember { mutableStateOf<Uri?>(null) }
    var restoringDirectory by remember { mutableStateOf(false) }
    var listState by remember { mutableStateOf(LazyListState()) }
    var retainedPosition by remember(access) { mutableStateOf<WorkspaceDirectoryPosition?>(null) }
    val loadingListState = rememberLazyListState()
    var pendingTree by remember { mutableStateOf<Boolean?>(null) }
    var request by remember { mutableStateOf<AtomicBoolean?>(null) }
    var revoking by remember { mutableStateOf<WorkspaceRevoke?>(null) }
    fun current() = visit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun sharingCurrent() = current() && showShared && fileScope === renderedScope
    fun invalidateRead() { request?.set(true); request = null; listing = null; shownTree = null; restoringDirectory = false }
    fun browse(uri: Uri, restoring: Boolean = false) {
        if (!current() || !restoring && !sharingCurrent() || busy || privateOccupied || !loaded || uri !in grants) return
        if (!restoring) retainedPosition = null
        invalidateRead(); notice = null; restoringDirectory = restoring
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { access.files(uri) { !visit.get() || token.get() } }
                if (current() && request === token && !token.get()) {
                    retainedPosition?.takeIf { restoring && it.tree == uri }?.let {
                        listState = LazyListState(it.index, it.offset)
                    }
                    listing = result; shownTree = uri; lastTree = uri; retainedPosition = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current() && request === token && !token.get()) { lastTree = null; retainedPosition = null; notice = "目录未读取，请刷新或重新选择" }
            }
            finally { if (request === token) { request = null; restoringDirectory = false } }
        }
    }
    fun refresh(clearNotice: Boolean = true) {
        if (!current() || busy || privateOccupied) return
        invalidateRead(); revoking = null; grants = emptyList(); loaded = false
        if (clearNotice) notice = null
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                val values = withContext(Dispatchers.IO) { access.grants() }
                if (current() && request === token && !token.get()) {
                    grants = values; loaded = true; request = null
                    val tree = lastTree
                    if (tree != null && tree in values) browse(tree, restoring = true)
                    else { lastTree = null; retainedPosition = null }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current() && request === token && !token.get()) { grants = emptyList(); lastTree = null; retainedPosition = null; loaded = true; request = null; notice = "共享目录暂时不可用，请刷新或重新选择" } }
        }
    }
    DisposableEffect(access, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
            if (event == Lifecycle.Event.ON_PAUSE) {
                val tree = shownTree
                if (loaded && !restoringDirectory && tree != null && listing != null) {
                    retainedPosition = WorkspaceDirectoryPosition(tree,
                        listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                }
                callbackEpoch = epoch.incrementAndGet(); invalidateRead(); revoking = null; grants = emptyList(); loaded = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); invalidateRead(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(access) { refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tree = pendingTree
        pendingTree = null
        if (visit.get() && tree != null && result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            busy = true; retainedPosition = null; invalidateRead()
            val admittedEpoch = epoch.get()
            scope.launch {
                try {
                    val accepted = withContext(Dispatchers.IO) { visit.get() && access.accept(data, tree) }
                    val values = withContext(Dispatchers.IO) { access.grants() }
                    lifecycle.withResumed {
                        if (current() && epoch.get() == admittedEpoch) {
                            grants = values; loaded = true
                            notice = if (accepted) "已添加只读共享${if (tree) "目录" else "文件"}" else "未取得有效的持久只读授权，请重新选择"
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    lifecycle.withResumed {
                        if (current() && epoch.get() == admittedEpoch) notice = "共享未确认成功，请刷新并检查系统授权"
                    }
                }
                finally { if (visit.get()) { busy = false; if (current() && (epoch.get() != admittedEpoch || !loaded)) refresh(clearNotice = false) } }
            }
        }
    }
    val pickerCallbackEpoch = callbackEpoch
    fun pick(tree: Boolean) {
        if (!sharingCurrent() || epoch.get() != pickerCallbackEpoch || busy || privateOccupied || pendingTree != null) return
        pendingTree = tree; revoking = null; retainedPosition = null; invalidateRead()
        try { picker.launch(PlatformSettingsAccess.pickerIntent(tree)) }
        catch (_: Exception) { pendingTree = null; notice = "未能打开系统文件选择器" }
    }
    fun copy(uri: Uri) {
        if (!sharingCurrent() || busy || privateOccupied || !loaded) return
        val available = uri in grants || listing?.files?.any { it.uri == uri } == true
        if (!available) return
        try {
            checkNotNull(context.getSystemService(ClipboardManager::class.java))
                .setPrimaryClip(ClipData.newPlainText("文件引用", uri.toString()))
            notice = "已复制文件引用；尚未发送"
        } catch (_: Exception) { notice = "文件引用未复制" }
    }
    val revoke = revoking
    if (revoke != null) app.daiyu.android.ui.components.DaiyuConfirmDialog(
        title = "撤销共享授权", confirmText = "撤销", destructive = true, message = "撤销后，本应用将无法再读取该目录或文件。不会删除原文件。",
        onDismiss = { if (revoking === revoke) revoking = null }, onConfirm = {
            if (sharingCurrent() && !busy && !privateOccupied && loaded && revoke.uri in grants && revoking === revoke) {
                revoking = null; busy = true; retainedPosition = null; invalidateRead()
                val admittedEpoch = epoch.get()
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { if (visit.get()) access.revoke(revoke.uri) }
                        val values = withContext(Dispatchers.IO) { access.grants() }
                        if (current() && epoch.get() == admittedEpoch) { grants = values; loaded = true; notice = "共享授权已撤销" }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (current() && epoch.get() == admittedEpoch) notice = "撤销未确认成功，请刷新并检查系统授权" }
                    finally { if (visit.get()) { busy = false; if (current() && (epoch.get() != admittedEpoch || !loaded)) refresh() } }
                }
            }
        })
    val back: () -> Unit = { if (current()) { visit.set(false); revoking = null; invalidateRead(); onBack() } }
    BackHandler { back() }
    val privatePanel = if (showPrivate) rememberPrivateWorkspacePanel(parentVisit = visit, compact = integratedLinux,
        sharedBusy = { busy || pendingTree != null }, onOccupied = {
            privateOccupied = it
            if (!it && current() && !loaded) refresh(clearNotice = false)
        }) else null
    val rootfsPanel = if (integratedLinux && location == LinuxFileLocation.ROOTFS) key(rootfs) { rememberLinuxRootfsPanel(rootfs?.rootfs) } else null
    // Loading must not measure the retained position against a temporarily shorter list.
    SettingsScaffoldPage(title = pageTitle, onBack = back, modifier = modifier, compactTitle = integratedLinux,
        listState = if (showShared && (!loaded || restoringDirectory)) loadingListState else listState, actions = {
        privatePanel?.actions?.invoke()
        rootfsPanel?.actions?.invoke()
        if (integratedLinux && showShared) app.daiyu.android.ui.haptics.miuix.IconButton(
            onClick = { if (sharingCurrent()) refresh() },
            enabled = loaded && !busy && !privateOccupied && pendingTree == null && request == null,
            modifier = Modifier.size(48.dp).testTag("workspace-refresh")) {
            app.daiyu.android.ui.icons.DaiyuIcon(app.daiyu.android.ui.icons.DaiyuIcons.Refresh, "刷新", size = 22.dp)
        }
        if (onOpenTerminal != null) app.daiyu.android.ui.haptics.miuix.IconButton(onClick = {
            if (current() && !busy && !privateOccupied && pendingTree == null) onOpenTerminal()
        }, modifier = Modifier.size(48.dp).testTag("linux-open-terminal")) {
            app.daiyu.android.ui.icons.DaiyuIcon(app.daiyu.android.ui.icons.DaiyuIcons.Terminal, "终端")
        }
    }) {
        if (integratedLinux) item(key = "linux-file-locations") {
            LinuxFileLocationTabs(location, !busy && !privateOccupied && pendingTree == null) {
                if (current() && fileScope === renderedScope && !busy && !privateOccupied && pendingTree == null && location != it) {
                    invalidateRead(); lastTree = null; retainedPosition = null; revoking = null; notice = null; fileScope = Any(); location = it
                    if (it == LinuxFileLocation.SHARED && !loaded) refresh(clearNotice = false)
                }
            }
        }
        if (privatePanel != null) item(key = "private-workspace") { privatePanel() }
        if (rootfsPanel != null) item(key = "linux-rootfs") { rootfsPanel() }
        if (showShared) {
        item(key = "sharing") {
            if (!integratedLinux) SettingsNote("选择要共享的目录或文件，访问只读。Linux 目录挂载在 Linux 工具环境中单独配置。")
            SettingsCard {
                SettingsRow("共享目录", onClick = { pick(true) }, tag = "workspace-pick-directory")
                SettingsRow("共享文件", onClick = { pick(false) }, tag = "workspace-pick-file")
                if (!integratedLinux) SettingsRow("刷新", onClick = { if (sharingCurrent()) refresh() }, tag = "workspace-refresh")
            }
            notice?.let { SettingsNote(it) }
            if (busy || !loaded) SettingsNote("正在读取共享状态")
            else if (request != null) SettingsNote("正在读取目录")
            else if (grants.isEmpty()) {
                if (integratedLinux) LinuxFileEmptyState("还没有共享目录或文件")
                else SettingsNote("还没有共享目录或文件")
            }
        }
        grants.forEachIndexed { index, uri ->
            item(key = "grant:$uri") {
                SettingsCard {
                    SettingsRow(if (DocumentsContract.isTreeUri(uri)) "共享目录 ${index + 1}" else "共享文件 ${index + 1}",
                        summary = uri.toString(), tag = "workspace-grant:$index")
                    if (DocumentsContract.isTreeUri(uri)) WorkspaceButton("查看文件", tag = "workspace-browse:$index", onClick = {
                        browse(uri)
                    })
                    WorkspaceButton("复制引用", tag = "workspace-copy:$index", onClick = { copy(uri) })
                    WorkspaceButton("撤销共享", tag = "workspace-revoke:$index", onClick = {
                        if (sharingCurrent() && !busy && !privateOccupied && loaded) revoking = WorkspaceRevoke(uri)
                    })
                }
            }
        }
        listing?.let { result ->
            item(key = "files-heading") {
                SettingsSectionTitle("目录中的文件")
                if (result.files.isEmpty()) SettingsNote("此目录为空")
                if (result.truncated) SettingsNote("仅显示部分文件，请在系统文件选择器中缩小目录范围")
            }
            result.files.forEachIndexed { index, file ->
                item(key = "file:${shownTree}:${file.uri}") {
                    SettingsCard { SettingsRow(file.name, summary = if (integratedLinux) null else if (file.directory) "子目录 · 点击复制引用" else "点击复制文件引用",
                        tag = "workspace-file:$index", onClick = { copy(file.uri) }) }
                }
            }
        }
        }
    }
}

@Composable
private fun WorkspaceButton(text: String, tag: String, onClick: () -> Unit) {
    val feedbackView = LocalView.current
    TextButton(text, onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { semanticsClick { TouchHaptics.action(feedbackView) { onClick() }; true } })
}
