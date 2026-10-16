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
    companion object { internal const val OPEN_PET_SETTINGS = "app.daiyu.android.OPEN_PET_SETTINGS" }
    private val terminalProviderDelegate = lazy { app.daiyu.android.terminal.TerminalApplicationProvider(this) }
    internal val terminalProvider by terminalProviderDelegate
    internal val terminalOwner get() = checkNotNull(
        (terminalProvider.state.value as? app.daiyu.android.terminal.TerminalApplicationState.Ready)?.application
    ) { "terminal_catalog_not_ready" }.terminalOwner
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
    private val virtualDisplayFactory: ControllerVirtualDisplayFactory = ControllerVirtualDisplayFactory.REAL) {
    // Voice credentials remain independent from chat settings.
    internal val voiceRuntime by lazy { ControllerVoiceRuntime(application) }
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
        assistantModel=AssistantModelFactory::create)
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
    // Entry points and callback commits share this controller's monitor. Runner control and
    // shutdown waits stay outside it, so a worker can always finish its last publication.
    private val listeners=mutableMapOf<()->Unit,Any>()
    private val reasoningListeners=mutableMapOf<(Message, ModelEvent)->Unit,Any>()
    private val runRevocationListeners=mutableSetOf<(Message)->Unit>()
    private var closed=false
    // The existing preparation lane is retained until the worker (and device cleanup) exits.
    // Identity, not a Boolean reset, prevents an old completion from clearing a newer owner.
    private class Preparation(val assistantId: String?, var textTicket: TextSendTicket? = null) {
        var admittedVoiceOwner: Message? = null
        val cancellation = ContextPreparationCancellation()
        var revoked = false
        var committing = false
        var persistenceUnconfirmed = false
        var browser: ControllerBrowserPreparation? = null
        var terminal: ControllerTerminalPreparation? = null
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
            virtualDisplayEntry?.let { it.revoked = true; it.session?.close() }
            preparation?.also { it.revoked = true; it.video?.revoke() }
        }
        token?.cancellation?.cancel()
        token?.browser?.cancel()
        if (token != null && !token.submitted && (token.browser != null || token.terminal != null ||
            synchronized(this) { virtualDisplayEntry?.submitted == false })) finishPreparation(token)
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
    private var imageReaders=0

    // One fixed application DB/account, not a resolver of whichever account is active later.
    @Volatile private var generationScope: String? = null
    private var generationResources: ControllerGenerationResources? = null
    // Deletion also works before any generation settings/resource exists. These in-memory
    // seals are inherited by the first (and only) resource, never cleared by a later import.
    private val generationConversationPublicationSeals = mutableSetOf<String>()
    // The only Resources constructor in the parent. Never reset this on stop, import, deletion
    // or reader close: its permanent owner gates belong to this actual account/DB lifetime.
    @Synchronized private fun retainGenerationResources(accountScope: String): ControllerGenerationResources {
        check(generationScope == null || generationScope == accountScope) { "generation_account_changed" }
        generationScope = accountScope
        return generationResources ?: ControllerGenerationResources(accountScope,
            { database.writableDatabase }, { generationScope },
            File(application.filesDir, "media-message-generation")).also { retained ->
            generationResources = retained
            // No existing gates/publications exist in a newly constructed resource, so these
            // seals need no wait. Every future fence inherits them before it can be returned.
            generationConversationPublicationSeals.forEach { retained.sealConversationPublicationAdmission(it) }
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
        }).also { entry.session = it }
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
                entry.initialEditor.text != current.text || current.attachmentIds.isNotEmpty()) return false
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
                val snapshot = GenerationSettingsStore(application).loadForEditing()
                val retained = retainGenerationResources(snapshot.accountScope)
                val displays = mutableListOf<GenerationAssetDisplay>()
                // Side table is authoritative, including registered outputs beside central UNKNOWN.
                retained.database().rawQuery("SELECT operation_id,item_id,output_index,fingerprint,protocol," +
                    "assistant_id,run_id,tool_call_id,tool_name FROM generation_outputs " +
                    "WHERE account_scope=? AND conversation_id=? ORDER BY rowid DESC LIMIT 32",
                    arrayOf(snapshot.accountScope, conversation.value)).use { rows ->
                    while (rows.moveToNext()) {
                        val binding = GenerationAttemptBinding(conversation.value, rows.getString(5),
                            rows.getString(6), rows.getString(7), rows.getString(8))
                        val owner = GenerationAssetOwner(snapshot.accountScope, binding)
                        val key = snapshot.accountScope + ":" + binding.operationId
                        val reader = generationOutputReaders.getOrPut(key) { retained.openOutputs(owner) }
                        generationOutputConversations[key] = conversation.value
                        val attempt = GenerationAttempt(GenerationIdentity(rows.getString(0), rows.getString(1)),
                            rows.getString(3), GenerationProtocol.valueOf(rows.getString(4)))
                        val index = rows.getInt(2)
                        val metadata = reader.metadata(attempt, index) ?: continue
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
    private var childrenClosing=false
    private var childrenClosed=false
    private var questionStore:PrivateQuestionStore?=null
    private val closeDone=CountDownLatch(1)
    private var closingStorage=false
    private class RunResources(val initial:Message,val runner:ChatRunner,val tools:RunCatalogTools,
        val auxiliary: ControllerAuxiliaryVision? = null) {
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
                virtualDisplayEntry?.takeIf { it.catalog === tools && !it.persistenceUnknown }?.let {
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
        } }
    internal fun stopChildTask(record:ChildTaskRecord):app.daiyu.android.domain.delegation.ChildTaskStop {
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
        resources==null && generationEntry==null && virtualDisplayEntry==null && generationReaders==0 && pendingShizuku==null && unacceptedTools.isEmpty() &&
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
        synchronized(this) { virtualDisplayEntry?.takeIf { it.textTicket === ticket } }
            ?.let { return cancelVirtualDisplay(it) }
        val token = synchronized(this) {
            preparation?.takeIf { it.textTicket === ticket }?.also { it.revoked = true; it.video?.revoke() }
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
        val installed = synchronized(this) { resources?.initial?.runId == token.video?.assistant?.runId && token.video != null }
        if (!installed) {
            token.video?.revoke()
            if (videoSession === token.video) videoSession = null
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
                ?.let { virtualDisplayEntry = null }
            pendingImage = null
            if (closed) closeStorageWhenIdle() else notifyChanged()
        }
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
            .getOrNull()?.also { virtualDisplayEntry = it; reservePreparation(); notifyChanged() }
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

    /** Explicit physical observation, never cancellation-as-exit or an automatic release write.
     * A pending/UNKNOWN result retains the entry and all handles; the user may observe again. */
    internal fun observeVirtualDisplayClose(entry: ControllerVirtualDisplayEntry): Boolean {
        val tools = synchronized(this) {
            if (virtualDisplayEntry !== entry || !entry.revoked) return false
            val catalog = entry.catalog ?: return false
            val lifetime = resources
            if (lifetime != null) {
                if (lifetime.tools !== catalog || lifetime.busy || lifetime.waiting ||
                    lifetime.result == null || !lifetime.devicesClosing) return false
            } else if (preparation != null || catalog !in unacceptedTools) return false
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
            check(virtualDisplayEntry === entry && entry.accepted && !entry.factoryEntered)
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
            val memory = if (profile.memoryEnabled) assistantAssets.memory(profile.id).content else ""
            val frozen = object : AssistantStore by assistantStore {
                override fun read(id: String): AssistantProfile? = profile.takeIf { it.id == id }
            }
            val context = AssistantRunContext.capture(frozen, profile.id, memory)
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
     * provider is the frozen MAIN configuration; route is the independent actual image capability.
     * An explicitly enabled auxiliary route must be captured on a worker, never by upgrading main.
     * expectedDraftText is the exact persisted composer/conversation text at this revision;
     * it is compared atomically before accepting, never inferred from image labels or URIs.
     * Null means busy/closed/invalid input, no task and no callback. Cancellation is via handle.
     */
    internal fun sendImages(conversation:Conversation?, owner:ImageAttachmentOwner, draftRevision:Long,
        parts:List<ImageSendPart>, provider:ProviderSnapshot, expectedDraftText:String,
        route: ImageRoute = ImageRoute(provider, null),
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
                    route.actual, route.auxiliaryGeneration)
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
        browserBinding:CatalogBrowserBinding?=null,
        generation:GenerationEntry?=null,
        terminalPreparation:ControllerTerminalPreparation?=null,
        terminalRun:TerminalConfirmedRun?=null,
        virtualDisplay:ControllerVirtualDisplayEntry?=null,
        videoAttempt:ControllerVideoAttempt?=null):Conversation? {
        require((terminalPreparation == null) == (terminalRun == null))
        require(terminalRun == null || (imageAttempt == null && shizukuPreparation == null &&
            browserPreparation == null && generation == null && virtualDisplay == null && videoAttempt == null && !withChildren &&
            terminalRun.preview === terminalPreparation?.preview &&
            terminalRun.authorization === terminalRun.binding.authorization))
        if (terminalPreparation != null && runCatching { terminalConfiguration(conversation) }.getOrNull() !=
            (terminalPreparation.assistant to terminalPreparation.provider)) return null
        require(virtualDisplay == null || (generation == null && shizukuPreparation == null &&
            browserBinding == null && imageAttempt == null && terminalRun == null && videoAttempt == null && !withChildren))
        require(videoAttempt == null || (generation == null && shizukuPreparation == null && shizukuAuthorization == null &&
            browserPreparation == null && browserBinding == null && imageAttempt == null &&
            terminalRun == null && virtualDisplay == null && !withChildren))
        require(shizukuPreparation==null || (imageAttempt==null && shizukuAuthorization!=null))
        require(generation == null || (shizukuPreparation == null && browserBinding == null && imageAttempt == null))
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
        val config=try {(terminalPreparation?.provider?.asModelSettings() ?: browserPreparation?.provider?.asModelSettings() ?: settings.load()).also {it.validate()}} catch (_:CredentialUnavailableException) {
            problem="已保存密钥不可用；请到模型设置重新输入，未发起网络请求";notifyChanged();return null
        } catch (_:Exception) {problem="请在模型设置里配置Responses地址、模型与密钥";notifyChanged();return null}
        val provider=try {
            val selected=terminalPreparation?.provider ?: browserPreparation?.provider ?: imageAttempt?.provider ?: synchronized(this) {imageProvider}
            if(imageAttempt!=null) require(selected?.asModelSettings()==config) {"stale_image_provider"}
            selected?.takeIf {it.asModelSettings()==config} ?: ProviderSnapshot.capture(config)
        } catch (_:Exception) {problem="图片模型配置已变化；草稿保留，未发起网络请求";notifyChanged();return null}
        val conversationId=virtualDisplay?.conversationId ?: terminalRun?.preview?.identity?.conversationId ?: videoAttempt?.conversationId ?: generation?.identity?.conversationId ?: browserPreparation?.identity?.conversationId ?: shizukuPreparation?.conversationId ?: conversation?.id ?: imageAttempt?.let {ConversationId(it.owner.conversationId)}
            ?: ConversationId(UUID.randomUUID().toString())
        val run=virtualDisplay?.runId ?: terminalRun?.preview?.identity?.runId ?: videoAttempt?.runId ?: generation?.identity?.runId ?: browserPreparation?.identity?.runId ?: shizukuPreparation?.runId ?: imageAttempt?.runId ?: RunId(UUID.randomUUID().toString())
        var user=try {imageAttempt?.prepare(imageAttachments.store,messageImageAssets) ?:
            Message(videoAttempt?.userId ?: MessageId(UUID.randomUUID().toString()),conversationId,run,"user",listOf(MessageBlock.Text("user",text)),RunStatus.COMPLETED)
        } catch (_:Exception) {problem="图片冻结失败或模型不支持；草稿保留，未发起网络请求";notifyChanged();return null}
        val assistant=Message(virtualDisplay?.assistantId ?: terminalRun?.preview?.identity?.assistantMessageId ?: videoAttempt?.assistantId ?: generation?.identity?.assistantMessageId ?: browserPreparation?.identity?.assistantMessageId ?: imageAttempt?.assistantId ?: MessageId(UUID.randomUUID().toString()),conversationId,run,"assistant",emptyList(),RunStatus.RUNNING)
        val physicalVideoHistory = try { ControllerVideoHistory(database).capture(conversationId) }
        catch (_: Exception) {
            problem="原视频来源无法确认；草稿保留，未启动模型请求";notifyChanged();return null
        }
        if (physicalVideoHistory.inputs.isNotEmpty() && (withChildren || imageAttempt != null ||
                generation != null || terminalRun != null || virtualDisplay != null ||
                shizukuPreparation != null || shizukuAuthorization != null ||
                browserPreparation != null || browserBinding != null)) {
            problem="当前专用批准不能与原视频历史混合发送；保留草稿，请使用原视频文本发送入口"
            notifyChanged(); return null
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
            videoPrepared = videoAttempt?.prepare(videoAttachments.store, provider, capability) { session.isRevoked() }
            videoPrepared?.let { user = it.user }
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
            val frozen = coordinator.freeze(conversationId,listOf(user),token.cancellation)
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
        val preparedModel=try {
            if (video != null) messageVideos.wrap(provider, video.second, conversationId, assistant.id,
                checkNotNull(videoPlan), assistantContext.systemPrompt, { video.first.isRevoked() },
                checkNotNull(video.first.post), onFrozenPlan = video.first::onFrozenPlan) else {
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
            val delegate=assistantModel?.invoke(provider,assistantContext)
                ?: if(plan.hasImages && auxiliaryFrozen == null) imageModel(provider) else model(config)
            if (auxiliaryFrozen == null) messageImages.wrap(delegate,provider,conversationId)
            else {
                ControllerAuxiliaryVision.create(delegate, auxiliarySelection,
                    listOf(independentModels.visionCandidate(auxiliaryFrozen)), messageImageResolver(conversationId),
                    checkNotNull(authorization)::authorize).also { auxiliaryPort = it }
            }
            }
        } catch (_:Exception) {
            problem="图片历史读取、能力检查或模型初始化失败；草稿保留，未启动聊天模型请求";notifyChanged();return null
        }
        if(preparationCancelled(token)) { auxiliaryPort?.close(); return null }
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
            val capabilities = SessionCapabilities(terminalRun?.preview?.identity?.sessionId ?: run.value,
                (if (virtualDisplay == null) AndroidToolRegistry.capabilitiesFor(config.enabledTools()) else emptySet()) + contextTools.map { it.definition.capability } +
                    setOf(SkillRunSession.READ, SkillRunSession.RESOURCE) +
                    (if (interactiveSnapshot.enabled) setOf(AndroidToolRegistry.ASK_USER_CAPABILITY) else emptySet()) +
                    (if (children == null) emptySet() else setOf(RunChildModels.CAPABILITY,RunChildTasks.CAPABILITY)) +
                    (if (shizukuPreparation == null) emptySet() else setOf(app.daiyu.android.shizuku.RunShizukuCommands.CAPABILITY)) +
                    (if (browserBinding == null) emptySet() else setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)) +
                    (if (generation == null) emptySet() else setOf(RunGenerationTools.CAPABILITY)) +
                    (if (terminalRun == null) emptySet() else setOf(RunTerminalTools.CAPABILITY)) +
                    (if (virtualDisplay == null) emptySet() else setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY)))
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
                backgroundFactory, authorization=terminalRun?.authorization ?: shizukuAuthorization ?: browserBinding?.let {
                    app.daiyu.android.domain.tools.TaskAuthorization(capabilities.sessionId, run.value, it.requiredWrites)
                }, shizuku=shizuku, browser=browserBinding, terminal=terminalRun?.binding,
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
                        } }, networkPolicy = ControllerGenerationResources.DENY_DOWNLOADS)
                }) { grants, authorization ->
                SkillToolRegistry(application).captureForRun(grants, authorization,
                    selection = assistantContext.enabledSkillIds.toSet())
            }
            preparingTools = tools
            if (terminalRun != null) token.terminalCatalog = tools
            synchronized(this) { virtualDisplay?.catalog = tools }
            generation?.catalog = tools
            val owned = ChatRunner(preparedModel, database, tools,
                RunnerOptions(runtimeStore=checkNotNull(runtimeStore), receiptBatchExecutor=tools,
                    onReasoning={ event -> publishReasoning(assistant,event) })) {
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
        val mayCommit=synchronized(this) {
            if(preparation !== token || closed || token.revoked || token.committing ||
                !auxiliaryCurrent() || token.cancellation.isCancelled || shizukuPreparation?.isCancelled()==true ||
                browserPreparation?.isCancelled==true || (imageAttempt!=null && !imageAttempt.beginCommit())) false
            else { token.committing=true; true }
        }
        if (!mayCommit) { auxiliaryPort?.close(); closeUnacceptedTools(tools); return null }
        var databaseSendEntered=false
        val saved=try {
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
            synchronized(this) { virtualDisplay?.persistenceUnknown = token.persistenceUnconfirmed }
            if(databaseSendEntered) imageAttempt?.unknown() else imageAttempt?.draftChanged()
            auxiliaryPort?.close()
            closeUnacceptedTools(tools)
            problem=if(!token.persistenceUnconfirmed) "会话、原文或编辑草稿已变化；保留当前草稿，未启动聊天模型请求"
                else "发送保存结果未确认；保留原文和附件，不自动重发"
            notifyChanged();return null
        }
        // database.send returns only after its real two-row transaction receipt succeeds.
        acceptedTitle.set(saved.title)
        imageAttempt?.accepted(saved)
        synchronized(this) { virtualDisplay?.accepted = true }
        video?.first?.accepted(user, videoPrepared?.inputs.orEmpty())
        generation?.session?.recordPersistence(generation.identity, GenerationPersistenceReceipt.Accepted(saved))
        val lifetime=RunResources(assistant,owned,tools,auxiliaryPort)
        val installed=synchronized(this) {
            if(closed || token.revoked || !auxiliaryCurrent() ||
                token.cancellation.isCancelled || shizukuPreparation?.isCancelled()==true ||
                browserPreparation?.isCancelled==true) false else {
                if(imageAttempt!=null) imageProvider=provider
                problem=null;active=assistant
                token.admittedVoiceOwner = assistant // Exact admitted run, not a later current/history guess.
                runner=owned;runTools=tools;resources=lifetime
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
        virtualDisplayEntry?.takeIf { it.catalog === lifetime.tools }?.let {
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
        pruneBackgroundRuns()
        if(resources!=null || preparing || generationEntry!=null || virtualDisplayEntry!=null || generationReaders!=0 ||
            unacceptedTools.isNotEmpty() || imageReaders!=0 || videoReaders!=0 || closingStorage) return
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
            finally {executor.shutdown();closeDone.countDown()}
        }
    }
    internal fun skillManagement():SkillManagement = SkillManagement.create {SkillToolRegistry(application)}
    private val neutralBackup by lazy {
        val videoAssets = app.daiyu.android.backup.MessageVideoBackupAssets(videoAttachments.store)
        NeutralBackupService(application, database,
            app.daiyu.android.backup.MessageImageBackupAssets(messageImageAssets),
            app.daiyu.android.backup.BackupVideoIntegration(videoAssets,
                exportText = { db, scope -> database.exportBackupForVideoAssets(db, scope, videoAssets) },
                restoreSidecars = { db, sidecars -> database.restoreVideoBackupSidecars(db, sidecars) }))
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
        requestOptionsReplacementAuthorized:Boolean,completed:(Boolean)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        invalidateVideoConfiguration()
        dataTask(failureNotice="模型设置未保存",completed=completed,work={
            settings.save(value,protocolChangeAuthorized,requestOptionsReplacementAuthorized)
            "模型设置已保存"
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

    @Synchronized internal fun dataTask(importing:Boolean=false,failureNotice:String?=null,
        completed:(Boolean)->Unit = {},work:()->String) {
        check(idleAvailable() && !hasLiveChildTasks()) { "请等待后台子任务实际结束" }
        dataBusy=true;notifyChanged()
        try {executor.execute {
            var value=failureNotice ?: if(importing) "导入失败；原会话保留，格式、校验或身份冲突" else "导出失败；目标文件可能不完整，原会话保留"
            var success=false
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
        lifetime.auxiliary?.cancel()
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
        return true
    }
    fun stop() {
        synchronized(this) { generationEntry }?.let { entry ->
            if (!entry.submitted) abandonGeneration(entry) else entry.session?.revokeAdmission()
        }
        cancelPreparation()
        synchronized(this) {pendingImage}?.cancel()
        synchronized(this) {pendingShizuku}?.let(::rejectShizuku)
        val lifetime=synchronized(this) {resources?.also {fenceRun(it)}} ?: return
        lifetime.auxiliary?.cancel()
        lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()
        handler.post {synchronized(this) {cleanupRun(lifetime)}}
    }
    internal fun close() {
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
        cancelPreparation()
        synchronized(this) {pendingImage}?.cancel()
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
