package app.daiyu.android

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import app.daiyu.android.assistants.*
import app.daiyu.android.model.AssistantModelFactory
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextPreparationCoordinator
import app.daiyu.android.context.ContextPreparationCancellation
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.context.ProductionContextPreparation
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
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.media.AndroidImageAttachments
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.MessageImageAssetStore
import java.io.File
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.Executors

class ChatApplication:Application() {
    private val terminalProviderDelegate = lazy { app.daiyu.android.terminal.TerminalApplicationProvider(this) }
    internal val terminalProvider by terminalProviderDelegate
    internal val terminalOwner get() = checkNotNull(
        (terminalProvider.state.value as? app.daiyu.android.terminal.TerminalApplicationState.Ready)?.application
    ) { "terminal_catalog_not_ready" }.terminalOwner
    val shizuku by lazy { app.daiyu.android.shizuku.ShizukuAuthorization(this) }
    lateinit var controller:ChatController
    lateinit var hapticsRuntime: app.daiyu.android.haptics.HapticsRuntime
        private set
    private var touchHaptics: AutoCloseable? = null
    override fun onCreate() {
        super.onCreate()
        // One store/runtime for the entire process, shared by UI, streams and settings.
        hapticsRuntime = app.daiyu.android.haptics.HapticsRuntime(
            app.daiyu.android.haptics.LocalHapticSettingsStore(this))
        hapticsRuntime.installForeground(this)
        touchHaptics = app.daiyu.android.ui.haptics.TouchHaptics.install(hapticsRuntime)
        controller = ChatController(this)
        terminalProvider
    }
    override fun onTerminate() {
        // Android normally kills the process without this callback; useful for hosted runtimes.
        if (terminalProviderDelegate.isInitialized()) terminalProvider.close()
        touchHaptics?.close(); touchHaptics = null
        hapticsRuntime.close()
        super.onTerminate()
    }
}
class ChatController internal constructor(private val application:Application,
    val database:ConversationDatabase, val settings:ModelSettingsStore, val preferences:SharedPreferences,
    private val model:(ModelSettings)->ModelPort,
    private val imageModel: (ProviderSnapshot)->ModelPort = { ModelFactory.create(it) },
    private val assistantModel: ((ProviderSnapshot, AssistantRunContext)->ModelPort)? = null,
    val assistantAssets: AssistantAssets = FileAssistantAssets(application),
    private val contextPreparationFactory: (android.content.Context, ContextService) -> ContextPreparationCoordinator =
        ProductionContextPreparation::create) {
    private val contextService by lazy { ContextService(application, database) }
    private val contextPreparation by lazy { contextPreparationFactory(application, contextService) }

    internal class TextSendTicket
    internal data class TextSendOwner(val conversationId: ConversationId?, val editorGeneration: Long,
        val draftRevision: Long)
    internal data class TextSendState(val ticket: TextSendTicket, val owner: TextSendOwner,
        val text: String, val result: TextSendResult? = null)
    private var pendingTextState: TextSendState? = null
    @Synchronized internal fun textSendState(): TextSendState? = pendingTextState
    @Synchronized internal fun acknowledgeTextSend(ticket: TextSendTicket) {
        if (pendingTextState?.ticket === ticket && pendingTextState?.result != null) pendingTextState = null
    }
    internal sealed interface TextSendResult {
        val owner: TextSendOwner
        data class Accepted(override val owner: TextSendOwner, val conversation: Conversation): TextSendResult
        data class Rejected(override val owner: TextSendOwner): TextSendResult
        /** Persistence did not return a confirmed receipt; retain the draft and do not auto-retry. */
        data class Unknown(override val owner: TextSendOwner): TextSendResult
    }
    constructor(application:Application):this(application,ConversationDatabase(application),ModelSettingsStore(application),application.getSharedPreferences("conversation-ui",0),{ModelFactory.create(it)},
        assistantModel=AssistantModelFactory::create)
    val assistantStore: SqliteAssistantStore by lazy { SqliteAssistantStore(database) }
    internal val interactiveMode = app.daiyu.android.config.InteractiveModePreference(preferences)
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    // Entry points and callback commits share this controller's monitor. Runner control and
    // shutdown waits stay outside it, so a worker can always finish its last publication.
    private val listeners=mutableMapOf<()->Unit,Any>()
    private val reasoningListeners=mutableMapOf<(Message, ModelEvent)->Unit,Any>()
    private val runRevocationListeners=mutableSetOf<(Message)->Unit>()
    private var closed=false
    // The existing preparation lane is retained until the worker (and device cleanup) exits.
    // Identity, not a Boolean reset, prevents an old completion from clearing a newer owner.
    private class Preparation(val assistantId: String?, var textTicket: TextSendTicket? = null) {
        val cancellation = ContextPreparationCancellation()
        var revoked = false
        var committing = false
        var persistenceUnconfirmed = false
        var browser: ControllerBrowserPreparation? = null
        var browserContext: AssistantRunContext? = null
        var submitted = false
        var cleanupDeadline = 0L
    }
    private var preparation: Preparation? = null
    private val preparing: Boolean get() = preparation != null
    // Call only after the idle gate; selection is already initialized in memory.
    private fun reservePreparation(textTicket: TextSendTicket? = null): Preparation =
        Preparation(assistantStore.selectedId.value, textTicket).also {
        check(preparation == null)
        preparation = it
    }
    private fun preparationCancelled(token: Preparation): Boolean = synchronized(this) {
        closed || preparation !== token || token.revoked || token.cancellation.isCancelled
    }
    // Fence under the short monitor; transport cancellation callbacks must run OUTSIDE it.
    private fun cancelPreparation() {
        val token = synchronized(this) { preparation?.also { it.revoked = true } }
        token?.cancellation?.cancel()
        token?.browser?.cancel()
        if (token?.browser != null && !token.submitted) finishPreparation(token)
    }
    internal fun bindConversationAssistant(owner: ConversationId, expected: String?, replacement: String): Boolean {
        val token = synchronized(this) {
            if(!idleAvailable() || hasLiveChildTasks(owner)) return false
            reservePreparation()
        }
        return try {
            database.bindAssistant(owner, expected, replacement).also { changed ->
                problem=if(changed) null else "会话或助手绑定已变化；请重新打开选择器"
                notifyChanged()
            }
        } catch (_:Exception) {problem="助手绑定失败；原绑定和草稿保留";notifyChanged();false}
        finally {finishPreparation(token)}
    }
    private var pendingImage:ImageSendHandle?=null
    private var pendingShizuku:ControllerShizukuPreparation?=null
    private var pendingShizukuToken:Preparation?=null
    @Synchronized internal fun shizukuPreparation():ControllerShizukuPreparation? = pendingShizuku
    private val shizukuAccess by lazy { checkNotNull((application as? ChatApplication)?.shizuku) {
        "shizuku_application_host_required"
    } }
    // Use this app-scoped importer in the host, rather than creating another draft store instance.
    internal val imageAttachments by lazy { AndroidImageAttachments(application.contentResolver,
        app.daiyu.android.media.ImageAttachmentStore(File(application.filesDir,"media-draft-images"),
            app.daiyu.android.media.AndroidImageInspector())) }
    private val messageImageAssets by lazy { MessageImageAssetStore(File(application.filesDir,"media-message-images")) }
    private val messageImages by lazy { ControllerMessageImages(database,messageImageAssets) }
    private val imageJournal by lazy { application.getSharedPreferences("image-send-identities",0) }
    private var imageProvider:ProviderSnapshot?=null
    private var imageReaders=0

    /** Worker-thread only. Preview gets read authority, never draft mutation or a raw URI. */
    internal fun messageImageResolver(conversation:ConversationId):app.daiyu.android.media.MessageImageResolver {
        val scoped=messageImages.resolver(conversation)
        return object : app.daiyu.android.media.MessageImageResolver {
            override fun read(owner:app.daiyu.android.media.MessageImageOwner,
                reference:app.daiyu.android.media.MessageImageReference):ByteArray =
                imageRead {scoped.read(owner,reference)}
            override fun freeze(owner:app.daiyu.android.media.MessageImageOwner,
                references:List<app.daiyu.android.media.MessageImageReference>, imageInputSupported:Boolean,
                isCancelled:()->Boolean):List<app.daiyu.android.media.FrozenImageAttachment> =
                imageRead {scoped.freeze(owner,references,imageInputSupported,isCancelled)}
        }
    }
    private fun <T> imageRead(work:()->T):T {
        check(Looper.myLooper()!=Looper.getMainLooper()) {"image_read_requires_worker"}
        synchronized(this) {check(!closed && ready && !recoveryFailed);imageReaders++}
        try {return work()} finally {synchronized(this) {
            imageReaders--
            if(closed) closeStorageWhenIdle()
        }}
    }

    /** Host capability grant for this exact saved endpoint/protocol/model/configuration.
     * Unknown/false is deliberately not upgraded based on a model name. */
    internal fun authorizeImageProvider(provider:ProviderSnapshot):Boolean {
        val matches=try { provider.asModelSettings()==settings.load() } catch (_:Exception) { false }
        return synchronized(this) {
            if(!idleAvailable() || !matches) false else { imageProvider=provider; true }
        }
    }
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
        val deviceCleanup=ControllerShizukuCleanup()
        var result:Message?=null
    }
    private var resources:RunResources?=null
    // Tools can exist before database acceptance. An UNKNOWN close must pin those too.
    private val unacceptedTools=mutableMapOf<RunCatalogTools,ControllerShizukuCleanup>()
    private fun closeUnacceptedTools(tools:RunCatalogTools) {
        val observation=synchronized(this) {
            if(unacceptedTools.containsKey(tools)) return
            ControllerShizukuCleanup().also {unacceptedTools[tools]=it}
        }
        tools.closeWithResult { result -> handler.post {synchronized(this) {
            observation.observe(result)
            if(observation.drained) unacceptedTools.remove(tools)
            else problem="运行准备清理结果 UNKNOWN；原资源保留，不自动重连或重发"
            if(closed) closeStorageWhenIdle() else notifyChanged()
        }}}
    }
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
    private val childBindings = SavedSubAgentRuntimeBindings(settings::load) {
        val config=it.asModelSettings()
        messageImages.wrap(model(config), ProviderSnapshot.capture(config))
    }
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
    internal fun revokeChildSavedModel(conversationId: ConversationId, profileId: String) {
        val targets=synchronized(this) {
            childBindings.revoke(SubAgentConfigKey.Conversation(conversationId.value), profileId)
            backgroundRuns.values.flatMap { tasks -> tasks.snapshots().filter {
                it.record.owner.conversationId == conversationId.value && it.record.profileId == profileId && !it.settled
            }.map { tasks to it.record.owner } }
        }
        // A provider's cancel implementation must never run with the Controller monitor held.
        targets.forEach { (tasks, owner) -> tasks.stop(owner) }
    }
    @Synchronized internal fun hasLiveChildTasks(conversationId:ConversationId?=null):Boolean =
        backgroundRuns.values.any { tasks -> tasks.snapshots().any {
            !it.settled && (conversationId==null || it.record.owner.conversationId==conversationId.value)
        } }
    internal fun stopChildTask(record:ChildTaskRecord):app.daiyu.android.domain.delegation.ChildTaskStop {
        val tasks=synchronized(this) {
            if(closed || !ready) null else backgroundRuns[RunId(record.owner.parentRunId)]
        }
        return tasks?.stop(record.owner) ?: app.daiyu.android.domain.delegation.ChildTaskStop.NOT_FOUND
    }
    // Only the supervisor knows when admission is sealed and every actual transport has exited.
    // Terminal history, cancellation requests, and an empty snapshot are not release boundaries.
    private fun pruneBackgroundRuns() {
        val entries=backgroundRuns.entries.iterator()
        while(entries.hasNext()) if(entries.next().value.isReleased()) entries.remove()
    }
    private fun backgroundTasksChanged() {
        handler.post {synchronized(this) {
            pruneBackgroundRuns()
            if(closed) closeStorageWhenIdle() else notifyChanged()
        }}
    }
    internal fun sendWithChildren(conversation: Conversation, text: String): Conversation? {
        if(rejectSynchronousMainSend()) return null
        val token = synchronized(this) {
            if (!idleAvailable() || text.isBlank()) return null
            reservePreparation()
        }
        return try {
            sendPrepared(token, conversation, text, withChildren=true)
        } finally {finishPreparation(token)}
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
        resources==null && pendingShizuku==null && unacceptedTools.isEmpty() &&
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
            assistantStore.requireReady() // Migration failure closes the send gate without replacing source data.
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

    /** Real accepted runner events only; no persistence, snapshot replay or model interception.
     * Capture registrations at emission, not delivery: a replacement Activity cannot receive an
     * old owner's queued event. Identity and registration are both checked again on the main thread.
     */
    @Synchronized internal fun observeReasoning(listener:(Message, ModelEvent)->Unit):AutoCloseable {
        val registration=Any()
        if(!closed) reasoningListeners[listener]=registration
        return AutoCloseable { synchronized(this) {
            if(reasoningListeners[listener]===registration) reasoningListeners.remove(listener)
        } }
    }
    /** Synchronous capability revocation, not a queued UI/status notification. Callbacks must be
     * thread-safe and must not wait for the main thread or for runner shutdown. */
    @Synchronized internal fun observeRunRevocation(listener:(Message)->Unit):AutoCloseable {
        if(!closed) runRevocationListeners.add(listener)
        return AutoCloseable { synchronized(this) {runRevocationListeners.remove(listener)} }
    }
    @Synchronized internal fun isRunUnfenced(owner:Message):Boolean = !closed &&
        owner.role==Message.ASSISTANT_ROLE && resources?.let {
            !it.fenced && it.initial.id==owner.id && it.initial.runId==owner.runId &&
                it.initial.conversationId==owner.conversationId
        }==true && active?.id==owner.id && active?.runId==owner.runId &&
            active?.conversationId==owner.conversationId

    // All callers hold the controller monitor. Revoke before any potentially slow runner control.
    private fun fenceRun(lifetime:RunResources) {
        if(lifetime.fenced) return
        lifetime.fenced=true
        runRevocationListeners.toList().forEach { listener ->
            try {listener(lifetime.initial)} catch (_:Exception) { /* Optional feedback only. */ }
        }
    }
    private fun publishReasoning(owner:Message,event:ModelEvent) {
        synchronized(this) {
            if(!isRunUnfenced(owner)) return
            val targets=reasoningListeners.toList()
            handler.post {
                targets.forEach { (listener,registration) ->
                    val current=synchronized(this) {
                        reasoningListeners[listener]===registration && isRunUnfenced(owner)
                    }
                    if(current) try { listener(owner,event) } catch (_:Exception) {
                        // Optional feedback must never fail a run or suppress another observer.
                    }
                }
            }
        }
    }
    fun send(conversation:Conversation?,text:String):Conversation? {
        if(rejectSynchronousMainSend()) return null
        val token = synchronized(this) {
            if(!idleAvailable() || text.isBlank()) return null
            // Legacy synchronous API; UI hosts should use sendText instead.
            reservePreparation()
        }
        return try {sendPrepared(token,conversation,text)} finally {finishPreparation(token)}
    }
    // Keep source compatibility for worker callers, never turn a legacy UI call into a
    // blocking wait on summary/network I/O. Main must migrate to the owner-aware sendText API.
    private fun rejectSynchronousMainSend(): Boolean {
        if(Looper.myLooper()!=Looper.getMainLooper()) return false
        synchronized(this) {
            if(!idleAvailable()) return true
            problem="同步发送不能在主线程准备上下文；请使用异步发送入口，草稿保留"
        }
        notifyChanged()
        return true
    }
    /** Queue admission only. Completion is delivered on main, even if close wins after commit.
     * Compare ALL owner fields before touching the editor. No callback clears a draft itself.
     * The host must persist text before admission and freeze that revision until completion. */
    internal fun sendText(conversation: Conversation?, editorGeneration: Long, draftRevision: Long,
        text: String, ticket: TextSendTicket = TextSendTicket(), complete: (TextSendResult) -> Unit): Boolean {
        val owner = TextSendOwner(conversation?.id, editorGeneration, draftRevision)
        val token = synchronized(this) {
            if(pendingTextState != null || !idleAvailable() || text.isBlank() || editorGeneration < 0 || draftRevision < 0) return false
            reservePreparation(ticket).also { pendingTextState = TextSendState(ticket, owner, text) }
        }
        try { executor.execute {
            val saved = try { sendPrepared(token,conversation,text,expectedDraftText=text) }
                catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(owner,saved)
                token.persistenceUnconfirmed -> TextSendResult.Unknown(owner)
                else -> TextSendResult.Rejected(owner)
            }
            synchronized(this) {
                if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result)
            }
            finishPreparation(token)
            handler.post { try { complete(result) } catch (_: Exception) {} }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            finishPreparation(token); return false
        }
        return true
    }
    /** Cancellation is scoped to the original editor submission, never a later preparation. */
    internal fun cancelTextSend(ticket: TextSendTicket): Boolean {
        val token = synchronized(this) {
            preparation?.takeIf { it.textTicket === ticket }?.also { it.revoked = true }
        } ?: return false
        token.cancellation.cancel()
        token.browser?.cancel()
        return true
    }
    /** Optional entry point. Ordinary send()/sendImages() never grant Shizuku access.
     * Reservation performs no I/O and blocks other sends until rejected or submitted.
     * Parent must discard this preview if it changes the conversation or editor revision.
     */
    @Synchronized internal fun prepareShizukuSend(conversation:Conversation?, text:String,
        command:String, uidInput:String, timeoutInput:String):ControllerShizukuPreparation? {
        if(!idleAvailable()) return null
        val uid=ControllerShizukuPreparation.parseUid(uidInput) ?: return null
        val timeout=ControllerShizukuPreparation.parseTimeout(timeoutInput) ?: return null
        val preview=runCatching {ControllerShizukuPreparation(conversation,text,uid,command,timeout)}.getOrNull()
            ?: return null
        pendingShizukuToken=reservePreparation();pendingShizuku=preview;notifyChanged()
        return preview
    }
    internal fun rejectShizuku(preview:ControllerShizukuPreparation) {
        val (draft, token)=synchronized(this) {
            if(pendingShizuku!==preview) return
            val token=checkNotNull(pendingShizukuToken)
            token.revoked=true
            (!preview.isSubmitted()) to token
        }
        token.cancellation.cancel()
        preview.cancel()
        // A submitted worker, possibly in connect(), retains ownership until its own cleanup.
        if(draft) finishShizukuPreparation(preview,installed=false)
    }
    /** Call only from an explicit confirmation of the immutable preview. Queues one attempt;
     * acceptance is the database receipt in complete, NOT a guarantee of command completion.
     * No Shizuku permission prompt, probe, new call ID or automatic retry occurs here.
     */
    internal fun confirmShizukuSend(preview:ControllerShizukuPreparation,
        complete:(Conversation?)->Unit):Boolean {
        val (token, authorization)=synchronized(this) {
            if(closed || pendingShizuku!==preview || pendingShizukuToken?.revoked!=false ||
                preview.isSubmitted() || preview.isCancelled()) return false
            checkNotNull(pendingShizukuToken) to preview.approve().also {notifyChanged()}
        }
        try {executor.execute {
            val saved=try {
                if(preview.isCancelled()) null else sendPrepared(token,preview.conversation,preview.messageText,
                    shizukuPreparation=preview,shizukuAuthorization=authorization)
            } catch (_:Exception) {
                synchronized(this) {problem="Shizuku 运行准备失败；未自动重发";notifyChanged()}
                null
            }
            val installed=synchronized(this) {resources?.initial?.runId==preview.runId}
            finishShizukuPreparation(preview,installed)
            handler.post { try {complete(saved)} catch (_:Exception) {} }
        }} catch (_:java.util.concurrent.RejectedExecutionException) {
            preview.cancel();finishShizukuPreparation(preview,installed=false);return false
        }
        return true
    }
    private fun finishShizukuPreparation(preview:ControllerShizukuPreparation,installed:Boolean) {
        fun release() = synchronized(this) {
            if(pendingShizuku===preview) {
                val token=checkNotNull(pendingShizukuToken)
                pendingShizuku=null
                pendingShizukuToken=null
                finishPreparation(token)
            }
        }
        if(installed) release() else preview.closeWithResult { result ->
            when(result) {
                app.daiyu.android.shizuku.ShizukuCloseResult.Drained -> release()
                is app.daiyu.android.shizuku.ShizukuCloseResult.Unknown -> synchronized(this) {
                    // Keep preparation and storage pinned. Stop/close must not reopen this run.
                    if(pendingShizuku===preview) {
                        problem="Shizuku 清理结果 UNKNOWN；资源保留，不自动重连或重发，请核实设备状态"
                        notifyChanged()
                    }
                }
            }
        }
    }
    private fun finishPreparation(token: Preparation) {
        val browser = token.browser
        if (browser != null && browser.state != ControllerBrowserPreparation.State.TRANSFERRED) {
            if (!browser.isCancelled) browser.cancel()
            when (browser.pollCleanup()) {
                RunCatalogCloseResult.Drained -> Unit
                is RunCatalogCloseResult.Unknown -> {
                    synchronized(this) {
                        problem = "浏览器准备清理结果 UNKNOWN；保留原资源，不自动重发"
                        notifyChanged()
                    }
                    return
                }
                null -> {
                    if (token.cleanupDeadline == 0L) token.cleanupDeadline = android.os.SystemClock.uptimeMillis() + 30_000
                    if (android.os.SystemClock.uptimeMillis() < token.cleanupDeadline) {
                        handler.postDelayed({ finishPreparation(token) }, 100)
                    } else synchronized(this) {
                        problem = "浏览器准备清理尚未确认；保留原资源，不自动重发"
                        notifyChanged()
                    }
                    return
                }
            }
        }
        synchronized(this) {
            if (preparation !== token) return
            preparation = null
            pendingImage = null
            if (closed) closeStorageWhenIdle() else notifyChanged()
        }
    }

    @Synchronized internal fun browserPreparation(): ControllerBrowserPreparation? = preparation?.browser

    /** Worker-only snapshot. Merely opening approval never creates a browser or calls a model. */
    internal fun prepareBrowserSend(conversation: Conversation?, draft: BrowserDraftSnapshot,
                                   writes: List<String>): ControllerBrowserPreparation? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "browser_prepare_requires_worker" }
        val token = synchronized(this) {
            if (!idleAvailable() || draft.text.isBlank() || draft.owner.conversationId != conversation?.id) return null
            reservePreparation()
        }
        try {
            val id = if (conversation == null) token.assistantId else database.assistantId(conversation.id)
            val profile = assistantStore.read(checkNotNull(id) { "会话未绑定助手" })
                ?.let { it.copy(enabledSkillIds = it.enabledSkillIds.toList()) }
                ?: error("会话助手不存在")
            val memory = if (profile.memoryEnabled) assistantAssets.memory(profile.id).content else ""
            val frozen = object : AssistantStore by assistantStore {
                override fun read(id: String): AssistantProfile? = profile.takeIf { it.id == id }
            }
            val context = AssistantRunContext.capture(frozen, profile.id, memory)
            val config = settings.load().also { it.validate() }
            val browserSettings = app.daiyu.android.browser.BrowserSettingsStore(application).snapshot()
            val run = RunId(UUID.randomUUID().toString())
            val grant = app.daiyu.android.browser.session.BrowserGrant(
                app.daiyu.android.browser.session.BrowserOwner(run.value,
                    app.daiyu.android.browser.session.BrowserOwnerKind.MAIN), browserSettings.access)
            val actions = app.daiyu.android.browser.session.BrowserSessionRegistry.readOnlyActions +
                "list_tabs" + writes.map { org.json.JSONObject(it).getString("action") }
            val pending = ControllerBrowserPreparation.prepare(conversation, draft, profile,
                ProviderSnapshot.capture(config), browserSettings, grant, actions, writes, runId = run)
            synchronized(this) {
                check(preparation === token && !token.revoked && !closed) { "浏览器准备已取消" }
                token.browser = pending
                token.browserContext = context
            }
            notifyChanged()
            return pending
        } catch (failure: Exception) {
            problem = failure.message ?: "浏览器准备失败；草稿保留"
            finishPreparation(token)
            notifyChanged()
            return null
        }
    }

    internal fun rejectBrowser(pending: ControllerBrowserPreparation) {
        val token = synchronized(this) {
            preparation?.takeIf { it.browser === pending }?.also { it.revoked = true }
        } ?: return
        token.cancellation.cancel()
        pending.cancel()
        if (!token.submitted) finishPreparation(token)
    }

    internal fun sendBrowser(pending: ControllerBrowserPreparation, current: BrowserDraftSnapshot,
                             ticket: TextSendTicket, complete: (TextSendResult) -> Unit): Boolean {
        val owner = TextSendOwner(current.owner.conversationId, current.owner.editorGeneration, current.owner.draftRevision)
        val token = synchronized(this) {
            val candidate = preparation ?: return false
            if (candidate.browser !== pending || candidate.submitted || candidate.revoked || closed ||
                current != pending.draft || pending.state != ControllerBrowserPreparation.State.CONFIRMED ||
                pendingTextState != null) return false
            candidate.submitted = true
            candidate.textTicket = ticket
            pendingTextState = TextSendState(ticket, owner, current.text)
            candidate
        }
        try { executor.execute {
            val saved = try {
                val binding = pending.openOnWorker(application, File(application.filesDir, "browser-runs").toPath())
                if (binding == null) null else sendPrepared(token, pending.conversation, pending.text,
                    expectedDraftText = pending.text, browserPreparation = pending, browserBinding = binding)
            } catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(owner, saved)
                token.persistenceUnconfirmed || pending.state == ControllerBrowserPreparation.State.UNKNOWN -> TextSendResult.Unknown(owner)
                else -> TextSendResult.Rejected(owner)
            }
            synchronized(this) {
                if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result)
            }
            finishPreparation(token)
            handler.post { try { complete(result) } catch (_: Exception) {} }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            rejectBrowser(pending)
            finishPreparation(token)
            return false
        }
        return true
    }
    /** Queue acceptance, NOT a receipt. complete always carries the exact owner + revision.
     * The host must retain/freeze that editor revision until completion and remove ONLY the
     * Accepted.images after comparing owner/revision. No callback here deletes a draft.
     * provider must be an explicit host capability snapshot matching the saved configuration.
     * expectedDraftText is the exact persisted composer/conversation text at this revision;
     * it is compared atomically before accepting, never inferred from image labels or URIs.
     * Null means busy/closed/invalid input, no task and no callback. Cancellation is via handle.
     */
    internal fun sendImages(conversation:Conversation?, owner:ImageAttachmentOwner, draftRevision:Long,
        parts:List<ImageSendPart>, provider:ProviderSnapshot, expectedDraftText:String,
        complete:(ImageSendResult)->Unit):ImageSendHandle? {
        val snapshot=parts.toList()
        val (token, handle)=synchronized(this) {
            if(!idleAvailable() || draftRevision<0 || snapshot.none {it is ImageSendPart.Image} ||
                conversation!=null && conversation.id.value!=owner.conversationId) return null
            val token=reservePreparation()
            val handle=ImageSendHandle(owner,draftRevision) { token.cancellation.cancel() }
            pendingImage=handle
            token to handle
        }
        try { executor.execute {
            var attempt:ControllerImageAttempt?=null
            val result=try {
                attempt=ControllerImageAttempt(owner,draftRevision,snapshot,provider,handle,imageJournal,expectedDraftText)
                val replay=try {attempt.reserve(database)} catch (_:Exception) {attempt.unknown()}
                if(replay!=null) replay else {
                    sendPrepared(token,conversation,"",imageAttempt=attempt)
                    attempt.result ?: attempt.rejected("发送准备被拒绝；草稿保留，未发起请求")
                }
            } catch (_:Exception) {
                attempt?.result ?: attempt?.rejected("图片发送失败；草稿保留，未自动重发")
                    ?: ImageSendResult.Rejected(owner,draftRevision,"图片身份或存储无效；草稿保留")
            }
            finishPreparation(token)
            // A receipt belongs to the submitted revision, not whichever editor is visible now.
            handler.post {try {complete(result)} catch (_:Exception) {}}
        } } catch (_:java.util.concurrent.RejectedExecutionException) {
            finishPreparation(token);return null
        }
        return handle
    }
    private fun sendPrepared(token:Preparation, conversation:Conversation?,text:String,
        withChildren:Boolean = false, imageAttempt:ControllerImageAttempt?=null,
        shizukuPreparation:ControllerShizukuPreparation?=null,
        shizukuAuthorization:app.daiyu.android.domain.tools.TaskAuthorization?=null,
        expectedDraftText:String?=null,
        browserPreparation:ControllerBrowserPreparation?=null,
        browserBinding:CatalogBrowserBinding?=null):Conversation? {
        require(shizukuPreparation==null || (imageAttempt==null && shizukuAuthorization!=null))
        if(preparationCancelled(token)) return null
        // Freeze the persisted draft before any model/history preparation. Legacy send callers
        // may send text different from their persisted draft; CAS still protects that exact draft.
        val frozenDraft = try {
            expectedDraftText ?: imageAttempt?.expectedDraftText ?: shizukuPreparation?.text ?:
                if(conversation==null) database.draft() else database.readableDatabase.rawQuery(
                    "SELECT draft FROM conversations WHERE id=?", arrayOf(conversation.id.value)
                ).use { check(it.moveToFirst()); it.getString(0) }
        } catch (_:Exception) { return null }
        val assistantContext = token.browserContext ?: try {
            val binding=if(conversation==null) token.assistantId
                else database.assistantId(conversation.id)
            if(binding==null) throw MissingAssistantBindingException()
            // Freeze the profile before any memory/history I/O. capture consumes this exact
            // value once; subsequent edits/deletion/global switching cannot change this run.
            val profile=assistantStore.read(binding)?.let {it.copy(enabledSkillIds=it.enabledSkillIds.toList())}
                ?: throw AssistantNotFoundException(binding)
            val memory=if(profile.memoryEnabled) assistantAssets.memory(binding).content else ""
            val frozen=object : AssistantStore by assistantStore {
                override fun read(id:String):AssistantProfile? = profile.takeIf {it.id==id}
            }
            AssistantRunContext.capture(frozen,binding,memory)
        } catch (_:MissingAssistantBindingException) {
            problem="会话未绑定助手；请显式选择助手，草稿保留";notifyChanged();return null
        } catch (_:AssistantNotFoundException) {
            problem="会话助手已删除或不存在；请显式重新绑定，草稿保留";notifyChanged();return null
        } catch (_:Exception) {
            problem="助手配置或记忆读取失败；草稿保留，未发起请求";notifyChanged();return null
        }
        val childModels:Map<String,ChildModelDefinition> = if(!withChildren) emptyMap() else try {
            val key=SubAgentConfigKey.Conversation(checkNotNull(conversation).id.value)
            childBindings.freeze(key,childPreferences.snapshot(key)) { provider ->
                val delegate=assistantModel?.invoke(provider,assistantContext) ?: model(provider.asModelSettings())
                messageImages.wrap(delegate,provider)
            }
        } catch (_:Exception) {
            problem="子模型配置未绑定、已变更或不支持；草稿保留，未切换到主模型";notifyChanged();return null
        }
        val interactiveSnapshot = interactiveMode.snapshot()
        val config=try {(browserPreparation?.provider?.asModelSettings() ?: settings.load()).also {it.validate()}} catch (_:CredentialUnavailableException) {
            problem="已保存密钥不可用；请到模型设置重新输入，未发起网络请求";notifyChanged();return null
        } catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return null}
        val provider=try {
            val selected=browserPreparation?.provider ?: imageAttempt?.provider ?: synchronized(this) {imageProvider}
            if(imageAttempt!=null) require(selected?.asModelSettings()==config) {"stale_image_provider"}
            selected?.takeIf {it.asModelSettings()==config} ?: ProviderSnapshot.capture(config)
        } catch (_:Exception) {problem="图片模型配置已变化；草稿保留，未发起网络请求";notifyChanged();return null}
        val conversationId=browserPreparation?.identity?.conversationId ?: shizukuPreparation?.conversationId ?: conversation?.id ?: imageAttempt?.let {ConversationId(it.owner.conversationId)}
            ?: ConversationId(UUID.randomUUID().toString())
        val run=browserPreparation?.identity?.runId ?: shizukuPreparation?.runId ?: imageAttempt?.runId ?: RunId(UUID.randomUUID().toString())
        val user=try {imageAttempt?.prepare(imageAttachments.store,messageImageAssets) ?:
            Message(MessageId(UUID.randomUUID().toString()),conversationId,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        } catch (_:Exception) {problem="图片冻结失败或模型不支持；草稿保留，未发起网络请求";notifyChanged();return null}
        val assistant=Message(browserPreparation?.identity?.assistantMessageId ?: imageAttempt?.assistantId ?: MessageId(UUID.randomUUID().toString()),conversationId,run,"assistant",emptyList(),RunStatus.RUNNING)
        val preparedContext = try {
            // All sends, including the very first message, share the same frozen policy/budget.
            val coordinator = contextPreparation
            coordinator.prepare(coordinator.freeze(conversationId,listOf(user),token.cancellation))
        } catch (failure: Exception) {
            problem = when (failure) {
                is java.util.concurrent.CancellationException ->
                    "上下文准备已取消；草稿保留，未启动聊天模型请求"
                is app.daiyu.android.context.ContextCapacityExceededException ->
                    "上下文超出发送预算；草稿保留，未启动聊天模型请求"
                is app.daiyu.android.context.SummaryProfileUnavailableException ->
                    "独立摘要配置不可用（${failure.reason.name}）；请重新保存，草稿保留，未回退聊天模型"
                else -> "上下文或摘要准备失败；草稿保留，未启动聊天模型请求"
            }
            notifyChanged(); return null
        }
        if(preparationCancelled(token)) return null
        val preparedModel=try {
            // Preflight the complete expanded history BEFORE DB acceptance or any model open.
            // Later opens re-authorize against persisted rows; candidate authorization ends here.
            val imageHistory=(contextService.displayHistory(conversationId) + preparedContext.messages)
                .distinctBy { it.id }
            val plan=messageImages.freeze(imageHistory,provider,user) {
                preparationCancelled(token) || imageAttempt?.handle?.isCancelled()==true || shizukuPreparation?.isCancelled()==true
            }
            val delegate=assistantModel?.invoke(provider,assistantContext)
                ?: if(plan.hasImages) imageModel(provider) else model(config)
            messageImages.wrap(delegate,provider,conversationId)
        } catch (_:Exception) {
            problem="图片历史读取、能力检查或模型初始化失败；草稿保留，未启动聊天模型请求";notifyChanged();return null
        }
        if(preparationCancelled(token)) return null
        var preparingTools: RunCatalogTools? = null
        var preparingDevices: RunDeviceResources? = null
        var preparingChildren: RunChildModels? = null
        val prepared = try {
            val contextTools = ScopedContextTools.create(
                ContextRegistry(contextService), ContextRegistryScope(conversationId),
                run, preparedContext.archiveId)
            val children = if (childModels.isEmpty()) null else RunChildModels(
                ChildModelBatch(childModels, store = checkNotNull(runtimeStore).childTasks,
                    usageRecorder = checkNotNull(runtimeStore).childUsage), childModels.keys)
            preparingChildren = children
            val devices = RunDeviceResources(application)
            preparingDevices = devices
            val registry = AndroidToolRegistry.create(devices, contextTools, enableUserBarrier=interactiveSnapshot.enabled)
            val capabilities = SessionCapabilities(run.value,
                AndroidToolRegistry.capabilitiesFor(config.enabledTools()) + contextTools.map { it.definition.capability } +
                    setOf(SkillRunSession.READ, SkillRunSession.RESOURCE) +
                    (if (interactiveSnapshot.enabled) setOf(AndroidToolRegistry.ASK_USER_CAPABILITY) else emptySet()) +
                    (if (children == null) emptySet() else setOf(RunChildModels.CAPABILITY,RunChildTasks.CAPABILITY)) +
                    (if (shizukuPreparation == null) emptySet() else setOf(app.daiyu.android.shizuku.RunShizukuCommands.CAPABILITY)) +
                    (if (browserBinding == null) emptySet() else setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)))
            val backgroundFactory: ((app.daiyu.android.domain.runtime.RunExecutionOwner, SessionCapabilities,
                app.daiyu.android.domain.tools.TaskAuthorization) -> RunChildTasks)? =
                if(childModels.isEmpty()) null else { owner, grants, authorization ->
                    synchronized(this) {
                        check(!closed)
                        val supervisor=checkNotNull(childSupervisor)
                        val binding=supervisor.bind(owner,grants,authorization,childModels,::backgroundTasksChanged)
                        RunChildTasks(supervisor,binding,childModels.keys).also { backgroundRuns[run]=it }
                    }
                }
            // Only the consumed, controller-owned approval may open the real Android transport.
            // The SAME frozen authorization reaches Dispatcher and RunShizukuCommands.bind.
            val shizuku=shizukuPreparation?.open(shizukuAccess)
            val tools = RunCatalogTools(registry, capabilities, toolLedger, conversationId, run, devices, children,
                backgroundFactory, authorization=shizukuAuthorization ?: browserBinding?.let {
                    app.daiyu.android.domain.tools.TaskAuthorization(capabilities.sessionId, run.value, it.requiredWrites)
                }, shizuku=shizuku, browser=browserBinding) { grants, authorization ->
                SkillToolRegistry(application).captureForRun(grants, authorization,
                    selection = assistantContext.enabledSkillIds.toSet())
            }
            preparingTools = tools
            val owned = ChatRunner(preparedModel, database, tools,
                RunnerOptions(runtimeStore=checkNotNull(runtimeStore), receiptBatchExecutor=tools,
                    onReasoning={ event -> publishReasoning(assistant,event) })) {
                synchronized(this) {
                    if(!closed && active?.id==assistant.id && active?.runId==run && active?.conversationId==conversationId &&
                        it.id==assistant.id && it.runId==run && it.conversationId==conversationId) {active=it;notifyChanged()}
                }
            }
            owned to tools
        } catch (_: Exception) {
            if(preparingTools!=null) closeUnacceptedTools(preparingTools)
            else {
                preparingDevices?.close()
                preparingChildren?.close()
            }
            problem="运行准备失败；草稿保留，未启动聊天模型请求";notifyChanged();return null
        }
        val (owned, tools) = prepared
        // Only the in-memory admission gate is under the controller monitor. In particular,
        // beginCommit must never encompass SQLite or provider/model I/O.
        val mayCommit=synchronized(this) {
            if(preparation !== token || closed || token.revoked || token.committing ||
                token.cancellation.isCancelled || shizukuPreparation?.isCancelled()==true ||
                browserPreparation?.isCancelled==true || (imageAttempt!=null && !imageAttempt.beginCommit())) false
            else { token.committing=true; true }
        }
        if (!mayCommit) { closeUnacceptedTools(tools); return null }
        var databaseSendEntered=false
        val saved=try {
            val commit: () -> Conversation? = {
                try { DatabaseTransactions { database.writableDatabase }.run { db ->
                val ownerExists=db.rawQuery("SELECT 1 FROM conversations WHERE id=?",
                    arrayOf(conversationId.value)).use {it.moveToFirst()}
                check(ownerExists == (conversation != null)) { "send_owner_changed" }
                val sameDraft=if(conversation==null) db.rawQuery(
                    "SELECT 1 FROM composer WHERE id=1 AND draft=?", arrayOf(frozenDraft)
                ).use {it.moveToFirst()} else db.rawQuery(
                    "SELECT 1 FROM conversations WHERE id=? AND draft=?",
                    arrayOf(conversationId.value,frozenDraft)
                ).use {it.moveToFirst()}
                check(sameDraft) { "send_draft_changed" }
                check(contextService.matchesHistoryRevision(db,conversationId,preparedContext.expectedHistoryRevision)) {
                    "send_history_changed"
                }
                databaseSendEntered=true
                    database.send(user,assistant,conversation==null,assistantContext.assistantId)
                } } catch (failure: Exception) {
                    // A failed pre-write CAS is definitely rejected, not an ambiguous commit.
                    if (browserPreparation != null && !databaseSendEntered) null else throw failure
                }
            }
            if (browserPreparation == null) checkNotNull(commit())
            else browserPreparation.accept(checkNotNull(browserBinding), commit)?.conversation
                ?: throw IllegalStateException("browser_acceptance_unconfirmed")
        } catch (_:Exception) {
            token.persistenceUnconfirmed=databaseSendEntered
            if(databaseSendEntered) imageAttempt?.unknown() else imageAttempt?.draftChanged()
            closeUnacceptedTools(tools)
            problem=if(!databaseSendEntered) "会话、原文或编辑草稿已变化；保留当前草稿，未启动聊天模型请求"
                else "发送保存结果未确认；保留草稿和图片，不自动重发"
            notifyChanged();return null
        }
        // database.send returns only after its real two-row transaction receipt succeeds.
        imageAttempt?.accepted(saved)
        val lifetime=RunResources(assistant,owned,tools)
        val installed=synchronized(this) {
            if(closed || token.revoked || token.cancellation.isCancelled || shizukuPreparation?.isCancelled()==true ||
                browserPreparation?.isCancelled==true) false else {
                if(imageAttempt!=null) imageProvider=provider
                problem=null;active=assistant
                runner=owned;runTools=tools;resources=lifetime
                enqueueSlice(lifetime,allowStopped=true) {
                    if(synchronized(this) {closed || token.revoked || lifetime.fenced}) {
                        val interrupted=assistant.copy(status=RunStatus.INTERRUPTED)
                        database.save(interrupted)
                        RunSlice.Completed(interrupted)
                    } else owned.startSlice(assistant,preparedContext.messages)
                }
                notifyChanged();true
            }
        }
        if(!installed) {
            // close won AFTER acceptance: receipt still wins, but no provider request is opened.
            closeUnacceptedTools(tools)
            try {database.save(assistant.copy(status=RunStatus.INTERRUPTED))} catch (_:Exception) { }
        }
        return saved
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
                            fenceRun(lifetime)
                            handler.post {lifetime.runner.stop()}
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
        fenceRun(lifetime)
        handler.post {
            lifetime.runner.stop()
            lifetime.tools.revokeChildren()
            lifetime.tools.close()
            synchronized(this) {cleanupRun(lifetime)}
        }
    }
    private fun cleanupRun(lifetime:RunResources) {
        pruneBackgroundRuns()
        if(resources!==lifetime) return
        fenceRun(lifetime)
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
            // Closing tools can reach a model/device transport. Fence under the monitor,
            // but invoke close and wait for its drain callback outside the monitor.
            handler.post {
                lifetime.tools.closeWithResult { result ->
                    handler.post {synchronized(this) {
                        if(resources===lifetime) {
                            lifetime.deviceCleanup.observe(result)
                            if(lifetime.deviceCleanup.drained) cleanupRun(lifetime)
                            else {
                                problem="运行清理结果 UNKNOWN；原资源保留，不自动重连或重发，请核实设备状态"
                                if(!closed) active=(active ?: lifetime.initial).copy(
                                    status=RunStatus.INTERRUPTED,error=problem)
                                notifyChanged()
                            }
                        }
                    }}
                }
            }
        }
        if(!lifetime.deviceCleanup.drained) return
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
        pruneBackgroundRuns()
        if(resources!=null || preparing || unacceptedTools.isNotEmpty() || imageReaders!=0 || closingStorage) return
        if(!childrenClosed) {
            if(!childrenClosing) {
                childrenClosing=true
                // Resolve after the initialization task: close may race with runtime recovery.
                // This also keeps provider cancellation outside the Controller monitor.
                executor.execute {
                    val supervisor=childSupervisor
                    val drained={ handler.post {synchronized(this) {
                        childrenClosed=true
                        closeStorageWhenIdle()
                    }}; Unit }
                    if(supervisor==null) drained() else supervisor.closeWhenIdle(drained)
                }
            }
            return
        }
        // Even close acknowledgement must not replace the per-binding release boundary.
        if(backgroundRuns.isNotEmpty()) return
        closingStorage=true
        executor.execute {
            try {questionStore?.close();database.close();runtimeStore?.close();runtimeStore=null}
            finally {executor.shutdown();closeDone.countDown()}
        }
    }
    internal fun skillManagement():SkillManagement = SkillManagement.create {SkillToolRegistry(application)}
    private val neutralBackup by lazy { NeutralBackupService(application, database, app.daiyu.android.backup.MessageImageBackupAssets(messageImageAssets)) }
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
    @Synchronized internal fun restoreImportedConfiguration(preview:ConfigurationRestorePreview,authorized:Boolean,
        completed:(Boolean)->Unit = {}) {
        val current=configurationRestorePreview
        configurationRestorePreview=null
        checkConfigurationRestoreAvailable()
        require(authorized && current===preview) {"恢复预览已失效；请重新预览并确认"}
        dataTask(failureNotice="配置未恢复；请重新预览并确认",completed=completed,work={
            check(neutralBackup.importedConfiguration().toString()==preview.metadata) {"备份配置已变化；请重新预览"}
            settings.applyNonSecretRestore(preview.plan,authorized=true)
            "配置已恢复"
        })
    }
    internal fun saveModelSettings(value:ModelSettings,protocolChangeAuthorized:Boolean,
        requestOptionsReplacementAuthorized:Boolean,completed:(Boolean)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        dataTask(failureNotice="模型设置未保存",completed=completed,work={
            settings.save(value,protocolChangeAuthorized,requestOptionsReplacementAuthorized)
            "模型设置已保存"
        })
    }
    internal fun selectConfiguredModel(expected:ModelSettings,model:String,completed:(Boolean)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        dataTask(failureNotice="模型切换未保存",completed=completed,work={
            settings.selectModel(expected,model)
            "模型已切换"
        })
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
    @Synchronized internal fun dataTask(importing:Boolean=false,failureNotice:String?=null,
        completed:(Boolean)->Unit = {},work:()->String) {
        check(idleAvailable() && !hasLiveChildTasks()) { "请等待后台子任务实际结束" }
        dataBusy=true;notifyChanged()
        try {executor.execute {
            var value=failureNotice ?: if(importing) "导入失败；原会话保留，格式、校验或身份冲突" else "导出失败；目标文件可能不完整，原会话保留"
            var success=false
            try {value=work();success=true} catch(failure:Exception) {
                value=failureNotice ?: when(failure) {
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
                    if(committed) {
                        dispatchChanged()
                        try { completed(success) } catch (_:Exception) { /* Persistence has already finished. */ }
                    }
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
                ?.also {fenceRun(it)}
        } ?: return false
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
        return true
    }
    fun stop() {
        cancelPreparation()
        synchronized(this) {pendingImage}?.cancel()
        synchronized(this) {pendingShizuku}?.let(::rejectShizuku)
        val lifetime=synchronized(this) {resources?.also {fenceRun(it)}} ?: return
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
    }
    internal fun close() {
        val lifetime=synchronized(this) {
            if(!closed) {
                closed=true;ready=false;dataBusy=false;active=null
                preparation?.revoked=true
                imageProvider=null
                configurationRestorePreview=null;dataNotice=null;listeners.clear();reasoningListeners.clear();childBindings.clear()
            }
            resources?.also {fenceRun(it)}.also {runRevocationListeners.clear()}
        }
        cancelPreparation()
        synchronized(this) {pendingImage}?.cancel()
        synchronized(this) {pendingShizuku}?.let(::rejectShizuku)
        lifetime?.runner?.stop();lifetime?.tools?.close()
        handler.post {synchronized(this) {
            if(lifetime!=null && resources===lifetime) cleanupRun(lifetime) else closeStorageWhenIdle()
        }}
        // The control loop must remain available for stop acknowledgments and continuation drain.
        if(Looper.myLooper()!=Looper.getMainLooper()) check(closeDone.await(10,TimeUnit.SECONDS))
    }
}
