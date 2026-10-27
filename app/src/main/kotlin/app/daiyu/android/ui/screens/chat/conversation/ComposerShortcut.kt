package app.daiyu.android.ui.screens.chat.conversation

enum class ComposerShortcutKind { CONVERSATION, SKILL }
data class ComposerShortcut(val kind: ComposerShortcutKind, val start: Int, val end: Int, val query: String) {
    companion object {
        fun afterEdit(previous: String, current: String, cursor: Int, composing: Boolean = false,
            compositionCommitted: Boolean = false): ComposerShortcut? {
            val prefix = previous.commonPrefixWith(current).length
            val suffix = previous.drop(prefix).commonSuffixWith(current.drop(prefix)).length
            if (current.length - prefix - suffix == 0 && !compositionCommitted) return null
            return at(current, cursor, composing)
        }
        fun at(text: String, cursor: Int, composing: Boolean = false): ComposerShortcut? {
            if (composing || cursor !in 1..text.length) return null
            val start = text.substring(0, cursor).indexOfLast { it.isWhitespace() } + 1
            val token = text.substring(start, cursor)
            val kind = when (token.firstOrNull()) {
                '@' -> ComposerShortcutKind.CONVERSATION
                '/' -> ComposerShortcutKind.SKILL
                else -> return null
            }
            val query = token.drop(1)
            if (query.length > 100 || query.any { it in "/:@\\" } || token.startsWith("/skill:")) return null
            return ComposerShortcut(kind, start, cursor, query)
        }
    }
}
