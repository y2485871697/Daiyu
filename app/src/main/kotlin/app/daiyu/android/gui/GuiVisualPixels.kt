package app.daiyu.android.gui

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import android.graphics.Bitmap
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Code
import java.security.MessageDigest

/** Exact native-resolution tiles; no bitmap or encoded screenshot is retained. */
internal class GuiVisualPixels private constructor(
    private val width: Int, private val height: Int,
    private val hashes: ByteArray, private val detail: BooleanArray,
) {
    private val columns = (width + TILE - 1) / TILE

    internal data class Check(val reason: RuntimeDiagnostics.Code,
        val beforeWidth: Int, val beforeHeight: Int, val afterWidth: Int, val afterHeight: Int,
        val comparedTiles: Int? = null, val changedTiles: Int? = null,
        val region: GuiBounds? = null, val changedTileBounds: List<GuiBounds> = emptyList(),
        val targetTileChanged: Boolean? = null) {
        // Pixel changes are diagnostic evidence, not proof that an authorized target moved.
        val accepted get() = reason in setOf(Code.LOCAL_EQUAL, Code.LOCAL_PIXELS_CHANGED, Code.BLANK_TARGET)
    }

    fun matchesTap(other: GuiVisualPixels?, gesture: GuiGesture, window: GuiVisualWindow): Boolean =
        compareTap(other, gesture, window).accepted

    fun compareTap(other: GuiVisualPixels?, gesture: GuiGesture, window: GuiVisualWindow): Check {
        fun result(reason: RuntimeDiagnostics.Code, compared: Int? = null, changed: Int? = null,
            region: GuiBounds? = null, changes: List<GuiBounds> = emptyList(), targetChanged: Boolean? = null) =
            Check(reason, width, height, other?.width ?: -1, other?.height ?: -1, compared, changed, region, changes, targetChanged)
        if (other == null) return result(Code.PIXELS_MISSING)
        if (width != other.width || height != other.height) return result(Code.DIMENSIONS_CHANGED)
        if (gesture.x != gesture.endX || gesture.y != gesture.endY) return result(Code.NOT_A_TAP)
        if (window.displayBounds != GuiBounds(0, 0, width, height)) return result(Code.DISPLAY_CHANGED)
        val radius = (minOf(width, height) / 12).coerceIn(64, 128)
        val left = maxOf(0, gesture.x - radius) / TILE
        val top = maxOf(0, gesture.y - radius) / TILE
        val right = (minOf(width, gesture.x + radius + 1) + TILE - 1) / TILE
        val bottom = (minOf(height, gesture.y + radius + 1) + TILE - 1) / TILE
        val region = GuiBounds(left * TILE, top * TILE, minOf(width, right * TILE), minOf(height, bottom * TILE))
        if (!window.admits(gesture)) return result(Code.TARGET_OUTSIDE)
        if (region.right - region.left < radius || region.bottom - region.top < radius) return result(Code.REGION_TOO_SMALL)
        var hasDetail = false
        val changes = ArrayList<GuiBounds>()
        var compared = 0
        for (row in top until bottom) for (column in left until right) {
            val tile = row * columns + column
            hasDetail = hasDetail || detail[tile]
            val offset = tile * HASH
            compared++
            if ((0 until HASH).any { hashes[offset + it] != other.hashes[offset + it] })
                changes.add(GuiBounds(column * TILE, row * TILE, minOf(width, (column + 1) * TILE), minOf(height, (row + 1) * TILE)))
        }
        val targetChanged = changes.any { gesture.x in it.left until it.right && gesture.y in it.top until it.bottom }
        return result(when {
            changes.isNotEmpty() -> Code.LOCAL_PIXELS_CHANGED
            !hasDetail -> Code.BLANK_TARGET
            else -> Code.LOCAL_EQUAL
        }, compared, changes.size, region, changes.toList(), targetChanged)
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
