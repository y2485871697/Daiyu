package app.daiyu.android

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.*
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Activity callbacks and picker launches, with a private in-process content provider.
 * No provider is registered/exported and no test permission is added to either manifest.
 * ControllerBackupTest owns the large backup/schema/credential fixtures.
 */
@Suppress("DEPRECATION")
class BackupActivityTest:ComposeActivityTestCase() {
    override val engineeringUi = true
    private class Documents(private val directory:File):ContentProvider() {
        val opens=AtomicInteger()
        var deny=false
        @Volatile var pipe: ImportPipe? = null
        fun file(uri:Uri)=File(directory,uri.lastPathSegment ?: error("missing document"))
        override fun onCreate()=true
        override fun getType(uri:Uri)=if (uri.lastPathSegment?.endsWith(".zip") == true) "application/zip" else "application/json"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            opens.incrementAndGet()
            if(deny) throw SecurityException("test revoked grant")
            pipe?.let { return it.open() }
            return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("unused")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=error("unused")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=error("unused")
    }
    private class ImportPipe(private val body: ByteArray) {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        private val opens = AtomicInteger()
        val started get() = opens.get() != 0
        fun open(): ParcelFileDescriptor {
            check(opens.incrementAndGet() == 1)
            val descriptors = ParcelFileDescriptor.createReliablePipe()
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
                        output.write(ByteArray(8192) { 0x20.toByte() }); output.flush()
                        check(release.await(30, TimeUnit.SECONDS)) { "backup pipe was not released" }
                        output.write(body)
                    }
                } catch (caught: Throwable) {
                    failure.set(caught)
                    runCatching { descriptors[1].closeWithError("backup test pipe failed") }
                } finally { finished.countDown() }
            }, "backup-test-pipe").apply { isDaemon = true; start() }
            return descriptors[0]
        }
    }
    private var importPipe: ImportPipe? = null

    private class FixtureApplication(base:Context,private val resolver:MockContentResolver):Application() {
        init {attachBaseContext(base)}
        override fun getContentResolver()=resolver
        var failNextRestoreCommit=false
        override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
            val raw=super.getSharedPreferences(name,mode)
            if(name!="model-settings") return raw
            return object:android.content.SharedPreferences by raw {
                override fun edit():android.content.SharedPreferences.Editor {
                    val editor=raw.edit()
                    return object:android.content.SharedPreferences.Editor by editor {
                        override fun commit():Boolean {
                            val committed=editor.commit()
                            if(failNextRestoreCommit) {failNextRestoreCommit=false;return false}
                            return committed
                        }
                    }
                }
            }
        }
    }
    private lateinit var application:ChatApplication
    private lateinit var fixtureApp:FixtureApplication
    private lateinit var controller:ChatController
    private val observedDataNotice = AtomicReference<String?>()
    private val noticeObserver: () -> Unit = { controller.dataNotice?.let { observedDataNotice.set(it) } }
    private lateinit var provider:Documents
    private lateinit var directory:File
    private var original:ChatController?=null
    private var activity:MainActivity?=null
    private val fixtureBalanceHost = app.daiyu.android.balance.ProviderBalanceRuntimeHost(
        draftQuery = app.daiyu.android.balance.BalanceQuery { error("Unexpected fixture balance request") },
        factory = { app.daiyu.android.balance.BalanceRuntime(
            app.daiyu.android.balance.ProviderBalanceStore(object : app.daiyu.android.balance.BalancePersistence {
                private var bytes: ByteArray? = null
                override fun read() = bytes?.clone()
                override fun write(record: ByteArray?) { bytes = record?.clone() }
            }), app.daiyu.android.balance.BalanceQuery { error("Unexpected fixture balance request") }) })
    private val modelCalls=AtomicInteger()
    private val textUri=Uri.parse("content://activity-backup/text.json")
    private val completeUri=Uri.parse("content://activity-backup/complete.json")
    private fun main(work:()->Unit) {
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {failure.set(t)}}
        failure.get()?.let {throw it}
    }
    private fun awaitState(predicate:()->Boolean) {
        val done=CountDownLatch(1)
        val listener:()->Unit={if(predicate()) done.countDown()}
        main {controller.observe(listener);listener()}
        try {assertTrue("controller callback timeout",done.await(10,TimeUnit.SECONDS));instrumentation.waitForIdleSync()}
        finally {main {controller.unobserve(listener)}}
    }
    override fun setUp() {
        super.setUp()
        val id=UUID.randomUUID().toString()
        directory=File(instrumentation.targetContext.cacheDir,"activity-backup-$id").apply {check(mkdir())}
        val isolated=testContext(instrumentation.targetContext,"activity-backup-$id-")
        val context=object:ContextWrapper(isolated) {override fun getFilesDir()=directory}
        provider=Documents(directory).apply {
            attachInfo(instrumentation.targetContext,android.content.pm.ProviderInfo().apply {
                authority="activity-backup";exported=false;grantUriPermissions=false
            })
        }
        val resolver=MockContentResolver(instrumentation.targetContext).apply {addProvider("activity-backup",provider)}
        fixtureApp=FixtureApplication(context,resolver)
        val database=ConversationDatabase(fixtureApp)
        controller=ChatController(fixtureApp,database,ModelSettingsStore(fixtureApp,"unused-activity-$id"),
            fixtureApp.getSharedPreferences("conversation-ui",0),{modelCalls.incrementAndGet();error("must not execute imported history")},
            providerBalanceHost = { fixtureBalanceHost })
        awaitState {controller.ready}
        assertNull(controller.problem)
        main { controller.observe(noticeObserver) }
        // Small real backup with historical messages/tool result; importing must not replay them.
        val conversation=database.create("default")
        val run=RunId("historical-run")
        val user=Message(MessageId("historical-user"),conversation.id,run,"user",listOf(MessageBlock.Text("u","question")),RunStatus.COMPLETED)
        val assistant=Message(MessageId("historical-assistant"),conversation.id,run,"assistant",
            listOf(MessageBlock.Text("a","historical answer"),
                MessageBlock.Tool(ToolInvocation(ToolCallId("historical-tool"),"get_battery","{}"),"{\"level\":50}")),RunStatus.RUNNING)
        database.send(user,assistant);database.save(assistant.copy(status=RunStatus.COMPLETED))
        provider.file(textUri).writeText(database.exportBackup())
        provider.file(completeUri).writeText(NeutralBackupService(fixtureApp,database).exportPackage())
        database.delete(conversation.id)
        application=instrumentation.targetContext.applicationContext as ChatApplication
        main {original=application.controller;application.controller=controller}
    }
    override fun tearDown() {
        var pipeFinished = true
        try {
            importPipe?.let { pipe ->
                pipe.release.countDown()
                if (pipe.started) pipeFinished = pipe.finished.await(10, TimeUnit.SECONDS)
            }
            main {activity?.finish()}
            instrumentation.waitForIdleSync()
            main {original?.let {application.controller=it}}
            main { controller.unobserve(noticeObserver) }
            controller.close();fixtureBalanceHost.close();fixtureApp.deleteDatabase("conversations.db")
            fixtureApp.getSharedPreferences("conversation-ui",0).edit().clear().commit()
            fixtureApp.getSharedPreferences("model-settings",0).getString("key-alias",null)?.let {alias->
                if(alias.startsWith("unused-activity-")) java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(alias)
            }
            fixtureApp.getSharedPreferences("model-settings",0).edit().clear().commit()
            directory.deleteRecursively()
            check(pipeFinished) { "backup pipe writer did not finish" }
        } finally {super.tearDown()}
    }
    private fun start(intent:Intent=Intent(instrumentation.targetContext,MainActivity::class.java)):MainActivity {
        val current=startMain(intent)
        activity=current;instrumentation.waitForIdleSync();return current
    }
    private fun opened(uri:Uri=textUri,mime:String="application/json",grant:Boolean=true)=
        Intent(Intent.ACTION_VIEW).setClass(instrumentation.targetContext,MainActivity::class.java).setDataAndType(uri,mime).apply {
            if(grant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    private fun dialog():Any?=(MainActivity::class.java.getDeclaredField("prompt\$delegate").apply {isAccessible=true}.get(activity) as androidx.compose.runtime.State<*>).value
    private fun pending():Int?=MainActivity::class.java.getDeclaredField("pendingBackupRequest").apply {isAccessible=true}.get(activity) as? Int
    private fun click(label:String) {
        if(label=="模型设置") {openModels();return}
        if(ui.onAllNodesWithTag("page:备份与导入").fetchSemanticsNodes().isEmpty()) {
            openSettings();reveal("settings-row:数据备份","设置").performClick()
        }
    }
    private fun press(which:Int) {
        node(if(which==DialogInterface.BUTTON_NEGATIVE) "dialog-cancel" else "dialog-confirm").performClick()
        ui.waitForIdle()
    }
    private fun setField(name:String,value:Any?) {
        ChatController::class.java.getDeclaredField(name).apply {isAccessible=true}.set(controller,value)
    }
    private fun gate(name:String?) {
        when(name) {
            "active"->setField("active",Message(MessageId("active-gate"),ConversationId("active-gate"),RunId("active-gate"),"assistant",emptyList(),RunStatus.RUNNING))
            "busy"->setField("dataBusy",true)
            "recovery"->setField("problem","会话恢复失败，原数据已保留")
            "not-ready"->setField("ready",false)
            null->{setField("active",null);setField("dataBusy",false);setField("problem",null);setField("ready",true)}
        }
    }
    private fun unchanged(before:String,revision:Long=0) {
        assertEquals(before,controller.database.exportBackup());assertEquals(revision,controller.dataRevision)
        assertEquals(0,modelCalls.get())
    }
    /** Monitor returns a platform ActivityResult to the actual startActivityForResult call. */
    private fun pendingLinux(): Boolean = MainActivity::class.java.getDeclaredField("pendingBackupIncludeLinux")
        .apply { isAccessible = true }.getBoolean(activity)
    private fun picker(code:Int,result:Int=Activity.RESULT_OK,uri:Uri?=null,includeLinux:Boolean=false,beforeReturn:()->Unit={}) {
        val actualCode = when (code) { 101, 103 -> 103; 102, 104, 107 -> 107; else -> error("unsupported backup action") }
        val exporting = actualCode == 103
        val observed=CountDownLatch(1)
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                assertEquals(if(exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT,intent.action)
                assertEquals(if (exporting) { if (includeLinux) "application/zip" else "application/json" } else "*/*", intent.type)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals(actualCode,pending())
                if(exporting) {
                    assertEquals(if (includeLinux) "daiyu-linux-backup.zip" else "daiyu-complete.json",intent.getStringExtra(Intent.EXTRA_TITLE))
                    assertEquals(includeLinux, pendingLinux())
                }
                else assertEquals(listOf("application/json", "application/zip", "application/octet-stream", "text/plain"),
                    intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
                beforeReturn();observed.countDown()
                return Instrumentation.ActivityResult(result,if(uri==null) Intent() else Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            click("备份与导入")
            if (exporting) {
                val option = node("backup-include-full-linux")
                val checked = option.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On
                if (checked != includeLinux) option.performClick()
            }
            node(if (exporting) "backup-export" else "backup-import").performClick()
            assertTrue("SAF launch not observed",observed.await(5,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            main {assertNull("request must be consumed at callback",pending()); assertFalse(pendingLinux())}
        } finally {instrumentation.removeMonitor(monitor)}
    }
    fun testLinuxPickerUsesZipAndDoesNotOpenTargetWhenNoEnvironmentIsAvailable() {
        start(); val before = controller.database.exportBackup()
        val output = Uri.parse("content://activity-backup/linux.zip")
        picker(103, uri = output, includeLinux = true)
        awaitState { !controller.dataBusy && observedDataNotice.get() != null }
        assertEquals("请先在 Linux 设置选择可用环境，再导出完整环境", observedDataNotice.get())
        assertEquals(0, provider.opens.get())
        assertFalse(provider.file(output).exists())
        unchanged(before)
    }
    fun testCanceledLinuxPickerClearsTheCapturedOptionWithoutOpeningTarget() {
        start(); val before = controller.database.exportBackup()
        picker(103, Activity.RESULT_CANCELED, completeUri, includeLinux = true)
        assertFalse(pendingLinux())
        assertEquals(0, provider.opens.get())
        unchanged(before)
    }

    fun testBothProductPickerRequestsCanceledAndMissingUriDoNoWork() {
        start();val before=controller.database.exportBackup()
        for(code in listOf(103,107)) {
            picker(code,Activity.RESULT_CANCELED,textUri)
            main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
            picker(code,Activity.RESULT_OK,null)
            main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
            picker(code,uri=Uri.fromFile(provider.file(textUri)))
            main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
        }
    }
    fun testBothProductPickerCallbacksRejectActiveBusyAndRecoveryAfterLaunch() {
        start();val before=controller.database.exportBackup()
        for(code in listOf(103,107)) for(state in listOf("active","busy","recovery","not-ready")) {
            picker(code,uri=if(code>=103) completeUri else textUri,beforeReturn={gate(state)})
            main {assertNull(dialog());gate(null)}
            assertEquals(0,provider.opens.get());unchanged(before)
        }
    }
    fun testBothImportConfirmationsCancelAndRecheckGatesAtPositiveCallback() {
        start();val before=controller.database.exportBackup()
        for(code in listOf(102,104)) {
            picker(code,uri=if(code==104) completeUri else textUri)
            main {assertNotNull(dialog());assertEquals(0,provider.opens.get())}
            press(DialogInterface.BUTTON_NEGATIVE);unchanged(before)
            for(state in listOf("active","busy","recovery","not-ready")) {
                picker(code,uri=if(code==104) completeUri else textUri)
                main {gate(state)};press(DialogInterface.BUTTON_POSITIVE);main {gate(null)}
                assertEquals(0,provider.opens.get());unchanged(before)
            }
        }
    }
    fun testCompleteExportAndAutomaticTextAndCompleteImportReachController() {
        start()
        for(code in listOf(102,104)) {
            val uri=if(code==104) completeUri else textUri
            val bytes=provider.file(uri).readBytes()
            picker(code,uri=uri)
            assertEquals(0L,controller.dataRevision)
            press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy && controller.dataRevision==1L}
            assertEquals(1,controller.database.conversations().size)
            assertTrue(bytes.contentEquals(provider.file(uri).readBytes()));assertEquals(0,modelCalls.get())
            val output=Uri.parse("content://activity-backup/output-$code.json")
            observedDataNotice.set(null)
            picker(if(code==104) 103 else 101,uri=output)
            awaitState { !controller.dataBusy && observedDataNotice.get() != null }
            assertTrue("export notice=${observedDataNotice.get()}, opens=${provider.opens.get()}", provider.file(output).isFile)
            val text=provider.file(output).readText()
            assertTrue(text.contains("historical answer"))
            val envelope=org.json.JSONObject(text)
            assertEquals("daiyu-neutral-complete",envelope.getString("format"))
            assertEquals(8,envelope.getInt("version"))
            NeutralBackupService(fixtureApp,controller.database).preflight(text)
            controller.database.conversations().forEach {controller.database.delete(it.id)}
            // Reset only revision for the second independent UI route; format fixtures stay tiny.
            main {setField("dataRevision",0L)}
        }
    }
    private fun verifyRealReadProgress(success: Boolean) {
        start(); click("数据备份")
        val before = controller.database.exportBackup()
        val source = provider.file(completeUri).readBytes()
        val pipe = ImportPipe(if (success) source else byteArrayOf(0xc3.toByte(), 0x28))
        importPipe = pipe; provider.pipe = pipe
        try {
            picker(107, uri = completeUri); press(DialogInterface.BUTTON_POSITIVE)
            val progress = hasText("正在读取备份：已读取 ", substring = true) and hasText("字节", substring = true)
            ui.waitUntil(10_000) { ui.onAllNodes(progress, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(progress, useUnmergedTree = true).assertIsDisplayed()
            main {
                assertTrue(controller.dataBusy); assertEquals(0L, controller.dataRevision)
                val bytes = checkNotNull(controller.dataStatus).substringAfter("已读取 ").substringBefore(" 字节").toLong()
                assertTrue(bytes in 1L..8192L)
            }
            assertEquals(1L, pipe.release.count)
            assertEquals(before, controller.database.exportBackup())
            pipe.release.countDown()
            awaitState { !controller.dataBusy }
            assertTrue(pipe.finished.await(5, TimeUnit.SECONDS)); assertNull(pipe.failure.get())
            ui.onAllNodes(hasText("字节", substring = true), useUnmergedTree = true).assertCountEquals(0)
            main { assertNull(controller.dataStatus) }
            if (success) {
                assertEquals(1L, controller.dataRevision)
                assertEquals(1, controller.database.conversations().size)
            } else {
                unchanged(before)
                ui.onAllNodes(hasText("已导入", substring = true), useUnmergedTree = true).assertCountEquals(0)
            }
            assertEquals(1, provider.opens.get())
            assertTrue(source.contentEquals(provider.file(completeUri).readBytes()))
            assertEquals(0, modelCalls.get())
        } finally { pipe.release.countDown() }
    }
    fun testRealImportShowsReadBytesBeforeEofAndClearsProgressOnSuccess() = verifyRealReadProgress(true)
    fun testRealImportReadProgressIsClearedOnFailureWithoutSuccessOrMutation() = verifyRealReadProgress(false)

    fun testImportConfirmationExpiresAfterRealPauseResume() {
        val current = start(opened(textUri))
        val before = controller.database.exportBackup()
        awaitUniqueTag("dialog-confirm")
        assertEquals(0, provider.opens.get())
        val stalePositive = retainedClick(current, "dialog-confirm")
        realPauseResume(current)
        main { stalePositive() }
        instrumentation.waitForIdleSync()
        assertEquals(0, provider.opens.get())
        unchanged(before)
        main { assertNull(dialog()) }
    }

    fun testConversationExportPickerUsesVersionlessSuggestedName() {
        val conversation=controller.database.create("default")
        start();val before=controller.database.exportBackup()
        val observed=CountDownLatch(1)
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action)
                assertEquals("application/json",intent.type)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals("daiyu-conversation.json",intent.getStringExtra(Intent.EXTRA_TITLE))
                assertEquals(105,pending())
                observed.countDown()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,Intent())
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            main {
                MainActivity::class.java.getDeclaredMethod("pickConversationExport",String::class.java)
                    .apply {isAccessible=true}.invoke(activity,conversation.id.value)
            }
            assertTrue(observed.await(5,TimeUnit.SECONDS));instrumentation.waitForIdleSync()
            main {assertNull(pending())}
            assertEquals(0,provider.opens.get());unchanged(before)
        } finally {instrumentation.removeMonitor(monitor)}
    }
    fun testViewRequiresConfirmationAndAllowsRetryAfterCancel() {
        start(opened(completeUri));val before=controller.database.exportBackup()
        main {assertNotNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
        assertEquals(0,provider.opens.get());unchanged(before)
        press(DialogInterface.BUTTON_NEGATIVE);unchanged(before)
        main {instrumentation.callActivityOnNewIntent(activity,opened(completeUri))}
        instrumentation.waitForIdleSync();press(DialogInterface.BUTTON_POSITIVE)
        awaitState {!controller.dataBusy && controller.dataRevision==1L}
        assertEquals(1,controller.database.conversations().size);assertEquals(0,modelCalls.get())
    }
    fun testViewNewIntentDeduplicatesVisiblePromptAndImportsOnlyOnConfirm() {
        start();val before=controller.database.exportBackup()
        main {
            instrumentation.callActivityOnNewIntent(activity,opened())
            val first=dialog();assertNotNull(first)
            instrumentation.callActivityOnNewIntent(activity,opened());assertSame(first,dialog())
        }
        instrumentation.waitForIdleSync();unchanged(before);assertEquals(0,provider.opens.get())
        main {
            val confirmation=dialog()
            instrumentation.callActivityOnNewIntent(activity,opened());assertSame(confirmation,dialog())
        }
        press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy && controller.dataRevision==1L}
        assertEquals(1,controller.database.conversations().size);assertEquals(0,modelCalls.get())
    }
    fun testViewMissingUriNoGrantWrongSchemeMimeAndGatesRejectWithoutReading() {
        start();val before=controller.database.exportBackup()
        val invalid=listOf(opened().setDataAndType(null,"application/json"),opened(grant=false),
            opened(Uri.parse("file:///ignored.json")),opened(mime="text/plain"),opened(mime="application/octet-stream"))
        for(intent in invalid) main {instrumentation.callActivityOnNewIntent(activity,intent);assertNull(dialog())}
        for(state in listOf("active","busy","recovery")) main {
            gate(state);instrumentation.callActivityOnNewIntent(activity,opened());assertNull(dialog());gate(null)
        }
        assertEquals(0,provider.opens.get());unchanged(before)
    }
    fun testViewRevokedGrantAndWrongFormatFailWithoutMutation() {
        start();val before=controller.database.exportBackup()
        main {provider.deny=true;instrumentation.callActivityOnNewIntent(activity,opened())}
        instrumentation.waitForIdleSync();press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy}
        assertEquals(1,provider.opens.get());unchanged(before)
        provider.file(textUri).writeText("{\"format\":\"unsupported\"}")
        main {provider.deny=false;instrumentation.callActivityOnNewIntent(activity,opened(textUri))}
        instrumentation.waitForIdleSync();press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy}
        assertEquals(2,provider.opens.get());unchanged(before)
        provider.file(textUri).writeText("not JSON")
        main {instrumentation.callActivityOnNewIntent(activity,opened(textUri))}
        instrumentation.waitForIdleSync();press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy}
        assertEquals(3,provider.opens.get());unchanged(before)
    }
    private fun deferred():Any?=MainActivity::class.java.getDeclaredField("deferredOpenedBackup").apply {isAccessible=true}.get(activity)
    private fun publishGate(state:String?) {
        main {
            gate(state)
            // Deliver the same production observer notification used by async recovery completion.
            ChatController::class.java.getDeclaredMethod("notifyChanged").apply {isAccessible=true}.invoke(controller)
        }
        instrumentation.waitForIdleSync()
    }
    private fun implicitOpened(uri:Uri=textUri)=opened(uri).setComponent(null).setPackage(instrumentation.targetContext.packageName)
    fun testImplicitViewDuringRecoveryDefersOneUriThenRequiresConfirmation() {
        val implicit=implicitOpened()
        assertNull(implicit.component)
        val resolved=instrumentation.targetContext.packageManager.resolveActivity(implicit,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name,resolved?.activityInfo?.name)
        val before=controller.database.exportBackup()
        main {gate("not-ready")}
        start(implicit)
        main {assertNotNull(deferred());assertNotNull(dialog())}
        node("dialog-confirm").assertTextEquals("取消打开")
        assertEquals(0,provider.opens.get());unchanged(before)
        publishGate(null)
        main {assertNull(deferred());assertNotNull(dialog())}
        node("dialog-confirm").assertTextEquals("导入")
        val choice=dialog()
        publishGate(null)
        main {assertSame(choice,dialog())}
        assertEquals(0,provider.opens.get());unchanged(before)

        assertEquals(0,provider.opens.get());unchanged(before)
        press(DialogInterface.BUTTON_POSITIVE)
        awaitState {!controller.dataBusy && controller.dataRevision==1L}
        assertEquals(1,provider.opens.get());assertEquals(1,controller.database.conversations().size)
        assertEquals(0,modelCalls.get())
    }
    fun testRecoveryDeferredViewBothCancelActionsRevokeWithoutLateReplay() {
        val before=controller.database.exportBackup()
        main {gate("not-ready")};start(opened())
        for(button in listOf(DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_POSITIVE)) {
            if(button==DialogInterface.BUTTON_POSITIVE) {
                main {gate("not-ready");instrumentation.callActivityOnNewIntent(activity,opened())}
                instrumentation.waitForIdleSync()
            }
            main {assertNotNull(deferred())}
            press(button)
            main {assertNull(deferred());assertNull(dialog())}
            publishGate(null)
            main {assertNull(dialog())}
            assertEquals(0,provider.opens.get());unchanged(before)
        }
    }
    fun testRecoveryDeferredViewReplacementKeepsOnlyNewestUri() {
        main {gate("not-ready")};start(opened(textUri))
        val first=deferred()
        main {
            instrumentation.callActivityOnNewIntent(activity,opened(completeUri))
            assertNotNull(deferred());assertNotSame(first,deferred())
        }
        publishGate(null)
        assertEquals(0,provider.opens.get())
        press(DialogInterface.BUTTON_POSITIVE)
        awaitState {!controller.dataBusy && controller.dataRevision==1L}
        assertEquals(1,provider.opens.get());assertEquals(1,controller.database.conversations().size)
        assertEquals(0,modelCalls.get())
    }
    fun testRecoveryDeferredViewInvalidReplacementAndOversizedUriRevoke() {
        val before=controller.database.exportBackup()
        main {gate("not-ready")};start()
        val invalid=listOf(opened(grant=false),opened(Uri.parse("file:///private.json")),
            opened(mime="text/plain"),opened(Uri.parse("content://activity-backup/"+"x".repeat(8193))),
            Intent(Intent.ACTION_MAIN).setClass(instrumentation.targetContext,MainActivity::class.java))
        for(replacement in invalid) {
            main {
                instrumentation.callActivityOnNewIntent(activity,opened());assertNotNull(deferred())
                instrumentation.callActivityOnNewIntent(activity,replacement)
                assertNull(deferred());assertNull(dialog())
            }
        }
        publishGate(null)
        main {assertNull(dialog())}
        assertEquals(0,provider.opens.get());unchanged(before)
    }
    fun testRecoveryDeferredViewRevokedProviderStillFailsOnlyAtExplicitImport() {
        val before=controller.database.exportBackup()
        main {gate("not-ready")};start(opened())
        provider.deny=true
        publishGate(null)
        assertEquals(0,provider.opens.get())

        assertEquals(0,provider.opens.get())
        press(DialogInterface.BUTTON_POSITIVE)
        awaitState {!controller.dataBusy}
        assertEquals(1,provider.opens.get());unchanged(before)
    }
    fun testRecoveryDeferredViewRecreationDropsUriAndDoesNotReplayActivityIntent() {
        val before=controller.database.exportBackup()
        main {gate("not-ready")};start(opened())
        main {assertNotNull(deferred())}
        recreate()
        main {assertNull(deferred());assertNull(dialog())}
        publishGate(null)
        main {assertNull(deferred());assertNull(dialog())}
        assertEquals(0,provider.opens.get());unchanged(before)
    }
    fun testRecoveryDeferredViewFailureBusyActiveOrNavigationRevokesVisit() {
        val before=controller.database.exportBackup()
        main {gate("not-ready")};start()
        for(state in listOf("recovery","busy","active",null)) {
            main {gate("not-ready");instrumentation.callActivityOnNewIntent(activity,opened());assertNotNull(deferred())}
            if(state==null) main {
                MainActivity::class.java.getDeclaredMethod("returnToChat").apply {isAccessible=true}.invoke(activity)
            } else publishGate(state)
            main {assertNull(deferred());assertNull(dialog())}
            publishGate(null)
            main {assertNull(dialog())}
            assertEquals(0,provider.opens.get());unchanged(before)
        }
    }
    fun testRecreationDropsViewConfirmationWithoutReplay() {
        start(opened());val before=controller.database.exportBackup()
        recreate();main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
        main {instrumentation.callActivityOnNewIntent(activity,opened())}
        instrumentation.waitForIdleSync()
        recreate();main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
    }
    private fun recreate() {
        activity = recreateActivity(checkNotNull(activity))
    }
    private fun restoreDialog():Any?=dialog()
    private fun restorePress(which:Int) {
        if(which==DialogInterface.BUTTON_NEGATIVE && dialog()==null) return
        press(which)
    }
    private fun restorePreview() {
        click("备份与导入")
        main {
            MainActivity::class.java.getDeclaredMethod("previewConfigurationRestore").apply {isAccessible=true}.invoke(activity)
            if (configurationPreview()?.plan?.isNoOp == false)
                MainActivity::class.java.getDeclaredMethod("confirmConfigurationRestore").apply {isAccessible=true}.invoke(activity)
        }
    }
    private fun configurationPreview() =
        (MainActivity::class.java.getDeclaredField("configurationRestorePreview\$delegate").apply {isAccessible=true}.get(activity)
            as androidx.compose.runtime.State<*>).value as? ChatController.ConfigurationRestorePreview
    private fun modelPreferences()=fixtureApp.getSharedPreferences("model-settings",0)
    private fun seedRestoreMetadata() {
        assertTrue(modelPreferences().edit().putString("endpoint","https://current.invalid").putString("model","current-model")
            .putString("responses-path","").putBoolean("battery",true).putBoolean("device-status",true)
            .putString("key","SECRET_ciphertext").putString("iv","SECRET_iv").putString("key-alias","SECRET_alias").commit())
        NeutralBackupService(fixtureApp,controller.database).setNonSecretConfiguration(mapOf(
            "endpoint" to "https://imported.invalid","model" to "imported-model","responses-path" to "/responses",
            "battery" to true,"device-status" to true))
    }
    fun testRealCompleteImportOffersConfigurationConfirmationWithoutApplying() {
        seedRestoreMetadata()
        // Export metadata into a real complete backup, then clear it so the production import must fill it.
        val backup=NeutralBackupService(fixtureApp,controller.database)
        provider.file(completeUri).writeText(backup.exportPackage())
        backup.setNonSecretConfiguration(emptyMap())
        val before=HashMap(modelPreferences().all)
        start(opened(completeUri));press(DialogInterface.BUTTON_POSITIVE)
        awaitState {!controller.dataBusy && controller.dataRevision==1L}
        assertEquals(before,modelPreferences().all);assertEquals(1,provider.opens.get())
        restorePreview()
        ui.onNode(hasText("https://current.invalid",substring=true),useUnmergedTree=true).assertExists()
        ui.onNode(hasText("https://imported.invalid",substring=true),useUnmergedTree=true).assertExists()
        ui.onAllNodes(hasText("SECRET_",substring=true),useUnmergedTree=true).assertCountEquals(0)
        assertEquals(before,modelPreferences().all)
        node("dialog-confirm").assertTextEquals("确认恢复")
        restorePress(DialogInterface.BUTTON_POSITIVE)
        assertEquals("https://imported.invalid",modelPreferences().getString("endpoint",null))
        assertEquals("imported-model",modelPreferences().getString("model",null))
        assertFalse(modelPreferences().getBoolean("battery",true));assertFalse(modelPreferences().getBoolean("device-status",true))
        for(name in listOf("key","iv","key-alias")) assertEquals(before[name],modelPreferences().all[name])
        assertTrue(modelPreferences().getBoolean("credential-rebind-required",false))
        try {controller.settings.load();fail("must not read/reuse old key")} catch(_:CredentialUnavailableException) {}
        val restoredEditing = controller.settings.loadForEditing()
        assertEquals("",restoredEditing.settings.apiKey)
        assertFalse(restoredEditing.headersUnavailable)
        assertTrue(restoredEditing.settings.customHeaders.isEmpty)
        main {assertNull(restoreDialog())}
        recreate()
        assertEquals("imported-model",modelPreferences().getString("model",null))
        main {assertNull(restoreDialog())};assertEquals(1,provider.opens.get());assertEquals(0,modelCalls.get())
        // Real settings dialog must not prefill the old credential; only an explicit new key save clears the gate.
        click("模型设置")
        modelField("API Key").assertEditableText("");editModel("API Key","synthetic-new-key");saveModel()
        awaitState { !controller.dataBusy && !modelPreferences().getBoolean("credential-rebind-required",false) }
        assertFalse(modelPreferences().getBoolean("credential-rebind-required",false))
        assertEquals("synthetic-new-key",controller.settings.load().apiKey)
        assertFalse(controller.settings.load().batteryEnabled);assertFalse(controller.settings.load().deviceStatusEnabled)
        assertEquals(0,modelCalls.get())
    }
    fun testRestoreCancelAndRecreationDropConfirmationWithoutApplyingOrReplaying() {
        seedRestoreMetadata();start();val before=HashMap(modelPreferences().all)
        restorePreview();restorePress(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(before,modelPreferences().all);main {assertNull(restoreDialog())}
        restorePreview();recreate()
        main {assertNull(restoreDialog())}
        assertEquals(before,modelPreferences().all)
        restorePreview();node("dialog-cancel").performClick()
        instrumentation.waitForIdleSync();assertEquals(before,modelPreferences().all)
        assertEquals(0,modelCalls.get());assertEquals(0,provider.opens.get());assertEquals(0L,controller.dataRevision)
    }
    fun testRestoreFinalConfirmationRechecksRuntimeGatesInRealActivity() {
        seedRestoreMetadata();start();val before=HashMap(modelPreferences().all)
        for(state in listOf("active","busy","recovery","not-ready")) {
            restorePreview();main {gate(state)}
            try {restorePress(DialogInterface.BUTTON_POSITIVE)} finally {main {gate(null)}}
            main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        }
        assertEquals(0,provider.opens.get());assertEquals(0,modelCalls.get());assertEquals(0L,controller.dataRevision)
    }
    fun testRestoreStaleSettingsAndMetadataFailWithoutApplying() {
        seedRestoreMetadata();start();val before=HashMap(modelPreferences().all)
        restorePreview()
        NeutralBackupService(fixtureApp,controller.database).setNonSecretConfiguration(mapOf("model" to "replacement"))
        restorePress(DialogInterface.BUTTON_POSITIVE);assertEquals(before,modelPreferences().all)
        restorePreview();assertTrue(modelPreferences().edit().putString("model","user-edit").commit())
        val changed=HashMap(modelPreferences().all)
        restorePress(DialogInterface.BUTTON_POSITIVE);assertEquals(changed,modelPreferences().all)
        assertEquals(0,provider.opens.get());assertEquals(0,modelCalls.get())
    }
    fun testRestoreCommitFailureInRealActivityPreservesOriginalAndRequiresNewPreview() {
        seedRestoreMetadata();start();val before=HashMap(modelPreferences().all)
        restorePreview();fixtureApp.failNextRestoreCommit=true
        restorePress(DialogInterface.BUTTON_POSITIVE)
        awaitState { !controller.dataBusy }
        assertFalse("restore failure injection was not consumed",fixtureApp.failNextRestoreCommit)
        main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        recreate();main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        restorePreview();restorePress(DialogInterface.BUTTON_POSITIVE)
        awaitState { !controller.dataBusy }
        assertEquals("imported-model",modelPreferences().getString("model",null))
        assertEquals(0,modelCalls.get());assertEquals(0,provider.opens.get());assertEquals(0L,controller.dataRevision)
    }
    fun testRestoreEmptyMetadataOnlyShowsNoOpAndInvalidMetadataNeverShowsConfirm() {
        start();val before=HashMap(modelPreferences().all)
        restorePreview()
        node("dialog-confirm").assertDoesNotExist()
        main { assertTrue(configurationPreview()?.plan?.isNoOp == true) }
        restorePress(DialogInterface.BUTTON_NEGATIVE)
        NeutralBackupService(fixtureApp,controller.database).setNonSecretConfiguration(mapOf("model" to "m".repeat(201)))
        click("备份与导入")
        main { runCatching {
            MainActivity::class.java.getDeclaredMethod("previewConfigurationRestore").apply {isAccessible=true}.invoke(activity)
        } }
        main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        assertEquals(0,modelCalls.get());assertEquals(0,provider.opens.get())
    }
    fun testManifestOnlyResolvesContentJsonOrZipView() {
        val pm=instrumentation.targetContext.packageManager
        val component=android.content.ComponentName(instrumentation.targetContext,MainActivity::class.java)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP,pm.getActivityInfo(component,0).launchMode)
        fun resolves(uri:String,mime:String):Boolean {
            val intent=Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),mime)
            assertNull(intent.component);assertNull(intent.`package`)
            return pm.queryIntentActivities(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo.packageName==component.packageName && it.activityInfo.name==component.className
            }
        }
        assertTrue(resolves("content://activity-backup/text.json","application/json"))
        assertTrue(resolves("content://activity-backup/full.zip","application/zip"))
        assertFalse(resolves("file:///full.zip","application/zip"))
        assertFalse(resolves("https://example.invalid/full.zip","application/zip"))
        assertFalse(resolves("file:///text.json","application/json"))
        assertFalse(resolves("https://example.invalid/text.json","application/json"))
        assertFalse(resolves("content://activity-backup/text.json","text/plain"))
        assertFalse(resolves("content://activity-backup/text.json","application/octet-stream"))
    }

    fun testExportResultAfterLeavingBackupDoesNotOpenTarget() {
        start()
        val before = controller.database.exportBackup()
        val output = Uri.parse("content://activity-backup/left-backup.json")
        picker(103, uri = output, beforeReturn = {
            MainActivity::class.java.getDeclaredMethod("returnToChat").apply { isAccessible = true }.invoke(activity)
        })
        awaitState { !controller.dataBusy }
        assertEquals(0, provider.opens.get())
        assertFalse(provider.file(output).exists())
        unchanged(before)
        main { assertNull(pending()) }
    }
    fun testExportResultCannotBorrowReplacementBackupPage() {
        start()
        val before = controller.database.exportBackup()
        val output = Uri.parse("content://activity-backup/replaced-backup.json")
        picker(103, uri = output, beforeReturn = {
            MainActivity::class.java.getDeclaredMethod("returnToChat").apply { isAccessible = true }.invoke(activity)
            MainActivity::class.java.getDeclaredMethod("backupMenu").apply { isAccessible = true }.invoke(activity)
        })
        awaitState { !controller.dataBusy }
        assertEquals(0, provider.opens.get())
        assertFalse(provider.file(output).exists())
        unchanged(before)
        main { assertNull(pending()) }
    }

}
