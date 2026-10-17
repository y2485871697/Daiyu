package app.daiyu.android.title

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.storage.ModelSettings
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class TitleModelSettingsEditorTest : InstrumentationTestCase() {
    private fun store(onSave: () -> Unit = {}): TitleModelSettingsStore {
        val namespace = "title-editor-${UUID.randomUUID()}"
        var model: ModelSettings? = null
        return TitleModelSettingsStore(instrumentation.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE),
            { checkNotNull(model) }, { p, _, _ -> onSave(); model = p }, namespace)
    }
    private fun draft() = TitleModelDraft(TitleModelBinding("fixture", UUID.randomUUID().toString()),
        "https://title.invalid", "synthetic-title", "synthetic-key")

    fun testQueuedSaveRevokedBeforeWorkerAdmissionNeverTouchesStoreOrNewVisit() {
        val accesses = AtomicInteger()
        val settings = store()
        val old = TitleModelSettingsEditor { accesses.incrementAndGet(); settings }
        val work = old.prepareSave(draft())!!
        assertEquals(0, accesses.get())
        old.revoke()
        val fresh = TitleModelSettingsEditor { accesses.incrementAndGet(); settings }
        val result = work.runWorker()
        assertTrue(result.revoked); assertFalse(old.isCurrent(result)); assertFalse(fresh.isCurrent(result))
        assertEquals(0, accesses.get())
        assertFalse(settings.snapshot().enabled)
        assertNull(old.prepareLoad())
        assertNotNull(fresh.prepareLoad())
    }

    fun testAdmittedSaveMayFinishButDisposalNeverPublishesIntoReplacementVisit() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val settings = store { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val owner = TitleModelSettingsEditor { settings }
        val work = owner.prepareSave(draft())!!
        val result = AtomicReference<TitleModelSettingsEditor.Result>()
        Thread { try { result.set(work.runWorker()) } finally { done.countDown() } }.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            owner.revoke() // must not wait for the persistence monitor
            assertNull(owner.prepareDisable())
        } finally { release.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertFalse(result.get().failed)
        assertFalse(owner.isCurrent(result.get()))
        val fresh = TitleModelSettingsEditor { settings }
        assertFalse(fresh.isCurrent(result.get()))
        assertTrue(settings.snapshot().enabled)
    }

    fun testSaveResultInvalidatedByNewQueuedOperationAndDraftNeverExposesKeyInString() {
        val settings = store(); val owner = TitleModelSettingsEditor { settings }; val draft = draft()
        assertFalse(draft.toString().contains(draft.apiKey))
        val work = owner.prepareSave(draft)!!
        assertNull(owner.prepareLoad())
        val saved = work.runWorker()
        assertTrue(owner.isCurrent(saved)); assertTrue(saved.settings!!.enabled)
        owner.prepareDisable()!!
        assertFalse(owner.isCurrent(saved))
    }
}
