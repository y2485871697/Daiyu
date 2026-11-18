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
    private data class Owner(val conversation: String, val run: String)
    private val temporaryFiles = linkedMapOf<Owner, MutableSet<File>>()
    private val fileImages = linkedMapOf<Owner, MutableMap<String, ByteArray>>()

    @Synchronized
    fun receipt(conversation: String, run: String, call: String, image: GuiScreenshot, metadata: String): String =
        assetReceipt(conversation, run, call, image.png, metadata)

    @Synchronized
    fun fileReceipt(conversation: String, run: String, call: String, png: ByteArray): String {
        require(listOf(conversation, run, call).all { it.isNotBlank() && it.length <= 200 })
        val (width, height) = requireNotNull(NativeFileImage.pngDimensions(png)) { "文件图片不是有效的有界 PNG" }
        check(!wasCleared(conversation, run)) { "本次文件图片已清理" }
        val id = assetId(run, call)
        val images = fileImages.getOrPut(Owner(conversation, run)) { linkedMapOf() }
        val previous = images[id]
        require(previous == null || previous.contentEquals(png)) { "文件图片资产损坏" }
        images[id] = png.copyOf()
        return JSONObject().put("image_source", "read_image").put("mime_type", "image/png")
            .put("width", width).put("height", height)
            .put("image_asset", JSONObject().put("version", 1).put("id", id).put("run_id", run).put("call_id", call)
                .put("sha256", hash(png)).put("bytes", png.size).put("temporary", true)).toString()
    }

    private fun assetReceipt(conversation: String, run: String, call: String, png: ByteArray, metadata: String): String {
        require(listOf(conversation, run, call).all { it.isNotBlank() && it.length <= 200 })
        require(png.size in 33..NativeScreenImage.MAX_BYTES)
        val result = JSONObject(metadata)
        check(!wasCleared(conversation, run)) { "本次操作截图已清理" }
        check(!Files.exists(clearedMarker(conversation, run).toPath(), NOFOLLOW_LINKS)) { "截图清理状态损坏" }
        val digest = hash(png)
        val id = assetId(run, call)
        val directory = mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)), create = true)
        val target = File(directory, "$id.png").also(::checkMediaPath)
        val owned = temporaryFiles.getOrPut(Owner(conversation, run)) { linkedSetOf() }
        if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            val temporary = File.createTempFile("image-", ".pending", directory)
            owned.add(temporary)
            try {
                FileOutputStream(temporary).use { it.write(png); it.fd.sync() }
                checkMediaPath(target)
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
                owned.add(target)
            } finally {
                if (temporary.delete() || !Files.exists(temporary.toPath(), NOFOLLOW_LINKS)) owned.remove(temporary)
            }
        } else require(Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && target.length() == png.size.toLong() &&
            hash(target.readBytes()) == digest) { "屏幕图片资产损坏" }
        return result.apply {
            remove("data_base64")
            put("image_asset", JSONObject().put("version", 1).put("id", id).put("run_id", run).put("call_id", call)
                .put("sha256", digest).put("bytes", png.size).put("temporary", temporaryFiles[Owner(conversation, run)]?.contains(target) == true))
        }.toString()
    }

    @Synchronized
    fun read(conversation: String, call: String, output: String, expectedRun: String? = null): ByteArray? = runCatching {
        val data = JSONObject(output)
        val reference = data.getJSONObject("image_asset")
        val run = reference.getString("run_id")
        val digest = reference.getString("sha256")
        val id = reference.getString("id")
        val size = reference.getInt("bytes")
        require(reference.getInt("version") == 1 && run.isNotBlank() && run.length <= 200 && reference.getString("call_id") == call &&
            (expectedRun == null || run == expectedRun) && IMAGE_HASH.matches(digest) &&
            id == assetId(run, call) && size in 33..NativeScreenImage.MAX_BYTES)
        if (data.opt("image_source") == "read_image") {
            require(expectedRun != null && expectedRun == run && reference.opt("temporary") == true &&
                !wasCleared(conversation, run))
            val bytes = requireNotNull(fileImages[Owner(conversation, run)]?.get(id))
            require(bytes.size == size && hash(bytes) == digest)
            return@runCatching bytes.copyOf()
        }
        val directory = mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)))
        val file = File(directory, "$id.png").also(::checkMediaPath)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == size.toLong())
        file.readBytes().also { require(it.size == size && hash(it) == digest) }
    }.getOrNull()

    /** Only captures created by this live run are temporary; existing saved previews stay untouched. */
    @Synchronized
    fun finishRun(conversation: String, run: String): Int {
        val owner = Owner(conversation, run)
        val files = temporaryFiles[owner] ?: emptySet()
        val images = fileImages[owner].orEmpty()
        if (files.isEmpty() && images.isEmpty() && owner !in temporaryFiles) return 0
        for (file in files) {
            checkMediaPath(file)
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.delete()) { "操作临时截图清理失败" }
            }
        }
        mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)), create = true)
        val marker = clearedMarker(conversation, run)
        checkMediaPath(marker)
        check(marker.createNewFile() || (Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 0L)) { "截图清理状态保存失败" }
        FileOutputStream(marker, true).use { it.fd.sync() }
        temporaryFiles.remove(owner)
        fileImages.remove(owner)
        return files.size + images.size
    }

    @Synchronized
    fun wasCleared(conversation: String, run: String): Boolean {
        val marker = clearedMarker(conversation, run)
        checkMediaPath(marker)
        return Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 0L
    }

    private fun clearedMarker(conversation: String, run: String): File = File(
        mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8))),
        "${hash(run.toByteArray(Charsets.UTF_8))}.cleared")

    @Synchronized
    fun deleteConversation(conversation: String) {
        val directory = mediaDirectory(root, hash(conversation.toByteArray(Charsets.UTF_8)))
        if (directory.exists()) {
            directory.listFiles().orEmpty().forEach(::checkMediaPath)
            check(directory.deleteRecursively()) { "屏幕图片清理失败" }
        }
        temporaryFiles.keys.removeAll { it.conversation == conversation }
        fileImages.keys.removeAll { it.conversation == conversation }
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
