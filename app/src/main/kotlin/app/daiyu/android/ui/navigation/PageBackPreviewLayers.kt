package app.daiyu.android.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Freezes the outgoing live layer before its nodes detach; historical pages are pixels only. */
internal class PageBackPreviewLayers(private val context: GraphicsContext, private val scope: CoroutineScope) {
    internal class Frame(val key: Any, val layer: GraphicsLayer, var sourceKey: Any = key) {
        var size = IntSize.Zero
        var childChangeCaptured: Boolean? = null
        var holdingForChild by mutableStateOf(false)
        var backTargetKey: Any? = null
        var returnDirection = 1f
        var animateReturn = false
        var image by mutableStateOf<ImageBitmap?>(null)
        var capture: Job? = null
    }
    internal class Departure(val frame: Frame, val target: Any, val progress: Float,
        val predictive: Boolean, val direction: Float, val forward: Boolean = false) {
        var forwardSkipped by mutableStateOf(false)
    }

    private val frames = LinkedHashMap<Any, Frame>()
    private var pending: Departure? = null
    private var closed = false
    private var previewEpoch = 0L
    var currentKey: Any? = null
        private set

    fun enter(key: Any): Departure? {
        val previous = frames[currentKey]?.takeIf { currentKey != key }
        if (currentKey != key) frames[currentKey]?.let { freeze(it) }
        currentKey = key
        val departure = pending
        pending = null
        return departure?.takeIf { it.target == key && it.frame === previous }
            ?: previous?.takeIf { it.animateReturn && it.backTargetKey == key && it.size != IntSize.Zero }
                ?.let { Departure(it, key, 0f, predictive = false, direction = it.returnDirection) }
    }

    fun transition(key: Any, backTargetKey: Any?, animateEnter: Boolean, size: IntSize): Departure? {
        val previous = frames[currentKey]?.takeIf { currentKey != key }
        val departure = enter(key)
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
            freeze(it)
        }
    }

    private fun freeze(frame: Frame) {
        if (frame.image != null || frame.size == IntSize.Zero || frame.capture?.isCompleted == false) return
        val epoch = previewEpoch
        frame.capture = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // API28+ completes here; API26 submits its hardware Surface before suspending.
                val image = frame.layer.toImageBitmap()
                if (!closed && previewEpoch == epoch && frames[frame.key] === frame) frame.image = image
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && previewEpoch == epoch && frames[frame.key] === frame) frame.image = null
                android.util.Log.w("DaiyuBackPreview", "freeze failed type=${failure.javaClass.simpleName}")
            }
        }
    }

    fun frame(key: Any, sourceKey: Any = key): Frame {
        frames.remove(key)?.let { release(it) }
        return Frame(key, context.createGraphicsLayer(), sourceKey).also { frames[key] = it }
    }

    private fun release(frame: Frame) {
        // Each visit owns its frame, so an older capture can never publish into a new visit.
        val capture = frame.capture
        if (capture != null && !capture.isCompleted) {
            capture.invokeOnCompletion { context.releaseGraphicsLayer(frame.layer) }
            capture.cancel()
        } else context.releaseGraphicsLayer(frame.layer)
    }

    fun find(key: Any?, size: IntSize): Frame? = frames[key]?.takeIf {
        size != IntSize.Zero && it.size == size && it.image != null
    }

    fun prepare(frame: Frame, target: Any, progress: Float, predictive: Boolean, direction: Float): Departure {
        return Departure(frame, target, progress, predictive, direction).also { pending = it }
    }

    fun abandon(departure: Departure) {
        if (pending === departure) pending = null
    }

    fun trim(current: Frame, target: Frame?, departure: Departure?, ancestors: Set<Any>) {
        val iterator = frames.entries.iterator()
        while (frames.size > 8 && iterator.hasNext()) {
            val frame = iterator.next().value
            if (frame.key in ancestors || frame === current || frame === target || frame === departure?.frame || frame.capture?.isActive == true) continue
            iterator.remove()
            frame.image = null
            release(frame)
        }
    }

    fun clearPreviews() {
        previewEpoch++
        pending = null
        frames.values.forEach {
            it.capture?.cancel()
            it.image = null
            it.holdingForChild = false
            it.childChangeCaptured = null
        }
    }

    fun close() {
        closed = true
        pending = null
        frames.values.forEach {
            it.image = null
            release(it)
        }
        frames.clear()
    }
}
