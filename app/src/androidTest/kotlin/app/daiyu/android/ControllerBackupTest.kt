package app.daiyu.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerBackupTest:InstrumentationTestCase() {
    private class FixtureApplication(base:Context):Application() {
        init {attachBaseContext(base)}
        val database=ConversationDatabase(this)
        var failNextRestoreCommit=false
        val projectionContext=object:ContextWrapper(this) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val raw=baseContext.getSharedPreferences(name,mode)
                if(name!="model-settings") return raw
                return object:SharedPreferences by raw {
                    override fun edit():SharedPreferences.Editor {
                        val editor=raw.edit()
                        return object:SharedPreferences.Editor by editor {
                            override fun commit():Boolean {
                                val committed=editor.commit()
                                if(failNextRestoreCommit) {failNextRestoreCommit=false;return false}
                                return committed
                            }
                        }
                    }
                    override fun getAll():MutableMap<String,*> = error("export/restore must not read all preferences")
                    override fun getString(key:String?,default:String?):String? {
                        check(key in setOf("endpoint","model","responses-path")) {"export must not read credentials"}
                        return raw.getString(key,default)
                    }
                }
            }
        }
        val settings=ModelSettingsStore(projectionContext,"backup-unused-${UUID.randomUUID()}")
        val modelPreferences=getSharedPreferences("model-settings",0)
    }
    private data class Fixture(val app:FixtureApplication,val controller:ChatController,
        val backup:NeutralBackupService,val calls:AtomicInteger,val directory:File) {
        val db get()=app.database
        val preferences get()=app.modelPreferences
    }
    private val fixtures=mutableListOf<Fixture>()
    private fun main(work:()->Unit) {
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {failure.set(t)}}
        failure.get()?.let {throw it}
    }
    private fun awaitState(f:Fixture,predicate:()->Boolean) {
        val done=CountDownLatch(1);val listener:()->Unit={if(predicate()) done.countDown()}
        main {f.controller.observe(listener);listener()}
        try {assertTrue("controller state timeout",done.await(10,TimeUnit.SECONDS));instrumentation.waitForIdleSync()}
        finally {main {f.controller.unobserve(listener)}}
    }
    private fun fresh():Fixture {
        val id=UUID.randomUUID().toString()
        val isolated=testContext(instrumentation.targetContext,"controller-backup-$id-")
        val directory=File(instrumentation.targetContext.cacheDir,"controller-backup-$id");check(directory.mkdir())
        val context=object:ContextWrapper(isolated) {override fun getFilesDir():File=directory}
        val app=FixtureApplication(context);val calls=AtomicInteger()
        val controller=ChatController(app,app.database,app.settings,app.getSharedPreferences("conversation-ui",0),{
            calls.incrementAndGet();error("backup must not open a model")})
        val f=Fixture(app,controller,NeutralBackupService(app,app.database),calls,directory)
        fixtures.add(f);awaitState(f) {controller.ready};assertNull(controller.problem);return f
    }
    override fun tearDown() {
        try {fixtures.forEach {f->
            f.controller.close();f.app.deleteDatabase("conversations.db")
            f.preferences.edit().clear().commit();f.app.getSharedPreferences("conversation-ui",0).edit().clear().commit()
            f.directory.deleteRecursively()
        }} finally {super.tearDown()}
    }
    private fun seed(f:Fixture) {
        val owner=f.db.create("default").id;val run=RunId("fixture-run-${owner.value}")
        val user=Message(MessageId("u-${owner.value}"),owner,run,"user",listOf(MessageBlock.Text("u","question")),RunStatus.COMPLETED)
        val reply=Message(MessageId("a-${owner.value}"),owner,run,"assistant",listOf(MessageBlock.Text("a","answer")),RunStatus.RUNNING)
        f.db.send(user,reply);f.db.save(reply.copy(status=RunStatus.COMPLETED))
        f.db.draft(owner,"conversation draft");f.db.draft("unsent draft")
        f.backup.attach("fixture-asset",user.id.value,"application/octet-stream",byteArrayOf(0,1,2,-1))
    }
    private fun settings(f:Fixture,model:String="source-model") {
        assertTrue(f.preferences.edit().putString("endpoint","https://example.invalid/gateway")
            .putString("model",model).putString("responses-path","/responses")
            .putBoolean("battery",true).putBoolean("device-status",false)
            .putString("key","SECRET_invalid_ciphertext").putString("iv","SECRET_invalid_iv")
            .putString("key-alias","SECRET_missing_alias").putString("cookie","SECRET_cookie").commit())
    }
    private fun task(f:Fixture,work:()->Unit):String {
        val done=CountDownLatch(1);val notice=AtomicReference<String?>();val wrongThread=AtomicInteger()
        val listener:()->Unit={
            if(Looper.myLooper()!=Looper.getMainLooper()) wrongThread.incrementAndGet()
            if(!f.controller.dataBusy) f.controller.dataNotice?.let {notice.set(it);done.countDown()}
        }
        main {f.controller.consumeDataNotice();f.controller.observe(listener)}
        try {
            main {work()};assertTrue("backup completion timeout",done.await(10,TimeUnit.SECONDS));instrumentation.waitForIdleSync()
            assertEquals(0,wrongThread.get());assertFalse(f.controller.dataBusy);return notice.get() ?: error("no notice")
        } finally {main {f.controller.unobserve(listener)}}
    }
    private fun export(f:Fixture,name:String="complete.json"):File {
        val output=File(f.directory,name)
        assertTrue(task(f) {f.controller.exportUserBackup(Uri.fromFile(output),true)}.startsWith("完整中立备份已导出"))
        assertTrue(output.isFile);return output
    }
    private fun config(file:File)=JSONObject(JSONObject(file.readText()).getString("payload")).getJSONObject("config")
    fun testCompleteUriExportProjectsCurrentSettingsWithoutSecretsOrMetadataMutation() {
        val f=fresh();seed(f);settings(f)
        f.backup.setNonSecretConfiguration(mapOf("model" to "imported-metadata","battery" to false))
        val metadata=f.backup.importedConfiguration().toString();val before=f.db.exportBackup()
        val output=export(f);val config=config(output)
        assertEquals(5,config.length());assertEquals("source-model",config.getString("model"))
        assertEquals("/responses",config.getString("responses-path"));assertTrue(config.getBoolean("battery"))
        assertFalse(config.getBoolean("device-status"));assertFalse(output.readText().contains("SECRET_"))
        assertTrue(f.preferences.edit().remove("responses-path").remove("device-status").putString("model","updated").commit())
        val updated=config(export(f,"updated.json"));assertEquals(3,updated.length());assertEquals("updated",updated.getString("model"))
        val bad=File(File(f.directory,"missing-parent"),"failed.json")
        assertTrue(task(f) {f.controller.exportUserBackup(Uri.fromFile(bad),true)}.startsWith("导出失败"))
        assertFalse(bad.exists());assertEquals(metadata,f.backup.importedConfiguration().toString())
        assertTrue(f.preferences.edit().clear().commit());assertEquals(0,config(export(f,"empty.json")).length())
        assertEquals(metadata,f.backup.importedConfiguration().toString());assertEquals(before,f.db.exportBackup())
        assertEquals(0L,f.controller.dataRevision);assertEquals(0,f.calls.get())
    }
    fun testCompleteUriImportAndDuplicatePreserveCurrentAccountAndSource() {
        val source=fresh();seed(source);settings(source);val input=export(source);val bytes=input.readBytes()
        val target=fresh();settings(target,"current-account");val before=HashMap(target.preferences.all)
        assertTrue(task(target) {target.controller.importUserBackup(Uri.fromFile(input),true)}.startsWith("已导入"))
        assertEquals(source.db.exportBackup(),target.db.exportBackup())
        assertTrue(byteArrayOf(0,1,2,-1).contentEquals(target.backup.attachment("fixture-asset")))
        assertEquals("source-model",target.backup.importedConfiguration().getString("model"))
        assertEquals(before,target.preferences.all);assertEquals(1L,target.controller.dataRevision)
        val after=target.backup.exportPackage()
        assertTrue(task(target) {target.controller.importUserBackup(Uri.fromFile(input),true)}.startsWith("此完整备份已导入过"))
        assertEquals(after,target.backup.exportPackage());assertEquals(2L,target.controller.dataRevision)
        assertEquals(before,target.preferences.all);assertTrue(bytes.contentEquals(input.readBytes()))
        assertEquals(0,source.calls.get());assertEquals(0,target.calls.get())
    }
    fun testWrongFormatAndMalformedUtf8LeaveStateAndRevisionUnchanged() {
        val source=fresh();seed(source);val complete=export(source)
        val text=File(source.directory,"text.json").apply {writeText(source.db.exportBackup())}
        val malformed=File(source.directory,"bad.json").apply {writeBytes(byteArrayOf(0xc3.toByte(),0x28))}
        val target=fresh();seed(target);target.backup.setNonSecretConfiguration(mapOf("model" to "preserved"))
        val before=target.backup.exportPackage()
        listOf(text to true,complete to false,malformed to true,malformed to false).forEach {(input,mode)->
            val bytes=input.readBytes();val notice=task(target) {target.controller.importUserBackup(Uri.fromFile(input),mode)}
            assertTrue(notice.startsWith("导入失败"));if(input==malformed) assertTrue(notice.contains("有效UTF-8"))
            assertEquals(0L,target.controller.dataRevision);assertEquals(before,target.backup.exportPackage())
            assertTrue(bytes.contentEquals(input.readBytes()))
        }
        assertEquals(0,target.calls.get())
    }
    private fun field(f:Fixture,name:String,value:Any?) {
        ChatController::class.java.getDeclaredField(name).apply {isAccessible=true}.set(f.controller,value)
    }
    private fun rejects(f:Fixture) {
        val poison=Uri.parse("content://controller-backup-no-provider/unread")
        main {
            try {f.controller.importUserBackup(poison,true);fail("import gate must reject")} catch(_:IllegalStateException) {}
            try {f.controller.exportUserBackup(poison,true);fail("export gate must reject")} catch(_:IllegalStateException) {}
            try {f.controller.exportConversationBackup(poison,ConversationId("gate"));fail("conversation export gate must reject")} catch(_:IllegalStateException) {}
            assertEquals(0L,f.controller.dataRevision)
        }
    }
    fun testActiveBusyAndRecoveryGatesRejectBeforeUriWork() {
        val f=fresh()
        main {field(f,"active",Message(MessageId("gate"),ConversationId("gate"),RunId("gate"),"assistant",emptyList(),RunStatus.RUNNING))}
        try {rejects(f);assertFalse(f.controller.dataBusy)} finally {main {field(f,"active",null)}}
        main {field(f,"problem","会话恢复失败，原数据已保留")}
        try {rejects(f);assertFalse(f.controller.dataBusy)} finally {main {field(f,"problem",null)}}
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        main {f.controller.dataTask {entered.countDown();check(release.await(10,TimeUnit.SECONDS));"fixture task finished"}}
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));rejects(f);assertTrue(f.controller.dataBusy)}
        finally {release.countDown();awaitState(f) {!f.controller.dataBusy && f.controller.dataNotice=="fixture task finished"}}
        assertEquals(0,f.calls.get())
    }
    private fun restoreRejected(work:()->Unit) {
        try {work();fail("manual restore must reject")} catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}
    }
    private fun preview(f:Fixture):ChatController.ConfigurationRestorePreview {
        val result=AtomicReference<ChatController.ConfigurationRestorePreview>()
        main {result.set(f.controller.previewImportedConfiguration())}
        return result.get()
    }
    private fun restore(f:Fixture,preview:ChatController.ConfigurationRestorePreview):Boolean {
        val result=AtomicReference<Boolean>();val done=CountDownLatch(1)
        main {f.controller.restoreImportedConfiguration(preview,true) { success ->
            assertEquals(Looper.getMainLooper(),Looper.myLooper())
            result.set(success);done.countDown()
        }}
        assertTrue("configuration restore timeout",done.await(10,TimeUnit.SECONDS))
        instrumentation.waitForIdleSync();assertFalse(f.controller.dataBusy)
        return result.get()
    }
    fun testImportedConfigurationOnlyAppliesAfterManualConfirmationWithoutCredentialReads() {
        val source=fresh();settings(source);val input=export(source)
        val f=fresh();settings(f,"current-model")
        assertTrue(f.preferences.edit().putString("endpoint","https://current.invalid").putBoolean("device-status",true).commit())
        val before=HashMap(f.preferences.all)
        task(f) {f.controller.importUserBackup(Uri.fromFile(input),true)}
        assertEquals(before,f.preferences.all)
        val metadata=f.backup.importedConfiguration().toString()
        val preview=preview(f)
        main {
            assertEquals(before,f.preferences.all)
            assertTrue(preview.plan.credentialRebindRequired)
            assertEquals("source-model",preview.plan.after["model"])
            assertEquals(false,preview.plan.after["battery"])
            assertEquals(false,preview.plan.after["device-status"])
            assertFalse(preview.plan.before.toString().contains("SECRET_"))
        }
        assertTrue(restore(f,preview))
        main {
            assertEquals("source-model",f.preferences.getString("model",null))
            assertEquals("https://example.invalid/gateway",f.preferences.getString("endpoint",null))
            assertFalse(f.preferences.getBoolean("battery",true));assertFalse(f.preferences.getBoolean("device-status",true))
            for(name in listOf("key","iv","key-alias","cookie")) assertEquals(before[name],f.preferences.all[name])
            assertTrue(f.preferences.getBoolean("credential-rebind-required",false))
            // ProjectionContext rejects getAll and every credential getString: the gate must run first.
            try {f.app.settings.load();fail("old key must not bind to new endpoint")} catch(_:CredentialUnavailableException) {}
            assertEquals("",f.app.settings.loadForEditing().settings.apiKey)
        }
        assertNull(f.controller.send(null,"must not use the previous key"))
        main {
            assertTrue(f.controller.problem?.contains("未发起网络请求")==true)
            restoreRejected {f.controller.restoreImportedConfiguration(preview,true)}
        }
        assertEquals(metadata,f.backup.importedConfiguration().toString())
        assertEquals(1L,f.controller.dataRevision);assertEquals(0,f.calls.get())
    }
    fun testRestoreCancelAuthorizationAndReplacedPreviewAreSingleUse() {
        val f=fresh();settings(f);f.backup.setNonSecretConfiguration(mapOf("model" to "imported"))
        val before=HashMap(f.preferences.all)
        main {
            val canceled=f.controller.previewImportedConfiguration();f.controller.cancelConfigurationRestore(canceled)
            restoreRejected {f.controller.restoreImportedConfiguration(canceled,true)}
            val unauthorized=f.controller.previewImportedConfiguration()
            restoreRejected {f.controller.restoreImportedConfiguration(unauthorized,false)}
            restoreRejected {f.controller.restoreImportedConfiguration(unauthorized,true)}
            val old=f.controller.previewImportedConfiguration();val newer=f.controller.previewImportedConfiguration()
            restoreRejected {f.controller.restoreImportedConfiguration(old,true)}
            restoreRejected {f.controller.restoreImportedConfiguration(newer,true)}
        }
        assertEquals(before,f.preferences.all);assertEquals(0,f.calls.get());assertEquals(0L,f.controller.dataRevision)
    }
    fun testRestoreRechecksAllRuntimeGatesAtFinalConfirmation() {
        val f=fresh();settings(f);f.backup.setNonSecretConfiguration(mapOf("model" to "imported"))
        val before=HashMap(f.preferences.all)
        val gates=listOf("ready" to false,"active" to Message(MessageId("restore-gate"),ConversationId("restore-gate"),RunId("restore-gate"),"assistant",emptyList(),RunStatus.RUNNING),
            "dataBusy" to true,"problem" to "会话恢复失败，原数据已保留")
        main {
            for((name,value) in gates) {
                val preview=f.controller.previewImportedConfiguration()
                field(f,name,value)
                try {
                    restoreRejected {f.controller.restoreImportedConfiguration(preview,true)}
                    restoreRejected {f.controller.previewImportedConfiguration()}
                } finally {field(f,name,when(name) {"ready"->true;"dataBusy"->false;else->null})}
                restoreRejected {f.controller.restoreImportedConfiguration(preview,true)}
                assertEquals(before,f.preferences.all)
            }
        }
        assertEquals(0,f.calls.get())
    }
    fun testRestoreRejectsChangedMetadataAndChangedCurrentSettingsWithoutPartialWrite() {
        val f=fresh();settings(f);f.backup.setNonSecretConfiguration(mapOf("model" to "imported"))
        val before=HashMap(f.preferences.all)
        val staleMetadata=preview(f)
        f.backup.setNonSecretConfiguration(mapOf("model" to "new-imported"))
        assertFalse(restore(f,staleMetadata));assertEquals(before,f.preferences.all)
        val staleSettings=preview(f)
        assertTrue(f.preferences.edit().putString("model","user-changed").commit())
        val changed=HashMap(f.preferences.all)
        assertFalse(restore(f,staleSettings));assertEquals(changed,f.preferences.all)
        assertEquals(0,f.calls.get())
    }
    fun testRestoreCommitFailureCompensatesOriginalConfigurationAndCannotReplay() {
        val f=fresh();settings(f);f.backup.setNonSecretConfiguration(mapOf("model" to "imported"))
        val before=HashMap(f.preferences.all)
        val first=preview(f);f.app.failNextRestoreCommit=true
        assertFalse(restore(f,first));assertEquals(before,f.preferences.all)
        main {restoreRejected {f.controller.restoreImportedConfiguration(first,true)}}
        assertTrue(restore(f,preview(f)))
        assertEquals("imported",f.preferences.getString("model",null))
        assertEquals(0,f.calls.get());assertEquals(0L,f.controller.dataRevision)
    }
    fun testRestoreEmptyMetadataIsTrueNoOpAndInvalidMetadataPreservesSettings() {
        val f=fresh();settings(f);val before=HashMap(f.preferences.all)
        val empty=preview(f);assertTrue(empty.plan.isNoOp)
        assertTrue(restore(f,empty));assertEquals(before,f.preferences.all)
        // Backup metadata allows model names over 200 chars; active settings must reject them.
        f.backup.setNonSecretConfiguration(mapOf("model" to "m".repeat(201)))
        main {restoreRejected {f.controller.previewImportedConfiguration()}}
        assertEquals(before,f.preferences.all)
        assertEquals(0,f.calls.get());assertEquals(0L,f.controller.dataRevision)
    }
    fun testBoundedReadAndCloseFailureOccurBeforeImport() {
        val f=fresh();val source=fresh();seed(source);val text=source.db.exportBackup();val before=f.db.exportBackup()
        try {f.controller.readBackupText(text.byteInputStream(),1);fail("oversized input must reject")}
        catch(_:IllegalArgumentException) {}
        var imports=0
        val stream=object:java.io.ByteArrayInputStream(text.toByteArray()) {override fun close() {throw java.io.IOException("synthetic close failure")}}
        assertTrue(task(f) {f.controller.dataTask(importing=true) {
            val read=stream.use {f.controller.readBackupText(it,16L*1024*1024)}
            imports++;f.db.importBackup(read);"unexpected import"
        }}.startsWith("导入失败"))
        assertEquals(0,imports);assertEquals(before,f.db.exportBackup());assertEquals(0L,f.controller.dataRevision)
    }
    fun testSingleConversationUriExportUsesExplicitIdAndNeverExportsSettingsOrOtherDrafts() {
        val f=fresh();seed(f);settings(f)
        val selected=f.db.conversations().single().id;val other=f.db.create().id
        f.db.rename(other,"OTHER_CONVERSATION");f.db.draft(other,"OTHER_DRAFT")
        f.backup.setNonSecretConfiguration(mapOf("model" to "IMPORTED_GLOBAL_MODEL"))
        val before=f.backup.exportPackage();val output=File(f.directory,"single.json")
        assertTrue(task(f) {f.controller.exportConversationBackup(Uri.fromFile(output),selected)}.startsWith("单会话中立备份已导出"))
        val payload=JSONObject(JSONObject(output.readText()).getString("payload"))
        assertEquals(0,payload.getJSONObject("config").length());assertEquals(1,f.backup.preflight(output.readText()).conversations)
        for(marker in listOf("source-model","IMPORTED_GLOBAL_MODEL","OTHER_CONVERSATION","OTHER_DRAFT","unsent draft","SECRET_")) assertFalse(output.readText().contains(marker))
        val target=fresh();target.db.draft("keep target global draft")
        assertTrue(task(target) {target.controller.importUserBackup(Uri.fromFile(output),true)}.startsWith("已导入1个会话"))
        assertEquals(listOf(selected),target.db.conversations().map {it.id})
        assertEquals(f.db.messages(selected).map {it.second},target.db.messages(selected).map {it.second})
        assertEquals("conversation draft",target.db.conversations().single().draft);assertEquals("keep target global draft",target.db.draft())
        assertTrue(byteArrayOf(0,1,2,-1).contentEquals(target.backup.attachment("fixture-asset")))
        assertEquals(before,f.backup.exportPackage());assertEquals(0L,f.controller.dataRevision)
        assertEquals(0,f.calls.get());assertEquals(0,target.calls.get())
    }
    fun testSingleConversationExportRejectsMissingBeforeOpeningOutputAndHandlesWriteFailure() {
        val f=fresh();seed(f);val selected=f.db.conversations().single().id
        val deleted=f.db.create().id;f.db.delete(deleted);val before=f.backup.exportPackage()
        val output=File(f.directory,"unchanged.json").apply {writeText("existing target must not be truncated")}
        for(id in listOf(ConversationId("missing"),deleted,ConversationId("' OR 1=1 --"))) {
            assertTrue(task(f) {f.controller.exportConversationBackup(Uri.fromFile(output),id)}.startsWith("导出失败"))
            assertEquals("existing target must not be truncated",output.readText())
        }
        val invalid=File(File(f.directory,"missing-parent"),"failed.json")
        assertTrue(task(f) {f.controller.exportConversationBackup(Uri.fromFile(invalid),selected)}.startsWith("导出失败"))
        assertFalse(invalid.exists());assertEquals(before,f.backup.exportPackage())
        assertEquals(0L,f.controller.dataRevision);assertEquals(0,f.calls.get())
    }

}
