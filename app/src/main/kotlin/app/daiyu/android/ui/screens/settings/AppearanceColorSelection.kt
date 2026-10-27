package app.daiyu.android.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.daiyu.android.ui.app.colorHsv

/** Keep HSV coordinates while the parent echoes the quantized saved-color format. */
internal data class AppearanceColorSelection(val hue: Float, val saturation: Float, val value: Float) {
    val color: Color get() = Color.hsv(hue, saturation, value)

    fun selectHue(next: Float) = copy(hue = next.coerceIn(0f, 360f))

    fun selectSaturationValue(saturation: Float, value: Float) = copy(
        saturation = saturation.coerceIn(0f, 1f), value = value.coerceIn(0f, 1f))

    fun synchronize(input: Color): AppearanceColorSelection =
        if (input.toArgb() == color.toArgb()) this else fromColor(input)

    companion object {
        fun fromColor(color: Color): AppearanceColorSelection {
            val hsv = colorHsv(color)
            return AppearanceColorSelection(hsv[0], hsv[1], hsv[2])
        }
    }
}
