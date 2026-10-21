package app.daiyu.android.assistants

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class AssistantProfileCasDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private val opened = mutableListOf<ConversationDatabase>()
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "assistant-cas-${UUID.randomUUID()}-")
    }
    private fun database() = ConversationDatabase(context).also { opened.add(it) }
    override fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase("conversations.db")
        super.tearDown()
    }
    private fun stale(action: () -> Unit) {
        try { action(); fail("stale profile accepted") } catch (_: StaleAssistantProfileException) {}
    }

    fun testFullProfileComparisonRejectsExternalEditAndNeverResurrectsDeletedRow() {
        val db = database()
        val first = SqliteAssistantStore(db)
        val second = SqliteAssistantStore(database())
        val original = first.read("default")!!
        val external = second.compareAndSet(original, original.copy(prompt = "external", avatarFileName = "new.png"))
        stale { first.compareAndSet(original, original.copy(memoryEnabled = false)) }
        assertEquals(external, first.read(original.id))
        val saved = first.compareAndSet(external, external.copy(memoryEnabled = false, enabledSkillIds = listOf("skill")))
        first.save(AssistantProfile("other", "Other", ""))
        second.delete(original.id)
        stale { first.compareAndSet(saved, saved.copy(name = "must not revive")) }
        assertNull(first.read(original.id))
        assertEquals(listOf("other"), first.list().filter { first.read(it.id) != null }.map { it.id })
        assertEquals(9, db.readableDatabase.rawQuery("PRAGMA table_info(assistants)", null).use { it.count })
        val restored = SqliteAssistantStore(database())
        assertNull(restored.read(original.id))
        assertEquals(listOf("other"), restored.list().map { it.id })
    }

    fun testConcurrentCasAcrossStoreInstancesHasExactlyOneWinner() {
        val first = SqliteAssistantStore(database())
        val second = SqliteAssistantStore(database())
        val original = first.read("default")!!
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val winners = AtomicInteger(); val conflicts = AtomicInteger(); val error = AtomicReference<Throwable?>()
        fun worker(store: SqliteAssistantStore, name: String) = Thread {
            try {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                store.compareAndSet(original, original.copy(name = name))
                winners.incrementAndGet()
            } catch (_: StaleAssistantProfileException) { conflicts.incrementAndGet() }
            catch (problem: Throwable) { error.set(problem) }
        }
        val a = worker(first, "A"); val b = worker(second, "B")
        a.start(); b.start()
        try { assertTrue(ready.await(5, TimeUnit.SECONDS)) } finally { start.countDown() }
        a.join(5000); b.join(5000)
        assertFalse(a.isAlive); assertFalse(b.isAlive)
        error.get()?.let { throw it }
        assertEquals(1, winners.get()); assertEquals(1, conflicts.get())
        assertTrue(first.read(original.id)!!.name in setOf("A", "B"))
        assertEquals(first.read(original.id), second.read(original.id))
    }
}
