package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.tasksurface.AndroidTaskSurfaceStore
import app.daiyu.android.tasksurface.TaskSurfacePreference
import app.daiyu.android.tasksurface.TaskSurfaceSettings
import app.daiyu.android.tasksurface.TaskSurfaceStore
import java.io.File

/** Real AtomicFile evidence, isolated cache files only. Never modifies the user's base preference. */
@Suppress("DEPRECATION")
class TaskSurfaceStoreAndroidTest : InstrumentationTestCase() {
    private lateinit var root: File
    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.cacheDir, "task-surface-test-${java.util.UUID.randomUUID()}")
        assertTrue(root.mkdirs())
    }
    override fun tearDown() { try { root.deleteRecursively() } finally { super.tearDown() } }
    private fun store(file: File) = TaskSurfaceStore(AndroidTaskSurfaceStore.AtomicPersistence(file))

    fun testAtomicRoundTripFreshInstancesAndBackupRecovery() {
        val file = File(root, "preference")
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.ASK), store(file).load())
        TaskSurfacePreference.entries.forEach { value ->
            assertTrue(store(file).save(value))
            assertEquals(TaskSurfaceSettings.Available(value), store(file).load())
        }
        val committed = file.readBytes()
        File(file.path + ".bak").writeBytes(committed)
        file.writeText("interrupted write")
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.ASK), store(file).load())
    }

    fun testCorruptOrUnwritablePathDoesNotDefaultForegroundOrReportSaved() {
        val corrupt = File(root, "corrupt").apply { writeText("task-surface-v1\nFOREGROUND") }
        assertSame(TaskSurfaceSettings.Unavailable, store(corrupt).load())
        val notDirectory = File(root, "file-parent").apply { writeText("block parent creation") }
        val blocked = store(File(notDirectory, "preference"))
        assertFalse(blocked.save(TaskSurfacePreference.FOREGROUND))
        assertSame(TaskSurfaceSettings.Unavailable, blocked.load())
    }
}
