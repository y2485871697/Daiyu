package app.daiyu.android.ui.screens.chat.attachments

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.VideoChatHost
import java.util.UUID
import kotlinx.coroutines.*

/** One route-view binding, NOT a host or journal owner. Main thread only.
 *
 * A fresh registry key and a callback closing over the exact Selection are used for EVERY launch.
 * Never use a rememberUpdatedState ticket: a delayed result must not become a newer selection.
 * The non-lifecycle registry overload is intentional: registration happens on a button press after
 * STARTED; the route explicitly unregisters it on disposal. Keys are never restored/reused after
 * recreation. Only the host's launchSelection may authorize a picker lifecycle excursion.
 */
internal class VideoPickerBinding(
    private val host: VideoChatHost,
    private val registry: ActivityResultRegistry,
) : AutoCloseable {
    private class Pending(val ticket: VideoChatHost.Selection, val launcher: ActivityResultLauncher<String>)
    private var pending: Pending? = null
    private var closed = false
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var saveLauncher: ActivityResultLauncher<String>? = null
    private var saveFile: java.io.File? = null
    var selecting by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set

    fun launch(expected: VideoChatHost.Snapshot): Boolean {
        if (closed || pending != null) return false
        val ticket = host.requestSelection(expected) ?: run {
            notice = "视频尚未准备好，请稍后重试"
            return false
        }
        notice = null
        lateinit var launcher: ActivityResultLauncher<String>
        try {
            launcher = registry.register("video-picker-${UUID.randomUUID()}",
                ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
                // This callback owns ticket, not whatever ticket is current when a result arrives.
                if (!closed) {
                    val current = host.state
                    val sameVisit = current.visible && current.owner == ticket.owner &&
                        current.revision == ticket.revision && current.visit == ticket.visit
                    val admitted = host.picked(ticket, uris)
                    notice = when {
                        !admitted && !sameVisit -> "已忽略离页或旧草稿的视频选择结果，请重新选择"
                        uris.size > ticket.remaining -> "本次最多还能选择 ${ticket.remaining} 个视频；未导入任何视频"
                        !admitted -> "视频选择未导入，请重新选择"
                        else -> null
                    }
                }
                launcher.unregister()
                if (pending?.ticket === ticket) {
                    pending = null
                    selecting = false
                }
            }
            pending = Pending(ticket, launcher)
            selecting = true
            // Arm immediately around the REAL launcher invocation, not when showing picker UI.
            if (!host.launchSelection(ticket) { launcher.launch("video/*") }) {
                host.selectionLaunchFailed(ticket)
                launcher.unregister()
                pending = null
                selecting = false
                notice = "无法打开视频选择器，请重试"
                return false
            }
        } catch (_: Exception) {
            host.selectionLaunchFailed(ticket)
            pending?.launcher?.unregister()
            pending = null
            selecting = false
            notice = "无法打开视频选择器，请重试"
            return false
        }
        return true
    }

    /** Stage the authorized original before leaving for DocumentsUI; the route owns its result. */
    fun savePreviewOriginal(context: android.content.Context,
        item: app.daiyu.android.ui.media.ChatMediaPreviewItem): Boolean {
        if (closed || saveJob != null || saveLauncher != null) return false
        val application = context.applicationContext
        saveJob = saveScope.launch {
            var staged: java.io.File? = null
            try {
                val file = java.io.File.createTempFile("draft-video-save-", ".original", application.cacheDir)
                staged = file
                saveFile = file
                withContext(Dispatchers.IO) {
                    val original = item.openOriginal() ?: error("video_original_unavailable")
                    original.use { source ->
                        require(source.mimeType == item.mimeType)
                        file.outputStream().use { output ->
                            app.daiyu.android.ui.media.chatMediaPreviewCopy(source.stream, output)
                        }
                    }
                }
                ensureActive()
                val result = CompletableDeferred<Uri?>()
                val launcher = registry.register("video-save-${UUID.randomUUID()}",
                    ActivityResultContracts.CreateDocument(item.mimeType)) { uri -> result.complete(uri) }
                saveLauncher = launcher
                launcher.launch(item.displayName)
                val destination = result.await()
                if (destination != null) {
                    withContext(Dispatchers.IO) {
                        val output = application.contentResolver.openOutputStream(destination, "w")
                            ?: error("video_destination_unavailable")
                        output.use { target -> file.inputStream().use { source ->
                            app.daiyu.android.ui.media.chatMediaPreviewCopy(source, target)
                        } }
                    }
                    android.widget.Toast.makeText(application, "已保存原件", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(application, "保存失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                saveLauncher?.unregister()
                saveLauncher = null
                saveFile = null
                staged?.delete()
                saveJob = null
            }
        }
        return true
    }

    override fun close() {
        closed = true
        saveScope.cancel()
        saveLauncher?.unregister()
        saveLauncher = null
        saveFile?.delete()
        pending?.let {
            // The unique registry key will never be rebound; retire its host tombstone explicitly.
            host.selectionLaunchFailed(it.ticket)
            it.launcher.unregister()
        }
        pending = null
        selecting = false
    }
}
