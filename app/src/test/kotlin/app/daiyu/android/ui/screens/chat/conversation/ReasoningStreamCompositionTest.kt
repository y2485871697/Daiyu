package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.ui.model.CloudContextUsageUi
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ReasoningStreamCompositionTest {
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
    private class Host(val scope: TestScope) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(scope.coroutineContext + clock)
        val runner = scope.launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        fun settle() {
            Snapshot.sendApplyNotifications()
            repeat(20) {
                scope.runCurrent()
                if (clock.hasAwaiters) clock.sendFrame(scope.testScheduler.currentTime * 1_000_000)
                scope.runCurrent()
                if (!recomposer.hasPendingWork && !clock.hasAwaiters) return
            }
            error("Composition did not settle")
        }
        suspend fun close() { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun textAppendsDoNotRestartTheOneHundredMillisecondTimer() = runTest {
        val host = Host(this)
        val reported = mutableStateOf(listOf(ReportedReasoning("r", "initial", 0, 0)))
        val live = mutableStateOf(true)
        var sampled: State<Long?>? = null
        var inputs: List<ReportedReasoning>? = null
        val nanoTime: () -> Long = { testScheduler.currentTime * 1_000_000 }
        try {
            host.composition.setContent {
                val timing = rememberReasoningTiming(reported.value)
                inputs = timing
                sampled = rememberReasoningElapsed(timing, live.value, nanoTime)
            }
            host.settle()
            val first = inputs
            assertEquals(0L, sampled!!.value)
            advanceTimeBy(50)
            repeat(100) {
                reported.value = listOf(ReportedReasoning("r", "append $it", 0, 0, it))
                host.settle()
                assertEquals("Text must not sample/restart between timer ticks", 0L, sampled!!.value)
                assertSame(first, inputs)
            }
            advanceTimeBy(50); host.settle()
            assertEquals(100L, sampled!!.value)
            advanceTimeBy(50)
            live.value = false; host.settle()
            assertEquals("Stopping samples the same active anchor once", 150L, sampled!!.value)
            advanceTimeBy(500); host.settle()
            assertEquals(150L, sampled!!.value)
            live.value = true; host.settle()
            assertEquals(650L, sampled!!.value)
            reported.value = listOf(ReportedReasoning("r", "done", 125, null))
            live.value = false; host.settle()
            assertEquals(125L, sampled!!.value)
            advanceTimeBy(1_000); host.settle()
            assertEquals(125L, sampled!!.value)
            reported.value = listOf(ReportedReasoning("r", "unknown")); host.settle()
            assertNull(sampled!!.value)
        } finally { host.close() }
    }

    @Test fun timingChangesUnknownsAndSaturationKeepDomainSemantics() = runTest {
        val host = Host(this)
        val reported = mutableStateOf(emptyList<ReportedReasoning>())
        var sampled: State<Long?>? = null
        val nanoTime: () -> Long = { 10_000_000L }
        try {
            host.composition.setContent { sampled = rememberReasoningElapsed(rememberReasoningTiming(reported.value), false, nanoTime) }
            host.settle(); assertEquals(0L, sampled!!.value)
            val cases = listOf(
                listOf(ReportedReasoning("a", "", 3), ReportedReasoning("b", "", 7, 0)),
                listOf(ReportedReasoning("a", "", Long.MAX_VALUE - 2, 0), ReportedReasoning("b", "", 7)),
                listOf(ReportedReasoning("a", "", 3), ReportedReasoning("b", "unknown")),
                listOf(ReportedReasoning("new-id", "", 2, 20_000_000)),
            )
            for (items in cases) {
                reported.value = items; host.settle()
                val expected = if (items.any { it.elapsedMillis == null }) null else items.fold(0L) { sum, item ->
                    sum + item.elapsedAt(nanoTime())!!.coerceAtMost(Long.MAX_VALUE - sum)
                }
                assertEquals(expected, sampled!!.value)
            }
        } finally { host.close() }
    }

    @Test fun unchangedUsageRetainsFormattedLabelsAndLocaleOrNumbersInvalidateThem() = runTest {
        val host = Host(this)
        val usage = mutableStateOf(CloudContextUsageUi(1_500, 128_000))
        val locale = mutableStateOf(Locale.US)
        val text = mutableStateOf(0)
        var labels: Pair<String, String>? = null
        try {
            host.composition.setContent {
                check(text.value >= 0)
                labels = rememberChatContextUsageLabels(usage.value, locale.value)
            }
            host.settle()
            val first = labels
            repeat(100) { text.value++; host.settle(); assertSame(first, labels) }
            for (nextLocale in listOf(Locale.GERMANY, Locale.forLanguageTag("ar"), Locale.US)) {
                locale.value = nextLocale; host.settle()
                assertEquals(usage.value.label(nextLocale) to usage.value.tokensLabel(nextLocale), labels)
                for (next in listOf(CloudContextUsageUi(), CloudContextUsageUi(-1, 0), CloudContextUsageUi(999, -1),
                    CloudContextUsageUi(1_000, 128_000), CloudContextUsageUi(1_500, 100_000))) {
                    usage.value = next; host.settle()
                    assertEquals(next.label(nextLocale) to next.tokensLabel(nextLocale), labels)
                }
            }
        } finally { host.close() }
    }
}
