package app.daiyu.android.backup

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.*
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Private, operation-owned files. Nothing in an imported package becomes a filesystem path. */
internal class BackupPackageSpool(parent: File) : Closeable {
    private val root = File.createTempFile("complete-backup-", ".stage", parent).also {
        check(it.delete() && it.mkdir()) { "无法准备备份暂存目录" }
    }
    private val originals = linkedMapOf<String, File>()
    private var closed = false
    private var total = 0L
    val bytes: Map<String, ByteArray> = object : AbstractMap<String, ByteArray>() {
        override val entries: Set<Map.Entry<String, ByteArray>> get() = originals.keys.mapTo(linkedSetOf()) { hash ->
            object : Map.Entry<String, ByteArray> {
                override val key = hash
                override val value: ByteArray get() = original(hash).readBytes()
            }
        }
        override fun get(key: String): ByteArray? = if (key in originals) original(key).readBytes() else null
    }
    fun temporary(): File {
        check(!closed)
        return File.createTempFile("part-", ".tmp", root)
    }
    fun original(hash: String): File {
        check(!closed)
        return originals.getValue(hash)
    }
    fun include(hash: String, value: ByteArray) {
        check(!closed)
        require(digest(value) == hash)
        originals[hash]?.let { previous ->
            check(previous.length() == value.size.toLong())
            previous.inputStream().use { input ->
                val block = ByteArray(8192); var offset = 0
                while (true) {
                    val count = input.read(block); if (count < 0) break
                    repeat(count) { check(block[it] == value[offset + it]) }
                    offset += count
                }
                check(offset == value.size)
            }
            return
        }
        require(total + value.size <= MAX_BYTES)
        val file = temporary()
        FileOutputStream(file).use { it.write(value); it.fd.sync() }
        originals[hash] = file; total += value.size
    }
    data class Envelope(val version: Long, val id: String, val payload: JSONObject)

