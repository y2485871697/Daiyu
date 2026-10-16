package app.daiyu.android

import android.net.Uri
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.media.AndroidMessageImagePreviews
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.ui.screens.chat.attachments.*
import app.daiyu.android.ui.screens.chat.conversation.MessageImageLoader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity-owned capabilities. No URI, credential or bitmap is persisted in saved instance state.
 * Cleanup has its own IO lifetime: destroying the Activity must not cancel receipt cleanup halfway.
 * Published message assets are NEVER deleted here; only exact draft references are removed.
 */
internal class ImageChatHost private constructor(controller: ChatController) {
    // Retain settlement with the Controller, not a destroyed Activity. The weak reference avoids
    // keeping closed test/application controllers alive through the registry's value.
    private val controllerRef = java.lang.ref.WeakReference(controller)
    private val controller: ChatController get() = checkNotNull(controllerRef.get())
    private var changed: () -> Unit = {}
    private var notice: (String) -> Unit = {}
    private var settled: (ImageSendResult.Accepted, String) -> Unit = { _, _ -> }
    private val store = ImageHostDraftStore(controller.preferences)
    internal class Session(conversation: String?, restored: ImageHostDraftStore.Draft? = null) {
        val owner = restored?.owner ?: ImageAttachmentOwner(conversation ?: UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val attachments = ImageAttachmentsState(ImageAttachmentDraft(owner, restored?.revision ?: 0), restored?.images.orEmpty())
        val imports = mutableMapOf<ImageAttachmentItemKey, CancellationSignal>()
        var attempt: Attempt? = null
        var abandoned = false
        var uncertain by mutableStateOf(restored?.fenced == true)
    }
    internal class Attempt(val submission: ImageAttachmentSubmission, val text: String) {
        var handle: ImageSendHandle? = null
        var cancelled = false
        fun cancel() { cancelled = true; handle?.cancel() }
    }

    // These jobs terminate individually. We deliberately do not cancel receipt/file cleanup on close.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var storageFailed by mutableStateOf(false)
    private val sessions = mutableMapOf<String?, Session>()
    init {
        try { store.read().forEach { sessions[it.conversation] = Session(it.conversation, it) } }
        catch (_: Exception) { storageFailed = true } // Corrupt state is not permission for a new owner/retry.
    }
    var session by mutableStateOf(sessions.getOrPut(null) { Session(null) }); private set
    private var visible = true
    private var visit = 0L
    private var closed = false
    // Cancellation can lose the Controller's commit gate. Until that old receipt settles,
    // freeze writes even to a newly selected editor (including an identical-text new chat).
    private var outstanding by mutableStateOf(0)
    private var picker: Pair<Session, ImageAttachmentSelection>? = null
    private var lease = PreviewLease(controller, null)
    var historyLoader by mutableStateOf<MessageImageLoader?>(null); private set
    var draftLoader by mutableStateOf<ImageAttachmentPreviewLoader>(lease.draftLoader); private set
    @Volatile private var authorizedSettings: ModelSettings? = null
    @Volatile private var capabilityGeneration = 0L
    private val capabilityLock = Mutex()
    var authorizationLabel by mutableStateOf("声明当前模型支持图片"); private set

    val hasAttachments: Boolean get() = session.attachments.state.value.items.isNotEmpty()
    val busy: Boolean get() = sending || session.uncertain || storageFailed
    val fenced: Boolean get() = session.uncertain || storageFailed

    private fun persist(): Boolean {
        if (storageFailed) return false // Never overwrite unreadable state with fresh identities.
        val saved = store.write(sessions.map { (conversation, target) ->
            ImageHostDraftStore.Draft(conversation, target.owner, target.attachments.state.value.draft.revision,
                target.attachments.state.value.ready.map { it.attachment }, target.uncertain || target.attempt != null)
        })
        if (!saved) storageFailed = true
        return saved
    }
    val sending: Boolean get() = outstanding > 0

    fun enter(conversation: String?) {
        visit++
        abandon(session)
        session = sessions.getOrPut(conversation) { Session(conversation) }
        session.abandoned = false
        persist()
        renewPreviews(conversation)
    }

    fun setVisible(value: Boolean, conversation: String?) {
        if (closed || visible == value) return
        visible = value
        if (!value) {
            visit++
            cancelTransient(session)
            session.attempt?.cancel()
            lease.close(); historyLoader = null
        } else renewPreviews(conversation)
    }

