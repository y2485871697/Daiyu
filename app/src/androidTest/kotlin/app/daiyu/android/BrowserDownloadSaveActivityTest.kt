package app.daiyu.android

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.performClick
import app.daiyu.android.browser.download.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.Conversation
import app.daiyu.android.storage.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Main picker launch/callback -> controller dataTask -> coordinator trusted ref ->
 * content provider stream. The fixture creates real private completed bytes, NOT a fake
 * exporter, model path, parsed receipt, or FileProvider. No manifest/permissions added.
 */
@Suppress("DEPRECATION")
class BrowserDownloadSaveActivityTest : ComposeActivityTestCase() {
    private class Documents(private val root: File) : ContentProvider() {
        val opens = AtomicInteger()
        val openEntered = CountDownLatch(1)
        @Volatile var releaseOpen: CountDownLatch? = null
        var deny = false
        fun file(uri: Uri) = File(root, checkNotNull(uri.lastPathSegment))
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens.incrementAndGet()
            openEntered.countDown()
            // Deliberately non-cancellable provider call. Revocation is not a claim
            // that this call drained; dataBusy must remain true until it returns.
            releaseOpen?.let { check(it.await(30, TimeUnit.SECONDS)) { "fixture provider timed out" } }
            if (deny) throw SecurityException("fixture denied")
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
    }
    private class FixtureApplication(context: Context, private val resolver: MockContentResolver) : Application() {
        init { attachBaseContext(context) }
        override fun getContentResolver() = resolver
    }
    private lateinit var controller: ChatController
    private lateinit var fixture: FixtureApplication
    private lateinit var application: ChatApplication
    private lateinit var original: ChatController
    private lateinit var activity: MainActivity
    private lateinit var root: File
    private lateinit var documents: Documents
    private lateinit var conversation: Conversation
    private lateinit var reference: BrowserCompletedDownload
    private lateinit var receipt: BrowserDownloadReceipt
    private val bytes = ByteArray(24017) { (it % 251).toByte() }
    private val destination = Uri.parse("content://browser-save-fixture/original.bin")
    private val modelCalls = AtomicInteger()
    private val notice = java.util.concurrent.atomic.AtomicReference<String?>()
    private val observeNotice: () -> Unit = { controller.dataNotice?.let { notice.set(it) } }

