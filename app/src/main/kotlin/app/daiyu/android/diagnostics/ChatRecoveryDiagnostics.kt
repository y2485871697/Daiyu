package app.daiyu.android.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject

/** Numeric layout witnesses only. A native draw is not a compositor presentation. */
internal data class ChatRecoveryGeometry(
    val width: Int, val height: Int, val x: Int, val y: Int, val attached: Boolean,
    val firstIndex: Int, val firstOffset: Int, val totalItems: Int,
    val revealEpoch: Long, val initialPending: Boolean, val resumePending: Boolean,
    val following: Boolean, val scrolling: Boolean,
    val nestedOffset: Int, val nestedScroll: Int, val nestedHeight: Int,
    val items: List<Item>,
) {
    data class Item(val index: Int, val offset: Int, val size: Int)
    fun json(): JSONObject = JSONObject().put("viewport", JSONArray(listOf(width, height, x, y)))
        .put("attached", attached).put("first", JSONArray(listOf(firstIndex, firstOffset)))
        .put("total_items", totalItems).put("reveal_epoch", revealEpoch)
        .put("initial_pending", initialPending).put("resume_pending", resumePending)
        .put("following", following).put("scrolling", scrolling)
        .put("nested", JSONArray(listOf(nestedOffset, nestedScroll, nestedHeight)))
        .put("items", JSONArray().apply { items.forEach { put(JSONArray(listOf(it.index, it.offset, it.size))) } })
}

internal class ChatRecoveryRecorder(
    private val clock: () -> Long = System::nanoTime,
    private val epoch: () -> Long = RuntimeDiagnostics::backEpoch,
    private val emit: (Incident) -> Unit = RuntimeDiagnostics::chatRecovery,
) {
    enum class End { LIMIT, TIMEOUT, INACTIVE, DETACHED, SAMPLE_FAILED }
    data class Frame(val offset: Long, val geometry: ChatRecoveryGeometry)
    data class Incident(val epoch: Long, val serial: Long, val elapsed: Long, val end: End, val frames: List<Frame>)
    private var initialized = false
    private var active = false
    private var serial = 0L
    private var started = 0L
    private var captureEpoch = 0L
    private var frames: MutableList<Frame>? = null
    val observing: Boolean get() = frames != null

    fun active(value: Boolean): Long? {
        if (!initialized) { initialized = true; active = value; return null }
        if (value == active) return null
        active = value
        if (!value) { finish(End.INACTIVE); return null }
        serial++
        started = clock(); captureEpoch = epoch(); frames = ArrayList(MAX_FRAMES)
        return serial
    }

    fun draw(geometry: ChatRecoveryGeometry) {
        val target = frames ?: return
        if (clock() - started >= WINDOW_NS) { finish(End.TIMEOUT); return }
        target += Frame((clock() - started).coerceAtLeast(0), geometry.copy(items = geometry.items.take(MAX_ITEMS)))
        if (target.size == MAX_FRAMES) finish(End.LIMIT)
    }

    fun timeout(token: Long) { if (token == serial) finish(End.TIMEOUT) }
    fun finish(end: End) {
        val captured = frames ?: return
        frames = null
        emit(Incident(captureEpoch, serial, (clock() - started).coerceAtLeast(0), end, captured.toList()))
    }

    companion object {
        const val MAX_FRAMES = 16
        const val MAX_ITEMS = 6
        const val WINDOW_MS = 750L
        const val WINDOW_NS = WINDOW_MS * 1_000_000L
    }
}

@Composable
internal fun ObserveChatRecovery(active: Boolean, geometry: () -> ChatRecoveryGeometry) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentActive = rememberUpdatedState(active)
    val currentGeometry = rememberUpdatedState(geometry)
    val recorder = remember(view, lifecycle) { ChatRecoveryRecorder() }
    val handler = remember(view) { Handler(Looper.getMainLooper()) }
    fun update() {
        val token = recorder.active(currentActive.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (token != null) handler.postAtTime({ recorder.timeout(token) }, recorder,
            SystemClock.uptimeMillis() + ChatRecoveryRecorder.WINDOW_MS)
    }
    DisposableEffect(view, lifecycle, recorder) {
        val draw = ViewTreeObserver.OnDrawListener {
            if (recorder.observing) try { recorder.draw(currentGeometry.value()) }
            catch (_: Exception) { recorder.finish(ChatRecoveryRecorder.End.SAMPLE_FAILED) }
        }
        val tree = view.viewTreeObserver
        tree.addOnDrawListener(draw)
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (tree.isAlive) tree.removeOnDrawListener(draw)
            else if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(draw)
            handler.removeCallbacksAndMessages(recorder)
            recorder.finish(ChatRecoveryRecorder.End.DETACHED)
        }
    }
    SideEffect { update() }
}
