package app.daiyu.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

/** Call-bound originals. A receipt is a locator, never screenshot or file-read authority. */
internal class BrowserScreenshotStore(private val filesDir: File, private val cacheDir: File,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryOnDisk) {
    internal data class Binding(val conversation: String, val message: String, val run: String,
        val call: String, val tool: String, val arguments: String) {
        companion object {
            fun from(owner: RunExecutionOwner, call: ToolInvocation) = Binding(owner.conversationId.value,
                owner.assistantMessageId.value, owner.runId.value, call.id.value, call.name, call.arguments)
        }
        fun key() = digest(listOf(conversation, message, run, call, tool, arguments)
            .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    }

    fun freeze(binding: Binding, sourcePath: String): JSONObject {
        require(binding.tool in setOf("browser_read", "browser_write") &&
            JSONObject(binding.arguments).optString("action") == "screenshot")
        val source = File(sourcePath)
        val expected = File(cacheDir, "browser_screenshots").canonicalFile
        check(source.isAbsolute && source.parentFile == expected &&
            Regex("screenshot_[0-9]+\\.jpg").matches(source.name))
        regular(source)
        val bytes = bounded(source)
        val bounds = inspect(bytes)
        val id = UUID.randomUUID().toString()
        val hash = digest(bytes)
        val directory = directory(binding, true)
        val pending = File(directory, "$id.pending")
        val target = File(directory, "$id-$hash.jpg")
        check(pending.createNewFile())
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), ATOMIC_MOVE)
            syncDirectory(directory)
        } finally { pending.delete() }
        return JSONObject().put("version", 1).put("asset", id).put("sha256", hash)
            .put("bytes", bytes.size).put("width", bounds.first).put("height", bounds.second)
    }

    fun read(binding: Binding, receipt: JSONObject): ByteArray {
        check(receipt.optInt("version") == 1)
        val id = receipt.getString("asset"); val hash = receipt.getString("sha256")
        check(Regex("[0-9a-f-]{36}").matches(id) && Regex("[0-9a-f]{64}").matches(hash))
        val bytes = bounded(File(directory(binding, false), "$id-$hash.jpg"))
        check(bytes.size == receipt.getInt("bytes") && digest(bytes) == hash)
        val bounds = inspect(bytes)
        check(bounds.first == receipt.getInt("width") && bounds.second == receipt.getInt("height"))
        return bytes
    }

    fun thumbnail(binding: Binding, receipt: JSONObject): Bitmap? {
        val bytes = read(binding, receipt)
        val size = inspect(bytes)
        var sample = 1
        while (maxOf(size.first, size.second) / sample > 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun deleteConversation(conversation: String) {
        require(conversation.isNotBlank())
        val root = deletionRoot() ?: return
        removeConversationDirectory(root, digest(conversation.toByteArray(Charsets.UTF_8)))
    }

    fun deleteOrphanConversations(retained: Set<String>) {
        val root = deletionRoot() ?: return
        val keep = retained.map { digest(it.toByteArray(Charsets.UTF_8)) }.toSet()
        for (entry in checkNotNull(root.listFiles())) {
            check(entry.name.matches(Regex("[a-f0-9]{64}")))
            if (entry.name !in keep) removeConversationDirectory(root, entry.name)
        }
        syncDirectory(root)
    }

    private fun deletionRoot(): File? {
        val root = File(filesDir, "browser-call-screenshots")
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return null
        check(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root.absoluteFile)
        return root
    }

    private fun removeConversationDirectory(root: File, name: String) {
        val target = File(root, name)
        fun remove(entry: File) {
            check(entry.canonicalFile == entry.absoluteFile)
            if (Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) {
                val children = checkNotNull(entry.listFiles())
                children.forEach(::remove)
            } else regular(entry)
            check(entry.delete())
        }
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) remove(target)
        syncDirectory(root)
    }

    private fun directory(binding: Binding, create: Boolean): File {
        val root = File(filesDir, "browser-call-screenshots")
        val conversation = File(root, digest(binding.conversation.toByteArray(Charsets.UTF_8)))
        val result = File(conversation, binding.key())
        for (dir in listOf(root, conversation, result)) {
            if (create && !dir.exists()) check(dir.mkdir() || dir.isDirectory)
            check(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) && dir.canonicalFile == dir.absoluteFile)
            if (create) syncDirectory(checkNotNull(dir.parentFile))
        }
        return result
    }

    private fun bounded(file: File): ByteArray {
        regular(file)
        check(file.length() in 4..MAX_BYTES.toLong())
        return file.inputStream().use { stream ->
            val bytes = stream.readBytesBounded()
            check(bytes.size in 4..MAX_BYTES); bytes
        }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer); if (n < 0) break
            check(output.size() + n <= MAX_BYTES); output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    private fun regular(file: File) {
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.canonicalFile == file.absoluteFile)
    }
    private fun inspect(bytes: ByteArray): Pair<Int, Int> {
        check(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        check(b.outMimeType == "image/jpeg" && b.outWidth in 1..8192 && b.outHeight in 1..32768 &&
            b.outWidth.toLong() * b.outHeight <= 32_000_000L)
        return b.outWidth to b.outHeight
    }
    companion object {
        const val MEDIA_KEY = "browser:screenshot"
        private fun syncDirectoryOnDisk(directory: File) {
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY or
                android.system.OsConstants.O_CLOEXEC or android.system.OsConstants.O_NOFOLLOW, 0)
            try {
                check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode))
                android.system.Os.fsync(fd)
            } finally { android.system.Os.close(fd) }
        }
        private const val MAX_BYTES = 8 * 1024 * 1024
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
