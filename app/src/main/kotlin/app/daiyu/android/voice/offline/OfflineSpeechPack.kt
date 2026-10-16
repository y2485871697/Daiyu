package app.daiyu.android.voice.offline

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit model download only. Verified complete packs are published by one directory rename. */
internal object OfflineSpeechPack {
    data class State(
        val checking: Boolean = true,
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val downloading: Boolean = false,
        val downloadedBytes: Long = 0,
        val error: Boolean = false,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var download: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE)
    fun directory(context: Context) = File(context.noBackupFilesDir, "speech/${SpeechModelManifest.REVISION}")

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val ready = withContext(Dispatchers.IO) { verify(directory(app)) }
            val enabled = ready && prefs(app).getBoolean("enabled", false)
            if (!ready) prefs(app).edit().putBoolean("enabled", false).apply()
            mutable.value = State(checking = false, ready = ready, enabled = enabled)
        }
    }

    /** Download completion deliberately does not call this method. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !SpeechInputPolicy.canEnable(mutable.value.ready, mutable.value.checking, mutable.value.downloading)) return false
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        mutable.value = mutable.value.copy(enabled = enabled)
        return true
    }

    fun cancelDownload() { download?.cancel() }

    fun download(context: Context) {
        if (mutable.value.checking || mutable.value.downloading || mutable.value.ready) return
        val app = context.applicationContext
        mutable.value = mutable.value.copy(downloading = true, downloadedBytes = 0, error = false)
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = directory(app)
                    // Recover the narrow cancel-after-rename race without redownloading a
                    // complete verified pack. This still never enables recording.
                    if (verify(target)) return@withContext
                    // Same filesystem as final directory. Never expose a partly downloaded pack.
                    val stage = File(target.parentFile, ".${SpeechModelManifest.REVISION}.download")
                    check(stage.isDirectory || stage.mkdirs()) { "Cannot create private speech directory" }
                    var done = 0L
                    for (asset in SpeechModelManifest.assets) {
                        ensureActive()
                        val staged = File(stage, asset.name)
                        if (!valid(staged, asset)) {
                            val partial = File(stage, asset.name + ".part")
                            val connection = openHttps(SpeechModelManifest.BASE_URL + asset.name)
                            try {
                                val declared = connection.contentLengthLong
                                check(declared < 0 || declared == asset.bytes) { "Model length mismatch" }
                                var received = 0L
                                var lastProgress = 0L
                                connection.inputStream.use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            received += count
                                            check(received <= asset.bytes) { "Model file exceeds manifest size" }
                                            output.write(buffer, 0, count)
                                            if (received - lastProgress >= 256 * 1024) {
                                                lastProgress = received
                                                withContext(Dispatchers.Main) {
                                                    mutable.value = mutable.value.copy(downloadedBytes = done + received)
                                                }
                                            }
                                        }
                                        output.fd.sync()
                                    }
                                }
                                ensureActive()
                                check(valid(partial, asset)) { "Model checksum mismatch" }
                                ensureActive()
                                check(partial.renameTo(staged)) { "Cannot commit verified file" }
                            } finally {
                                connection.disconnect()
                                partial.delete()
                            }
                        }
                        done += asset.bytes
                        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(downloadedBytes = done) }
                    }
                    ensureActive()
                    check(verify(stage)) { "Incomplete model pack" }
                    ensureActive()
                    // An invalid previous pack was never admitted for recognition by initialize().
                    check(!target.exists() || target.deleteRecursively()) { "Cannot replace invalid model pack" }
                    check(stage.renameTo(target)) { "Cannot atomically publish model pack" }
                }
                mutable.value = State(checking = false, ready = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = true)
            } finally {
                mutable.value = mutable.value.copy(downloading = false)
                download = null
            }
        }
    }

    /** HF redirects to CDN hosts. Follow only HTTPS, with bounded redirects and timeouts. */
    private fun openHttps(url: String): HttpURLConnection {
        var next = URI(url)
        repeat(6) {
            check(next.scheme == "https" && next.rawUserInfo == null) { "Insecure model URL" }
            val connection = next.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val code = connection.responseCode
                if (code == 200) return connection
                check(code in listOf(301, 302, 303, 307, 308)) { "Model download HTTP failure" }
                val location = checkNotNull(connection.getHeaderField("Location"))
                next = next.resolve(location)
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        error("Too many model redirects")
    }

    internal fun valid(file: File, asset: SpeechModelManifest.Asset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }.getOrDefault(false)

    internal fun verify(dir: File) = SpeechModelManifest.assets.all { valid(File(dir, it.name), it) }
}
