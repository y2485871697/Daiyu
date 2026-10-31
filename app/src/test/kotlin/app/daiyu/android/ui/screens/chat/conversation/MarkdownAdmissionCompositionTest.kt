package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.daiyu.android.MarkdownParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MarkdownAdmissionCompositionTest {
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun terminalBodyDoesNotTypeOrEmitFeedbackBeforeAdmission() = verifyAdmission(terminal = true)
    @Test fun streamingBodyDoesNotTypeBehindAClosingWorkflow() = verifyAdmission(terminal = false)

    private fun verifyAdmission(terminal: Boolean) = runBlocking {
        projectMarkdown(listOf(""))
        projectMarkdown(listOf(""), parser = streamingMarkdownParser)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val text = "字".repeat(500)
        val sources = mutableStateOf(listOf(MarkdownTypingSource("answer", "")))
        val parser = defaultMarkdownParser
        fun projection(text: String) = MarkdownProjection(listOf(text), mapOf(text to MarkdownParser.parse(text)), parser)
        val parsed = mutableStateOf(projection(""))
        val complete = mutableStateOf(false)
        val admitted = mutableStateOf(false)
        var reveal: MarkdownReveal? = null
        var advances = 0
        var time = 1_000_000_000L
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(12) { yield() }
        }
        try {
            composition.setContent {
                reveal = rememberMarkdownReveal(sources.value, parsed.value, streaming = !complete.value,
                    terminal = complete.value, onVisibleAdvance = { advances++ },
                    blockedSources = if (admitted.value) emptySet() else setOf("answer"))
            }
            repeat(3) { frame() }
            sources.value = listOf(MarkdownTypingSource("answer", text))
            parsed.value = projection(text)
            complete.value = terminal
            repeat(40) { frame() }
            assertEquals("Hidden body must retain a zero reveal budget", 0, reveal!!.limit("answer"))
            assertEquals("Hidden body must not emit visible feedback", 0, advances)
            assertFalse(reveal!!.settled)
            admitted.value = true
            repeat(5) { frame() }
            val partial = reveal!!.limit("answer")
            assertTrue("Admission starts a real intermediate reveal: $partial", partial in 1 until text.length)
            assertTrue(advances > 0)
            complete.value = true
            repeat(25) { frame() }
            assertEquals(text.length, reveal!!.limit("answer"))
            assertTrue(reveal!!.settled)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }
}
