package app.daiyu.android.tasksurface

import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceStoreTest {
    @Test fun missingRecordIsAskAndAllThreeValuesSurviveFreshStoreInstances() {
        val persistence = MemoryPersistence()
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.ASK), TaskSurfaceStore(persistence).load())
        TaskSurfacePreference.entries.forEach { value ->
            assertTrue(TaskSurfaceStore(persistence).save(value))
            assertEquals(TaskSurfaceSettings.Available(value), TaskSurfaceStore(persistence).load())
        }
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
