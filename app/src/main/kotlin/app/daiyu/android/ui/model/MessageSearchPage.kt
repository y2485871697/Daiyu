package app.daiyu.android.ui.model

/** An in-memory continuation, not a database offset or a capability to change scope. */
internal class MessageSearchCursor internal constructor(
    internal val query: String,
    internal val conversationId: String?,
    internal val conversations: List<SearchConversationRevision>,
    internal val nextConversation: Int,
    internal val nextExpanded: Int,
)

internal data class SearchConversationRevision(val id: String, val title: String, val revision: String)

internal data class MessageSearchPage(
    val results: List<MessageSearchHit>,
    val nextCursor: MessageSearchCursor? = null,
)

/** A caller must restart, not append a new history snapshot to already displayed hits. */
internal class SearchCursorInvalidatedException : IllegalStateException("search_history_changed")
