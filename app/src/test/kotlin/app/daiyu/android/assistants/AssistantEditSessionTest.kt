package app.daiyu.android.assistants

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantEditSessionTest {
    @Test fun lateInitialReadNeverOverwritesTypedDraftEvenWhenUserClearedIt() {
        val editor = AssistantMemoryDraft()
        val ticket = editor.beginRead()
        editor.edit("typed before disk returned")
        editor.completeRead(ticket, AssistantMemorySnapshot("disk", "v1"))
        assertEquals("typed before disk returned", editor.content)
        assertEquals("disk", editor.saved)
        assertEquals("v1", editor.revision)
        val cleared = AssistantMemoryDraft()
        val second = cleared.beginRead()
        cleared.edit("")
        cleared.completeRead(second, AssistantMemorySnapshot("must not reappear", "v2"))
        assertEquals("", cleared.content)
        val untouched = AssistantMemoryDraft()
        untouched.completeRead(untouched.beginRead(), AssistantMemorySnapshot("initial", "v3"))
        assertEquals("initial", untouched.content)
    }

    @Test fun continuousMemoryAndSkillTogglesAreSerializedAndFinalSaveUsesLastReceipt() = runBlocking {
        val original = AssistantProfile("a", "A", "prompt", enabledSkillIds = emptyList())
        val store = MemoryTestStore(original)
        val page = AssistantEditSession(store, original)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        store.beforeCas = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { page.toggles(false, emptySet()) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { page.toggles(false, setOf("skill")) }
            val third = async(start = CoroutineStart.UNDISPATCHED) { page.toggles(true, setOf("skill")) }
            assertEquals(1, calls.get())
            release.countDown()
            first.await(); second.await(); third.await()
            val receipt = page.save("edited", "new prompt", true, setOf("skill")) { null }
            assertEquals(original.copy(name = "edited", prompt = "new prompt", enabledSkillIds = listOf("skill")), receipt.profile)
            assertEquals(receipt.profile, store.read("a"))
        } finally { release.countDown() }
    }

    @Test fun concurrentFullProfileEditRejectsToggleAndFinalSaveBeforeAnyMemoryWrite() = runBlocking {
        val original = AssistantProfile("a", "A", "prompt")
        val store = MemoryTestStore(original)
        val page = AssistantEditSession(store, original)
        val other = original.copy(name = "external", prompt = "external prompt", enabledSkillIds = listOf("other"))
        store.save(other)
        var memoryWrites = 0
        try { page.toggles(false, emptySet()); fail("stale toggle accepted") } catch (_: StaleAssistantProfileException) {}
        val draft = "my draft with 验证码 as ordinary explanatory text"
        try {
            page.save("my name", draft, true, emptySet()) { memoryWrites++; AssistantMemorySnapshot(draft, "v2") }
            fail("stale final save accepted")
        } catch (_: StaleAssistantProfileException) {}
        assertEquals(0, memoryWrites)
        assertEquals(other, store.read("a"))
        assertEquals("my draft with 验证码 as ordinary explanatory text", draft)
    }

    @Test fun closedPageRejectsQueuedAndOldCallbacksWithoutTouchingReopenedPage() = runBlocking {
        val original = AssistantProfile("a", "A", "")
        val store = MemoryTestStore(original)
        val old = AssistantEditSession(store, original)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        store.beforeCas = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { old.toggles(false, emptySet()) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                try { old.toggles(true, setOf("late")); false } catch (_: ClosedAssistantEditorException) { true }
            }
            old.close()
            release.countDown(); first.await()
            assertTrue(queued.await())
            assertEquals(1, calls.get())
            val current = store.read("a")!!
            val reopened = AssistantEditSession(store, current)
            reopened.toggles(true, setOf("new"))
            try { old.save("old", "old", false, emptySet()) { error("must not write") }; fail("old page accepted") }
            catch (_: ClosedAssistantEditorException) {}
            assertEquals(listOf("new"), store.read("a")!!.enabledSkillIds)
            assertTrue(store.read("a")!!.memoryEnabled)
        } finally { release.countDown() }
    }

    @Test fun deletedProfileIsNotRecreatedAndManualMemoryHasNoSecretKeywordFilter() = runBlocking {
        val original = AssistantProfile("a", "A", "")
        val store = MemoryTestStore(original)
        val deletedPage = AssistantEditSession(store, original)
        store.delete("a")
        try { deletedPage.save("name", "", true, emptySet()) { error("must not write") }; fail("resurrected") }
        catch (_: StaleAssistantProfileException) {}
        assertNull(store.read("a"))
        store.save(original)
        val page = AssistantEditSession(store, original)
        val assets = MemoryTestAssets("a" to "initial")
        val manual = "不要存储验证码。密码管理器使用说明：正常用户手动文本。"
        val receipt = page.save("A", "", true, emptySet()) { assets.saveMemory("a", manual, "a-v1") }
        assertEquals(manual, receipt.memory!!.content)
    }
}
