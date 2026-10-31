package app.daiyu.android.ui.screens.chat.conversation

import kotlin.math.max

/** Pure pixel geometry. No Android Matrix dependency, so the exact renderer math is JVM-testable. */
internal object DoubaoGenieGeometry {
    const val STRIP_COUNT = 64
    const val DURATION_MILLIS = 420

    // Exact preview symbols (viewBox 0 0 24 24), parsed by the native renderer.
    const val COLLAPSE_PATH = "m7 10 5 5 5-5M7 5h10"
    const val EXPAND_PATH = "M14 4h6v6m-6 0 6-6M10 20H4v-6m0 6 6-6"

    const val ORB_DIAMETER = 66f
    const val HALO_INSET = 7f
    const val MINI_DIAMETER = 37f
    const val MINI_WAVE_SCALE = .65f
    enum class OrbMode { CONNECTING, LISTENING, RESPONDING, MUTED, RECONNECTING, ENDED, ERROR }
    data class OrbStyle(val background: Long, val bars: Long, val halo: Long, val icon: Long,
                        val glow: Boolean, val showWave: Boolean, val spinner: Boolean, val still: Boolean)

    fun orbStyle(mode: OrbMode, compact: Boolean): OrbStyle {
        val muted = mode == OrbMode.MUTED
        val ended = mode == OrbMode.ENDED || mode == OrbMode.ERROR
        val connecting = mode == OrbMode.CONNECTING || mode == OrbMode.RECONNECTING
        return OrbStyle(
            background = when {
                compact -> 0xFFEEE5F9
                muted -> 0xFFEEE9F2
                ended -> 0xFFEFEAF4
                mode == OrbMode.RESPONDING -> 0xFFE9DDF9
                mode == OrbMode.RECONNECTING -> 0xFFEFE8F5
                else -> 0xFFEEE6FB
            },
            bars = if (compact) 0xFFA58CC5 else if (mode == OrbMode.RESPONDING) 0xFF9471BD else 0xFFA18AC7,
            halo = if (muted) 0xFFF5F1F8 else if (ended) 0xFFF6F2F9 else 0xFFF6F2FC,
            icon = if (compact) 0xFF9F87BA else if (muted) 0xFFB1A4BD else 0xFFAD9CBA,
            glow = !compact && !muted && !ended,
            showWave = !muted && !ended && (compact || !connecting),
            spinner = !compact && connecting,
            still = muted || ended,
        )
    }

    private val barHeights = floatArrayOf(12f, 21f, 29f, 20f, 11f)
    private val barOffsetsMillis = floatArrayOf(0f, 500f, 900f, 300f, 700f)

    // CSS ease-in-out = cubic-bezier(.42,0,.58,1), not a sine approximation.
    private fun cssEase(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        repeat(16) {
            val t = (low + high) / 2f
            val q = 1f - t
            val at = 3f * q * q * t * .42f + 3f * q * t * t * .58f + t * t * t
            if (at < x) low = t else high = t
        }
        val t = (low + high) / 2f
        return t * t * (3f - 2f * t)
    }

    private fun pulse(elapsedMillis: Float, periodMillis: Float): Float {
        val phase = ((elapsedMillis % periodMillis) + periodMillis) % periodMillis / periodMillis
        return cssEase(if (phase <= .5f) phase * 2f else (1f - phase) * 2f)
    }

    data class OrbFrame(
        val barHeights: List<Float>,
        val haloScale: Float,
        val haloAlpha: Float,
        val spinnerDegrees: Float,
    )

    fun orbFrame(elapsedMillis: Float, responding: Boolean = false, compact: Boolean = false,
                 connecting: Boolean = false, still: Boolean = false): OrbFrame {
        val period = if (compact && connecting) 2300f else if (responding) 750f else 1450f
        val bars = barHeights.mapIndexed { i, height ->
            val offset = if (compact && connecting) 0f else barOffsetsMillis[i]
            height * (.55f + .45f * pulse(elapsedMillis + offset, period)) *
                (if (compact) MINI_WAVE_SCALE else 1f)
        }
        val breath = if (still) 0f else pulse(elapsedMillis, 3000f)
        return OrbFrame(bars, 1f + .055f * breath, if (still) .6f else .45f + .35f * breath,
            ((elapsedMillis % 1500f) / 1500f) * 360f)
    }

    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)
    data class Point(val x: Float, val y: Float)
    data class Edge(val left: Float, val y: Float, val width: Float)

    data class Strip(
        val sourceY: Float,
        val sourceHeight: Float,
        val top: Edge,
        val bottom: Edge,
        /** Compose's column-major 4x4 matrix, operating on strip-local coordinates. */
        val matrix: FloatArray,
    ) {
        fun map(x: Float, y: Float): Point {
            val denominator = matrix[3] * x + matrix[7] * y + matrix[15]
            return Point(
                (matrix[0] * x + matrix[4] * y + matrix[12]) / denominator,
                (matrix[1] * x + matrix[5] * y + matrix[13]) / denominator,
            )
        }
    }

    fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** p is position, not elapsed time: reversing a motion visits precisely the same surface. */
    fun edge(u: Float, p: Float, anchor: Bounds, panel: Bounds): Edge {
        val v = u.coerceIn(0f, 1f)
        val progress = p.coerceIn(0f, 1f)
        val unfold = smooth((progress - .5f * smooth(v)) / .5f)
        val head = smooth(progress / .6f)
        val foot = smooth((progress - .48f) / .52f)
        val travel = head + (foot - head) * smooth(v)
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        return Edge(
            left = mix(anchor.left, panel.left, unfold),
            y = mix(anchor.top + v * anchor.height, panel.top + v * panel.height, travel),
            width = max(.001f, mix(anchor.width, panel.width, unfold)),
        )
    }

    fun strips(
        p: Float,
        anchor: Bounds,
        panel: Bounds,
        sourceWidth: Float = panel.width,
        sourceHeight: Float = panel.height,
    ): List<Strip> {
        require(sourceWidth > 0f && sourceHeight > 0f)
        // Compute the 65 shared edges once, rather than independently rounding adjacent strips.
        val edges = (0..STRIP_COUNT).map { edge(it.toFloat() / STRIP_COUNT, p, anchor, panel) }
        return (0 until STRIP_COUNT).map { index ->
            val sourceY = sourceHeight * index / STRIP_COUNT
            val nextY = sourceHeight * (index + 1) / STRIP_COUNT
            val h = nextY - sourceY
            val top = edges[index]
            val bottom = edges[index + 1]
            val r = top.width / bottom.width
            val a = top.width / sourceWidth
            val c = (bottom.left * r - top.left) / h
            val d = (bottom.y * r - top.y) / h
            val g = (r - 1f) / h
            Strip(sourceY, h, top, bottom, floatArrayOf(
                a, 0f, 0f, 0f,
                c, d, 0f, g,
                0f, 0f, 1f, 0f,
                top.left, top.y, 0f, 1f,
            ))
        }
    }
}
