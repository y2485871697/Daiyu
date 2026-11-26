package app.daiyu.android.domain.context

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Local-only price of visible input. Provider occupancy is never inferred from this value. */
object DaiyuContextEstimate {
    const val MESSAGE_OVERHEAD = 3L

    fun text(value: String): Long {
        if (value.isEmpty()) return 0
        var cjk = 0L
        var other = 0L
        var offset = 0
        while (offset < value.length) {
            val code = value.codePointAt(offset)
            offset += Character.charCount(code)
            when (code) {
                in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF,
                in 0x3040..0x30FF, in 0xAC00..0xD7AF, in 0x3000..0x303F,
                in 0xFF00..0xFFEF, in 0x20000..0x2A6DF -> cjk++
                else -> other++
            }
        }
        return (cjk * 1.5 + other * 0.25).roundToLong().coerceAtLeast(1)
    }

    fun image(width: Int, height: Int): Long {
        val edge = maxOf(width, height)
        val scale = if (edge > 2048) 2048f / edge else 1f
        return maxOf(85L, (ceil(width * scale / 32) * ceil(height * scale / 32)).toLong())
    }

    fun summaryOutputLimit(window: Long): Int =
        minOf(16_384L, maxOf(8192L, window / 8), maxOf(1024L, window / 4)).toInt()

    fun summaryInputLimit(window: Long, output: Int): Long {
        val planningWindow = minOf(window, 128_000L)
        return (planningWindow - output - maxOf(512L, planningWindow / 20)).coerceAtLeast(0)
    }
}
