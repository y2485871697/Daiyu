package app.daiyu.android.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.File
import java.io.IOException

class AndroidImageInspector : ImageInspector {
    override fun inspect(bytes: ByteArray): ImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null) {
            throw ImageAttachmentException(ImageAttachmentFailure.INVALID_IMAGE)
        }
        if (options.outMimeType !in ImageAttachmentStore.SUPPORTED_MIMES) {
            throw ImageAttachmentException(ImageAttachmentFailure.UNSUPPORTED_IMAGE)
        }
        if (options.outWidth.toLong() * options.outHeight > 40_000_000) {
            throw ImageAttachmentException(ImageAttachmentFailure.TOO_LARGE)
        }
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight).toLong() / sample > 128) sample *= 2
        val probe = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ImageAttachmentException(ImageAttachmentFailure.INVALID_IMAGE)
        probe.recycle()
        return ImageDimensions(options.outMimeType, options.outWidth, options.outHeight)
    }
}

/** App-owned files survive temporary provider grants; source URIs never enter the saved reference. */
class AndroidImageAttachments(
    private val resolver: ContentResolver,
    val store: ImageAttachmentStore,
) {
    constructor(context: Context) : this(context.applicationContext.contentResolver,
        ImageAttachmentStore(File(context.applicationContext.filesDir, "media-draft-images"), AndroidImageInspector()))

    /** One cancellation signal per import; its listener belongs to this operation. Run on a worker. */
    fun import(
        owner: ImageAttachmentOwner,
        uri: Uri,
        cancellation: CancellationSignal = CancellationSignal(),
    ): ImageAttachment {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw ImageAttachmentException(ImageAttachmentFailure.SOURCE_UNAVAILABLE)
        }
        try {
            cancellation.throwIfCanceled()
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation)
                ?: throw ImageAttachmentException(ImageAttachmentFailure.SOURCE_UNAVAILABLE)
            val stream = try { descriptor.createInputStream() } catch (failure: Exception) {
                try { descriptor.close() } catch (closeFailure: IOException) { failure.addSuppressed(closeFailure) }
                throw failure
            }
            cancellation.setOnCancelListener {
                try { stream.close() } catch (_: IOException) { /* Read/close reports the terminal outcome. */ }
            }
            try {
                return store.import(owner, stream) { cancellation.isCanceled || Thread.currentThread().isInterrupted }
            } finally { cancellation.setOnCancelListener(null) }
        } catch (failure: OperationCanceledException) {
            throw ImageAttachmentException(ImageAttachmentFailure.CANCELLED, failure)
        } catch (failure: SecurityException) {
            throw ImageAttachmentException(ImageAttachmentFailure.SOURCE_UNAVAILABLE, failure)
        } catch (failure: IOException) {
            throw ImageAttachmentException(ImageAttachmentFailure.SOURCE_UNAVAILABLE, failure)
        }
    }

    /** A caller owns/recycles this bitmap; original persisted bytes are never recompressed. */
    fun decodePreview(owner: ImageAttachmentOwner, attachment: ImageAttachment, maxEdge: Int = 1024): Bitmap =
        decodeImagePreview(attachment, store.read(owner, attachment), maxEdge)
}

/** History has only the read capability; preview never looks up a draft or a provider URI. */
class AndroidMessageImagePreviews(private val resolver: MessageImageResolver) {
    fun decodePreview(owner: MessageImageOwner, reference: MessageImageReference, maxEdge: Int = 1024): Bitmap =
        decodeImagePreview(reference.asAttachment(), resolver.read(owner, reference), maxEdge)
}

private fun decodeImagePreview(attachment: ImageAttachment, bytes: ByteArray, maxEdge: Int): Bitmap {
    require(maxEdge in 1..2048)
    val orientation = if (attachment.mime == "image/jpeg") {
        try {
            bytes.inputStream().use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (failure: IOException) {
            throw ImageAttachmentException(ImageAttachmentFailure.INVALID_IMAGE, failure)
        }
    } else ExifInterface.ORIENTATION_NORMAL
    var sample = 1
    while ((maxOf(attachment.width, attachment.height).toLong() + sample - 1) / sample > maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw ImageAttachmentException(ImageAttachmentFailure.INVALID_IMAGE)
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
}
