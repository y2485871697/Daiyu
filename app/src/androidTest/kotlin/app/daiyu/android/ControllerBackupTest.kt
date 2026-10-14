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
        val projectionContext=object:ContextWrapper(this) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val raw=baseContext.getSharedPreferences(name,mode)
                if(name!="model-settings") return raw
                return object:SharedPreferences by raw {
                    override fun getAll():MutableMap<String,*> = error("export must not read all preferences")
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
        val owner=f.db.create().id;val run=RunId("fixture-run-${owner.value}")
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
}
