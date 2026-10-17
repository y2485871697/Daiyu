package app.daiyu.android

import android.content.ContextWrapper
import androidx.compose.ui.test.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ModelSaveOwnerActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "model-save-owner-${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }
    private fun withHeldCatalog(work: (release: () -> Unit) -> Unit) {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val holder = Thread {
            try {
                fixture.settings.modelCatalog.configurationChange {
                    entered.countDown(); check(released.await(10, TimeUnit.SECONDS))
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            work { released.countDown() }
        } finally {
            released.countDown(); holder.join(10_000); assertFalse(holder.isAlive)
            failure.get()?.let { throw it }
        }
    }
    fun testPausedQueuedSaveIsRejectedAndOnlyAnExplicitResumedSaveCanPersist() {
        val saved = fixture.settings.load(); setDraft("paused model save draft")
        openModels(); editModel("模型名", "resumed-explicit-model")
        withHeldCatalog { release ->
            reveal("model-save").performClick()
            awaitControl("save waiting for catalog") { fixture.controller.dataBusy }
            realPauseResume(activity)
            release()
            awaitControl("revoked save drained") { !fixture.controller.dataBusy }
        }
        assertEquals(saved, fixture.settings.load())
        modelField("模型名").assertEditableText("resumed-explicit-model")
        reveal("model-save").performClick()
        awaitControl("explicit save completed") { !fixture.controller.dataBusy }
        awaitUniqueTag("chat-draft"); assertDraft("paused model save draft")
        assertEquals(saved.copy(model = "resumed-explicit-model"), fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
    fun testLeavingPageRevokesAQueuedSaveWithoutChangingTheStoredConnection() {
        val saved = fixture.settings.load(); setDraft("departing model save draft")
        openModels(); editModel("模型名", "must-not-be-written")
        withHeldCatalog { release ->
            reveal("model-save").performClick()
            awaitControl("queued save") { fixture.controller.dataBusy }
            backToChat(activity); release()
            awaitControl("departed save drained") { !fixture.controller.dataBusy }
        }
        assertEquals(saved, fixture.settings.load()); assertDraft("departing model save draft")
        assertEquals(0, fixture.opens.get())
    }
    fun testAStaleModelEditorCannotOverwriteAChangedStoredConfiguration() {
        val original = fixture.settings.load(); openModels()
        val changed = original.copy(model = "concurrent-saved-model")
        fixture.settings.save(changed)
        editModel("模型名", "stale-editor-model")
        reveal("model-save").performClick()
        awaitControl("stale save rejected") { !fixture.controller.dataBusy }
        assertEquals(changed, fixture.settings.load())
        node("page:模型设置").assertExists()
        modelField("模型名").assertEditableText("stale-editor-model")
        assertEquals(0, fixture.opens.get())
    }
}
