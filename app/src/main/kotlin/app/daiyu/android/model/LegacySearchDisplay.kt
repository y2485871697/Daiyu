package app.daiyu.android.model

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import org.json.JSONObject

/** Display-only recovery. The exact old projection is the witness; source history is never edited. */
internal object LegacySearchDisplay {
    fun candidate(text: String): Boolean = '\u2060' in text ||
        text.startsWith(" [1](<") && "\n\n来源：\n\n" in text

    fun project(role: String, blocks: List<MessageBlock>, protocol: List<String>): List<MessageBlock> {
        if (role != Message.ASSISTANT_ROLE || blocks.none { it is MessageBlock.Text && candidate(it.text) }) return blocks
        val items = mutableMapOf<String, JSONObject>()
        val duplicates = mutableSetOf<String>()
        for (raw in protocol) {
            val item = try { JSONObject(raw) } catch (_: Exception) { return blocks }
            if (item.optString("type") != "message" || item.optString("role") != Message.ASSISTANT_ROLE) continue
            val id = item.optString("id")
            if (items.put(id, item) != null) duplicates += id
        }
        return blocks.map { block ->
            if (block !is MessageBlock.Text || !candidate(block.text) || block.itemId in duplicates) block else {
                val item = items[block.itemId] ?: return@map block
                try {
                    val content = item.getJSONArray("content")
                    val plain = buildString {
                        repeat(content.length()) { index ->
                            val part = content.getJSONObject(index)
                            append(when (part.getString("type")) {
                                "output_text" -> part.getString("text")
                                "refusal" -> part.getString("refusal")
                                else -> return@map block
                            })
                        }
                    }
                    if (ResponsesSearchProtocol.legacyDisplayText(item, plain) != block.text) block
                    else block.copy(text = ResponsesSearchProtocol.displayText(item, plain))
                } catch (_: Exception) { block }
            }
        }
    }

    private class Key(value: MessageBlock.Text, queue: ReferenceQueue<MessageBlock.Text>? = null) :
        WeakReference<MessageBlock.Text>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }
    private val collected = ReferenceQueue<MessageBlock.Text>()
    private val prepared = mutableMapOf<Key, MessageBlock.Text>()
    private fun prune() {
        while (true) {
            val key = collected.poll() as? Key ?: break
            prepared.remove(key)
        }
    }

    /** Called on the existing history loading worker, never from a mapper or a Composable. */
    fun prepare(message: Message) {
        fun segment(role: String, blocks: List<MessageBlock>, protocol: List<String>) {
            val projected = project(role, blocks, protocol)
            if (projected === blocks) return
            synchronized(prepared) {
                prune()
                blocks.zip(projected).forEach { (original, display) ->
                    if (original is MessageBlock.Text && display is MessageBlock.Text && display !== original)
                        prepared[Key(original, collected)] = display
                }
            }
        }
        segment(message.role, message.blocks, message.protocolItems)
        message.historySegments.forEach { segment(it.role, it.blocks, it.protocolItems) }
    }

    /** Identity lookup only; no JSON, parsing, hashing of source text or SQL on the UI. */
    fun display(block: MessageBlock): MessageBlock = if (block !is MessageBlock.Text) block else synchronized(prepared) {
        prune()
        prepared[Key(block)] ?: block
    }
}