    override fun setUp() {
        super.setUp()
        val id = UUID.randomUUID().toString()
        root = File(instrumentation.targetContext.cacheDir, "browser-save-$id").apply { check(mkdir()) }
        val isolated = testContext(instrumentation.targetContext, "browser-save-$id-")
        val context = object : ContextWrapper(isolated) { override fun getFilesDir() = root }
        documents = Documents(root).apply {
            attachInfo(instrumentation.targetContext, android.content.pm.ProviderInfo().apply {
                authority = "browser-save-fixture"; exported = false; grantUriPermissions = false
            })
        }
        fixture = FixtureApplication(context, MockContentResolver(instrumentation.targetContext).apply {
            addProvider("browser-save-fixture", documents)
        })
        val database = ConversationDatabase(fixture)
        conversation = database.create("default")
        val preferences = fixture.getSharedPreferences("conversation-ui", 0)
        preferences.edit().putString("selected", conversation.id.value).commit()
        controller = ChatController(fixture, database, ModelSettingsStore(fixture, "unused-browser-save-$id"),
            preferences, { modelCalls.incrementAndGet(); error("save must not invoke model") })
        val ready = CountDownLatch(1)
        val observer: () -> Unit = { if (controller.ready) ready.countDown() }
        mainThread { controller.observe(observer); observer() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        mainThread { controller.unobserve(observer) }
        assertNull(controller.problem)
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("original-main-$id", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        val downloads = BrowserDownloadCoordinator(sessions, BrowserPathMapper(File(root, "downloads").apply { check(mkdir()) }.toPath()))
        downloads.observeCompleted(controller.browserDownloads.recorder(conversation.id.value, owner))
        receipt = downloads.write(downloads.reserve(sessions.newTab(owner)), ByteArrayInputStream(bytes))
        reference = controller.completedBrowserDownloads(conversation.id).single()
        sessions.close(owner) // A normally completed run need not retain live browser authority.
        application = instrumentation.targetContext.applicationContext as ChatApplication
        mainThread { controller.observe(observeNotice); original = application.controller; application.controller = controller }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        ui.waitUntil(10_000) {
            var readyForSelection = false
            instrumentation.runOnMainSync {
                readyForSelection = MainActivity::class.java.getDeclaredField("selectionReady").apply {
                    isAccessible = true
                }.getBoolean(activity)
            }
            readyForSelection
        }
    }

    override fun tearDown() {
        try {
            if (::documents.isInitialized) documents.releaseOpen?.countDown()
            mainThread { if (::activity.isInitialized) activity.finish() }
            if (::controller.isInitialized) ui.waitUntil(10_000) { !controller.dataBusy }
            if (::original.isInitialized) mainThread { application.controller = original }
            if (::controller.isInitialized) controller.close()
            if (::fixture.isInitialized) fixture.deleteDatabase("conversations.db")
            if (::root.isInitialized) root.deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun openListing() {
        mainThread { MainActivity::class.java.getDeclaredMethod("openBrowserDownloadOutputs").apply {
            isAccessible = true
        }.invoke(activity) }
        ui.waitForIdle()
    }
    private fun pendingCode(): Int? = MainActivity::class.java.getDeclaredField("pendingBackupRequest").apply {
        isAccessible = true
    }.get(activity) as? Int

    private fun choose(result: Int = Activity.RESULT_OK, uri: Uri? = destination,
        awaitCopy: Boolean = true, beforeReturn: () -> Unit = {}): Int {
        openListing()
        val seen = CountDownLatch(1)
        var code = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/octet-stream", intent.type)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals("daiyu-browser-${reference.id}.download", intent.getStringExtra(Intent.EXTRA_TITLE))
                assertNull(intent.data)
                code = checkNotNull(pendingCode())
                assertTrue(code >= 14000)
                beforeReturn(); seen.countDown()
                return Instrumentation.ActivityResult(result, Intent().apply { if (uri != null) data = uri })
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            node("browser-download-save:${reference.id}").performClick()
            assertTrue(seen.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            ui.waitUntil(10_000) { pendingCode() == null && (!awaitCopy || !controller.dataBusy) }
            return code
        } finally { instrumentation.removeMonitor(monitor) }
    }

    // These invoke the real navigation/lifecycle boundaries (not their backing fields),
    // so an A -> B -> A transition must revoke even though final UI values match.
    private fun page(value: String) {
        MainActivity::class.java.getDeclaredMethod("setRetainedPage", String::class.java).apply {
            isAccessible = true
        }.invoke(activity, value)
    }
    private fun sidebar(value: Boolean) {
        MainActivity::class.java.getDeclaredMethod("setSidebarVisible", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(activity, value)
    }
    private fun lifecycle(method: String) {
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    }
    private fun listing(): Any? = MainActivity::class.java.getDeclaredField("browserDownloadOutputs\$delegate").apply {
        isAccessible = true
    }.get(activity).let { (it as androidx.compose.runtime.State<*>).value }
    private fun deliver(code: Int) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, code, Activity.RESULT_OK, Intent().setData(destination))
    }

    fun testLeavingChatAndSidebarRoundTripsRevokeListingAndOldCallback() {
        choose(beforeReturn = {
            assertNotNull(listing())
            page("settings"); page("chat")
            assertNull(listing())
            assertNull(pendingCode())
        })
        assertEquals(0, documents.opens.get())
        choose(beforeReturn = {
            sidebar(true); sidebar(false)
            assertNull(listing())
            assertNull(pendingCode())
        })
        assertEquals(0, documents.opens.get())
        assertFalse(documents.file(destination).exists())
    }

    fun testSameConversationReentryRevokesPickerAndListing() {
        choose(beforeReturn = {
            activity.select(conversation)
            assertNull(listing())
            assertNull(pendingCode())
        })
        assertEquals(0, documents.opens.get())
    }

    fun testNewVisitCannotAdoptOldPickerEvenWhileNewPickerIsPending() {
        val oldCode = choose(beforeReturn = {
            val oldListing = listing()
            page("settings"); page("chat")
            MainActivity::class.java.getDeclaredMethod("openBrowserDownloadOutputs").apply {
                isAccessible = true
            }.invoke(activity)
            assertNotNull(listing())
            assertNotSame(oldListing, listing())
            assertNull(pendingCode()) // A new list grants no authority to the old result.
            val stale = checkNotNull(oldListing)
            try {
                MainActivity::class.java.getDeclaredMethod("pickBrowserDownloadOriginal",
                    stale.javaClass, BrowserCompletedDownload::class.java).apply { isAccessible = true }
                    .invoke(activity, stale, reference)
                fail("stale listing must not launch a picker on the new visit")
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                assertTrue(failure.cause is IllegalStateException)
            }
            assertNull(pendingCode())
        })
        assertEquals(0, documents.opens.get())
        val newCode = choose(Activity.RESULT_CANCELED, beforeReturn = {
            val pending = pendingCode()
            deliver(oldCode)
            assertEquals(pending, pendingCode())
        })
        assertTrue(newCode > oldCode)
        mainThread { deliver(oldCode); deliver(newCode) }
        assertEquals(0, documents.opens.get())
    }

    fun testDocumentsUiPausePreservesOnlyItsOutstandingSelection() {
        try {
            choose(beforeReturn = {
                val pending = pendingCode()
                lifecycle("onPause")
                assertEquals(pending, pendingCode())
                assertNotNull(listing())
            }) // Result can arrive before onResume; necessary picker pause isn't revocation.
            assertEquals(1, documents.opens.get())
            assertTrue(bytes.contentEquals(documents.file(destination).readBytes()))
        } finally { mainThread { lifecycle("onResume") } }
    }

    fun testBackgroundCopyCancellationSurvivesResumeAndNewVisitWithoutFalseDrain() {
        val release = CountDownLatch(1)
        documents.releaseOpen = release
        try {
            val oldCode = choose(awaitCopy = false)
            assertTrue(documents.openEntered.await(5, TimeUnit.SECONDS))
            assertTrue(controller.dataBusy)
            mainThread {
                lifecycle("onPause")
                assertNull(listing())
                assertNull(pendingCode())
                lifecycle("onResume")
            }
            // Provider is still blocked: cancellation must not pretend the worker drained.
            assertTrue(controller.dataBusy)
            openListing() // New visit cannot revive the captured, now-revoked worker token.
            mainThread { assertNotNull(listing()); deliver(oldCode) }
            assertEquals(1, documents.opens.get())
            assertTrue(controller.dataBusy)
            release.countDown()
            ui.waitUntil(10_000) { !controller.dataBusy }
            assertTrue(notice.get()?.startsWith("浏览器原件保存失败") == true)
            assertEquals(0L, documents.file(destination).length())
            assertEquals(1, documents.opens.get())
            assertEquals(0, modelCalls.get())
        } finally { release.countDown() }
    }

    fun testProductionMainSaveCopiesExactCompletedBytesWithoutModelOrAutomaticPublication() {
        openListing()
        assertEquals(0, documents.opens.get())
        assertFalse(documents.file(destination).exists())
        choose()
        assertEquals(1, documents.opens.get())
        assertTrue(bytes.contentEquals(documents.file(destination).readBytes()))
        assertEquals(reference.sha256, MessageDigest.getInstance("SHA-256").digest(documents.file(destination).readBytes())
            .joinToString("") { "%02x".format(it) })
        assertEquals("浏览器原件已保存；原字节及 SHA-256 校验通过", notice.get())
        assertEquals(0, modelCalls.get())
        assertTrue(receipt.path.toFile().exists())
    }

    fun testCancelMissingUriAndNonContentUriNeverOpenDestination() {
        choose(Activity.RESULT_CANCELED)
        choose(uri = null)
        choose(uri = Uri.fromFile(File(root, "not-a-document")))
        assertEquals(0, documents.opens.get())
        assertFalse(documents.file(destination).exists())
        assertEquals(0, modelCalls.get())
    }

    fun testOldEditorOwnerAndLateDuplicateCallbacksAreRejected() {
        val code = choose(beforeReturn = {
            val field = MainActivity::class.java.getDeclaredField("editorGeneration").apply { isAccessible = true }
            field.setLong(activity, field.getLong(activity) + 1)
        })
        assertEquals(0, documents.opens.get())
        fun deliverOld() {
            MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                .invoke(activity, code, Activity.RESULT_OK, Intent().setData(destination))
        }
        val newCode = choose(Activity.RESULT_CANCELED, beforeReturn = {
            val current = pendingCode()
            deliverOld() // Old callback while a different picker is outstanding.
            assertEquals(current, pendingCode())
        })
        assertTrue(newCode > code)
        mainThread { deliverOld() }
        assertEquals(0, documents.opens.get())
        assertFalse(documents.file(destination).exists())
    }

    // Static originals deliberately reuse this real Activity/provider/browser fixture: these
    // tests exercise the shared request-code allocator, not a parallel picker abstraction.
    private fun seedStaticOriginal(): StaticGenerationOutput {
        val credentials = mutableMapOf<String, String>()
        val scope = app.daiyu.android.model.generation.GenerationSettingsStore(
            fixture.getSharedPreferences(app.daiyu.android.model.generation.GenerationSettingsStore.METADATA_NAMESPACE, 0),
            { credentials.getValue(it) }, { id, value -> credentials[id] = value }).save(
            app.daiyu.android.model.generation.GenerationSettingsValues(listOf(
                app.daiyu.android.model.generation.GenerationProfileSettings(label = "offline static save",
                    kind = app.daiyu.android.model.generation.GenerationKind.IMAGE,
                    protocol = app.daiyu.android.model.generation.GenerationProtocol.OPENAI_IMAGES_JSON,
                    endpoint = "https://example.invalid/images/generations", model = "offline", apiKey = "in-memory-fixture")),
                0, false, emptySet()), null).accountScope
        val run = app.daiyu.android.domain.RunId(UUID.randomUUID().toString())
        val tool = app.daiyu.android.domain.MessageBlock.Tool(app.daiyu.android.domain.ToolInvocation(
            app.daiyu.android.domain.ToolCallId(UUID.randomUUID().toString()), "generate_images", "{}"), "static history only")
        val assistant = app.daiyu.android.domain.Message(app.daiyu.android.domain.MessageId(UUID.randomUUID().toString()),
            conversation.id, run, app.daiyu.android.domain.Message.ASSISTANT_ROLE, listOf(tool), app.daiyu.android.domain.RunStatus.RUNNING)
        controller.database.send(assistant.copy(id = app.daiyu.android.domain.MessageId(UUID.randomUUID().toString()),
            role = app.daiyu.android.domain.Message.USER_ROLE,
            blocks = listOf(app.daiyu.android.domain.MessageBlock.Text("body", "offline original")),
            status = app.daiyu.android.domain.RunStatus.COMPLETED), assistant)
        controller.database.save(assistant.copy(status = app.daiyu.android.domain.RunStatus.COMPLETED))
        val hash = app.daiyu.android.context.PrivateBlobs.digest(staticBytes)
        val origin = GenerationAttemptBinding("origin-conversation", "origin-owner", "origin-run", "origin-call", "generate_images")
        val output = StaticGenerationOutput(StaticGenerationProvenance("offline", origin.operationId, "origin-item", 0,
            origin.conversationId, origin.assistantMessageId, origin.runId, origin.toolCallId, origin.toolName, "a".repeat(64),
            app.daiyu.android.model.generation.GenerationProtocol.OPENAI_IMAGES_JSON, "genasset-v1-${"b".repeat(64)}-$hash",
            app.daiyu.android.model.generation.GenerationKind.IMAGE, "image/png", staticBytes.size.toLong(), hash, 1, 1, 0),
            StaticGenerationDestination(scope, conversation.id.value, assistant.id.value, run.value, tool.invocation.id.value, tool.invocation.name))
        val assets = app.daiyu.android.media.StaticGenerationAssets(File(root, "static-generation-assets"))
        val lease = assets.prepare(output, app.daiyu.android.media.StaticGenerationOriginalReader { staticBytes.inputStream() })
        assets.publish(lease)
        app.daiyu.android.context.DatabaseTransactions { controller.database.writableDatabase }.run { db ->
            app.daiyu.android.backup.BackupHistoryRows.install(db)
            app.daiyu.android.backup.StaticGenerationCodec.saveInTransaction(db, output,
                app.daiyu.android.backup.staticMessageRows(app.daiyu.android.context.ContextService.read(db, conversation.id)))
        }
        assets.settle(lease, app.daiyu.android.media.StaticGenerationSettlement.COMMITTED)
        return output
    }
    private val staticBytes get() = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kF9sAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(activity)
    private fun openStaticListing() {
        mainThread { MainActivity::class.java.getDeclaredMethod("openGenerationOutputs").apply {
            isAccessible = true
        }.invoke(activity) }
        ui.waitUntil(10_000) {
            ((field("staticGenerationOutputs\$delegate") as androidx.compose.runtime.State<*>).value as? List<*>)?.isNotEmpty() == true
        }
    }
    private fun chooseStatic(output: StaticGenerationOutput, result: Int = Activity.RESULT_CANCELED,
        uri: Uri? = destination, beforeReturn: () -> Unit = {}): Int {
        openStaticListing()
        var code = 0
        val seen = CountDownLatch(1)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("image/png", intent.type)
                assertEquals("daiyu-static-original.png", intent.getStringExtra(Intent.EXTRA_TITLE))
                code = checkNotNull(pendingCode()); assertTrue(code in 14000..65534)
                assertNotNull(field("pendingStaticOriginal"))
                beforeReturn(); seen.countDown()
                return Instrumentation.ActivityResult(result, Intent().apply { if (uri != null) data = uri })
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            mainThread { MainActivity::class.java.getDeclaredMethod("pickStaticGenerationOriginal", StaticGenerationOutput::class.java)
                .apply { isAccessible = true }.invoke(activity, output) }
            assertTrue(seen.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            ui.waitUntil(10_000) { pendingCode() == null && !controller.dataBusy }
            return code
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun recreateMain() {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            mainThread { activity.recreate() }
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as MainActivity
            instrumentation.waitForIdleSync()
            ui.waitUntil(10_000) { field("selectionReady") == true }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun testStaticAndBrowserShareMonotonicCodesAndStaleCancelDuplicateCannotSaveNewSelection() {
        val output = seedStaticOriginal()
        val browserCode = choose(Activity.RESULT_CANCELED)
        val cancelled = chooseStatic(output)
        assertTrue(cancelled > browserCode)
        val second = chooseStatic(output, beforeReturn = {
            val current = pendingCode()
            deliver(cancelled); deliver(browserCode); deliver(cancelled)
            assertEquals(current, pendingCode()); assertNotNull(field("pendingStaticOriginal"))
        })
        assertTrue(second > cancelled)
        mainThread { deliver(second); deliver(cancelled) }
        assertEquals(0, documents.opens.get())
        val saved = chooseStatic(output, Activity.RESULT_OK)
        assertTrue(saved > second)
        assertEquals(1, documents.opens.get())
        assertTrue(staticBytes.contentEquals(documents.file(destination).readBytes()))
        val afterSave = chooseStatic(output, beforeReturn = {
            val current = pendingCode(); deliver(saved); assertEquals(current, pendingCode())
        })
        assertTrue(afterSave > saved)
        assertEquals(1, documents.opens.get()); assertEquals(0, modelCalls.get())
        assertTrue(bytes.contentEquals(receipt.path.toFile().readBytes()))
    }

    fun testStaticPickerOnlyFirstHopSurvivesAndSecondUnrelatedPauseRevokesOutstandingVisit() {
        val output = seedStaticOriginal()
        var firstVisit: java.util.concurrent.atomic.AtomicBoolean? = null
        chooseStatic(output, Activity.RESULT_OK, beforeReturn = {
            firstVisit = field("staticGenerationVisit") as java.util.concurrent.atomic.AtomicBoolean
            lifecycle("onPause")
            assertTrue(firstVisit!!.get()); assertNotNull(pendingCode())
            lifecycle("onResume")
            lifecycle("onPause") // no picker result arrived: this is NOT the first external hop
            assertFalse(firstVisit!!.get()); assertNull(pendingCode()); assertNull(field("pendingStaticOriginal"))
            lifecycle("onResume")
        })
        assertEquals(0, documents.opens.get())
        chooseStatic(output, Activity.RESULT_OK, beforeReturn = {
            assertNotSame(firstVisit, field("staticGenerationVisit"))
            lifecycle("onPause"); lifecycle("onResume") // a fresh pick gets exactly one fresh hop
        })
        assertEquals(1, documents.opens.get())
        assertTrue(staticBytes.contentEquals(documents.file(destination).readBytes()))
        mainThread { lifecycle("onPause"); lifecycle("onResume") }
        assertNull(field("staticGenerationVisit")); assertEquals(0, modelCalls.get())
    }

    fun testStaticSavedStateRetainsOnlyNextCodeAndRecreationRejectsOldCallback() {
        val output = seedStaticOriginal()
        val state = android.os.Bundle()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val old: Int
        try {
            old = chooseStatic(output, beforeReturn = {
                MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", android.os.Bundle::class.java)
                    .apply { isAccessible = true }.invoke(activity, state)
                assertEquals(checkNotNull(pendingCode()) + 1, state.getInt("browser-save-next"))
                assertFalse(state.keySet().any { it.contains("static", ignoreCase = true) })
                activity.recreate() // request replacement while the exact original picker is still pending
            })
            activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as MainActivity
            instrumentation.waitForIdleSync()
            ui.waitUntil(10_000) { field("selectionReady") == true }
        } finally { instrumentation.removeMonitor(monitor) }
        assertNull(field("pendingStaticOriginal")); assertNull(field("staticGenerationVisit")); assertNull(pendingCode())
        mainThread { deliver(old) }
        assertEquals(0, documents.opens.get())
        val next = chooseStatic(output, beforeReturn = {
            val current = pendingCode(); deliver(old); assertEquals(current, pendingCode())
        })
        assertTrue(next > old)
        assertEquals(0, documents.opens.get()); assertEquals(0, modelCalls.get())
    }

    fun testUnknownBranchIsOfferedToNewForegroundAndRecreatedActivityUntilExplicitDismiss() {
        val output = seedStaticOriginal()
        // Main may already have initialized messageActionStore while preparing its first
        // rendered snapshot. Fault its actual retained asset dependency, not a replacement lazy.
        val assets = (ChatController::class.java.getDeclaredField("staticGenerationAssets\$delegate").apply {
            isAccessible = true
        }.get(controller) as Lazy<*>).value
        val publicationFault: () -> Unit = { throw java.io.IOException("Activity UNKNOWN fixture") }
        app.daiyu.android.media.StaticGenerationAssets::class.java.getDeclaredField("afterAtomicMove").apply {
            isAccessible = true
        }.set(assets, publicationFault)
        val loaded = CountDownLatch(1)
        val snapshot = java.util.concurrent.atomic.AtomicReference<MessageActionSnapshot>()
        mainThread { assertTrue(controller.loadMessageActionSnapshot(conversation.id) { snapshot.set(it); loaded.countDown() }) }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        val obsolete = AtomicInteger()
        mainThread {
            val host = ControllerMessageActionHost(controller)
            host.bind(snapshot.get(), { true }) { obsolete.incrementAndGet() }.onBranch(snapshot.get().targets.keys.first())
            host.invalidate(); lifecycle("onPause"); page("settings")
        }
        ui.waitUntil(10_000) { !controller.dataBusy && controller.pendingBranchOutcome() != null }
        val receipt = checkNotNull(controller.pendingBranchOutcome())
        val locator = checkNotNull(receipt.locator)
        assertEquals(0, obsolete.get())
        mainThread { lifecycle("onResume") }
        ui.waitForIdle()
        assertSame(receipt, (field("unknownBranchOutcome\$delegate") as androidx.compose.runtime.State<*>).value)
        assertEquals(conversation.id, (field("selected") as Conversation).id)
        node("branch-unknown-inspect").performClick()
        ui.waitUntil(10_000) { !controller.dataBusy }
        assertSame(receipt, controller.pendingBranchOutcome())
        recreateMain()
        mainThread { page("settings") } // global surface must not depend on the composer being mounted
        assertSame(receipt, (field("unknownBranchOutcome\$delegate") as androidx.compose.runtime.State<*>).value)
        assertEquals(conversation.id, (field("selected") as Conversation).id)
        val slot = locator.staticIdentities.single().removePrefix("genstatic-v1-")
        val retainedOriginal = File(root, "static-generation-assets/generation-static-v1/$slot/original")
        val retainedLease = File(root, "static-generation-assets/generation-static-v1/$slot.lease")
        assertTrue(retainedOriginal.isFile); assertTrue(retainedLease.isFile)
        node("branch-unknown-dismiss").performClick()
        assertNull(controller.pendingBranchOutcome())
        recreateMain()
        assertNull((field("unknownBranchOutcome\$delegate") as androidx.compose.runtime.State<*>).value)
        assertTrue(retainedOriginal.isFile); assertTrue(retainedLease.isFile)
        assertEquals(1, controller.database.conversations().size)
        val service = app.daiyu.android.backup.NeutralBackupService(fixture, controller.database)
            .withStaticAssets(app.daiyu.android.media.StaticGenerationAssets(File(root, "static-generation-assets")))
        assertTrue(staticBytes.contentEquals(service.staticGenerationOriginal(output)))
        assertEquals(0, documents.opens.get()); assertEquals(0, modelCalls.get())
    }

    fun testStaticMissingAndNonContentUriDoNotOpenProvider() {
        val output = seedStaticOriginal()
        chooseStatic(output, Activity.RESULT_OK, uri = null)
        chooseStatic(output, Activity.RESULT_OK, uri = Uri.fromFile(File(root, "not-content")))
        assertEquals(0, documents.opens.get()); assertEquals(0, modelCalls.get())
    }

    fun testCorruptSourceAndRevokedProviderNeverReportSaveSuccess() {
        receipt.path.toFile().writeBytes(bytes.copyOf().apply { this[0] = 99 })
        choose()
        assertEquals(0, documents.opens.get())
        assertTrue(notice.get()?.startsWith("浏览器原件保存失败") == true)
        receipt.path.toFile().writeBytes(bytes)
        documents.deny = true
        choose()
        assertEquals(1, documents.opens.get())
        assertTrue(notice.get()?.startsWith("浏览器原件保存失败") == true)
        assertFalse(documents.file(destination).exists())
        assertEquals(0, modelCalls.get())
    }
}
