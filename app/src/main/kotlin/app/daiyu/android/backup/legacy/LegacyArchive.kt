package app.daiyu.android.backup.legacy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Independent read-only container reader. No archive name is ever a filesystem path. */
internal object LegacyArchive {
    const val TEXT_LIMIT = 16 * 1024 * 1024
    const val BLOB_LIMIT = 64 * 1024 * 1024
    const val COMPLETE_LIMIT = 112 * 1024 * 1024
    private const val ENTRY_LIMIT = 4096
    private const val DIRECTORY_LIMIT = 1024 * 1024
    data class Container(val entries: Map<String, ByteArray>, val zip: Boolean, val sha256: String)

    fun read(input: InputStream): Container {
        val bytes = bounded(input, COMPLETE_LIMIT)
        return if (bytes.size >= 4 && u32(bytes, 0) == 0x04034b50L) zip(bytes)
        else {
            require(bytes.size <= TEXT_LIMIT) { "Legacy JSON exceeds text budget" }
            Container(mapOf("<json>" to bytes), false, digest(bytes))
        }
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            require(n > 0) { "Non-progressing input" }
            require(out.size().toLong() + n <= limit) { "Legacy input exceeds budget" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun path(name: String): String {
        require(name.isNotEmpty() && name.length <= 1024 && !name.startsWith('/') && !name.contains('\\') &&
            !name.contains(':') && !name.contains('%') && name.none { it.code < 32 || it.code == 127 }) { "Unsafe archive path" }
        require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe archive components" }
        return name
    }

    private data class Entry(val name: String, val flags: Int, val method: Int, val crc: Long,
        val compressed: Int, val size: Int, val offset: Int)

    private fun zip(bytes: ByteArray): Container {
        require(bytes.size >= 22)
        // Exactly one EOCD at EOF; ZIP64, multipart and prepended/trailing data are unsupported.
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
            u32(bytes, it) == 0x06054b50L && it + 22 + u16(bytes, it + 20) == bytes.size
        } ?: error("Missing ZIP directory")
        require(u16(bytes, end + 4) == 0 && u16(bytes, end + 6) == 0)
        val count = u16(bytes, end + 10)
        require(count in 1..ENTRY_LIMIT && u16(bytes, end + 8) == count)
        val dirSize = int32(bytes, end + 12); val dirOffset = int32(bytes, end + 16)
        require(dirSize <= DIRECTORY_LIMIT && dirOffset.toLong() + dirSize == end.toLong())
        var p = dirOffset; var textBytes = 0L; var blobBytes = 0L
        val names = hashSetOf<String>(); val entries = ArrayList<Entry>()
        repeat(count) {
            require(p.toLong() + 46 <= end && u32(bytes, p) == 0x02014b50L)
            val flags = u16(bytes, p + 8); val method = u16(bytes, p + 10)
            require(flags and (0x800 or 8).inv() == 0 && method in setOf(0, 8)) { "Encrypted/unsupported ZIP" }
            require(u16(bytes, p + 34) == 0)
            val compressed = int32(bytes, p + 20); val size = int32(bytes, p + 24)
            val nameLength = u16(bytes, p + 28); val extra = u16(bytes, p + 30); val comment = u16(bytes, p + 32)
            val next = p.toLong() + 46 + nameLength + extra + comment
            require(next <= end)
            val name = utf8(bytes.copyOfRange(p + 46, p + 46 + nameLength))
            val directory = name.endsWith('/')
            val normalized = path(if (directory) name.dropLast(1) else name)
            require(names.add(normalized)) { "Duplicate ZIP name" }
            require(!directory || size == 0) { "Directory contains data" }
            // Symlinks and other special Unix objects cannot masquerade as regular originals.
            val unixType = (u32(bytes, p + 38) shr 16).toInt() and 0xf000
            require(unixType == 0 || unixType == if (directory) 0x4000 else 0x8000)
            if (name.startsWith("attachments/")) blobBytes += size else textBytes += size
            require(textBytes <= TEXT_LIMIT && blobBytes <= BLOB_LIMIT && textBytes + blobBytes <= COMPLETE_LIMIT)
            entries += Entry(name, flags, method, u32(bytes, p + 16), compressed, size, int32(bytes, p + 42))
            p = next.toInt()
        }
        require(p == end)
        val result = linkedMapOf<String, ByteArray>(); var cursor = 0
        entries.sortedBy { it.offset }.forEach { e ->
            require(e.offset == cursor && cursor.toLong() + 30 <= dirOffset && u32(bytes, cursor) == 0x04034b50L)
            require(u16(bytes, cursor + 6) == e.flags && u16(bytes, cursor + 8) == e.method)
            val nameLength = u16(bytes, cursor + 26); val extra = u16(bytes, cursor + 28)
            val start = cursor.toLong() + 30 + nameLength + extra
            require(start + e.compressed <= dirOffset)
            require(utf8(bytes.copyOfRange(cursor + 30, cursor + 30 + nameLength)) == e.name)
            if (e.flags and 8 == 0) {
                require(u32(bytes, cursor + 14) == e.crc && int32(bytes, cursor + 18) == e.compressed && int32(bytes, cursor + 22) == e.size)
            } else {
                require(u32(bytes, cursor + 14) in setOf(0L, e.crc))
                require(int32(bytes, cursor + 18) in setOf(0, e.compressed) && int32(bytes, cursor + 22) in setOf(0, e.size))
            }
            val data = if (e.method == 0) {
                require(e.compressed == e.size); bytes.copyOfRange(start.toInt(), start.toInt() + e.size)
            } else inflate(bytes, start.toInt(), e.compressed, e.size)
            require(CRC32().apply { update(data) }.value == e.crc) { "ZIP CRC mismatch" }
            cursor = start.toInt() + e.compressed
            if (e.flags and 8 != 0) {
                if (u32(bytes, cursor) == 0x08074b50L) cursor += 4
                require(cursor.toLong() + 12 <= dirOffset && u32(bytes, cursor) == e.crc &&
                    int32(bytes, cursor + 4) == e.compressed && int32(bytes, cursor + 8) == e.size)
                cursor += 12
            }
            if (!e.name.endsWith('/')) result[e.name] = data
        }
        require(cursor == dirOffset) { "ZIP unindexed bytes" }
        return Container(result, true, digest(bytes))
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int, expected: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, offset, length)
            val out = ByteArrayOutputStream(minOf(expected, 8192)); val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                require(n > 0 || inflater.finished()) { "Invalid deflate stream" }
                require(out.size().toLong() + n <= expected) { "Inflation exceeds declared size" }
                out.write(buffer, 0, n)
            }
            require(inflater.remaining == 0 && out.size() == expected)
            return out.toByteArray()
        } finally { inflater.end() }
    }

    private fun u16(b: ByteArray, p: Int): Int { require(p >= 0 && p.toLong() + 2 <= b.size); return (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8) }
    private fun u32(b: ByteArray, p: Int): Long = u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)
    private fun int32(b: ByteArray, p: Int): Int = u32(b, p).also { require(it <= Int.MAX_VALUE) }.toInt()
}
