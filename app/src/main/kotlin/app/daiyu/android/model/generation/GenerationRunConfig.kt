package app.daiyu.android.model.generation

import java.security.MessageDigest
import java.util.Collections

/** Parent-owned generation configuration. This is NOT inherited from chat/model settings.
 * The model can select only a frozen batch ID, never an endpoint, credential or new prompt.
 * Construct before approval and retain the same instance throughout the run and recovery.
 */
class GenerationRunConfig(batches: List<Batch>, val maxPolls: Int = 4) {
    class Item(
        val id: String,
        val prompt: String,
        references: List<GenerationInput> = emptyList(),
        val mask: GenerationInput? = null,
        parameters: Map<String, String> = emptyMap(),
    ) {
        val references: List<GenerationInput> = Collections.unmodifiableList(references.toList())
        val parameters: Map<String, String> = Collections.unmodifiableMap(parameters.toMap())
        init { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
        override fun toString() = "GenerationRunConfig.Item(<redacted>)"
    }

    class Batch(
        val id: String,
        val protocol: GenerationProtocol,
        val endpoint: String,
        private val apiKey: String,
        val model: String,
        items: List<Item>,
    ) {
        val items: List<Item> = Collections.unmodifiableList(items.toList())
        init {
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            require(this.items.size in 1..32 && this.items.map { it.id }.distinct().size == this.items.size)
        }

        fun requests(identity: (String) -> GenerationIdentity): List<GenerationRequest> = items.map {
            GenerationRequest(identity(it.id), protocol, endpoint, apiKey, model, it.prompt,
                it.references, it.mask, it.parameters)
        }

        /** Complete, length-delimited desired change, without exposing credentials/media in a ledger.
         * RequestPlan still performs the authoritative protocol/media preflight on the worker.
         */
        val fingerprint: String = MessageDigest.getInstance("SHA-256").run {
            fun field(bytes: ByteArray) {
                update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes)
            }
            fun text(value: String) = field(value.toByteArray(Charsets.UTF_8))
            text("generation-config-v1"); text(id); text(protocol.name); text(endpoint); text(apiKey); text(model)
            text(this@Batch.items.size.toString())
            this@Batch.items.forEach { item ->
                text(item.id); text(item.prompt); text(item.references.size.toString())
                item.references.forEach { text(it.mimeType); field(it.bytes) }
                text(if (item.mask == null) "no-mask" else "mask")
                item.mask?.let { text(it.mimeType); field(it.bytes) }
                text(item.parameters.size.toString())
                item.parameters.toSortedMap().forEach { (key, value) -> text(key); text(value) }
            }
            digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        override fun toString() = "GenerationRunConfig.Batch(<redacted>)"
    }

    val batches: List<Batch> = Collections.unmodifiableList(batches.toList())
    init {
        require(maxPolls in 0..32)
        require(this.batches.isNotEmpty() && this.batches.map { it.id }.distinct().size == this.batches.size)
    }
    /** Persistent value identity of the complete frozen config, independent of object/call IDs.
     * Retain these exact values on recovery. Credentials/media are represented only by digests.
     */
    val fingerprint: String = MessageDigest.getInstance("SHA-256").run {
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes)
        }
        text("generation-run-config-v1"); text(maxPolls.toString()); text(this@GenerationRunConfig.batches.size.toString())
        this@GenerationRunConfig.batches.forEach { text(it.fingerprint) }
        digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun toString() = "GenerationRunConfig(<redacted>)"
}
