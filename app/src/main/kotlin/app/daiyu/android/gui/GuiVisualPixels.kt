package app.daiyu.android.gui

import android.graphics.Bitmap
import java.security.MessageDigest

/** Exact native-resolution tiles; no bitmap or encoded screenshot is retained. */
internal class GuiVisualPixels private constructor(
    private val width: Int, private val height: Int,
    private val hashes: ByteArray, private val detail: BooleanArray,
) {
    private val columns = (width + TILE - 1) / TILE

    fun matchesTap(other: GuiVisualPixels?, gesture: GuiGesture, window: GuiVisualWindow): Boolean {
        if (other == null || width != other.width || height != other.height ||
            gesture.x != gesture.endX || gesture.y != gesture.endY ||
            window.displayBounds != GuiBounds(0, 0, width, height)) return false
        val radius = (minOf(width, height) / 12).coerceIn(64, 128)
        val left = maxOf(0, gesture.x - radius) / TILE
        val top = maxOf(0, gesture.y - radius) / TILE
        val right = (minOf(width, gesture.x + radius + 1) + TILE - 1) / TILE
        val bottom = (minOf(height, gesture.y + radius + 1) + TILE - 1) / TILE
        val region = GuiBounds(left * TILE, top * TILE, minOf(width, right * TILE), minOf(height, bottom * TILE))
        if (!window.admits(gesture) || region.right - region.left < radius ||
            region.bottom - region.top < radius) return false
        var hasDetail = false
        for (row in top until bottom) for (column in left until right) {
            val tile = row * columns + column
            hasDetail = hasDetail || detail[tile]
            val offset = tile * HASH
            for (byte in 0 until HASH) if (hashes[offset + byte] != other.hashes[offset + byte]) return false
        }
        // An unchanged blank patch is not a visible target witness.
        return hasDetail
    }

    companion object {
        private const val TILE = 32
        private const val HASH = 32

        fun from(bitmap: Bitmap): GuiVisualPixels {
            val width = bitmap.width; val height = bitmap.height
            require(width in 1..2048 && height in 1..4096 && width.toLong() * height <= 8_388_608)
            val columns = (width + TILE - 1) / TILE
            val rows = (height + TILE - 1) / TILE
            val hashes = ByteArray(columns * rows * HASH)
            val detail = BooleanArray(columns * rows)
            val pixels = IntArray(TILE * TILE)
            val bytes = ByteArray(16 + pixels.size * 4)
            val digest = MessageDigest.getInstance("SHA-256")
            fun putInt(offset: Int, value: Int) {
                for (part in 0..3) bytes[offset + part] = (value ushr (24 - part * 8)).toByte()
            }
            for (row in 0 until rows) for (column in 0 until columns) {
                val x = column * TILE; val y = row * TILE
                val w = minOf(TILE, width - x); val h = minOf(TILE, height - y)
                bitmap.getPixels(pixels, 0, w, x, y, w, h)
                putInt(0, x); putInt(4, y); putInt(8, w); putInt(12, h)
                val tile = row * columns + column
                for (index in 0 until w * h) {
                    putInt(16 + index * 4, pixels[index])
                    if (pixels[index] != pixels[0]) detail[tile] = true
                }
                digest.update(bytes, 0, 16 + w * h * 4)
                digest.digest().copyInto(hashes, tile * HASH)
            }
            return GuiVisualPixels(width, height, hashes, detail)
        }
    }
}
