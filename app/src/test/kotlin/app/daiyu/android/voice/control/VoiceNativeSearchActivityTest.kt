package app.daiyu.android.voice.control

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.NativeSearchPhase
import org.junit.Assert.*
import org.junit.Test

class VoiceNativeSearchActivityTest {
    private fun event(id: String, phase: NativeSearchPhase) = ModelEvent.NativeSearch(id, phase)
    @Test fun successIsReportedOnlyByCompletedSearchAndDoesNotReplay() {
        val activity = VoiceNativeSearchActivity()
        activity.accept(event("request:ws", NativeSearchPhase.RUNNING))
        assertTrue(activity.state.searching); assertNull(activity.state.completion)
        activity.accept(event("request:ws", NativeSearchPhase.COMPLETED))
        val success = activity.state.completion
        assertNotNull(success); assertFalse(activity.state.searching)
        activity.accept(event("request:ws", NativeSearchPhase.COMPLETED))
        assertEquals(success, activity.state.completion)
        activity.accept(event("request:ws", NativeSearchPhase.RUNNING))
        assertFalse(activity.state.searching)
    }
    @Test fun anotherSearchImmediatelySupersedesSuccessAndFailuresNeverShowCheck() {
        val activity = VoiceNativeSearchActivity()
        activity.accept(event("one", NativeSearchPhase.COMPLETED))
        val first = activity.state.completion
        activity.accept(event("two", NativeSearchPhase.RUNNING))
        assertTrue(activity.state.searching); assertNull(activity.state.completion)
        activity.accept(event("two", NativeSearchPhase.COMPLETED))
        assertNotEquals(first, activity.state.completion)
        activity.accept(event("three", NativeSearchPhase.FAILED))
        assertFalse(activity.state.searching); assertNull(activity.state.completion)
        activity.accept(event("four", NativeSearchPhase.INCOMPLETE))
        assertNull(activity.state.completion)
    }
    @Test fun failedOrIncompleteMemberPreventsAGroupSuccessButDoesNotPoisonTheNextSearch() {
        for (failure in listOf(NativeSearchPhase.FAILED, NativeSearchPhase.INCOMPLETE)) {
            val activity = VoiceNativeSearchActivity()
            activity.accept(event("a", NativeSearchPhase.RUNNING))
            activity.accept(event("b", NativeSearchPhase.RUNNING))
            activity.accept(event("a", failure))
            assertTrue(activity.state.searching)
            activity.accept(event("b", NativeSearchPhase.COMPLETED))
            assertFalse(activity.state.searching); assertNull(activity.state.completion)
            activity.accept(event("c", NativeSearchPhase.RUNNING))
            activity.accept(event("c", NativeSearchPhase.COMPLETED))
            assertNotNull(activity.state.completion)
        }
    }

    @Test fun concurrentSearchesRemainBusyUntilAllKnownCallsFinish() {
        val activity = VoiceNativeSearchActivity()
        activity.accept(event("one", NativeSearchPhase.RUNNING))
        activity.accept(event("two", NativeSearchPhase.RUNNING))
        activity.accept(event("one", NativeSearchPhase.COMPLETED))
        assertTrue(activity.state.searching); assertNull(activity.state.completion)
        activity.accept(event("two", NativeSearchPhase.COMPLETED))
        assertFalse(activity.state.searching); assertNotNull(activity.state.completion)
    }
}
