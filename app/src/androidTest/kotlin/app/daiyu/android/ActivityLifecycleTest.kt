package app.daiyu.android

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettingsStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real callbacks retained from the rendered host exercise the same stale-authority regressions. */
@Suppress("DEPRECATION")
class ActivityLifecycleTest : ComposeActivityTestCase() {
    private class Documents(private val document: File) : ContentProvider() {
        val opens = AtomicInteger()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens.incrementAndGet(); return ParcelFileDescriptor.open(document, ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
    }
    private class FixtureApplication(base: Context, private val resolver: MockContentResolver) : Application() {
        init { attachBaseContext(base) }
        override fun getContentResolver() = resolver
    }
    private class SelectionPreferences(private val raw: SharedPreferences) : SharedPreferences by raw {
        var failNextCommit = false
        override fun edit(): SharedPreferences.Editor {
            val editor = raw.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    if (failNextCommit) { failNextCommit = false; return false }
                    return editor.commit()
                }
            }
        }
    }
    private lateinit var application: ChatApplication
    private lateinit var fixture: FixtureApplication
    private lateinit var controller: ChatController
    private lateinit var preferences: SelectionPreferences
    private lateinit var provider: Documents
    private lateinit var directory: File
    private lateinit var aliasPrefix: String
    private lateinit var original: ChatController
    private var activity: MainActivity? = null
    private val modelCalls = AtomicInteger()
    private val uri = Uri.parse("content://activity-lifecycle/import.json")
    override fun setUp() {
        super.setUp()
        val id = UUID.randomUUID().toString(); aliasPrefix = "app.daiyu.android.test.lifecycle.$id"
        directory = File(instrumentation.targetContext.cacheDir, "activity-lifecycle-$id").apply { check(mkdir()) }
        val isolated = testContext(instrumentation.targetContext, "activity-lifecycle-$id-")
        val context = object : ContextWrapper(isolated) { override fun getFilesDir() = directory }
        provider = Documents(File(directory, "import.json")).apply {
            attachInfo(instrumentation.targetContext, android.content.pm.ProviderInfo().apply { authority = "activity-lifecycle"; exported = false })
        }
        val resolver = MockContentResolver(instrumentation.targetContext).apply { addProvider("activity-lifecycle", provider) }
        fixture = FixtureApplication(context, resolver)
        preferences = SelectionPreferences(fixture.getSharedPreferences("conversation-ui", 0))
        controller = ChatController(fixture, ConversationDatabase(fixture), ModelSettingsStore(fixture, aliasPrefix), preferences,
            { modelCalls.incrementAndGet(); error("no model request permitted") })
        awaitControl("ready") { controller.ready }; assertNull(controller.problem)
        val historical = controller.database.create(); controller.database.rename(historical.id, "historical fixture")
        File(directory, "import.json").writeText(controller.database.exportBackup()); controller.database.delete(historical.id)
        application = instrumentation.targetContext.applicationContext as ChatApplication
        mainThread { original = application.controller; application.controller = controller }
    }
    override fun tearDown() {
        try {
            mainThread { activity?.finish(); application.controller = original }; controller.close()
            fixture.deleteDatabase("conversations.db")
            fixture.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            fixture.getSharedPreferences("model-settings", 0).edit().clear().commit()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == aliasPrefix || it.startsWith("$aliasPrefix.") }.forEach { keys.deleteEntry(it) }
            directory.deleteRecursively()
        } finally { super.tearDown() }
    }
    private fun start(open: Boolean = false): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
        if (open) intent.setAction(Intent.ACTION_VIEW).setDataAndType(uri, "application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startMain(intent).also { activity = it }
    }
    private fun confirmation() { node("prompt-choice:1").performClick(); node("dialog-confirm").assertTextEquals("导入") }
    private fun assertNoImport(before: String) {
        instrumentation.waitForIdleSync(); awaitControl("idle") { !controller.dataBusy }
        assertEquals(0, provider.opens.get()); assertEquals(0L, controller.dataRevision)
        assertEquals(before, controller.database.exportBackup()); assertEquals(0, modelCalls.get())
    }
    fun testFailedSelectionPreferenceCommitKeepsInputAndDraftOwner() {
        val current = start(); val a = controller.database.create(); val b = controller.database.create()
        controller.database.draft(b.id, "B protected draft")
        mainThread { current.select(a) }; setDraft("A draft"); setSelection(1, 3)
        mainThread {
            preferences.failNextCommit = true
            try { current.select(b); fail("selection commit must reject") } catch (_: IllegalStateException) {}
        }
        assertDraft("A draft"); assertSelection(1, 3); assertEquals(a.id.value, preferences.getString("selected", null))
        setDraft("A amended draft"); setSelection(2, 4)
        assertEquals("A amended draft", controller.database.conversations().single { it.id == a.id }.draft)
        assertEquals("B protected draft", controller.database.conversations().single { it.id == b.id }.draft)
        mainThread { current.select(b) }; assertDraft("B protected draft")
        mainThread { current.select(a) }; assertDraft("A amended draft"); assertSelection(2, 4)
        mainThread {
            preferences.failNextCommit = true
            try { current.select(null); fail("new-chat selection commit must reject") } catch (_: IllegalStateException) {}
        }
        setDraft("A still owned"); assertEquals("", controller.database.draft())
        assertEquals("A still owned", controller.database.conversations().single { it.id == a.id }.draft)
        assertEquals(0, modelCalls.get())
    }
    fun testQueuedImportConfirmationRejectsFinishBeforeListenerDelivery() {
        val current = start(open = true); val before = controller.database.exportBackup(); confirmation()
        val positive = retainedClick(checkNotNull(activity), "dialog-confirm")
        // Deliver the real host callback after finish, without invoking a detached Compose node.
        mainThread { current.finish(); assertTrue(current.isFinishing); positive() }
        assertNoImport(before)
    }
    fun testDestroyedImportDialogButtonCannotImportAfterFrameworkRecreation() {
        val current = start(open = true); val before = controller.database.exportBackup(); confirmation()
        val positive = retainedClick(checkNotNull(activity), "dialog-confirm")
        activity = recreateActivity(current)
        node("dialog-confirm").assertDoesNotExist(); mainThread { positive() }; assertNoImport(before)
    }
    fun testCancelledImportConfirmationCannotAuthorizeRetainedPositiveCallback() {
        start(open = true); val before = controller.database.exportBackup(); confirmation()
        val positive = retainedClick(checkNotNull(activity), "dialog-confirm")
        node("dialog-cancel").performClick(); node("dialog-confirm").assertDoesNotExist()
        mainThread { positive() }; assertNoImport(before)
    }
    fun testBusyReplacementRevokesRetainedImportConfirmationBeforeAndAfterCompletion() {
        start(open = true); val before = controller.database.exportBackup(); confirmation()
        val positive = retainedClick(checkNotNull(activity), "dialog-confirm")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            mainThread { controller.dataTask(work = {
                entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); "完成"
            }) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            node("dialog-confirm").assertDoesNotExist(); mainThread { positive() }
            assertEquals(0, provider.opens.get()); assertEquals(before, controller.database.exportBackup())
            release.countDown(); awaitControl("busy finished") { !controller.dataBusy }
            mainThread { positive() }; assertNoImport(before)
        } finally { release.countDown() }
    }
    fun testDestroyedModelSettingsSaveCannotWriteAfterFrameworkRecreation() {
        val current = start(); openModels()
        editModel("Base URL", "https://fixture.invalid"); editModel("模型名", "fixture-model")
        editModel("API Key", "synthetic-lifecycle-key"); reveal("model-save")
        val save = retainedClick(checkNotNull(activity), "model-save")
        val before = HashMap(fixture.getSharedPreferences("model-settings", 0).all)
        activity = recreateActivity(current)
        node("model-save").assertDoesNotExist(); mainThread { save() }
        assertEquals(before, fixture.getSharedPreferences("model-settings", 0).all); assertEquals(0, modelCalls.get())
    }
}
