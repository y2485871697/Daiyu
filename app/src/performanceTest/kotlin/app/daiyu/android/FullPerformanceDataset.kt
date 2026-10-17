package app.daiyu.android

import android.content.Context
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Random

/** Test-APK-only fixture. MB here means 1,000,000 bytes; setup is outside all measured windows. */
internal object FullPerformanceDataset {
    const val TEXT_BYTES = 10_000_000L
    const val PACKAGE_BYTES = 100_000_000L
    private const val SMALL_ASSET_BYTES = 600_000
    private const val LAST_ASSET = "p-att-099"
    data class Package(val file: File, val sha256: String)
    data class Snapshot(val sha256: String, val manifest: String)

    private fun body(ordinal: Int) = "Synthetic-$ordinal:".padEnd(1000, 'x').also {
        check(it.toByteArray(Charsets.UTF_8).size == 1000)
    }
    private fun binary(seed: Int, size: Int) = ByteArray(size).also { Random(seed.toLong()).nextBytes(it) }

    private fun writeUtf8(writer: Writer, text: String) {
        var offset = 0
        while (offset < text.length) {
            val count = minOf(4096, text.length - offset)
            writer.write(text, offset, count)
            offset += count
        }
    }

    private fun utf8Size(text: String): Long {
        var count = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { count++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
        }
        OutputStreamWriter(sink, Charsets.UTF_8).use { writeUtf8(it, text) }
        return count
    }

    fun seed(db: ConversationDatabase, backup: NeutralBackupService): Conversation {
        check(db.conversations().isEmpty())
        var ordinal = 0
        repeat(1000) { index ->
            val owner = ConversationId("p-c-$index")
            repeat(if (index == 0) 1004 else 4) { turn ->
                val n = ordinal++
                val run = RunId("p-r-$n")
                val user = Message(MessageId("p-u-$n"), owner, run, Message.USER_ROLE,
                    listOf(MessageBlock.Text("u", body(2 * n))), RunStatus.COMPLETED,
                    createdAt = 1_700_000_000_000L + 2 * n)
                val blocks = buildList<MessageBlock> {
                    add(MessageBlock.Text("a", body(2 * n + 1)))
                    if (n < 500) add(MessageBlock.Tool(ToolInvocation(ToolCallId("p-call-$n"),
                        "synthetic_lookup", """{"key":"fixture-$n"}"""), """{"value":"synthetic-result-$n"}"""))
                }
                val assistant = Message(MessageId("p-a-$n"), owner, run, Message.ASSISTANT_ROLE,
                    blocks, RunStatus.RUNNING, createdAt = 1_700_000_000_001L + 2 * n)
                db.send(user, assistant, newConversation = turn == 0, expectedAssistantId = "default")
                db.save(assistant.copy(status = RunStatus.COMPLETED))
            }
        }
        check(ordinal == 5000)
        repeat(99) { n -> backup.attach("p-att-${n.toString().padStart(3, '0')}",
            "p-u-$n", "application/octet-stream", binary(n, SMALL_ASSET_BYTES)) }
        return db.conversations().single { it.id == ConversationId("p-c-0") }
    }

    /** Calibrate only real attachment bytes/ID, never append padding or rewrite the export envelope. */
    fun buildPackage(db: ConversationDatabase, backup: NeutralBackupService, output: File): Package {
        check(!output.exists())
        var previousId: String? = null
        var lastSize = 1L
        fun replace(id: String, bytes: ByteArray) {
            DatabaseTransactions { db.writableDatabase }.run { sql ->
                previousId?.let { check(sql.delete("neutral_assets", "id=?", arrayOf(it)) == 1) }
                backup.attach(id, "p-u-99", "application/octet-stream", bytes)
            }
            previousId = id
        }
        repeat(12) {
            require(lastSize in 1..Int.MAX_VALUE.toLong())
            require(99L * SMALL_ASSET_BYTES + lastSize <= NeutralBackupService.MAX_BYTES)
            val bytes = binary(99, lastSize.toInt())
            replace(LAST_ASSET, bytes)
            var text = backup.exportPackage()
            val gap = PACKAGE_BYTES - utf8Size(text)
            if (gap in 0L..(200 - LAST_ASSET.length).toLong()) {
                if (gap != 0L) {
                    replace(LAST_ASSET + "x".repeat(gap.toInt()), bytes)
                    text = backup.exportPackage()
                }
                check(utf8Size(text) == PACKAGE_BYTES)
                val inventory = backup.preflight(text)
                check(inventory.conversations == 1000 && inventory.messages == 10000 && inventory.attachments == 100)
                check(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                val hash = MessageDigest.getInstance("SHA-256")
                FileOutputStream(output).use { stream ->
                    OutputStreamWriter(DigestOutputStream(stream, hash), Charsets.UTF_8).use { writer ->
                        writeUtf8(writer, text); writer.flush(); stream.fd.sync()
                    }
                }
                check(output.length() == PACKAGE_BYTES)
                return Package(output, hash.digest().joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                })
            }
            lastSize = if (gap > 0L) lastSize + maxOf(1L, (gap - 64L) * 3L / 4L)
                else lastSize - maxOf(3L, ((-gap) * 3L + 3L) / 4L + 3L)
        }
        error("exact_package_size_not_converged")
    }

