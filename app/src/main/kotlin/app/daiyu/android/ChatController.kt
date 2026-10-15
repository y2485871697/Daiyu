package app.daiyu.android

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextRegistry
import app.daiyu.android.context.ContextRegistryScope
import app.daiyu.android.integration.tools.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.ToolLedger
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.model.ModelFactory
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
    constructor(application:Application):this(application,ConversationDatabase(application),ModelSettingsStore(application),application.getSharedPreferences("conversation-ui",0),{ModelFactory.create(it)})
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    // Entry points and callback commits share this controller's monitor. Runner control and
    // shutdown waits stay outside it, so a worker can always finish its last publication.
    private val listeners=mutableMapOf<()->Unit,Any>()
    private var closed=false
    private var preparing=false
    private var recoveryFailed=false
    private var runtimeStore:SQLiteRuntimeStore?=null
    private val toolLedger = ToolLedger(SQLiteToolLedgerStore(database))
    private fun idleAvailable() = !closed && ready && !preparing && !dataBusy && active==null &&
        !recoveryFailed && problem?.startsWith("会话恢复失败")!=true
    internal val selections=mutableMapOf<ConversationId?,Pair<Int,Int>>()
    @Volatile var active:Message?=null;private set
    @Volatile var problem:String?=null;private set
    @Volatile private var runner:ChatRunner?=null
    @Volatile var dataBusy=false;private set
    var dataRevision=0L;private set
    var dataNotice:String?=null;private set
    @Synchronized internal fun consumeDataNotice():String? = dataNotice.also {dataNotice=null}
    @Volatile var ready=false;private set
    init {executor.execute {
        val failed=try {
            val runtime=SQLiteRuntimeStore.acquire(database)
            runtimeStore=runtime
            runtime.recoverPending();false
        } catch (_:Exception) {true}
        synchronized(this) {
            if(!closed) {
                recoveryFailed=failed
                if(failed) problem="会话恢复失败，原数据已保留"
                ready=true;notifyChanged()
            }
        }
    }}
    @Synchronized fun observe(listener:()->Unit) {
        if(!closed && listener !in listeners) listeners[listener]=Any()
    }
    @Synchronized fun unobserve(listener:()->Unit) {listeners.remove(listener)}
    private fun dispatchChanged() {
        val snapshot=synchronized(this) {if(closed) emptyList() else listeners.map {it.key to it.value}}
        snapshot.forEach {(listener,registration)->
            val current=synchronized(this) {!closed && listeners[listener]===registration}
            if(current) try {listener()} catch (_:Exception) { /* One observer cannot suppress the others. */ }
        }
    }
    private fun notifyChanged() {handler.post {dispatchChanged()} }
    @Synchronized fun send(conversation:Conversation?,text:String):Conversation? {
        if(!idleAvailable() || text.isBlank()) return null
        // Reserve before settings/history reads (also protects against reentrant entry).
        preparing=true
        return try {sendPrepared(conversation,text)} finally {preparing=false}
    }
    private fun sendPrepared(conversation:Conversation?,text:String):Conversation? {
        val config=try {settings.load().also {it.validate()}} catch (_:CredentialUnavailableException) {
            problem="已保存密钥不可用；请到模型设置重新输入，未发起网络请求";notifyChanged();return null
        } catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return null}
        val conversationId=conversation?.id ?: ConversationId(UUID.randomUUID().toString())
        val run=RunId(UUID.randomUUID().toString())
        val user=Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        val assistant=Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"assistant",emptyList(),RunStatus.RUNNING)
        val contextService = ContextService(application, database)
        val preparedContext = try {
            if (conversation == null) null else contextService.prepareSend(conversationId)
        } catch (_: Exception) {
            problem="读取历史失败；草稿保留，未发起网络请求";notifyChanged();return null
        }
        val prior = preparedContext?.messages.orEmpty()
        val preparedModel=try {model(config)} catch (_:Exception) {
            problem="模型初始化失败；草稿保留，未发起网络请求";notifyChanged();return null
        }
        val owned = try {
            val contextTools = ScopedContextTools.create(
                ContextRegistry(contextService), ContextRegistryScope(conversationId),
                run, preparedContext?.archiveId)
            val catalog = AndroidToolRegistry.create(application, contextTools).snapshot()
            val capabilities = SessionCapabilities(run.value,
                AndroidToolRegistry.capabilitiesFor(config.enabledTools()) + contextTools.map { it.definition.capability })
            val tools = CatalogModelBridge(catalog, capabilities, toolLedger, conversationId, run)
            ChatRunner(preparedModel, database, tools,
                RunnerOptions(runtimeStore=checkNotNull(runtimeStore), receiptBatchExecutor=tools)) {
                synchronized(this) {
                    if(!closed && active?.id==assistant.id && active?.runId==run && active?.conversationId==conversationId &&
                        it.id==assistant.id && it.runId==run && it.conversationId==conversationId) {active=it;notifyChanged()}
                }
            }
        } catch (_: Exception) {
            problem="运行准备失败；草稿保留，未发起网络请求";notifyChanged();return null
        }
        if (closed) return null
        val saved=try {
            database.send(user,assistant,conversation==null)
        } catch (_:Exception) {problem="发送内容保存失败；草稿保留，未发起网络请求";notifyChanged();return null}
        problem=null;active=assistant
        runner=owned
        executor.execute {
            val result=owned.run(assistant,prior+user)
            synchronized(this) {
                if(!closed && runner===owned && active?.id==assistant.id && active?.runId==run && active?.conversationId==conversationId) {
                    runner=null
                    if (result.error?.startsWith("保存失败")==true) { active=result;problem=result.error }
                    else active=null
                    notifyChanged()
                }
            }
        }
        notifyChanged();return saved
    }
    private val neutralBackup by lazy { NeutralBackupService(application, database) }
    internal class ConfigurationRestorePreview internal constructor(
        val plan:NonSecretRestorePlan, internal val metadata:String
    )
    private var configurationRestorePreview:ConfigurationRestorePreview?=null
    private fun checkConfigurationRestoreAvailable() {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(idleAvailable()) {
            "请等待会话恢复、运行或数据操作结束后再恢复配置"
        }
    }
    /** Only reads imported metadata and the five non-secret current fields. Never loads a credential. */
    @Synchronized internal fun previewImportedConfiguration():ConfigurationRestorePreview {
        checkConfigurationRestoreAvailable()
        configurationRestorePreview=null
        val metadata=neutralBackup.importedConfiguration()
        return ConfigurationRestorePreview(settings.prepareNonSecretRestore(metadata),metadata.toString()).also {
            configurationRestorePreview=it
        }
    }
    @Synchronized internal fun cancelConfigurationRestore(preview:ConfigurationRestorePreview) {
        if(configurationRestorePreview===preview) configurationRestorePreview=null
    }
    /** Single-use manual confirmation. Recheck runtime gates AND both snapshots at the actual commit. */
    @Synchronized internal fun restoreImportedConfiguration(preview:ConfigurationRestorePreview,authorized:Boolean) {
        val current=configurationRestorePreview
        configurationRestorePreview=null
        checkConfigurationRestoreAvailable()
        require(authorized && current===preview) {"恢复预览已失效；请重新预览并确认"}
        check(neutralBackup.importedConfiguration().toString()==preview.metadata) {"备份配置已变化；请重新预览"}
        checkConfigurationRestoreAvailable()
        settings.applyNonSecretRestore(preview.plan,authorized=true)
        notifyChanged()
    }
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
    @Synchronized internal fun dataTask(importing:Boolean=false,work:()->String) {
        check(idleAvailable())
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
                handler.post {
                    val committed=synchronized(this) {
                        if(closed) false else {
                            if(changed) dataRevision++
                            dataNotice=notice;dataBusy=false;true
                        }
                    }
                    if(committed) dispatchChanged()
                }
            }
        }} catch(failure:java.util.concurrent.RejectedExecutionException) {dataBusy=false;notifyChanged();throw failure}
    }
    fun stop() {val owned=synchronized(this) {runner};owned?.stop()}
    internal fun close() {
        val owned=synchronized(this) {
            if(closed) null else {
                closed=true;ready=false;dataBusy=false;active=null
                configurationRestorePreview=null;dataNotice=null;listeners.clear()
                runner.also {runner=null}
            }
        }
        handler.removeCallbacksAndMessages(null)
        owned?.stop()
        executor.shutdown()
        // Never close storage while an accepted run/data task can still be using it.
        check(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))
        database.close()
        runtimeStore?.close();runtimeStore=null
    }
}
