package app.daiyu.android.voice.duplex

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android PCM adapter for the migrated session. Hardware AEC is best effort, not a
 * guarantee of echo-free audio. Speculative ASR still cannot truncate playback.
 * A context opts duplex calls into owned communication routing; dictation keeps its old route.
 * Never changes global volume. The host owns permission/lifecycle.
 */
class AndroidDuplexAudio(context: Context? = null) : DuplexAudio {
    private val route = context?.let {
        DuplexRouteLease(AndroidCommunicationRoute(it.applicationContext.getSystemService(AudioManager::class.java)))
    }
    override val speaker: Boolean get() = route?.speaker ?: true
    override fun setSpeaker(speaker: Boolean): Boolean = synchronized(lock) {
        !closing && opened && route?.setSpeaker(speaker) == true
    }
    private data class Packet(val bytes: ByteArray, val epoch: Long)
    private val lock = Any()
    private val drained = CountDownLatch(1)
    private val queue = ArrayDeque<Packet>()
    private var queuedBytes = 0
    private var epoch = 0L
    @Volatile private var closing = false
    private var opened = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var capture: Thread? = null
    private var playback: Thread? = null
    private var failure: (() -> Unit)? = null
    @Volatile private var closeFailure: Throwable? = null

    @SuppressLint("MissingPermission")
    override fun open(onFailure: () -> Unit) {
        synchronized(lock) {
            check(!opened && !closing) { "audio_single_use" }
            opened = true; failure = onFailure
            check(route?.setSpeaker(true) != false) { "communication_route_unavailable" }
            val inputMin = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputMin = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputMin > 0 && outputMin > 0) { "audio_unavailable" }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inputMin * 2, 640 * 8))
            check(recorder!!.state == AudioRecord.STATE_INITIALIZED) { "microphone_unavailable" }
            // Effect availability is device/route dependent. Do not require a vendor AEC.
            aec = runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder!!.audioSessionId) else null
            }.getOrNull()
            runCatching { aec?.enabled = true }
            noise = runCatching {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder!!.audioSessionId) else null
            }.getOrNull()
            runCatching { noise?.enabled = true }
            player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(
                    if (route != null) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(outputMin * 2, 24_000)).build()
            check(player!!.state == AudioTrack.STATE_INITIALIZED) { "speaker_unavailable" }
            player!!.play()
            playback = Thread({ playLoop() }, "doubao-duplex-playback").also { it.start() }
        }
    }

    override fun startCapture(onPcm: (ByteArray) -> Unit) {
        synchronized(lock) {
            check(opened && !closing && capture == null)
            val record = checkNotNull(recorder)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            capture = Thread({
                try {
                    val frame = ByteArray(DoubaoDuplexProtocol.INPUT_FRAME_BYTES)
                    while (!closing) {
                        val count = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                        if (closing) break
                        check(count >= 0 && count % 2 == 0) { "microphone_read_failed" }
                        if (count > 0) onPcm(frame.copyOf(count))
                    }
                } catch (_: Exception) { signalFailure() }
            }, "doubao-duplex-capture").also { it.start() }
        }
    }

    override fun enqueue(pcm: ByteArray, epoch: Long) {
        synchronized(lock) {
            if (closing || epoch != this.epoch) return
            check(pcm.size % 2 == 0 && queuedBytes + pcm.size <= MAX_QUEUE_BYTES) { "playback_queue_limit" }
            queue.addLast(Packet(pcm.copyOf(), epoch)); queuedBytes += pcm.size
        }
    }

    override fun flush(epoch: Long) {
        synchronized(lock) {
            if (closing) return
            require(epoch >= this.epoch)
            this.epoch = epoch; queue.clear(); queuedBytes = 0
            // The writer uses this same lock for each small nonblocking write: no
            // in-flight old partial packet can be re-enqueued after hardware flush.
            player?.let { it.pause(); it.flush(); it.play() }
        }
    }

    private fun playLoop() {
        try {
            while (!closing) {
                val packet = synchronized(lock) {
                    if (queue.isEmpty()) null else queue.removeFirst().also { queuedBytes -= it.bytes.size }
                }
                if (packet == null) { Thread.sleep(5); continue }
                var offset = 0
                while (!closing && offset < packet.bytes.size) {
                    val n = synchronized(lock) {
                        if (closing || packet.epoch != epoch) -1
                        else checkNotNull(player).write(packet.bytes, offset,
                            minOf(2048, packet.bytes.size - offset), AudioTrack.WRITE_NON_BLOCKING).also {
                            check(it >= 0) { "playback_write_failed" }
                        }
                    }
                    if (n < 0) break
                    offset += n
                    if (n == 0) Thread.sleep(5)
                }
            }
        } catch (_: Exception) { signalFailure() }
    }

    private fun signalFailure() {
        if (!closing) {
            // Never make a worker wait for another worker's synchronous teardown.
            val callback = synchronized(lock) { failure }
            Thread({ if (!closing) callback?.invoke() }, "doubao-duplex-audio-failure").start()
        }
    }

    private var drainStarted = false
    private var stopFailure: Throwable? = null
    override fun stopImmediately() {
        synchronized(lock) {
            if (closing) return
            closing = true; epoch++; queue.clear(); queuedBytes = 0; failure = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (t: Throwable) { if (stopFailure == null) stopFailure = t }
            }
            // Fence writes with the playback lock and silence hardware BEFORE any joins.
            recorder?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) attempt { record.stop() }
            }
            player?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    attempt { track.pause() }; attempt { track.flush() }
                }
            }
        }
    }

    override fun closeAndDrain() {
        stopImmediately()
        val first = synchronized(lock) {
            if (drainStarted) false else { drainStarted = true; true }
        }
        if (!first) {
            // A worker may already be inside a callback while a different closer joins it.
            if (Thread.currentThread() === capture || Thread.currentThread() === playback) return
            check(drained.await(3, TimeUnit.SECONDS)) { "audio_release_pending" }
            closeFailure?.let { throw it }
            return
        }
        var problem: Throwable? = stopFailure
        fun attempt(action: () -> Unit) { try { action() } catch (t: Throwable) { if (problem == null) problem = t } }
        try {
            for (worker in listOfNotNull(capture, playback)) {
                if (worker !== Thread.currentThread()) attempt {
                    worker.join(2_000)
                    check(!worker.isAlive) { "audio_worker_not_drained" }
                }
            }
            synchronized(lock) {
                player?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        attempt { track.pause() }; attempt { track.flush() }
                    }
                    attempt { track.release() }
                }
                attempt { aec?.release() }; attempt { noise?.release() }; attempt { recorder?.release() }
                player = null; recorder = null; aec = null; noise = null
                attempt { route?.close() }
            }
        } finally { closeFailure = problem; drained.countDown() }
        problem?.let { throw it }
    }

    companion object { private const val MAX_QUEUE_BYTES = 2 * 1024 * 1024 }
}

