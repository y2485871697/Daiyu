package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host JVM + real Compose runtime, no Activity, layout, renderer or frame-time claims. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ChatMenuIsolationCompositionTest {
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Host(scope: CoroutineScope) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(scope.coroutineContext + clock)
        val runner = scope.launch(clock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(NoNodes(), recomposer)

        suspend fun settle() = withTimeout(5_000) {
            Snapshot.sendApplyNotifications()
            do {
                yield()
                if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                yield()
            } while (recomposer.hasPendingWork || clock.hasAwaiters)
        }

        suspend fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }

    private class Menu(val readState: () -> ChatAutomaticCompressionState)

    @Test fun pendingAndReadbackRecomposeOnlyTheProductionMenuLeaf() = runBlocking {
        val host = Host(this)
        val enabled = mutableStateOf<Boolean?>(false)
        val pending = mutableStateOf(false)
        val layer = ChatMenuLayerState<Menu>()
        layer.active = Menu { ChatAutomaticCompressionState(enabled.value, pending.value) }
        var pages = 0
        var leafCommits = 0
        var displayed: ChatAutomaticCompressionState? = null
        try {
            host.composition.setContent {
                // This is the same production boundary used below ChatMenuLayer's captured page.
                SideEffect { pages++ }
                ChatMenuOverlayContent(layer) { menu ->
                    ChatAutomaticCompressionContent(menu.readState) { value ->
                        SideEffect { leafCommits++; displayed = value }
                    }
                }
            }
            host.settle()
            assertEquals(1, pages)
            assertEquals(1, leafCommits)
            assertEquals(ChatAutomaticCompressionState(false, false), displayed)

            pending.value = true // onMore -> refreshAutomaticCompression, before IO returns
            host.settle()
            assertEquals(ChatAutomaticCompressionState(false, true), displayed)
            assertEquals(2, leafCommits)
            assertEquals("Pending must not rebuild the captured chat page", 1, pages)

            enabled.value = true
            pending.value = false // authoritative readback
            host.settle()
            assertEquals(ChatAutomaticCompressionState(true, false), displayed)
            assertEquals(3, leafCommits)
            assertEquals("Readback must not rebuild the captured chat page", 1, pages)

            pending.value = true
            host.settle()
            enabled.value = null // failed configuration read must still disable the toggle
            pending.value = false
            host.settle()
            assertEquals(ChatAutomaticCompressionState(null, false), displayed)
            assertEquals(5, leafCommits)
            assertEquals(1, pages)
        } finally { host.close() }
    }

    @Test fun activeOpenReplacementExitAndReopenDoNotRecomposeCapturedContent() = runBlocking {
        val host = Host(this)
        val layer = ChatMenuLayerState<Menu>()
        val first = Menu { ChatAutomaticCompressionState(false, false) }
        val second = Menu { ChatAutomaticCompressionState(true, false) }
        var pages = 0
        var mounted = 0
        var disposed = 0
        var identity: Any? = null
        try {
            host.composition.setContent {
                SideEffect { pages++ }
                ChatMenuOverlayContent(layer) { menu ->
                    val instance = remember { Any() }
                    SideEffect { identity = instance }
                    DisposableEffect(menu) {
                        mounted++
                        onDispose { disposed++ }
                    }
                }
            }
            host.settle()
            assertEquals(1, pages)
            assertEquals(0, mounted)
            layer.active = first
            host.settle()
            val firstIdentity = identity
            assertNotNull(firstIdentity)
            assertEquals(1, mounted)
            assertEquals(1, pages)

            layer.active = first // same owner does not restart its motion/content
            host.settle()
            assertSame(firstIdentity, identity)
            assertEquals(1, mounted)
            layer.active = second
            host.settle()
            assertNotSame("Replacement keeps the production owner key", firstIdentity, identity)
            assertEquals(2, mounted)
            assertEquals(1, disposed)
            assertEquals(1, pages)

            layer.active = null // disposal after the unchanged exit retention
            host.settle()
            assertEquals(2, disposed)
            layer.active = first
            host.settle()
            assertNotSame("Reopening starts a fresh entry", firstIdentity, identity)
            assertEquals(3, mounted)
            assertEquals("Opening/closing may not recompose captured content", 1, pages)
        } finally { host.close() }
    }

    @Test fun scrollSamplingInvalidatesOnlyItsObserverWhileConfigurationStaysLive() = runBlocking {
        val host = Host(this)
        val offset = mutableStateOf(0)
        val pending = mutableStateOf(false)
        val layer = ChatMenuLayerState<Menu>()
        layer.active = Menu { ChatAutomaticCompressionState(true, pending.value) }
        val observer = SnapshotStateObserver { it() }
        var sampleInvalidations = 0
        var sampled = -1
        var pages = 0
        var leaves = 0
        var displayed: ChatAutomaticCompressionState? = null
        val onSampleChanged: (String) -> Unit = { sampleInvalidations++ }
        fun sample() = observer.observeReads("scroll-draw", onSampleChanged) { sampled = offset.value }
        observer.start()
        try {
            host.composition.setContent {
                SideEffect { pages++ }
                ChatMenuOverlayContent(layer) { menu ->
                    ChatAutomaticCompressionContent(menu.readState) { value ->
                        SideEffect { leaves++; displayed = value }
                    }
                }
            }
            host.settle()
            sample()
            repeat(120) { frame ->
                offset.value = frame + 1
                host.settle()
                assertEquals(frame + 1, sampleInvalidations)
                sample()
                if (frame == 20 || frame == 80) {
                    val invalidationsBeforeConfig = sampleInvalidations
                    pending.value = frame == 20
                    host.settle()
                    assertEquals("Menu config must not invalidate scroll sampling",
                        invalidationsBeforeConfig, sampleInvalidations)
                    assertEquals(pending.value, displayed!!.pending)
                }
                assertEquals("Per-pixel sampling must not recompose the page", 1, pages)
            }
            assertEquals(120, sampled)
            assertEquals("Only initial composition and two config changes reach the leaf", 3, leaves)
            assertEquals(ChatAutomaticCompressionState(true, false), displayed)
        } finally { observer.stop(); observer.clear(); host.close() }
    }

    @Test fun closedMenuDoesNotReadConfigurationAndReopenReadsTheLatestValue() = runBlocking {
        val host = Host(this)
        val enabled = mutableStateOf<Boolean?>(false)
        val layer = ChatMenuLayerState<Menu>()
        var reads = 0
        var pages = 0
        var displayed: ChatAutomaticCompressionState? = null
        val menu = Menu { reads++; ChatAutomaticCompressionState(enabled.value, false) }
        try {
            host.composition.setContent {
                SideEffect { pages++ }
                ChatMenuOverlayContent(layer) { active ->
                    ChatAutomaticCompressionContent(active.readState) { value ->
                        SideEffect { displayed = value }
                    }
                }
            }
            host.settle()
            enabled.value = true
            host.settle()
            assertEquals(0, reads)
            layer.active = menu
            host.settle()
            assertEquals(ChatAutomaticCompressionState(true, false), displayed)
            assertEquals(1, reads)
            layer.active = null
            host.settle()
            enabled.value = null
            host.settle()
            assertEquals("Unmounted menu must unsubscribe from configuration", 1, reads)
            layer.active = menu
            host.settle()
            assertEquals(ChatAutomaticCompressionState(null, false), displayed)
            assertEquals(2, reads)
            assertEquals(1, pages)
        } finally { host.close() }
    }

    private class CaptureOwner {
        val expanded = mutableStateOf(true)
    }

    @Test fun captureFollowsMountedOwnerThroughExitReplacementAndRapidReopen() = runBlocking {
        val host = Host(this)
        val layer = ChatMenuLayerState<CaptureOwner>()
        val first = CaptureOwner()
        val second = CaptureOwner()
        var capture = false
        var expanded: Boolean? = null
        var pageCommits = 0
        var pageMounts = 0
        var pageDisposals = 0
        var pageIdentity: Any? = null
        val page: @Composable () -> Unit = {
            val identity = remember { Any() }
            SideEffect { pageCommits++; pageIdentity = identity }
            DisposableEffect(Unit) {
                pageMounts++
                onDispose { pageDisposals++ }
            }
        }
        try {
            host.composition.setContent {
                ChatMenuCaptureContent(layer) { enabled ->
                    SideEffect { capture = enabled }
                    page()
                }
                ChatMenuOverlayContent(layer) { owner ->
                    val value = owner.expanded.value
                    SideEffect { expanded = value }
                }
            }
            host.settle()
            val originalPage = pageIdentity
            assertFalse("A closed menu has no capture consumer", capture)
            assertEquals(1, pageCommits)

            layer.active = first
            host.settle()
            assertTrue(capture)
            assertEquals(true, expanded)
            first.expanded.value = false
            host.settle()
            assertEquals(false, expanded)
            assertTrue("The mounted exit still needs its background", capture)

            first.expanded.value = true // cancel exit before the owner is disposed
            host.settle()
            assertTrue(capture)
            layer.active = second // replacement is still a live consumer
            host.settle()
            assertTrue(capture)
            second.expanded.value = false
            host.settle()
            assertTrue("Replacement exit keeps capture until disposal", capture)
            layer.active = null // ChatAnchoredMenu clears ownership after exit retention
            host.settle()
            assertFalse(capture)
            layer.active = first
            host.settle()
            assertTrue("A fresh entry starts capture again", capture)
            layer.active = null
            host.settle()
            assertFalse(capture)

            assertSame("Capture changes must preserve page identity", originalPage, pageIdentity)
            assertEquals("Only the capture wrapper recomposes", 1, pageCommits)
            assertEquals(1, pageMounts)
            assertEquals(0, pageDisposals)
        } finally { host.close() }
        assertEquals(1, pageDisposals)
    }

    @Test fun nestedMenuCapturesSubscribeOnlyToTheirOwnMountedConsumer() = runBlocking {
        val host = Host(this)
        val outer = ChatMenuLayerState<CaptureOwner>()
        val inner = ChatMenuLayerState<CaptureOwner>()
        val outerOwner = CaptureOwner()
        val innerOwner = CaptureOwner()
        var outerCapture = false
        var innerCapture = false
        var pages = 0
        var identity: Any? = null
        val page: @Composable () -> Unit = {
            val instance = remember { Any() }
            SideEffect { pages++; identity = instance }
        }
        try {
            host.composition.setContent {
                ChatMenuCaptureContent(outer) { capturingOuter ->
                    SideEffect { outerCapture = capturingOuter }
                    ChatMenuCaptureContent(inner) { capturingInner ->
                        SideEffect { innerCapture = capturingInner }
                        page()
                    }
                }
            }
            host.settle()
            val originalPage = identity
            assertFalse(outerCapture)
            assertFalse(innerCapture)
            inner.active = innerOwner
            host.settle()
            assertTrue(innerCapture)
            assertFalse("Opening a chat menu must not capture the outer drawer host", outerCapture)
            outer.active = outerOwner
            host.settle()
            assertTrue(innerCapture)
            assertTrue(outerCapture)
            inner.active = null
            host.settle()
            assertFalse(innerCapture)
            assertTrue("Closing the inner menu must not stop the outer consumer", outerCapture)
            outer.active = null
            host.settle()
            assertFalse(innerCapture)
            assertFalse(outerCapture)
            assertSame(originalPage, identity)
            assertEquals("Nested capture switches must not rebuild the page", 1, pages)
        } finally { host.close() }
    }
}
