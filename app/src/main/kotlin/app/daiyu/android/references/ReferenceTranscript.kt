package app.daiyu.android.references

import app.daiyu.android.domain.*
import org.json.JSONObject
import java.io.File

/** Exports authoritative ordered history, not UI summaries or protocol replay secrets. */
internal object ReferenceTranscript {
    fun write(directory: File, history: List<Message>): Map<String, Long> {
        val lengths = mutableMapOf<String, Long>()
        val messages = MessageHistory.expand(history)
        File(directory, "index").bufferedWriter().use { index ->
            File(directory, "transcript").bufferedWriter().use { transcript ->
                messages.forEachIndexed { messageIndex, message ->
                    val heading = "[$messageIndex] ${message.role} message=${message.id.value} run=${message.runId.value} " +
                        "status=${message.status} created_at=${message.createdAt}\n"
                    index.write(heading); transcript.write(heading)
                    message.error?.let { transcript.write("[error] $it\n") }
                    message.blocks.forEachIndexed { blockIndex, block -> when (block) {
                        is MessageBlock.Text -> { transcript.write(block.text); transcript.write("\n") }
                        is MessageBlock.Image -> transcript.write("[image asset=${block.reference.id}]\n")
                        is MessageBlock.Tool -> {
                            val key = "$messageIndex-$blockIndex"
                            val record = JSONObject().put("tool_key", key).put("message_id", message.id.value)
                                .put("run_id", message.runId.value).put("call_id", block.invocation.id.value)
                                .put("name", block.invocation.name).put("arguments", block.invocation.arguments)
                                .put("result", block.result ?: JSONObject.NULL)
                                .put("completeness", if (block.result == null) "result_not_recorded" else "stored_verbatim")
                                .toString() + "\n"
                            index.write(JSONObject().put("tool_key", key).put("path", File(directory, "tool-$key").absolutePath)
                                .put("name", block.invocation.name)
                                .put("call_id", block.invocation.id.value).put("chars", record.length).toString() + "\n")
                            File(directory, "tool-$key").writeText(record)
                            lengths["tool-$key"] = record.length.toLong()
                            transcript.write(record)
                        }
                    } }
                    message.reportedReasoning.forEach { transcript.write("[reported reasoning]\n${it.text}\n") }
                    transcript.write("\n")
                }
            }
        }
        listOf("index", "transcript").forEach { name ->
            lengths[name] = File(directory, name).bufferedReader().use { reader ->
                val buffer = CharArray(8192); var total = 0L
                while (true) { val n = reader.read(buffer); if (n < 0) break; total += n }; total
            }
        }
        return lengths.toMap()
    }
}
