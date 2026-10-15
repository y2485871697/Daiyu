package app.daiyu.android.assistants

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Optional host boundary for avatar files and revision-checked MEMORY.md editing. */
interface AssistantAssets {
    fun avatarBitmap(fileName: String?): Bitmap?
    fun saveAvatar(id: String, bitmap: Bitmap): String
    fun copyAvatar(fileName: String, toId: String): String?
    fun deleteAvatar(fileName: String?)
    fun memory(id: String): AssistantMemorySnapshot
    fun saveMemory(id: String, content: String, revision: String): AssistantMemorySnapshot
    fun copyMemory(fromId: String, toId: String)
    fun deleteMemory(id: String)
    companion object { const val MAX_MEMORY_BYTES = 1024 * 1024 }
}

data class AssistantMemorySnapshot(val content: String, val revision: String)
data class AssistantSkill(val id: String, val name: String, val description: String)

/** Local implementation, deliberately independent of the agent/runtime skill repository. */
class FileAssistantAssets(context: Context) : AssistantAssets {
    private val root = File(context.applicationContext.filesDir, "daiyu-assistants")
    private fun avatar(fileName: String): File {
        require(fileName == File(fileName).name && !fileName.contains('\\'))
        return File(root, "avatars/$fileName")
    }
    override fun avatarBitmap(fileName: String?): Bitmap? = fileName?.let {
        runCatching { BitmapFactory.decodeFile(avatar(it).absolutePath) }.getOrNull()
    }
    @Synchronized
    override fun saveAvatar(id: String, bitmap: Bitmap): String {
        AssistantStorage.id(id)
        val name = "$id.png"
        val file = avatar(name)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = 512f / longest
        val scaled = if (longest <= 512) bitmap else Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        try { file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if (scaled !== bitmap) scaled.recycle() }
        return name
    }
    @Synchronized
    override fun copyAvatar(fileName: String, toId: String): String? {
        val source = avatar(fileName).takeIf { it.isFile } ?: return null
        val targetName = "${AssistantStorage.id(toId)}.png"
        val target = avatar(targetName)
        return runCatching {
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            source.copyTo(target, overwrite = true)
            targetName
        }.getOrNull()
    }
    @Synchronized
    override fun deleteAvatar(fileName: String?) { fileName?.let { avatar(it).delete() } }
    private fun memoryFile(id: String) = AtomicFile(File(root, "memory/${AssistantStorage.id(id)}/MEMORY.md"))
    @Synchronized
    override fun memory(id: String): AssistantMemorySnapshot {
        val file = memoryFile(id)
        val bytes = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) byteArrayOf()
            else file.openRead().use { it.readNBytes(AssistantAssets.MAX_MEMORY_BYTES + 1) }
        require(bytes.size <= AssistantAssets.MAX_MEMORY_BYTES) { "记忆文件超过 1 MiB 安全上限" }
        return snapshot(bytes)
    }
    @Synchronized
    override fun saveMemory(id: String, content: String, revision: String): AssistantMemorySnapshot {
        check(memory(id).revision == revision) { "记忆已被其他操作更新，草稿已保留；请先重新读取并合并。" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= AssistantAssets.MAX_MEMORY_BYTES) { "记忆文件不能超过 1 MiB UTF-8 字节" }
        val file = memoryFile(id)
        check(file.baseFile.parentFile!!.mkdirs() || file.baseFile.parentFile!!.isDirectory) { "无法创建记忆目录" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
        return snapshot(bytes)
    }
    @Synchronized
    override fun copyMemory(fromId: String, toId: String) {
        saveMemory(toId, memory(fromId).content, memory(toId).revision)
    }
    @Synchronized
    override fun deleteMemory(id: String) { memoryFile(id).delete() }
    private fun snapshot(bytes: ByteArray) = AssistantMemorySnapshot(bytes.toString(Charsets.UTF_8),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
