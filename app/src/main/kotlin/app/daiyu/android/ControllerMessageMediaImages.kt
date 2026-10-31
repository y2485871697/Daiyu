package app.daiyu.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import app.daiyu.android.media.AndroidMessageImagePreviews
import app.daiyu.android.media.MessageImageOwner
import app.daiyu.android.media.MessageImageReference
import app.daiyu.android.media.MessageImageResolver

/** Viewer-only read decoder. The existing <=2048 thumbnail API and its callers stay unchanged. */
internal fun decodeMessageMediaDisplay(resolver: MessageImageResolver, owner: MessageImageOwner,
    reference: MessageImageReference, edge: Int): Bitmap {
    require(edge in 1..4096)
    if (edge <= 2048) return AndroidMessageImagePreviews(resolver).decodePreview(owner, reference, edge)
    val bytes = resolver.read(owner, reference)
    val orientation = if (reference.mime == "image/jpeg") bytes.inputStream().use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } else ExifInterface.ORIENTATION_NORMAL
    var sample = 1
    while ((maxOf(reference.width, reference.height).toLong() + sample - 1) / sample > edge) sample *= 2
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })) { "media_display_decode_failed" }
    try {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}
