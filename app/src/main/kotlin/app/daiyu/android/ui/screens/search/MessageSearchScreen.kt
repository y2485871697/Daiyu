package app.daiyu.android.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.daiyu.android.ui.model.MessageSearchHit
import top.yukonga.miuix.kmp.basic.TextButton

internal data class MessageSearchRequest(val query: String, val conversationId: String? = null)

/**
 * P27 read-only message search. Pass each complete hit, including ownerMessageId, back to the host;
 * navigation/window loading belongs to the host, never to text matching in this screen.
 *
 * onSearch directly accepts RetainedConversationData.search. Its current cap is 200. The optional
 * current-conversation reader must scope BEFORE searching/limiting: filtering a capped global
 * result is NOT a valid implementation, so that scope is unavailable until such a reader exists.
 * Readers run on IO; capture any mutable host inputs before supplying them.
 */
@Composable
internal fun MessageSearchScreen(
    onSearch: suspend (String) -> List<MessageSearchHit>,
    onOpenHit: (MessageSearchHit) -> Unit,
    onBack: () -> Unit,
    currentConversationId: String? = null,
    onSearchCurrentConversation: (suspend (query: String, conversationId: String) -> List<MessageSearchHit>)? = null,
    resultLimit: Int? = 200,
) {
    val scope = rememberCoroutineScope()
    val requests = remember(scope) { SearchRequestState<MessageSearchRequest, MessageSearchHit>(scope) }
    val snapshot by requests.state.collectAsState()
    val latestSearch by rememberUpdatedState(onSearch)
    val latestCurrentSearch by rememberUpdatedState(onSearchCurrentConversation)
    var query by rememberSaveable { mutableStateOf("") }
    var searchCurrent by rememberSaveable { mutableStateOf(false) }
    val canSearchCurrent = currentConversationId != null && onSearchCurrentConversation != null
    val request = MessageSearchRequest(query.trim(), currentConversationId.takeIf { searchCurrent && canSearchCurrent })

    fun search() {
        if (request.query.isBlank()) {
            requests.cancel()
            return
        }
        val readAll = latestSearch
        val readCurrent = latestCurrentSearch
        requests.start(request, debounceMillis = 180) { input ->
            if (input.conversationId == null) readAll(input.query)
            else checkNotNull(readCurrent)(input.query, input.conversationId)
        }
    }
    LaunchedEffect(request, query, canSearchCurrent) { search() }
    DisposableEffect(requests) { onDispose { requests.cancel() } }
    // Also gate rendering while an input/scope change waits for its LaunchedEffect.
    val phase = if (snapshot.request == request) snapshot.phase else SearchPhase.Idle
    val exit = { requests.cancel(); onBack() }
    BackHandler(onBack = exit)

    MiuixScaffoldPage(title = stringResource(R.string.search_messages_title), onBack = exit) {
        item(key = "search_query") {
            SearchQueryField(query, stringResource(R.string.search_messages_hint)) {
                if (query != it) {
                    requests.cancel() // Invalidate synchronously, not after recomposition/debounce.
                    query = it
                }
            }
        }
        item(key = "search_scope") {
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(
                    text = stringResource(if (request.conversationId == null) R.string.search_all_records_selected else R.string.search_all_records),
                    onClick = {
                        if (request.conversationId != null) requests.cancel()
                        searchCurrent = false
                    },
                )
                TextButton(
                    text = stringResource(if (request.conversationId != null) R.string.search_current_selected else R.string.search_current),
                    enabled = canSearchCurrent,
                    onClick = { if (!searchCurrent) { requests.cancel(); searchCurrent = true } },
                )
            }
            if (!canSearchCurrent) SearchNotice(stringResource(R.string.search_current_unavailable))
        }
        when {
            request.query.isBlank() -> item(key = "search_prompt") {
                SearchNotice(stringResource(R.string.search_enter_query), "search:prompt")
            }
            phase == SearchPhase.Idle || phase == SearchPhase.Loading -> item(key = "search_loading") {
                SearchLoading { requests.cancel() }
            }
            phase == SearchPhase.ReadError -> item(key = "search_error") { SearchReadError { search() } }
            phase == SearchPhase.Cancelled -> item(key = "search_cancelled") {
                SearchNotice(stringResource(R.string.search_cancelled))
                TextButton(text = stringResource(R.string.search_retry), onClick = { search() })
            }
            else -> {
                item(key = "search_result_count") {
                    SearchNotice(stringResource(R.string.search_message_count, snapshot.results.size))
                    if (resultLimit != null) SearchNotice(stringResource(R.string.search_limited_results, resultLimit))
                }
                if (snapshot.results.isEmpty()) item(key = "search_empty") {
                    SearchNotice(stringResource(R.string.search_no_results), "search:empty")
                }
                itemsIndexed(
                    snapshot.results,
                    // Length prefix avoids delimiter collisions; index also permits repeated hits.
                    key = { index, hit -> "${hit.conversationId.length}:${hit.conversationId}:${hit.messageId.length}:${hit.messageId}:$index" },
                    contentType = { _, _ -> "search_message" },
                ) { _, hit ->
                    SearchResultRow(
                        title = hit.conversationTitle,
                        detail = hit.snippet,
                        label = hit.roleLabel,
                        tag = "search:message:${hit.conversationId}:${hit.messageId}",
                        onClick = { requests.cancel(); onOpenHit(hit) },
                    )
                }
            }
        }
    }
}
