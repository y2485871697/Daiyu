package app.daiyu.android.media.video

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Worker-thread API. Only content:// input is accepted; MIME hints/extensions are never trusted. */
class AndroidVideoAttachments(
    private val resolver: ContentResolver,
    val store: VideoAssetStore,
) {
    constructor(context: Context) : this(context.applicationContext.contentResolver,
        VideoAssetStore(File(context.applicationContext.filesDir, "media-video-assets")))

    /** A signal is owned by this operation, not shared with other concurrent operations. */
    fun import(
        owner: VideoAttachmentOwner,
        uri: Uri,
        cancellation: CancellationSignal = CancellationSignal(),
    ): VideoAttachment {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) videoFail(VideoAttachmentFailure.SOURCE_UNAVAILABLE)
        try {
            cancellation.throwIfCanceled()
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation)
                ?: videoFail(VideoAttachmentFailure.SOURCE_UNAVAILABLE)
            val stream = try { descriptor.createInputStream() } catch (failure: Exception) {
                try { descriptor.close() } catch (close: IOException) { failure.addSuppressed(close) }
                throw failure
            }
            try {
                cancellation.setOnCancelListener { try { stream.close() } catch (_: IOException) { } }
                return store.import(owner, stream) { cancellation.isCanceled || Thread.currentThread().isInterrupted }
            } finally {
                cancellation.setOnCancelListener(null)
                // Store owns/closes the stream before publication. This fallback must not turn a
                // successfully published import into a false failure by closing it a second time.
                try { stream.close() } catch (_: IOException) { }
            }
        } catch (failure: OperationCanceledException) {
            throw VideoAttachmentException(VideoAttachmentFailure.CANCELLED, failure)
        } catch (failure: SecurityException) {
            throw VideoAttachmentException(VideoAttachmentFailure.SOURCE_UNAVAILABLE, failure)
        } catch (failure: IOException) {
            throw VideoAttachmentException(if (cancellation.isCanceled) VideoAttachmentFailure.CANCELLED
                else VideoAttachmentFailure.SOURCE_UNAVAILABLE, failure)
        }
    }

    fun prepare(
        messageOwner: MessageVideoOwner,
        attachment: VideoAttachment,
        cancellation: CancellationSignal = CancellationSignal(),
    ): MessageVideoReference = store.prepare(messageOwner, attachment) {
        cancellation.isCanceled || Thread.currentThread().isInterrupted
    }

    /** Separate, bounded JPEG metadata. No placeholder image can be mistaken for an original. */
    fun preview(
        owner: VideoOwner,
        reference: VideoReference,
        maxEdge: Int = 512,
        cancellation: CancellationSignal = CancellationSignal(),
    ): VideoPreview {
        require(maxEdge in 1..1024)
        val cancelled = { cancellation.isCanceled || Thread.currentThread().isInterrupted }
        checkVideoCancellation(cancelled)
        store.openOriginal(owner, reference, cancelled).use { original ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(original.fd, 0L, reference.bytes)
                checkVideoCancellation(cancelled)
                val frame = scaledFrame(retriever, reference.width, reference.height, maxEdge)
                    ?: videoFail(VideoAttachmentFailure.PREVIEW_UNAVAILABLE)
                try {
                    checkVideoCancellation(cancelled)
                    val result = ByteArrayOutputStream()
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, 80, result) || result.size() > 2 * 1024 * 1024) {
                        videoFail(VideoAttachmentFailure.PREVIEW_UNAVAILABLE)
                    }
                    checkVideoCancellation(cancelled)
                    return VideoPreview(reference, frame.width, frame.height, result.toByteArray())
                } finally { frame.recycle() }
            } catch (failure: Exception) {
                checkVideoCancellation(cancelled)
                if (failure is VideoAttachmentException) throw failure
                throw VideoAttachmentException(VideoAttachmentFailure.PREVIEW_UNAVAILABLE, failure)
            } finally { retriever.release() }
        }
    }
}

/** Production inspection requires a recognized container, actual video track/sample and a decoded
 * frame. A name, provider MIME, ftyp/EBML signature, audio-only file or placeholder is insufficient.
 * Decoder work is bounded by source bytes, dimensions, duration and scaled output. Native parsing
 * itself is synchronous and cannot be interrupted mid-call; cancellation is checked around it.
 */
