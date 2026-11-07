package app.daiyu.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.daiyu.android.domain.runtime.ToolDisposition
import app.daiyu.android.domain.runtime.ToolRecord

/** Exact native screen protocol only. Paths, URLs and external tool bodies are never assets. */
internal object ToolScreenshotReceipt {
    fun decode(record: ToolRecord, readAsset: ((String) -> ByteArray?)? = null): Bitmap? = runCatching { decodePng(record, readAsset) }.getOrNull()

    private fun decodePng(record: ToolRecord, readAsset: ((String) -> ByteArray?)?): Bitmap? {
        if (record.disposition != ToolDisposition.SUCCEEDED) return null
        val image = app.daiyu.android.media.NativeScreenImage.parse(record.invocation.name,
            record.invocation.arguments, record.result ?: return null, readAsset) ?: return null
        val bytes = image.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth != image.width || bounds.outHeight != image.height) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
