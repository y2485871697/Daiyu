package app.daiyu.android.appearance

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import app.daiyu.android.R
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.Task17IconCatalog

object Task17IconResources {
    fun drawableId(context: Context, entry: Task17IconCatalog.Entry): Int =
        context.resources.getIdentifier(entry.resourceName, "drawable", context.packageName)

    /** Original notification behavior: a missing/unreadable large icon falls back to no large icon. */
    fun largeIcon(context: Context, entry: Task17IconCatalog.Entry?, onFailure: (Exception) -> Unit): Bitmap? {
        if (entry == null) return null
        return try {
            val id = drawableId(context, entry)
            check(id != 0) { "Missing ${entry.resourceName}" }
            val drawable = checkNotNull(context.getDrawable(id))
            if (drawable is BitmapDrawable) drawable.bitmap else {
                val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                bitmap
            }
        } catch (error: Exception) { onFailure(error); null }
    }

    /** Use on both new and rebuilt active notifications. Host owns channels, IDs and permission. */
    fun decorate(context: Context, builder: Notification.Builder, entry: Task17IconCatalog.Entry?, onFailure: (Exception) -> Unit): Notification.Builder =
        builder.setSmallIcon(R.drawable.task17_ic_stat_cat).setLargeIcon(largeIcon(context, entry, onFailure))
}

/** Inline main-thread refresh; workers fail fast without posting or waiting on the main thread. */
class MainThreadIconNotifications(
    private val refreshActive: (Task17IconCatalog.Entry) -> Unit,
) : IconNotificationPort {
    override fun refresh(entry: Task17IconCatalog.Entry) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Icon notifications must refresh on the main thread" }
        refreshActive(entry)
    }
}
