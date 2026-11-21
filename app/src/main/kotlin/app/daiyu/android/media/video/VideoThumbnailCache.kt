package app.daiyu.android.media.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.util.AtomicFile
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.checkMediaPath
import app.daiyu.android.media.initializeMediaRoot
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Worker-only derivatives. The caller must verify the current original before EVERY lookup. */
internal class VideoThumbnailCache(private val directory: File,
    private val maxBytes: Long = 32L * 1024 * 1024, private val maxEntries: Int = 64) {
    // Controllers recreated in this process still use the same on-disk AtomicFiles.
    private companion object { val gate = Any() }
    private val names = Regex("[0-9a-f]{64}\\.cover")
    private val maxEntryBytes = 5 * 1024 * 1024

    fun load(reference: VideoReference, edge: Int, cancelled: () -> Boolean, decode: () -> Bitmap): Bitmap = synchronized(gate) {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(edge in 1..1024)
        checkVideoCancellation(cancelled)
        val key = videoDigest(listOf("cover-v1", reference.owner.directoryName(), reference.id,
            reference.mime, reference.bytes.toString(), reference.sha256, reference.durationMs.toString(),
            reference.width.toString(), reference.height.toString(), edge.toString())
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
        val root = optional { initializeMediaRoot(directory).also(::trim) }
        var bitmap: Bitmap? = null
        var delivered = false
        try {
            bitmap = root?.let { optional { read(File(it, "$key.cover"), key, edge) } }
            val hit = bitmap != null
            checkVideoCancellation(cancelled)
            if (!hit) bitmap = decode()
            val result = checkNotNull(bitmap)
            checkVideoCancellation(cancelled)
            if (!hit && root != null && result.width in 1..edge && result.height in 1..edge) {
                optional { write(File(root, "$key.cover"), key, result, cancelled); trim(root) }
            }
            checkVideoCancellation(cancelled)
            delivered = true
            result
        } finally { if (!delivered) bitmap?.recycle() }
    }

    private fun read(file: File, key: String, edge: Int): Bitmap? {
        checkMediaPath(file)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return null
        if (file.length() !in 1L..maxEntryBytes.toLong()) { file.delete(); return null }
        val bytes = DataInputStream(file.inputStream()).use { input ->
            if (input.readUTF() != key) return null
            val digest = input.readUTF()
            val size = input.readInt()
            if (size !in 1..maxEntryBytes || size.toLong() > file.length()) return null
            ByteArray(size).also {
                input.readFully(it)
                if (input.read() != -1 || videoDigest(it) != digest) return null
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/png" || bounds.outWidth !in 1..edge || bounds.outHeight !in 1..edge) {
            file.delete(); return null
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    private fun write(file: File, key: String, bitmap: Bitmap, cancelled: () -> Boolean) {
        val encoded = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                if (count >= maxEntryBytes - 256) throw IOException("cover_too_large")
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkVideoCancellation(cancelled)
                if (count.toLong() + length > maxEntryBytes - 256) throw IOException("cover_too_large")
                super.write(bytes, offset, length)
            }
        }
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) throw IOException("cover_encode_failed")
        checkVideoCancellation(cancelled)
        val bytes = encoded.toByteArray()
        for (candidate in listOf(file, File(file.path + ".new"), File(file.path + ".bak"))) checkMediaPath(candidate)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeUTF(key); data.writeUTF(videoDigest(bytes)); data.writeInt(bytes.size); data.write(bytes); data.flush()
            checkVideoCancellation(cancelled)
            atomic.finishWrite(output)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    private fun trim(root: File) {
        val files = root.listFiles() ?: throw IOException("cover_list_failed")
        val entries = files.filter { names.matches(it.name) }.onEach {
            checkMediaPath(it)
            if (!Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) throw IOException("cover_not_regular")
        }.sortedBy { it.lastModified() }
        var size = entries.sumOf { it.length() }
        var count = entries.size
        for (file in entries) {
            if (size <= maxBytes && count <= maxEntries) break
            val length = file.length()
            if (!file.delete()) throw IOException("cover_delete_failed")
            size -= length; count--
        }
        // AtomicFile's interrupted sidecars are disposable derivatives, not original assets.
        files.filter { it.extension in setOf("new", "bak") && names.matches(it.nameWithoutExtension) }.forEach {
            checkMediaPath(it)
            if (Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) it.delete()
        }
    }

    private inline fun <T> optional(work: () -> T): T? = try { work() }
    catch (_: IOException) { null }
    catch (_: SecurityException) { null }
    catch (_: ImageAttachmentException) { null }
    catch (_: IllegalArgumentException) { null }
}
