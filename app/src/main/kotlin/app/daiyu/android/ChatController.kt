package app.daiyu.android

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextRegistry
import app.daiyu.android.context.ContextRegistryScope
import app.daiyu.android.integration.tools.*
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildModelBatch
import app.daiyu.android.domain.delegation.ChildModelDefinition
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.ToolLedger
import android.os.Handler
import android.os.Looper
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.domain.question.*
import app.daiyu.android.domain.tools.BarrierOffer
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.question.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import app.daiyu.android.domain.runtime.SupplementAdmission
import app.daiyu.android.domain.runtime.ExecutionState
import app.daiyu.android.skills.SkillRunSession
import app.daiyu.android.skills.SkillToolRegistry
import app.daiyu.android.skills.SkillManagement
import app.daiyu.android.model.catalog.ModelCatalogService
import app.daiyu.android.model.catalog.RemoteModel
import app.daiyu.android.model.ModelFactory
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.Executors

class ChatApplication:Application() {
    val shizuku by lazy { app.daiyu.android.shizuku.ShizukuAuthorization(this) }
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
    private var childSupervisor:app.daiyu.android.domain.delegation.RetainedChildTasks?=null
    private val backgroundRuns=linkedMapOf<RunId,RunChildTasks>()
    private var childrenClosing=false
    private var childrenClosed=false
    private var questionStore:PrivateQuestionStore?=null
    private val closeDone=CountDownLatch(1)
    private var closingStorage=false
    private class RunResources(val initial:Message,val runner:ChatRunner,val tools:RunCatalogTools) {
        val writer=Executors.newSingleThreadExecutor()
        val waiter=Executors.newSingleThreadExecutor()
        var question:QuestionRunSession?=null
        var offer:BarrierOffer?=null
        var busy=false
        var waiting=false
        var fenced=false
        var cleaning=false
        var cleanupConfirmed=false
        var cleanupFailed=false
        var devicesClosing=false
        var devicesClosed=false
        var result:Message?=null
    }
    private var resources:RunResources?=null
    internal data class QuestionProjection(val identity:QuestionIdentity,val record:QuestionRecord,
        val binding:QuestionCardBinding?,val canAnswer:Boolean)
    private val questionHistory=linkedMapOf<String,Pair<QuestionIdentity,QuestionRecord>>()
    private fun questionIdentity(key:String):QuestionIdentity? = try {
        require(key.startsWith("q1:"))
        var offset=3
        val parts=(0 until 4).map {
            val colon=key.indexOf(':',offset)
            require(colon>offset)
            val length=key.substring(offset,colon).toInt()
            require(length in 1..256 && colon+1+length<=key.length)
            key.substring(colon+1,colon+1+length).also {offset=colon+1+length}
        }
        require(offset==key.length)
        QuestionIdentity(parts[0],parts[1],parts[2],parts[3]).also {require(it.storageKey==key)}
    } catch (_:IllegalArgumentException) {null}
    private fun rememberQuestions(records:List<QuestionRecord>) {
        records.forEach { record -> questionIdentity(record.callId)?.let {questionHistory[record.callId]=it to record} }
    }
    @Synchronized internal fun questionProjection(message:Message,call:ToolInvocation):QuestionProjection? {
        if(call.name!=ASK_USER_TOOL_NAME || message.role!=Message.ASSISTANT_ROLE) return null
        val (identity,record)=questionHistory.values.singleOrNull {
            it.first.conversationId==message.conversationId.value && it.first.runId==message.runId.value &&
                it.first.callId==call.id.value
        } ?: return null
        val current=resources?.takeIf {it.initial.id==message.id && it.initial.runId==message.runId &&
            it.initial.conversationId==message.conversationId}
        val session=current?.question
        val writable=!closed && current!=null && !current.fenced && current.waiting &&
            current.offer?.context?.owner?.ownerId==identity.ownerId &&
            current.offer?.pending?.callId==call.id && record.answer==null && !record.interrupted
        return QuestionProjection(identity,record,session?.binding,writable)
    }
    private val toolLedger = ToolLedger(SQLiteToolLedgerStore(database))
    private val childPreferences by lazy { ConversationSubAgentPreferences(application.getSharedPreferences("subagent_configuration", 0)) }
    private fun conversationExists(id: ConversationId): Boolean = database.readableDatabase.rawQuery(
        "SELECT 1 FROM conversations WHERE id=?", arrayOf(id.value)).use { it.moveToFirst() }
    private val childBindings = SavedSubAgentRuntimeBindings(settings::load) { model(it.asModelSettings()) }
    @Synchronized internal fun bindChildSavedModel(conversationId: ConversationId, profileId: String,
        expectedApiModel: String, provider: SubAgentProvider): Boolean {
        childBindings.revoke(SubAgentConfigKey.Conversation(conversationId.value), profileId)
        if (!idleAvailable()) return false
        return try {
            require(conversationExists(conversationId)) { "Conversation no longer exists" }
            val owner = SubAgentConfigKey.Conversation(conversationId.value)
            val config = childPreferences.snapshot(owner)
            require(config.enabled)
            val profile = requireNotNull(config.profiles.singleOrNull { it.id == profileId })
            childBindings.authorizeSavedModel(owner, profile, expectedApiModel, provider)
            problem = null; notifyChanged(); true
        } catch (_: Exception) { problem = "子模型绑定失败；未发起请求，请核对明确选择及已保存配置"; notifyChanged(); false }
    }
    @Synchronized internal fun revokeChildSavedModel(conversationId: ConversationId, profileId: String) {
        childBindings.revoke(SubAgentConfigKey.Conversation(conversationId.value), profileId)
        backgroundRuns.values.forEach { tasks -> tasks.snapshots().filter {
            it.record.owner.conversationId == conversationId.value && it.record.profileId == profileId && !it.settled
        }.forEach { tasks.stop(it.record.owner) } }
    }
    @Synchronized internal fun hasLiveChildTasks(conversationId:ConversationId?=null):Boolean =
        backgroundRuns.values.any { tasks -> tasks.snapshots().any {
            !it.settled && (conversationId==null || it.record.owner.conversationId==conversationId.value)
        } }
    @Synchronized internal fun stopChildTask(record:ChildTaskRecord):app.daiyu.android.domain.delegation.ChildTaskStop {
        if(closed || !ready) return app.daiyu.android.domain.delegation.ChildTaskStop.NOT_FOUND
        return backgroundRuns[RunId(record.owner.parentRunId)]?.stop(record.owner)
            ?: app.daiyu.android.domain.delegation.ChildTaskStop.NOT_FOUND
    }
    @Synchronized internal fun sendWithChildren(conversation: Conversation, text: String): Conversation? {
        if (!idleAvailable() || text.isBlank()) return null
        preparing = true
        return try {
            val models = try {
                require(conversationExists(conversation.id))
                val key = SubAgentConfigKey.Conversation(conversation.id.value)
                childBindings.freeze(key, childPreferences.snapshot(key))
            } catch (_: Exception) {
                problem = "子模型配置未绑定、已变更或不支持；草稿保留，未切换到主模型"; notifyChanged(); return null
            }
            sendPrepared(conversation, text, models)
        } finally { preparing = false }
    }
    @Synchronized internal fun childTasks(conversationId: ConversationId, limit: Int = 20): List<ChildTaskRecord> {
        check(!closed && ready && !recoveryFailed) { "child_tasks_unavailable" }
        return checkNotNull(runtimeStore).childTasks.list(conversationId.value, limit)
    }
    /** Read on a worker thread; no runtime acquisition, recovery or provider request. */
    internal fun recordedRequestUsage(conversationId: ConversationId? = null): app.daiyu.android.model.usage.AttributedUsageStats {
        synchronized(this) { check(!closed && ready && !recoveryFailed) { "recorded_usage_unavailable" } }
        check(Looper.myLooper() != Looper.getMainLooper()) { "recorded_usage_requires_worker" }
        return app.daiyu.android.context.DatabaseTransactions { database.readableDatabase }.run {
            RecordedUsageReader.readAttributed(it, conversationId)
        }
    }
    private fun idleAvailable() = !closed && ready && !preparing && !dataBusy && active==null &&
        !recoveryFailed && problem?.startsWith("会话恢复失败")!=true
    internal val selections=mutableMapOf<ConversationId?,Pair<Int,Int>>()
    @Volatile var active:Message?=null;private set
    @Volatile var problem:String?=null;private set
    @Volatile private var runner:ChatRunner?=null
    @Volatile private var runTools:RunCatalogTools?=null
    @Volatile var dataBusy=false;private set
    var dataRevision=0L;private set
    var dataNotice:String?=null;private set
    @Synchronized internal fun consumeDataNotice():String? = dataNotice.also {dataNotice=null}
    @Volatile var ready=false;private set
    init {executor.execute {
        val failed=try {
            val runtime=SQLiteRuntimeStore.acquire(database)
            runtimeStore=runtime
            val questions=PrivateQuestionStore(application)
            questionStore=questions
            runtime.recoverPending {questions.interruptPending(it.ownerPrefix)}
            childSupervisor=app.daiyu.android.domain.delegation.RetainedChildTasks(runtime.childTasks, usageRecorder=runtime.childUsage)
            val history=questions.records()
            synchronized(this) {rememberQuestions(history)}
            false
        } catch (_:Exception) {true}
        synchronized(this) {
            if(!closed) {
                recoveryFailed=failed
                if(failed) problem="会话恢复失败，原数据已保留"
                ready=true;notifyChanged()
            }
        }
    }}
    internal var modelCatalog=ModelCatalogService(settings)
    internal fun fetchModelCatalog(draft:ModelSettings,cancelled:()->Boolean,
        complete:(List<RemoteModel>?)->Unit) = synchronized(this) {
        if(closed || !ready || active!=null || dataBusy) return@synchronized false
        val service=modelCatalog
        executor.execute {
            fun revoked() = synchronized(this) {closed} || cancelled()
            val models=try {service.fetchDraft(draft,::revoked)} catch (_:Exception) {null}
            if(!revoked()) handler.post {if(!revoked()) complete(models)}
        }
        true
    }
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
    private fun sendPrepared(conversation:Conversation?,text:String, childModels:Map<String,ChildModelDefinition> = emptyMap()):Conversation? {
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
        var preparingTools: RunCatalogTools? = null
        var preparingDevices: RunDeviceResources? = null
        var preparingChildren: RunChildModels? = null
        val prepared = try {
            val contextTools = ScopedContextTools.create(
                ContextRegistry(contextService), ContextRegistryScope(conversationId),
                run, preparedContext?.archiveId)
            val children = if (childModels.isEmpty()) null else RunChildModels(
                ChildModelBatch(childModels, store = checkNotNull(runtimeStore).childTasks,
                    usageRecorder = checkNotNull(runtimeStore).childUsage), childModels.keys)
            preparingChildren = children
            val devices = RunDeviceResources(application)
            preparingDevices = devices
            val registry = AndroidToolRegistry.create(devices, contextTools, enableUserBarrier=true)
            val capabilities = SessionCapabilities(run.value,
                AndroidToolRegistry.capabilitiesFor(config.enabledTools()) + contextTools.map { it.definition.capability } +
                    setOf(SkillRunSession.READ, SkillRunSession.RESOURCE, AndroidToolRegistry.ASK_USER_CAPABILITY) +
                    if (children == null) emptySet() else setOf(RunChildModels.CAPABILITY,RunChildTasks.CAPABILITY))
            val backgroundFactory: ((app.daiyu.android.domain.runtime.RunExecutionOwner, SessionCapabilities,
                app.daiyu.android.domain.tools.TaskAuthorization) -> RunChildTasks)? =
                if(childModels.isEmpty()) null else { owner, grants, authorization ->
                    synchronized(this) {
                        check(!closed)
                        val supervisor=checkNotNull(childSupervisor)
                        val binding=supervisor.bind(owner,grants,authorization,childModels,::notifyChanged)
                        RunChildTasks(supervisor,binding,childModels.keys).also { backgroundRuns[run]=it }
                    }
                }
            val tools = RunCatalogTools(registry, capabilities, toolLedger, conversationId, run, devices, children,
                backgroundFactory) { grants, authorization ->
                SkillToolRegistry(application).captureForRun(grants, authorization)
            }
            preparingTools = tools
            val owned = ChatRunner(preparedModel, database, tools,
                RunnerOptions(runtimeStore=checkNotNull(runtimeStore), receiptBatchExecutor=tools)) {
                synchronized(this) {
                    if(!closed && active?.id==assistant.id && active?.runId==run && active?.conversationId==conversationId &&
                        it.id==assistant.id && it.runId==run && it.conversationId==conversationId) {active=it;notifyChanged()}
                }
            }
            owned to tools
        } catch (_: Exception) {
            preparingTools?.close()
            preparingDevices?.close()
            preparingChildren?.close()
            problem="运行准备失败；草稿保留，未发起网络请求";notifyChanged();return null
        }
        val (owned, tools) = prepared
        if (closed) { tools.close(); return null }
        val saved=try {
            database.send(user,assistant,conversation==null)
        } catch (_:Exception) {tools.close();problem="发送内容保存失败；草稿保留，未发起网络请求";notifyChanged();return null}
        problem=null;active=assistant
        runner=owned;runTools=tools
        val lifetime=RunResources(assistant,owned,tools)
        resources=lifetime
        enqueueSlice(lifetime,allowStopped=true) {owned.startSlice(assistant,prior+user)}
        notifyChanged();return saved
    }
    private fun enqueueSlice(lifetime:RunResources,allowStopped:Boolean=false,work:()->RunSlice) {
        check(!lifetime.busy)
        lifetime.busy=true
        executor.execute {
            val allowed=synchronized(this) {resources===lifetime && (allowStopped || !lifetime.fenced)}
            val result=if(allowed) runCatching(work) else null
            handler.post {synchronized(this) {
                if(resources!==lifetime) return@synchronized
                lifetime.busy=false
                // A fenced queued resume has not consumed the Runner continuation.
                if(result==null && lifetime.offer!=null) lifetime.waiting=true
                if(result!=null) result.fold(
                    onSuccess={slice -> when(slice) {
                        is RunSlice.Completed -> {lifetime.waiting=false;lifetime.offer=null;lifetime.result=slice.message}
                        is RunSlice.Suspended -> {lifetime.offer=slice.offer;lifetime.waiting=true}
                    }},
                    onFailure={
                        if(lifetime.fenced && lifetime.runner.state().phase==app.daiyu.android.domain.runtime.ExecutionPhase.WAITING_USER) {
                            // stop may win after the queue gate but before resumeSlice takes its continuation.
                            lifetime.waiting=true
                        } else {
                            lifetime.waiting=false
                            lifetime.result=(active ?: lifetime.initial).copy(error="保存失败，运行已停止；原检查点保留，重新打开后仅恢复历史")
                            problem=lifetime.result?.error
                            lifetime.fenced=true
                            lifetime.runner.stop()
                        }
                    })
                if(lifetime.result!=null || lifetime.fenced) cleanupRun(lifetime)
                else if(lifetime.waiting) beginQuestion(lifetime,checkNotNull(lifetime.offer))
            }}
        }
    }
    private fun beginQuestion(lifetime:RunResources,offer:BarrierOffer) {
        val original=offer.context.owner
        if(!offer.waitingCommitted || !original.owns(lifetime.initial) || lifetime.fenced) {
            stopRun(lifetime);return
        }
        if(lifetime.question==null) {
            val scope=QuestionIdentity(original.conversationId.value,original.runId.value,original.ownerId,offer.pending.callId.value)
            val registry=QuestionRegistry(checkNotNull(questionStore),scope)
            lifetime.question=QuestionRunSession(registry,scope,lifetime.writer,lifetime.waiter,
                {synchronized(this) {resources===lifetime && !lifetime.fenced && !closed}},
                {_,_->handler.post {synchronized(this) {
                    if(resources===lifetime) {problem="提问清理未确认；请重试停止";stopRun(lifetime)}
                }}},original,
                {synchronized(this) {lifetime.offer.takeIf {resources===lifetime && lifetime.waiting && !lifetime.fenced && !closed}}})
        }
        val accepted=checkNotNull(lifetime.question).begin(offer,
            onPending={record -> synchronized(this) {
                if(resources===lifetime && lifetime.offer===offer && !lifetime.fenced && !closed) {
                    rememberQuestions(listOf(record));notifyChanged()
                }
            }},
            onResult={result -> synchronized(this) {
                if(resources!==lifetime || lifetime.offer!==offer || !lifetime.waiting || lifetime.fenced || closed) return@synchronized
                val identity=result.identity
                if(identity.ownerId!=original.ownerId || identity.runId!=original.runId.value ||
                    identity.conversationId!=original.conversationId.value || identity.callId!=offer.pending.callId.value) {
                    stopRun(lifetime);return@synchronized
                }
                val receipt=when(result) {
                    is QuestionRunResult.Answered -> {
                        questionHistory[identity.storageKey]?.let {questionHistory[identity.storageKey]=identity to it.second.copy(answer=result.answer)}
                        ExecutionReceipt.Confirmed(org.json.JSONObject().put("ok",true).put("call_id",identity.callId)
                            .put("answer",result.answer.json()).toString())
                    }
                    is QuestionRunResult.Stopped -> {stopRun(lifetime);return@synchronized}
                    is QuestionRunResult.Rejected -> ExecutionReceipt.Unknown("question_rejected")
                    is QuestionRunResult.Unconfirmed -> ExecutionReceipt.Unknown("question_unconfirmed")
                }
                lifetime.waiting=false
                enqueueSlice(lifetime) {lifetime.runner.resumeSlice(offer,receipt)}
            }})
        if(!accepted) stopRun(lifetime)
        notifyChanged()
    }
    private fun stopRun(lifetime:RunResources) {
        lifetime.fenced=true
        lifetime.runner.stop()
        lifetime.tools.revokeChildren()
        lifetime.tools.close()
        cleanupRun(lifetime)
    }
    private fun cleanupRun(lifetime:RunResources) {
        if(resources!==lifetime) return
        lifetime.fenced=true
        if(!lifetime.cleanupConfirmed && !lifetime.cleaning) {
            val question=lifetime.question
            if(question==null) lifetime.cleanupConfirmed=true
            else {
                lifetime.cleaning=true;lifetime.cleanupFailed=false
                question.stop { confirmed ->
                    if(confirmed) lifetime.writer.execute {
                        val history=runCatching {checkNotNull(questionStore).records()}
                        handler.post {synchronized(this) {
                            if(resources===lifetime) {
                                lifetime.cleaning=false
                                history.fold(onSuccess={rememberQuestions(it);lifetime.cleanupConfirmed=true},
                                    onFailure={lifetime.cleanupFailed=true;problem="提问清理状态读取失败；请重试停止"})
                                if(lifetime.cleanupConfirmed) cleanupRun(lifetime) else notifyChanged()
                            }
                        }}
                    } else synchronized(this) {
                        lifetime.cleaning=false;lifetime.cleanupFailed=true
                        problem="提问停止尚未持久确认；请重试停止，原资源保留";notifyChanged()
                    }
                }
            }
        }
        if(!lifetime.cleanupConfirmed || lifetime.busy) return
        if(lifetime.waiting) {
            lifetime.waiting=false
            enqueueSlice(lifetime,allowStopped=true) {lifetime.runner.drainStoppedSlice()}
            return
        }
        if(lifetime.result==null) return
        if(!lifetime.devicesClosing) {
            lifetime.devicesClosing=true
            lifetime.tools.closeWhenIdle {
                handler.post {synchronized(this) {
                    if(resources===lifetime) {
                        lifetime.devicesClosed=true
                        cleanupRun(lifetime)
                    }
                }}
            }
        }
        if(!lifetime.devicesClosed) return
        lifetime.writer.shutdown();lifetime.waiter.shutdown()
        resources=null;runner=null;runTools=null
        val result=checkNotNull(lifetime.result)
        if(!closed) {
            if(result.error?.startsWith("保存失败")==true) {active=result;problem=result.error}
            else {active=null;if(problem?.startsWith("提问")==true) problem=null}
            notifyChanged()
        }
        if(closed) closeStorageWhenIdle()
    }
    @Synchronized internal fun canRetryQuestionCleanup(message:Message):Boolean = resources?.let {
        it.initial.id==message.id && it.initial.runId==message.runId &&
            it.initial.conversationId==message.conversationId && it.cleanupFailed && !it.cleaning
    }==true
    private fun closeStorageWhenIdle() {
        if(resources!=null || closingStorage) return
        if(!childrenClosed) {
            if(!childrenClosing) {
                childrenClosing=true
                val supervisor=childSupervisor
                val drained={ handler.post {synchronized(this) {childrenClosed=true;closeStorageWhenIdle()} }; Unit }
                if(supervisor==null) drained() else supervisor.closeWhenIdle(drained)
            }
            return
        }
        closingStorage=true
        executor.execute {
            try {questionStore?.close();database.close();runtimeStore?.close();runtimeStore=null}
            finally {executor.shutdown();closeDone.countDown()}
        }
    }
    internal fun skillManagement():SkillManagement = SkillManagement.create {SkillToolRegistry(application)}
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
    internal fun exportConversationBackup(uri:Uri,conversationId:ConversationId,resolver:android.content.ContentResolver=application.contentResolver) {
        dataTask(work={
            val text=neutralBackup.exportConversation(conversationId)
            resolver.openOutputStream(uri,"wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("无法打开目标")
            "单会话中立备份已导出；不含全局草稿、配置、凭据或运行恢复状态，请妥善保管"
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
        check(idleAvailable() && !hasLiveChildTasks()) { "请等待后台子任务实际结束" }
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
    private fun ownedRunner(owner:Message):ChatRunner? {
        val current=active ?: return null
        return if(!closed && ready && !dataBusy && current.status==RunStatus.RUNNING &&
            owner.id==current.id && owner.runId==current.runId && owner.conversationId==current.conversationId &&
            owner.role==Message.ASSISTANT_ROLE) runner else null
    }
    @Synchronized internal fun runState(owner:Message):ExecutionState? = ownedRunner(owner)?.state()
    /** Backend-only paused text admission. The Boolean is queueing, never a durable receipt.
     * Callers retain the messageId and draft until Accepted for the matching editor owner. */
    internal fun admitSupplement(owner:Message, messageId:MessageId, text:String,
        complete:(SupplementAdmission)->Unit):Boolean {
        val owned=synchronized(this) {
            resources?.takeIf {!it.fenced && it.runner===ownedRunner(owner)}?.runner
        }
        fun deliver(result:SupplementAdmission) {
            handler.post {
                try {complete(result)} catch (_:Exception) { }
                synchronized(this) {if(owned!=null && ownedRunner(owner)===owned) notifyChanged()}
            }
        }
        if(owned==null || text.isBlank()) {
            deliver(SupplementAdmission.Rejected(messageId));return false
        }
        val user=Message(messageId,owner.conversationId,owner.runId,Message.USER_ROLE,
            listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        return owned.admitSupplement(user,::deliver)
    }
    internal fun pause(owner:Message):Boolean {
        val owned=synchronized(this) {ownedRunner(owner)} ?: return false
        val accepted=owned.pause()
        if(accepted) synchronized(this) {if(ownedRunner(owner)===owned) notifyChanged()}
        return accepted
    }
    internal fun resume(owner:Message):Boolean {
        val owned=synchronized(this) {ownedRunner(owner)} ?: return false
        val accepted=owned.resume()
        if(accepted) synchronized(this) {if(ownedRunner(owner)===owned) notifyChanged()}
        return accepted
    }
    // True acknowledges the matching control target, not a persisted terminal outcome.
    internal fun stop(owner:Message):Boolean {
        val lifetime=synchronized(this) {
            resources?.takeIf {!closed && it.initial.id==owner.id && it.initial.runId==owner.runId &&
                it.initial.conversationId==owner.conversationId && owner.role==Message.ASSISTANT_ROLE}
                ?.also {it.fenced=true}
        } ?: return false
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
        return true
    }
    fun stop() {
        val lifetime=synchronized(this) {resources?.also {it.fenced=true}} ?: return
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
    }
    internal fun close() {
        val lifetime=synchronized(this) {
            if(!closed) {
                closed=true;ready=false;dataBusy=false;active=null
                configurationRestorePreview=null;dataNotice=null;listeners.clear();childBindings.clear()
            }
            resources?.also {it.fenced=true}
        }
        lifetime?.runner?.stop();lifetime?.tools?.close()
        handler.post {synchronized(this) {
            if(lifetime!=null && resources===lifetime) cleanupRun(lifetime) else closeStorageWhenIdle()
        }}
        // The control loop must remain available for stop acknowledgments and continuation drain.
        if(Looper.myLooper()!=Looper.getMainLooper()) check(closeDone.await(10,TimeUnit.SECONDS))
    }
}
