package app.daiyu.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SpeechError
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.SpeechPlaybackState
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.account.AccountPreviewRequest
import app.daiyu.android.voice.account.VoiceAccountIdentity
import app.daiyu.android.voice.account.VoiceAccountPreviewPort
import app.daiyu.android.voice.network.OkHttpVoiceTransport
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import app.daiyu.android.voice.providers.SpeechSynthesisClient
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * One saved configuration/page incarnation per instance. All public methods and callbacks are MAIN
 * confined; scope must use Main. Construction, admission changes and page entry never create a player.
 * Production uses ONLY ActivityVoiceAudio.gate; gate injection is an isolated JVM test seam.
 * The factory must return a fresh, ungated VoiceHostPlayer with synchronous, strict close/drain.
 */
internal class ActivityVoiceAccountPreviewHost(
    private val scope: CoroutineScope,
    private val configuration: SpeechProviderConfig,
    private val admission: (AccountPreviewRequest) -> Boolean,
    private val gate: AudioOwnerGate = ActivityVoiceAudio.gate,
    private val factory: (AccountPreviewRequest) -> VoiceHostPlayer,
) : VoiceAccountPreviewPort, AutoCloseable {
    private class Entry(val request: AccountPreviewRequest, val listener: VoiceAccountPreviewPort.Listener, val serial: Long) {
        val audioOwner = VoiceOwner("account-preview", UUID.randomUUID().toString())
        val playerOwner = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        lateinit var lease: AudioOwnerGate.Lease
        var player: VoiceHostPlayer? = null
        var observation: Job? = null
        var tail: Job? = null
        var started = false
        var terminal = false
    }
    private var active: Entry? = null
    private var serial = 0L
    private var closed = false
    private var releaseFailure: Throwable? = null

    private fun admitted(request: AccountPreviewRequest): Boolean = !closed &&
        sameConfiguration(configuration, request.configuration) &&
        request.configuration.engine == SpeechEngine.DOUBAO &&
        !request.configuration.model.contains("seed-audio", ignoreCase = true) &&
        (request.voice.id.startsWith("S_") || request.voice.id.startsWith("etaClone")) &&
        request.voice.availableFor(VoiceAccountIdentity.from(request.configuration).key) && admission(request)

    override fun play(request: AccountPreviewRequest, listener: VoiceAccountPreviewPort.Listener): VoiceAccountPreviewPort.Lease {
        // Rejection must not steal another owner's microphone or stop a valid current preview.
        if (!runCatching { admitted(request) }.getOrDefault(false)) {
            listener.failed()
            return VoiceAccountPreviewPort.Lease { }
        }
        val entry = Entry(request, listener, ++serial)
        try {
            entry.lease = gate.acquire(entry.audioOwner, { runCatching { admitted(request) }.getOrDefault(false) }) {
                try { teardown(entry); notifyTerminal(entry, failed = false) }
                catch (failure: Exception) { notifyTerminal(entry, failed = true); throw failure }
            }
            active = entry
            val player = factory(request)
            entry.player = player // Install before bind so a throwing bind still undergoes strict close.
            check(admitted(request))
            player.bindOwner(entry.playerOwner)
            entry.observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.state.collect { accept(entry, player, it) }
            }
            if (active === entry && !entry.terminal) {
                val accepted = player.play(entry.playerOwner, entry.requestId, PREVIEW_TEXT)
                accept(entry, player, player.state.value)
                if (!accepted && active === entry) finish(entry, failed = true)
            }
        } catch (_: Exception) {
            if (active === entry) finish(entry, failed = true) else notifyTerminal(entry, failed = true)
        }
        return VoiceAccountPreviewPort.Lease { stop(entry) }
    }

    /** Parent MUST invoke on config revision/account/page/foreground changes, not just on next click. */
    fun admissionChanged() {
        val entry = active ?: return
        if (!runCatching { admitted(entry.request) }.getOrDefault(false)) stop(entry)
    }
    fun stop() { active?.let { stop(it) } }
    /** Synchronous exact teardown + retained failure + singleton gate health, not stop() optimism. */
    fun stopPreviewStrictly(): Boolean {
        val entry = active
        return try {
            if (entry != null && !gate.cancel(entry.audioOwner)) return false
            active == null && releaseFailure == null && gate.releaseHealthy()
        } catch (failure: Throwable) {
            releaseFailure = failure
            entry?.let { notifyTerminal(it, failed = true) }
            false
        }
    }
    private fun stop(entry: Entry) {
        if (active !== entry) return
        try { gate.cancel(entry.audioOwner) }
        catch (_: Exception) { notifyTerminal(entry, failed = true) } // Gate remains poisoned, never bare release.
    }
    private fun accept(entry: Entry, source: VoiceHostPlayer, value: SpeechPlaybackState) {
        if (active !== entry || entry.terminal || entry.player !== source || !gate.current(entry.lease) ||
            value.owner != entry.playerOwner || value.requestId != entry.requestId) return
        if (!runCatching { admitted(entry.request) }.getOrDefault(false)) { stop(entry); return }
        when (value.phase) {
            SpeechPhase.SPEAKING -> if (!entry.started && entry.tail == null) {
                entry.started = true
                entry.listener.started() // Actual player's SPEAKING, never Boolean/bytes/QUEUED evidence.
            }
            SpeechPhase.COMPLETED -> if (entry.tail == null) {
                // MediaPlayer completion is software evidence. Keep ownership across the bounded tail.
                entry.tail = scope.launch {
                    delay(COMPLETION_TAIL_MS)
                    if (active === entry) finish(entry, failed = false)
                }
            }
            SpeechPhase.ERROR, SpeechPhase.UNAVAILABLE -> finish(entry, failed = true)
            SpeechPhase.STOPPED, SpeechPhase.CLOSED -> finish(entry, failed = false)
            else -> Unit
        }
    }
    private fun finish(entry: Entry, failed: Boolean) {
        if (active !== entry) return
        try {
            if (gate.releaseAfter(entry.lease) { teardown(entry) }) notifyTerminal(entry, failed)
        } catch (_: Exception) { notifyTerminal(entry, failed = true) }
    }
    private fun teardown(entry: Entry) {
        if (active === entry) active = null // Fence before cancellation/reentrant platform callbacks.
        entry.observation?.cancel(); entry.observation = null
        entry.tail?.cancel(); entry.tail = null
        // Never use close() or drop the resource before strict confirmation. Failure poisons the gate.
        try { entry.player?.closeStrict() }
        catch (failure: Throwable) { releaseFailure = failure; throw failure }
        entry.player = null
    }
    private fun notifyTerminal(entry: Entry, failed: Boolean) {
        if (entry.terminal) return
        entry.terminal = true
        scope.launch {
            yield() // A gate cancellation callback runs inside its release barrier; deliver only afterwards.
            if (serial == entry.serial) {
                if (failed) entry.listener.failed() else entry.listener.stopped()
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        stop()
    }
    companion object {
        const val PREVIEW_TEXT = "你好，这是当前账户音色的试听。"
        internal const val COMPLETION_TAIL_MS = 150L

        /** No CDN read: one explicit, potentially billable existing synthesis request per click. */
        fun forContext(context: Context, scope: CoroutineScope, configuration: SpeechProviderConfig,
            admission: (AccountPreviewRequest) -> Boolean): ActivityVoiceAccountPreviewHost {
            val app = context.applicationContext
            return ActivityVoiceAccountPreviewHost(scope, configuration, admission) { request ->
                AccountPreviewSpeechPlayer(app, scope, request, admission = { admission(request) })
            }
        }
        private fun sameConfiguration(a: SpeechProviderConfig, b: SpeechProviderConfig) =
            a.engine == b.engine && a.baseUrl == b.baseUrl && a.apiKey == b.apiKey && a.model == b.model &&
                a.providerId == b.providerId && a.customHeaders == b.customHeaders
    }
}

/** Existing RemoteVoiceHostPlayer architecture, specialized for the fourth personal-voice argument.
 * No gate here. IO only synthesizes bytes; MediaPlayer/file lifecycle and state are main-confined.
 * Strict close waits for BOTH the synthesis worker and the existing HTTP adapter's actual callback.
 */
internal class AccountPreviewSpeechPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val request: AccountPreviewRequest,
    private val admission: () -> Boolean,
    private val calls: AccountPreviewCalls = AccountPreviewCalls(),
) : VoiceHostPlayer {
    private val mutable = MutableStateFlow(SpeechPlaybackState())
    override val state = mutable.asStateFlow()
    private var owner: String? = null
    private var worker: Job? = null
    private var delivery: Job? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var fenced = false
    private var releaseConfirmed = false
    private var used = false
    override fun bindOwner(owner: String) { check(!fenced && this.owner == null); this.owner = owner }
    override fun play(owner: String, requestId: String, text: String): Boolean {
        if (fenced || used || this.owner != owner || !admission() || text != ActivityVoiceAccountPreviewHost.PREVIEW_TEXT) return false
        used = true
        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
        // Independent IO dispatcher: strict main-thread close can wait without a main/IO join deadlock.
        val work = CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
            var bytes: ByteArray? = null
            try {
                bytes = SpeechSynthesisClient(OkHttpVoiceTransport(calls)).synthesize(
                    request.configuration, text, request.voice.id, request.voice)
                ensureActive()
                val audio = bytes
                // Queued delivery captures bytes but cannot touch MediaPlayer after the main fence.
                delivery = scope.launch {
                    try {
                        if (!fenced && admission()) prepare(owner, requestId, checkNotNull(audio))
                        else if (!fenced) fail(owner, requestId)
                    } catch (_: Exception) { if (!fenced) fail(owner, requestId) }
                    finally { audio?.fill(0) }
                }
                bytes = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                delivery = scope.launch { if (!fenced) fail(owner, requestId) }
            } finally { bytes?.fill(0) }
        }
        worker = work
        work.start()
        return true
    }
    private fun prepare(owner: String, requestId: String, bytes: ByteArray) {
        check(!fenced && admission())
        file = File.createTempFile("account-preview-", ".audio", context.cacheDir)
        checkNotNull(file).writeBytes(bytes)
        val next = MediaPlayer()
        player = next
        next.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA).build())
        next.setOnPreparedListener { source ->
            if (fenced || player !== source) return@setOnPreparedListener
            try {
                check(admission())
                source.start()
                mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.SPEAKING)
            } catch (_: Exception) { fail(owner, requestId) }
        }
        next.setOnCompletionListener { source ->
            if (!fenced && player === source) mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.COMPLETED)
        }
        next.setOnErrorListener { source, _, _ ->
            if (!fenced && player === source) fail(owner, requestId)
            true
        }
        next.setDataSource(checkNotNull(file).absolutePath)
        next.prepareAsync()
    }
    private fun fail(owner: String, requestId: String) {
        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.ERROR, SpeechError.PLAYBACK_FAILED)
    }
    override fun stop(owner: String): Boolean {
        if (this.owner != owner) return false
        closeStrict(); return true
    }
    override fun closeStrict() {
        if (releaseConfirmed) return
        check(!fenced) { "account_preview_release_unconfirmed" }
        fenced = true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STRICT_DRAIN_MS)
        var failure: Exception? = null
        fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { if (failure == null) failure = e } }
        attempt { worker?.cancel() }
        // Close admission before waiting: no worker can open a late HTTP call.
        attempt { calls.cancelAndDrain(deadline) }
        attempt {
            val done = CountDownLatch(1)
            val handle = worker?.invokeOnCompletion { done.countDown() }
            try {
                if (worker != null) check(done.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) { "account_preview_worker_not_drained" }
            } finally { handle?.dispose() }
        }
        // Worker is now fenced even on failure; callbacks/commands cannot create media later.
        attempt { delivery?.cancel() }; delivery = null
        val media = player
        attempt { media?.reset() }
        attempt { media?.release() }
        attempt { file?.let { check(!it.exists() || it.delete()) { "account_preview_cache_not_removed" } } }
        failure?.let { throw IllegalStateException("account_preview_release_unconfirmed") }
        worker = null; player = null; file = null
        releaseConfirmed = true
    }
    override fun close() = closeStrict()
    companion object { internal const val STRICT_DRAIN_MS = 2_000L }
}

