package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.storage.ConversationCompletionMarkers.Receipt
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CompletionReadCompositionTest {
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
        val runner = scope.launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        suspend fun settle() = withTimeout(5_000) {
            Snapshot.sendApplyNotifications()
            do {
                yield()
                if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                yield()
            } while (recomposer.hasPendingWork || clock.hasAwaiters)
        }
        suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) { settle(); delay(1) }
            settle()
        }
        suspend fun close() { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }
    private fun owner(generation: Long = 1, revision: Long = 0) =
        CompletionReadOwner(ConversationId("c"), generation, revision, setOf("m" to "r"))
    private fun receipt(message: String = "m", token: String = "event") = Receipt("c", message, "r", token)

    @Test fun noTerminalDoesNotReadAndThoughtAppendsDoNotRequeryHistoricalTerminal() = runBlocking {
        val host = Host(this)
        val selected = mutableStateOf<CompletionReadOwner?>(null)
        val text = mutableStateOf(0)
        val calls = AtomicInteger()
        val stored = AtomicReference(receipt())
        val compositionThread = Thread.currentThread()
        var result: MutableState<Receipt?>? = null
        try {
            host.composition.setContent {
                check(text.value >= 0)
                result = rememberCompletionReceipt(selected.value) {
                    assertNotSame("SQLite readers must not run in composition", compositionThread, Thread.currentThread())
                    calls.incrementAndGet()
                    stored.get()
                }
            }
            host.settle()
            assertEquals(0, calls.get())
            selected.value = owner()
            host.until { result?.value != null }
            assertEquals(1, calls.get())
            repeat(100) {
                text.value++
                selected.value = owner().copy(completed = setOf("m" to "r"))
                host.settle()
            }
            assertEquals(1, calls.get())
            stored.set(receipt("m2", "second"))
            selected.value = owner().copy(completed = setOf("m" to "r", "m2" to "r"))
            host.until { result?.value?.token == "second" }
            assertEquals("Same visit/revision must observe a newly completed owner", 2, calls.get())
            result!!.value = null
            text.value++
            host.settle()
            assertNull(result!!.value)
            assertEquals(2, calls.get())
        } finally { host.close() }
    }

    @Test fun staleUninterruptibleReadCannotPublishAcrossRestoreOrReentry() = runBlocking {
        val host = Host(this)
        val selected = mutableStateOf<CompletionReadOwner?>(owner())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        var result: MutableState<Receipt?>? = null
        val observed = mutableListOf<Receipt?>()
        try {
            host.composition.setContent {
                result = rememberCompletionReceipt(selected.value) {
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        returned.complete(Unit)
                        receipt(token = "old-token")
                    } else receipt(token = "fresh-token")
                }
                observed += result!!.value
            }
            host.until { entered.isCompleted }
            selected.value = null; host.settle()
            assertNull(result!!.value)
            selected.value = owner(generation = 2, revision = 1)
            host.until { result?.value?.token == "fresh-token" }
            release.complete(Unit)
            host.until { returned.isCompleted }
            assertEquals("fresh-token", result!!.value!!.token)
            assertFalse(observed.any { it?.token == "old-token" })
            assertEquals(2, calls.get())
        } finally { release.complete(Unit); host.close() }
    }

    @Test fun invalidReceiptAndReadFailureRemainUnreadAndSurfaceReentryCanRetry() = runBlocking {
        val host = Host(this)
        val selected = mutableStateOf<CompletionReadOwner?>(owner())
        val calls = AtomicInteger()
        var result: MutableState<Receipt?>? = null
        try {
            host.composition.setContent {
                result = rememberCompletionReceipt(selected.value) {
                    when (calls.incrementAndGet()) {
                        1 -> receipt("different-owner")
                        2 -> throw java.io.IOException("fixture read failed")
                        else -> receipt()
                    }
                }
            }
            host.until { calls.get() == 1 }; host.settle()
            assertNull(result!!.value)
            selected.value = null; host.settle()
            selected.value = owner(); host.until { calls.get() == 2 }; host.settle()
            assertNull(result!!.value)
            selected.value = null; host.settle()
            selected.value = owner(); host.until { result?.value != null }
            assertEquals(3, calls.get())
        } finally { host.close() }
    }
}
