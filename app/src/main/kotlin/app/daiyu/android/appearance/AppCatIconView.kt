package app.daiyu.android.appearance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.View
import app.daiyu.android.R
import app.daiyu.android.domain.appearance.IconAppearance

/** View port of source AppCatIcon: original cat bitmap, 22% corner radius, Fit, independent tint. */
class AppCatIconView(context: Context) : View(context) {
    var backgroundColorValue: Int = IconAppearance.DEFAULT_ICON_LIGHT
        set(value) { field = value; invalidate() }
    var catColor: Int = IconAppearance.DEFAULT_ICON_CAT
        set(value) { field = value; invalidate() }
    private val cat = checkNotNull(context.getDrawable(R.drawable.task17_ic_cat_mark)).mutate()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val rect = RectF(left, top, left + side, top + side)
        val radius = side * 0.22f
        val saved = canvas.save()
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.color = backgroundColorValue
        canvas.drawRect(rect, paint)
        val scale = minOf(side / cat.intrinsicWidth.coerceAtLeast(1), side / cat.intrinsicHeight.coerceAtLeast(1))
        val w = cat.intrinsicWidth.coerceAtLeast(1) * scale
        val h = cat.intrinsicHeight.coerceAtLeast(1) * scale
        cat.setBounds((left + (side - w) / 2).toInt(), (top + (side - h) / 2).toInt(), (left + (side + w) / 2).toInt(), (top + (side + h) / 2).toInt())
        @Suppress("DEPRECATION")
        cat.setColorFilter(catColor, PorterDuff.Mode.SRC_IN)
        cat.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
