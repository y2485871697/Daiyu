package app.daiyu.android

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import app.daiyu.android.backup.NeutralBackupService
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.platform.AndroidReadOnlyTools
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.Executors

class ChatApplication:Application() {
    lateinit var controller:ChatController
    override fun onCreate() {super.onCreate();controller=ChatController(this)}
}
class ChatController internal constructor(private val application:Application,
    val database:ConversationDatabase, val settings:ModelSettingsStore, val preferences:SharedPreferences,
    private val model:(ModelSettings)->ModelPort) {
    constructor(application:Application):this(application,ConversationDatabase(application),ModelSettingsStore(application),application.getSharedPreferences("conversation-ui",0),{ResponsesModel(it)})
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<()->Unit>()
    internal val selections=mutableMapOf<ConversationId?,Pair<Int,Int>>()
    @Volatile var active:Message?=null;private set
    @Volatile var problem:String?=null;private set
    @Volatile private var runner:ChatRunner?=null
    @Volatile var dataBusy=false;private set
    var dataRevision=0L;private set
    var dataNotice:String?=null;private set
    internal fun consumeDataNotice():String? = dataNotice.also {dataNotice=null}
    @Volatile var ready=false;private set
    init {executor.execute {try {database.recover()} catch (_:Exception) {problem="会话恢复失败，原数据已保留"};ready=true;notifyChanged()} }
    fun observe(listener:()->Unit) {listeners.add(listener)}
    fun unobserve(listener:()->Unit) {listeners.remove(listener)}
    private fun notifyChanged() {handler.post {listeners.toList().forEach {it()} } }
    fun send(conversation:Conversation?,text:String):Conversation? {
        if(!ready || dataBusy || active!=null || problem?.startsWith("会话恢复失败")==true || text.isBlank()) return null
        val config=try {settings.load().also {it.validate()}} catch (_:CredentialUnavailableException) {
            problem="已保存密钥不可用；请到模型设置重新输入，未发起网络请求";notifyChanged();return null
        } catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return null}
        val conversationId=conversation?.id ?: ConversationId(UUID.randomUUID().toString())
        val run=RunId(UUID.randomUUID().toString())
        val user=Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        val assistant=Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"assistant",emptyList(),RunStatus.RUNNING)
        val prior=try {if(conversation==null) emptyList() else database.messages(conversationId,limit=50).map {it.second}} catch(_:Exception) {problem="读取历史失败；草稿保留，未发起网络请求";notifyChanged();return null}
        val saved=try {
            database.send(user,assistant,conversation==null)
        } catch (_:Exception) {problem="发送内容保存失败；草稿保留，未发起网络请求";notifyChanged();return null}
        problem=null;active=assistant
        val tools=config.enabledTools()
        val owned=ChatRunner(ModelPort { history -> model(config).open(history,tools) },database,AndroidReadOnlyTools(application,tools)) {
            if(active?.id==assistant.id && active?.runId==run && it.id==assistant.id && it.runId==run && it.conversationId==conversationId) {active=it;notifyChanged()}
        }
        runner=owned
        executor.execute {
            val result=owned.run(assistant,prior+user)
            if(runner===owned && active?.id==assistant.id && active?.runId==run) {
                runner=null
                if (result.error?.startsWith("保存失败")==true) { active=result;problem=result.error }
                else active=null
                notifyChanged()
            }
        }
        notifyChanged();return saved
    }
    private val neutralBackup by lazy { NeutralBackupService(application, database) }
    internal fun exportUserBackup(uri:Uri,complete:Boolean,resolver:android.content.ContentResolver=application.contentResolver) {
        dataTask(work={
            val text=if(complete) {
                neutralBackup.exportPackage(settings.nonSecretConfiguration())
            } else database.exportBackup()
            resolver.openOutputStream(uri,"wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("无法打开目标")
            if(complete) "完整中立备份已导出；不含凭据或系统授权，请妥善保管"
            else "纯文本兼容备份已导出；不含附件、归档与分支元数据"
        })
    }
    internal fun importUserBackup(uri:Uri,complete:Boolean,resolver:android.content.ContentResolver=application.contentResolver) {
        dataTask(importing=true,work={
            val stream=resolver.openInputStream(uri)
                ?: throw java.io.IOException("无法打开备份")
            val text=stream.use { readBackupText(it,if(complete) 112L*1024*1024 else 16L*1024*1024) }
            if(complete) {
                val result=neutralBackup.importPackage(text)
                val inventory=result.inventory
                if(result.alreadyImported) "此完整备份已导入过；未重复追加，未执行历史请求"
                else "已导入${inventory.conversations}个会话、${inventory.messages}条消息、"+
                    "${inventory.branches}个分支、${inventory.archives}个归档、${inventory.attachments}个附件；"+
                    "非秘密配置仅保存为备份元数据，未执行历史请求"
            } else {
                val counts=database.importBackup(text)
                "已导入${counts.first}个会话、${counts.second}条消息；未执行历史请求"
            }
        })
    }
    internal fun readBackupText(stream:java.io.InputStream,limit:Long):String {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=stream.read(buffer);if(count<0) break
            require(out.size().toLong()+count<=limit)
            out.write(buffer,0,count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
    }
    internal fun dataTask(importing:Boolean=false,work:()->String) {
        check(ready && active==null && !dataBusy && problem?.startsWith("会话恢复失败")!=true)
        dataBusy=true;notifyChanged()
        try {executor.execute {
            var value=if(importing) "导入失败；原会话保留，格式、校验或身份冲突" else "导出失败；目标文件可能不完整，原会话保留"
            var success=false
            try {value=work();success=true} catch(failure:Exception) {
                value=when(failure) {
                    is SecurityException -> "备份文件访问权限不足或已失效；请重新选择文件。原会话保留，导出目标可能不完整"
                    is java.nio.charset.CharacterCodingException -> "导入失败；备份不是有效UTF-8文本。原会话保留，未执行历史请求"
                    is java.io.IOException -> if(importing) "导入失败；读取备份或写入附件失败。原会话保留，未执行历史请求"
                        else "导出失败；读取附件或写入目标失败。目标文件可能不完整，请勿作为有效备份；原会话保留"
                    else -> value
                }
            }
            finally {
                val notice=value;val changed=success && importing
                handler.post {if(changed) dataRevision++;dataNotice=notice;dataBusy=false;listeners.toList().forEach {it()}}
            }
        }} catch(failure:java.util.concurrent.RejectedExecutionException) {dataBusy=false;notifyChanged();throw failure}
    }
    fun stop() {runner?.stop()}
    internal fun close() {stop();executor.shutdown();check(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));database.close()}
}
