package app.daiyu.android

import android.content.SharedPreferences
import android.net.Uri
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.media.video.*
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*

/** Immutable handoff, NOT an acceptance or a network send. Originals remain owned by this draft. */
internal class VideoDraftRequest internal constructor(
    val conversation: String?,
    val owner: VideoAttachmentOwner,
    val revision: Long,
    val text: String,
    videos: List<VideoAttachment>,
) {
    val videos: List<VideoAttachment> = immutableVideos(videos)
}

/** Only the parent's authoritative DB outcome may be used to settle a handoff (or recover UNKNOWN). */
internal enum class VideoDraftOutcome { ACCEPTED, REJECTED, UNKNOWN }

internal class VideoDraftReceipt(
    val conversation: String?,
    val owner: VideoAttachmentOwner,
    val revision: Long,
    videos: List<VideoAttachment>,
    val outcome: VideoDraftOutcome,
    val acceptedConversation: String? = null,
) {
    val videos: List<VideoAttachment> = immutableVideos(videos)
}

internal fun videoReceiptMatches(request: VideoDraftRequest, receipt: VideoDraftReceipt): Boolean =
    request.conversation == receipt.conversation && request.owner == receipt.owner &&
        request.revision == receipt.revision && request.videos == receipt.videos &&
        (receipt.outcome != VideoDraftOutcome.ACCEPTED || receipt.acceptedConversation == request.owner.conversationId)

/** Synchronous worker API; the host NEVER holds a lock across these calls. */
internal interface VideoDraftMedia {
    fun import(owner: VideoAttachmentOwner, uri: Uri, cancellation: CancellationSignal): VideoAttachment
    fun preview(video: VideoAttachment, maxEdge: Int, cancellation: CancellationSignal): VideoPreview
    /** Enumerate only this exact draft owner; never message assets or other owners. */
    fun list(owner: VideoAttachmentOwner): List<VideoAttachment>
    /** Idempotent: absence is success; partial deletion or IO failure must throw. */
    fun remove(video: VideoAttachment)
}

private class AndroidDraftVideoMedia(private val media: AndroidVideoAttachments) : VideoDraftMedia {
    override fun import(owner: VideoAttachmentOwner, uri: Uri, cancellation: CancellationSignal) =
        media.import(owner, uri, cancellation)
    override fun preview(video: VideoAttachment, maxEdge: Int, cancellation: CancellationSignal) =
        media.preview(video.owner, video, maxEdge, cancellation)
    override fun list(owner: VideoAttachmentOwner) = media.store.list(owner)
    override fun remove(video: VideoAttachment) { media.store.remove(video.owner, video) }
}

/**
 * Main-thread confined durable draft host, independent of Controller/network/DB.
 * Retain this instance across Activity recreation and attach/detach callbacks, or restore a new
 * instance only after the old one can no longer execute journal writes (e.g. process death).
 * There must be one live writer per journal. Detached handoffs may still settle.
 * Only private imported VideoAttachment references are journalled. No persistable URI permission
 * is requested. Cancellation/visit invalidation is synchronous; provider work runs only on IO.
 */
