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
        fun file(uri:Uri)=File(directory,uri.lastPathSegment ?: error("missing document"))
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/json"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            opens.incrementAndGet()
            if(deny) throw SecurityException("test revoked grant")
            return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("unused")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=error("unused")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=error("unused")
    }
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
    private lateinit var provider:Documents
    private lateinit var directory:File
    private var original:ChatController?=null
    private var activity:MainActivity?=null
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
            fixtureApp.getSharedPreferences("conversation-ui",0),{modelCalls.incrementAndGet();error("must not execute imported history")})
        awaitState {controller.ready}
        assertNull(controller.problem)
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
        try {
            main {activity?.finish()}
            instrumentation.waitForIdleSync()
            main {original?.let {application.controller=it}}
            controller.close();fixtureApp.deleteDatabase("conversations.db")
            fixtureApp.getSharedPreferences("conversation-ui",0).edit().clear().commit()
            fixtureApp.getSharedPreferences("model-settings",0).getString("key-alias",null)?.let {alias->
                if(alias.startsWith("unused-activity-")) java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(alias)
            }
            fixtureApp.getSharedPreferences("model-settings",0).edit().clear().commit()
            directory.deleteRecursively()
        } finally {super.tearDown()}
    }
    private fun start(intent:Intent=Intent(instrumentation.targetContext,MainActivity::class.java)):MainActivity {
        val current=instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
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
    private fun choose(index:Int) {node("prompt-choice:$index").assertIsDisplayed().performClick()}
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
    private fun picker(code:Int,result:Int=Activity.RESULT_OK,uri:Uri?=null,beforeReturn:()->Unit={}) {
        val exporting=code==101 || code==103
        val complete=code==103 || code==104
        val observed=CountDownLatch(1)
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                assertEquals(if(exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT,intent.action)
                assertEquals("application/json",intent.type)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals(code,pending())
                if(exporting) assertEquals(if(complete) "daiyu-complete.json" else "daiyu-text.json",intent.getStringExtra(Intent.EXTRA_TITLE))
                beforeReturn();observed.countDown()
                return Instrumentation.ActivityResult(result,if(uri==null) Intent() else Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            click("备份与导入")
            val action=if(exporting) {if(complete) "导出完整备份" else "导出精简备份"} else {if(complete) "导入完整备份" else "导入精简备份"}
            reveal("settings-row:$action","备份与导入").performClick()
            assertTrue("SAF launch not observed",observed.await(5,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            main {assertNull("request must be consumed at callback",pending())}
        } finally {instrumentation.removeMonitor(monitor)}
    }
    fun testAllFourPickerRequestsCanceledAndMissingUriDoNoWork() {
        start();val before=controller.database.exportBackup()
        for(code in 101..104) {
            picker(code,Activity.RESULT_CANCELED,textUri)
            main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
            picker(code,Activity.RESULT_OK,null)
            main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
            picker(code,uri=Uri.fromFile(provider.file(textUri)))
            main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
        }
    }
    fun testAllFourPickerCallbacksRejectActiveBusyAndRecoveryAfterLaunch() {
        start();val before=controller.database.exportBackup()
        for(code in 101..104) for(state in listOf("active","busy","recovery","not-ready")) {
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
    fun testTextAndCompletePickerExportAndConfirmedImportReachController() {
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
            picker(if(code==104) 103 else 101,uri=output);awaitState {!controller.dataBusy}
            val text=provider.file(output).readText()
            assertTrue(text.contains("historical answer"))
            val envelope=org.json.JSONObject(text)
            assertEquals(if(code==104) "daiyu-neutral-complete" else "daiyu-neutral-text",envelope.getString("format"))
            assertEquals(if(code==104) 5 else 2,envelope.getInt("version"))
            if(code==104) NeutralBackupService(fixtureApp,controller.database).preflight(text)
            else assertEquals(controller.database.exportBackup(),text)
            controller.database.conversations().forEach {controller.database.delete(it.id)}
            // Reset only revision for the second independent UI route; format fixtures stay tiny.
            main {setField("dataRevision",0L)}
        }
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
    fun testViewRequiresManualChoiceAndConfirmationAndAllowsRetryAfterCancel() {
        start(opened(completeUri));val before=controller.database.exportBackup()
        main {assertNotNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
        choose(0);assertEquals(0,provider.opens.get());unchanged(before)
        press(DialogInterface.BUTTON_NEGATIVE);unchanged(before)
        main {instrumentation.callActivityOnNewIntent(activity,opened(completeUri))}
        instrumentation.waitForIdleSync();choose(0);press(DialogInterface.BUTTON_POSITIVE)
        awaitState {!controller.dataBusy && controller.dataRevision==1L}
        assertEquals(1,controller.database.conversations().size);assertEquals(0,modelCalls.get())
    }
    fun testViewNewIntentDeduplicatesVisiblePromptAndTextChoiceImportsOnlyOnConfirm() {
        start();val before=controller.database.exportBackup()
        main {
            instrumentation.callActivityOnNewIntent(activity,opened())
            val first=dialog();assertNotNull(first)
            instrumentation.callActivityOnNewIntent(activity,opened());assertSame(first,dialog())
        }
        instrumentation.waitForIdleSync();choose(1);unchanged(before);assertEquals(0,provider.opens.get())
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
        instrumentation.waitForIdleSync();choose(1);press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy}
        assertEquals(1,provider.opens.get());unchanged(before)
        main {provider.deny=false;instrumentation.callActivityOnNewIntent(activity,opened(textUri))}
        instrumentation.waitForIdleSync();choose(0);press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy}
        assertEquals(2,provider.opens.get());unchanged(before)
        provider.file(textUri).writeText("not JSON")
        main {instrumentation.callActivityOnNewIntent(activity,opened(textUri))}
        instrumentation.waitForIdleSync();choose(1);press(DialogInterface.BUTTON_POSITIVE);awaitState {!controller.dataBusy}
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
    fun testImplicitViewDuringRecoveryDefersOneUriThenRequiresChoiceAndConfirmation() {
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
        node("prompt-choice:1").assertExists()
        val choice=dialog()
        publishGate(null)
        main {assertSame(choice,dialog())}
        assertEquals(0,provider.opens.get());unchanged(before)
        choose(1)
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
        choose(0);press(DialogInterface.BUTTON_POSITIVE)
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
        choose(1)
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
    fun testRecreationDropsViewChoiceAndConfirmationWithoutReplay() {
        start(opened());val before=controller.database.exportBackup()
        recreate();main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
        main {instrumentation.callActivityOnNewIntent(activity,opened())}
        instrumentation.waitForIdleSync();choose(1)
        recreate();main {assertNull(dialog())};assertEquals(0,provider.opens.get());unchanged(before)
    }
    private fun recreate() {
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            main {activity!!.recreate()}
            val recreated=instrumentation.waitForMonitorWithTimeout(monitor,5000) as? MainActivity
            assertNotNull("recreated Activity must resume",recreated);activity=recreated
            instrumentation.waitForIdleSync()
        } finally {instrumentation.removeMonitor(monitor)}
    }
    private fun restoreDialog():Any?=dialog()
    private fun restorePress(which:Int) {
        if(which==DialogInterface.BUTTON_NEGATIVE && dialog()==null) backToChat(checkNotNull(activity)) else press(which)
    }
    private fun restorePreview() {
        click("备份与导入");reveal("settings-row:预览模型配置","备份与导入").performClick()
        val preview=(MainActivity::class.java.getDeclaredField("configurationRestorePreview\$delegate").apply {isAccessible=true}.get(activity) as androidx.compose.runtime.State<*>).value as? ChatController.ConfigurationRestorePreview
        if(preview?.plan?.isNoOp==false) reveal("settings-row:恢复配置","备份与导入").performClick()
    }
    private fun modelPreferences()=fixtureApp.getSharedPreferences("model-settings",0)
    private fun seedRestoreMetadata() {
        assertTrue(modelPreferences().edit().putString("endpoint","https://current.invalid").putString("model","current-model")
            .putString("responses-path","").putBoolean("battery",true).putBoolean("device-status",true)
            .putString("key","SECRET_ciphertext").putString("iv","SECRET_iv").putString("key-alias","SECRET_alias").commit())
        NeutralBackupService(fixtureApp,controller.database).setNonSecretConfiguration(mapOf(
            "endpoint" to "https://imported.invalid","model" to "imported-model","responses-path" to "/responses",
            "battery" to true,"device-status" to true))
    }
    fun testRealCompleteImportDoesNotApplyConfigurationThenMenuPreviewsAndConfirms() {
        seedRestoreMetadata()
        // Export metadata into a real complete backup, then clear it so the production import must fill it.
        val backup=NeutralBackupService(fixtureApp,controller.database)
        provider.file(completeUri).writeText(backup.exportPackage())
        backup.setNonSecretConfiguration(emptyMap())
        val before=HashMap(modelPreferences().all)
        start(opened(completeUri));choose(0);press(DialogInterface.BUTTON_POSITIVE)
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
        assertEquals("",controller.settings.loadForEditing().settings.apiKey)
        main {assertNull(restoreDialog())}
        recreate()
        assertEquals("imported-model",modelPreferences().getString("model",null))
        main {assertNull(restoreDialog())};assertEquals(1,provider.opens.get());assertEquals(0,modelCalls.get())
        // Real settings dialog must not prefill the old credential; only an explicit new key save clears the gate.
        click("模型设置")
        modelField("API Key").assertEditableText("");editModel("API Key","synthetic-new-key");saveModel()
        instrumentation.waitForIdleSync()
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
        main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        recreate();main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        restorePreview();restorePress(DialogInterface.BUTTON_POSITIVE)
        assertEquals("imported-model",modelPreferences().getString("model",null))
        assertEquals(0,modelCalls.get());assertEquals(0,provider.opens.get());assertEquals(0L,controller.dataRevision)
    }
    fun testRestoreEmptyMetadataOnlyShowsNoOpAndInvalidMetadataNeverShowsConfirm() {
        start();val before=HashMap(modelPreferences().all)
        restorePreview()
        node("dialog-confirm").assertDoesNotExist()
        ui.onNodeWithText("暂无配置").assertExists()
        restorePress(DialogInterface.BUTTON_NEGATIVE)
        NeutralBackupService(fixtureApp,controller.database).setNonSecretConfiguration(mapOf("model" to "m".repeat(201)))
        click("备份与导入");reveal("settings-row:预览模型配置","备份与导入").performClick()
        main {assertNull(restoreDialog())};assertEquals(before,modelPreferences().all)
        assertEquals(0,modelCalls.get());assertEquals(0,provider.opens.get())
    }
    fun testManifestOnlyResolvesContentJsonView() {
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
        assertFalse(resolves("file:///text.json","application/json"))
        assertFalse(resolves("https://example.invalid/text.json","application/json"))
        assertFalse(resolves("content://activity-backup/text.json","text/plain"))
        assertFalse(resolves("content://activity-backup/text.json","application/octet-stream"))
    }
}
