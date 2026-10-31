package app.daiyu.android.ui.screens.chat.conversation

import kotlin.math.max

/** Pure pixel geometry. No Android Matrix dependency, so the exact renderer math is JVM-testable. */
internal object DoubaoGenieGeometry {
    const val STRIP_COUNT = 64
    const val DURATION_MILLIS = 740

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
