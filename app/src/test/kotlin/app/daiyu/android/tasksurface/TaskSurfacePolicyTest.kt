package app.daiyu.android.tasksurface

import org.junit.Assert.*
import org.junit.Test

class TaskSurfacePolicyTest {
    private val own = "app.daiyu.android"
    private val external = "example.target"
    private fun run(preference: TaskSurfacePreference, owner: Any = Any()) =
        TaskSurfaceRunDecision(owner, TaskSurfaceSettings.Available(preference), own)
    private fun ready(decision: TaskSurfaceDecision) = decision as TaskSurfaceDecision.Ready
    private fun code(decision: TaskSurfaceDecision) = (decision as TaskSurfaceDecision.NotExecuted).code

    @Test fun realPreferenceTargetAndCapabilityMatrix() {
        TaskSurfacePreference.entries.forEach { preference ->
            listOf(false, true).forEach { supported ->
                val run = run(preference)
                val self = ready(run.decision(own, supported))
                assertEquals(TaskSurface.FOREGROUND, self.surface)
                val selfTarget = checkNotNull(self.selfTarget)
                assertTrue(selfTarget.isCurrent(run.owner, own))
                assertFalse(selfTarget.isCurrent(Any(), own))
                assertFalse(selfTarget.isCurrent(run.owner, external))
                assertNull(run.askFromUser(own))
                val result = run.decision(external, supported)
                when (preference) {
                    TaskSurfacePreference.FOREGROUND -> {
                        assertEquals(TaskSurface.FOREGROUND, ready(result).surface)
                        assertNull(ready(result).selfTarget)
                    }
                    TaskSurfacePreference.BACKGROUND -> if (supported) {
                        assertEquals(TaskSurface.BACKGROUND, ready(result).surface)
                        assertNull(ready(result).selfTarget)
                    } else {
                        assertEquals("TASK_SURFACE_BACKGROUND_UNSUPPORTED", code(result))
                        assertEquals("后台执行不可用", (result as TaskSurfaceDecision.NotExecuted).message)
                    }
                    TaskSurfacePreference.ASK -> assertSame(TaskSurfaceDecision.AskRequired, result)
                }
            }
        }
    }

    @Test fun refusalMessagesDescribeOnlyTheSpecificCause() {
        fun message(decision: TaskSurfaceDecision) = (decision as TaskSurfaceDecision.NotExecuted).message
        val ended = run(TaskSurfacePreference.FOREGROUND)
        ended.end()
        assertEquals("任务已结束", message(ended.decision(external, true)))
        val unavailable = TaskSurfaceRunDecision(Any(), TaskSurfaceSettings.Unavailable, own)
        assertEquals("执行位置设置不可用", message(unavailable.decision(own, true)))
        assertEquals("缺少目标应用", message(run(TaskSurfacePreference.FOREGROUND).decision("", true)))
        val cancelled = run(TaskSurfacePreference.ASK)
        val question = checkNotNull(cancelled.askFromUser(external))
        assertTrue(cancelled.answerFromUser(question, null))
        assertEquals("已取消执行位置选择", message(cancelled.decision(external, true)))
        assertEquals("后台执行不可用", message(run(TaskSurfacePreference.BACKGROUND).decision(external, false)))
    }

    @Test fun cancellationIsStickyThroughSelfExceptionLaterOrdinaryRequestsAndPreferenceChanges() {
        val persistence = MemoryPersistence()
        val store = TaskSurfaceStore(persistence)
        assertTrue(store.save(TaskSurfacePreference.ASK))
        val run = store.freeze(Any(), own)
        val question = checkNotNull(run.askFromUser(external))
        assertSame(TaskSurfaceDecision.AskPending, run.decision(external, true))
        assertNull(run.askFromUser(external))
        assertTrue(run.answerFromUser(question, null))
        repeat(3) {
            assertEquals("TASK_SURFACE_ASK_CANCELLED", code(run.decision(external, true)))
            assertNull(run.askFromUser(external))
            assertFalse(run.answerFromUser(question, TaskSurface.FOREGROUND))
            assertEquals(TaskSurface.FOREGROUND, ready(run.decision(own, true)).surface)
            assertNull(run.askFromUser(own))
        }
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.ASK), store.load())
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        assertEquals("TASK_SURFACE_ASK_CANCELLED", code(run.decision(external, false)))
        val self = ready(run.decision(own, false)).selfTarget!!
        run.end()
        assertFalse(self.isCurrent(run.owner, own))
        assertEquals("TASK_SURFACE_RUN_ENDED", code(run.decision(own, true)))
    }

    @Test fun oneQuestionExactRunTicketAndOneAnswerWithNoRepromptOrFallback() {
        listOf(TaskSurface.FOREGROUND, TaskSurface.BACKGROUND).forEach { answer ->
            val run = run(TaskSurfacePreference.ASK)
            val other = this.run(TaskSurfacePreference.ASK)
            val question = checkNotNull(run.askFromUser(external))
            val otherQuestion = checkNotNull(other.askFromUser(external))
            assertFalse(run.answerFromUser(otherQuestion, answer))
            assertTrue(run.answerFromUser(question, answer))
            assertFalse(run.answerFromUser(question, null))
            assertNull(run.askFromUser(external))
            assertEquals(answer, ready(run.decision(external, true)).surface)
            if (answer == TaskSurface.BACKGROUND)
                assertEquals("TASK_SURFACE_BACKGROUND_UNSUPPORTED", code(run.decision(external, false)))
            assertEquals(answer, ready(run.decision(external, true)).surface)
        }
    }

    @Test fun runSnapshotIgnoresSubsequentStoreSaves() {
        val store = TaskSurfaceStore(MemoryPersistence())
        assertTrue(store.save(TaskSurfacePreference.BACKGROUND))
        val old = store.freeze(Any(), own)
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        assertEquals(TaskSurface.BACKGROUND, ready(old.decision(external, true)).surface)
        assertEquals(TaskSurface.FOREGROUND, ready(store.freeze(Any(), own).decision(external, true)).surface)
    }

    @Test fun unavailableSnapshotAndMissingTargetNeverGuessForegroundEvenForSelf() {
        val unavailable = TaskSurfaceRunDecision(Any(), TaskSurfaceSettings.Unavailable, own)
        assertEquals("TASK_SURFACE_SETTINGS_UNAVAILABLE", code(unavailable.decision(own, true)))
        assertNull(unavailable.askFromUser(external))
        assertEquals("TASK_SURFACE_TARGET_REQUIRED", code(run(TaskSurfacePreference.FOREGROUND).decision("", true)))
    }
}

internal class MemoryPersistence : TaskSurfacePersistence {
    var record: String? = null
    var failRead = false
    var failWrite = false
    var ignoreWrite = false
    override fun read(): String? { check(!failRead); return record }
    override fun write(record: String) { check(!failWrite); if (!ignoreWrite) this.record = record }
}
