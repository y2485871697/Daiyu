package app.daiyu.android.media

import app.daiyu.android.gui.GuiScreenshot
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** One private PNG per tool call. Receipts contain only a bound, non-path asset reference. */
internal class ScreenToolImageStore(root: File) {
    private val root = initializeMediaRoot(root)

    @Synchronized
    fun receipt(conversation: String, run: String, call: String, image: GuiScreenshot, metadata: String): String {
        require(listOf(conversation, run, call).all { it.isNotBlank() && it.length <= 200 })
        require(image.png.size in 33..NativeScreenImage.MAX_BYTES)
        val digest = hash(image.png)
        val id = assetId(run, call)
        val directory = mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)), create = true)
        val target = File(directory, "$id.png").also(::checkMediaPath)
        if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            val temporary = File.createTempFile("image-", ".pending", directory)
            try {
                FileOutputStream(temporary).use { it.write(image.png); it.fd.sync() }
                checkMediaPath(target)
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
            } finally { temporary.delete() }
        } else require(Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && target.length() == image.png.size.toLong() &&
            hash(target.readBytes()) == digest) { "屏幕图片资产损坏" }
        return JSONObject(metadata).apply {
            remove("data_base64")
            put("image_asset", JSONObject().put("version", 1).put("id", id).put("run_id", run).put("call_id", call)
                .put("sha256", digest).put("bytes", image.png.size))
        }.toString()
    }

    @Synchronized
    fun read(conversation: String, call: String, output: String, expectedRun: String? = null): ByteArray? = runCatching {
        val reference = JSONObject(output).getJSONObject("image_asset")
        val run = reference.getString("run_id")
        val digest = reference.getString("sha256")
        val id = reference.getString("id")
        val size = reference.getInt("bytes")
        require(reference.getInt("version") == 1 && run.isNotBlank() && run.length <= 200 && reference.getString("call_id") == call &&
            (expectedRun == null || run == expectedRun) && IMAGE_HASH.matches(digest) &&
            id == assetId(run, call) && size in 33..NativeScreenImage.MAX_BYTES)
        val directory = mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)))
        val file = File(directory, "$id.png").also(::checkMediaPath)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == size.toLong())
        file.readBytes().also { require(it.size == size && hash(it) == digest) }
    }.getOrNull()

    @Synchronized
    fun deleteConversation(conversation: String) {
        val directory = mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)))
        if (directory.exists()) {
            directory.listFiles().orEmpty().forEach(::checkMediaPath)
            check(directory.deleteRecursively()) { "屏幕图片清理失败" }
        }
    }

    @Synchronized
    fun deleteOrphanConversations(retained: Set<String>) {
        val keep = retained.map { hash(it.toByteArray(Charsets.UTF_8)) }.toSet()
        checkMediaPath(root)
        root.listFiles().orEmpty().filter { IMAGE_HASH.matches(it.name) && it.name !in keep }.forEach {
            val directory = mediaDirectory(root, it.name)
            directory.listFiles().orEmpty().forEach(::checkMediaPath)
            check(directory.deleteRecursively()) { "屏幕图片清理失败" }
        }
    }

    private fun assetId(run: String, call: String) = hash("$run\u0000$call".toByteArray(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
