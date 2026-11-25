package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

/** One pixel-space animation owns both the visible extent and its content placement. */
internal class WorkflowCardExtent(private val initiallyExpanded: Boolean) {
    private val geometry = Animatable(Offset.Zero, Offset.VectorConverter)
    private var initialized = false
    private var finished by mutableStateOf<Boolean?>(if (initiallyExpanded) null else false)
    private var placedGeometry by mutableStateOf<Offset?>(if (initiallyExpanded) null else Offset.Zero)
    var fullHeight by mutableIntStateOf(0)
    private var measuredHeight = 0
    private var openingTarget = 0
    var contentOffset = 0
        private set

    fun keepsContent(expanded: Boolean): Boolean = expanded || finished != false || placedGeometry != Offset.Zero

    fun idle(expanded: Boolean): Boolean = finished == expanded && !geometry.isRunning &&
        placedGeometry == if (expanded) Offset(fullHeight.toFloat(), 0f) else Offset.Zero

    fun measure(expanded: Boolean, closingLimit: Int?): Int {
        val settledExpanded = finished == true && !geometry.isRunning
        val pixels = when {
            settledExpanded && expanded || !initialized && initiallyExpanded -> fullHeight
            settledExpanded -> placedGeometry?.x?.roundToInt() ?: measuredHeight
            else -> geometry.value.x.roundToInt().coerceAtLeast(0)
        }
        measuredHeight = if (expanded) pixels else minOf(pixels, closingLimit ?: pixels)
        contentOffset = if (settledExpanded || !initialized) 0 else geometry.value.y.roundToInt()
        return measuredHeight
    }

    fun placed(height: Int = measuredHeight) {
        placedGeometry = Offset(height.toFloat(), contentOffset.toFloat())
    }

    suspend fun animate(expanded: Boolean, closingLimit: Int? = null, upwards: Boolean = false) {
        if (expanded && fullHeight == 0 || !expanded && finished == false) return
        if (expanded && finished == true && !geometry.isRunning) {
            geometry.snapTo(Offset(fullHeight.toFloat(), 0f))
            return
        }
        // Content growing inside an already open card is not a disclosure. Restarting the opening spring on every
        // new line kept the card a line short, so its bottom border hugged and then jumped past the newest text.
        if (expanded && (!initialized && initiallyExpanded ||
                finished != false && openingTarget > 0 && geometry.value.x >= openingTarget - 1f)) {
            geometry.snapTo(Offset(fullHeight.toFloat(), 0f))
            initialized = true
            openingTarget = fullHeight
            finished = true
            return
        }
        if (!initialized) {
            geometry.snapTo(Offset(measuredHeight.toFloat(), contentOffset.toFloat()))
            initialized = true
        }
        if (expanded && finished == false && geometry.value.x == 0f) {
            geometry.snapTo(Offset(0f, if (upwards) -fullHeight.toFloat() else 0f))
        }
        finished = null
        val spec = spring<Offset>(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Offset(1f, 1f))
        if (expanded) {
            openingTarget = fullHeight
            geometry.animateTo(Offset(fullHeight.toFloat(), 0f), spec)
        } else {
            // The effect can run before the closing layout: apply its frozen limit here too.
            val start = minOf(measuredHeight, closingLimit ?: measuredHeight).coerceAtLeast(0)
            val velocity = geometry.velocity
            geometry.snapTo(Offset(start.toFloat(), contentOffset.toFloat()))
            geometry.animateTo(Offset.Zero, spec, initialVelocity = Offset(velocity.x.coerceAtMost(0f), velocity.y))
        }
        finished = expanded
    }
}
