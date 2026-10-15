package app.daiyu.android.skills

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Optional pinned GitHub ZIP importer. Validation occurs before any filesystem write. */
object SkillZipArchive {
    const val MAX_ZIP_BYTES = 16 * 1024 * 1024
    fun read(bytes: ByteArray, commit: String, checkpoint: () -> Unit = {}): Map<String, ByteArray> {
        checkpoint()
        require(commit.matches(Regex("[a-f0-9]{40}")) && bytes.size <= MAX_ZIP_BYTES) { "invalid_archive" }
        fun u16(offset: Int): Int { require(offset >= 0 && offset + 2 <= bytes.size) { "invalid_archive" }; return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) }
        fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
        var end = -1
        for (offset in bytes.size - 22 downTo maxOf(0, bytes.size - 65557)) {
            if (u32(offset) == 0x06054b50L && offset + 22 + u16(offset + 20) == bytes.size) { end = offset; break }
        }
        require(end >= 0 && u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "invalid_archive" }
        val count = u16(end + 10); require(count in 1..20000) { "archive_too_large" }
        val centralSize = u32(end + 12); val centralOffset = u32(end + 16)
        require(centralOffset + centralSize == end.toLong()) { "invalid_archive" }
        val names = linkedSetOf<String>(); var cursor = centralOffset.toInt(); var expanded = 0L
        repeat(count) {
            checkpoint()
            require(u32(cursor) == 0x02014b50L) { "invalid_archive" }
            val flags = u16(cursor + 8); val method = u16(cursor + 10)
            require(flags and 1 == 0 && method in setOf(0, 8)) { "unsafe_archive_entry" }
            val size = u32(cursor + 24); val nameLength = u16(cursor + 28); val extra = u16(cursor + 30); val comment = u16(cursor + 32)
            require(u16(cursor + 34) == 0 && u32(cursor + 42) < centralOffset && nameLength in 1..512 && cursor.toLong() + 46 + nameLength + extra + comment <= end) { "invalid_archive" }
            val mode = ((u32(cursor + 38) shr 16) and 0xf000).toInt()
            require(mode in setOf(0, 0x8000, 0x4000)) { "unsafe_archive_entry" }
            val name = SkillFiles.text(bytes.copyOfRange(cursor + 46, cursor + 46 + nameLength))
            validateName(name)
            require(names.add(name)) { "duplicate_archive_entry" }
            require(size <= SkillStore.MAX_FILE_BYTES || name.endsWith('/')) { "resource_too_large" }
            expanded += size; require(expanded <= SkillStore.MAX_SKILL_BYTES) { "archive_too_large" }
            cursor += 46 + nameLength + extra + comment
        }
        require(cursor.toLong() == centralOffset + centralSize) { "invalid_archive" }
        val fileNames = names.filterNot { it.endsWith('/') }.toHashSet()
        require(names.filter { it.endsWith('/') }.none { it.trimEnd('/') in fileNames }) { "invalid_resource_tree" }
        // Include explicit directories when checking ancestors, but only files conflict as parents.
        names.forEach { name ->
            checkpoint()
            var slash = name.indexOf('/')
            while (slash >= 0) {
                require(name.substring(0, slash) !in fileNames) { "invalid_resource_tree" }
                slash = name.indexOf('/', slash + 1)
            }
        }
        val roots = names.map { it.substringBefore('/') }.toSet()
        require(roots.size == 1 && roots.single().endsWith("-$commit")) { "archive_commit_mismatch" }
        val root = roots.single() + "/"
        val files = linkedMapOf<String, ByteArray>(); val seen = mutableSetOf<String>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                checkpoint()
                val entry = zip.nextEntry ?: break
                validateName(entry.name)
                require(entry.name in names && seen.add(entry.name)) { "invalid_archive" }
                val relative = entry.name.removePrefix(root)
                require(entry.name.startsWith(root)) { "unsafe_archive_entry" }
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    checkpoint()
                    val read = zip.read(buffer); if (read < 0) break
                    require(!entry.isDirectory && output.size() + read <= SkillStore.MAX_FILE_BYTES) { "resource_too_large" }
                    total += read; require(total <= SkillStore.MAX_SKILL_BYTES) { "archive_too_large" }
                    output.write(buffer, 0, read)
                }
                if (!entry.isDirectory) { require(relative.isNotEmpty()) { "invalid_archive" }; files[relative] = output.toByteArray() }
                zip.closeEntry()
            }
        }
        require(seen == names) { "invalid_archive" }
        checkpoint()
        SkillFiles.validateResourceTree(files.keys)
        return files
    }
    private fun validateName(name: String) {
        require(name.length in 1..512 && '/' in name && !name.startsWith('/') && '\\' !in name && name.none { it.code < 32 } && name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }) { "unsafe_archive_path" }
    }
}
