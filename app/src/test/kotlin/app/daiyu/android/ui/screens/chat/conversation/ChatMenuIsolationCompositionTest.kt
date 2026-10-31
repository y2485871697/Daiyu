package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
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
}
