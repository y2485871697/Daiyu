package app.daiyu.android.ui.screens.search

import app.daiyu.android.ui.model.ConversationModeUi
import app.daiyu.android.ui.model.ConversationSummaryUi
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSearchResultsTest {
    private fun row(id: String, title: String, preview: String = "", folder: String? = null) =
        ConversationSummaryUi(id, title, preview, "", mode = ConversationModeUi.Chat, folderId = folder)

    @Test fun matchesOnlyTitleOrPreviewIgnoringCaseAndOuterSpaces() {
        val all = listOf(
            row("title", "NEEDLE", folder = "folder"),
            row("preview", "Other", "a needle here"),
            row("needle", "Other", folder = "needle"),
        )
        assertEquals(listOf("title", "preview"), conversationSearchResults(all, " Needle ", null).map { it.id })
    }

    @Test fun shortenAndClearQueryRestoreFromCompleteSnapshot() {
        val all = listOf(row("a", "Alpha"), row("b", "Alpine"), row("c", "Other"))
        assertEquals(listOf("a"), conversationSearchResults(all, "Alpha", null).map { it.id })
        assertEquals(listOf("a", "b"), conversationSearchResults(all, "Al", null).map { it.id })
        assertEquals(all, conversationSearchResults(all, "  ", null))
    }

    @Test fun folderScopeDistinguishesAllUncategorizedAndExactFolder() {
        val all = listOf(row("a", "Match"), row("b", "Match", folder = "work"), row("c", "Match", folder = ""))
        assertEquals(listOf("a", "b", "c"), conversationSearchResults(all, "match", null).map { it.id })
        assertEquals(listOf("a", "c"), conversationSearchResults(all, "match", "").map { it.id })
        assertEquals(listOf("b"), conversationSearchResults(all, "match", "work").map { it.id })
        assertEquals(emptyList<ConversationSummaryUi>(), conversationSearchResults(all, "", "deleted-folder"))
    }
}