    /** Reads the two JSON string layers without ever constructing the complete payload String. */
    fun read(source: InputStream): Envelope = read(strictReader(LimitedInput(source, MAX_PACKAGE)))
    fun read(source: Reader): Envelope {
        val raw = temporary()
        val outer = JsonInput(source)
        val envelope = JSONObject()
        var payloadDigest: String? = null
        outer.fields { key ->
            require(key in setOf("format", "version", "sha256", "payload"))
            if (key == "payload") {
                val sha = MessageDigest.getInstance("SHA-256")
                DigestOutputStream(LimitedOutput(FileOutputStream(raw), MAX_PACKAGE), sha).writer(Charsets.UTF_8).use { output ->
                    outer.quoted().copyTo(output)
                }
                payloadDigest = hex(sha.digest())
            } else envelope.put(key, outer.value(1))
        }
        outer.end()
        require(envelope.keys().asSequence().toSet() == setOf("format", "version", "sha256"))
        require(envelope.get("format") == "daiyu-neutral-complete")
        val version = envelope.get("version")
        require(version is Int || version is Long)
        val number = (version as Number).toLong(); require(number in 1L..7L)
        val id = requireNotNull(payloadDigest)
        require(envelope.get("sha256") == id) { "备份摘要不匹配" }
        val payload = raw.inputStream().use { input ->
            val parser = JsonInput(strictReader(input))
            val result = JSONObject()
            parser.fields { key -> result.put(key, if (key == "blobs") readBlobs(parser) else parser.value(1)) }
            parser.end(); result
        }
        check(raw.delete()) { "备份中间文件清理失败" }
        return Envelope(number, id, payload)
    }
    private fun readBlobs(parser: JsonInput): JSONArray = parser.array {
        var original: File? = null
        var encodedCharacters = 0L
        val row = JSONObject()
        parser.fields { key ->
            require(key in setOf("sha256", "bytes", "data"))
            if (key != "data") row.put(key, parser.value(3)) else {
                val file = temporary(); original = file
                val text = parser.quoted()
                val ascii = object : InputStream() {
                    override fun read(): Int {
                        val ch = text.read()
                        if (ch >= 0) {
                            require(ch < 128 && ++encodedCharacters <= (MAX_BYTES + 2) / 3 * 4)
                        }
                        return ch
                    }
                }
                val sha = MessageDigest.getInstance("SHA-256")
                var size = 0L
                Base64.getDecoder().wrap(ascii).use { decoded ->
                    FileOutputStream(file).use { target ->
                        val block = ByteArray(8192)
                        while (true) {
                            val count = decoded.read(block); if (count < 0) break
                            require(count > 0 && total + size + count <= MAX_BYTES)
                            target.write(block, 0, count); sha.update(block, 0, count); size += count
                        }
                        target.fd.sync()
                    }
                    require(ascii.read() == -1) { "Base64结束后存在多余数据" }
                }
                row.put("decoded_sha256", hex(sha.digest()))
            }
        }
        require(row.keys().asSequence().toSet() == setOf("sha256", "bytes", "decoded_sha256"))
        val hash = row.get("sha256") as? String ?: error("缺少原件摘要")
        val length = row.get("bytes"); require(length is Int || length is Long)
        val size = (length as Number).toLong()
        val file = requireNotNull(original)
        require(hash.matches(Regex("[0-9a-f]{64}")) && hash !in originals)
        require(size in 0..MAX_BYTES && file.length() == size && row.get("decoded_sha256") == hash)
        require(encodedCharacters <= (size + 2) / 3 * 4)
        total += size; require(total <= MAX_BYTES)
        originals[hash] = file
        row.remove("decoded_sha256"); row
    }
    /** Produces the versioned envelope, including the hash of the actual serialized payload. */
    fun write(version: Long, payload: JSONObject): File {
        require(version in 1L..7L)
        val raw = temporary(); val sha = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(LimitedOutput(FileOutputStream(raw), MAX_PACKAGE), sha).writer(Charsets.UTF_8).use { writer ->
            writeObject(writer, payload, true)
        }
        val hash = hex(sha.digest()); val result = temporary()
        LimitedOutput(FileOutputStream(result), MAX_PACKAGE).writer(Charsets.UTF_8).use { writer ->
            writer.write("{\"format\":\"daiyu-neutral-complete\",\"version\":$version,\"sha256\":\"$hash\",\"payload\":")
            raw.reader(Charsets.UTF_8).use { quote(writer, it) }
            writer.write("}")
        }
        check(raw.delete()) { "备份中间文件清理失败" }
        return result
    }
    private fun writeObject(writer: Writer, value: JSONObject, payload: Boolean = false) {
        writer.write("{"); var comma = false
        value.keys().forEach { key ->
            if (comma) writer.write(","); comma = true
            quote(writer, StringReader(key)); writer.write(":")
            if (payload && key == "blobs") {
                writer.write("["); val rows = value.getJSONArray(key)
                repeat(rows.length()) { index ->
                    if (index > 0) writer.write(",")
                    val row = rows.getJSONObject(index); val hash = row.getString("sha256")
                    writer.write("{\"sha256\":\"$hash\",\"bytes\":${row.getLong("bytes")},\"data\":\"")
                    val ascii = object : OutputStream() {
                        override fun write(b: Int) { writer.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { writer.write(String(b, off, len, Charsets.US_ASCII)) }
                        override fun close() = Unit
                    }
                    Base64.getEncoder().wrap(ascii).use { encoded -> original(hash).inputStream().use { it.copyTo(encoded) } }
                    writer.write("\"}")
                }
                writer.write("]")
            } else writeValue(writer, value.get(key))
        }
        writer.write("}")
    }
    private fun writeValue(writer: Writer, value: Any) {
        when (value) {
            is JSONObject -> writeObject(writer, value)
            is JSONArray -> {
                writer.write("[")
                repeat(value.length()) { if (it > 0) writer.write(","); writeValue(writer, value.get(it)) }
                writer.write("]")
            }
            is String -> quote(writer, StringReader(value))
            is Number -> writer.write(JSONObject.numberToString(value))
            is Boolean -> writer.write(value.toString())
            else -> { require(value == JSONObject.NULL); writer.write("null") }
        }
    }
    override fun close() {
        if (!closed) { closed = true; check(root.deleteRecursively()) { "备份暂存清理失败" } }
    }
    private class JsonInput(private val input: Reader, private val probe: Boolean = false) {
        private var next = -2
        private fun peek(): Int { if (next == -2) next = input.read(); return next }
        private fun take(): Int = peek().also { next = -2 }
        private fun whitespace() { while (peek() in listOf(32, 9, 10, 13)) take() }
        private fun expect(ch: Char) { whitespace(); require(take() == ch.code) { "备份JSON格式错误" } }
        fun end() { whitespace(); require(peek() == -1) { "备份JSON末尾存在多余内容" } }
        fun fields(consume: (String) -> Unit) {
            expect('{'); whitespace(); if (peek() == '}'.code) { take(); return }
            val keys = hashSetOf<String>()
            while (true) {
                val key = string(if (probe) 512 else MAX_PACKAGE); require(keys.add(key)) { "重复JSON字段" }
                if (probe) require(keys.size <= 50_000)
                expect(':'); consume(key); whitespace()
                when (take()) { '}'.code -> return; ','.code -> Unit; else -> error("备份JSON对象未结束") }
            }
        }
        fun array(element: () -> Any): JSONArray {
            expect('['); val array = JSONArray(); whitespace()
            if (peek() == ']'.code) { take(); return array }
            while (true) {
                require(array.length() < 50_000)
                array.put(element()); whitespace()
                when (take()) { ']'.code -> return array; ','.code -> Unit; else -> error("备份JSON数组未结束") }
            }
        }
        fun quoted(): Reader {
            expect('"')
            return object : Reader() {
                private var finished = false
                override fun read(): Int {
                    if (finished) return -1
                    val ch = take(); require(ch >= 32) { "备份JSON字符串未结束或含控制字符" }
                    if (ch == '"'.code) { finished = true; return -1 }
                    if (ch != '\\'.code) return ch
                    return when (val escaped = take()) {
                        '"'.code, '\\'.code, '/'.code -> escaped
                        'b'.code -> 8; 'f'.code -> 12; 'n'.code -> 10; 'r'.code -> 13; 't'.code -> 9
                        'u'.code -> {
                            var number = 0
                            repeat(4) {
                                val character = take()
                                val digit = when (character) {
                                    in '0'.code..'9'.code -> character - '0'.code
                                    in 'a'.code..'f'.code -> character - 'a'.code + 10
                                    in 'A'.code..'F'.code -> character - 'A'.code + 10
                                    else -> error("非法JSON Unicode转义")
                                }
                                number = number * 16 + digit
                            }
                            number
                        }
                        else -> error("非法JSON转义")
                    }
                }
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    var count = 0
                    while (count < length) { val ch = read(); if (ch < 0) break; buffer[offset + count++] = ch.toChar() }
                    return if (count == 0) -1 else count
                }
                override fun close() = Unit
            }
        }
        fun string(limit: Long = MAX_PACKAGE): String {
            val result = StringBuilder()
            val text = quoted(); val block = CharArray(4096)
            while (true) {
                val count = text.read(block); if (count < 0) break
                require(result.length.toLong() + count <= limit)
                result.append(block, 0, count)
            }
            return result.toString()
        }
        fun skip(depth: Int) {
            require(depth <= 32); whitespace()
            when (peek()) {
                '"'.code -> { val text = quoted(); val block = CharArray(8192); while (text.read(block) >= 0) { } }
                '{'.code -> fields { skip(depth + 1) }
                '['.code -> {
                    expect('['); whitespace()
                    if (peek() == ']'.code) { take(); return }
                    var count = 0
                    while (true) {
                        require(++count <= 50_000); skip(depth + 1); whitespace()
                        when (take()) { ']'.code -> return; ','.code -> Unit; else -> error("备份JSON数组未结束") }
                    }
                }
                else -> value(depth)
            }
        }
        fun value(depth: Int): Any {
            require(depth <= 32); whitespace()
            return when (peek()) {
                '"'.code -> string()
                '{'.code -> JSONObject().also { result -> fields { key -> result.put(key, value(depth + 1)) } }
                '['.code -> array { value(depth + 1) }
                else -> {
                    val token = StringBuilder()
                    while (peek() >= 0 && peek() !in listOf(32, 9, 10, 13, ','.code, ']'.code, '}'.code)) {
                        require(token.length < 64); token.append(take().toChar())
                    }
                    val text = token.toString()
                    require(text in setOf("true", "false", "null") || text.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    JSONTokener(text).nextValue()
                }
            }
        }
    }
    companion object {
        /** Probe only selects a parser. The selected importer still validates the entire package. */
        internal fun detectFormat(source: InputStream): String {
            val parser = JsonInput(strictReader(LimitedInput(source, MAX_PACKAGE)), probe = true)
            var format: String? = null
            parser.fields { key ->
                if (key == "format") format = parser.string(64)
                else parser.skip(1)
            }
            parser.end()
            return requireNotNull(format) { "缺少备份格式" }
        }
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_PACKAGE = 112L * 1024 * 1024
        private fun digest(value: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(value))
        private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun strictReader(input: InputStream) = InputStreamReader(input, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
        private fun quote(writer: Writer, input: Reader) {
            writer.write("\""); val block = CharArray(8192)
            while (true) {
                val count = input.read(block); if (count < 0) break
                var start = 0
                repeat(count) { index ->
                    val ch = block[index]
                    if (ch == '"' || ch == '\\' || ch < ' ') {
                        writer.write(block, start, index - start)
                        when (ch) {
                            '"' -> writer.write("\\\""); '\\' -> writer.write("\\\\")
                            else -> writer.write("\\u" + ch.code.toString(16).padStart(4, '0'))
                        }
                        start = index + 1
                    }
                }
                writer.write(block, start, count - start)
            }
            writer.write("\"")
        }
        private class LimitedInput(private val source: InputStream, private val limit: Long) : InputStream() {
            private var count = 0L
            override fun read(): Int = source.read().also { if (it >= 0) { count++; require(count <= limit) } }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val length = source.read(buffer, off, len)
                if (length == 0) {
                    val value = read(); if (value < 0) return -1
                    buffer[off] = value.toByte(); return 1
                }
                if (length > 0) { count += length; require(count <= limit) }
                return length
            }
        }
        private class LimitedOutput(private val target: OutputStream, private val limit: Long) : OutputStream() {
            private var count = 0L
            override fun write(b: Int) { require(++count <= limit); target.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len; require(count <= limit); target.write(b, off, len) }
            override fun flush() = target.flush()
            override fun close() = target.close()
        }
    }
}
