package app.daiyu.android.ui.navigation

import androidx.compose.runtime.getValue
import app.daiyu.android.diagnostics.JankDiagnostics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Historical previews own pixels; live pages prepare them after drawing settles. */
internal class PageBackPreviewLayers(private val context: GraphicsContext, private val scope: CoroutineScope,
    private val snapshot: suspend (Frame) -> ImageBitmap = { frame -> frame.layer.toImageBitmap() }) {
    internal class Frame(val key: Any, val layer: GraphicsLayer, var sourceKey: Any = key, var contentKey: Any = key,
        private val onRecorded: (Frame) -> Unit) {
        var size = IntSize.Zero
        var generation = 0L
        var readyGeneration = -1L
        var readySize = IntSize.Zero
        var childChangeCaptured: Boolean? = null
        var holdingForChild by mutableStateOf(false)
        var backTargetKey: Any? = null
        var returnDirection = 1f
        var animateReturn = false
        var frozen = false
        var releaseRequested = false
        val canDrawPreview: Boolean get() = image?.let { it.width == size.width && it.height == size.height } == true
        val hasFreshImage: Boolean get() = ready != null && readyGeneration == generation && readySize == size
        var ready: ImageBitmap? = null
        var image by mutableStateOf<ImageBitmap?>(null)
        var warmup: Job? = null
        var capture: Job? = null
        val captures = mutableSetOf<Job>()

        fun recorded(size: IntSize) {
            this.size = size
            generation++
            onRecorded(this)
        }
    }
    internal class Departure(val frame: Frame, val target: Any, val progress: Float,
        val predictive: Boolean, val direction: Float, val forward: Boolean = false,
        val velocity: Float = 0f, val targetImage: ImageBitmap? = null) {
        var forwardSkipped by mutableStateOf(false)
    }

    private val frames = LinkedHashMap<Any, Frame>()
    private var pending: Departure? = null
    private var closed = false
    private var active = false
    private var previewEpoch = 0L
    var currentKey: Any? = null
        private set

    fun enter(key: Any, contentKey: Any = key): Departure? {
        val previous = frames[currentKey]?.takeIf { currentKey != key }
        if (currentKey != key) frames[currentKey]?.let {
            JankDiagnostics.section("daiyu.back-preview=route-change") { freeze(it) }
        }
        currentKey = key
        val departure = pending
        pending = null
        return departure?.takeIf { it.target == key && it.frame === previous }
            ?: previous?.takeIf { it.animateReturn && it.backTargetKey == key && it.size != IntSize.Zero }
                ?.let { Departure(it, key, 0f, predictive = false, direction = it.returnDirection, targetImage = frames[key]?.image) }
    }

    fun transition(key: Any, backTargetKey: Any?, animateEnter: Boolean, size: IntSize, contentKey: Any = key): Departure? {
        val previous = frames[currentKey]?.takeIf { currentKey != key }
        val departure = enter(key, contentKey)
        if (departure != null) return departure
        if (!animateEnter || previous == null || !previous.animateReturn ||
            previous.sourceKey != backTargetKey || previous.childChangeCaptured == false) return null
        val source = frames[previous.sourceKey]?.takeIf {
            it.animateReturn && size != IntSize.Zero && it.size == size
        } ?: return null
        return Departure(source, key, 0f, predictive = false, direction = previous.returnDirection, forward = true)
    }

    fun freezeCurrent() {
        frames[currentKey]?.let {
            it.childChangeCaptured = it.animateReturn && it.size != IntSize.Zero
            it.holdingForChild = it.childChangeCaptured == true
            JankDiagnostics.section("daiyu.back-preview=child-change") { freeze(it) }
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) clearPreviews()
        else frames[currentKey]?.let { schedule(it) }
    }

    private fun schedule(frame: Frame) {
        if (closed || !active || frame.frozen || frame.releaseRequested || currentKey != frame.key || frame.size == IntSize.Zero) return
        if (frame.warmup?.isActive == true || frame.captures.any { !it.isCompleted }) return
        frame.warmup = scope.launch {
            if (frame.ready == null) withFrameNanos { } else delay(64)
            frame.warmup = null
            if (!closed && active && !frame.frozen && frames[frame.key] === frame && currentKey == frame.key) {
                capture(frame)
            }
        }
    }

    fun beginGesture(frame: Frame): Boolean {
        freeze(frame)
        return frame.canDrawPreview
    }

    fun resume(frame: Frame) {
        if (frames[currentKey] !== frame || !frame.frozen || frame.holdingForChild || pending?.frame === frame) return
        frame.frozen = false
        frame.image = null
        schedule(frame)
    }

    private fun freeze(frame: Frame) {
        if (frame.frozen) return
        frame.warmup?.cancel()
        frame.frozen = true
        if (!active || closed || frame.releaseRequested) return
        frame.image = frame.ready?.takeIf { frame.readySize == frame.size }
        JankDiagnostics.section(if (frame.image != null) "daiyu.back-preview=cache-hit" else "daiyu.back-preview=not-ready") { }
    }

    private fun capture(frame: Frame) {
        if (closed || !active || frame.releaseRequested || frame.hasFreshImage || frame.size == IntSize.Zero || frame.layer.isReleased ||
            frame.captures.any { !it.isCompleted }) return
        val epoch = previewEpoch
        val generation = frame.generation
        val size = frame.size
        JankDiagnostics.section("daiyu.back-preview=freeze-start") {
            val capture = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val traceCookie = System.identityHashCode(frame)
                JankDiagnostics.beginPhase("daiyu.back-preview=freeze", traceCookie)
                try {
                    // API26 must finish its submitted Surface copy before releasing the source layer.
                    val image = withContext(NonCancellable) { snapshot(frame) }
                    if (isActive && active && !closed && !frame.releaseRequested && !frame.frozen && previewEpoch == epoch &&
                        frames[frame.key] === frame && frame.size == size && image.width == size.width && image.height == size.height) {
                        frame.readyGeneration = generation
                        frame.readySize = size
                        frame.ready = image
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("DaiyuBackPreview", "freeze failed type=${failure.javaClass.simpleName}")
                } finally {
                    JankDiagnostics.endPhase("daiyu.back-preview=freeze", traceCookie)
                }
            }
            frame.capture = capture
            frame.captures.add(capture)
            capture.invokeOnCompletion {
                frame.captures.remove(capture)
                if (frame.releaseRequested && frame.captures.isEmpty()) context.releaseGraphicsLayer(frame.layer)
                else if (previewEpoch != epoch || frame.generation != generation) schedule(frame)
            }
        }
    }

    fun frame(key: Any, sourceKey: Any = key, contentKey: Any = key): Frame {
        frames.remove(key)?.let { release(it) }
        return Frame(key, context.createGraphicsLayer(), sourceKey, contentKey, ::schedule).also { frames[key] = it }
    }

    private fun release(frame: Frame) {
        if (frame.releaseRequested) return
        frame.releaseRequested = true
        frame.warmup?.cancel()
        frame.image = null
        frame.ready = null
        if (frame.captures.isEmpty()) context.releaseGraphicsLayer(frame.layer)
        else frame.captures.toList().forEach { it.cancel() }
    }

    fun find(key: Any?, size: IntSize): Frame? = frames[key]?.takeIf {
        size != IntSize.Zero && it.size == size && it.canDrawPreview
    }

    fun prepare(frame: Frame, target: Any, progress: Float, predictive: Boolean, direction: Float, velocity: Float = 0f): Departure {
        freeze(frame)
        return Departure(frame, target, progress, predictive, direction, velocity = velocity,
            targetImage = frames[target]?.image).also { pending = it }
    }

    fun abandon(departure: Departure) {
        if (pending === departure) {
            pending = null
            // The visible cancel settle still owns this image; resume unlocks it at rest.
        }
    }

    fun trim(current: Frame, target: Frame?, departure: Departure?, ancestors: Set<Any>) {
        val iterator = frames.entries.iterator()
        while (frames.size > 8 && iterator.hasNext()) {
            val frame = iterator.next().value
            if (frame.key in ancestors || frame === current || frame === target || frame === departure?.frame || frame.captures.any { it.isActive }) continue
            iterator.remove()
            release(frame)
        }
    }

    fun clearPreviews() {
        previewEpoch++
        pending = null
        frames.values.forEach {
            it.warmup?.cancel()
            it.captures.toList().forEach { capture -> capture.cancel() }
            it.image = null
            it.ready = null
            it.readyGeneration = -1L
            it.holdingForChild = false
            it.childChangeCaptured = null
            it.frozen = false
        }
    }

    fun close() {
        closed = true
        active = false
        pending = null
        frames.values.forEach { release(it) }
        frames.clear()
    }
}
