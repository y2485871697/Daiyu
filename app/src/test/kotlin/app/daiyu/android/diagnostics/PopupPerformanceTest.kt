package app.daiyu.android.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PopupPerformanceTest {
    @Test fun popupRootsMatchCompilerFunctionBoundariesWithoutSourceLineCoupling() {
        for (name in listOf("app.daiyu.android.ui.screens.assistants.AssistantPickerDialog",
            "app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker",
            "app.daiyu.android.ui.components.PreviewDialog",
            "app.daiyu.android.ui.screens.chat.conversation.ChatMessageList",
            "app.daiyu.android.ui.screens.chat.conversation.DaiyuChatScreen")) {
            assertTrue(popupCompositionRoot("$name (Fixture.kt:999)"))
            assertFalse(popupCompositionRoot("${name}Child (Fixture.kt:999)"))
            assertFalse(popupCompositionRoot("$name.<anonymous> (Fixture.kt:999)"))
        }
        assertFalse(popupCompositionRoot("androidx.compose.material3.Text (Text.kt:1)"))
        assertFalse(popupCompositionRoot("app.daiyu.android.MainActivity.<anonymous> (MainActivity.kt:1)"))
    }

    private class Fixture {
        var now = 1_000_000_000L
        var epoch = 0L
        val host = Any()
        val window = Any()
        val boundaries = mutableListOf<PopupPerformanceCapture.Boundary>()
        val rows = mutableListOf<BackPerformanceRecorder.Incident>()
        val timers = mutableListOf<() -> Unit>()
        var ended = 0
        val capture = PopupPerformanceCapture({ now }, { epoch }, { row, _, _ -> rows.add(row) },
            { if (it.epoch == epoch) boundaries.add(it) })
        fun start(type: PopupPerformanceCapture.Type = PopupPerformanceCapture.Type.ASSISTANT_PICKER) =
            capture.start(host, window, type, BackPerformanceRecorder.Scroll.NON_DRAG_SCROLL,
                { delay, work -> assertEquals(4000L, delay); timers.add(work) }, { ended++ })
        fun frame(targetHost: Any = host, targetWindow: Any? = window) =
            capture.frame(targetHost, targetWindow, now, 40_000_000L, 8_333_333L, 120f, LongArray(9),
                BackPerformanceRecorder.Scroll.DRAG)
    }

    @Test fun fastSelectorsKeepOpenCloseButDoNotWriteFrames() {
        val f = Fixture()
        for (type in PopupPerformanceCapture.Type.entries) {
            val lease = f.start(type)
            f.capture.frame(f.host, null, f.now, 1L, 8_333_333L, 120f, LongArray(9), BackPerformanceRecorder.Scroll.IDLE)
            f.capture.close(lease); f.capture.close(lease)
            f.now += 100_000_000L
        }
        assertTrue(f.rows.isEmpty())
        assertEquals(listOf(false, true, false, true), f.boundaries.map { it.closed })
        assertEquals(2, f.ended)
    }

    @Test fun onlyOwnersMainAndChosenPopupContributeWithDistinctSources() {
        val f = Fixture(); val lease = f.start()
        f.frame(targetWindow = null); f.frame()
        f.frame(targetWindow = Any()); f.frame(targetHost = Any())
        f.capture.close(lease)
        val row = f.rows.single()
        assertEquals(2, row.frames)
        assertEquals(setOf(BackPerformanceRecorder.Source.MAIN, BackPerformanceRecorder.Source.POPUP), row.worst.map { it.source }.toSet())
        assertTrue(row.worst.all { it.scroll == BackPerformanceRecorder.Scroll.DRAG })
        assertEquals(BackPerformanceRecorder.Kind.ASSISTANT_PICKER, row.kind)
        assertFalse(row.commitAttempted); assertFalse(row.routeChanged)
    }

    @Test fun oldTimerCloseFrameAndCompositionCannotEndOrPolluteReplacement() {
        val f = Fixture(); val first = f.start(); f.frame()
        val oldTimer = f.timers.single()
        f.now += 100_000_000L
        val second = f.start(PopupPerformanceCapture.Type.MODEL_PICKER)
        oldTimer(); f.capture.close(first)
        f.capture.recorder.frame(f.now, 50_000_000L, 8_333_333L, 120f, LongArray(9), f.host, sourceToken = first.token)
        f.capture.recorder.composition(first.token, "old", f.now, 20_000_000L, 20_000_000L)
        assertTrue(f.capture.observing(f.host, f.window))
        assertEquals(second.token, f.capture.recorder.token())
        f.frame(); f.capture.close(second)
        assertEquals(listOf(BackPerformanceRecorder.Kind.ASSISTANT_PICKER, BackPerformanceRecorder.Kind.MODEL_PICKER), f.rows.map { it.kind })
        assertEquals(1, f.rows.last().frames)
        assertTrue(f.rows.last().compositions.isEmpty())
    }

    @Test fun hardLimitStopsSamplingWithoutClaimingThatTheSelectorClosed() {
        val f = Fixture(); val lease = f.start(); f.frame()
        f.now += BackPerformanceRecorder.HARD_NS
        f.timers.single()()
        assertFalse(f.capture.observing(f.host))
        assertEquals(BackPerformanceRecorder.End.HARD_LIMIT, f.rows.single().reason)
        assertEquals(1, f.boundaries.size)
        f.frame(); f.now += 10_000_000_000L
        f.capture.close(lease)
        assertEquals(2, f.boundaries.size)
        assertTrue(f.boundaries.last().closed)
        assertEquals(1, f.rows.size); assertEquals(1, f.ended)
    }

    @Test fun pauseDestroyAndClearRespectTheOwningHostAndEpoch() {
        for (reason in listOf(BackPerformanceRecorder.End.PAUSED, BackPerformanceRecorder.End.DESTROYED)) {
            val f = Fixture(); val lease = f.start(); f.frame()
            f.capture.endHost(Any(), reason)
            assertTrue(f.capture.observing(f.host))
            f.capture.endHost(f.host, reason)
            assertEquals(reason, f.rows.single().reason)
            assertEquals(1, f.boundaries.size)
            f.capture.close(lease)
            assertEquals(2, f.boundaries.size)
        }
        val f = Fixture(); val old = f.start(); f.frame()
        f.epoch++; f.boundaries.clear()
        f.frame(); f.capture.close(old); f.timers.single()()
        assertFalse(f.capture.observing(f.host))
        assertTrue(f.rows.isEmpty()); assertTrue(f.boundaries.isEmpty())
    }

    @Test fun backAndPopupUseIndependentRecordersAndCompilerStacks() {
        val f = Fixture()
        val backRows = mutableListOf<BackPerformanceRecorder.Incident>()
        val back = BackPerformanceRecorder({ f.now }, { f.epoch }, { backRows.add(it) })
        val backOwner = BackPerformanceRecorder.Owner(f.host) { _, _ -> }
        val backToken = back.start(backOwner, BackPerformanceRecorder.Kind.BACK_PRESS)
        val lease = f.start()
        val backSamples = BackCompositionSamples({ f.now }, back::compositionObserved, back::compositionLimited, back::composition)
        val popupSamples = BackCompositionSamples({ f.now }, f.capture.recorder::compositionObserved,
            f.capture.recorder::compositionLimited, f.capture.recorder::composition)
        backSamples.start(backToken, "back-only")
        popupSamples.start(lease.token, "popup-only")
        f.now += 20_000_000L
        popupSamples.end(lease.token); backSamples.end(backToken)
        f.capture.close(lease)
        assertEquals(backToken, back.token())
        back.endHost(f.host, BackPerformanceRecorder.End.PAUSED)
        assertEquals("back-only", backRows.single().compositions.single().function)
        assertEquals("popup-only", f.rows.single().compositions.single().function)
    }

    @Test fun ordinaryOffWriterPreservesTypedPopupPrivacyAndBackSchema() {
        val root = Files.createTempDirectory("popup-runtime").toFile()
        RuntimeDiagnostics.install(root)
        try {
            RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear()
            var now = 1_000_000_000L
            val host = Any(); val window = Any()
            val capture = PopupPerformanceCapture({ now }, RuntimeDiagnostics::backEpoch,
                RuntimeDiagnostics::popupIncident, RuntimeDiagnostics::popupBoundary)
            val lease = capture.start(host, window, PopupPerformanceCapture.Type.MODEL_PICKER,
                BackPerformanceRecorder.Scroll.NON_DRAG_SCROLL, { _, _ -> }, {})
            RuntimeDiagnostics.enabled = true; RuntimeDiagnostics.enabled = false
            capture.frame(host, null, now, 40_000_000L, 8_333_333L, 120f, LongArray(9), BackPerformanceRecorder.Scroll.NON_DRAG_SCROLL)
            now += 50_000_000L; capture.close(lease)
            val rows = RuntimeDiagnostics.export()
            assertEquals(3, rows.length())
            assertEquals(listOf("OPEN", "INCIDENT", "CLOSE"), (0 until rows.length()).map { rows.getJSONObject(it).getString("code") })
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                assertEquals("POPUP_PERFORMANCE", row.getString("stage"))
                assertEquals("MODEL_PICKER", row.getString("kind"))
                assertEquals("WINDOW_REGISTERED", row.getString("sampling_start"))
                assertFalse(row.has("title")); assertFalse(row.has("message_id")); assertFalse(row.has("coordinates"))
                assertTrue(row.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024)
            }
            val worst = rows.getJSONObject(1).getJSONArray("worst_frames").getJSONObject(0)
            assertEquals("MAIN", worst.getString("source_window"))
            assertEquals("NON_DRAG_SCROLL", worst.getString("scroll"))
            RuntimeDiagnostics.clear()
            val stale = capture.start(host, window, PopupPerformanceCapture.Type.ASSISTANT_PICKER,
                BackPerformanceRecorder.Scroll.IDLE, { _, _ -> }, {})
            RuntimeDiagnostics.clear(); capture.close(stale)
            assertEquals(0, RuntimeDiagnostics.export().length())
        } finally {
            RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear(); root.deleteRecursively()
        }
    }
}
