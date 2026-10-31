package app.daiyu.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ToolDisposition
import app.daiyu.android.domain.runtime.ToolRecord
import org.json.JSONObject

/** Exact native screen protocol only. Paths, URLs and external tool bodies are never assets. */
internal object ToolScreenshotReceipt {
    private const val MAX_BYTES = 8 * 1024 * 1024
    fun decode(record: ToolRecord): Bitmap? = runCatching { decodePng(record) }.getOrNull()

    private fun decodePng(record: ToolRecord): Bitmap? {
        if (record.disposition != ToolDisposition.SUCCEEDED || record.invocation.name != "screen") return null
        val args = JSONObject(record.invocation.arguments)
        if ((args.opt("operation") as? String) != "screenshot") return null
        val result = record.result?.takeIf { it.length <= MAX_BYTES * 4 / 3 + 4096 } ?: return null
        val data = JSONObject(result)
        val observation = data.opt("observation_id") as? String
        val capture = data.opt("capture_id") as? String
        if ((data.opt("mime_type") as? String) != "image/png") return null
        if (observation != null) {
            if (observation.isBlank() || data.has("capture_id")) return null
            if (args.has("observation_id") && observation != (args.opt("observation_id") as? String)) return null
        } else if (data.has("observation_id") || capture.isNullOrBlank() || args.has("observation_id")) return null
        val width = data.opt("width").let { if (it is Int || it is Long) (it as Number).toLong() else return null }
        val height = data.opt("height").let { if (it is Int || it is Long) (it as Number).toLong() else return null }
        val encoded = data.opt("data_base64") as? String ?: return null
        if (encoded.isEmpty() || encoded.length > MAX_BYTES * 4 / 3 + 4) return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size !in 8..MAX_BYTES || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
            bounds.outWidth.toLong() * bounds.outHeight > 32_000_000L ||
            width != bounds.outWidth.toLong() || height != bounds.outHeight.toLong()) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
