package app.daiyu.android.ui.screens.chat.conversation

/** First measurement is navigation/restoration, never a streaming-output animation. */
internal fun initialChatListIndex(request: ChatScrollRequest?, targetIndex: Int, tailIndex: Int): Int = when {
    targetIndex >= 0 -> targetIndex
    request == null || request.followBottom && request.messageId == null && request.listKey == null -> tailIndex.coerceAtLeast(0)
    else -> 0
}

internal fun initialChatPositionPending(request: ChatScrollRequest?, consumedSerial: Long?, followBottom: Boolean): Boolean =
    request?.let {
        it.serial != consumedSerial && it.followBottom && it.messageId == null && it.listKey == null
    } ?: followBottom

/** An empty snapshot is not a completed layout; only a measured alignment can finish it. */
internal fun retainInitialChatPosition(pending: Boolean, followBottom: Boolean, aligned: Boolean = false): Boolean =
    pending && followBottom && !aligned