    private fun renewPreviews(conversation: String?) {
        lease.close()
        lease = PreviewLease(controller, conversation)
        if (!visible || closed) lease.close()
        draftLoader = lease.draftLoader
        historyLoader = if (visible && !closed && conversation != null) lease.historyLoader else null
    }

    fun syncConversation(conversation: String?) {
        // Accepted can move the provisional session out of the null slot without navigating
        // the editor. Reconcile by slot identity, including null, not just the owner's ID.
        if (sessions[conversation] !== session) {
            enter(conversation)
        } else if (lease.conversation != conversation) renewPreviews(conversation)
    }

    fun requestSelection(expected: ImageAttachmentDraft, launch: () -> Unit) {
        val target = session
        if (closed || !visible || busy || target.abandoned || picker != null) return
        val request = target.attachments.requestSelection(expected) ?: return
        picker = target to request
        try { launch() } catch (_: Exception) {
            picker = null; target.attachments.cancelSelection(request)
            notice("无法打开图片选择器")
        }
    }

    fun picked(uris: List<Uri>) {
        val (target, request) = picker ?: return
        picker = null
        if (closed || !visible || target !== session || target.abandoned) {
            target.attachments.cancelSelection(request); return
        }
        if (uris.size > request.remaining) {
            target.attachments.cancelSelection(request)
            notice("图片数量超出剩余名额，请重新选择"); return
        }
        val keys = target.attachments.beginImport(request, uris.size)
        keys.zip(uris).forEach { (key, uri) ->
            val signal = CancellationSignal()
            target.imports[key] = signal
            io.launch {
                var imported: ImageAttachment? = null
                var failure = ImageAttachmentFailure.SOURCE_UNAVAILABLE
                try { imported = controller.imageAttachments.import(target.owner, uri, signal) }
                catch (error: ImageAttachmentException) { failure = error.failure }
                catch (_: Exception) { /* Never surface source URIs or local file paths. */ }
                withContext(Dispatchers.Main) {
                    target.imports.remove(key)
                    val image = imported
                    if (image == null) target.attachments.importFailed(key, failure)
                    else if (!target.attachments.importSucceeded(key, image)) delete(listOf(image))
                    persist()
                    changed()
                }
            }
        }
    }

    fun remove(key: ImageAttachmentItemKey) {
        val target = session
        if (closed || !visible || target.uncertain || storageFailed || sending) return
        val removed = target.attachments.remove(key) ?: return
        target.imports.remove(key)?.let { signal -> io.launch { signal.cancel() } }
        if (persist() && removed is ImageAttachmentItem.Ready) delete(listOf(removed.attachment))
        changed()
    }

    fun actions(launch: () -> Unit): ImageAttachmentsActions {
        val target = session
        val generation = visit
        fun current() = !closed && visible && session === target && visit == generation
        return ImageAttachmentsActions(
            onSelect = { if (current()) requestSelection(it, launch) },
            onPreview = { if (current()) target.attachments.openPreview(it) },
            onRemove = { if (current()) remove(it) },
            onDismissPreview = { if (current()) target.attachments.closePreview(it) },
        )
    }

    /** Called only after an explicit confirmation naming this exact saved model/configuration. */
    fun authorize(saved: ModelSettings, complete: (Boolean) -> Unit) {
        val generation = capabilityGeneration
        io.launch {
            capabilityLock.withLock {
                val accepted = try {
                    generation == capabilityGeneration && controller.settings.load() == saved && controller.authorizeImageProvider(
                        ProviderSnapshot.capture(saved, saved.protocol, imageInputSupported = true))
                } catch (_: Exception) { false }
                withContext(Dispatchers.Main) {
                    if (!closed && generation == capabilityGeneration) {
                        authorizedSettings = saved.takeIf { accepted }
                        authorizationLabel = if (accepted) "已声明：${saved.model} 支持图片" else "配置已变化，请重新声明图片能力"
                        complete(accepted)
                    }
                }
            }
        }
    }

    /** Settings are read on IO and compared by complete value, never inferred from a model name. */
    fun readSettings(complete: (ModelSettings?) -> Unit) {
        io.launch {
            val saved = try { controller.settings.load() } catch (_: Exception) { null }
            withContext(Dispatchers.Main) { if (!closed && visible) complete(saved) }
        }
    }

