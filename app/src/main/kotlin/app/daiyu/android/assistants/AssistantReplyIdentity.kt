package app.daiyu.android.assistants

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Frozen display identity only; never contains prompts, memory or credentials. */
data class AssistantReplyIdentity(val assistantId: String, val name: String, val avatarPng: String? = null) {
    override fun toString(): String = "AssistantReplyIdentity(assistantId=$assistantId, name=$name, avatar=<snapshot>)"

    fun avatarBitmap(): Bitmap? = avatarPng?.let {
        val bytes = Base64.decode(it, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    companion object {
        /** Called on the sending worker, using the same context passed to the model factory. */
        fun capture(context: AssistantRunContext, assets: AssistantAssets?): AssistantReplyIdentity {
            val original = runCatching { assets?.avatarBitmap(context.avatarFileName) }.getOrNull()
            val avatar = original?.let { bitmap ->
                val longest = maxOf(bitmap.width, bitmap.height)
                val scale = 512f / longest.coerceAtLeast(1)
                val scaled = if (longest <= 512) bitmap else Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val bytes = ByteArrayOutputStream().also { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } finally { if (scaled !== bitmap) scaled.recycle() }
            }
            return AssistantReplyIdentity(context.assistantId, context.name, avatar)
        }
    }
}
