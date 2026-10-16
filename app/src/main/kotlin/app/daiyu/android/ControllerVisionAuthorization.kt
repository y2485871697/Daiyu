package app.daiyu.android

import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.model.auxiliary.AuthorizedVisionObservation
import app.daiyu.android.storage.ConversationDatabase
import java.security.MessageDigest

/** Per accepted run. Check physical rows (including segments) before expanding any request.
 * The two exceptions are exact capabilities, never message-ID prefixes. */
internal class ControllerVisionAuthorization(
    private val database: ConversationDatabase,
    private val assistant: Message,
    private val summaries: List<Message>,
    private val current: () -> Boolean,
) {
    fun authorize(raw: List<Message>): List<AuthorizedVisionObservation> = authorizeRows(raw, null)

    /** Only the controller's exact not-yet-accepted user candidate is admitted in local preflight.
     * This capability is never retained by the leaf's post-accept authorization callback. */
    fun preflight(raw: List<Message>, candidate: Message) {
        require(candidate.conversationId == assistant.conversationId && candidate.runId == assistant.runId &&
            candidate.id != assistant.id && candidate.role == Message.USER_ROLE &&
            candidate.status == RunStatus.COMPLETED && candidate.error == null &&
            candidate.historySegments.isEmpty() && candidate.protocolItems.isEmpty())
        authorizeRows(raw, candidate)
    }

    private fun authorizeRows(raw: List<Message>, candidate: Message?): List<AuthorizedVisionObservation> {
        check(current()) { "auxiliary_configuration_revoked" }
        val result = DatabaseTransactions { database.readableDatabase }.run { db ->
            val conversation = assistant.conversationId
            val exists = db.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(conversation.value)).use { it.moveToFirst() }
            check(exists || candidate != null && raw.all { it == candidate }) { "auxiliary_conversation_missing" }
            val ids = db.rawQuery("SELECT id FROM messages WHERE conversation_id=? ORDER BY seq",
                arrayOf(conversation.value)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
            val physical = ids.map { database.storedMessage(db, conversation, it) }
            MessageHistory.validate(physical)
            raw.forEach { row ->
                require(row.conversationId == conversation)
                val saved = physical.singleOrNull { it.id == row.id }
                val summary = saved == null && summaries.any { it == row } &&
                    row.role == Message.USER_ROLE && row.status == RunStatus.COMPLETED && row.error == null &&
                    row.protocolItems.isEmpty() && row.historySegments.isEmpty() &&
                    row.blocks.isNotEmpty() && row.blocks.all { it is MessageBlock.Text }
                val projection = saved != null && saved.id == assistant.id && saved.runId == assistant.runId &&
                    saved.role == Message.ASSISTANT_ROLE && saved.status == RunStatus.RUNNING && saved.error == null &&
                    row == saved.copy(status = RunStatus.COMPLETED, error = null)
                check(row == saved || summary || projection || candidate != null && saved == null && row == candidate) {
                    "auxiliary_raw_row_not_authorized"
                }
            }
            // Raw equality above includes every segment, its owner and order; expansion now cannot
            // hide a forged outer row, supplement or protocol item behind an authentic image.
            MessageHistory.expand(raw).flatMap { row ->
                row.blocks.filterIsInstance<MessageBlock.Image>().mapIndexed { index, image ->
                    require(row.role == Message.USER_ROLE && row.protocolItems.isEmpty())
                    require(image.reference.owner == MessageImageOwner(conversation.value, row.id.value))
                    val identity = "${conversation.value}\u0000${row.runId.value}\u0000${row.id.value}\u0000$index\u0000${image.reference.id}"
                    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    AuthorizedVisionObservation(row.runId, "persisted-$digest", image.reference)
                }
            }
        }
        check(current()) { "auxiliary_configuration_revoked" }
        return result
    }
}
