package app.daiyu.android

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import app.daiyu.android.assistants.*
import app.daiyu.android.model.AssistantModelFactory
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextManagementOwner
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.context.ControlledContextManagementAdapter
import app.daiyu.android.context.ContextManagementAdmission
import app.daiyu.android.context.ContextManagementLease
import app.daiyu.android.context.ContextManagementBackend
import app.daiyu.android.context.ContextManagementSnapshot
import app.daiyu.android.context.ContextManagementWorker
import app.daiyu.android.context.ContextPreparationCoordinator
import app.daiyu.android.context.ContextPreparationCancellation
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.context.ProductionContextPreparation
import app.daiyu.android.context.ContextRegistry
import app.daiyu.android.context.ContextRegistryScope
import app.daiyu.android.integration.tools.*
import app.daiyu.android.tasksurface.*
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildTaskCommand
import app.daiyu.android.domain.delegation.ChildTaskCommandReceipt
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
import app.daiyu.android.model.generation.*
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.AndroidImageAttachments
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.MessageImageAssetStore
import java.io.File
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.Executors

class ChatApplication:Application() {
    internal var engineeringUiForTests: Boolean = false
    companion object { internal const val OPEN_PET_SETTINGS = "app.daiyu.android.OPEN_PET_SETTINGS" }
    // P05 process singleton. Deferred factory runs on IO; no credentials borrowed from controller.
    internal var balanceHost = app.daiyu.android.balance.BalanceRuntimeHost(factory = {
        app.daiyu.android.balance.BalanceRuntime(app.daiyu.android.balance.ProviderBalanceStore(
            app.daiyu.android.balance.EncryptedBalancePersistence(applicationContext)))
    })
    private val terminalProviderDelegate = lazy { app.daiyu.android.terminal.TerminalApplicationProvider(this) }
    internal val terminalProvider by terminalProviderDelegate
    internal val terminalOwner get() = checkNotNull(
        (terminalProvider.state.value as? app.daiyu.android.terminal.TerminalApplicationState.Ready)?.application
    ) { "terminal_catalog_not_ready" }.terminalOwner
    internal val notificationAccess by lazy {
        app.daiyu.android.platform.CrossAppNotifications(
            { app.daiyu.android.platform.CrossAppNotifications.systemGranted(this) },
            app.daiyu.android.platform.NotificationHistoryStore(controller.database))
    }
    val shizuku by lazy { app.daiyu.android.shizuku.ShizukuAuthorization(this) }
    lateinit var controller:ChatController
    lateinit var pets: app.daiyu.android.pet.PetApplicationHost
        private set
    internal var petSettingsNavigator: (() -> Unit)? = null
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
        pets = app.daiyu.android.pet.PetApplicationHost(this,
            app.daiyu.android.pet.PetModelResolver { selection ->
                controller.independentModels.resolvePet(selection, controller.assistantStore)
            }, onOpenSettings = {
                val navigate = petSettingsNavigator
                if (navigate != null) navigate() else startActivity(android.content.Intent(this, MainActivity::class.java)
                    .setAction(OPEN_PET_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            })
        pets.connectAssistantTransitions(controller.petTransitions)
        controller.independentModels.onChanging = {
            pets.onModelConfigurationChanged() // Synchronous worker-safe fence; UI sync is posted by the host.
            controller.independentConfigurationChanging()
        }
        terminalProvider
    }
    override fun onTerminate() {
        // Android normally kills the process without this callback; useful for hosted runtimes.
        balanceHost.close()
        if (terminalProviderDelegate.isInitialized()) terminalProvider.close()
        pets.close() // Hosted-runtime cleanup; an Activity never closes the application host.
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
        ProductionContextPreparation::create,
    private val virtualDisplayFactory: ControllerVirtualDisplayFactory = ControllerVirtualDisplayFactory.REAL,
    // Optional so existing injected one/two-argument factories remain genuine test seams.
    private val sessionModel: ((ProviderSnapshot, AssistantRunContext, SessionGatewayContext)->ModelPort)? = null,
    private val titleModelFactory: (ProviderSnapshot) -> ModelPort = {
        ModelFactory.create(app.daiyu.android.title.TitleModelCoordinator.requestSnapshot(it),
            app.daiyu.android.title.ConversationTitleModel.INSTRUCTIONS)
    }, private val titleSettingsOverride: app.daiyu.android.title.TitleModelSettingsStore? = null,
    private val contextManagementFactory: (android.content.Context, ContextService) -> ContextManagementBackend =
        { context, service -> ContextManagementWorker.production(context, service) },
    private val platformActions: app.daiyu.android.platform.actions.PlatformActionBackend =
        app.daiyu.android.platform.actions.AndroidPlatformActionBackend(application),
    private val mcpTransport: (app.daiyu.android.mcp.McpServer) -> app.daiyu.android.mcp.McpHttpSession = {
        app.daiyu.android.mcp.McpHttpSession(it)
    }, private val taskSurfaceStore: TaskSurfaceStore = AndroidTaskSurfaceStore.get(application),
    internal var platformSettings: PlatformToolSettings = PlatformToolSettings(application.getSharedPreferences("platform-read-settings", 0)),
    internal var browserSettings: app.daiyu.android.browser.BrowserSettingsStore =
        app.daiyu.android.browser.BrowserSettingsStore(application.getSharedPreferences("browser-settings", 0)),
    internal val diagnostics: app.daiyu.android.diagnostics.DiagnosticLogStore? = null) {
    // Voice credentials remain independent from chat settings.
    internal val voiceAccounts by lazy { app.daiyu.android.voice.account.VoiceAccountResources.forContext(application) }
    internal val voiceRuntime by lazy {
        ControllerVoiceRuntime(application) { config ->
            voiceAccounts.voices.selected(config.voice, config.speech())
        }
    }
    // The Activity editor and run preparation share this exact independent store instance.
    internal val reconnectSettings = app.daiyu.android.model.reconnect.ReconnectSettingsStore(application)
    internal val reasoningPreference = app.daiyu.android.ui.model.ConversationReasoningPreference(preferences)
    internal val titleSettings by lazy { titleSettingsOverride ?: app.daiyu.android.title.TitleModelSettingsStore(application) }
    private val titleCoordinatorDelegate = lazy {
        app.daiyu.android.title.TitleModelCoordinator(titleSettings,
            app.daiyu.android.title.SqliteTitleCommitAdapter({ database.writableDatabase },
                object : app.daiyu.android.title.ParentTitleUsagePort {
                    override fun beginRequest(db: android.database.sqlite.SQLiteDatabase, receipt: app.daiyu.android.title.ParentTitleUsage) {
                        check(db === database.writableDatabase && db.inTransaction())
                        app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.beginTitle(db, receipt)
                    }
                    override fun observeRequest(db: android.database.sqlite.SQLiteDatabase, receipt: app.daiyu.android.title.ParentTitleUsage) {
                        check(db === database.writableDatabase && db.inTransaction())
                        app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.observeTitle(db, receipt)
                    }
                    override fun recordAfterTitleSaved(db: android.database.sqlite.SQLiteDatabase, receipt: app.daiyu.android.title.ParentTitleUsage) {
                        check(db === database.writableDatabase && db.inTransaction())
                        app.daiyu.android.model.usage.SQLiteAdditionalUsageStore.finishTitle(db, receipt)
                    }
                }), titleModelFactory)
    }
    private val titleCoordinator by titleCoordinatorDelegate
    private val titleExecutor = Executors.newSingleThreadExecutor()
    private var titleReaders = 0
    internal var titleNotice: String? = null
        private set
    internal var titleRevision = 0L
        private set
    @Synchronized internal fun consumeTitleNotice(): String? = titleNotice.also { titleNotice = null }

    /** Called from the Runner's post-persist publisher, not from a UI/cleanup replay. */
    private fun afterTitleParentPersisted(message: Message, user: Message, expectedAssistant: Message,
        pending: java.util.concurrent.atomic.AtomicReference<app.daiyu.android.title.TitleJob?>) {
        if (message.id != expectedAssistant.id || message.role != Message.ASSISTANT_ROLE || message.runId != user.runId ||
            message.conversationId != user.conversationId || message.status == RunStatus.RUNNING) return
        val job = pending.getAndSet(null) ?: return
        val admitted = synchronized(this) {
            if (closed || message.status != RunStatus.COMPLETED) false else { titleReaders++; true }
        }
        if (!admitted) { titleCoordinator.revoke(job); return }
        try { titleExecutor.execute {
            val result = try {
                titleCoordinator.generate(job, listOf(user, message))
            } catch (_: Exception) {
                // A failed usage/storage receipt is visible independently of the accepted chat.
                null
            }
            handler.post { synchronized(this) {
                titleReaders--
                if (result == app.daiyu.android.title.TitleOutcome.SAVED) titleRevision++
                titleNotice = when (result) {
                    app.daiyu.android.title.TitleOutcome.SAVED -> "会话标题已保存"
                    null -> "标题或用量保存未确认；聊天结果不受影响，未自动重试"
                    else -> "标题未保存（${result.name}）；聊天结果不受影响"
                }
                if (closed) closeStorageWhenIdle() else notifyChanged()
            } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            titleCoordinator.revoke(job)
            synchronized(this) { titleReaders--; if (closed) closeStorageWhenIdle() }
        }
    }

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
        data class Accepted(override val owner: TextSendOwner, val conversation: Conversation,
            val assistant: Message? = null): TextSendResult
        data class Rejected(override val owner: TextSendOwner): TextSendResult
        /** Persistence did not return a confirmed receipt; retain the draft and do not auto-retry. */
        data class Unknown(override val owner: TextSendOwner): TextSendResult
    }
    constructor(application:Application):this(application,ConversationDatabase(application),ModelSettingsStore(application),application.getSharedPreferences("conversation-ui",0),{ModelFactory.create(it)},
        assistantModel=AssistantModelFactory::create, sessionModel=AssistantModelFactory::create,
        platformSettings=PlatformToolSettings(application),
        browserSettings=app.daiyu.android.browser.BrowserSettingsStore(application),
        diagnostics=app.daiyu.android.diagnostics.DiagnosticLogStore(java.io.File(application.noBackupFilesDir, "diagnostics-v1/state.json")))
    val assistantStore: SqliteAssistantStore by lazy { SqliteAssistantStore(database) }
    internal val independentModels = IndependentModelSettings(application).also {
        it.onChanging = { independentConfigurationChanging() }
    }
    internal val petTransitions = CommittedPetTransitions()
    internal data class ImageRoute(val actual: ProviderSnapshot, val auxiliaryGeneration: Long?)
    /** Worker-only: explicit auxiliary enablement is not main-provider image capability. */
    internal fun captureImageRoute(main: ProviderSnapshot): ImageRoute {
        if (!independentModels.visionSelection().enabled) return ImageRoute(main, null)
        val generation = independentModels.generation()
        val frozen = independentModels.freeze(IndependentModelSettings.Slot.VISION)
        independentModels.visionSelection().resolve(listOf(independentModels.visionCandidate(frozen)))
        check(independentModels.isCurrent(generation))
        return ImageRoute(frozen.provider, generation)
    }
    internal fun independentConfigurationChanging() {
        val lifetime = synchronized(this) { resources?.takeIf { it.auxiliary != null } }
        lifetime?.let { stop(it.initial) }
    }
    internal val interactiveMode = app.daiyu.android.config.InteractiveModePreference(preferences)
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val imageReceiptHandler=Handler(Looper.getMainLooper())
    // Entry points and callback commits share this controller's monitor. Runner control and
    // shutdown waits stay outside it, so a worker can always finish its last publication.
    private val listeners=mutableMapOf<()->Unit,Any>()
    private val reasoningListeners=mutableMapOf<(Message, ModelEvent)->Unit,Any>()
    private val runRevocationListeners=mutableSetOf<(Message)->Unit>()
    private var closed=false
    // The existing preparation lane is retained until the worker (and device cleanup) exits.
    // Identity, not a Boolean reset, prevents an old completion from clearing a newer owner.
    private class Preparation(val assistantId: String?, var textTicket: TextSendTicket? = null) {
        var summaryPreparation: app.daiyu.android.context.RuntimeSummaryPreparation? = null
        var summaryTransferred = false
        var admittedVoiceOwner: Message? = null
        val cancellation = ContextPreparationCancellation()
        var revoked = false
        var committing = false
        var persistenceUnconfirmed = false
        var browser: ControllerBrowserPreparation? = null
        var mcp: ControllerMcpPreparation? = null
        var mcpCatalog: RunCatalogTools? = null
        var terminal: ControllerTerminalPreparation? = null
        var terminalJob: ControllerTerminalJobPreparation? = null
        var terminalJobRun: TerminalJobSession? = null
        var terminalLaunch: TerminalPtyLaunch? = null
        var terminalRun: TerminalConfirmedRun? = null
        var terminalCatalog: RunCatalogTools? = null
        var terminalCleanupStarted = false
        var browserContext: AssistantRunContext? = null
        var submitted = false
        var cleanupDeadline = 0L
        @Volatile var videoRequired = false
        var videoEditor: ControllerVideoEditor? = null
        var videoRouteEpoch = 0L
        var videoReceipt: VideoDraftReceipt? = null
        var video: ControllerVideoSession? = null
    }
    private var preparation: Preparation? = null

    // A visit is a capability, including for A -> away -> A. Never identify it by ID alone.
    private class ContextVisit(val owner: ContextManagementOwner) {
        lateinit var adapter: ContextManagementControllerAdapter
        var token: Preparation? = null
    }
    private var contextVisit: ContextVisit? = null

    internal fun openContextManagement(owner: ContextManagementOwner): ContextManagementControllerAdapter? {
        check(Looper.myLooper() == Looper.getMainLooper())
        return synchronized(this) {
            if (contextVisit != null || !idleAvailable() || hasLiveChildTasks(owner.conversationId)) return null
            val visit = ContextVisit(owner)
            fun current(candidate: ContextManagementOwner): Boolean =
                !closed && contextVisit === visit && visit.owner == candidate &&
                    contextVisit?.adapter === visit.adapter
            val admission = object : ContextManagementAdmission {
                override fun isCurrent(owner: ContextManagementOwner) = synchronized(this@ChatController) {
                    current(owner)
                }
                override fun tryReserve(owner: ContextManagementOwner): ContextManagementLease? =
                    synchronized(this@ChatController) reservation@ {
                        if (!current(owner) || !idleAvailable() || hasLiveChildTasks(owner.conversationId)) return@reservation null
                        val token = reservePreparation()
                        visit.token = token
                        object : ContextManagementLease {
                            override val owner = visit.owner
                            override val cancellation = token.cancellation
                            private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                            override fun checkCurrent() {
                                synchronized(this@ChatController) {
                                    if (!current(this.owner) || preparation !== token || token.revoked ||
                                        preparationCancelled(token)) throw java.util.concurrent.CancellationException("context_visit_revoked")
                                }
                            }
                            override fun finish() {
                                if (!finished.compareAndSet(false, true)) return
                                // Called only by adapter worker finally (or pre-start rejection).
                                // Token identity in finishPreparation cannot release a newer owner.
                                try { finishPreparation(token) } catch (_: Exception) {
                                    // Fail closed if parent cleanup cannot complete; never clear its slot here.
                                } finally {
                                    synchronized(this@ChatController) { if (visit.token === token) visit.token = null }
                                }
                            }
                        }
                    }
            }
            // Construction is memory-only. Even the shared service's first mkdir must wait for
            // an admitted task on the existing executor, never this monitor/UI call.
            val backend = object : ContextManagementBackend {
                private val delegate by lazy {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    contextManagementFactory(application, contextService)
                }
                override fun snapshot(lease: ContextManagementLease): ContextManagementSnapshot {
                    lease.checkCurrent()
                    return delegate.snapshot(lease)
                }
                override fun archive(lease: ContextManagementLease, snapshot: ContextManagementSnapshot, offset: Int) =
                    run { lease.checkCurrent(); delegate.archive(lease, snapshot, offset) }
                override fun compress(lease: ContextManagementLease, expected: ContextManagementSnapshot) =
                    run { lease.checkCurrent(); delegate.compress(lease, expected) }
            }
            visit.adapter = ControlledContextManagementAdapter(owner, admission, backend, executor,
                java.util.concurrent.Executor { command -> handler.post(command); Unit })
            contextVisit = visit
            visit.adapter
        }
    }

    internal fun invalidateContextManagement(expected: ContextManagementControllerAdapter) {
        val token = synchronized(this) {
            val visit = contextVisit?.takeIf { it.adapter === expected } ?: return
            contextVisit = null
            visit.token?.also { it.revoked = true }
        }
        // Transport callbacks and adapter cancellation are deliberately outside the monitor.
        try { token?.cancellation?.cancel() } finally {
            // Main navigation closes synchronously. Hosted stop/close may originate on a worker;
            // keep adapter commands UI-confined, after the synchronous registration/token fence.
            if (Looper.myLooper() == Looper.getMainLooper()) expected.close()
            else handler.post { expected.close() }
        }
        // Do NOT finish here: queued/running worker retains the original Preparation until exit.
    }

    private fun invalidateCurrentContextManagement() {
        val expected = synchronized(this) { contextVisit?.adapter } ?: return
        invalidateContextManagement(expected)
    }
    // Explicit tool-enabled child resources are retained independently of model worker exit.
    // Unknown cleanup remains here and keeps its permit; no text-only batch is upgraded.
    private val browserChildCapacity = java.util.concurrent.Semaphore(1, true)
    private val browserChildren = mutableListOf<ControllerChildBrowserPlan>()
    internal fun browserChildPlans(): List<ControllerChildBrowserPlan> = synchronized(this) { browserChildren.toList() }
    internal fun stopBrowserChildren() {
        val children = synchronized(this) { browserChildren.toList() }
        children.forEach { it.close() }
        browserChildrenChanged()
    }
    private fun browserChildrenChanged() {
        handler.post { synchronized(this) {
            browserChildren.removeAll { it.drained }
            if (closed) closeStorageWhenIdle() else notifyChanged()
        } }
    }
    private val preparing: Boolean get() = preparation != null
    // Call only after the idle gate; selection is already initialized in memory.
    private fun reservePreparation(textTicket: TextSendTicket? = null): Preparation =
        Preparation(assistantStore.selectedId.value, textTicket).also {
        check(preparation == null)
        it.videoRouteEpoch = videoRouteEpoch.get()
        preparation = it
    }
    private fun preparationCancelled(token: Preparation): Boolean = synchronized(this) {
        closed || preparation !== token || token.revoked || token.cancellation.isCancelled
    }
    // Fence under the short monitor; transport cancellation callbacks must run OUTSIDE it.
    private fun cancelPreparation() {
        val token = synchronized(this) {
            guiEntry?.let { it.revoked = true; it.session?.close() }
            virtualDisplayEntry?.let { it.revoked = true; it.session?.close() }
            preparation?.also { it.revoked = true; it.video?.revoke() }
        }
        token?.cancellation?.cancel()
        token?.browser?.cancel()
        token?.mcp?.cancel()
        if (token != null && !token.submitted && (token.browser != null || token.mcp != null || token.terminal != null || token.terminalJob != null ||
            synchronized(this) { virtualDisplayEntry?.submitted == false || guiEntry?.submitted == false })) finishPreparation(token)
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
    // AC037 dedicated platform approval lane. Ordinary send paths never populate it.
    private var pendingPlatformAction: ControllerPlatformActionPreparation? = null
    private var pendingPlatformToken: Preparation? = null
    @Synchronized internal fun platformActionPreparation() = pendingPlatformAction
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
    internal val videoAttachments by lazy {
        app.daiyu.android.media.video.AndroidVideoAttachments(application)
    }
    // One retained draft writer survives Activity recreation; message assets use the existing store.
    internal val videoHost by lazy { VideoChatHost(application.getSharedPreferences("video-drafts", 0), videoAttachments) }
    private val videoJournal by lazy { ControllerVideoJournal(application.getSharedPreferences("video-send-identities", 0)) }
    private val messageVideos by lazy { ControllerMessageVideos(database, videoAttachments.store, messageImages) }
    @Volatile private var videoSession: ControllerVideoSession? = null
    @Volatile private var videoUi: ControllerVideoUi? = null
    internal data class VideoSendState(val request: VideoDraftRequest, val editor: ControllerVideoEditor,
        val receipt: VideoDraftReceipt? = null, val conversation: Conversation? = null)
    @Volatile private var pendingVideoState: VideoSendState? = null
    internal fun videoSendState(): VideoSendState? = pendingVideoState
    @Synchronized internal fun acknowledgeVideoSend(request: VideoDraftRequest) {
        if (pendingVideoState?.request === request && pendingVideoState?.receipt != null) pendingVideoState = null
    }
    private val videoConfigurationEpoch = java.util.concurrent.atomic.AtomicLong()
    private val videoRouteEpoch = java.util.concurrent.atomic.AtomicLong()
    private val videoRunEpoch = java.util.concurrent.atomic.AtomicLong()
    internal fun attachVideoUi(ui: ControllerVideoUi) { invalidateVideo(); videoUi = ui }
    internal fun detachVideoUi(ui: ControllerVideoUi) {
        if (videoUi === ui) { invalidateVideo(); videoUi = null }
    }
    internal fun ownsVideoUi(ui: ControllerVideoUi): Boolean = videoUi === ui
    internal fun invalidateVideo(expectedUi: ControllerVideoUi? = null) {
        if (expectedUi != null && videoUi !== expectedUi) return
        val (token, lifetime) = synchronized(this) {
            videoSession?.revoke() // Close POST permits before publishing a changed route authority.
            videoRouteEpoch.incrementAndGet()
            val token = preparation?.takeIf { it.videoRequired }?.also { it.revoked = true }
            token to resources?.takeIf { it.initial.runId == videoSession?.assistant?.runId }
        }
        token?.cancellation?.cancel()
        // Do not clear the owner: preparation / RunResources retain it until actual worker drain.
        lifetime?.let(::stopRun)
    }
    private fun invalidateVideoConfiguration() {
        invalidateVideo()
        videoConfigurationEpoch.incrementAndGet()
    }
    private var videoReaders = 0
    internal fun recoverVideos(complete: () -> Unit = {}) {
        val pending = videoHost.pendingRecovery()
        if (pending.isEmpty()) { complete(); return }
        synchronized(this) { if (closed) return; videoReaders++ }
        try { executor.execute {
            val receipts = pending.map { videoJournal.recover(it, database) }
            handler.post {
                try { receipts.forEach(videoHost::settle); complete() }
                finally { synchronized(this) { videoReaders--; if (closed) closeStorageWhenIdle() } }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { videoReaders--; if (closed) closeStorageWhenIdle() }
        }
    }
    internal fun sendVideos(conversation: Conversation?, request: VideoDraftRequest,
        editor: ControllerVideoEditor, complete: (VideoDraftReceipt, Conversation?) -> Unit): Boolean {
        val ui = videoUi ?: return false
        if (Looper.myLooper() != Looper.getMainLooper() || ui.editor() != editor || !editor.visible ||
            editor.conversation != request.conversation || editor.text != request.text ||
            conversation?.id?.value != request.conversation) return false
        val token = synchronized(this) {
            if (!idleAvailable() || pendingVideoState != null) return false
            reservePreparation().also {
                it.videoRequired = true; it.videoEditor = editor
                pendingVideoState = VideoSendState(request, editor)
            }
        }
        try { executor.execute {
            var attempt: ControllerVideoAttempt? = null
            val result = try {
                val original = ControllerVideoAttempt(request, videoJournal).also { attempt = it }
                val replay = original.reserve(database) // ORIGINAL identity, before any DB write.
                if (replay != null) replay else {
                    sendPrepared(token, conversation, request.text, expectedDraftText = request.text, videoAttempt = original)
                    token.videoReceipt ?: original.reject()
                }
            } catch (_: Exception) {
                token.videoReceipt ?: attempt?.reject() ?: VideoDraftReceipt(request.conversation, request.owner, request.revision,
                    request.videos, VideoDraftOutcome.UNKNOWN)
            }
            synchronized(this) {
                if (pendingVideoState?.request === request) pendingVideoState = pendingVideoState?.copy(
                    receipt = result, conversation = attempt?.acceptedConversation)
            }
            finishPreparation(token)
            handler.post { try { complete(result, attempt?.acceptedConversation) } catch (_: Exception) { } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingVideoState?.request === request) pendingVideoState = null }
            finishPreparation(token); return false
        }
        return true
    }
    private val messageImageAssets by lazy { MessageImageAssetStore(File(application.filesDir,"media-message-images")) }
    private val messageImages by lazy { ControllerMessageImages(database,messageImageAssets) }
    private val imageJournal by lazy { application.getSharedPreferences("image-send-identities",0) }
    private var imageProvider:ProviderSnapshot?=null
    private var messageActionReaders = 0
    private var imageReaders=0

    // One fixed application DB/account, not a resolver of whichever account is active later.
    @Volatile private var generationScope: String? = null
    private var generationResources: ControllerGenerationResources? = null
    // Deletion also works before any generation settings/resource exists. These in-memory
    // seals are inherited by the first (and only) resource, never cleared by a later import.
    private val generationConversationPublicationSeals = mutableSetOf<String>()
    private val generationRunPublicationSeals = mutableSetOf<Pair<String, String>>()
    // The only Resources constructor in the parent. Never reset this on stop, import, deletion
    // or reader close: its permanent owner gates belong to this actual account/DB lifetime.
    @Synchronized private fun retainGenerationResources(accountScope: String): ControllerGenerationResources {
        check(generationScope == null || generationScope == accountScope) { "generation_account_changed" }
        generationScope = accountScope
        return generationResources ?: ControllerGenerationResources(accountScope,
            { database.writableDatabase }, { generationScope },
            File(application.filesDir, "media-message-generation"),
            downloadTransport = GenerationPublicNetworkHttp()).also { retained ->
            generationResources = retained
            // No existing gates/publications exist in a newly constructed resource, so these
            // seals need no wait. Every future fence inherits them before it can be returned.
            generationConversationPublicationSeals.forEach { retained.sealConversationPublicationAdmission(it) }
            generationRunPublicationSeals.forEach { (conversation, run) -> retained.sealRunPublicationAdmission(conversation, run) }
        }
    }
    private val generationCleanup = ControllerGenerationCleanupQueue(workers = 2, capacity = 8)
    private var generationEntry: GenerationEntry? = null
    private var generationPreparation: Preparation? = null
    private var generationReaders = 0

    internal class GenerationEntry internal constructor(
        val conversation: Conversation?, val editorGeneration: Long,
        val initialEditor: GenerationEditorSnapshot, val identity: GenerationPreparationIdentity,
        val settings: GenerationSettingsSnapshot,
    ) {
        lateinit var handoff: GenerationConfirmationHandoff
        var session: ControllerGenerationSession? = null
        var submitted = false
        var abandoned = false
        var runner: ChatRunner? = null
        var catalog: RunCatalogTools? = null
        val persistenceDone = CountDownLatch(1)
        val runnerDone = CountDownLatch(1)
    }

    @Synchronized internal fun currentGenerationEntry(): GenerationEntry? = generationEntry
    @Synchronized internal fun generationSubmissionPending(): Boolean = generationEntry?.submitted == true

    /** Worker-only settings load. The reservation spans the form, approval and physical drain. */
    internal fun prepareGeneration(conversation: Conversation?, editorGeneration: Long,
        editor: GenerationEditorSnapshot): GenerationEntry? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val reserved = synchronized(this) {
            if (!idleAvailable() ||
                (conversation != null && conversation.id.value in generationConversationPublicationSeals) ||
                editor.text.isBlank() || editor.attachmentIds.isNotEmpty()) return null
            reservePreparation().also { generationPreparation = it }
        }
        return try {
            val snapshot = GenerationSettingsStore(application).loadSnapshot()
            synchronized(this) {
                check(!preparationCancelled(reserved))
                retainGenerationResources(snapshot.accountScope)
                val identity = GenerationPreparationIdentity(snapshot.accountScope,
                    conversation?.id ?: ConversationId(UUID.randomUUID().toString()),
                    RunId(UUID.randomUUID().toString()), MessageId(UUID.randomUUID().toString()))
                GenerationEntry(conversation, editorGeneration, editor, identity, snapshot).also { entry ->
                    entry.handoff = GenerationConfirmationHandoff { abandonGeneration(entry) }
                    generationEntry = entry
                }
            }
        } catch (_: Exception) {
            synchronized(this) { generationPreparation = null }
            finishPreparation(reserved)
            problem = "独立生成配置不可用或已变化；请先保存生成设置。草稿保留，未生成。"
            notifyChanged(); null
        }
    }

    private fun generationSession(entry: GenerationEntry): ControllerGenerationSession = synchronized(this) {
        entry.session ?: ControllerGenerationSession(checkNotNull(entry.handoff.token),
            parentStop = { stopGenerationParent(entry) }, parentDrain = { timeout ->
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
            fun await(latch: CountDownLatch): Boolean = latch.await(
                (deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (!await(entry.persistenceDone) || !await(entry.runnerDone))
                GenerationToolsCloseResult.Unknown("generation_parent_runner_not_drained")
            else {
                val drained = CountDownLatch(1)
                var result: RunCatalogCloseResult? = null
                val catalog = entry.catalog
                if (catalog == null) GenerationToolsCloseResult.Drained else {
                    catalog.closeWithResult { result = it; drained.countDown() }
                    if (await(drained) && result == RunCatalogCloseResult.Drained)
                        GenerationToolsCloseResult.Drained
                    else GenerationToolsCloseResult.Unknown("generation_parent_catalog_not_drained")
                }
            }
        }, originalEditor = entry.initialEditor).also { entry.session = it }
    }

    private fun stopGenerationParent(entry: GenerationEntry) {
        val lifetime = synchronized(this) {
            resources?.takeIf { it.runner === entry.runner }?.also { fenceRun(it) }
        }
        entry.runner?.stop()
        entry.catalog?.close()
        if (lifetime != null) handler.post { synchronized(this) { cleanupRun(lifetime) } }
    }

    internal fun abandonGeneration(entry: GenerationEntry) {
        val reserved = synchronized(this) {
            if (generationEntry !== entry || entry.submitted || entry.abandoned) return
            entry.abandoned = true
            generationPreparation.also { generationPreparation = null }
        }
        if (entry.handoff.token == null) {
            synchronized(this) { if (generationEntry === entry) generationEntry = null }
        } else {
            val session = generationSession(entry)
            session.recordPersistence(entry.identity, GenerationPersistenceReceipt.Rejected)
            entry.persistenceDone.countDown(); entry.runnerDone.countDown()
            scheduleGenerationCleanup(entry)
        }
        reserved?.let(::finishPreparation)
    }

    /** Invoked ONLY by GenerationApprovalHost's exact paid-confirmation handoff. */
    internal fun sendGeneration(entry: GenerationEntry, current: GenerationEditorSnapshot): Boolean {
        val reserved = synchronized(this) {
            val confirmed = entry.handoff.token ?: return false
            if (generationEntry !== entry || entry.submitted || entry.abandoned || closed ||
                confirmed.preview.identity != entry.identity || !confirmed.preview.editor.matches(current) ||
                entry.initialEditor.text != current.text || entry.initialEditor.attachmentIds.isNotEmpty()) return false
            // These are immutable GENERATION form inputs, not ordinary chat draft attachments.
            // Keep the draft prohibition above and require exact ordered, role-bound input IDs.
            val batches = confirmed.preview.config.batches
            if (batches.any { batch -> batch.items.any {
                    !GenerationFormInputs.supported(batch.protocol, it.references, it.mask)
                } } || current.attachmentIds != batches.flatMap { batch -> batch.items.flatMap {
                    GenerationFormInputs.ids(it.references, it.mask)
                } }) return false
            if (!confirmed.preview.config.batches.all { batch -> entry.settings.profiles.any { profile ->
                    profile.freeze(batch.id, batch.items).fingerprint == batch.fingerprint
                } }) return false
            checkNotNull(generationPreparation).also { entry.submitted = true }
        }
        val session = generationSession(entry)
        try { executor.execute {
            try {
                // Compare saved configuration on the worker before any preparation or model work.
                val latest = GenerationSettingsStore(application).loadSnapshot()
                check(latest.accountScope == entry.settings.accountScope && latest.revision == entry.settings.revision)
                sendPrepared(reserved, entry.conversation, current.text,
                    expectedDraftText = current.text, generation = entry)
            } catch (_: Exception) {
                problem = "生成配置或发送准备已变化；草稿保留，未自动重试"
            } finally {
                if (session.persistenceReceipt == null) session.recordPersistence(entry.identity,
                    if (reserved.persistenceUnconfirmed) GenerationPersistenceReceipt.Unknown
                    else GenerationPersistenceReceipt.Rejected)
                entry.persistenceDone.countDown()
                if (entry.runner == null) entry.runnerDone.countDown()
                synchronized(this) { if (generationPreparation === reserved) generationPreparation = null }
                finishPreparation(reserved)
                if (entry.runner == null) scheduleGenerationCleanup(entry)
                notifyChanged()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            session.recordPersistence(entry.identity, GenerationPersistenceReceipt.Rejected)
            entry.persistenceDone.countDown(); entry.runnerDone.countDown()
            synchronized(this) { generationPreparation = null }
            finishPreparation(reserved); scheduleGenerationCleanup(entry); return false
        }
        return true
    }

    internal data class GenerationReceiptNotice(
        val entry: GenerationEntry,
        val identity: GenerationPreparationIdentity,
        val receipt: GenerationPersistenceReceipt.Accepted,
    )

    @Synchronized internal fun pendingGenerationReceiptNotice(): GenerationReceiptNotice? {
        val entry = generationEntry?.takeIf { it.submitted } ?: return null
        val receipt = entry.session?.pendingAcceptedReceipt ?: return null
        return GenerationReceiptNotice(entry, entry.identity, receipt)
    }

    @Synchronized internal fun acknowledgeGenerationReceipt(notice: GenerationReceiptNotice): Boolean {
        val entry = generationEntry ?: return false
        if (entry !== notice.entry || !entry.submitted || notice.identity != entry.identity ||
            entry.session?.acknowledgeAccepted(notice.identity, notice.receipt) != true) return false
        generationCleanupChanged(entry)
        return true
    }

    /** UI CAS and session receipt consumption share this monitor. Never consumes a newer editor. */
    @Synchronized internal fun consumeGenerationReceipt(entry: GenerationEntry, editorGeneration: Long,
        current: GenerationEditorSnapshot, consume: (Conversation) -> Unit): Boolean {
        if (generationEntry !== entry || editorGeneration != entry.editorGeneration) return false
        val receipt = entry.session?.consumeAcceptedForOriginalEditor(entry.initialEditor, current, entry.identity) ?: return false
        consume(receipt.conversation)
        generationCleanupChanged(entry)
        return true
    }

    private fun scheduleGenerationCleanup(entry: GenerationEntry) {
        val session = entry.session ?: return
        session.revokeAdmission()
        if (!generationCleanup.offer(session) {
            handler.post { synchronized(this) { generationCleanupChanged(entry) } }
        }) {
            // Rejection does not run close on the caller and cannot discard the reservation.
            handler.postDelayed({ scheduleGenerationCleanup(entry) }, 100)
        }
    }

    // The queue callback observes a published result AFTER the real worker exits its marker.
    private fun generationCleanupChanged(entry: GenerationEntry) {
        if (generationEntry !== entry) return
        val session = entry.session ?: return
        if (session.canRetire) {
            generationEntry = null
            if (closed) closeStorageWhenIdle() else notifyChanged()
        } else if (session.closeResult is GenerationToolsCloseResult.Unknown ||
            session.persistenceReceipt === GenerationPersistenceReceipt.Unknown) {
            problem = "生成保存或清理结果 UNKNOWN；原资源和草稿保留，禁止自动重试"
            notifyChanged()
        } else if (session.closeResult == GenerationToolsCloseResult.Drained &&
            session.persistenceReceipt is GenerationPersistenceReceipt.Accepted) {
            problem = "消息已保存，但原编辑器收据尚未匹配；保留草稿和运行槽，不自动重发"
            notifyChanged()
        }
        // Accepted without a matching editor remains retained; reopening history is not a receipt.

    }

    internal data class GenerationAssetDisplay(val metadata: ControllerGenerationOutputMetadata,
        val thumbnail: android.graphics.Bitmap?)
    private val generationOutputReaders = linkedMapOf<String, ControllerGenerationOutputReader>()
    private val generationOutputConversations = mutableMapOf<String, String>()

    /** Same account and owner reuse one read fence. Only the controller worker touches this map. */
    internal fun loadGenerationOutputs(conversation: ConversationId,
        complete: (List<GenerationAssetDisplay>?) -> Unit): Boolean {
        synchronized(this) {
            if (closed || !ready || dataBusy) return false
            generationReaders++
        }
        try { executor.execute {
            val result = runCatching {
                synchronized(this) {
                    check(conversation.value !in generationConversationPublicationSeals)
                }
                val scope = GenerationSettingsStore(application).readAccountScope()
                    ?: return@runCatching emptyList<GenerationAssetDisplay>()
                val retained = retainGenerationResources(scope)
                val displays = mutableListOf<GenerationAssetDisplay>()
                // Materialize scalars and CLOSE the SQL cursor before any owner/file gate read.
                // Side table is authoritative, including registered outputs beside central UNKNOWN.
                val selected = retained.database().rawQuery("SELECT operation_id,item_id,output_index,fingerprint,protocol," +
                    "assistant_id,run_id,tool_call_id,tool_name FROM generation_outputs " +
                    "WHERE account_scope=? AND conversation_id=? ORDER BY rowid DESC LIMIT 32",
                    arrayOf(scope, conversation.value)).use { rows ->
                    val result = mutableListOf<Triple<GenerationAttemptBinding, GenerationAttempt, Int>>()
                    while (rows.moveToNext()) {
                        val binding = GenerationAttemptBinding(conversation.value, rows.getString(5),
                            rows.getString(6), rows.getString(7), rows.getString(8))
                        val attempt = GenerationAttempt(GenerationIdentity(rows.getString(0), rows.getString(1)),
                            rows.getString(3), GenerationProtocol.valueOf(rows.getString(4)))
                        result.add(Triple(binding, attempt, rows.getInt(2)))
                    }
                    result
                }
                selected.forEach { (binding, attempt, index) ->
                    val reader = generationOutputReader(scope, binding)
                    val metadata = reader.metadata(attempt, index) ?: return@forEach
                    val thumbnail = if (metadata.kind == GenerationKind.IMAGE) {
                        // Decode bounded display pixels, not a rewritten/published original.
                        reader.openOriginal(attempt, index)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream, null,
                                android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = Integer.highestOneBit(
                                        (maxOf(metadata.width, metadata.height) / 512).coerceAtLeast(1))
                                })
                        }
                    } else {
                        // Authenticate the real video descriptor too; external file/URL exposure is denied.
                        reader.openOriginal(attempt, index)?.use { it.read() }
                        null
                    }
                    displays.add(GenerationAssetDisplay(metadata, thumbnail))
                }
                displays.toList()
            }.getOrNull()
            handler.post {
                synchronized(this) {
                    generationReaders--
                    if (closed) closeStorageWhenIdle()
                }
                complete(result)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { generationReaders-- }; return false
        }
        return true
    }

    /** Worker-only cache admission shared by history and complete backup. Both indexes are
     * installed together; delete/close can therefore find every backup-created reader too.
     * The monitor protects only the memory seal check, never openOutputs' gate/SQL validation.
     */
    private fun generationOutputReader(scope: String, binding: GenerationAttemptBinding): ControllerGenerationOutputReader {
        check(Looper.myLooper() != Looper.getMainLooper())
        synchronized(this) { check(binding.conversationId !in generationConversationPublicationSeals) }
        val retained = retainGenerationResources(scope)
        val key = scope + ":" + binding.operationId
        val reader = generationOutputReaders.getOrPut(key) {
            retained.openOutputs(GenerationAssetOwner(scope, binding))
        }
        generationOutputConversations[key] = binding.conversationId
        return reader
    }

    private fun readGenerationBackup(reference: app.daiyu.android.storage.GenerationOutputReference): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper())
        // Current identity comes ONLY from validated local manifest metadata, never export rows
        // or loadForEditing/loadSnapshot (both decrypt credentials).
        val scope = checkNotNull(GenerationSettingsStore(application).readAccountScope())
        check(scope == reference.accountScope) { "generation_account_changed" }
        val binding = GenerationAttemptBinding(reference.conversationId, reference.assistantMessageId,
            reference.runId, reference.toolCallId, reference.toolName)
        check(binding.operationId == reference.identity.operationId)
        return generationOutputReader(scope, binding).readBackup(reference)
    }

    internal data class HistoricalGenerationAssetDisplay(
        val history: app.daiyu.android.backup.HistoricalGenerationOutput,
        val thumbnail: android.graphics.Bitmap?,
    )

    /** Imported originals have a separate read-only path. No settings/vault, retained live
     * Resources, output registration, reservation, download or restoration of authority.
     */
    internal fun loadHistoricalGenerationOutputs(conversation: ConversationId,
        complete: (List<HistoricalGenerationAssetDisplay>?) -> Unit): Boolean {
        synchronized(this) {
            if (closed || !ready || dataBusy) return false
            generationReaders++
        }
        try { executor.execute {
            val result = runCatching {
                synchronized(this) { check(conversation.value !in generationConversationPublicationSeals) }
                // Display is explicitly capped like live history; complete export is not capped.
                neutralBackup.historicalGenerationOutputs(conversation).take(32).map { history ->
                    val bytes = neutralBackup.historicalGenerationOriginal(history)
                    val thumbnail = if (history.kind == GenerationKind.IMAGE) {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = Integer.highestOneBit(
                                    (maxOf(history.width, history.height) / 512).coerceAtLeast(1))
                            })
                    } else null
                    HistoricalGenerationAssetDisplay(history, thumbnail)
                }
            }.getOrNull()
            handler.post {
                synchronized(this) {
                    generationReaders--
                    if (closed) closeStorageWhenIdle()
                }
                complete(result)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { generationReaders-- }; return false
        }
        return true
    }

    internal data class StaticGenerationAssetDisplay(val output: app.daiyu.android.storage.StaticGenerationOutput,
        val thumbnail: android.graphics.Bitmap?)

    internal fun loadStaticGenerationOutputs(conversation: ConversationId,
        complete: (List<StaticGenerationAssetDisplay>?) -> Unit): Boolean {
        synchronized(this) {
            if (closed || !ready || dataBusy) return false
            generationReaders++
        }
        try { executor.execute {
            val result = runCatching {
                synchronized(this) { check(conversation.value !in generationConversationPublicationSeals) }
                neutralBackup.staticGenerationOutputs(conversation).take(32).map { output ->
                    val source = output.source
                    val bytes = neutralBackup.staticGenerationOriginal(output)
                    val thumbnail = if (source.kind == GenerationKind.IMAGE) android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit((maxOf(source.width, source.height) / 512).coerceAtLeast(1)) }) else null
                    StaticGenerationAssetDisplay(output, thumbnail)
                }
            }.getOrNull()
            handler.post {
                synchronized(this) { generationReaders--; if (closed) closeStorageWhenIdle() }
                complete(result)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { generationReaders-- }; return false
        }
        return true
    }

    // Process-local receipts, deliberately independent of Activity/editor leases. Record BEFORE
    // posting the completion: an obsolete host may discard UI callbacks, never this locator.
    // Activity recreation retains these via the Application controller; process death does not.
    private val pendingBranchOutcomes = mutableListOf<MessageActionResult.Unknown>()
    internal fun pendingBranchOutcome(): MessageActionResult.Unknown? = synchronized(this) {
        pendingBranchOutcomes.firstOrNull()
    }
    internal fun dismissBranchOutcome(receipt: MessageActionResult.Unknown) {
        val removed = synchronized(this) {
            val index = pendingBranchOutcomes.indexOfFirst { it === receipt }
            if (index < 0) false else { pendingBranchOutcomes.removeAt(index); true }
        }
        if (removed) notifyChanged()
    }

    /** A read-only inspection of the T1 locator. Absence is NOT permission to repeat the branch. */
    internal fun inspectBranch(locator: app.daiyu.android.context.BranchLocator, complete: (Boolean) -> Unit): Boolean {
        synchronized(this) { if (closed || !ready || dataBusy) return false; generationReaders++ }
        try { executor.execute {
            val verified = runCatching {
                check(conversationExists(locator.conversationId))
                DatabaseTransactions { database.writableDatabase }.run { db ->
                    check(db.rawQuery("SELECT 1 FROM context_branches WHERE conversation_id=?", arrayOf(locator.conversationId.value)).use { it.moveToFirst() })
                    val messages = ContextService.read(db, locator.conversationId)
                    check(messages.isNotEmpty() && messages.all { it.status == RunStatus.COMPLETED })
                    messages.forEach { app.daiyu.android.storage.SQLiteMessageVideoStore.readVerified(db, it) }
                }
                val outputs = neutralBackup.staticGenerationOutputs(locator.conversationId)
                check(outputs.map { it.identity }.toSet() == locator.staticIdentities.toSet())
                outputs.forEach { neutralBackup.staticGenerationOriginal(it) }
                true
            }.getOrDefault(false)
            handler.post {
                synchronized(this) { generationReaders--; if (closed) closeStorageWhenIdle() }
                complete(verified)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { generationReaders-- }; return false
        }
        return true
    }

    internal fun saveStaticGenerationOriginal(output: app.daiyu.android.storage.StaticGenerationOutput, uri: Uri,
        visitActive: java.util.concurrent.atomic.AtomicBoolean,
        resolver: android.content.ContentResolver = application.contentResolver) {
        require(uri.scheme == "content") { "请选择文档目标" }
        dataTask(failureNotice = "静态原件保存未确认；目标可能不完整，未重试", work = {
            fun authorize() {
                check(visitActive.get()) { "原会话访问已失效" }
                synchronized(this) { check(!closed && output.destination.conversationId !in generationConversationPublicationSeals) }
            }
            authorize()
            val bytes = neutralBackup.staticGenerationOriginal(output)
            authorize()
            resolver.openOutputStream(uri, "wt")?.use { destination ->
                var offset = 0
                while (offset < bytes.size) {
                    authorize(); val count = minOf(8192, bytes.size - offset)
                    destination.write(bytes, offset, count); offset += count
                }
                destination.flush()
            } ?: throw java.io.IOException("无法打开目标")
            "静态生成原件已保存；仅本地历史文件，未恢复执行授权"
        })
    }

    internal val browserDownloads = app.daiyu.android.browser.download.BrowserDownloadShelf()

    internal fun completedBrowserDownloads(conversation: ConversationId) = browserDownloads.list(conversation.value)

    /** Save is a separate UI action, not fetch approval or publication. The resolver receives
     * only the user-selected content URI; no SAF-to-host-path translation is attempted.
     */
    internal fun saveBrowserDownload(conversation: ConversationId,
        reference: app.daiyu.android.browser.download.BrowserCompletedDownload, uri: Uri,
        assertUiOwner: () -> Unit,
        resolver: android.content.ContentResolver = application.contentResolver) {
        require(uri.scheme == "content") { "请选择文档目标" }
        browserDownloads.assertOwned(conversation.value, reference)
        assertUiOwner()
        dataTask(failureNotice = "浏览器原件保存失败；目标可能不完整，未自动重试", work = {
            check(conversationExists(conversation)) { "原会话已不存在" }
            reference.saveOriginal(openDestination = {
                resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("无法打开目标")
            }, assertOwner = {
                synchronized(this) { check(!closed) { "保存宿主已关闭" } }
                browserDownloads.assertOwned(conversation.value, reference)
                assertUiOwner()
            })
            "浏览器原件已保存；原字节及 SHA-256 校验通过"
        })
    }

    /** Explicit user-selected destination only. Revalidates the backup-owned reference and
     * exact hash-addressed original; never exports a private path or an execution capability.
     */
    internal fun saveHistoricalGenerationOriginal(history: app.daiyu.android.backup.HistoricalGenerationOutput,
        uri: Uri, resolver: android.content.ContentResolver = application.contentResolver) {
        dataTask(failureNotice = "历史原件保存失败；目标可能不完整，未重发请求", work = {
            synchronized(this) { check(history.conversationId !in generationConversationPublicationSeals) }
            val bytes = neutralBackup.historicalGenerationOriginal(history)
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw java.io.IOException("无法打开目标")
            "历史生成原件已保存；仅历史文件，未恢复生成授权或重发请求"
        })
    }

    /** Worker-only LOCAL reader cleanup. This is not a publication barrier or a history seal. */
    private fun closeGenerationOutputReaders(owners: Set<String>? = null) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val keys = generationOutputConversations.filterValues { owners == null || it in owners }.keys.toList()
        val readers = keys.mapNotNull { key ->
            generationOutputConversations.remove(key)
            generationOutputReaders.remove(key)
        }
        readers.forEach { it.close() }
    }

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
    private var childCommandReaders=0
    private var childrenClosing=false
    private var childrenClosed=false
    private var questionStore:PrivateQuestionStore?=null
    private val closeDone=CountDownLatch(1)
    private var closingStorage=false
    private class RunResources(val initial:Message,val runner:ChatRunner,val tools:RunCatalogTools,
        val auxiliary: ControllerAuxiliaryVision? = null,
        val summaryPreparation: app.daiyu.android.context.RuntimeSummaryPreparation? = null) {
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
            if(observation.drained) {
                unacceptedTools.remove(tools)
                clearCleanupProblem(tools)
                guiEntry?.takeIf { it.catalog === tools && !it.persistenceUnknown && it.session?.releaseConfirmed == true }
                    ?.let { it.taskSurface.end(); guiEntry = null }
                virtualDisplayEntry?.takeIf { it.catalog === tools && !it.persistenceUnknown }?.let {
                    it.taskSurface.end()
                    virtualDisplayEntry = null
                }
            }
            else reportCleanupProblem(tools, "运行准备清理结果 UNKNOWN；原资源保留，不自动重连或重发")
            if(closed) closeStorageWhenIdle() else notifyChanged()
        }}}
        if (synchronized(this) { virtualDisplayEntry?.catalog === tools }) executor.execute {
            val result = tools.observeVirtualDisplayCloseOnWorker(parentDrained = true)
            if (result is RunCatalogCloseResult.Unknown) handler.post { synchronized(this) {
                if (unacceptedTools.containsKey(tools)) {
                    reportCleanupProblem(tools, "副屏清理结果 UNKNOWN；保留原身份、会话和 lease，不自动重试")
                    notifyChanged()
                }
            } }
        }
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
        } } || browserChildren.any { !it.drained &&
            (conversationId == null || it.scope.owner.conversationId == conversationId.value) }
    internal fun stopChildTask(record:ChildTaskRecord):app.daiyu.android.domain.delegation.ChildTaskStop {
        val browser = synchronized(this) {
            if (closed || !ready) null else browserChildren.firstOrNull { it.scope.owner == record.owner }
        }
        if (browser != null) {
            browser.close()
            browserChildrenChanged()
            return app.daiyu.android.domain.delegation.ChildTaskStop.REQUESTED
        }
        val tasks=synchronized(this) {
            if(closed || !ready) null else backgroundRuns[RunId(record.owner.parentRunId)]
        }
        return tasks?.stop(record.owner) ?: app.daiyu.android.domain.delegation.ChildTaskStop.NOT_FOUND
    }
    internal fun liveChildTasks(conversationId: ConversationId): List<app.daiyu.android.domain.delegation.RetainedChildTask> {
        val retained = synchronized(this) {
            check(!closed && ready && !recoveryFailed) { "child_tasks_unavailable" }
            backgroundRuns.values.toList()
        }
        return retained.flatMap { it.snapshots() }.filter { it.record.owner.conversationId == conversationId.value }
    }
    internal fun pauseChildTask(record: ChildTaskRecord): app.daiyu.android.domain.delegation.ChildTaskControlReceipt {
        val retained = synchronized(this) {
            if (closed || !ready || recoveryFailed) null else backgroundRuns[RunId(record.owner.parentRunId)]
        }
        return retained?.pause(record.owner) ?: app.daiyu.android.domain.delegation.ChildTaskControlReceipt.STALE
    }
    internal fun resumeChildTask(record: ChildTaskRecord): app.daiyu.android.domain.delegation.ChildTaskControlReceipt {
        val retained = synchronized(this) {
            if (closed || !ready || recoveryFailed) null else backgroundRuns[RunId(record.owner.parentRunId)]
        }
        return retained?.resume(record.owner) ?: app.daiyu.android.domain.delegation.ChildTaskControlReceipt.STALE
    }
    /** Worker-only records action. Historical work gets a new real Runner owner, never a reopened binding. */
    internal fun directChildTask(command: ChildTaskCommand): ChildTaskCommandReceipt {
        check(Looper.myLooper() != Looper.getMainLooper()) { "child_command_requires_worker" }
        synchronized(this) {
            if (closed || !ready || recoveryFailed) return ChildTaskCommandReceipt.Rejected("child_tasks_unavailable")
            childCommandReaders++
        }
        try { return directChildTaskPinned(command) }
        finally { synchronized(this) {
            childCommandReaders--
            if (closed) closeStorageWhenIdle()
        } }
    }
    private fun directChildTaskPinned(command: ChildTaskCommand): ChildTaskCommandReceipt {
        val runtime = synchronized(this) { checkNotNull(runtimeStore) }
        runtime.childTasks.commandReceipt(command)?.let { return it }
        val liveParent = synchronized(this) {
            backgroundRuns[RunId(command.source.owner.parentRunId)]?.takeIf { active?.runId?.value == command.source.owner.parentRunId }
        }
        val invocation = ToolInvocation(ToolCallId(command.callId), "direct_child_task", "{}")
        if (synchronized(this) { closed }) return ChildTaskCommandReceipt.Rejected("child_tasks_unavailable")
        if (liveParent != null) {
            if (liveParent.snapshots().none { it.record.owner == command.source.owner })
                return ChildTaskCommandReceipt.Rejected("child_source_not_controlled")
            return liveParent.command(invocation, command)
        }
        val token = synchronized(this) {
            if (!idleAvailable()) return ChildTaskCommandReceipt.Rejected("child_host_busy")
            reservePreparation()
        }
        try {
            val conversation = ConversationId(command.source.owner.conversationId)
            if (!conversationExists(conversation)) return ChildTaskCommandReceipt.Rejected("child_source_stale")
            val key = SubAgentConfigKey.Conversation(conversation.value)
            var frozenAssistantId: String? = null
            val models = try {
                val assistantId = database.assistantId(conversation) ?: throw MissingAssistantBindingException()
                frozenAssistantId = assistantId
                val profile = assistantStore.read(assistantId)?.let { it.copy(enabledSkillIds = it.enabledSkillIds.toList()) }
                    ?: throw AssistantNotFoundException(assistantId)
                val assistantContext = AssistantRunContext.capture(assistantStore, assistantId, assistantAssets)
                check(assistantStore.read(assistantId) == profile) { "assistant_profile_changed" }
                childBindings.freeze(key, childPreferences.snapshot(key)) { provider ->
                    val delegate = sessionModel?.invoke(provider, assistantContext, SessionGatewayContext(conversation))
                        ?: assistantModel?.invoke(provider, assistantContext) ?: model(provider.asModelSettings())
                    messageImages.wrap(delegate, provider)
                }
            } catch (_: Exception) { return ChildTaskCommandReceipt.Rejected("child_model_binding_unavailable") }
            if (command.profileId !in models) return ChildTaskCommandReceipt.Rejected("invalid_child_profile")
            if (preparationCancelled(token)) return ChildTaskCommandReceipt.Rejected("cancelled_before_admission")
            val run = RunId(UUID.randomUUID().toString())
            val acceptedAt = System.currentTimeMillis()
            val user = Message(MessageId(UUID.randomUUID().toString()), conversation, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("child-command", "子任务${command.kind.name}；来源 ${command.source.owner.childRunId}\n${command.instruction}")), RunStatus.COMPLETED, createdAt = acceptedAt)
            val initial = Message(MessageId(UUID.randomUUID().toString()), conversation, run, Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING, createdAt = acceptedAt)
            database.send(user, initial, expectedAssistantId = frozenAssistantId, preserveDraft = true)
            return ChildTaskCommandHost.run(initial, command, checkNotNull(childSupervisor), models, database, runtime,
                cancelled = { preparationCancelled(token) }, onChanged = ::backgroundTasksChanged,
                retain = { tasks -> synchronized(this) {
                    check(!closed && !preparationCancelled(token))
                    backgroundRuns[run] = tasks
                } })
        } finally { finishPreparation(token); backgroundTasksChanged() }
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
        resources==null && generationEntry==null && virtualDisplayEntry==null && guiEntry==null && generationReaders==0 && pendingShizuku==null && pendingPlatformAction==null && unacceptedTools.isEmpty() &&
        !recoveryFailed && problem?.startsWith("会话恢复失败")!=true
    internal val selections=mutableMapOf<ConversationId?,Pair<Int,Int>>()
    @Volatile var active:Message?=null;private set
    private var problemRevision = 0L
    @Volatile var problem:String?=null
        private set(value) { synchronized(this) { field = value; problemRevision++ } }
    // Each cleanup chain owns only its last publication, never a later unrelated error (even
    // one with identical text). All accesses to this map are under the Controller monitor.
    private val cleanupProblemRevisions = mutableMapOf<RunCatalogTools, Long>()
    private fun reportCleanupProblem(tools: RunCatalogTools, text: String) {
        problem = text
        cleanupProblemRevisions[tools] = problemRevision
    }
    private fun clearCleanupProblem(tools: RunCatalogTools) {
        val revision = cleanupProblemRevisions.remove(tools) ?: return
        if (problemRevision == revision) problem = null
    }
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
    internal fun testModelConnection(draft: ModelSettings, cancelled: () -> Boolean,
        complete: (app.daiyu.android.model.catalog.ModelConnectionResult) -> Unit): Boolean = synchronized(this) {
        if (closed || !ready || active != null || dataBusy || cancelled()) return@synchronized false
        val service = modelCatalog
        try {
            executor.execute {
                fun revoked() = synchronized(this) { closed } || cancelled()
                val result = service.testDraft(draft, ::revoked)
                if (!revoked()) handler.post { if (!revoked()) complete(result) }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
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
    private var changedDispatchPending = false
    private fun notifyChanged() {
        synchronized(this) {
            if (closed || changedDispatchPending) return
            changedDispatchPending = true
            handler.post {
                synchronized(this) { changedDispatchPending = false }
                dispatchChanged()
            }
        }
    }

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
        guiEntry?.takeIf { it.assistantId == lifetime.initial.id && it.runId == lifetime.initial.runId &&
            it.conversationId == lifetime.initial.conversationId }?.let { it.revoked = true; it.session?.close() }
        virtualDisplayEntry?.takeIf { it.assistantId == lifetime.initial.id && it.runId == lifetime.initial.runId &&
            it.conversationId == lifetime.initial.conversationId }?.let { it.revoked = true; it.session?.close() }
        videoSession?.takeIf { it.assistant.runId == lifetime.initial.runId }?.revoke()
        generationEntry?.takeIf { it.runner === lifetime.runner }?.session?.revokeAdmission()
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
            reservePreparation(ticket).also {
                pendingTextState = TextSendState(ticket, owner, text)
                it.videoEditor = ControllerVideoEditor(conversation?.id?.value, editorGeneration, draftRevision, text, true)
            }
        }
        try { executor.execute {
            val saved = try { sendPrepared(token,conversation,text,expectedDraftText=text) }
                catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(owner,saved,token.admittedVoiceOwner)
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
        synchronized(this) { guiEntry?.takeIf { it.textTicket === ticket } }?.let { return cancelGui(it) }
        synchronized(this) { virtualDisplayEntry?.takeIf { it.textTicket === ticket } }
            ?.let { return cancelVirtualDisplay(it) }
        val token = synchronized(this) {
            preparation?.takeIf { it.textTicket === ticket }?.also { it.revoked = true; it.video?.revoke() }
        } ?: return false
        token.cancellation.cancel()
        token.browser?.cancel()
        token.mcp?.cancel()
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
    // AC037: prepare is validation only; explicit confirmation issues ONE exact grant.
    @Synchronized internal fun preparePlatformAction(conversation: Conversation?, text: String,
        kind: app.daiyu.android.platform.actions.PlatformActionKind, arguments: String): ControllerPlatformActionPreparation? {
        if (!idleAvailable()) return null
        val preview = runCatching {
            ControllerPlatformActionPreparation(conversation, text,
                app.daiyu.android.platform.actions.PlatformAction.parse(kind, arguments), platformActions,
                { taskSurfaceStore }, application.packageName,
                clockTarget = {
                    val action = if (kind == app.daiyu.android.platform.actions.PlatformActionKind.ALARM)
                        android.provider.AlarmClock.ACTION_SET_ALARM else android.provider.AlarmClock.ACTION_SET_TIMER
                    android.content.Intent(action).resolveActivity(application.packageManager)?.packageName
                })
        }.getOrNull() ?: return null
        pendingPlatformToken = reservePreparation()
        pendingPlatformAction = preview
        notifyChanged()
        return preview
    }
    internal fun preparePlatformActionLocation(preview: ControllerPlatformActionPreparation,
        complete: (app.daiyu.android.tasksurface.TaskSurfaceDecision) -> Unit): Boolean {
        synchronized(this) {
            if (closed || pendingPlatformAction !== preview || preview.cancelled || preview.submitted || !preview.requiresLocation) return false
        }
        return try {
            executor.execute {
                val decision = try { preview.freezeLocationOnWorker() } catch (_: Exception) {
                    app.daiyu.android.tasksurface.TaskSurfaceDecision.NotExecuted("CLOCK_LOCATION_UNAVAILABLE", "时钟位置准备失败；未执行")
                }
                handler.post { complete(decision) }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }
    internal fun rejectPlatformAction(preview: ControllerPlatformActionPreparation) {
        val token = synchronized(this) {
            if (pendingPlatformAction !== preview) null
            else pendingPlatformToken?.also { it.revoked = true }
        }
        preview.cancel()
        token?.cancellation?.cancel()
        val installed = synchronized(this) { resources?.takeIf { it.initial.runId == preview.runId } }
        installed?.let(::stopRun)
        if (!preview.submitted && token != null) finishPlatformAction(preview)
    }
    internal fun confirmPlatformAction(preview: ControllerPlatformActionPreparation,
        complete: (Conversation?) -> Unit): Boolean {
        val token = synchronized(this) {
            if (closed || pendingPlatformAction !== preview || preview.submitted || preview.cancelled ||
                pendingPlatformToken?.revoked != false ||
                (preview.requiresLocation && preview.location?.ready(app.daiyu.android.tasksurface.TaskSurface.FOREGROUND) == null)) return false
            preview.approve() // User confirmation only, no Android calls.
            checkNotNull(pendingPlatformToken)
        }
        try { executor.execute {
            val saved = try {
                if (preview.cancelled) null else sendPrepared(token, preview.conversation, preview.messageText,
                    expectedDraftText = preview.text, platformAction = preview)
            } catch (_: Exception) {
                synchronized(this) { problem = "系统操作准备失败；草稿保留，未自动重发"; notifyChanged() }
                null
            }
            finishPlatformAction(preview)
            handler.post { try { complete(saved) } catch (_: Exception) {} }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            preview.cancel(); finishPlatformAction(preview); return false
        }
        return true
    }
    private fun finishPlatformAction(preview: ControllerPlatformActionPreparation) {
        fun release() = synchronized(this) {
            if (pendingPlatformAction === preview) {
                val token = checkNotNull(pendingPlatformToken)
                pendingPlatformAction = null; pendingPlatformToken = null
                finishPreparation(token)
            }
        }
        // Installed scope belongs to the original RunResources and its existing owner-drain.
        if (synchronized(this) { resources?.initial?.runId == preview.runId }) release()
        else preview.scope?.observeClose { result ->
            if (result == RunCatalogCloseResult.Drained) release()
            else synchronized(this) {
                problem = "系统操作结果 UNKNOWN；保留原运行，不自动重试，请核实设备状态"
                notifyChanged()
            }
        } ?: release()
    }

    private fun closeSummaryPreparationSafely(value: app.daiyu.android.context.RuntimeSummaryPreparation?) {
        try { value?.close() }
        catch (_: Exception) {
            synchronized(this) {
                recoveryFailed = true
                problem = "摘要用量清理未确认；新发送已停用，未自动重发"
                notifyChanged()
            }
        }
    }
    private fun finishPreparation(token: Preparation) {
        if (token.mcpCatalog == null) token.mcp?.cancel()
        // Accepted READY belongs to RunResources until the first checkpoint (or final drain).
        // Clearing the editor preparation lane must NOT cancel the handoff.
        if (!token.summaryTransferred) closeSummaryPreparationSafely(token.summaryPreparation)
        val gui = synchronized(this) { if (preparation === token) guiEntry?.takeIf { it.catalog == null } else null }
        if (gui != null) {
            gui.session?.close()
            if (gui.session?.releaseConfirmed == false) {
                if (token.cleanupDeadline == 0L) token.cleanupDeadline = android.os.SystemClock.uptimeMillis() + 30_000
                if (android.os.SystemClock.uptimeMillis() < token.cleanupDeadline)
                    handler.postDelayed({ finishPreparation(token) }, 50)
                else synchronized(this) {
                    problem = "GUI 清理未确认；保留原会话与草稿，不自动重试"
                    notifyChanged()
                }
                return
            }
            synchronized(this) { if (guiEntry === gui && !gui.persistenceUnknown) {
                gui.taskSurface.end()
                guiEntry = null
            } }
        }
        val installed = synchronized(this) { resources?.initial?.runId == token.video?.assistant?.runId && token.video != null }
        if (!installed) {
            token.video?.revoke()
            if (videoSession === token.video) videoSession = null
        }
        val job = token.terminalJob
        if (job != null && token.terminalCatalog == null) {
            val start = synchronized(this) {
                if (token.terminalCleanupStarted) false else { token.terminalCleanupStarted = true; true }
            }
            if (start) Thread({
                val drained = try { job.cleanupOnWorker() } catch (_: Exception) { false }
                if (drained) releasePreparation(token) else synchronized(this) {
                    problem = "固定命令 job 清理 UNKNOWN；保留原资源，不自动重发"
                    notifyChanged()
                }
            }, "terminal-job-preparation-drain").apply { isDaemon = true; start() }
            return
        }
        val terminal = token.terminal
        if (token.terminalCatalog == null && ((terminal != null && terminal.state != ControllerTerminalPreparation.State.DRAINED) ||
                (terminal == null && token.terminalLaunch is TerminalPtyLaunch.Linux))) {
            val start = synchronized(this) {
                if (token.terminalCleanupStarted) false else { token.terminalCleanupStarted = true; true }
            }
            if (start) Thread({
                val drained = try {
                    val transferred = token.terminalRun
                    if (transferred != null) {
                        transferred.binding.close()
                        transferred.binding.awaitTermination(30_000)
                    } else if (terminal != null) {
                        terminal.cancel()
                        terminal.awaitCleanupOnWorker(30_000)
                    } else {
                        (token.terminalLaunch as TerminalPtyLaunch.Linux).close()
                        true
                    }
                } catch (_: Exception) { false }
                if (drained) releasePreparation(token) else synchronized(this) {
                    problem = "终端清理 UNKNOWN；保留原 owner 和草稿，不自动重发"
                    notifyChanged()
                }
            }, "terminal-preparation-drain").apply { isDaemon = true; start() }
            return
        }
        finishNonTerminalPreparation(token)
    }
    private fun releasePreparation(token: Preparation) {
        synchronized(this) {
            if (preparation !== token) return
            preparation = null
            pendingImage = null
            if (closed) closeStorageWhenIdle() else notifyChanged()
        }
    }
    private fun finishNonTerminalPreparation(token: Preparation) {
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
            virtualDisplayEntry?.takeIf { !it.accepted && !token.persistenceUnconfirmed && !it.factoryEntered && it.catalog == null }
                ?.let { it.taskSurface.end(); virtualDisplayEntry = null }
            pendingImage = null
            if (closed) closeStorageWhenIdle() else notifyChanged()
        }
    }

    private var guiEntry: ControllerGuiEntry? = null
    @Synchronized internal fun guiEntry(): ControllerGuiEntry? = guiEntry

    /** Reserve only: no screen lease, overlay, model, or durable acceptance. The original entry
     * is the location owner and must be retained while its one frozen decision is pending. */
    @Synchronized internal fun prepareGuiSend(conversation: Conversation?, editor: TextSendOwner, text: String,
        operation: app.daiyu.android.gui.GuiOperation, input: String?, targetPackage: String? = null): ControllerGuiEntry? {
        if (!idleAvailable() || pendingTextState != null) return null
        val entry = runCatching { ControllerGuiEntry(conversation, editor, text, operation, input, targetPackage) }
            .getOrNull() ?: return null
        entry.taskSurface = ControllerTaskSurface(entry, taskSurfaceStore, application.packageName,
            targetPackage, backgroundSupported = false) {
            synchronized(this) { !closed && guiEntry === entry && !entry.revoked }
        }
        guiEntry = entry
        reservePreparation()
        notifyChanged()
        return entry
    }

    /** The foreground backend is opened separately, only after the retained location decision.
     * Main and direct Controller callers use the same gate; reservation itself is not permission. */
    internal fun openPreparedGui(entry: ControllerGuiEntry): Boolean {
        check(Looper.myLooper() === Looper.getMainLooper())
        synchronized(this) {
            val token = preparation ?: return false
            if (closed || guiEntry !== entry || entry.revoked || entry.submitted || token.revoked ||
                entry.session != null || pendingTextState != null) return false
        }
        val ready = entry.taskSurface.ready(TaskSurface.FOREGROUND) ?: return false
        val opened = try { app.daiyu.android.gui.GuiRuntime.openForUser(
            app.daiyu.android.gui.GuiOwner(entry.sessionId, entry.runId.value),
            targetPackage = entry.targetPackage, selfTarget = ready.selfTarget, surfaceOwner = entry) }
        catch (_: Exception) { app.daiyu.android.gui.GuiResult.Rejected("GUI_HOST_UNAVAILABLE") }
        if (opened !is app.daiyu.android.gui.GuiResult.Value) {
            cancelGui(entry)
            return false
        }
        synchronized(this) {
            entry.session = opened.value // Retain before overlay attachment, including a late cancellation.
            if (closed || guiEntry !== entry || entry.revoked || preparation?.revoked != false) {
                opened.value.close()
                return false
            }
        }
        notifyChanged()
        return true
    }

    internal fun cancelGui(entry: ControllerGuiEntry): Boolean {
        val (running, token) = synchronized(this) {
            if (guiEntry !== entry) return false
            entry.revoked = true
            entry.session?.close() // synchronous admission fence; physical release is separately observed
            val pending = preparation?.also { it.revoked = true }
            val running = resources?.takeIf { it.initial.id == entry.assistantId &&
                it.initial.runId == entry.runId && it.initial.conversationId == entry.conversationId }
                ?.also { fenceRun(it) }
            running to pending
        }
        token?.cancellation?.cancel()
        if (running != null) stopRun(running)
        else if (!entry.submitted && token != null) finishPreparation(token)
        return true
    }

    internal fun sendGui(entry: ControllerGuiEntry, delivery: app.daiyu.android.gui.GuiConfirmedDelivery,
        ticket: TextSendTicket, complete: (TextSendResult) -> Unit): Boolean {
        val token = synchronized(this) {
            val current = preparation ?: return false
            if (closed || guiEntry !== entry || entry.revoked || entry.submitted || current.revoked ||
                entry.taskSurface.ready(TaskSurface.FOREGROUND) == null ||
                pendingTextState != null || entry.session !== delivery.session ||
                delivery.session.identity != app.daiyu.android.gui.GuiOwner(entry.sessionId, entry.runId.value) ||
                delivery.action.operation != entry.operation || delivery.action.text != entry.input ||
                delivery.action.observationId != delivery.observation.observationId ||
                delivery.writes != setOf(delivery.action.effect(delivery.session.identity))) return false
            entry.delivery = delivery
            entry.submitted = true
            entry.textTicket = ticket
            current.submitted = true
            current.textTicket = ticket
            pendingTextState = TextSendState(ticket, entry.editor, entry.text)
            current
        }
        try { executor.execute {
            val saved = try { sendPrepared(token, entry.conversation, entry.text,
                expectedDraftText = entry.text, gui = entry) } catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(entry.editor, saved)
                token.persistenceUnconfirmed -> TextSendResult.Unknown(entry.editor)
                else -> TextSendResult.Rejected(entry.editor)
            }
            synchronized(this) {
                if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result)
            }
            finishPreparation(token)
            handler.post { try { complete(result) } catch (_: Exception) {} }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            cancelGui(entry); finishPreparation(token); return false
        }
        return true
    }

    private var virtualDisplayEntry: ControllerVirtualDisplayEntry? = null
    @Synchronized internal fun virtualDisplayEntry(): ControllerVirtualDisplayEntry? = virtualDisplayEntry
    @Synchronized internal fun virtualDisplayApproval(entry: ControllerVirtualDisplayEntry) =
        if (virtualDisplayEntry === entry && !entry.revoked) entry.session?.pendingApproval() else null

    /** Explicit text-only mode. Preparation is in-memory; durable lease reservation occurs only
     * after database acceptance when the ONE Runner binds its opaque owner to the catalog. */
    @Synchronized internal fun prepareVirtualDisplaySend(conversation: Conversation?, editor: TextSendOwner,
        text: String, writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead>): ControllerVirtualDisplayEntry? {
        if (!idleAvailable() || pendingTextState != null) return null
        return runCatching { ControllerVirtualDisplayEntry(conversation, editor, text, writes, reads) }
            .getOrNull()?.also { entry ->
                val targets = entry.writes.mapNotNull { (it.action as? R08VirtualDisplayAction.Launch)?.packageName }
                val containsSelf = application.packageName in targets
                entry.taskSurface = ControllerTaskSurface(entry, taskSurfaceStore, application.packageName,
                    targets.firstOrNull(), backgroundSupported = !containsSelf, unsupportedPlan = containsSelf) {
                    synchronized(this) { !closed && virtualDisplayEntry === entry && !entry.revoked }
                }
                virtualDisplayEntry = entry; reservePreparation(); notifyChanged()
            }
    }

    /** Identity fence is synchronous, including while a factory or DB receipt is in flight.
     * Session.close is revocation only; runner/transport work is posted outside this monitor. */
    internal fun cancelVirtualDisplay(entry: ControllerVirtualDisplayEntry): Boolean {
        val (lifetime, token, submitted) = synchronized(this) {
            if (virtualDisplayEntry !== entry) return false
            entry.revoked = true
            entry.session?.close()
            val pending = preparation?.also { it.revoked = true }
            val running = resources?.takeIf { it.initial.id == entry.assistantId && it.initial.runId == entry.runId &&
                it.initial.conversationId == entry.conversationId }?.also { fenceRun(it) }
            Triple(running, pending, entry.submitted)
        }
        token?.cancellation?.cancel() // can invoke transports; never under the Controller monitor
        if (lifetime != null) stopRun(lifetime)
        else if (!submitted && token != null) finishPreparation(token)
        return true // acknowledgement of this identity, not proof of drain or DB rejection
    }

    /** Original physical-observation outer gate. Caller holds this Controller's monitor.
     * This does NOT prove the catalog's local drain; cleanup independently rechecks that gate. */
    private fun virtualDisplayParentDrainedLocked(entry: ControllerVirtualDisplayEntry,
        catalog: RunCatalogTools): Boolean {
        if (virtualDisplayEntry !== entry || !entry.revoked || entry.catalog !== catalog) return false
        val lifetime = resources
        return if (lifetime != null) {
            lifetime.tools === catalog && !lifetime.busy && !lifetime.waiting &&
                lifetime.result != null && lifetime.devicesClosing
        } else preparation == null && catalog in unacceptedTools
    }

    /** Explicit cleanup visit, never a physical-observation probe or fallback session.
     * Keep ORIGINAL entry/catalog/session even if later UI callbacks arrive after replacement. */
    internal fun openVirtualDisplayCleanup(entry: ControllerVirtualDisplayEntry,
        uiOwner: app.daiyu.android.vdapproval.R08CleanupUiOwner,
        currentUiOwner: () -> app.daiyu.android.vdapproval.R08CleanupUiOwner?
    ): ControllerVirtualCleanupAdapter? = synchronized(this) {
        if (closingStorage || virtualDisplayEntry !== entry || !entry.revoked) return@synchronized null
        val originalCatalog = entry.catalog ?: return@synchronized null
        val originalSession = entry.session ?: return@synchronized null
        ControllerVirtualCleanupAdapter(originalCatalog, originalSession, uiOwner,
            parentIsRetainedAndDrained = {
                synchronized(this@ChatController) {
                    !closingStorage && entry.session === originalSession &&
                        virtualDisplayParentDrainedLocked(entry, originalCatalog)
                }
            }, currentUiOwner = currentUiOwner)
    }

    /** Explicit physical observation, never cancellation-as-exit or an automatic release write.
     * A pending/UNKNOWN result retains the entry and all handles; the user may observe again. */
    internal fun observeVirtualDisplayClose(entry: ControllerVirtualDisplayEntry): Boolean {
        val tools = synchronized(this) {
            val catalog = entry.catalog ?: return false
            if (!virtualDisplayParentDrainedLocked(entry, catalog)) return false
            catalog
        }
        executor.execute {
            val result = tools.observeVirtualDisplayCloseOnWorker(parentDrained = true)
            if (result is RunCatalogCloseResult.Unknown) handler.post { synchronized(this) {
                if (virtualDisplayEntry === entry) {
                    reportCleanupProblem(tools, "副屏清理结果 UNKNOWN；保留原身份、会话和 lease，不自动重连或重发")
                    notifyChanged()
                }
            } }
        }
        return true
    }

    internal fun answerVirtualDisplay(entry: ControllerVirtualDisplayEntry,
        request: app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest, approved: Boolean): Boolean {
        val answered = synchronized(this) {
            if (closed || virtualDisplayEntry !== entry || entry.revoked) return false
            entry.session?.answerFromUi(request, approved) == true
        }
        if (answered && !approved) cancelVirtualDisplay(entry)
        if (answered) notifyChanged()
        return answered
    }

    internal fun sendVirtualDisplay(entry: ControllerVirtualDisplayEntry, ticket: TextSendTicket,
        complete: (TextSendResult) -> Unit): Boolean {
        val token = synchronized(this) {
            val current = preparation ?: return false
            if (closed || virtualDisplayEntry !== entry || entry.revoked || entry.submitted ||
                entry.taskSurface.ready(TaskSurface.BACKGROUND)?.selfTarget != null ||
                entry.taskSurface.ready(TaskSurface.BACKGROUND) == null ||
                current.revoked || pendingTextState != null) return false
            entry.submitted = true
            entry.textTicket = ticket
            current.submitted = true
            current.textTicket = ticket
            pendingTextState = TextSendState(ticket, entry.editor, entry.text)
            current
        }
        try { executor.execute {
            val saved = try { sendPrepared(token, entry.conversation, entry.text,
                expectedDraftText = entry.text, virtualDisplay = entry) } catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(entry.editor, saved)
                token.persistenceUnconfirmed -> TextSendResult.Unknown(entry.editor)
                else -> TextSendResult.Rejected(entry.editor)
            }
            synchronized(this) {
                if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result)
            }
            finishPreparation(token)
            handler.post { try { complete(result) } catch (_: Exception) {} }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            cancelVirtualDisplay(entry); finishPreparation(token); return false
        }
        return true
    }

    /** Adapter to THIS controller's accepted run, actual authorization and central batch feedback.
     * It owns neither a second catalog nor a second dispatcher/ledger. */
    private fun bindVirtualDisplay(entry: ControllerVirtualDisplayEntry,
        realOwner: app.daiyu.android.domain.runtime.RunExecutionOwner,
        caps: SessionCapabilities): ControllerVirtualDisplaySession {
        synchronized(this) {
            check(virtualDisplayEntry === entry && entry.accepted && !entry.revoked && !entry.factoryEntered)
            check(entry.taskSurface.ready(TaskSurface.BACKGROUND)?.let { it.selfTarget == null } == true)
            check(realOwner.conversationId == entry.conversationId && realOwner.runId == entry.runId &&
                realOwner.assistantMessageId == entry.assistantId)
            entry.factoryEntered = true
            entry.owner = realOwner; entry.capabilities = caps
        }
        val parent = object : ControllerVirtualDisplayRuntime.Parent {
            override fun showApproval(request: app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest) {
                val current = synchronized(this@ChatController) {
                    virtualDisplayEntry === entry && !closed && !entry.revoked && entry.owner === realOwner &&
                        resources?.let { !it.fenced && realOwner.owns(it.initial) } == true
                }
                if (!current) { synchronized(this@ChatController) { entry.session?.close() }; return }
                notifyChanged() // asynchronous UI publication, never wait for Main
            }
            override fun combinedAuthorization(owner: app.daiyu.android.domain.runtime.RunExecutionOwner,
                capabilities: SessionCapabilities, approvedEffects: Set<app.daiyu.android.domain.tools.WriteEffect>) =
                synchronized(this@ChatController) {
                    check(virtualDisplayEntry === entry && !closed && !entry.revoked &&
                        entry.owner === owner && entry.capabilities === capabilities)
                    check(entry.authorization == null)
                    app.daiyu.android.domain.tools.TaskAuthorization(capabilities.sessionId, owner.runId.value,
                        approvedEffects).also { entry.authorization = it }
                }
            override fun stopAcceptedRun(owner: app.daiyu.android.domain.runtime.RunExecutionOwner) {
                if (synchronized(this@ChatController) { entry.owner === owner }) cancelVirtualDisplay(entry)
            }
            override fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>):
                ControllerVirtualDisplaySession.BatchCompletion {
                val ticket = leaf.beginBatch(originalWholeBatch)
                return ControllerVirtualDisplaySession.BatchCompletion { results -> leaf.finishBatch(ticket, results) }
            }
        }
        return virtualDisplayFactory.create(application, realOwner, caps, entry.writes, entry.reads, parent,
            ControllerVirtualDisplayRuntime.Retention { session ->
                synchronized(this) {
                    check(virtualDisplayEntry === entry && entry.session == null && entry.owner === realOwner)
                    entry.session = session // retain even a late factory; never resurrect admission
                    if (closed || entry.revoked) session.close()
                }
            })
    }

    @Synchronized internal fun browserPreparation(): ControllerBrowserPreparation? = preparation?.browser

    @Synchronized internal fun terminalPreparation(): ControllerTerminalPreparation? = preparation?.terminal
    @Synchronized internal fun terminalJobPreparation(): ControllerTerminalJobPreparation? = preparation?.terminalJob

    // Kept after catalog/run drain: daemon lifetime is independent of run, not of app process.
    // Bounded fail-closed retention; never evict UNKNOWN evidence to create dispatch capacity.
    private val retainedTerminalJobs = mutableListOf<TerminalJobSession>()
    @Synchronized internal fun terminalJobRuns(conversation: ConversationId): List<TerminalJobSession> =
        retainedTerminalJobs.filter { it.plan.conversationId == conversation }.toList()
    internal data class TerminalJobsSnapshot(val sessions: List<TerminalJobSession>,
        val history: List<app.daiyu.android.terminal.TerminalJobHistoryReader.Record>,
        val skipped: Int, val error: String?)

    /** Read-only restart evidence. Only a real Controller conversation may select the owner;
     * this path never creates a TerminalJobSession, Registry or TaskAuthorization. */
    internal fun terminalJobsOnWorker(conversation: ConversationId, admit: () -> Boolean): TerminalJobsSnapshot {
        checkTerminalHistoryOwner(conversation, admit)
        val scan = app.daiyu.android.terminal.TerminalJobHistoryReader(application).list(conversation.value)
        checkTerminalHistoryOwner(conversation, admit)
        val live = terminalJobRuns(conversation)
        val ids = live.mapNotNull { it.jobId }.toSet()
        return TerminalJobsSnapshot(live, scan.records.filter { it.jobId !in ids }, scan.skipped, scan.error)
    }
    internal fun terminalJobHistoryPageOnWorker(conversation: ConversationId,
        original: app.daiyu.android.terminal.TerminalJobHistoryReader.Record,
        offset: Long, limit: Int, admit: () -> Boolean): org.json.JSONObject {
        checkTerminalHistoryOwner(conversation, admit)
        require(original.owner == conversation.value)
        check(terminalJobRuns(conversation).none { it.jobId == original.jobId }) { "original_live_job_retained" }
        val page = app.daiyu.android.terminal.TerminalJobHistoryReader(application)
            .page(conversation.value, original, offset, limit)
        checkTerminalHistoryOwner(conversation, admit)
        return page
    }
    private fun checkTerminalHistoryOwner(conversation: ConversationId, admit: () -> Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(admit()) // Pure visit admission, never caller code under the Controller monitor.
        check(synchronized(this) { !closed })
        check(conversationExists(conversation)) { "terminal_history_conversation_unavailable" }
    }

    internal fun inspectTerminalJobOnWorker(conversation: ConversationId, original: TerminalJobSession): org.json.JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(synchronized(this) { original in retainedTerminalJobs && original.plan.conversationId == conversation })
        return original.inspectOnWorker()
    }
    internal fun stopTerminalJobOnWorker(conversation: ConversationId, original: TerminalJobSession,
        admit: () -> Boolean = { false }): org.json.JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(admit()) // Pure one-shot page admission; never invoke caller code under Controller monitor.
        check(synchronized(this) { !closed && original in retainedTerminalJobs && original.plan.conversationId == conversation })
        return original.stopOnWorker()
    }

    internal fun prepareTerminalJobSend(conversation: Conversation?, draft: TerminalDraftSnapshot,
        command: String, mode: String, linux: app.daiyu.android.terminal.LinuxGenerationRef?): ControllerTerminalJobPreparation? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val token = synchronized(this) {
            if (!idleAvailable() || pendingTextState != null || draft.text.isBlank() ||
                draft.owner.conversationId != conversation?.id || retainedTerminalJobs.size >= 64) return null
            reservePreparation()
        }
        try {
            require(mode in setOf("async", "daemon"))
            require(mode != "daemon" || linux == null) { "linux_daemon_not_supported" }
            val (profile, provider) = terminalConfiguration(conversation)
            val context = AssistantRunContext.capture(assistantStore, profile.id, assistantAssets)
            check(assistantStore.read(profile.id) == profile)
            val launch = if (linux == null) TerminalPtyLaunch.Android(listOf("/system/bin/sh"),
                mapOf("HOME" to application.filesDir.path, "TMPDIR" to application.cacheDir.path,
                    "PATH" to "/system/bin:/system/xbin"), application.filesDir)
            else checkNotNull(((application as? ChatApplication)?.terminalProvider?.state?.value as?
                app.daiyu.android.terminal.TerminalApplicationState.Ready)?.application)
                .freezeTerminalLaunchOnWorker(linux, application.cacheDir, application.filesDir)
            token.terminalLaunch = launch
            val plan = TerminalJobPlan(conversation?.id ?: ConversationId(UUID.randomUUID().toString()),
                RunId(UUID.randomUUID().toString()), launch, command, mode)
            val pending = ControllerTerminalJobPreparation(application, conversation, draft, profile, provider, plan)
            synchronized(this) {
                token.terminalJob = pending
                token.browserContext = context
                check(preparation === token && !token.revoked && !closed)
            }
            notifyChanged()
            return pending
        } catch (_: Exception) {
            problem = "固定命令 job 准备失败或后端不支持；未启动，草稿保留"
            finishPreparation(token); notifyChanged(); return null
        }
    }
    internal fun rejectTerminalJob(pending: ControllerTerminalJobPreparation) {
        val token = synchronized(this) {
            preparation?.takeIf { it.terminalJob === pending }?.also { it.revoked = true }
        } ?: return
        token.cancellation.cancel()
        if (!token.submitted) finishPreparation(token)
    }
    internal fun confirmTerminalJob(pending: ControllerTerminalJobPreparation, current: TerminalDraftSnapshot): Boolean {
        val actual = runCatching { terminalConfiguration(pending.conversation) }.getOrNull()
        if (actual != (pending.assistant to pending.provider) || current != pending.draft) {
            rejectTerminalJob(pending); return false
        }
        return synchronized(this) {
            val token = preparation ?: return false
            token.terminalJob === pending && !token.revoked && !token.submitted && !closed &&
                pending.confirm(current, pending.assistant, pending.provider)
        }
    }
    internal fun sendTerminalJob(pending: ControllerTerminalJobPreparation, current: TerminalDraftSnapshot,
        ticket: TextSendTicket, complete: (TextSendResult) -> Unit): Boolean {
        val actual = runCatching { terminalConfiguration(pending.conversation) }.getOrNull()
        if (actual != (pending.assistant to pending.provider) || current != pending.draft) {
            rejectTerminalJob(pending); return false
        }
        val owner = TextSendOwner(current.owner.conversationId, current.owner.editorGeneration, current.owner.draftRevision)
        val token = synchronized(this) {
            val candidate = preparation ?: return false
            if (candidate.terminalJob !== pending || candidate.revoked || candidate.submitted || closed || pendingTextState != null) return false
            val transferred = pending.transfer(current, pending.assistant, pending.provider) ?: return false
            retainedTerminalJobs.add(transferred)
            candidate.terminalJobRun = transferred
            candidate.submitted = true
            candidate.textTicket = ticket
            pendingTextState = TextSendState(ticket, owner, current.text)
            candidate
        }
        try { executor.execute {
            val saved = try { sendPrepared(token, pending.conversation, current.text,
                expectedDraftText = current.text, terminalJobPreparation = pending, terminalJobRun = token.terminalJobRun) }
                catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(owner, saved)
                token.persistenceUnconfirmed -> TextSendResult.Unknown(owner)
                else -> TextSendResult.Rejected(owner)
            }
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result) }
            finishPreparation(token)
            handler.post { try { complete(result) } catch (_: Exception) { } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            finishPreparation(token); return false
        }
        return true
    }

    /** Snapshot outside the admission monitor: SQLite/settings reads are never under it. */
    internal fun terminalConfiguration(conversation: Conversation?): Pair<AssistantProfile, ProviderSnapshot> {
        val id = if (conversation == null) assistantStore.selectedId.value else database.assistantId(conversation.id)
        val profile = checkNotNull(assistantStore.read(checkNotNull(id)))
        return profile.copy(enabledSkillIds = profile.enabledSkillIds.toList()) to
            ProviderSnapshot.capture(settings.load().also { it.validate() })
    }

    /** Non-starting worker entry. Linux must already be installed, trusted AND selected. */
    internal fun prepareTerminalSend(conversation: Conversation?, draft: TerminalDraftSnapshot,
        exactCommand: String, linux: app.daiyu.android.terminal.LinuxGenerationRef?): ControllerTerminalPreparation? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val token = synchronized(this) {
            if (!idleAvailable() || pendingTextState != null || draft.text.isBlank() ||
                draft.owner.conversationId != conversation?.id) return null
            reservePreparation()
        }
        var launch: TerminalPtyLaunch? = null
        var pending: ControllerTerminalPreparation? = null
        try {
            val (profile, provider) = terminalConfiguration(conversation)
            val context = AssistantRunContext.capture(assistantStore, profile.id, assistantAssets)
            check(assistantStore.read(profile.id) == profile) { "assistant_profile_changed" }
            launch = if (linux == null) TerminalPtyLaunch.Android(listOf("/system/bin/sh"),
                mapOf("HOME" to application.filesDir.path, "TMPDIR" to application.cacheDir.path,
                    "PATH" to "/system/bin:/system/xbin", "TERM" to "xterm-256color"), application.filesDir)
            else {
                val host = checkNotNull(((application as? ChatApplication)?.terminalProvider?.state?.value as?
                    app.daiyu.android.terminal.TerminalApplicationState.Ready)?.application)
                host.freezeTerminalLaunchOnWorker(linux, application.cacheDir, application.filesDir)
            }
            token.terminalLaunch = launch
            val plan = TerminalChatPlan.create(conversation?.id ?: ConversationId(UUID.randomUUID().toString()),
                exactCommand, launch, File(application.filesDir, "terminal-chat-journal"))
            pending = ControllerTerminalPreparation.prepare(application, conversation, draft, profile, provider, plan)
            synchronized(this) {
                token.terminal = pending // retain even if cancellation won while freezing
                token.browserContext = context
                check(preparation === token && !token.revoked && !closed)
            }
            notifyChanged()
            return pending
        } catch (_: Exception) {
            problem = "终端准备失败或已取消；未启动、未发送，草稿保留"
            finishPreparation(token)
            notifyChanged()
            return null
        }
    }

    internal fun rejectTerminal(pending: ControllerTerminalPreparation) {
        val token = synchronized(this) {
            preparation?.takeIf { it.terminal === pending }?.also { it.revoked = true }
        } ?: return
        token.cancellation.cancel()
        if (!token.submitted) finishPreparation(token)
    }

    internal fun confirmTerminal(pending: ControllerTerminalPreparation, displayed: TerminalRunPreview,
        current: TerminalDraftSnapshot, assistant: AssistantProfile, provider: ProviderSnapshot): Boolean {
        val actual = runCatching { terminalConfiguration(pending.conversation) }.getOrNull()
        if (actual != (assistant to provider) || actual != (pending.assistant to pending.provider) || current != pending.draft) {
            rejectTerminal(pending)
            return false
        }
        return synchronized(this) {
            val token = preparation ?: return false
            token.terminal === pending && !token.revoked && !token.submitted && !closed &&
                pending.confirm(displayed, current, assistant, provider)
        }
    }

    /** Explicit send, separate from confirm. Reuses the text ticket and durable receipt path. */
    internal fun sendTerminal(pending: ControllerTerminalPreparation, displayed: TerminalRunPreview,
        current: TerminalDraftSnapshot, assistant: AssistantProfile, provider: ProviderSnapshot,
        ticket: TextSendTicket, complete: (TextSendResult) -> Unit): Boolean {
        val actual = runCatching { terminalConfiguration(pending.conversation) }.getOrNull()
        if (actual != (assistant to provider) || actual != (pending.assistant to pending.provider) || current != pending.draft) {
            rejectTerminal(pending)
            return false
        }
        val owner = TextSendOwner(current.owner.conversationId, current.owner.editorGeneration, current.owner.draftRevision)
        val token = synchronized(this) {
            val candidate = preparation ?: return false
            if (candidate.terminal !== pending || candidate.revoked || candidate.submitted || closed || pendingTextState != null) return false
            val transferred = pending.takeConfirmed(displayed, current, assistant, provider) ?: return false
            candidate.terminalRun = transferred
            candidate.submitted = true
            candidate.textTicket = ticket
            pendingTextState = TextSendState(ticket, owner, current.text)
            candidate
        }
        try { executor.execute {
            val saved = try { sendPrepared(token, pending.conversation, current.text,
                expectedDraftText = current.text, terminalPreparation = pending, terminalRun = token.terminalRun) }
                catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(owner, saved)
                token.persistenceUnconfirmed -> TextSendResult.Unknown(owner)
                else -> TextSendResult.Rejected(owner)
            }
            synchronized(this) {
                if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result)
            }
            finishPreparation(token)
            handler.post { try { complete(result) } catch (_: Exception) { } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            finishPreparation(token)
            return false
        }
        return true
    }

    /** Worker-only snapshot. Merely opening approval never creates a browser or calls a model. */
    internal fun prepareMcpSend(conversation: Conversation?, editor: TextSendOwner, text: String,
        serverId: String, tool: String, arguments: String): ControllerMcpPreparation? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "mcp_worker_required" }
        val token = synchronized(this) {
            if (!idleAvailable() || text.isBlank() || editor.conversationId != conversation?.id ||
                editor.editorGeneration < 0 || editor.draftRevision < 0) return null
            reservePreparation()
        }
        var pending: ControllerMcpPreparation? = null
        try {
            val binding = if (conversation == null) token.assistantId else database.assistantId(conversation.id)
            val profile = assistantStore.read(checkNotNull(binding)) ?: error("mcp_assistant_missing")
            val context = AssistantRunContext.capture(assistantStore, profile.id, assistantAssets)
            check(assistantStore.read(profile.id) == profile)
            val config = settings.load().also { it.validate() }
            val store = app.daiyu.android.mcp.McpSettingsStore(application)
            val frozen = store.snapshot()
            val selection = app.daiyu.android.mcp.McpSelection(serverId, tool, arguments)
            val scope = app.daiyu.android.mcp.McpRunScope(frozen, listOf(selection),
                conversation?.id ?: ConversationId(UUID.randomUUID().toString()), RunId(UUID.randomUUID().toString()),
                configurationCurrent = { store.isCurrent(frozen) }, transport = mcpTransport)
            pending = ControllerMcpPreparation(conversation, editor, text, profile, ProviderSnapshot.capture(config),
                selection, scope, MessageId(UUID.randomUUID().toString()))
            synchronized(this) {
                check(preparation === token && !closed && !token.revoked)
                token.mcp = pending
                token.browserContext = context
            }
            notifyChanged()
            return pending
        } catch (_: Exception) {
            pending?.cancel()
            problem = "MCP 准备失败或配置已变化；草稿保留，未连接服务器"
            finishPreparation(token); notifyChanged(); return null
        }
    }
    internal fun rejectMcp(pending: ControllerMcpPreparation) {
        val token = synchronized(this) {
            preparation?.takeIf { it.mcp === pending }?.also { it.revoked = true }
        } ?: return
        token.cancellation.cancel(); pending.cancel()
        if (!token.submitted) finishPreparation(token)
    }
    internal fun sendMcp(pending: ControllerMcpPreparation, owner: TextSendOwner, text: String,
        ticket: TextSendTicket, complete: (TextSendResult) -> Unit): Boolean {
        val token = synchronized(this) {
            val candidate = preparation ?: return false
            if (candidate.mcp !== pending || candidate.submitted || candidate.revoked || closed ||
                !pending.confirmed || owner != pending.editor || text != pending.text || pendingTextState != null) return false
            candidate.submitted = true; candidate.textTicket = ticket
            pendingTextState = TextSendState(ticket, owner, text)
            candidate
        }
        try { executor.execute {
            val saved = try {
                val binding = if (pending.conversation == null) token.assistantId else database.assistantId(pending.conversation.id)
                if (preparationCancelled(token) || !pending.confirmed || binding != pending.assistant.id ||
                    assistantStore.read(pending.assistant.id) != pending.assistant ||
                    settings.load() != pending.provider.asModelSettings()) null
                else sendPrepared(token, pending.conversation, text, expectedDraftText = text, mcpPreparation = pending)
            } catch (_: Exception) { null }
            val result = when {
                saved != null -> TextSendResult.Accepted(owner, saved)
                token.persistenceUnconfirmed -> TextSendResult.Unknown(owner)
                else -> TextSendResult.Rejected(owner)
            }
            synchronized(this) {
                if (pendingTextState?.ticket === ticket) pendingTextState = pendingTextState?.copy(result = result)
            }
            finishPreparation(token)
            handler.post { runCatching { complete(result) } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (pendingTextState?.ticket === ticket) pendingTextState = null }
            rejectMcp(pending); finishPreparation(token); return false
        }
        return true
    }

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
            val context = AssistantRunContext.capture(assistantStore, profile.id, assistantAssets)
            check(assistantStore.read(profile.id) == profile) { "assistant_profile_changed" }
            val config = settings.load().also { it.validate() }
            val browserSettings = browserSettings.snapshot()
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

    /** Pure trusted UI plan; no directories, hosts, DB rows or model creation here. */
    internal fun prepareBrowserChild(pending: ControllerBrowserPreparation, prompt: String,
                                    actions: Set<String>, writes: List<String>): ControllerChildBrowserPlan? {
        synchronized(this) {
            val token = preparation ?: return null
            if (closed || token.revoked || token.submitted || token.browser !== pending ||
                pending.state != ControllerBrowserPreparation.State.PREVIEW || pending.childPlan != null ||
                browserChildren.count { !it.drained } >= 8) return null
        }
        val plan = try { ControllerChildBrowserPlan.prepare(pending, prompt, actions, writes, browserDownloads) }
            catch (_: Exception) { return null }
        val attached = synchronized(this) {
            val token = preparation
            if (closed || token == null || token.revoked || token.submitted || token.browser !== pending) false
            else pending.attachChild(plan).also { if (it) browserChildren.add(plan) }
        }
        if (!attached) { plan.close(); return null }
        notifyChanged()
        return plan
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
                // The queued ticket may have been cancelled before this worker started. Fence
                // BEFORE mkdir as well as native construction; preview/confirmation are pure.
                if (preparationCancelled(token) || pending.isCancelled ||
                    pending.state != ControllerBrowserPreparation.State.CONFIRMED) null else {
                    val root = ControllerBrowserPreparation.preparePrivateRootOnWorker(application)
                    val binding = if (preparationCancelled(token) || pending.isCancelled) null
                        else pending.openOnWorker(application, root, onDownloadCompleted =
                            browserDownloads.recorder(pending.identity.conversationId.value, pending.preview.grant.owner))
                    if (binding == null) null else sendPrepared(token, pending.conversation, pending.text,
                        expectedDraftText = pending.text, browserPreparation = pending, browserBinding = binding)
                }
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
     * provider is the frozen MAIN configuration; route is the independent actual image capability.
     * An explicitly enabled auxiliary route must be captured on a worker, never by upgrading main.
     * expectedDraftText is the exact persisted composer/conversation text at this revision;
     * it is compared atomically before accepting, never inferred from image labels or URIs.
     * Null means busy/closed/invalid input, no task and no callback. Cancellation is via handle.
     */
    internal fun sendImages(conversation:Conversation?, owner:ImageAttachmentOwner, draftRevision:Long,
        parts:List<ImageSendPart>, provider:ProviderSnapshot, expectedDraftText:String,
        route: ImageRoute = ImageRoute(provider, null),
        identity: ImageSendIdentity? = null,
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
                attempt=ControllerImageAttempt(owner,draftRevision,snapshot,provider,handle,imageJournal,expectedDraftText,
                    route.actual, route.auxiliaryGeneration, identity)
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
            imageReceiptHandler.post {try {complete(result)} catch (_:Exception) {}}
        } } catch (_:java.util.concurrent.RejectedExecutionException) {
            finishPreparation(token);return null
        }
        return handle
    }
    /** Dedicated read-only receipt lane. Does not reserve preparation, read settings, create
     * a model/identity, publish assets, resume a runner or call sendPrepared/sendImages.
     */
    internal fun readImageReceipt(frozen: FrozenImageSend): ImageSendResult = try {
        imageRead {
            ControllerImageAttempt(frozen.owner, frozen.revision, frozen.parts, null,
                ImageSendHandle(frozen.owner, frozen.revision), imageJournal, frozen.text,
                identity = frozen.identity).readReceipt(database)
        }
    } catch (_: Exception) {
        ImageSendResult.Unknown(frozen.owner, frozen.revision, frozen.identity.userId, frozen.identity.assistantId)
    }

    /** Exact local receipt acknowledgement, never a request or SQLite mutation. */
    internal fun confirmImageReceipt(frozen: FrozenImageSend, receipt: ImageSendResult.Accepted): Boolean = try {
        ControllerImageAttempt(frozen.owner, frozen.revision, frozen.parts, null,
            ImageSendHandle(frozen.owner, frozen.revision), imageJournal, frozen.text,
            identity = frozen.identity).confirmReceipt(receipt)
    } catch (_: Exception) { false }

    private fun sendPrepared(token:Preparation, conversation:Conversation?,text:String,
        withChildren:Boolean = false, imageAttempt:ControllerImageAttempt?=null,
        shizukuPreparation:ControllerShizukuPreparation?=null,
        shizukuAuthorization:app.daiyu.android.domain.tools.TaskAuthorization?=null,
        expectedDraftText:String?=null,
        mcpPreparation:ControllerMcpPreparation?=null,
        browserPreparation:ControllerBrowserPreparation?=null,
        browserBinding:CatalogBrowserBinding?=null,
        generation:GenerationEntry?=null,
        terminalPreparation:ControllerTerminalPreparation?=null,
        terminalRun:TerminalConfirmedRun?=null,
        terminalJobPreparation:ControllerTerminalJobPreparation?=null,
        terminalJobRun:TerminalJobSession?=null,
        virtualDisplay:ControllerVirtualDisplayEntry?=null,
        gui:ControllerGuiEntry?=null,
        videoAttempt:ControllerVideoAttempt?=null,
        platformAction:ControllerPlatformActionPreparation?=null):Conversation? {
        require(platformAction == null || (platformAction.submitted && !platformAction.cancelled &&
            platformAction.scope != null && imageAttempt == null && shizukuPreparation == null &&
            shizukuAuthorization == null && mcpPreparation == null && browserPreparation == null &&
            browserBinding == null && generation == null && terminalRun == null && terminalJobRun == null &&
            virtualDisplay == null && gui == null && videoAttempt == null && !withChildren))
        require(gui == null || (mcpPreparation == null && virtualDisplay == null && generation == null && shizukuPreparation == null &&
            shizukuAuthorization == null && browserPreparation == null && browserBinding == null &&
            imageAttempt == null && terminalRun == null && terminalJobRun == null && videoAttempt == null && !withChildren &&
            gui.session != null && gui.delivery?.session === gui.session && !gui.revoked))
        require(mcpPreparation == null || (imageAttempt == null && shizukuPreparation == null &&
            browserPreparation == null && browserBinding == null && generation == null &&
            gui == null && terminalJobRun == null && shizukuAuthorization == null && terminalRun == null && virtualDisplay == null && videoAttempt == null && !withChildren && mcpPreparation.confirmed))
        require((terminalPreparation == null) == (terminalRun == null))
        require((terminalJobPreparation == null) == (terminalJobRun == null))
        require(terminalJobRun == null || (mcpPreparation == null && terminalRun == null && imageAttempt == null &&
            shizukuPreparation == null && shizukuAuthorization == null && browserPreparation == null &&
            browserBinding == null && generation == null && virtualDisplay == null && gui == null && videoAttempt == null && !withChildren &&
            terminalJobRun.plan === terminalJobPreparation?.plan))
        if (terminalJobPreparation != null && runCatching { terminalConfiguration(conversation) }.getOrNull() !=
            (terminalJobPreparation.assistant to terminalJobPreparation.provider)) return null
        require(terminalRun == null || (imageAttempt == null && shizukuPreparation == null &&
            browserPreparation == null && generation == null && virtualDisplay == null && videoAttempt == null && !withChildren &&
            terminalRun.preview === terminalPreparation?.preview &&
            terminalRun.authorization === terminalRun.binding.authorization))
        if (terminalPreparation != null && runCatching { terminalConfiguration(conversation) }.getOrNull() !=
            (terminalPreparation.assistant to terminalPreparation.provider)) return null
        require(virtualDisplay == null || (generation == null && shizukuPreparation == null &&
            browserBinding == null && imageAttempt == null && terminalRun == null && terminalJobRun == null && videoAttempt == null && !withChildren))
        require(videoAttempt == null || (generation == null && shizukuPreparation == null && shizukuAuthorization == null &&
            browserPreparation == null && browserBinding == null && imageAttempt == null &&
            terminalRun == null && virtualDisplay == null && !withChildren))
        require(shizukuPreparation==null || (imageAttempt==null && shizukuAuthorization!=null))
        require(generation == null || (shizukuPreparation == null && browserBinding == null && imageAttempt == null))
        if(preparationCancelled(token)) return null
        // All callers, including direct Controller sends, recheck the ORIGINAL location run
        // before draft/history/model preparation or SQLite acceptance. No preference reload here.
        if (gui != null && gui.taskSurface.ready(TaskSurface.FOREGROUND) == null) return null
        if (virtualDisplay != null && virtualDisplay.taskSurface.ready(TaskSurface.BACKGROUND)
                ?.let { it.selfTarget == null } != true) return null
        // Independent platform capability and destination consents are frozen once for this run.
        // The exact policy and selection below reach the same catalog/resource owner and drain.
        val platformSnapshot = platformSettings.freeze()
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
            AssistantRunContext.capture(assistantStore,binding,assistantAssets)
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
                val delegate=sessionModel?.invoke(provider,assistantContext,SessionGatewayContext(checkNotNull(conversation).id))
                    ?: assistantModel?.invoke(provider,assistantContext) ?: model(provider.asModelSettings())
                messageImages.wrap(delegate,provider)
            }
        } catch (_:Exception) {
            problem="子模型配置未绑定、已变更或不支持；草稿保留，未切换到主模型";notifyChanged();return null
        }
        val interactiveSnapshot = interactiveMode.snapshot()
        val config=try {(mcpPreparation?.provider?.asModelSettings() ?: terminalJobPreparation?.provider?.asModelSettings() ?: terminalPreparation?.provider?.asModelSettings() ?: browserPreparation?.provider?.asModelSettings() ?: settings.load()).also {it.validate()}} catch (_:CredentialUnavailableException) {
            problem="已保存密钥不可用；请到模型设置重新输入，未发起网络请求";notifyChanged();return null
        } catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return null}
        val provider=try {
            val selected=mcpPreparation?.provider ?: terminalJobPreparation?.provider ?: terminalPreparation?.provider ?: browserPreparation?.provider ?: imageAttempt?.provider ?: synchronized(this) {imageProvider}
            if(imageAttempt!=null) require(selected?.asModelSettings()==config) {"stale_image_provider"}
            selected?.takeIf {it.asModelSettings()==config} ?: ProviderSnapshot.capture(config)
        } catch (_:Exception) {problem="图片模型配置已变化；草稿保留，未发起网络请求";notifyChanged();return null}
        // One immutable request policy, read once before context/model preparation. Changing
        // the UI/store during streaming, pause/continue or tool rounds affects only the next run.
        val frozenReasoning = try { reasoningPreference.snapshot(conversation?.id?.value) }
        catch (_: Exception) { problem="思考偏好无效或读取失败；请重新保存，草稿保留";notifyChanged();return null }
        val requestProvider = try { app.daiyu.android.model.ReasoningRequestPolicy.freeze(provider, frozenReasoning) }
        catch (failure: Exception) {
            problem=if (failure is IllegalArgumentException) failure.message ?: "当前模型协议不支持此思考配置；草稿保留"
                else "推理参数格式无效；Responses 使用 reasoning 对象，Messages 仅支持 enabled/disabled thinking；草稿保留"
            notifyChanged();return null
        }
        val requestConfig = requestProvider.asModelSettings()
        // sendPrepared runs on the preparation worker. Freeze once, AFTER the exact provider;
        // no settings IO on pause/continue/tool rounds, and no fallback on a corrupt/fenced store.
        val frozenReconnect = try {
            app.daiyu.android.model.reconnect.FrozenReconnectAdapter.freeze(reconnectSettings.snapshot(), requestProvider)
        } catch (_: Exception) {
            problem="错误重连配置无效或写入未确认；请重新保存，草稿保留，未启动聊天模型请求"
            notifyChanged(); return null
        }
        val conversationId=platformAction?.conversationId ?: mcpPreparation?.scope?.conversationId ?: gui?.conversationId ?: terminalJobPreparation?.identity?.conversationId ?: virtualDisplay?.conversationId ?: terminalRun?.preview?.identity?.conversationId ?: videoAttempt?.conversationId ?: generation?.identity?.conversationId ?: browserPreparation?.identity?.conversationId ?: shizukuPreparation?.conversationId ?: conversation?.id ?: imageAttempt?.let {ConversationId(it.owner.conversationId)}
            ?: ConversationId(UUID.randomUUID().toString())
        val run=platformAction?.runId ?: mcpPreparation?.scope?.runId ?: gui?.runId ?: terminalJobPreparation?.identity?.runId ?: virtualDisplay?.runId ?: terminalRun?.preview?.identity?.runId ?: videoAttempt?.runId ?: generation?.identity?.runId ?: browserPreparation?.identity?.runId ?: shizukuPreparation?.runId ?: imageAttempt?.runId ?: RunId(UUID.randomUUID().toString())
        if (conversation == null) try { reasoningPreference.retainAccepted(conversationId.value, frozenReasoning) }
        catch (_: Exception) { problem="会话思考偏好保存未确认；草稿保留，未发起请求";notifyChanged();return null }
        // One acceptance candidate, including its media reconstruction and all run owners.
        // This value must not change during preflight, streaming, or receipt reconciliation.
        val acceptedAt = System.currentTimeMillis()
        var user=try {imageAttempt?.prepare(imageAttachments.store,messageImageAssets,acceptedAt) ?:
            Message(videoAttempt?.userId ?: MessageId(UUID.randomUUID().toString()),conversationId,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED,createdAt=acceptedAt)
        } catch (_:Exception) {problem="图片冻结失败或模型不支持；草稿保留，未发起网络请求";notifyChanged();return null}
        val assistant=Message(mcpPreparation?.assistantMessageId ?: gui?.assistantId ?: terminalJobPreparation?.identity?.assistantMessageId ?: virtualDisplay?.assistantId ?: terminalRun?.preview?.identity?.assistantMessageId ?: videoAttempt?.assistantId ?: generation?.identity?.assistantMessageId ?: browserPreparation?.identity?.assistantMessageId ?: imageAttempt?.assistantId ?: MessageId(UUID.randomUUID().toString()),conversationId,run,"assistant",emptyList(),RunStatus.RUNNING,createdAt=acceptedAt)
        val physicalVideoHistory = try { ControllerVideoHistory(database).capture(conversationId) }
        catch (_: Exception) {
            problem="原视频来源无法确认；草稿保留，未启动模型请求";notifyChanged();return null
        }
        if (physicalVideoHistory.inputs.isNotEmpty() && (withChildren || imageAttempt != null ||
                generation != null || terminalRun != null || terminalJobRun != null || virtualDisplay != null || gui != null ||
                shizukuPreparation != null || shizukuAuthorization != null ||
                mcpPreparation != null || browserPreparation != null || browserBinding != null)) {
            problem="当前专用批准不能与原视频历史混合发送；保留草稿，请使用原视频文本发送入口"
            notifyChanged(); return null
        }
        if ((videoAttempt != null || physicalVideoHistory.inputs.isNotEmpty()) &&
            frozenReasoning != app.daiyu.android.delegation.ReasoningEffort.DEFAULT) {
            problem="原视频批准尚未接通独立思考偏好；请选择默认，草稿保留，未发起请求"
            notifyChanged();return null
        }
        var videoPrepared: PreparedControllerVideoSend? = null
        var videoPlan: ControllerVideoModelPlan? = null
        val video = if (videoAttempt != null || physicalVideoHistory.inputs.isNotEmpty()) try {
            token.videoRequired = true
            check(token.videoRouteEpoch == videoRouteEpoch.get() && !preparationCancelled(token))
            val epoch = videoConfigurationEpoch.get()
            val proof = ModelCatalogService(settings).fetchSavedVideoEvidence(provider, epoch,
                videoConfigurationEpoch::get) { preparationCancelled(token) || token.videoRouteEpoch != videoRouteEpoch.get() }
            val ready = CountDownLatch(1)
            var created: ControllerVideoSession? = null
            handler.post {
                try {
                    val ui = checkNotNull(videoUi)
                    val editor = checkNotNull(token.videoEditor) { "video_requires_editor_owner" }
                    check(!preparationCancelled(token) && ui.editor() == editor)
                    val frozen = if (videoAttempt != null)
                        app.daiyu.android.model.videos.FrozenVideoApprovalRequest.forDraft(videoAttempt.request,
                            run.value, run.value, provider, epoch, MessageHistory.expand(physicalVideoHistory.rows), physicalVideoHistory.inputs)
                    else app.daiyu.android.model.videos.FrozenVideoApprovalRequest.forText(conversationId.value,
                        run.value, run.value, editor.revision, text, provider, epoch,
                        MessageHistory.expand(physicalVideoHistory.rows), physicalVideoHistory.inputs)
                    created = ControllerVideoSession(handler, database, provider, epoch, videoRunEpoch.incrementAndGet(), videoConfigurationEpoch::get,
                        settings::load, ui, editor, physicalVideoHistory, assistant, frozen,
                        isParentCurrent = { synchronized(this) {
                            !closed && token.videoRouteEpoch == videoRouteEpoch.get() && videoSession === token.video &&
                                (preparation === token && !token.revoked || resources?.let {
                                    it.initial.runId == run && !it.fenced
                                } == true)
                        } }, currentAssistant = { synchronized(this) { active } }, cancelTransport = {
                            token.cancellation.cancel()
                            val owned = synchronized(this) { resources?.takeIf { it.initial.runId == run } }
                            owned?.let(::stopRun)
                        }).also { token.video = it; videoSession = it }
                } catch (_: Exception) { } finally { ready.countDown() }
            }
            while (!ready.await(50, TimeUnit.MILLISECONDS)) check(!preparationCancelled(token))
            val session = checkNotNull(created)
            val capability = session.authorize(proof)
            videoPrepared = videoAttempt?.prepare(videoAttachments.store, provider, capability, acceptedAt) { session.isRevoked() }
            videoPrepared?.let {
                check(it.user == user && it.assistant == assistant) { "video_candidate_changed" }
                user = it.user
            }
            videoPlan = if (videoPrepared != null) messageVideos.preflight(physicalVideoHistory, provider,
                capability, videoPrepared) { session.isRevoked() }
            else messageVideos.preflightText(physicalVideoHistory, provider, capability, user) { session.isRevoked() }
            session to capability
        } catch (_: Exception) {
            token.video?.revoke()
            problem="原视频目录证据、运行批准或原件校验失败；草稿保留，未启动模型请求";notifyChanged();return null
        } else null
        val auxiliaryEpoch = independentModels.generation()
        val auxiliaryCurrent = { video != null || independentModels.isCurrent(auxiliaryEpoch) }
        val auxiliarySelection = if (video != null) app.daiyu.android.model.auxiliary.AuxiliaryModelSelection(false, "", "")
            else independentModels.visionSelection()
        val auxiliaryFrozen = try {
            if (video != null || !auxiliarySelection.enabled) null else independentModels.freeze(IndependentModelSettings.Slot.VISION).also {
                auxiliarySelection.resolve(listOf(independentModels.visionCandidate(it)))
            }
        } catch (_: Exception) {
            problem="独立辅助视觉配置不可用或图片能力未知；草稿保留，不回退主模型";notifyChanged();return null
        }
        if (imageAttempt != null && (imageAttempt.auxiliaryGeneration != auxiliaryEpoch.takeIf { auxiliaryFrozen != null } ||
                !auxiliaryCurrent())) {
            problem="图片路由已变化；草稿保留，请重新发送";notifyChanged();return null
        }
        val preparedContext = try {
            // All sends, including the very first message, share the same frozen policy/budget.
            val coordinator = contextPreparation
            val summary = checkNotNull(runtimeStore).summaryPreparation(conversationId, run, assistant.id, token.cancellation)
            token.summaryPreparation = summary
            // Budget the exact confirmed GUI model input in the ORIGINAL preparation, including
            // compression trigger and final admission. Only this projection enters Runner; user,
            // draft CAS and database.send stay unchanged. No re-observe or second resource owner.
            val modelUser = if (gui == null) user else app.daiyu.android.gui.GuiConfirmedModelPort.project(
                user, checkNotNull(gui.delivery))
            val frozen = coordinator.freeze(conversationId,listOf(modelUser),token.cancellation, summaryUsage = summary.usagePort)
            if (video == null) coordinator.prepare(frozen) else {
                // A video run must not silently replace physical/retained history with a text summary.
                // Keep the same policy/budget and acceptance revision, but send every physical row.
                val estimate = app.daiyu.android.domain.context.estimateTokens(physicalVideoHistory.rows + user)
                val budget = frozen.policy.sendBudgetTokens
                if (budget != null && !frozen.policy.fitsSendBudget(estimate))
                    throw app.daiyu.android.context.ContextCapacityExceededException(estimate, budget)
                app.daiyu.android.context.PreparedSendContext(frozen,
                    app.daiyu.android.context.PreparedContext(physicalVideoHistory.rows,
                        app.daiyu.android.domain.context.estimateTokens(physicalVideoHistory.rows), null),
                    app.daiyu.android.domain.context.ContextRevision.digest(contextService.displayHistory(conversationId)))
            }
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
        var auxiliaryPort: ControllerAuxiliaryVision? = null
        val acceptedTitle = java.util.concurrent.atomic.AtomicReference<String?>()
        val pendingTitle = java.util.concurrent.atomic.AtomicReference<app.daiyu.android.title.TitleJob?>()
        val preparedModel=try {
            if (video != null) messageVideos.wrap(provider, video.second, conversationId, assistant.id,
                checkNotNull(videoPlan), assistantContext.systemPrompt, { video.first.isRevoked() },
                checkNotNull(video.first.post), onFrozenPlan = video.first::onFrozenPlan,
                backgroundResume = frozenReconnect.responsesBackground) else {
            // Capability preflight is against the actual image route, not the text/main provider.
            val imageHistory=(contextService.displayHistory(conversationId) + preparedContext.messages)
                .distinctBy { it.id }
            val summaries = preparedContext.messages.filter { row ->
                preparedContext.archiveId != null && row.id.value == "summary-${preparedContext.archiveId}" &&
                    row.runId.value == "summary-${preparedContext.archiveId}"
            }
            val authorization = if (auxiliaryFrozen == null) null else
                ControllerVisionAuthorization(database, assistant, summaries) {
                    auxiliaryCurrent() && !closed
                }
            // Authorize raw owners/segments even before LOCAL auxiliary preflight image reads.
            // The candidate capability is not installed in the post-accept leaf callback.
            authorization?.preflight(imageHistory, user)
            val plan=messageImages.freeze(imageHistory,auxiliaryFrozen?.provider ?: provider,user) {
                preparationCancelled(token) || !auxiliaryCurrent() ||
                    imageAttempt?.handle?.isCancelled()==true || shizukuPreparation?.isCancelled()==true
            }
            val assembledDelegate=sessionModel?.invoke(requestProvider,assistantContext,SessionGatewayContext(conversationId))
                ?: assistantModel?.invoke(requestProvider,assistantContext)
                ?: if(plan.hasImages && auxiliaryFrozen == null) imageModel(requestProvider) else model(requestConfig)
            // Auxiliary vision owns a Unit-sink lifecycle and cannot acknowledge background batches.
            val background = frozenReconnect.responsesBackground.takeIf { auxiliaryFrozen == null }
            val delegate = if (background != null && assembledDelegate is app.daiyu.android.model.ResponsesModel)
                assembledDelegate.withBackgroundResume(background) else assembledDelegate
            if (auxiliaryFrozen == null) messageImages.wrap(delegate,provider,conversationId)
            else {
                ControllerAuxiliaryVision.create(delegate, auxiliarySelection,
                    listOf(independentModels.visionCandidate(auxiliaryFrozen)), messageImageResolver(conversationId),
                    checkNotNull(authorization)::authorize,
                    beginUsage = { checkNotNull(runtimeStore).auxiliaryUsage(assistant) }).also { auxiliaryPort = it }
            }
            }
        } catch (_:Exception) {
            problem="图片历史读取、能力检查或模型初始化失败；草稿保留，未启动聊天模型请求";notifyChanged();return null
        }
        if(preparationCancelled(token)) { auxiliaryPort?.close(); return null }
        // The observer must be OUTSIDE the fully assembled video/image/auxiliary port. It is
        // diagnostic only: no open/resume/repost or tool replay; only approved background sessions resume.
        // Both assignment and callback reads of this original lifetime are under our monitor.
        var reconnectResources: RunResources? = null
        var reconnectNotice: String? = null
        fun postReconnectNotice(message: String?) {
            handler.post { synchronized(this) {
                val original = reconnectResources
                if (original != null && resources === original && !original.fenced && !closed &&
                    runner === original.runner && active?.id == assistant.id &&
                    active?.runId == run && active?.conversationId == conversationId) {
                    if (message != null || problem == reconnectNotice) problem = message
                    reconnectNotice = message
                    notifyChanged()
                }
            } }
        }
        val observedModel = frozenReconnect.observeFailures(preparedModel, ::postReconnectNotice)
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
            val devices = RunDeviceResources(application, resultPolicy = platformSnapshot.resultPolicy)
            preparingDevices = devices
            val registry = AndroidToolRegistry.create(devices, contextTools, enableUserBarrier=interactiveSnapshot.enabled && gui == null)
            val capabilities = SessionCapabilities(gui?.sessionId ?: terminalJobPreparation?.identity?.sessionId ?: terminalRun?.preview?.identity?.sessionId ?: run.value,
                (if (virtualDisplay == null) AndroidToolRegistry.capabilitiesFor(if (platformSnapshot.allDeviceTools) ReadOnlyTool.entries.toSet() else config.enabledTools(),
                    platformAllowed = platformSnapshot.platformAllowed) else emptySet()) + contextTools.map { it.definition.capability } +
                    setOf(SkillRunSession.READ, SkillRunSession.RESOURCE) +
                    (if (assistantContext.memoryReadPort == null) emptySet() else setOf(ScopedAssistantMemoryTools.READ)) +
                    (if (interactiveSnapshot.enabled && gui == null) setOf(AndroidToolRegistry.ASK_USER_CAPABILITY) else emptySet()) +
                    (if (children == null) emptySet() else setOf(RunChildModels.CAPABILITY,RunChildTasks.CAPABILITY)) +
                    (if (platformAction == null) emptySet() else setOf(platformAction.action.kind.capability)) +
                    (if (shizukuPreparation == null) emptySet() else setOf(app.daiyu.android.shizuku.RunShizukuCommands.CAPABILITY)) +
                    (if (browserBinding == null) emptySet() else setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)) +
                    (if (mcpPreparation == null) emptySet() else setOf(app.daiyu.android.mcp.McpRunScope.CAPABILITY)) +
                    (if (generation == null) emptySet() else setOf(RunGenerationTools.CAPABILITY)) +
                    (if (terminalRun == null) emptySet() else setOf(RunTerminalTools.CAPABILITY)) +
                    (if (terminalJobRun == null) emptySet() else setOf(TerminalJobSession.CAPABILITY)) +
                    (if (virtualDisplay == null) emptySet() else setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY)) +
                    (if (gui == null) emptySet() else setOf(app.daiyu.android.gui.GuiScreenToolFactory.CAPABILITY)))
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
                backgroundFactory, memoryContext=assistantContext, mcp=mcpPreparation?.scope,
                platformAction=platformAction?.scope,
                onCatalogReady = browserPreparation?.childPlan?.let { plan -> { realOwner: app.daiyu.android.domain.runtime.RunExecutionOwner,
                    grants: SessionCapabilities, task: app.daiyu.android.domain.tools.TaskAuthorization ->
                    plan.launch(application, realOwner, grants, task, toolLedger,
                        checkNotNull(runtimeStore).childTasks, browserChildCapacity,
                        model = { sessionModel?.invoke(provider, assistantContext, SessionGatewayContext(conversationId))
                            ?: assistantModel?.invoke(provider, assistantContext) ?: model(config) },
                        onChanged = ::browserChildrenChanged)
                } },
                authorization=platformAction?.scope?.authorization ?: mcpPreparation?.scope?.authorization ?: terminalJobRun?.authorization ?: terminalRun?.authorization ?: shizukuAuthorization ?: gui?.let {
                    app.daiyu.android.domain.tools.TaskAuthorization(capabilities.sessionId, run.value,
                        checkNotNull(it.delivery).writes)
                } ?: browserPreparation?.childPlan?.parentAuthorization ?: browserBinding?.let {
                    app.daiyu.android.domain.tools.TaskAuthorization(capabilities.sessionId, run.value, it.requiredWrites)
                }, shizuku=shizuku, browser=browserBinding, terminal=terminalRun?.binding, gui=gui?.session, terminalJobs=terminalJobRun,
                authorizationFactory = if (video != null) { realOwner ->
                    video.first.bindOwner(realOwner)
                    app.daiyu.android.domain.tools.TaskAuthorization(capabilities.sessionId, run.value,
                        browserBinding?.requiredWrites.orEmpty())
                } else if (generation == null) null else { realOwner ->
                    synchronized(this) {
                        check(generationEntry === generation)
                        val session = checkNotNull(generation.session)
                        if (closed || session.cleanupRequested) {
                            // This real Runner owner can validate a late durable receipt, never paid work.
                            session.validateOwnerForReceipt(realOwner, session.token.preview.editor, generation.identity)
                            notifyChanged()
                            error("generation_session_stopped")
                        }
                        session.bindOwner(realOwner, session.token.preview.editor, generation.identity,
                            capabilities.sessionId).also { notifyChanged() }
                    }
                },
                virtualDisplaySessionFactory = if (virtualDisplay == null) null else { realOwner, grants ->
                    bindVirtualDisplay(virtualDisplay, realOwner, grants)
                },
                generationFactory = if (generation == null) null else { realOwner, grants, task ->
                    val retained = synchronized(this) {
                        check(generationEntry === generation && !closed)
                        checkNotNull(generationResources)
                    }
                    checkNotNull(generation.session).openLeaf(realOwner, grants, task, retained,
                        isCurrentOwner = { synchronized(this) {
                            generationEntry === generation && !closed &&
                                resources?.let { !it.fenced && realOwner.owns(it.initial) } == true
                        } })
                }) { grants, authorization ->
                SkillToolRegistry(application).captureForRun(grants, authorization,
                    selection = assistantContext.enabledSkillIds.toSet())
            }
            preparingTools = tools
            if (mcpPreparation != null) token.mcpCatalog = tools
            if (terminalRun != null || terminalJobRun != null) token.terminalCatalog = tools
            synchronized(this) { virtualDisplay?.catalog = tools; gui?.catalog = tools }
            generation?.catalog = tools
            // GUI evidence is already frozen in preparedContext.messages; do not append it again.
            val owned = ChatRunner(observedModel, database, tools,
                RunnerOptions(reconnect=frozenReconnect.runnerPolicyWithDiagnostics { event ->
                    if (event.status == "succeeded") {
                        postReconnectNotice(null)
                        return@runnerPolicyWithDiagnostics
                    }
                    val status = when (event.status) {
                        "running" -> "正在重连"
                        "stopped" -> "重连已暂停或停止"
                        "failed" -> "重连未恢复"
                        else -> return@runnerPolicyWithDiagnostics
                    }
                    postReconnectNotice("$status · 第${event.round + 1}轮 · ${event.elapsedMs / 1000}秒")
                },
                    runtimeStore=checkNotNull(runtimeStore), receiptBatchExecutor=tools,
                    onReasoning={ event -> publishReasoning(assistant,event) })) {
                afterTitleParentPersisted(it, user, assistant, pendingTitle)
                synchronized(this) {
                    if(!closed && active?.id==assistant.id && active?.runId==run && active?.conversationId==conversationId &&
                        it.id==assistant.id && it.runId==run && it.conversationId==conversationId) {
                        val before = checkNotNull(active)
                        active=it;notifyChanged()
                        before
                    } else null
                }?.let { before ->
                    acceptedTitle.get()?.let { title -> petTransitions.afterCommit(before, it, title) }
                }
            }
            owned to tools
        } catch (_: Exception) {
            auxiliaryPort?.close()
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
        if (terminalPreparation != null && runCatching { terminalConfiguration(conversation) }.getOrNull() !=
            (terminalPreparation.assistant to terminalPreparation.provider)) {
            closeUnacceptedTools(tools)
            return null
        }
        if (terminalJobPreparation != null && runCatching { terminalConfiguration(conversation) }.getOrNull() !=
            (terminalJobPreparation.assistant to terminalJobPreparation.provider)) {
            closeUnacceptedTools(tools)
            return null
        }
        val mayCommit=synchronized(this) {
            if(preparation !== token || closed || token.revoked || token.committing ||
                !auxiliaryCurrent() || token.cancellation.isCancelled || platformAction?.cancelled==true || shizukuPreparation?.isCancelled()==true ||
                mcpPreparation?.confirmed==false || browserPreparation?.isCancelled==true || (imageAttempt!=null && !imageAttempt.beginCommit())) false
            else { token.committing=true; true }
        }
        if (!mayCommit) { auxiliaryPort?.close(); closeUnacceptedTools(tools); return null }
        var databaseSendEntered=false
        val saved=try {
            checkNotNull(token.summaryPreparation).seal()
            if (videoAttempt != null) {
                val receipt = videoAttempt.commit(database, physicalVideoHistory, checkNotNull(videoPrepared),
                    checkNotNull(videoPlan), assistantContext.assistantId, frozenDraft,
                    cancelled = { preparationCancelled(token) || video?.first?.isRevoked() == true },
                    beforeSend = { db ->
                        check(contextService.matchesHistoryRevision(db, conversationId, preparedContext.expectedHistoryRevision))
                    })
                token.videoReceipt = receipt
                check(receipt.outcome == VideoDraftOutcome.ACCEPTED) { "video_acceptance_unconfirmed" }
                checkNotNull(videoAttempt.acceptedConversation)
            } else {
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
                ControllerVideoHistory(database).assertCurrent(db, physicalVideoHistory)
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
            }
        } catch (_:Exception) {
            token.persistenceUnconfirmed=databaseSendEntered || token.videoReceipt?.outcome == VideoDraftOutcome.UNKNOWN
            synchronized(this) {
                virtualDisplay?.persistenceUnknown = token.persistenceUnconfirmed
                gui?.persistenceUnknown = token.persistenceUnconfirmed
            }
            if(databaseSendEntered) imageAttempt?.unknown() else imageAttempt?.draftChanged()
            auxiliaryPort?.close()
            closeUnacceptedTools(tools)
            problem=if(!token.persistenceUnconfirmed) "会话、原文或编辑草稿已变化；保留当前草稿，未启动聊天模型请求"
                else "发送保存结果未确认；保留原文和附件，不自动重发"
            notifyChanged();return null
        }
        // database.send returns only after its real two-row transaction receipt succeeds.
        acceptedTitle.set(saved.title)
        // Only THIS newly-created durable first run gets a job. Never inspect old/new-empty
        // conversations, restoration, or a subsequent send for catch-up title generation.
        // Title preparation failure cannot turn the accepted chat receipt into rejection.
        if (conversation == null) {
            try {
                val coordinator = titleCoordinator
                if (synchronized(this) { closed }) coordinator.close()
                else {
                    val preparedTitle = coordinator.prepare(saved.id, run)
                    pendingTitle.set(preparedTitle.job)
                    if (preparedTitle.outcome == app.daiyu.android.title.TitleOutcome.BINDING_UNAVAILABLE)
                        synchronized(this) { titleNotice = "独立标题模型失绑定或配置不可用；聊天已保存，未使用主模型" }
                    if (synchronized(this) { closed }) pendingTitle.getAndSet(null)?.let(coordinator::revoke)
                }
            } catch (_: Exception) { titleNotice = "独立标题配置不可用；聊天已保存" }
        }
        imageAttempt?.accepted(saved)
        synchronized(this) { virtualDisplay?.accepted = true; gui?.accepted = true }
        video?.first?.accepted(user, videoPrepared?.inputs.orEmpty())
        generation?.session?.recordPersistence(generation.identity, GenerationPersistenceReceipt.Accepted(saved))
        val lifetime=RunResources(assistant,owned,tools,auxiliaryPort,token.summaryPreparation)
        val installed=synchronized(this) {
            if(closed || token.revoked || !auxiliaryCurrent() ||
                token.cancellation.isCancelled || shizukuPreparation?.isCancelled()==true ||
                mcpPreparation?.confirmed==false || browserPreparation?.isCancelled==true) false else {
                if(imageAttempt!=null) imageProvider=provider
                problem=null;active=assistant
                token.admittedVoiceOwner = assistant // Exact admitted run, not a later current/history guess.
                runner=owned;runTools=tools;resources=lifetime
                token.summaryTransferred = true
                reconnectResources=lifetime
                generation?.let { it.runner=owned; it.catalog=tools }
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
            pendingTitle.getAndSet(null)?.let(titleCoordinator::revoke)
            // close won AFTER acceptance: receipt still wins, but no provider request is opened.
            auxiliaryPort?.close()
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
            if (allowed) diagnostics?.record(app.daiyu.android.diagnostics.DiagnosticEvent.RUN_SLICE_STARTED)
            val result=if(allowed) runCatching(work) else null
            result?.fold(onSuccess = { slice ->
                diagnostics?.record(when (slice) {
                    is RunSlice.Suspended -> app.daiyu.android.diagnostics.DiagnosticEvent.RUN_WAITING_USER
                    is RunSlice.Completed -> when (slice.message.status) {
                        RunStatus.COMPLETED -> app.daiyu.android.diagnostics.DiagnosticEvent.RUN_COMPLETED
                        RunStatus.INTERRUPTED -> app.daiyu.android.diagnostics.DiagnosticEvent.RUN_INTERRUPTED
                        else -> app.daiyu.android.diagnostics.DiagnosticEvent.RUN_FAILED
                    }
                })
            }, onFailure = { diagnostics?.record(app.daiyu.android.diagnostics.DiagnosticEvent.RUN_FAILED) })
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
        synchronized(this) {
            if (resources !== lifetime) return
            fenceRun(lifetime)
        }
        handler.post {
            lifetime.auxiliary?.cancel()
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
                // Terminal worker/drain boundary, not the end of a WAITING_USER slice.
                lifetime.auxiliary?.close()
                closeSummaryPreparationSafely(lifetime.summaryPreparation)
                lifetime.tools.closeWithResult { result ->
                    handler.post {synchronized(this) {
                        if(resources===lifetime) {
                            lifetime.deviceCleanup.observe(result)
                            if(lifetime.deviceCleanup.drained) cleanupRun(lifetime)
                            else {
                                reportCleanupProblem(lifetime.tools, "运行清理结果 UNKNOWN；原资源保留，不自动重连或重发，请核实设备状态")
                                if(!closed) active=(active ?: lifetime.initial).copy(
                                    status=RunStatus.INTERRUPTED,error=problem)
                                notifyChanged()
                            }
                        }
                    }}
                }
                synchronized(this) { virtualDisplayEntry?.takeIf { it.catalog === lifetime.tools } }
                    ?.let(::observeVirtualDisplayClose)
            }
        }
        if(!lifetime.deviceCleanup.drained) return
        lifetime.writer.shutdown();lifetime.waiter.shutdown()
        if (videoSession?.assistant?.runId == lifetime.initial.runId) videoSession = null
        resources=null;runner=null;runTools=null
        clearCleanupProblem(lifetime.tools)
        guiEntry?.takeIf { it.catalog === lifetime.tools && !it.persistenceUnknown && it.session?.releaseConfirmed == true }
            ?.let { it.taskSurface.end(); guiEntry = null }
        virtualDisplayEntry?.takeIf { it.catalog === lifetime.tools }?.let {
            it.taskSurface.end()
            virtualDisplayEntry = null
        }
        generationEntry?.takeIf { it.runner === lifetime.runner }?.let {
            // Slice completion + receipt persistence + catalog physical drain, not HTTP cancel.
            it.runnerDone.countDown()
            scheduleGenerationCleanup(it)
        }
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
        browserChildren.removeAll { it.drained }
        if (browserChildren.isNotEmpty()) return // Child workers/UNKNOWN still own the DB and ledger.
        pruneBackgroundRuns()
        if(resources!=null || preparing || generationEntry!=null || virtualDisplayEntry!=null || guiEntry!=null || generationReaders!=0 ||
            unacceptedTools.isNotEmpty() || messageActionReaders!=0 || imageReaders!=0 || videoReaders!=0 || childCommandReaders!=0 || titleReaders!=0 || closingStorage) return
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
            try {closeGenerationOutputReaders();generationCleanup.close();questionStore?.close();database.close();runtimeStore?.close();runtimeStore=null}
            finally {titleExecutor.shutdown();executor.shutdown();closeDone.countDown()}
        }
    }
    private val staticGenerationAssets by lazy { app.daiyu.android.media.StaticGenerationAssets(
        java.io.File(application.filesDir, "static-generation-assets")) }
    private val staticGenerationBranch by lazy { app.daiyu.android.backup.StaticGenerationBranch(
        staticGenerationAssets,
        account = { checkNotNull(GenerationSettingsStore(application).readAccountScope()) },
        liveOriginal = ::readGenerationBackup,
        historicalOriginal = { neutralBackup.historicalGenerationOriginal(it) },
        lookup = { neutralBackup.findStaticGeneration(it) },
        cancelled = { synchronized(this) { closed } }) }
    private val messageActionStore by lazy { MessageActionStore(database, contextService, messageImageAssets,
        app.daiyu.android.backup.MessageVideoBackupAssets(videoAttachments.store), staticGenerationBranch,
        cancelled = { synchronized(this) { closed } }) }

    /** Capture on the same serialized worker as persistence. Main must bind this snapshot to the
     * rendered conversation/editor owner, never acquire a fresh target from a stale callback. */
    internal fun loadMessageActionSnapshot(owner: ConversationId,
        complete: (MessageActionSnapshot?) -> Unit): Boolean {
        synchronized(this) {
            if (!idleAvailable() || hasLiveChildTasks()) return false
            messageActionReaders++
        }
        try { executor.execute {
            val snapshot = runCatching { messageActionStore.snapshot(owner) }.getOrNull()
            handler.post {
                val deliver = synchronized(this) {
                    messageActionReaders--
                    if (closed) { closeStorageWhenIdle(); false } else true
                }
                if (deliver) { complete(snapshot); dispatchChanged() }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { messageActionReaders--; if (closed) closeStorageWhenIdle() }; return false
        }
        return true
    }

    /** Explicit gestures only. No old tool or model attempt is ever resumed by this API.
     * Deletion seals the exact generation RUN before waiting on its publication barrier.
     * Read fences are closed on the worker; unrelated runs retain their publication rights. */
    internal fun messageAction(action: MessageAction, target: MessageActionTarget,
        complete: (MessageActionResult) -> Unit): Boolean {
        synchronized(this) {
            if (!idleAvailable() || hasLiveChildTasks()) return false
            if ((action == MessageAction.RETRY_STEP) != (target.toolCallId != null)) return false
            dataBusy = true
        }
        notifyChanged()
        try { executor.execute {
            var changed = false
            val result = try {
                when (action) {
                    MessageAction.EDIT_USER -> MessageActionResult.EditDraft(messageActionStore.originalUserText(target))
                    MessageAction.BRANCH -> {
                        val copy = messageActionStore.branch(target); changed = true
                        MessageActionResult.Branch(copy)
                    }
                    MessageAction.DELETE_TURN -> {
                        // Validate before even an in-memory revocation; deleteTurn repeats CAS
                        // inside its final mutation transaction after the physical barrier.
                        messageActionStore.validate(target)
                        val revocation = synchronized(this) {
                            generationRunPublicationSeals.add(target.conversationId.value to target.runId.value)
                            generationResources?.sealRunPublicationAdmission(target.conversationId.value, target.runId.value)
                        }
                        revocation?.revokeOnWorker()
                        closeGenerationOutputReaders(setOf(target.conversationId.value))
                        messageActionStore.deleteTurn(target); changed = true
                        MessageActionResult.Deleted
                    }
                    MessageAction.RETRY_STEP -> messageActionStore.rejectRetry(target)
                    MessageAction.REGENERATE -> {
                        val prepared = messageActionStore.prepareRegeneration(target); changed = true
                        val token = synchronized(this) {
                            if (closed) null else {
                                // Retain the data reservation through result delivery as well
                                // as normal preparation, so a late completion cannot clear a
                                // newer operation's busy gate. sendPrepared owns this token.
                                reservePreparation()
                            }
                        }
                        val outcome = if (token == null) RegenerationSendOutcome.NOT_SENT else try {
                            val saved = try { sendPrepared(token, prepared.conversation, prepared.text,
                                expectedDraftText = prepared.text) } catch (_: Exception) { null }
                            when {
                                saved != null -> RegenerationSendOutcome.ACCEPTED
                                token.persistenceUnconfirmed -> RegenerationSendOutcome.UNKNOWN
                                else -> RegenerationSendOutcome.NOT_SENT
                            }
                        } finally { finishPreparation(token) }
                        // Even UNKNOWN carries the exact new branch. Never re-prepare or resend.
                        MessageActionResult.Regenerated(prepared.conversation, outcome)
                    }
                }
            } catch (failure: app.daiyu.android.context.BranchRolledBack) {
                MessageActionResult.Rejected("分支已确认未提交；原会话和原件保留，未重试")
            } catch (failure: app.daiyu.android.context.BranchOutcomeUnknown) {
                changed = true
                MessageActionResult.Unknown("分支提交或原件发布未确认；仅核实目标，不要再次分支或重放工具",
                    failure.locator.copy(staticIdentities = failure.locator.staticIdentities.toList())).also { receipt ->
                    synchronized(this) { pendingBranchOutcomes.add(receipt) }
                }
            } catch (failure: IllegalArgumentException) {
                MessageActionResult.Rejected(failure.message ?: "消息身份或安全历史基线不匹配；未重放请求")
            } catch (_: Exception) {
                // Includes commit/endTransaction errors: an exception is NOT proof of absence.
                changed = true
                MessageActionResult.Unknown("消息操作未确认；请刷新核实持久历史，未自动重试或重放工具")
            }
            handler.post {
                val deliver = synchronized(this) {
                    dataBusy = false
                    if (changed) dataRevision++
                    if (closed) { closeStorageWhenIdle(); false } else true
                }
                if (deliver) { complete(result); dispatchChanged() }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { dataBusy = false }; return false
        }
        return true
    }

    internal fun skillManagement():SkillManagement = SkillManagement.create {SkillToolRegistry(application)}
    private val neutralBackup by lazy {
        val videoAssets = app.daiyu.android.backup.MessageVideoBackupAssets(videoAttachments.store)
        NeutralBackupService(application, database,
            app.daiyu.android.backup.MessageImageBackupAssets(messageImageAssets),
            app.daiyu.android.backup.BackupVideoIntegration(videoAssets,
                exportText = { db, scope -> database.exportBackupForVideoAssets(db, scope, videoAssets) },
                restoreSidecars = { db, sidecars -> database.restoreVideoBackupSidecars(db, sidecars) }),
            generationAssets = app.daiyu.android.backup.BackupGenerationAssets(::readGenerationBackup))
            .withStaticAssets(staticGenerationAssets)
    }
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
        videoSession?.revoke()
        videoConfigurationEpoch.incrementAndGet()
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
        requestOptionsReplacementAuthorized:Boolean,completed:(Boolean)->Unit) =
        saveModelSettings(value, protocolChangeAuthorized, requestOptionsReplacementAuthorized, null, null, completed)

    /** Same authorization/persistence protocol as the old entry, plus a worker-side visit and
     * configuration fence. Never let an asynchronously loaded editor overwrite a newer provider.
     */
    internal fun saveModelSettings(value:ModelSettings,protocolChangeAuthorized:Boolean,
        requestOptionsReplacementAuthorized:Boolean,expected:EditingSettings?,owner:ModelSettingsSaveOwner?,
        completed:(Boolean)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        invalidateVideoConfiguration()
        dataTask(failureNotice="模型设置未保存",completed=completed,work={
            // Check the visit after waiting for the catalog lease, not before. Departure can
            // revoke queued work; an already admitted persistence operation is never retried.
            if (expected == null && owner == null) settings.save(value,protocolChangeAuthorized,requestOptionsReplacementAuthorized)
            else settings.modelCatalog.configurationChange {
                val save = {
                    if (expected != null) check(settings.loadForEditing() == expected) { "模型配置已变化；请重新打开设置" }
                    settings.save(value,protocolChangeAuthorized,requestOptionsReplacementAuthorized)
                }
                if (owner != null) owner.commit(save) else save()
            }
            try {
                settings.modelCatalogSnapshot()
                "模型设置已保存"
            } catch (_: Exception) {
                "模型设置已保存；模型目录初始化失败，请重新打开模型设置"
            }
        })
    }
    internal fun selectConfiguredModel(expected:ModelSettings,model:String,completed:(Boolean)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        invalidateVideoConfiguration()
        dataTask(failureNotice="模型切换未保存",completed=completed,work={
            settings.selectModel(expected,model)
            "模型已切换"
        })
    }
    internal fun selectConfiguredCatalogModel(
        expected: ModelSettings,
        ticket: app.daiyu.android.model.catalog.CatalogModelSelection<app.daiyu.android.model.catalog.CatalogModelParameters>,
        completed: (Boolean) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        invalidateVideoConfiguration()
        dataTask(failureNotice = "模型切换未保存", completed = completed, work = {
            settings.selectCatalogModel(expected, ticket)
            "模型已切换"
        })
    }
    internal fun renameConfiguredCatalogModel(
        ticket: app.daiyu.android.model.catalog.CatalogModelSelection<app.daiyu.android.model.catalog.CatalogModelParameters>,
        name: String, cancelled: () -> Boolean,
        completed: (app.daiyu.android.model.catalog.CatalogLocalEditResult) -> Unit,
    ) = editConfiguredCatalogModel(cancelled, completed) { settings.renameCatalogModel(ticket, name, cancelled) }

    internal fun deleteConfiguredCatalogModel(
        ticket: app.daiyu.android.model.catalog.CatalogModelSelection<app.daiyu.android.model.catalog.CatalogModelParameters>,
        cancelled: () -> Boolean,
        completed: (app.daiyu.android.model.catalog.CatalogLocalEditResult) -> Unit,
    ) = editConfiguredCatalogModel(cancelled, completed) { settings.deleteCatalogModel(ticket, cancelled) }

    private fun editConfiguredCatalogModel(
        cancelled: () -> Boolean,
        completed: (app.daiyu.android.model.catalog.CatalogLocalEditResult) -> Unit,
        change: () -> app.daiyu.android.model.catalog.CatalogLocalEditResult,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (cancelled()) { completed(app.daiyu.android.model.catalog.CatalogLocalEditResult.CANCELLED); return }
        var result = app.daiyu.android.model.catalog.CatalogLocalEditResult.FAILED
        dataTask(failureNotice = "本地模型修改未保存", completed = { success ->
            completed(if (success) result else app.daiyu.android.model.catalog.CatalogLocalEditResult.FAILED)
        }, work = {
            result = change()
            when (result) {
                app.daiyu.android.model.catalog.CatalogLocalEditResult.APPLIED -> "本地模型已更新"
                app.daiyu.android.model.catalog.CatalogLocalEditResult.STALE -> "目录已变化，请重新选择模型"
                app.daiyu.android.model.catalog.CatalogLocalEditResult.CANCELLED -> "本地模型修改已取消"
                app.daiyu.android.model.catalog.CatalogLocalEditResult.CURRENT_MODEL -> "请先切换当前模型，再删除此模型"
                app.daiyu.android.model.catalog.CatalogLocalEditResult.FAILED -> throw java.io.IOException("local_catalog_edit_failed")
            }
        })
    }
    internal fun exportUserBackup(uri:Uri,complete:Boolean,resolver:android.content.ContentResolver=application.contentResolver) {
        dataTask(work={
            if(complete) {
                neutralBackup.prepareExport(settings.nonSecretConfiguration()).use { prepared ->
                    resolver.openOutputStream(uri,"wt")?.use { prepared.writeTo(it) }
                        ?: throw java.io.IOException("无法打开目标")
                }
            } else {
                val text=database.exportBackup()
                resolver.openOutputStream(uri,"wt")?.use { target -> target.writer(Charsets.UTF_8).apply { write(text); flush() } }
                    ?: throw java.io.IOException("无法打开目标")
            }
            if(complete) "完整中立备份已导出；不含凭据或系统授权，请妥善保管"
            else "纯文本兼容备份已导出；不含附件、归档与分支元数据"
        })
    }
    internal fun exportConversationBackup(uri:Uri,conversationId:ConversationId,resolver:android.content.ContentResolver=application.contentResolver) {
        dataTask(work={
            neutralBackup.prepareExport(emptyMap(),conversationId).use { prepared ->
                resolver.openOutputStream(uri,"wt")?.use { prepared.writeTo(it) }
                    ?: throw java.io.IOException("无法打开目标")
            }
            "单会话中立备份已导出；不含全局草稿、配置、凭据或运行恢复状态，请妥善保管"
        })
    }
    internal fun importUserBackup(uri:Uri,complete:Boolean,resolver:android.content.ContentResolver=application.contentResolver) {
        dataTask(importing=true,work={
            importUserBackupContent(complete, { resolver.openInputStream(uri) ?: throw java.io.IOException("无法打开备份") }).first
        })
    }
    internal data class BackupImportOutcome(val kind: app.daiyu.android.backup.BackupImportSource.Kind,
        val hasConfiguration: Boolean = false)
    internal fun importUserBackupAuto(uri: Uri,
        resolver: android.content.ContentResolver = application.contentResolver,
        completed: (BackupImportOutcome?) -> Unit = {}) {
        var outcome: BackupImportOutcome? = null
        dataTask(importing = true, completed = { success -> completed(outcome.takeIf { success }) }, work = {
            val source = app.daiyu.android.backup.BackupImportSource.prepare(application.cacheDir) {
                resolver.openInputStream(uri) ?: throw java.io.IOException("无法打开备份")
            }
            var notice: String? = null
            var primaryFailure: Throwable? = null
            try {
                if (source.kind == app.daiyu.android.backup.BackupImportSource.Kind.LEGACY) {
                    outcome = BackupImportOutcome(source.kind)
                    notice = "已识别旧版备份；请在资料库预览并确认导入"
                } else {
                    val result = importUserBackupContent(source.kind == app.daiyu.android.backup.BackupImportSource.Kind.COMPLETE, source::open)
                    notice = result.first
                    outcome = BackupImportOutcome(source.kind, result.second)
                }
            } catch (failure: Throwable) {
                primaryFailure = failure; throw failure
            } finally {
                if (!source.delete()) {
                    val cleanup = IllegalStateException("备份临时文件清理失败")
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanup)
                    else notice = "${checkNotNull(notice)}；临时文件清理未完成"
                }
            }
            checkNotNull(notice)
        })
    }
    private fun importUserBackupContent(complete: Boolean, open: () -> java.io.InputStream): Pair<String, Boolean> {
        if (complete) {
            val result = neutralBackup.importPackageFrom(open)
            val inventory = result.inventory
            val summary = if (result.alreadyImported) "此完整备份已导入过，未重复添加"
            else "已导入${inventory.conversations}个会话、${inventory.messages}条消息、${inventory.attachments}个附件"
            return (result.cleanupProblem?.let { "$summary；$it" } ?: summary) to (inventory.configEntries > 0)
        }
        val text = open().use { readBackupText(it, 16L * 1024 * 1024) }
        val counts = database.importBackup(text)
        return "已导入${counts.first}个会话、${counts.second}条消息" to false
    }
    internal fun readBackupText(stream:java.io.InputStream,limit:Long):String {
        require(limit in 1..Int.MAX_VALUE.toLong())
        return app.daiyu.android.context.Utf8Payload.read(stream,limit.toInt())
    }
    /** Destructive owner boundary used by both conversation deletion and delete-all in Main.
     * Seal is only in-memory under the short admission lock. Waiting for publications, closing
     * readers and the caller's actual deletion transaction all run on the worker, in that order.
     * A failed/ambiguous delete remains sealed; neither retry nor import may reopen that owner.
     */
    @Synchronized internal fun mutateGenerationOwners(owners: Set<String>? = null, completed: (Boolean) -> Unit,
        mutation: () -> Unit) {
        check(idleAvailable() && !hasLiveChildTasks()) { "请等待后台子任务实际结束" }
        val selected = owners?.toSet()
        require(selected == null || selected.all { it.isNotBlank() })
        val captured = selected?.let(::sealGenerationOwners)
        dataTask(failureNotice = "删除未确认；原生成发布和读取授权已撤销", completed = completed, work = {
            check(Looper.myLooper() != Looper.getMainLooper())
            // Clear history deletes existing conversations, not the account. dataBusy prevents
            // new preparation/read admission while the worker captures and seals this scope.
            val revocations = captured ?: run {
                val deleted = database.conversations().map { it.id.value }.toSet()
                synchronized(this) { sealGenerationOwners(deleted) }
            }
            revocations.forEach { it.revokeOnWorker() }
            closeGenerationOutputReaders(selected)
            mutation()
            "删除已完成"
        })
    }

    // Called only under the controller monitor; no DB/FS/validator or wait in these APIs.
    private fun sealGenerationOwners(owners: Set<String>): List<ControllerGenerationPublicationRevocation> {
        generationConversationPublicationSeals.addAll(owners)
        return generationResources?.let { retained ->
            owners.map { retained.sealConversationPublicationAdmission(it) }
        }.orEmpty()
    }

    internal val legacyLibrary by lazy {
        LegacyLibraryHost(application.contentResolver,
            serviceFactory = { beforeCommit ->
                app.daiyu.android.backup.legacy.LegacyImportService(
                    database = { database.writableDatabase }, beforeCommit = beforeCommit)
            },
            gateway = LegacyLibraryHost.Gateway { work, completed ->
                try {
                    dataTask(failureNotice = "兼容库操作未确认；请重新读取历史核实，不自动重试。目标可能不完整。",
                        completed = completed, work = { work(); "兼容库操作已结束，请查看资料库结果。" })
                    true
                } catch (_: Exception) { false }
            })
    }

    @Synchronized internal fun dataTask(importing:Boolean=false,failureNotice:String?=null,
        completed:(Boolean)->Unit = {},work:()->String) {
        check(idleAvailable() && !hasLiveChildTasks()) { "请等待后台子任务实际结束" }
        dataBusy=true;notifyChanged()
        try {executor.execute {
            var value=failureNotice ?: if(importing) "导入失败；原会话保留，格式、校验或身份冲突" else "导出失败；目标文件可能不完整，原会话保留"
            var success=false
            diagnostics?.record(app.daiyu.android.diagnostics.DiagnosticEvent.DATA_STARTED)
            try {
                // Both backup importers append new identities or roll back on conflict; even
                // an attempted/duplicate import must leave old owners and their gates untouched.
                value=work();success=true
            } catch(failure:Exception) {
                value=failureNotice ?: when(failure) {
                    is SecurityException -> "备份文件访问权限不足或已失效；请重新选择文件。原会话保留，导出目标可能不完整"
                    is java.nio.charset.CharacterCodingException -> "导入失败；备份不是有效UTF-8文本。原会话保留，未执行历史请求"
                    is java.io.IOException -> if(importing) "导入失败；读取备份或写入附件失败。原会话保留，未执行历史请求"
                        else "导出失败；读取附件或写入目标失败。目标文件可能不完整，请勿作为有效备份；原会话保留"
                    else -> value
                }
            }
            finally {
                diagnostics?.record(if (success) app.daiyu.android.diagnostics.DiagnosticEvent.DATA_COMPLETED
                    else app.daiyu.android.diagnostics.DiagnosticEvent.DATA_FAILED)
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
        val acceptedAt = System.currentTimeMillis()
        val user=Message(messageId,owner.conversationId,owner.runId,Message.USER_ROLE,
            listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED,createdAt=acceptedAt)
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
        lifetime.auxiliary?.cancel()
        synchronized(this) { browserChildren.filter { it.parent.identity.runId == owner.runId }.toList() }
            .forEach { it.close() }
        browserChildrenChanged()
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
        return true
    }
    fun stop() {
        invalidateCurrentContextManagement()
        stopBrowserChildren()
        synchronized(this) { generationEntry }?.let { entry ->
            if (!entry.submitted) abandonGeneration(entry) else entry.session?.revokeAdmission()
        }
        cancelPreparation()
        synchronized(this) {pendingImage}?.cancel()
        synchronized(this) {pendingPlatformAction}?.let(::rejectPlatformAction)
        synchronized(this) {pendingShizuku}?.let(::rejectShizuku)
        val lifetime=synchronized(this) {resources?.also {fenceRun(it)}} ?: return
        lifetime.auxiliary?.cancel()
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
    }
    internal fun close() {
        stopBrowserChildren()
        synchronized(this) { generationEntry }?.let { entry ->
            if (!entry.submitted) abandonGeneration(entry) else entry.session?.revokeAdmission()
        }
        val lifetime=synchronized(this) {
            if(!closed) {
                closed=true;ready=false;dataBusy=false;active=null
                preparation?.revoked=true
                imageProvider=null
                configurationRestorePreview=null;dataNotice=null;listeners.clear();reasoningListeners.clear();childBindings.clear()
            }
            resources?.also {fenceRun(it)}.also {runRevocationListeners.clear()}
        }
        invalidateCurrentContextManagement()
        // Fence/cancel the independent title owner before storage drain. No UI join.
        if (titleCoordinatorDelegate.isInitialized()) titleCoordinator.close()
        cancelPreparation()
        synchronized(this) {pendingImage}?.cancel()
        synchronized(this) {pendingPlatformAction}?.let(::rejectPlatformAction)
        synchronized(this) {pendingShizuku}?.let(::rejectShizuku)
        lifetime?.auxiliary?.cancel()
        lifetime?.runner?.stop();lifetime?.tools?.close()
        handler.post {synchronized(this) {
            if(lifetime!=null && resources===lifetime) cleanupRun(lifetime) else closeStorageWhenIdle()
        }}
        // The control loop must remain available for stop acknowledgments and continuation drain.
        if(Looper.myLooper()!=Looper.getMainLooper()) check(closeDone.await(10,TimeUnit.SECONDS))
    }
}
