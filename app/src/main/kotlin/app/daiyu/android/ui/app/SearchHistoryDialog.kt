package app.daiyu.android.ui.app

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.ui.haptics.TouchHaptics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.R
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.screens.assistants.AssistantSearchField
import app.daiyu.android.ui.screens.search.MessageSearchPageReader
import app.daiyu.android.ui.screens.search.MessageSearchRequest
import app.daiyu.android.ui.screens.search.MessageSearchRequestState
import app.daiyu.android.ui.screens.search.SearchAppendPhase
import app.daiyu.android.ui.screens.search.SearchLoading
import app.daiyu.android.ui.screens.search.SearchNotice
import app.daiyu.android.ui.screens.search.SearchPhase
import app.daiyu.android.ui.screens.search.SearchReadError
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class DisplayedHistoryResults(
    val rows: List<MessageSearchHit>,
    val appendPhase: SearchAppendPhase,
    val hasMore: Boolean,
)

@Composable
internal fun SearchHistoryDialog(
    conversationId: String?,
    onSearchPage: MessageSearchPageReader,
    onOpenHit: (MessageSearchHit) -> Unit,
    onDismiss: () -> Unit,
    ownerCurrent: () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val feedbackView = LocalView.current
    val requests = remember(scope) { MessageSearchRequestState(scope) }
    val snapshot = requests.state.collectAsState().value
    val latestSearch by rememberUpdatedState(onSearchPage)
    val currentOwner by rememberUpdatedState(ownerCurrent)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var query by remember(conversationId) { mutableStateOf("") }
    var searchFocused by remember(conversationId) { mutableStateOf(false) }
    val request = MessageSearchRequest(query.trim(), conversationId)
    val listState = remember(request) { LazyListState() }
    val loadingListState = rememberLazyListState()
    var visit by remember(lifecycle) { mutableStateOf(Any()) }
    val renderedVisit = visit
    var searchedQuery by remember(conversationId) { mutableStateOf<String?>(null) }

    fun available() = visit === renderedVisit && currentOwner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun search() {
        if (available() && conversationId != null && request.query.isNotEmpty()) {
            requests.start(request, debounceMillis = 180, read = latestSearch)
        } else requests.cancel()
    }
    DisposableEffect(requests, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            val nextResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !nextResumed) {
                visit = Any()
            }
            resumed = nextResumed
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); requests.cancel() }
    }
    LaunchedEffect(request, query, resumed) {
        if (available() && searchedQuery != query) {
            searchedQuery = query
            search()
        }
    }
    val phase = if (snapshot.request == request) snapshot.phase else SearchPhase.Idle
    val renderFrame = if (phase == SearchPhase.Results) DisplayedHistoryResults(
        snapshot.results, snapshot.appendPhase, snapshot.nextCursor != null,
    ) else null
    val renderReadOnly = !resumed
    val useResultsPosition = renderFrame != null
    val showInitialLoading = request.query.isNotBlank() && conversationId != null &&
        (phase == SearchPhase.Idle || phase == SearchPhase.Loading)
    fun requestCurrent(): Boolean {
        val now = requests.state.value
        return available() && now.generation == snapshot.generation &&
            now.request == request && snapshot.request == request
    }
    fun retrySearch() { if (requestCurrent()) search() }
    fun retryPage() { if (requestCurrent()) requests.loadMore() }
    fun cancelSearch() { if (requestCurrent()) requests.cancel() }
    val exit = { if (available()) { requests.cancel(); onDismiss() } }

    UsageGlassDialog(title = stringResource(R.string.action_search_history), onDismiss = exit, useDefaultHeight = true) {
        Column(Modifier.fillMaxWidth().weight(1f).testTag("search-history-dialog")) {
            AssistantSearchField(query, onValueChange = {
                if (available() && query != it.take(1000)) {
                    val next = it.take(1000)
                    requests.cancel()
                    query = next
                }
            }, placeholder = stringResource(R.string.search_history_hint),
                focused = searchFocused, onFocusChanged = { searchFocused = it },
                modifier = Modifier.padding(bottom = 8.dp).testTag("search-history-search"),
                fieldModifier = Modifier.testTag("search:query"))
            Box(Modifier.fillMaxWidth().weight(1f).testTag("search-history-content")) {
                LazyColumn(Modifier.fillMaxSize()
                    .testTag("search-history-results"),
                    state = if (useResultsPosition) listState else loadingListState) {
                    when {
                        request.query.isBlank() -> Unit
                        conversationId == null -> item("empty-draft") {
                            SearchNotice(stringResource(R.string.search_history_no_results), "search:empty")
                        }
                        phase == SearchPhase.ReadError -> item("error") { SearchReadError { retrySearch() } }
                        phase == SearchPhase.Results && snapshot.results.isEmpty() -> item("empty") {
                            SearchNotice(stringResource(R.string.search_history_no_results), "search:empty")
                        }
                        renderFrame != null -> {
                            itemsIndexed(renderFrame.rows,
                                key = { index, hit -> "${hit.messageId.length}:${hit.messageId}:${hit.segmentIndex}:$index" },
                            ) { _, hit ->
                                val generation = snapshot.generation
                                val openHit: () -> Unit = {
                                    val now = requests.state.value
                                    if (!renderReadOnly && available() && now.generation == generation && now.request == request &&
                                        now.phase == SearchPhase.Results && hit.conversationId == conversationId) {
                                        TouchHaptics.action(feedbackView) { onOpenHit(hit) }
                                    }
                                }
                                Column(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                    .testTag("search:message:${hit.conversationId}:${hit.messageId}:${hit.segmentIndex}")
                                    .then(if (!renderReadOnly) Modifier
                                        .semantics { semanticsClick { openHit(); true } }
                                        .clickable(clickFeedback = false, onClick = openHit) else Modifier)
                                    .padding(vertical = 10.dp, horizontal = 4.dp)) {
                                    Text("${hit.roleLabel} · ${hit.snippet}", fontSize = 14.sp, lineHeight = 21.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider()
                            }
                            item("continuation") {
                                when (renderFrame.appendPhase) {
                                    SearchAppendPhase.Loading -> SearchLoading(enabled = !renderReadOnly) { if (!renderReadOnly) cancelSearch() }
                                    SearchAppendPhase.ReadError -> SearchReadError(enabled = !renderReadOnly) { if (!renderReadOnly) retryPage() }
                                    SearchAppendPhase.Invalidated -> {
                                        SearchNotice(stringResource(R.string.search_history_changed), "search:history_changed")
                                        TextButton(text = stringResource(R.string.search_refresh), enabled = !renderReadOnly,
                                            onClick = { if (!renderReadOnly) retrySearch() })
                                    }
                                    SearchAppendPhase.Cancelled, SearchAppendPhase.Idle -> if (renderFrame.hasMore) {
                                        val generation = snapshot.generation
                                        val loadNextPage: () -> Unit = {
                                            val now = requests.state.value
                                            if (!renderReadOnly && available() && now.generation == generation && now.request == request &&
                                                now.phase == SearchPhase.Results && now.nextCursor != null) {
                                                TouchHaptics.action(feedbackView) { requests.loadMore() }
                                            }
                                        }
                                        TextButton(text = stringResource(R.string.search_load_more), enabled = !renderReadOnly,
                                            modifier = Modifier.testTag("search:more").then(if (!renderReadOnly) Modifier.semantics {
                                                semanticsClick { loadNextPage(); true }
                                            } else Modifier), onClick = loadNextPage)
                                    }
                                }
                            }
                        }
                        phase == SearchPhase.Cancelled -> item("cancelled") {
                            SearchNotice(stringResource(R.string.search_cancelled))
                            TextButton(text = stringResource(R.string.search_retry), onClick = { retrySearch() })
                        }
                    }
                }
                if (showInitialLoading) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        SearchHistoryLoading { cancelSearch() }
                    }
                }

            }
        }
    }
}

@Composable
private fun SearchHistoryLoading(onCancel: () -> Unit) {
    Column(Modifier.testTag("search:loading-content"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp).testTag("search:loading"))
            Text(text = stringResource(R.string.search_loading),
                modifier = Modifier.padding(start = 12.dp), style = MiuixTheme.textStyles.body2)
        }
        TextButton(text = stringResource(R.string.search_cancel), onClick = onCancel,
            modifier = Modifier.padding(top = 8.dp).testTag("search:cancel"))
    }
}
