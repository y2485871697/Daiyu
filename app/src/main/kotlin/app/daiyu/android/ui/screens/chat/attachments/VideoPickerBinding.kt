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
    var selecting by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set

    fun launch(expected: VideoChatHost.Snapshot) {
        if (closed || pending != null) return
        val ticket = host.requestSelection(expected) ?: return
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
                        !sameVisit -> "已忽略离页或旧草稿的视频选择结果，请重新选择"
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
            }
        } catch (_: Exception) {
            host.selectionLaunchFailed(ticket)
            pending?.launcher?.unregister()
            pending = null
            selecting = false
            notice = "无法打开视频选择器，请重试"
        }
    }

    override fun close() {
        closed = true
        pending?.let {
            // The unique registry key will never be rebound; retire its host tombstone explicitly.
            host.selectionLaunchFailed(it.ticket)
            it.launcher.unregister()
        }
        pending = null
        selecting = false
    }
}