    fun configurationChanged() {
        val generation = ++capabilityGeneration
        authorizedSettings = null
        authorizationLabel = "声明当前模型支持图片"
        // Also downgrade the Controller's history-continuation grant. Merely forgetting the UI
        // grant would allow A -> B -> A settings changes to revive an old true snapshot.
        io.launch {
            capabilityLock.withLock {
                try {
                    val saved = controller.settings.load()
                    if (generation == capabilityGeneration && authorizedSettings == null) {
                        controller.authorizeImageProvider(ProviderSnapshot.capture(saved, saved.protocol, imageInputSupported = null))
                    }
                } catch (_: Exception) { /* Invalid settings already fail Controller preflight. */ }
            }
        }
    }

    fun send(conversation: Conversation?, revision: Long, text: String,
        stillCurrent: () -> Boolean) {
        val target = session
        if (closed || !visible || busy || !target.attachments.state.value.canSubmit) return
        // Text is displayed before the ordered attachment strip, and sent in exactly that order.
        val ready = target.attachments.state.value.ready.map { it.attachment }
        target.attachments.activate(ImageAttachmentDraft(target.owner, revision), ready)
        val submission = target.attachments.beginSubmission() ?: return
        val attempt = Attempt(submission, text)
        target.attempt = attempt
        outstanding++
        // Durably fence this exact owner/revision before any Controller enqueue. A restart
        // cannot reinterpret a process-lost attempt as a fresh draft with a new journal key.
        if (!persist()) {
            finish(target, attempt, ImageSendResult.Unknown(target.owner, revision, null, null))
            return
        }
        changed()
        io.launch {
            val request = try {
                val saved = controller.settings.load()
                val main = ProviderSnapshot.capture(saved, saved.protocol,
                    imageInputSupported = if (saved == authorizedSettings) true else null)
                main to controller.captureImageRoute(main)
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                val provider = request?.first
                val route = request?.second
                if (attempt.cancelled || target.abandoned || closed || !visible || !stillCurrent() || provider == null || route?.actual?.imageInputSupported != true) {
                    finish(target, attempt, ImageSendResult.Rejected(target.owner, revision,
                        "实际图片路由未配置或能力未确认；请检查辅助视觉或主模型声明，草稿保留"))
                    return@withContext
                }
                val parts = buildList {
                    if (text.isNotEmpty()) add(ImageSendPart.Text(text))
                    submission.attachments.forEach { add(ImageSendPart.Image(it)) }
                }
                attempt.handle = controller.sendImages(conversation, target.owner, revision, parts, provider, text, checkNotNull(route)) {
                    finish(target, attempt, it)
                }
                if (attempt.handle == null) finish(target, attempt,
                    ImageSendResult.Rejected(target.owner, revision, "当前无法排队发送；草稿保留"))
            }
        }
    }

    private fun finish(target: Session, attempt: Attempt, result: ImageSendResult) {
        if (target.attempt !== attempt) return
        // A malformed/mismatched receipt grants no deletion authority, and is never retried.
        val exact = imageReceiptMatches(attempt.submission, result)
        val acceptance = when {
            !exact -> ImageAttachmentAcceptance.Unknown
            result is ImageSendResult.Accepted -> ImageAttachmentAcceptance.Accepted
            result is ImageSendResult.Rejected -> ImageAttachmentAcceptance.Rejected
            else -> ImageAttachmentAcceptance.Unknown
        }
        target.uncertain = acceptance == ImageAttachmentAcceptance.Unknown
        target.attachments.finishSubmission(attempt.submission, acceptance)
        target.attempt = null
        if (exact && result is ImageSendResult.Accepted) {
            // Transfer the provisional new-chat identity to the durable conversation without
            // creating another send identity. Other selected conversations remain untouched.
            if (sessions[null] === target) {
                sessions.remove(null)
                sessions[result.conversation.id.value] = target
            }
            persist()
            delete(result.images)
            // The currently attached Activity reconciles BEFORE the global write gate opens.
            // Never deliver exclusively to the Activity which happened to submit the request.
            if (!closed) {
                settled(result, attempt.text)
                // Settlement may correctly decline navigation (e.g. recreated empty text).
                // Reconcile the still-selected slot before admitting writes to that editor.
                changed()
            }
        } else {
            persist()
            if (!closed && target === session && !target.abandoned) {
                notice(if (result is ImageSendResult.Rejected && exact) result.reason else "发送结果待核实；保留草稿，禁止重发")
            }
        }
        outstanding--
        if (!closed) changed()
    }

