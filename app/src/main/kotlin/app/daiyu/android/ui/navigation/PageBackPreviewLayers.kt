package app.daiyu.android.ui.navigation

import androidx.compose.runtime.getValue
import app.daiyu.android.diagnostics.JankDiagnostics
import app.daiyu.android.diagnostics.BackPerformance
import app.daiyu.android.diagnostics.BackPerformanceRecorder
import app.daiyu.android.diagnostics.captureBackPerformanceToken
import app.daiyu.android.diagnostics.synchronousTrace
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
    private val contextChangeReason: Int = 0,
    private val snapshot: suspend (Frame) -> ImageBitmap = { frame -> frame.layer.toImageBitmap() }) {
    internal class Frame(val key: Any, val layer: GraphicsLayer, var sourceKey: Any = key, var contentKey: Any = key,
        private val onRecorded: (Frame) -> Unit) {
        var size = IntSize.Zero
        var generation = 0L
        var captureEpoch = 0L
        var readyGeneration = -1L
        var readySize = IntSize.Zero
        var childChangeCaptured: Boolean? = null
        var holdingForChild by mutableStateOf(false)
        var backTargetKey: Any? = null
        var returnDirection = 1f
        var animateReturn = false
        var frozen = false
        var navigationFrozen = false
        var releaseRequested = false
        val canDrawPreview: Boolean get() = image?.let { it.width == size.width && it.height == size.height } == true
        val hasFreshImage: Boolean get() = ready != null && readyGeneration == generation && readySize == size
        var ready: ImageBitmap? = null
        var inheritedImage: ImageBitmap? = null
        var image by mutableStateOf<ImageBitmap?>(null)
        var warmup: Job? = null
        var capture: Job? = null
        val captures = mutableSetOf<Job>()

        fun recorded(size: IntSize) {
            this.size = size
            generation++
            inheritedImage = null
            onRecorded(this)
        }
    }
    internal class Departure(val frame: Frame, val target: Any, val progress: Float,
        val predictive: Boolean, val direction: Float, val forward: Boolean = false,
        val velocity: Float = 0f, val targetImage: ImageBitmap? = null,
        val motion: PageBackCompletionMotion? = null) {
        var forwardSkipped by mutableStateOf(false)
        var returnSkipped by mutableStateOf(false)
    }

    private val frames = LinkedHashMap<Any, Frame>()
    private var pending: Departure? = null
    private var closed = false
    private var active = false
    private var previewEpoch = 0L
    private var resumedSinceGesture = false
    var currentKey: Any? = null
        private set

    fun enter(key: Any, contentKey: Any = key): Departure? {
        val previous = frames[currentKey]?.takeIf { currentKey != key }
        val returning = pending?.let { it.target == key && it.frame === previous } == true || previous?.backTargetKey == key
        if (currentKey != key) frames[currentKey]?.let {
            JankDiagnostics.section("daiyu.back-preview=route-change") {
                if (returning) freeze(it) else freezeForNavigation(it)
            }
        }
        currentKey = key
        val departure = pending
        pending = null
        val accepted = departure?.takeIf { it.target == key && it.frame === previous }
        departure?.motion?.let { motion ->
            BackPerformance.mark(if (accepted == null) BackPerformanceRecorder.Event.MOTION_REJECTED
                else BackPerformanceRecorder.Event.MOTION_ACCEPTED, motion.diagnosticToken)
        }
        if (accepted == null) departure?.motion?.cancel()
        return accepted
            ?: previous?.takeIf { it.animateReturn && it.backTargetKey == key && it.size != IntSize.Zero }
                ?.let { Departure(it, key, 0f, predictive = false, direction = it.returnDirection, targetImage = frames[key]?.image) }
    }

    fun transition(key: Any, backTargetKey: Any?, animateEnter: Boolean, size: IntSize, contentKey: Any = key): Departure? {
        return synchronousTrace("daiyu.back.layers.transition") {
            val previous = frames[currentKey]?.takeIf { currentKey != key }
            val departure = enter(key, contentKey)
            if (departure != null) return@synchronousTrace departure
            if (!animateEnter || previous == null || !previous.animateReturn ||
                previous.sourceKey != backTargetKey || previous.childChangeCaptured == false) return@synchronousTrace null
            val source = frames[previous.sourceKey]?.takeIf {
                it.animateReturn && size != IntSize.Zero && it.size == size && it.canDrawPreview
            } ?: return@synchronousTrace null
            Departure(source, key, 0f, predictive = false, direction = previous.returnDirection, forward = true)
        }
    }

    fun freezeCurrent(targetKey: Any? = null) {
        frames[currentKey]?.let {
            if (targetKey != null && it.backTargetKey == targetKey) {
                freeze(it)
            } else {
                it.childChangeCaptured = it.animateReturn && it.size != IntSize.Zero
                it.holdingForChild = it.childChangeCaptured == true
                JankDiagnostics.section("daiyu.back-preview=child-change") { freezeForNavigation(it) }
            }
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            resumedSinceGesture = true
            pending?.motion?.cancel()
            pending = null
            frames.values.forEach { frame ->
                frame.warmup?.cancel()
                // A covered ancestor's final read was submitted before its content left.
                // Let that immutable read drain; only the live page and warm reads expire.
                if (frame.key == currentKey || !frame.navigationFrozen) {
                    frame.captureEpoch++
                    frame.captures.toList().forEach { it.cancel() }
                }
            }
            // Ancestors cannot redraw while covered. Keep their immutable target pixels.
            frames[currentKey]?.let { frame ->
                frame.image = null
                frame.ready = null
                frame.inheritedImage = null
                frame.readyGeneration = -1L
                frame.holdingForChild = false
                frame.childChangeCaptured = null
                frame.frozen = false
                frame.navigationFrozen = false
            }
        } else {
            frames[currentKey]?.let { schedule(it) }
        }
    }

    private fun schedule(frame: Frame) = schedule(frame, captureBackPerformanceToken())

    private fun schedule(frame: Frame, sourceToken: BackPerformanceRecorder.Token?) {
        if (closed || !active || frame.frozen || frame.releaseRequested || currentKey != frame.key || frame.generation == 0L || frame.size == IntSize.Zero) return
        if (frame.warmup?.isActive == true || frame.captures.any { !it.isCompleted }) return
        frame.warmup = scope.launch {
            if (frame.ready == null) withFrameNanos { } else delay(64)
            frame.warmup = null
            if (!closed && active && !frame.frozen && frames[frame.key] === frame && currentKey == frame.key) {
                capture(frame, sourceToken)
            }
        }
    }

    private fun observeCaptures(sourceToken: BackPerformanceRecorder.Token?) {
        BackPerformance.safely {
            val count = frames.values.sumOf { frame -> frame.captures.count { !it.isCompleted } }
            if (count > 0) BackPerformance.mark(BackPerformanceRecorder.Event.OBSERVED_CAPTURE_IN_FLIGHT, sourceToken, count)
        }
    }

    // Fixed diagnostic codes; no route keys or pixels enter the performance record.
    fun missingTargetReason(key: Any?, size: IntSize): Int {
        if (key == null) return 1
        val target = frames[key] ?: return 2
        if (size == IntSize.Zero) return 3
        if (target.size != size) return 4
        if (target.captures.any { !it.isCompleted }) return 5
        return if (target.canDrawPreview) 0 else 6
    }

    fun beginGesture(frame: Frame, target: Frame?, missingTargetReason: Int = 0): Boolean {
        val token = captureBackPerformanceToken()
        if (resumedSinceGesture) {
            BackPerformance.mark(BackPerformanceRecorder.Event.PREVIEW_AFTER_RESUME, token)
            resumedSinceGesture = false
        }
        if (target == null) {
            observeCaptures(token)
            BackPerformance.mark(BackPerformanceRecorder.Event.PREVIEW_TARGET_MISSING, token, missingTargetReason)
            if (contextChangeReason != 0) {
                BackPerformance.mark(BackPerformanceRecorder.Event.PREVIEW_CONTEXT_CHANGED, token, contextChangeReason)
            }
            return false
        }
        if (target.readyGeneration >= 0 && target.readyGeneration < target.generation) {
            BackPerformance.mark(BackPerformanceRecorder.Event.PREVIEW_TARGET_STALE, token,
                (target.generation - target.readyGeneration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return beginGesture(frame)
    }

    fun beginGesture(frame: Frame): Boolean {
        val token = captureBackPerformanceToken()
        observeCaptures(token)
        val canBegin = active && !closed && frames[frame.key] === frame && frame.size != IntSize.Zero &&
            (frame.generation > 0L || frame.inheritedImage != null) && !frame.releaseRequested && !frame.layer.isReleased
        if (frame.readyGeneration >= 0 && frame.readyGeneration < frame.generation) {
            BackPerformance.mark(BackPerformanceRecorder.Event.PREVIEW_SOURCE_STALE, token,
                (frame.generation - frame.readyGeneration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        freeze(frame)
        BackPerformance.mark(if (canBegin) BackPerformanceRecorder.Event.PREVIEW_READY
            else BackPerformanceRecorder.Event.PREVIEW_SOURCE_MISSING, token)
        // A fresh copy can still be draining. Until then the same live page remains visible.
        return canBegin
    }

    fun resume(frame: Frame) {
        if (frames[currentKey] !== frame || !frame.frozen || frame.holdingForChild || pending?.frame === frame) return
        frame.frozen = false
        frame.navigationFrozen = false
        frame.image = null
        schedule(frame)
    }

    private fun freeze(frame: Frame) {
        if (frame.frozen) return
        // Child graphics layers can change without advancing the outer recording generation.
        // Freeze the pixels visible now, never a warm bitmap from a previous tab or layout.
        freezeForNavigation(frame)
    }

    private fun freezeForNavigation(frame: Frame) {
        if (frame.navigationFrozen) return
        frame.navigationFrozen = true
        frame.warmup?.cancel()
        frame.frozen = true
        // Descendant layers can move without recording the outer layer again. A warm bitmap
        // is not the final position; submit this copy before the outgoing content is disposed.
        val inherited = frame.inheritedImage?.takeIf {
            frame.generation == 0L && it.width == frame.size.width && it.height == frame.size.height
        }
        frame.image = inherited
        frame.ready = null
        frame.readyGeneration = -1L
        frame.readySize = IntSize.Zero
        frame.captures.toList().forEach { it.cancel() }
        if (inherited == null) capture(frame, captureBackPerformanceToken(), navigation = true)
    }

    private fun capture(frame: Frame, sourceToken: BackPerformanceRecorder.Token?, navigation: Boolean = false) {
        if (closed || !active || frame.releaseRequested || frame.size == IntSize.Zero || frame.layer.isReleased ||
            frame.captures.any { !it.isCompleted && (!navigation || !it.isCancelled) }) return
        // Submit the final read while the source is alive; cancelled warm reads drain separately.
        if (navigation) { if (!frame.frozen || frame.generation == 0L) return }
        else if (frame.frozen || frame.hasFreshImage) return
        val epoch = previewEpoch
        val captureEpoch = frame.captureEpoch
        val generation = frame.generation
        val size = frame.size
        JankDiagnostics.section("daiyu.back-preview=freeze-start") {
            val capture = synchronousTrace("daiyu.back.capture-launch", sourceToken) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val wall = BackPerformance.beginCapture(sourceToken)
                    var result = BackPerformanceRecorder.CaptureResult.FAILED
                    val traceCookie = System.identityHashCode(frame)
                    JankDiagnostics.beginPhase("daiyu.back-preview=freeze", traceCookie)
                    try {
                        // API26 must finish its submitted Surface copy before releasing the source layer.
                        val image = withContext(NonCancellable) { snapshot(frame) }
                        result = if (isActive) BackPerformanceRecorder.CaptureResult.DISCARDED
                            else BackPerformanceRecorder.CaptureResult.CANCELLED
                        val coveredFinalRead = navigation && frame.key != currentKey && frame.navigationFrozen
                        if (isActive && (active || coveredFinalRead) && !closed && !frame.releaseRequested &&
                            previewEpoch == epoch && frame.captureEpoch == captureEpoch &&
                            (if (navigation) frame.frozen && frame.generation == generation else !frame.frozen) &&
                            frames[frame.key] === frame && frame.size == size && image.width == size.width && image.height == size.height) {
                            frame.readyGeneration = generation
                            frame.readySize = size
                            frame.ready = image
                            if (navigation) frame.image = image
                            result = BackPerformanceRecorder.CaptureResult.STORED
                        }
                    } catch (cancelled: CancellationException) {
                        result = BackPerformanceRecorder.CaptureResult.CANCELLED
                        throw cancelled
                    } catch (failure: Exception) {
                        result = BackPerformanceRecorder.CaptureResult.FAILED
                        android.util.Log.w("DaiyuBackPreview", "freeze failed type=${failure.javaClass.simpleName}")
                    } finally {
                        JankDiagnostics.endPhase("daiyu.back-preview=freeze", traceCookie)
                        BackPerformance.completeCapture(wall, result)
                    }
                }
            }
            frame.capture = capture
            frame.captures.add(capture)
            capture.invokeOnCompletion {
                frame.captures.remove(capture)
                if (frame.releaseRequested && frame.captures.isEmpty()) context.releaseGraphicsLayer(frame.layer)
                else if (!navigation && active && !closed && currentKey == frame.key &&
                    frame.navigationFrozen && frame.frozen && frame.image == null) {
                    // Only an on-screen gesture may follow a cancelled warm copy with a fresh one.
                    // Never enqueue another read after navigation has disposed the source content.
                    capture(frame, sourceToken, navigation = true)
                } else if (previewEpoch != epoch || frame.captureEpoch != captureEpoch || frame.generation != generation) schedule(frame, sourceToken)
            }
        }
    }

    fun frame(key: Any, sourceKey: Any = key, contentKey: Any = key): Frame {
        return synchronousTrace("daiyu.back.layers.frame") {
            val previous = frames.remove(key)
            Frame(key, context.createGraphicsLayer(), sourceKey, contentKey, ::schedule).also { frame ->
                if (previous != null && !previous.releaseRequested && previous.sourceKey == sourceKey && previous.contentKey == contentKey) {
                    (previous.image ?: previous.ready?.takeIf { previous.readySize == previous.size })?.takeIf {
                        it.width == previous.size.width && it.height == previous.size.height }?.let { image ->
                        // The returning visit may still be covered by its child's departure.
                        // Keep its prepared pixels, not the old layer or its recording generation.
                        frame.size = previous.size
                        frame.readySize = previous.size
                        frame.ready = image
                        frame.inheritedImage = previous.image?.takeIf { it === image }
                    }
                }
                previous?.let { release(it) }
                frames[key] = frame
            }
        }
    }

    private fun release(frame: Frame) {
        if (frame.releaseRequested) return
        frame.releaseRequested = true
        frame.warmup?.cancel()
        frame.image = null
        frame.ready = null
        frame.inheritedImage = null
        if (frame.captures.isEmpty()) context.releaseGraphicsLayer(frame.layer)
        else frame.captures.toList().forEach { it.cancel() }
    }

    fun find(key: Any?, size: IntSize): Frame? = synchronousTrace("daiyu.back.layers.find") {
        frames[key]?.takeIf {
            size != IntSize.Zero && it.size == size && it.canDrawPreview
        }
    }

    fun prepare(frame: Frame, target: Any, progress: Float, predictive: Boolean, direction: Float, velocity: Float = 0f): Departure {
        return synchronousTrace("daiyu.back.prepare") {
            observeCaptures(captureBackPerformanceToken())
            freeze(frame)
            pending?.motion?.cancel()
            Departure(frame, target, progress, predictive, direction, velocity = velocity,
                targetImage = frames[target]?.image,
                motion = PageBackCompletionMotion(scope, progress, velocity)).also { pending = it }
        }
    }

    fun abandon(departure: Departure) {
        if (pending === departure) {
            BackPerformance.mark(BackPerformanceRecorder.Event.ABANDON_APPLIED, departure.motion?.diagnosticToken)
            departure.motion?.cancel()
            pending = null
            // The visible cancel settle still owns this image; resume unlocks it at rest.
        } else BackPerformance.mark(BackPerformanceRecorder.Event.ABANDON_NOT_PENDING, departure.motion?.diagnosticToken)
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
        pending?.motion?.cancel()
        pending = null
        frames.values.forEach {
            it.warmup?.cancel()
            it.captures.toList().forEach { capture -> capture.cancel() }
            it.image = null
            it.ready = null
            it.inheritedImage = null
            it.readyGeneration = -1L
            it.holdingForChild = false
            it.childChangeCaptured = null
            it.frozen = false
            it.navigationFrozen = false
        }
    }

    fun close() {
        closed = true
        active = false
        pending?.motion?.cancel()
        pending = null
        frames.values.forEach { release(it) }
        frames.clear()
    }
}
