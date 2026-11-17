package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import app.daiyu.android.ui.navigation.LocalPageContentActive
import app.daiyu.android.ui.navigation.LocalPageInputOwned

internal val LocalChatPresentationActive = compositionLocalOf { true }

/** Instrumentation can delay a ready page, never grant production readiness. */
internal object ChatPageHostProbe {
    class Observer(
        val allowReady: (Any?) -> Boolean = { true },
        val onDraw: (Any?, Any, Any) -> Unit = { _, _, _ -> },
        val onPlaced: (Any?, IntSize) -> Unit = { _, _ -> },
        val onDisposed: (Any) -> Unit = {},
    )
    var observer: Observer? by mutableStateOf(null)
    var drawEpoch by mutableIntStateOf(0)
}

private typealias PageContent = @Composable (Boolean, (Boolean) -> Unit) -> Unit
private class ChatPageVisit(val owner: Any?, var content: PageContent) {
    var ready by mutableStateOf(false)
    var placed by mutableStateOf(false)
}
private class PageFrame { var recorded = false }

/** Keep the old live composition until the latest whole page is positioned and formatted. */
@Composable
internal fun ChatPageHost(owner: Any?, modifier: Modifier = Modifier, content: PageContent) {
    val active = LocalPageContentActive.current
    val parentInputOwned = LocalPageInputOwned.current
    val parentAuthorized = LocalChatPresentationActive.current
    val rootToken = remember { Any() }
    var committed by remember { mutableStateOf<ChatPageVisit?>(null) }
    val candidate = remember(owner) { ChatPageVisit(owner, content) }
    val front = committed
    val wanted = front?.takeIf { it.owner == owner } ?: candidate
    val currentWanted by rememberUpdatedState(wanted)
    SideEffect { wanted.content = content }
    val allowed = wanted.ready && (ChatPageHostProbe.observer?.allowReady?.invoke(owner) ?: true)
    LaunchedEffect(wanted, allowed, wanted.placed, active) {
        if (!active || !allowed || !wanted.placed || wanted === committed) return@LaunchedEffect
        withFrameNanos { }
        if (currentWanted === wanted && wanted.ready && wanted.placed &&
            (ChatPageHostProbe.observer?.allowReady?.invoke(owner) ?: true)) committed = wanted
    }
    val visits = if (front != null && front !== wanted) listOf(front, wanted) else listOf(wanted)
    Layout(modifier = modifier.fillMaxSize().clipToBounds(), content = {
        visits.forEach { visit -> key(visit) {
            val drawn = visit === front
            val requested = visit === wanted
            val authorized = drawn && requested && active && parentAuthorized
            val inputOwned = drawn && requested && parentInputOwned
            val body = if (requested) content else visit.content
            val report = remember(visit) { { value: Boolean -> visit.ready = value } }
            val layer = rememberGraphicsLayer()
            val frame = remember { PageFrame() }
            DisposableEffect(visit) { onDispose { ChatPageHostProbe.observer?.onDisposed?.invoke(visit) } }
            CompositionLocalProvider(LocalChatPresentationActive provides authorized, LocalPageInputOwned provides inputOwned) {
                Box(Modifier.fillMaxSize().onPlaced {
                    visit.placed = true
                    ChatPageHostProbe.observer?.onPlaced?.invoke(visit.owner, it.size)
                }.focusProperties { canFocus = inputOwned }.onPreviewKeyEvent { !authorized }
                    .then(if (authorized) Modifier else Modifier.clearAndSetSemantics {})
                    .pointerInput(authorized) {
                        if (!authorized) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }.drawWithContent {
                        @Suppress("UNUSED_VARIABLE") val epoch = ChatPageHostProbe.drawEpoch
                        if (drawn) {
                            if (requested || !frame.recorded) {
                                layer.record { this@drawWithContent.drawContent() }
                                frame.recorded = true
                            }
                            if (frame.recorded) {
                                drawLayer(layer)
                                ChatPageHostProbe.observer?.onDraw?.invoke(visit.owner, rootToken, visit)
                            }
                        }
                    }) { body(authorized, report) }
            }
        } }
    }) { measurables, constraints ->
        require(constraints.hasBoundedWidth && constraints.hasBoundedHeight)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val pages = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) {
            pages.indices.filter { visits[it] !== front }.forEach { pages[it].placeRelative(0, 0) }
            pages.indices.filter { visits[it] === front }.forEach { pages[it].placeRelative(0, 0) }
        }
    }
}
