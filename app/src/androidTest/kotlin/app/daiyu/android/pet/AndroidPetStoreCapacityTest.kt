package app.daiyu.android.pet

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.pet.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class AndroidPetStoreCapacityTest : InstrumentationTestCase() {
    fun testExactCapacityRoundTripsEntireSnapshot() {
        val context = testContext(instrumentation.targetContext, "pet-capacity-${UUID.randomUUID()}-")
        val prefs = context.getSharedPreferences("daiyu_pet_v1", 0)
        try {
            val state = stateAtCapacity()
            val raw = PetStateJson.encode(state)
            assertEquals(MAX_STATE_CHARS, raw.length)
            assertEquals(state, PetStateJson.decode(raw))
            // Otherwise valid JSON with one trailing space crosses the reader's same limit.
            expectCapacityFailure { PetStateJson.decode(raw + " ") }

            AndroidPetStore(context).save(state)

            assertEquals(raw, prefs.getString("state", null))
            // A fresh store must retain every field, including usage and run deduplication.
            assertEquals(state, AndroidPetStore(context).load())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    fun testOneCharacterOverCapacityDoesNotReplaceSnapshot() {
        val context = testContext(instrumentation.targetContext, "pet-capacity-${UUID.randomUUID()}-")
        val prefs = context.getSharedPreferences("daiyu_pet_v1", 0)
        try {
            val store = AndroidPetStore(context)
            val previous = historyState()
            store.save(previous)
            val previousRaw = prefs.getString("state", null)
            val oversized = stateAtCapacity(extraChars = 1)

            expectCapacityFailure { PetStateJson.encode(oversized) }
            expectCapacityFailure { store.save(oversized) }

            assertEquals(previousRaw, prefs.getString("state", null))
            assertEquals(previous, store.load())
            assertEquals(previous, AndroidPetStore(context).load())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    fun testEscapedUsageOverCapacityDoesNotWriteAnInitialSnapshot() {
        val context = testContext(instrumentation.targetContext, "pet-capacity-${UUID.randomUUID()}-")
        val prefs = context.getSharedPreferences("daiyu_pet_v1", 0)
        try {
            val previous = historyState()
            // The field itself is below the cap, but each quote takes two JSON characters.
            val escapedUsage = previous.usage.last().copy(requestId = "\"".repeat(MAX_STATE_CHARS / 2))
            val oversized = previous.copy(usage = previous.usage + escapedUsage)
            assertTrue(escapedUsage.requestId.length < MAX_STATE_CHARS)

            expectCapacityFailure { AndroidPetStore(context).save(oversized) }

            assertFalse(prefs.contains("state"))
            assertEquals(PetState(), AndroidPetStore(context).load())
            assertFalse(prefs.contains("state"))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    private fun expectCapacityFailure(action: () -> Unit) {
        try {
            action()
            fail("Oversized pet state must fail explicitly")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Pet state too large", expected.message)
        }
    }

    private fun stateAtCapacity(extraChars: Int = 0): PetState {
        val state = historyState()
        val padding = MAX_STATE_CHARS - PetStateJson.encode(state).length + extraChars
        val lastRun = state.completedRuns.last()
        return state.copy(completedRuns = state.completedRuns.dropLast(1) +
            lastRun.copy(runId = lastRun.runId + "x".repeat(padding)))
    }

    private fun historyState() = PetState(
        settings = PetSettings(true, true, false, true, 1.25f, 42, 84,
            PetModelSelection("pet-a", "pet-p", "pet-m")),
        satiety = 12_345, mood = PetMood.EATING, speech = "米饭真香", speechVisible = true,
        memories = listOf(PetMemory(PetMood.EATING, "米饭真香", 400, 123, PetEvent.FEED)),
        recentTasks = listOf(PetTask("任务", 100)),
        usage = listOf(
            PetUsage("known", "pet-a", "pet-p", "pet-m", PetEvent.FEED,
                PetRequestStatus.COMPLETED, 12L, 34L),
            PetUsage("unknown", "pet-a", "pet-p", "pet-m", PetEvent.WORK_DONE,
                PetRequestStatus.PENDING)
        ),
        completedRuns = listOf(PetCompletedRun("conversation-a", "run-a"),
            PetCompletedRun("conversation-b", "run-b"))
    )

    private companion object {
        const val MAX_STATE_CHARS = 8_000_000
    }
}