/** Tracks completion of the EXISTING bounded/cancellable HTTP transport, not a new network stack.
 * Cancel of a suspended continuation alone does not prove the OkHttp response callback has drained.
 */
internal class AccountPreviewCalls(private val delegate: Call.Factory = OkHttpVoiceTransport.client) : Call.Factory {
    private val lock = Any()
    private var closed = false
    private var call: Call? = null
    private var enqueued = false
    private val done = CountDownLatch(1)
    override fun newCall(request: Request): Call = synchronized(lock) {
        check(!closed && call == null) { "account_preview_call_not_admitted" }
        val original = delegate.newCall(request)
        call = original
        object : Call by original {
            override fun execute(): Response = error("account_preview_async_only")
            override fun clone(): Call = error("account_preview_no_retry")
            override fun enqueue(responseCallback: Callback) {
                synchronized(lock) {
                    check(!closed && !enqueued) { "account_preview_call_not_admitted" }
                    enqueued = true
                    try {
                        original.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                try { responseCallback.onFailure(call, e) } finally { done.countDown() }
                            }
                            override fun onResponse(call: Call, response: Response) {
                                try { responseCallback.onResponse(call, response) } finally { done.countDown() }
                            }
                        })
                    } catch (failure: Exception) { done.countDown(); throw failure }
                }
            }
        }
    }
    fun cancelAndDrain(deadlineNanos: Long) {
        val wait = synchronized(lock) { closed = true; call?.cancel(); enqueued }
        if (wait) check(done.await(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) { "account_preview_http_not_drained" }
    }
}

private fun remainingNanos(deadline: Long) = (deadline - System.nanoTime()).coerceAtLeast(0L)
