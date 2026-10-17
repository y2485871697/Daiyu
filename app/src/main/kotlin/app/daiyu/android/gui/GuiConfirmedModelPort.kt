package app.daiyu.android.gui

import app.daiyu.android.domain.*

/** Inject the SAME confirmed bounded observation and action into the accepted user's model
 * input. Do not re-observe, rewrite the persisted draft, or allocate a second user/run identity. */
internal class GuiConfirmedModelPort(private val delegate: ModelPort, private val userId: MessageId,
    private val delivery: GuiConfirmedDelivery) : ModelPort {
    companion object {
        /** Model projection: budget before preparation; the message table keeps the original user.
         * Runner checkpoints retain the projected history for interrupted-run recovery. */
        fun project(user: Message, delivery: GuiConfirmedDelivery): Message = user.copy(blocks = user.blocks +
            MessageBlock.Text("gui_confirmed_action", delivery.modelContext()))
    }
    private fun input(history: List<Message>) = history.map { message ->
        if (message.id != userId) message else project(message, delivery)
    }
    override fun open(history: List<Message>) = delegate.open(input(history))
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = delegate.open(input(history), tools)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>) = delegate.open(input(history), definitions)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan) =
        delegate.open(input(history), definitions, images)
}
