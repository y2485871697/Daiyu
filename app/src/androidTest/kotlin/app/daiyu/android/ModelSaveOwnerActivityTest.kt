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
                fixture.settings.modelCatalog.readConfiguration {
                    entered.countDown(); check(released.await(10, TimeUnit.SECONDS))
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }.apply { start() }
        var primary: Throwable? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            work { released.countDown() }
        } catch (problem: Throwable) {
            primary = problem
            throw problem
        } finally {
            try {
                released.countDown(); holder.join(10_000); assertFalse(holder.isAlive)
                failure.get()?.let { throw it }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }
    fun testPausedQueuedSaveIsRejectedAndOnlyAnExplicitResumedSaveCanPersist() {
        val saved = fixture.settings.load(); setDraft("paused model save draft")
        openModels(); editModel("API Key", "resumed-explicit-model")
        reveal("model-save").assertIsDisplayed().assertIsEnabled()
        val save = retainedClick(activity, "model-save")
        withHeldCatalog { release ->
            controlMain { save(); assertTrue(fixture.controller.dataBusy) }
            realPauseResume(activity, atPause = {
                assertTrue(fixture.controller.dataBusy)
                release()
            })
            awaitControl("revoked save drained") { !fixture.controller.dataBusy }
        }
        assertEquals(saved, fixture.settings.load())
        awaitModelEditor()
        reveal("settings-secret-visibility:API Key").performClick()
        modelField("API Key").assertEditableText("resumed-explicit-model")
        reveal("model-save").performClick()
        awaitControl("explicit save completed") { !fixture.controller.dataBusy }
        assertEquals(saved.copy(apiKey = "resumed-explicit-model"), fixture.settings.load())
        awaitModelEditor()
        backToChat(activity)
        awaitUniqueTag("chat-draft"); assertDraft("paused model save draft")
        assertEquals(0, fixture.opens.get())
    }
    fun testRepeatedPauseWhileQueuedSaveDrainsRetainsDraftWithoutReauthorizingOldSave() {
        val saved = fixture.settings.load()
        openModels(); editModel("API Key", "twice-paused-model")
        reveal("model-save").assertIsDisplayed().assertIsEnabled()
        val save = retainedClick(activity, "model-save")
        withHeldCatalog { release ->
            controlMain { save(); assertTrue(fixture.controller.dataBusy) }
            realPauseResume(activity, atPause = { assertTrue(fixture.controller.dataBusy) })
            realPauseResume(activity, atPause = {
                assertTrue(fixture.controller.dataBusy)
                release()
            })
            awaitControl("twice-revoked save drained") { !fixture.controller.dataBusy }
        }
        assertEquals(saved, fixture.settings.load())
        awaitModelEditor()
        reveal("settings-secret-visibility:API Key").performClick()
        modelField("API Key").assertEditableText("twice-paused-model")
        reveal("model-save").performClick()
        awaitControl("explicit save after two pauses") { !fixture.controller.dataBusy }
        assertEquals(saved.copy(apiKey = "twice-paused-model"), fixture.settings.load())
        awaitModelEditor()
        backToChat(activity)
        awaitUniqueTag("chat-draft")
        assertEquals(0, fixture.opens.get())
    }
    fun testPauseDuringRestoreReadKeepsDraftForNextResumedEditor() {
        val saved = fixture.settings.load()
        openModels(); editModel("API Key", "restore-read-paused-model")
        withHeldCatalog { release ->
            realPauseResume(activity)
            node("page:主模型服务商").assertIsDisplayed()
            modelField("API Key").assertIsNotEnabled()
            reveal("model-save").assertIsNotEnabled()
            realPauseResume(activity, atPause = { release() })
        }
        assertEquals(saved, fixture.settings.load())
        awaitModelEditor()
        reveal("settings-secret-visibility:API Key").performClick()
        modelField("API Key").assertEditableText("restore-read-paused-model")
        assertEquals(0, fixture.opens.get())
    }
    fun testLeavingPageRevokesAQueuedSaveWithoutChangingTheStoredConnection() {
        val saved = fixture.settings.load(); setDraft("departing model save draft")
        openModels(); editModel("API Key", "must-not-be-written")
        reveal("model-save").assertIsDisplayed().assertIsEnabled()
        val save = retainedClick(activity, "model-save")
        withHeldCatalog { release ->
            controlMain { save(); assertTrue(fixture.controller.dataBusy) }
            controlMain { activity.onBackPressedDispatcher.onBackPressed() }
            awaitUniqueTag("provider-management")
            node("page:主模型服务商").assertDoesNotExist()
            controlMain { assertTrue(fixture.controller.dataBusy); release() }
            awaitControl("departed save drained") { !fixture.controller.dataBusy }
        }
        awaitUniqueTag("provider-management")
        backToChat(activity)
        awaitUniqueTag("chat-draft")
        assertEquals(saved, fixture.settings.load()); assertDraft("departing model save draft")
        assertEquals(0, fixture.opens.get())
    }
    fun testAStaleModelEditorCannotOverwriteAChangedStoredConfiguration() {
        val original = fixture.settings.load(); openModels()
        reveal("settings-secret-visibility:API Key").performClick()
        modelField("API Key").assertEditableText(original.apiKey)
        val changed = original.copy(model = "concurrent-saved-model")
        fixture.settings.save(changed)
        editModel("API Key", "stale-editor-model")
        reveal("model-save").performClick()
        awaitControl("stale save rejected") { !fixture.controller.dataBusy }
        assertEquals(changed, fixture.settings.load())
        awaitUniqueTag("provider-management")
        node("page:主模型服务商").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
