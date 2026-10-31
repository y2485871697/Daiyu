package app.daiyu.android.ui.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun chatMediaPreviewIsVideo(mimeType: String): Boolean =
    mimeType.startsWith("video/", ignoreCase = true)

internal fun chatMediaPreviewDirectory(mimeType: String): String =
    if (chatMediaPreviewIsVideo(mimeType)) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

internal fun chatMediaPreviewFileName(displayName: String, mimeType: String): String {
    val name = displayName.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }
        .joinToString("").trim().trim('.').take(180)
    return name.ifBlank { if (chatMediaPreviewIsVideo(mimeType)) "video.mp4" else "image.jpg" }
}

/** Byte-for-byte copy; cancellation does not publish an incomplete MediaStore entry. */
internal suspend fun chatMediaPreviewCopy(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    output.flush()
}

internal suspend fun saveChatMediaPreviewOriginal(context: Context, item: ChatMediaPreviewItem): Boolean =
    withContext(Dispatchers.IO) {
        try {
            // Older devices must go through the caller's CreateDocument authorization.
            if (Build.VERSION.SDK_INT < 29) return@withContext false
            val original = item.openOriginal() ?: return@withContext false
            chatMediaPreviewCommitOriginal(original) {
                require(it.mimeType.startsWith("image/", true) || chatMediaPreviewIsVideo(it.mimeType))
                saveChatMediaPreviewPending(context, it)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

/** Closing an input after a successful commit must not turn an actual save into a failure. */
internal suspend fun chatMediaPreviewCommitOriginal(
    original: ChatMediaPreviewOriginal,
    commit: suspend (ChatMediaPreviewOriginal) -> Unit,
) {
    var committed = false
    var failure: Throwable? = null
    try {
        commit(original)
        committed = true
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            original.close()
        } catch (close: Throwable) {
            if (failure != null) failure.addSuppressed(close)
            else if (!committed) throw close
        }
    }
}

/** API 26–28 use the system document picker, never a new broad storage permission. */
internal suspend fun saveChatMediaPreviewDocument(
    context: Context,
    item: ChatMediaPreviewItem,
    destination: Uri,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val original = item.openOriginal() ?: return@withContext false
        chatMediaPreviewCommitOriginal(original) {
            require(it.mimeType.startsWith("image/", true) || chatMediaPreviewIsVideo(it.mimeType))
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("Cannot open selected document")
            output.use { stream -> chatMediaPreviewCopy(it.stream, stream) }
            currentCoroutineContext().ensureActive()
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private suspend fun saveChatMediaPreviewPending(context: Context, original: ChatMediaPreviewOriginal) {
    val resolver = context.contentResolver
    val collection = if (chatMediaPreviewIsVideo(original.mimeType)) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, chatMediaPreviewFileName(original.displayName, original.mimeType))
        put(MediaStore.MediaColumns.MIME_TYPE, original.mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${chatMediaPreviewDirectory(original.mimeType)}/Daiyu")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri: Uri = resolver.insert(collection, values) ?: throw IOException("Cannot create media entry")
    try {
        val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open media entry")
        output.use { chatMediaPreviewCopy(original.stream, it) }
        currentCoroutineContext().ensureActive()
        val updated = resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        if (updated != 1) throw IOException("Cannot publish media entry")
        // Successful publication is the commit point; an input close failure cannot undo it.
    } catch (failure: Throwable) {
        try {
            resolver.delete(uri, null, null)
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

internal fun chatMediaPreviewReserveFile(directory: File, name: String): File {
    val stem = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    for (index in 0..9999) {
        val candidate = File(directory, if (index == 0) name else "$stem ($index)$extension")
        if (candidate.createNewFile()) return candidate
    }
    throw IOException("Cannot reserve media filename")
}

/** The only playback path is a short-lived private copy of the owner's verified stream. */
internal suspend fun <T> withChatMediaPreviewVideo(
    context: Context,
    item: ChatMediaPreviewItem,
    play: suspend (File) -> T,
): T = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "chat-media-preview")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create playback cache")
    val staged = File.createTempFile("playback-", ".media", directory)
    try {
        val original = item.openOriginal() ?: throw IOException("Original is unavailable")
        original.use {
            require(chatMediaPreviewIsVideo(it.mimeType))
            staged.outputStream().use { output -> chatMediaPreviewCopy(it.stream, output) }
        }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) { play(staged) }
    } finally {
        staged.delete()
    }
}