internal class AndroidVideoInspector : VideoInspector {
    override fun inspect(file: File, limits: VideoAttachmentLimits, isCancelled: () -> Boolean): VideoMetadata {
        checkVideoCancellation(isCancelled)
        if (file.length() !in 1..limits.maxVideoBytes) videoFail(VideoAttachmentFailure.TOO_LARGE)
        val mime = sniffVideoContainer(file) ?: videoFail(VideoAttachmentFailure.UNSUPPORTED_VIDEO)
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount !in 1..64) videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                checkVideoCancellation(isCancelled)
                val track = extractor.getTrackFormat(index)
                if (track.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    // Ambiguous multi-video-track assets are deliberately unsupported.
                    if (videoTrack >= 0) videoFail(VideoAttachmentFailure.UNSUPPORTED_VIDEO)
                    videoTrack = index
                }
            }
            if (videoTrack < 0) videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            val format = extractor.getTrackFormat(videoTrack)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0) videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            val actual = VideoMetadata(mime, durationUs / 1000,
                format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
            validateVideo(actual, limits)
            extractor.selectTrack(videoTrack)
            // This is not used as MIME evidence; it rejects empty/truncated track tables.
            val probe = ByteBuffer.allocateDirect(minOf(file.length(), 8L * 1024 * 1024).toInt())
            if (extractor.readSampleData(probe, 0) <= 0 || extractor.sampleTime < 0) {
                videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            }
            checkVideoCancellation(isCancelled)
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            val metadata = VideoMetadata(mime, duration, width, height)
            validateVideo(metadata, limits)
            if (width != actual.width || height != actual.height || kotlin.math.abs(duration - actual.durationMs) > 1000) {
                videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            }
            val frame = scaledFrame(retriever, width, height, 128)
                ?: videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            frame.recycle()
            checkVideoCancellation(isCancelled)
            return metadata
        } catch (failure: Exception) {
            checkVideoCancellation(isCancelled)
            if (failure is VideoAttachmentException) throw failure
            throw VideoAttachmentException(VideoAttachmentFailure.INVALID_VIDEO, failure)
        } finally {
            try { extractor.release() } finally { retriever.release() }
        }
    }
}

/** API 26 has no scaled decode. Bound that fallback to Full HD before allocating a frame. */
private fun scaledFrame(retriever: MediaMetadataRetriever, width: Int, height: Int, maxEdge: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 27) {
        return retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge)
    }
    if (width.toLong() * height > 1920L * 1080) videoFail(VideoAttachmentFailure.TOO_LARGE)
    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
    if (frame.width.toLong() * frame.height > 1920L * 1080) {
        frame.recycle()
        videoFail(VideoAttachmentFailure.TOO_LARGE)
    }
    val edge = maxOf(frame.width, frame.height)
    if (edge <= maxEdge) return frame
    return try {
        Bitmap.createScaledBitmap(frame, maxOf(1, frame.width * maxEdge / edge),
            maxOf(1, frame.height * maxEdge / edge), true)
    } finally { frame.recycle() }
}

/** Conservative container allowlist. Unknown brands/DocTypes fail closed rather than guessing MP4. */
internal fun sniffVideoContainer(file: File): String? {
    val header = file.inputStream().use { input ->
        val buffer = ByteArray(minOf(file.length(), 4096L).toInt())
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            if (read == 0) return@use buffer.copyOf(offset)
            offset += read
        }
        buffer.copyOf(offset)
    }
    if (header.size >= 16 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") {
        val size = (0..3).fold(0L) { value, index -> (value shl 8) or (header[index].toLong() and 255) }
        if (size < 16 || size > file.length() || size % 4 != 0L) return null
        val brand = String(header, 8, 4, Charsets.US_ASCII)
        return when {
            brand == "qt  " -> "video/quicktime"
            brand.startsWith("3gp") -> "video/3gpp"
            brand.startsWith("3g2") -> "video/3gpp2"
            brand in setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "M4V ") -> "video/mp4"
            else -> null
        }
    }
    if (header.size < 5 || !header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))) return null
    // EBML variable-length sizes, and raw element IDs (only the EBML header is traversed).
    fun vint(offset: Int, id: Boolean): Pair<Long, Int>? {
        if (offset >= header.size) return null
        val first = header[offset].toInt() and 255
        var mask = 0x80
        var length = 1
        while (mask != 0 && first and mask == 0) { mask = mask shr 1; length++ }
        if (mask == 0 || length > (if (id) 4 else 8) || offset + length > header.size) return null
        var value = (if (id) first else first and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or (header[offset + i].toLong() and 255)
        return value to length
    }
    val (headerLength, prefix) = vint(4, false) ?: return null
    if (headerLength <= 0 || headerLength > header.size - 4 - prefix) return null
    var offset = 4 + prefix
    val end = offset + headerLength.toInt()
    var docType: String? = null
    while (offset < end) {
        val (id, idSize) = vint(offset, true) ?: return null
        offset += idSize
        val (size, sizeBytes) = vint(offset, false) ?: return null
        offset += sizeBytes
        if (size < 0 || size > end - offset) return null
        if (id == 0x4282L) {
            if (docType != null) return null
            docType = String(header, offset, size.toInt(), Charsets.US_ASCII)
        }
        offset += size.toInt()
    }
    return when (docType) { "webm" -> "video/webm"; "matroska" -> "video/x-matroska"; else -> null }
}
