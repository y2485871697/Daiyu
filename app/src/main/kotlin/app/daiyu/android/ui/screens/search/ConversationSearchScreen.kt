package app.daiyu.android.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.model.ConversationFolderUi
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationSummaryUi
import app.daiyu.android.ui.model.filterForFolder
import app.daiyu.android.ui.model.searchResults
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * P28: load a COMPLETE summary snapshot, then filter only title/preview using the existing rule.
 * Do not wire loadConversations to database.conversations(query): that API also searches bodies.
 * Folder null = all folders; empty string = uncategorized; otherwise the existing folder ID.
 * The loader runs on IO and must not read Compose state there: capture its inputs in the host.
 */
@Composable
internal fun ConversationSearchScreen(
    loadConversations: suspend () -> List<ConversationSummaryUi>,
    onOpenConversation: (String) -> Unit,
    onBack: () -> Unit,
    folders: List<ConversationFolderUi> = emptyList(),
    initialFolderId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val requests = remember(scope) { SearchRequestState<Unit, ConversationSummaryUi>(scope) }
    val snapshot by requests.state.collectAsState()
    val latestLoad by rememberUpdatedState(loadConversations)
    var query by rememberSaveable { mutableStateOf("") }
    var folderId by rememberSaveable { mutableStateOf(initialFolderId) }
    fun reload() {
        val read = latestLoad
        requests.start(Unit) { read() }
    }

    LaunchedEffect(requests) { reload() }
    DisposableEffect(requests) { onDispose { requests.cancel() } }

    val results = remember(snapshot.results, query, folderId) {
        conversationSearchResults(snapshot.results, query, folderId)
    }
    val exit = { requests.cancel(); onBack() }
    BackHandler(onBack = exit)
    MiuixScaffoldPage(
        title = stringResource(R.string.search_conversations_title),
        onBack = exit,
        actions = {
            TextButton(text = stringResource(R.string.search_refresh), onClick = { reload() })
        },
    ) {
        item(key = "search_query") {
            SearchQueryField(query, stringResource(R.string.search_conversations_hint)) { query = it }
        }
        item(key = "search_folders") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                FolderChoice(stringResource(R.string.search_all_folders), folderId == null) { folderId = null }
                FolderChoice(stringResource(R.string.search_uncategorized), folderId == "") { folderId = "" }
                // Keep a selected, subsequently deleted folder explicit rather than falling back to all.
                if (!folderId.isNullOrEmpty() && folders.none { it.id == folderId }) {
                    FolderChoice(folderId.orEmpty(), true) { }
                }
                folders.filter { it.id.isNotEmpty() }.forEach { folder ->
                    FolderChoice(folder.name, folderId == folder.id) { folderId = folder.id }
                }
            }
        }
        when (snapshot.phase) {
            SearchPhase.Idle, SearchPhase.Loading -> item(key = "search_loading") {
                SearchLoading { requests.cancel() }
            }
            SearchPhase.ReadError -> item(key = "search_error") { SearchReadError { reload() } }
            SearchPhase.Cancelled -> item(key = "search_cancelled") {
                SearchNotice(stringResource(R.string.search_cancelled))
                TextButton(text = stringResource(R.string.search_retry), onClick = { reload() })
            }
            SearchPhase.Results -> {
                if (results.isEmpty()) item(key = "search_empty") {
                    SearchNotice(stringResource(
                        if (query.isBlank()) R.string.search_no_conversations else R.string.search_no_results,
                    ), "search:empty")
                }
                items(results, key = { it.id }, contentType = { "search_conversation" }) { conversation ->
                    SearchResultRow(
                        title = conversation.title,
                        detail = conversation.preview,
                        label = conversation.timeLabel,
                        tag = "search:conversation:${conversation.id}",
                        onClick = { requests.cancel(); onOpenConversation(conversation.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        text = if (selected) stringResource(R.string.search_selected_scope, label) else label,
        onClick = onClick,
    )
}

/** Pure adapter to P28's canonical filter; shortening/clearing always starts from the full snapshot. */
internal fun conversationSearchResults(
    all: List<ConversationSummaryUi>,
    query: String,
    folderId: String?,
): List<ConversationSummaryUi> {
    val scoped = if (folderId == null) all else all.filterForFolder(folderId.takeUnless { it.isEmpty() })
    return ConversationPaneUiState(scoped, null, query).searchResults()
}
