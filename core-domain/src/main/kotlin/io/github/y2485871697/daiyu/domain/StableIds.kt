package io.github.y2485871697.daiyu.domain

@JvmInline value class ConversationId(val value: String) {
    init { require(value.isNotBlank()) { "Conversation identity must not be blank" } }
}
@JvmInline value class MessageId(val value: String) {
    init { require(value.isNotBlank()) { "Message identity must not be blank" } }
}
@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) { "Run identity must not be blank" } }
}
@JvmInline value class ToolCallId(val value: String) {
    init { require(value.isNotBlank()) { "Tool call identity must not be blank" } }
}
