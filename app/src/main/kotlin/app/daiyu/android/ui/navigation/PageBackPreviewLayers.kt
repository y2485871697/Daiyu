package app.daiyu.android.ui.navigation

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize

/** Retains drawing commands, not another mounted page or an on-disk screenshot. */
internal class PageBackPreviewLayers(private val context: GraphicsContext) {
    internal class Frame(val key: Any, val layer: GraphicsLayer) {
        var size = IntSize.Zero
    }
    internal class Departure(val frame: Frame, val target: Any, val progress: Float,
        val predictive: Boolean, val direction: Float)

    private val frames = LinkedHashMap<Any, Frame>()
    private var pending: Departure? = null
    var currentKey: Any? = null
        private set

    fun enter(key: Any): Departure? {
        currentKey = key
        val departure = pending
        pending = null
        return departure?.takeIf { it.target == key && it.frame.key != key }
    }

    fun frame(key: Any): Frame {
        frames.remove(key)?.let { frames[key] = it; return it }
        return Frame(key, context.createGraphicsLayer()).also { frames[key] = it }
    }

    fun find(key: Any?, size: IntSize): Frame? = frames[key]?.takeIf {
        size != IntSize.Zero && it.size == size
    }

    fun prepare(frame: Frame, target: Any, progress: Float, predictive: Boolean, direction: Float): Departure {
        return Departure(frame, target, progress, predictive, direction).also { pending = it }
    }

    fun abandon(departure: Departure) {
        if (pending === departure) pending = null
    }

    fun trim(current: Frame, target: Frame?, departure: Departure?) {
        val iterator = frames.entries.iterator()
        while (frames.size > 8 && iterator.hasNext()) {
            val frame = iterator.next().value
            if (frame === current || frame === target || frame === departure?.frame) continue
            iterator.remove()
            context.releaseGraphicsLayer(frame.layer)
        }
    }

    fun close() {
        pending = null
        frames.values.forEach { context.releaseGraphicsLayer(it.layer) }
        frames.clear()
    }
}