/** Small testable ownership boundary: never restore over a route/mode changed by another user. */
internal interface DuplexRoutePort {
    var mode: Int
    val communicationMode: Int
    val current: Int?
    fun target(speaker: Boolean): Int?
    fun isSpeaker(route: Int?): Boolean
    fun select(route: Int): Boolean
    fun isAvailable(route: Int): Boolean
    /** Release our explicit preference; return the system route observed after release.
     * Failure must throw, not be reported as a successful release with a null route.
     */
    fun clearSelection(): Int?
}

internal class DuplexRouteLease(private val port: DuplexRoutePort) : AutoCloseable {
    private var acquired = false
    private var closed = false
    private var previousMode = 0
    private var previousRoute: Int? = null
    private var ownedRoute: Int? = null
    val speaker: Boolean get() = port.isSpeaker(port.current)
    @Synchronized fun setSpeaker(speaker: Boolean): Boolean {
        if (closed) return false
        return try {
            val target = port.target(speaker) ?: return false
            if (acquired && (port.mode != port.communicationMode || port.current != ownedRoute)) return false
            if (!acquired) {
                previousMode = port.mode; previousRoute = port.current
                port.mode = port.communicationMode
                ownedRoute = previousRoute; acquired = true
            }
            val selected = port.select(target)
            if (selected && port.current == target && port.mode == port.communicationMode) {
                ownedRoute = target
                this.speaker == speaker
            } else {
                // Remember only a route we actually installed. A changed external route is not ours.
                if (port.current == target) ownedRoute = target
                false
            }
        } catch (_: Exception) { false }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (!acquired || !stillOwnsRoute()) return
        val restoreRoute = previousRoute?.takeIf { port.isAvailable(it) }
        // Enumeration can race a route/mode change. Never clear someone else's preference.
        if (!stillOwnsRoute()) return
        val restoredRoute = if (restoreRoute == null) {
            // A disconnected previous device is not a platform release failure. Relinquish
            // our explicit selection instead; Android may now report a non-null default.
            port.clearSelection()
        } else {
            val restored = port.select(restoreRoute)
            if (!restored) {
                // The device may have disappeared between enumeration and selection. Only
                // fall back for disappearance, never mask a rejected available-device restore.
                check(!port.isAvailable(restoreRoute)) { "communication_route_restore_failed" }
                if (!stillOwnsRoute()) return
                port.clearSelection()
            } else restoreRoute
        }
        // Restore mode only while the route observed at release is still current.
        if (port.mode == port.communicationMode && port.current == restoredRoute) port.mode = previousMode
    }