    fun snapshot(db: ConversationDatabase, backup: NeutralBackupService): Snapshot {
        val conversations = db.conversations().sortedBy { it.id.value }
        check(conversations.size == 1000)
        var messages = 0; var textBytes = 0L; var tools = 0
        conversations.forEach { conversation ->
            var before = Long.MAX_VALUE
            while (true) {
                val page = db.messages(conversation.id, before = before, limit = 200)
                if (page.isEmpty()) break
                page.forEach { (_, message) ->
                    check(message.status == RunStatus.COMPLETED && message.historySegments.isEmpty())
                    messages++
                    message.blocks.forEach { block -> when (block) {
                        is MessageBlock.Text -> textBytes += utf8Size(block.text)
                        is MessageBlock.Tool -> { check(block.result != null); tools++ }
                        is MessageBlock.Image -> error("unexpected_fixture_image")
                    } }
                }
                val next = page.first().first
                check(next < before); before = next
            }
        }
        check(messages == 10000 && textBytes == TEXT_BYTES && tools == 500)
        data class Asset(val id: String, val message: String, val kind: String,
            val mime: String, val bytes: Long, val hash: String)
        val assets = db.readableDatabase.rawQuery(
            "SELECT id,message_id,kind,mime,bytes,blob_hash FROM neutral_assets ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) {
                check(!c.isNull(1))
                add(Asset(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5)))
            } }
        }
        check(assets.size == 100 && assets.all { it.kind == "attachment" })
        check(assets.map { it.hash }.toSet().size == 100)
        val encoded = JSONArray()
        assets.forEach { asset ->
            val bytes = backup.attachment(asset.id)
            check(bytes.size.toLong() == asset.bytes && PrivateBlobs.digest(bytes) == asset.hash)
            encoded.put(JSONObject().put("id", asset.id).put("message_id", asset.message).put("kind", asset.kind)
                .put("mime", asset.mime).put("bytes", asset.bytes).put("sha256", asset.hash))
        }
        val manifest = JSONObject().put("schema", "daiyu.performance.dataset.manifest.v1")
            .put("mb_definition", "decimal_1000000_bytes").put("conversations", 1000).put("messages", messages)
            .put("text_bytes", textBytes).put("text_unit", "UTF-8 bytes of MessageBlock.Text.text")
            .put("tool_steps", tools).put("tool_step_definition", "completed_persisted_tool_block")
            .put("text_backup_sha256", PrivateBlobs.digest(db.exportBackup().toByteArray(Charsets.UTF_8)))
            .put("blob_bytes", assets.sumOf { it.bytes }).put("attachments", encoded).toString()
        return Snapshot(PrivateBlobs.digest(manifest.toByteArray(Charsets.UTF_8)), manifest)
    }

    /** Measures backend read+import commit only. UI progress and SAF are separate, unmeasured endpoints. */
    fun migrate(context: Context, db: ConversationDatabase, source: Package, expected: Snapshot): JSONObject {
        check(db.conversations().isEmpty())
        var readBytes = 0L
        val start = System.nanoTime()
        val result = source.file.inputStream().use { input ->
            val counted = object : java.io.FilterInputStream(input) {
                override fun read(): Int = input.read().also { if (it >= 0) readBytes++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also {
                    if (it > 0) readBytes += it
                }
            }
            NeutralBackupService(context, db).importPackage(counted)
        }
        val committed = System.nanoTime()
        check(readBytes == PACKAGE_BYTES && !result.alreadyImported)
        db.close()
        val actual = ConversationDatabase(context).use { reopened ->
            snapshot(reopened, NeutralBackupService(context, reopened))
        }
        check(actual == expected) { "restored_dataset_not_identical" }
        return JSONObject().put("type", "backend_migration_sample").put("clock", "System.nanoTime")
            .put("start_ns", start).put("commit_returned_ns", committed).put("duration_ns", committed - start)
            .put("bytes_read", readBytes).put("package_sha256", source.sha256).put("dataset_sha256", actual.sha256)
            .put("database_reopened", true).put("integrity_verified", true)
            .put("ui_progress_measured", false).put("saf_measured", false)
            .put("business_acceptance", false).put("collection_authenticated", false)
    }
}
