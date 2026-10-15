package app.daiyu.android.skills

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object SkillFiles {
    private val locks = ConcurrentHashMap<String, Any>()
    fun <T> locked(root: File, action: () -> T): T {
        require(!Files.isSymbolicLink(root.toPath())) { "unsafe_root" }
        require(root.isDirectory || root.mkdirs()) { "storage_unavailable" }
        return synchronized(locks.getOrPut(root.canonicalPath) { Any() }) {
            val lock = File(root, ".lock")
            require(!Files.isSymbolicLink(lock.toPath())) { "unsafe_root" }
            FileOutputStream(lock, true).channel.use { channel ->
                channel.lock().use { action() }
            }
        }
    }
    fun path(root: File, relative: String): File {
        require(relative.isNotBlank() && relative.length <= 512 && !relative.startsWith('/') && '\\' !in relative && '\u0000' !in relative) { "unsafe_path" }
        val parts = relative.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "unsafe_path" }
        var file = root
        parts.forEach { part ->
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath())) { "unsafe_path" }
        }
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "unsafe_path" }
        return file
    }
    /** O(total path length), not an all-pairs scan of a potentially 20k-entry archive. */
    fun validateResourceTree(paths: Collection<String>) {
        val files = paths.toHashSet()
        paths.forEach { path ->
            var slash = path.indexOf('/')
            while (slash >= 0) {
                require(path.substring(0, slash) !in files) { "invalid_resource_tree" }
                slash = path.indexOf('/', slash + 1)
            }
        }
    }
    fun read(file: File, limit: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "not_a_file" }
        require(file.length() <= limit) { "resource_too_large" }
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= limit) { "resource_too_large" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
    fun text(bytes: ByteArray): String {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(text.none { Character.isISOControl(it) && it !in "\n\r\t" }) { "binary_resource" }
        return text
    }
    fun readAtomic(file: File, limit: Int): ByteArray {
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(File(file.path + ".bak").toPath()) && !Files.isSymbolicLink(File(file.path + ".new").toPath())) { "unsafe_path" }
        return AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "resource_too_large" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun atomic(file: File, bytes: ByteArray) {
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(File(file.path + ".bak").toPath()) && !Files.isSymbolicLink(File(file.path + ".new").toPath())) { "unsafe_path" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    fun move(from: File, to: File) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
    fun remove(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::remove)
        check(file.delete()) { "storage_unavailable" }
    }
}

data class SkillHeader(val name: String, val description: String)

/** Deliberately supports the portable SKILL frontmatter subset, not executable YAML. */
object SkillFrontmatter {
    fun parse(markdown: String): SkillHeader {
        require(markdown.length <= 256 * 1024) { "skill_too_large" }
        val lines = markdown.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')
        require(lines.firstOrNull() == "---") { "missing_frontmatter" }
        val end = (1 until minOf(lines.size, 200)).firstOrNull { lines[it] == "---" } ?: error("missing_frontmatter_end")
        val fields = mutableMapOf<String, String>()
        var index = 1
        while (index < end) {
            val line = lines[index++]
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            require(!line.startsWith(' ') && ':' in line) { "invalid_frontmatter" }
            val key = line.substringBefore(':').trim()
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) && key !in fields) { "invalid_frontmatter" }
            var value = line.substringAfter(':').trim()
            if (value == "|" || value == ">" || value == "|-" || value == ">-") {
                val block = mutableListOf<String>()
                while (index < end && (lines[index].startsWith(' ') || lines[index].isBlank())) block.add(lines[index++].trim())
                value = block.joinToString(if (value.startsWith('>')) " " else "\n").trim()
            } else if (value.startsWith('"') || value.startsWith('\'')) {
                require(value.length >= 2 && value.last() == value.first()) { "invalid_frontmatter" }
                value = value.substring(1, value.length - 1)
            }
            fields[key] = value
        }
        val name = fields["name"] ?: error("missing_name")
        val description = fields["description"] ?: error("missing_description")
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && name.length <= 64) { "invalid_name" }
        require(description.isNotBlank() && description.length <= 2048) { "invalid_description" }
        return SkillHeader(name, description)
    }
}