    private fun stillOwnsRoute(): Boolean =
        port.mode == port.communicationMode && port.current == ownedRoute
}

/** Legacy speakerphone control cannot start or confirm SCO (or BLE) routing. */
internal fun legacyPrivateRouteTarget(deviceTypes: List<Int>): Int? {
    // Even a co-enumerated earpiece does not prove where speakerphone=false will
    // route while Bluetooth is present. Be conservative rather than claim SCO works.
    if (deviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET }) return null
    return if (deviceTypes.any {
        it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }) 0 else null
}

@Suppress("DEPRECATION")
private class AndroidCommunicationRoute(private val manager: AudioManager) : DuplexRoutePort {
    override var mode: Int
        get() = manager.mode
        set(value) { manager.mode = value }
    override val communicationMode: Int get() = AudioManager.MODE_IN_COMMUNICATION
    override val current: Int? get() = if (Build.VERSION.SDK_INT >= 31)
        manager.communicationDevice?.id else if (manager.isSpeakerphoneOn) 1 else 0
    override fun target(speaker: Boolean): Int? {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = manager.availableCommunicationDevices
            return if (speaker) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id
            else devices.firstOrNull { it.type in PRIVATE_ROUTES }?.id
        }
        if (speaker) return 1
        return legacyPrivateRouteTarget(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    }
    override fun isSpeaker(route: Int?): Boolean = if (Build.VERSION.SDK_INT >= 31)
        manager.communicationDevice?.let { it.id == route && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } == true
        else route == 1
    override fun select(route: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val device = manager.availableCommunicationDevices.firstOrNull { it.id == route } ?: return false
            return manager.setCommunicationDevice(device) && current == route
        }
        manager.isSpeakerphoneOn = route == 1
        return current == route
    }
    override fun isAvailable(route: Int): Boolean = if (Build.VERSION.SDK_INT >= 31)
        manager.availableCommunicationDevices.any { it.id == route } else route == 0 || route == 1

    override fun clearSelection(): Int? {
        // clearCommunicationDevice is a void platform API: a normal return releases
        // the explicit preference, not necessarily the active default device itself.
        // Do not catch platform failures or equate a non-null default with failure.
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
        else manager.isSpeakerphoneOn = false
        return current
    }
    companion object {
        private val PRIVATE_ROUTES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