    private fun cancelTransient(target: Session) {
        picker?.takeIf { it.first === target }?.let { target.attachments.cancelSelection(it.second) }
        // Keep the outstanding launcher tombstone until its result returns. It must not be
        // mistaken for a newer picker's result after navigation.
        target.imports.forEach { (key, signal) ->
            target.attachments.remove(key)
            io.launch { signal.cancel() }
        }
        target.imports.clear()
        target.attachments.state.value.preview?.let(target.attachments::closePreview)
    }

    private fun abandon(target: Session) {
        target.abandoned = true
        cancelTransient(target)
        target.attempt?.cancel()
        // Ready references (especially UNKNOWN) belong to this stable draft, not this visit.
        persist()
    }

    private fun delete(images: List<ImageAttachment>) {
        if (images.isEmpty()) return
        io.launch { images.forEach { image ->
            try { controller.imageAttachments.store.remove(image.owner, image) } catch (_: Exception) { /* Retain on cleanup failure. */ }
        } }
    }

    fun close() {
        if (closed) return
        closed = true; visible = false; visit++
        abandon(session); lease.close(); historyLoader = null
        capabilityGeneration++ // A late declaration from the detached Activity cannot regrant.
        authorizedSettings = null
        authorizationLabel = "声明当前模型支持图片"
        changed = {}; notice = {}; settled = { _, _ -> }
    }

    private fun attach(changed: () -> Unit, notice: (String) -> Unit,
        settled: (ImageSendResult.Accepted, String) -> Unit): ImageChatHost {
        this.changed = changed; this.notice = notice; this.settled = settled
        closed = false; visible = true; visit++
        session.abandoned = false
        renewPreviews(null)
        return this
    }

    companion object {
        private val retained = java.util.WeakHashMap<ChatController, ImageChatHost>()
        fun obtain(controller: ChatController, changed: () -> Unit, notice: (String) -> Unit,
            settled: (ImageSendResult.Accepted, String) -> Unit): ImageChatHost =
            retained.getOrPut(controller) { ImageChatHost(controller) }.attach(changed, notice, settled)
    }
}

internal fun imageReceiptMatches(submission: ImageAttachmentSubmission, result: ImageSendResult): Boolean =
    result.owner == submission.draft.owner && result.revision == submission.draft.revision &&
        (result !is ImageSendResult.Accepted || result.conversation.id.value == result.owner.conversationId &&
            result.images == submission.attachments)

/** No cache survives the route. Returned bitmaps are owned by Compose until it releases them;
 * never recycle a bitmap which might still be displayed. Late decodes are recycled before return.
 */
private class PreviewLease(private val controller: ChatController, val conversation: String?) {
    private val open = AtomicBoolean(true)
    fun close() { open.set(false) }
    private suspend fun decode(work: () -> android.graphics.Bitmap): androidx.compose.ui.graphics.ImageBitmap {
        currentCoroutineContext().ensureActive()
        if (!open.get()) throw CancellationException("Image page left")
        val bitmap = work()
        if (!open.get() || !currentCoroutineContext().isActive) {
            bitmap.recycle(); throw CancellationException("Image page left")
        }
        return bitmap.asImageBitmap()
    }
    val draftLoader: ImageAttachmentPreviewLoader = { image, edge ->
        decode { controller.imageAttachments.decodePreview(image.owner, image, edge) }
    }
    val historyLoader = MessageImageLoader { owner, ref, edge ->
        if (conversation == null) null else decode {
            val previews = AndroidMessageImagePreviews(controller.messageImageResolver(ConversationId(conversation)))
            previews.decodePreview(app.daiyu.android.media.MessageImageOwner(owner.conversationId, owner.messageId),
                app.daiyu.android.media.MessageImageReference(
                    app.daiyu.android.media.MessageImageOwner(ref.owner.conversationId, ref.owner.messageId),
                    ref.id, ref.mime, ref.bytes, ref.sha256, ref.width, ref.height), edge)
        }
    }
}
