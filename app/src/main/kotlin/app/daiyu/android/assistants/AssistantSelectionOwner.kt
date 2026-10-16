package app.daiyu.android.assistants

/** A picker belongs to one editor mount, not to whichever conversation is visible later. */
internal class AssistantSelectionOwner(val conversationId: String?, val editorGeneration: Long,
    val expectedAssistantId: String?, val global: Boolean) {
    fun accepts(current: AssistantSelectionOwner?, conversationId: String?, editorGeneration: Long, visible: Boolean): Boolean =
        visible && current === this && this.conversationId == conversationId && this.editorGeneration == editorGeneration
}