internal class VideoChatHost internal constructor(
    private val persistence: VideoDraftPersistence,
    private val media: VideoDraftMedia,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hostDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    constructor(preferences: SharedPreferences, media: AndroidVideoAttachments) :
        this(VideoHostDraftStore(preferences), AndroidDraftVideoMedia(media))

    sealed interface Item {
        val key: String
        data class Importing(override val key: String) : Item
        data class Ready(override val key: String, val video: VideoAttachment) : Item
        data class Failed(override val key: String, val failure: VideoAttachmentFailure) : Item
    }

    data class Snapshot internal constructor(
        val conversation: String?,
        val owner: VideoAttachmentOwner,
        val revision: Long,
        val visit: Long,
        val items: List<Item>,
        val fenced: Boolean,
        val storageFailed: Boolean,
        val visible: Boolean,
        val recovering: Boolean = false,
    ) {
        val remaining: Int get() = VideoHostDraftStore.MAX_VIDEOS - items.size
        val canSubmit: Boolean get() = visible && !fenced && !storageFailed && !recovering &&
            items.isNotEmpty() && items.all { it is Item.Ready }
    }

    /** Keep this exact ticket in the Activity-result callback; never substitute the latest ticket. */
    class Selection internal constructor(
        internal val identity: Any,
        val owner: VideoAttachmentOwner,
        val revision: Long,
        val visit: Long,
        val remaining: Int,
    )

    private class Session(val slot: String?, val owner: VideoAttachmentOwner, var revision: Long,
        var fenced: Boolean, val items: MutableList<Item>,
        val pendingDeletes: MutableList<VideoAttachment> = mutableListOf()) {
        val imports = mutableMapOf<String, CancellationSignal>()
    }

    private val thread = Thread.currentThread()
    // This scope intentionally outlives detach: late imports must delete only their own new assets.
    private val io = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val sessions = linkedMapOf<String?, Session>()
    private val previews = mutableSetOf<CancellationSignal>()
    private var changed: () -> Unit = {}
    private var storageFailed = false
    private var visible = false
    private var visit = 0L
    private var picker: Selection? = null
    private var excursion: Selection? = null
    private var excursionStopped = false
    private var lifecycleBinding: DefaultLifecycleObserver? = null
    private val recoveringOwners = mutableSetOf<VideoAttachmentOwner>()
    private val deletingOwners = mutableSetOf<VideoAttachmentOwner>()
    private val reconcilingOwners = mutableSetOf<VideoAttachmentOwner>()
    private var selected: Session
    var state by mutableStateOf(Snapshot(null, VideoAttachmentOwner("initial", "initial"), 0, 0,
        emptyList(), false, false, false)); private set

    init {
        try {
            val rows = persistence.read()
            VideoHostDraftStore.validate(rows)
            rows.forEach { row ->
                val items = row.videos.mapTo(mutableListOf<Item>()) { Item.Ready(it.id, it) }
                repeat(row.blockedSlots) { items.add(Item.Failed(UUID.randomUUID().toString(), VideoAttachmentFailure.CANCELLED)) }
                sessions[row.conversation] = Session(row.conversation, row.owner, row.revision, row.fenced, items,
                    row.pendingDeletes.toMutableList())
                recoveringOwners.add(row.owner)
            }
        } catch (_: Exception) {
            // An unreadable journal is NOT permission to generate fresh, sendable identities.
            storageFailed = true
        }
        selected = sessions.getOrPut(null) { fresh(null) }
        persist()
        publish()
        // Every restored owner was committed before provider access. Its exact inventory can
        // therefore recover import->journal crashes without guessing acceptance from existence.
        if (!storageFailed) recoveringOwners.toList().forEach(::reconcile)
    }

    private fun checkThread() { check(Thread.currentThread() === thread) { "VideoChatHost is host-thread confined" } }
    private fun fresh(slot: String?) = Session(slot,
        VideoAttachmentOwner(slot ?: UUID.randomUUID().toString(), UUID.randomUUID().toString()),
        0, false, mutableListOf())
    private fun videos(target: Session) = target.items.filterIsInstance<Item.Ready>().map { it.video }
    private fun row(target: Session) = VideoHostDraftStore.Draft(target.slot, target.owner,
        target.revision, videos(target), target.fenced, target.items.count { it !is Item.Ready },
        target.pendingDeletes.toList())
    private fun persist(rows: List<VideoHostDraftStore.Draft> = sessions.values.map(::row)): Boolean {
        if (storageFailed) return false
        val saved = try { persistence.write(rows) } catch (_: Exception) { false }
        if (!saved) storageFailed = true
        return saved
    }
    private fun publish() {
        state = Snapshot(selected.slot, selected.owner, selected.revision, visit,
            Collections.unmodifiableList(ArrayList(selected.items)), selected.fenced, storageFailed, visible,
            selected.owner in recoveringOwners || selected.owner in deletingOwners)
        runCatching { changed() }
    }
    private fun current(expected: Snapshot): Boolean = visible && !storageFailed && !selected.fenced &&
        selected.owner !in recoveringOwners && selected.owner !in deletingOwners &&
        expected.owner == selected.owner && expected.revision == selected.revision &&
        expected.conversation == selected.slot && expected.visit == visit
    private fun advance(target: Session) {
        if (target.revision >= Long.MAX_VALUE - 1) storageFailed = true else target.revision++
    }

    /**
     * Production lifecycle hook. Bind once per route view; close it synchronously on route
     * departure (even if the Activity stays started). Destruction is always a real departure.
     * Only launchSelection's exact, in-memory ticket may bridge ONE onStop/onStart pair.
     */
    fun bindLifecycle(owner: LifecycleOwner, onChanged: () -> Unit = {}): AutoCloseable {
        checkThread()
        check(lifecycleBinding == null)
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (lifecycleBinding !== this) return
                if (excursionStopped && excursion === picker && validSelection(picker)) {
                    excursion = null
                    excursionStopped = false
                    changed = onChanged
                    visible = true
                    persist(); publish()
                } else attach(onChanged)
            }
            override fun onStop(owner: LifecycleOwner) {
                if (lifecycleBinding !== this) return
                if (!excursionStopped && excursion === picker && validSelection(picker)) {
                    excursionStopped = true
                    visible = false
                    changed = {}
                    // No imports are admitted while a picker is outstanding. Previews still
                    // obey the stop fence and cannot publish after this callback.
                    val signals = previews.toList()
                    previews.clear()
                    signals.forEach { runCatching { it.cancel() } }
                    publish()
                } else detach()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                if (lifecycleBinding === this) {
                    lifecycleBinding = null
                    owner.lifecycle.removeObserver(this)
                    detach()
                }
            }
        }
        lifecycleBinding = observer
        owner.lifecycle.addObserver(observer)
        return AutoCloseable {
            checkThread()
            if (lifecycleBinding === observer) {
                lifecycleBinding = null
                owner.lifecycle.removeObserver(observer)
                detach()
            }
        }
    }

    private fun validSelection(selection: Selection?): Boolean = selection != null &&
        selection === picker && !storageFailed && !selected.fenced &&
        selection.identity === selected && selection.owner == selected.owner &&
        selection.revision == selected.revision && selection.visit == visit

    /** Arm immediately around the actual system-picker launch, not merely when requesting UI. */
    fun launchSelection(selection: Selection, launch: () -> Unit): Boolean {
        checkThread()
        if (!visible || !validSelection(selection) || excursion != null) return false
        excursion = selection
        return try { launch(); true } catch (_: Exception) { selectionLaunchFailed(selection); false }
    }

    fun attach(onChanged: () -> Unit = {}) {
        checkThread()
        invalidateVisit()
        changed = onChanged
        visible = true
        retryCleanup()
        persist(); publish()
    }

    /** Real route departure; use bindLifecycle for Activity stop/start picker excursions. */
    fun detach() {
        checkThread()
        visible = false
        changed = {}
        invalidateVisit()
        persist(); publish()
    }

    fun enter(conversation: String?) {
        checkThread()
        require(conversation == null || conversation.isNotBlank() && conversation.length <= 200)
        invalidateVisit()
        selected = sessions.getOrPut(conversation) { fresh(conversation) }
        persist(); publish()
    }

    private fun invalidateVisit() {
        excursion = null
        excursionStopped = false
        visit++
        // Leave a picker tombstone until its callback/launch failure consumes that exact ticket.
        // A -> B -> A is still a different visit, even with the same owner and revision.
        val target = selected
        val imports = target.imports.toMap()
        target.imports.clear()
        if (imports.isNotEmpty()) {
            target.items.replaceAll { item ->
                if (item.key in imports) Item.Failed(item.key, VideoAttachmentFailure.CANCELLED) else item
            }
            advance(target)
        }
        val signals = imports.values + previews.toList()
        previews.clear()
        // No host lock is held. Invalidate identity/state BEFORE invoking provider cancel listeners.
        signals.forEach { runCatching { it.cancel() } }
    }

    fun requestSelection(expected: Snapshot = state): Selection? {
        checkThread()
        if (selected.pendingDeletes.isNotEmpty()) {
            retryCleanup()
            return null
        }
        if (!current(expected) || picker != null || selected.imports.isNotEmpty() || state.remaining <= 0) return null
        return Selection(selected, selected.owner, selected.revision, visit, state.remaining).also { picker = it }
    }

    fun selectionLaunchFailed(selection: Selection) {
        checkThread()
        if (picker === selection) {
            picker = null
            excursion = null
            excursionStopped = false
        }
    }

    /** Validate the captured ticket before any URI is passed to a provider. Oversized picks reject all. */
    fun picked(selection: Selection, uris: List<Uri>): Boolean {
        checkThread()
        if (picker !== selection) return false
        picker = null
        excursion = null
        excursionStopped = false
        val target = selected
        if (!visible || storageFailed || target.fenced || selection.identity !== target ||
            selection.visit != visit || selection.revision != target.revision ||
            uris.size > selection.remaining || uris.size > VideoHostDraftStore.MAX_VIDEOS - target.items.size) return false
        if (uris.isEmpty()) return true
        val work = uris.map { uri ->
            val key = UUID.randomUUID().toString()
            val signal = CancellationSignal()
            target.items.add(Item.Importing(key))
            target.imports[key] = signal
            Triple(key, signal, uri)
        }
        advance(target)
        // Persist the revision before granting provider access, even though URIs/pending work aren't saved.
        if (!persist()) {
            work.forEach { (key, signal, _) ->
                target.imports.remove(key)
                target.items.replaceAll { if (it.key == key) Item.Failed(key, VideoAttachmentFailure.STORAGE) else it }
                signal.cancel()
            }
            publish(); return false
        }
        val generation = visit
        work.forEach { (key, signal, uri) -> io.launch {
            var imported: VideoAttachment? = null
            var failure = VideoAttachmentFailure.SOURCE_UNAVAILABLE
            try { imported = media.import(target.owner, uri, signal) }
            catch (error: VideoAttachmentException) { failure = error.failure }
            catch (_: Exception) { /* Do not surface provider URIs, paths or exception messages. */ }
            withContext(hostDispatcher) {
                val video = imported
                val valid = visible && !storageFailed && !target.fenced && selected === target && visit == generation &&
                    target.imports[key] === signal && !signal.isCanceled
                target.imports.remove(key)
                if (!valid) {
                    if (video != null) delete(listOf(video))
                } else {
                    // The production importer creates this exact owner's immutable reference.
                    val owned = video?.takeIf { it.owner == target.owner }
                    target.items.replaceAll { item ->
                        if (item.key != key) item else if (owned != null) Item.Ready(key, owned) else Item.Failed(key, failure)
                    }
                    advance(target)
                    persist()
                }
                publish()
            }
        } }
        publish()
        return true
    }

    /** Failed/cancelled imports keep their quota slot until the user explicitly removes them. */
    fun remove(key: String, expected: Snapshot = state): Boolean {
        checkThread()
        if (!current(expected)) return false
        val target = selected
        val item = target.items.firstOrNull { it.key == key } ?: return false
        target.items.remove(item)
        val signal = target.imports.remove(key)
        advance(target)
        if (item is Item.Ready) target.pendingDeletes.add(item.video)
        val saved = persist()
        signal?.let { runCatching { it.cancel() } }
        if (saved) cleanup(target.owner)
        publish()
        return saved
    }

    /** Returns only a derivative tied to the original; never usable as a submission attachment. */
    suspend fun preview(video: VideoAttachment, maxEdge: Int = 512): VideoPreview? {
        checkThread()
        require(maxEdge in 1..1024)
        val target = selected
        val generation = visit
        if (!visible || storageFailed || video !in videos(target)) return null
        val signal = CancellationSignal()
        previews.add(signal)
        return try {
            val result = withContext(ioDispatcher) { media.preview(video, maxEdge, signal) }
            if (visible && selected === target && visit == generation && !signal.isCanceled &&
                video in videos(target) && result.original == video) result else null
        } catch (_: Exception) { null }
        finally { previews.remove(signal) }
    }

    /**
     * Fences + commits BEFORE calling the parent. The parent must not interpret this as acceptance.
     * A throwing callback is UNKNOWN (it could already have committed); no automatic retry is legal.
     */
    fun submit(text: String, expected: Snapshot = state, handoff: (VideoDraftRequest) -> Unit): VideoDraftRequest? {
        checkThread()
        if (!current(expected) || !state.canSubmit || picker != null) return null
        val target = selected
        val request = VideoDraftRequest(target.slot, target.owner, target.revision, text, videos(target))
        target.fenced = true
        if (!persist()) { publish(); return null }
        publish()
        try { handoff(request) } catch (_: Exception) { /* Keep the exact revision fenced as UNKNOWN. */ }
        return request
    }

    /** Recovery inspection only: these rows contain no text and must NEVER be resubmitted. */
    fun pendingRecovery(): List<VideoHostDraftStore.Draft> {
        checkThread()
        return Collections.unmodifiableList(sessions.values.filter { it.fenced }.map {
            row(it).let { saved -> saved.copy(videos = immutableVideos(saved.videos)) }
        })
    }

    /**
     * An exact authoritative receipt may settle while detached/on another route, or after restart.
     * UNKNOWN and malformed receipts grant NO deletion, revision change or resend authority.
     * ACCEPTED must use owner.conversationId, including when migrating the stable null slot.
     */
    fun settle(receipt: VideoDraftReceipt): Boolean {
        checkThread()
        if (storageFailed) return false
        val target = sessions[receipt.conversation] ?: return false
        if (!target.fenced) return false
        val request = VideoDraftRequest(target.slot, target.owner, target.revision, "", videos(target))
        if (!videoReceiptMatches(request, receipt)) return false
        if (receipt.outcome == VideoDraftOutcome.UNKNOWN) return true
        if (target.revision >= Long.MAX_VALUE - 1) return false
        val accepted = receipt.outcome == VideoDraftOutcome.ACCEPTED
        val destination = if (accepted) receipt.acceptedConversation else target.slot
        if (destination != target.slot && sessions[destination]?.let { it !== target } == true) return false
        val next = Session(destination, target.owner, target.revision + 1, false,
            if (accepted) mutableListOf() else target.items.toMutableList(),
            (target.pendingDeletes + if (accepted) request.videos else emptyList()).toMutableList())
        val rows = sessions.values.filter { it !== target }.map(::row) + row(next)
        if (!persist(rows)) { publish(); return false }
        sessions.remove(target.slot)
        sessions[destination] = next
        if (selected === target) {
            invalidateVisit()
            selected = next
        }
        // Never call discardUnreferenced(MessageVideoOwner, ...): published message assets are the parent's concern.
        if (accepted) cleanup(next.owner)
        publish()
        return true
    }

    /** Explicit/user retry plus attach/requestSelection retry; never a timer or age-based GC. */
    fun retryCleanup() {
        checkThread()
        if (storageFailed) return
        recoveringOwners.toList().forEach(::reconcile)
        sessions.values.toList().forEach { cleanup(it.owner) }
        publish()
    }

    private fun session(owner: VideoAttachmentOwner) = sessions.values.firstOrNull { it.owner == owner }

    private fun reconcile(owner: VideoAttachmentOwner) {
        if (storageFailed || !reconcilingOwners.add(owner)) return
        io.launch {
            val inventory = runCatching { media.list(owner) }.getOrNull()
            withContext(hostDispatcher) {
                reconcilingOwners.remove(owner)
                val target = session(owner)
                if (storageFailed || target == null) return@withContext
                // On error keep admission fenced and allow an explicit retry. A partial/foreign
                // inventory is never deletion authority. Protect Ready and UNKNOWN by ID, too.
                val ready = videos(target)
                if (inventory == null || inventory.any { it.owner != owner } ||
                    inventory.map { it.id }.distinct().size != inventory.size ||
                    inventory.any { asset -> ready.any { it.id == asset.id && it != asset } }) {
                    publish(); return@withContext
                }
                val protectedIds = ready.map { it.id }.toSet()
                inventory.filter { it.id !in protectedIds }.forEach { asset ->
                    if (target.pendingDeletes.none { it.id == asset.id }) target.pendingDeletes.add(asset)
                }
                // Commit exact orphan references BEFORE touching disk. Crashes before this
                // commit repeat the owner inventory; crashes after it repeat idempotent deletion.
                if (persist()) {
                    recoveringOwners.remove(owner)
                    cleanup(owner)
                }
                publish()
            }
        }
    }

    private fun delete(assets: List<VideoAttachment>) {
        if (storageFailed) return
        val owners = mutableSetOf<VideoAttachmentOwner>()
        assets.forEach { video ->
            val target = session(video.owner) ?: return@forEach
            if (videos(target).none { it.id == video.id } && target.pendingDeletes.none { it.id == video.id }) {
                target.pendingDeletes.add(video)
                owners.add(video.owner)
            }
        }
        if (owners.isNotEmpty() && persist()) owners.forEach(::cleanup)
    }

    private fun cleanup(owner: VideoAttachmentOwner) {
        val target = session(owner) ?: return
        if (storageFailed || owner in recoveringOwners || owner in deletingOwners || target.pendingDeletes.isEmpty()) return
        val pending = target.pendingDeletes.toList()
        // The committed queue alone grants deletion authority, not acceptance or resubmission.
        if (pending.any { asset -> videos(target).any { it.id == asset.id } }) return
        deletingOwners.add(owner)
        io.launch {
            val removed = pending.filter { video -> runCatching { media.remove(video) }.isSuccess }
            withContext(hostDispatcher) {
                deletingOwners.remove(owner)
                val latest = session(owner)
                if (!storageFailed && latest != null && removed.isNotEmpty()) {
                    latest.pendingDeletes.removeAll(removed.toSet())
                    persist()
                }
                // Failed records remain durable, without a tight retry loop. A later attach,
                // picker request, retryCleanup, or process restart retries the same references.
                publish()
            }
        }
    }
}
