package app.daiyu.android.ui.app

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.SideEffect
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
    val request = MessageSearchRequest(query.trim(), conversationId)
    val listState = remember(request) { LazyListState() }
    val loadingListState = rememberLazyListState()
    var visit by remember(lifecycle) { mutableStateOf(Any()) }
    val renderedVisit = visit
    var lastDisplayed by remember(request) { mutableStateOf<DisplayedHistoryResults?>(null) }
    var restoreCount by remember(request) { mutableStateOf(0) }
    var restoring by remember(request) { mutableStateOf(false) }

    fun available() = visit === renderedVisit && currentOwner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun retainForRestore() {
        restoreCount = maxOf(restoreCount, lastDisplayed?.rows?.size ?: 0)
        restoring = restoreCount > 0
    }
    fun search() {
        if (available() && conversationId != null && request.query.isNotEmpty()) {
            retainForRestore()
            requests.start(request, debounceMillis = 180, read = latestSearch)
        } else requests.cancel()
    }
    DisposableEffect(requests, lifecycle, request) {
        val observer = LifecycleEventObserver { _, _ ->
            val nextResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !nextResumed) {
                visit = Any()
                retainForRestore()
            }
            resumed = nextResumed
            if (!resumed) requests.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); requests.cancel() }
    }
    LaunchedEffect(request, query, resumed, renderedVisit) { search() }
    val phase = if (snapshot.request == request) snapshot.phase else SearchPhase.Idle
    // The lazy content and its list position must use the same render version.
    val renderResumed = resumed
    val renderRestoring = restoring
    val renderRestoreCount = restoreCount
    val authoritativeTerminal = phase == SearchPhase.ReadError ||
        (phase == SearchPhase.Results && snapshot.results.isEmpty())
    val renderFresh = renderResumed && !renderRestoring && phase == SearchPhase.Results
    val freshFrame = if (renderFresh) DisplayedHistoryResults(
        snapshot.results, snapshot.appendPhase, snapshot.nextCursor != null,
    ) else null
    val retainedFrame = lastDisplayed?.takeIf {
        !authoritativeTerminal && (!renderResumed || renderRestoring)
    }
    val renderFrame = retainedFrame ?: freshFrame
    val renderReadOnly = retainedFrame != null
    val useResultsPosition = renderFrame != null
    SideEffect {
        if (authoritativeTerminal) lastDisplayed = null
        else if (renderFresh && available() && snapshot.generation == requests.state.value.generation) {
            lastDisplayed = freshFrame
        }
    }
    LaunchedEffect(request, renderResumed, renderRestoring, renderRestoreCount, snapshot) {
        if (renderResumed && renderRestoring && available() &&
            snapshot.generation == requests.state.value.generation && snapshot.request == request) {
            if (authoritativeTerminal) { restoring = false; restoreCount = 0 }
            else if (snapshot.phase == SearchPhase.Results && snapshot.appendPhase == SearchAppendPhase.Idle) {
                if (snapshot.results.size < renderRestoreCount && snapshot.nextCursor != null) requests.loadMore()
                else { restoring = false; restoreCount = 0 }
            }
        }
    }
    fun requestCurrent(): Boolean {
        val now = requests.state.value
        return available() && restoring == renderRestoring && now.generation == snapshot.generation &&
            now.request == request && snapshot.request == request
    }
    fun retrySearch() { if (requestCurrent()) search() }
    fun retryPage() { if (requestCurrent()) requests.loadMore() }
    fun cancelSearch() { if (requestCurrent()) requests.cancel() }
    val exit = { if (available()) { requests.cancel(); onDismiss() } }

    UsageGlassDialog(title = stringResource(R.string.action_search_history), onDismiss = exit) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("search-history-dialog")) {
            AssistantPreviewField(query, onValueChange = {
                if (available() && query != it.take(1000)) {
                    val next = it.take(1000)
                    if (next.trim() != request.query) {
                        lastDisplayed = null; restoring = false; restoreCount = 0
                    } else retainForRestore()
                    requests.cancel()
                    query = next
                }
            }, label = "", placeholder = stringResource(R.string.search_history_hint),
                fieldModifier = Modifier.testTag("search:query"), minHeight = 44.dp, bottomSpacing = 8.dp)
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .testTag("search-history-results"),
                    state = if (useResultsPosition) listState else loadingListState) {
                    when {
                        request.query.isBlank() -> item("prompt") {
                            SearchNotice(stringResource(R.string.search_history_empty), "search:prompt")
                        }
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
                                    if (!renderReadOnly && !restoring && available() && now.generation == generation && now.request == request &&
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
                                            if (!renderReadOnly && !restoring && available() && now.generation == generation && now.request == request &&
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
                        !renderResumed || renderRestoring || phase == SearchPhase.Idle || phase == SearchPhase.Loading -> item("loading") {
                            SearchLoading { cancelSearch() }
                        }
                    }
                }
                if (renderReadOnly && renderResumed) {
                    Box(Modifier.matchParentSize()) {
                        Column(Modifier.align(Alignment.BottomEnd).background(MiuixTheme.colorScheme.surface)) {
                            when (snapshot.appendPhase) {
                                SearchAppendPhase.ReadError -> SearchReadError { retryPage() }
                                SearchAppendPhase.Invalidated -> {
                                    SearchNotice(stringResource(R.string.search_history_changed), "search:history_changed")
                                    TextButton(text = stringResource(R.string.search_refresh), onClick = { retrySearch() })
                                }
                                SearchAppendPhase.Cancelled -> {
                                    SearchNotice(stringResource(R.string.search_cancelled))
                                    TextButton(text = stringResource(R.string.search_retry), onClick = { retrySearch() })
                                }
                                else -> if (phase == SearchPhase.Cancelled) {
                                    TextButton(text = stringResource(R.string.search_retry), onClick = { retrySearch() })
                                } else SearchLoading { cancelSearch() }
                            }
                        }
                    }
                }
            }
        }
    }
}
