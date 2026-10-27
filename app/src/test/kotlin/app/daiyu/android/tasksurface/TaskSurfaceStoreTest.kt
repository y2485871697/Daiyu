package app.daiyu.android.tasksurface

import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceStoreTest {
    @Test fun missingRecordIsForegroundAndAllThreeExplicitValuesSurviveFreshStoreInstances() {
        val persistence = MemoryPersistence()
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.FOREGROUND), TaskSurfaceStore(persistence).load())
        TaskSurfacePreference.entries.forEach { value ->
            assertTrue(TaskSurfaceStore(persistence).save(value))
            assertEquals(TaskSurfaceSettings.Available(value), TaskSurfaceStore(persistence).load())
        }
    }

    @Test fun absentDefaultDoesNotWritePreferenceAndFrozenRunDoesNotFollowLaterSettings() {
        val persistence = MemoryPersistence()
        val store = TaskSurfaceStore(persistence)
        val original = store.freeze(Any(), "app.daiyu.android")
        assertNull(persistence.record)
        assertEquals(TaskSurface.FOREGROUND,
            (original.decision("example.target", false) as TaskSurfaceDecision.Ready).surface)
        assertTrue(store.save(TaskSurfacePreference.BACKGROUND))
        assertEquals(TaskSurface.FOREGROUND,
            (original.decision("example.target", false) as TaskSurfaceDecision.Ready).surface)
        val next = store.freeze(Any(), "app.daiyu.android")
        assertEquals("TASK_SURFACE_BACKGROUND_UNSUPPORTED",
            (next.decision("example.target", false) as TaskSurfaceDecision.NotExecuted).code)
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND), store.load())
        val self = next.decision("app.daiyu.android", false) as TaskSurfaceDecision.Ready
        assertEquals(TaskSurface.FOREGROUND, self.surface)
        assertTrue(self.selfTarget!!.isCurrent(next.owner, "app.daiyu.android"))
        next.end()
        assertFalse(self.selfTarget!!.isCurrent(next.owner, "app.daiyu.android"))
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND), store.load())
    }

    @Test fun failedFirstWriteDoesNotFallBackToTheAbsentForegroundDefault() {
        val persistence = MemoryPersistence().apply { ignoreWrite = true }
        val store = TaskSurfaceStore(persistence)
        assertFalse(store.save(TaskSurfacePreference.ASK))
        assertNull(persistence.record)
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
        assertTrue(store.freeze(Any(), "app.daiyu.android").decision("example.target", false) is TaskSurfaceDecision.NotExecuted)
    }

    @Test fun corruptVersionTruncatedUnknownAndReadFailureNeverDefaultToForeground() {
        val persistence = MemoryPersistence()
        val store = TaskSurfaceStore(persistence)
        listOf("", "FOREGROUND", "task-surface-v1\n", "task-surface-v1\nAUTO\n",
            "task-surface-v2\nFOREGROUND\n", "task-surface-v1\nFOREGROUND\nextra").forEach { corrupt ->
            persistence.record = corrupt
            assertSame(TaskSurfaceSettings.Unavailable, store.load())
        }
        persistence.failRead = true
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
        val run = store.freeze(Any(), "app.daiyu.android")
        assertTrue(run.decision("example.target", true) is TaskSurfaceDecision.NotExecuted)
    }

    @Test fun writeFailureAndReadbackMismatchAreNotReportedAsSavedAndBlockNewRuns() {
        val persistence = MemoryPersistence()
        val store = TaskSurfaceStore(persistence)
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        persistence.failWrite = true
        assertFalse(store.save(TaskSurfacePreference.BACKGROUND))
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
        persistence.failWrite = false; persistence.ignoreWrite = true
        assertFalse(store.save(TaskSurfacePreference.ASK))
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
        persistence.ignoreWrite = false
        assertTrue(store.save(TaskSurfacePreference.BACKGROUND))
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND), store.load())
        persistence.failRead = true
        assertFalse(store.save(TaskSurfacePreference.FOREGROUND))
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
    }

    @Test fun editorDoesNotChangeSavedValueOnFailureAndCanRecoverWithExplicitSuccessfulSave() {
        val persistence = MemoryPersistence()
        val store = TaskSurfaceStore(persistence)
        assertTrue(store.save(TaskSurfacePreference.ASK))
        val editor = TaskSurfaceSettingsEditor(store)
        editor.choose(TaskSurfacePreference.BACKGROUND)
        persistence.failWrite = true
        assertFalse(editor.save())
        assertTrue(editor.saveFailed)
        assertEquals(TaskSurfacePreference.BACKGROUND, editor.draft)
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.ASK), editor.saved)
        persistence.failWrite = false
        assertTrue(editor.save())
        assertFalse(editor.saveFailed)
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND), editor.saved)
    }

    @Test fun selfAndCancelledAskNeverWriteBasePreferenceOrClearItsSnapshot() {
        val persistence = MemoryPersistence()
        val store = TaskSurfaceStore(persistence)
        assertTrue(store.save(TaskSurfacePreference.ASK))
        val before = persistence.record
        val run = store.freeze(Any(), "app.daiyu.android")
        run.decision("app.daiyu.android", true)
        assertNull(run.askFromUser("app.daiyu.android"))
        val question = checkNotNull(run.askFromUser("example.target"))
        assertTrue(run.answerFromUser(question, null))
        run.decision("app.daiyu.android", false)
        assertEquals(before, persistence.record)
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.ASK), store.load())
        assertEquals("TASK_SURFACE_ASK_CANCELLED", (run.decision("example.target", true)
            as TaskSurfaceDecision.NotExecuted).code)
    }
}
