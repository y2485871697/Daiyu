package app.daiyu.android.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import app.daiyu.android.ui.screens.assistants.AssistantPreviewField
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SearchHistoryDialog(
    conversationId: String?,
    onSearchPage: MessageSearchPageReader,
    onOpenHit: (MessageSearchHit) -> Unit,
    onDismiss: () -> Unit,
    ownerCurrent: () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val requests = remember(scope) { MessageSearchRequestState(scope) }
    val snapshot by requests.state.collectAsState()
    val latestSearch by rememberUpdatedState(onSearchPage)
    val currentOwner by rememberUpdatedState(ownerCurrent)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var query by remember(conversationId) { mutableStateOf("") }
    val request = MessageSearchRequest(query.trim(), conversationId)
    val listState = remember(conversationId, query) { LazyListState() }
    val loadingListState = rememberLazyListState()
    var visit by remember(lifecycle) { mutableStateOf(Any()) }
    val renderedVisit = visit
    var restoreCount by remember(conversationId) { mutableStateOf(0) }
    var restoring by remember(conversationId) { mutableStateOf(false) }

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
                restoreCount = maxOf(restoreCount, requests.state.value.results.size)
                restoring = restoreCount > 0
            }
            resumed = nextResumed
            if (!resumed) requests.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); requests.cancel() }
    }
    LaunchedEffect(request, query, resumed, renderedVisit) { search() }
    val phase = if (snapshot.request == request) snapshot.phase else SearchPhase.Idle
    LaunchedEffect(resumed, restoring, snapshot.generation, snapshot.phase, snapshot.results.size, snapshot.appendPhase) {
        if (resumed && restoring) {
            val now = requests.state.value
            if (now.request == request && now.phase == SearchPhase.Results && now.appendPhase == SearchAppendPhase.Idle) {
                if (now.results.size < restoreCount && now.nextCursor != null) requests.loadMore()
                else { restoring = false; restoreCount = 0 }
            }
        }
    }
    fun cancelSearch() {
        if (available()) requests.cancel()
    }
    val exit = { if (available()) { requests.cancel(); onDismiss() } }

    UsageGlassDialog(title = stringResource(R.string.action_search_history), onDismiss = exit) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("search-history-dialog")) {
            AssistantPreviewField(query, onValueChange = {
                if (available() && query != it.take(1000)) {
                    requests.cancel()
                    restoring = false; restoreCount = 0
                    query = it.take(1000)
                }
            }, label = "", placeholder = stringResource(R.string.search_history_hint),
                fieldModifier = Modifier.testTag("search:query"), minHeight = 44.dp, bottomSpacing = 8.dp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp)
                .testTag("search-history-results"),
                state = if (!resumed || restoring || phase != SearchPhase.Results) loadingListState else listState) {
                when {
                    request.query.isBlank() -> item("prompt") {
                        SearchNotice(stringResource(R.string.search_history_empty), "search:prompt")
                    }
                    conversationId == null -> item("empty-draft") {
                        SearchNotice(stringResource(R.string.search_history_no_results), "search:empty")
                    }
                    phase == SearchPhase.ReadError -> item("error") { SearchReadError { search() } }
                    phase == SearchPhase.Cancelled -> item("cancelled") {
                        SearchNotice(stringResource(R.string.search_cancelled))
                        TextButton(text = stringResource(R.string.search_retry), onClick = { search() })
                    }
                    restoring && snapshot.appendPhase == SearchAppendPhase.ReadError -> item("restore-error") {
                        SearchReadError { if (available()) requests.loadMore() }
                    }
                    restoring && snapshot.appendPhase == SearchAppendPhase.Cancelled -> item("restore-cancelled") {
                        SearchNotice(stringResource(R.string.search_cancelled))
                        TextButton(text = stringResource(R.string.search_retry), onClick = { search() })
                    }
                    restoring && snapshot.appendPhase == SearchAppendPhase.Invalidated -> item("restore-invalidated") {
                        SearchNotice(stringResource(R.string.search_history_changed), "search:history_changed")
                        TextButton(text = stringResource(R.string.search_refresh), onClick = { search() })
                    }
                    !resumed || restoring || phase == SearchPhase.Idle || phase == SearchPhase.Loading -> item("loading") {
                        SearchLoading { cancelSearch() }
                    }
                    else -> {
                        if (snapshot.results.isEmpty()) item("empty") {
                            SearchNotice(stringResource(R.string.search_history_no_results), "search:empty")
                        }
                        itemsIndexed(snapshot.results,
                            key = { index, hit -> "${hit.messageId.length}:${hit.messageId}:${hit.segmentIndex}:$index" },
                        ) { _, hit ->
                            val generation = snapshot.generation
                            Column(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .testTag("search:message:${hit.conversationId}:${hit.messageId}:${hit.segmentIndex}")
                                .clickable {
                                    val now = requests.state.value
                                    if (available() && now.generation == generation && now.request == request &&
                                        now.phase == SearchPhase.Results && hit.conversationId == conversationId) {
                                        // The host closes only after resolving the persisted owner and window.
                                        onOpenHit(hit)
                                    }
                                }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                                Text("${hit.roleLabel} · ${hit.snippet}", fontSize = 14.sp, lineHeight = 21.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider()
                        }
                        item("continuation") {
                            when (snapshot.appendPhase) {
                                SearchAppendPhase.Loading -> SearchLoading { if (available()) requests.cancel() }
                                SearchAppendPhase.ReadError -> SearchReadError { if (available()) requests.loadMore() }
                                SearchAppendPhase.Invalidated -> {
                                    SearchNotice(stringResource(R.string.search_history_changed), "search:history_changed")
                                    TextButton(text = stringResource(R.string.search_refresh), onClick = { search() })
                                }
                                SearchAppendPhase.Cancelled, SearchAppendPhase.Idle -> if (snapshot.nextCursor != null) {
                                    TextButton(text = stringResource(R.string.search_load_more),
                                        modifier = Modifier.testTag("search:more"),
                                        onClick = { if (available()) requests.loadMore() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
