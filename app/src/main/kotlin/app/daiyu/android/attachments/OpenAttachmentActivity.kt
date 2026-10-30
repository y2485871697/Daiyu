package app.daiyu.android.attachments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.daiyu.android.ChatApplication
import app.daiyu.android.MainActivity
import kotlinx.coroutines.*

/** Process-local handoff contains only completed private copies, never external URI permissions. */
internal object OpenedAttachmentInbox {
    data class Batch(val conversation: String?, val documents: List<OpenedFileStore.Document>)
    private var pending: Batch? = null
    @Synchronized fun offer(batch: Batch): Boolean = if (pending != null) false else { pending = batch; true }
    @Synchronized fun take(): Batch? = pending.also { pending = null }
    @Synchronized fun hasPending() = pending != null
}

internal object OpenedFileIntents {
    const val MAX_FILES = 16
    @Suppress("DEPRECATION")
    fun uris(intent: Intent): List<Uri> {
        require(intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE))
        val values = linkedSetOf<Uri>()
        fun add(uri: Uri?) {
            if (uri == null) return
            require(uri.toString().length <= 2048 && uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && !android.provider.DocumentsContract.isTreeUri(uri))
            require(uri.toString().none { it.isISOControl() })
            values += uri
            require(values.size <= MAX_FILES)
        }
        add(intent.data)
        if (intent.action == Intent.ACTION_SEND) add(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            require(streams.size <= MAX_FILES)
            streams.forEach(::add)
        }
        intent.clipData?.let { clip ->
            require(clip.itemCount <= MAX_FILES)
            repeat(clip.itemCount) { add(clip.getItemAt(it).uri) }
        }
        require(values.isNotEmpty())
        return values.toList()
    }
}

/** Android Open-with/share entry. It copies transient sources before handing control to chat. */
class OpenAttachmentActivity : ComponentActivity() {
    private val cancellation = CancellationSignal()
    private var receiving: Job? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) { finish(); return } // Never replay a partially received external Intent.
        app.daiyu.android.diagnostics.WindowFramePerformance.install(this,
            (application as ChatApplication).controller.diagnostics, "attachment-receive")
        setContentView(TextView(this).apply { text = "正在添加附件…"; gravity = android.view.Gravity.CENTER })
        val sources = try { OpenedFileIntents.uris(intent) } catch (_: Exception) {
            notice("文件打开失败；请选择有效文件后重试"); finish(); return
        }
        val conversation = (application as ChatApplication).controller.preferences.getString("selected", null)
        val store = OpenedFileStore(applicationContext)
        receiving = lifecycleScope.launch {
            val documents = mutableListOf<OpenedFileStore.Document>()
            var handedOff = false
            var failed = 0
            try {
                withContext(Dispatchers.IO) {
                    var remaining = OpenedFileStore.MAX_BATCH_BYTES
                    sources.forEach { uri ->
                        cancellation.throwIfCanceled()
                        try {
                            val document = store.import(uri, intent.type, remaining, cancellation)
                            documents += document; remaining -= document.size
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
                        catch (_: Exception) { failed++ }
                    }
                }
                ensureActive()
                if (documents.isEmpty()) { notice("未添加附件：读取权限已失效、文件不可读或超过128 MiB上限"); return@launch }
                if (failed > 0) notice("${documents.size}个文件已接收，${failed}个未添加（不可读或超限）")
                // Start first; the actual handoff is made before this main-thread callback returns.
                startActivity(Intent(this@OpenAttachmentActivity, MainActivity::class.java)
                    .setAction("app.daiyu.android.OPEN_ATTACHMENTS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                handedOff = OpenedAttachmentInbox.offer(OpenedAttachmentInbox.Batch(conversation, documents.toList()))
                if (!handedOff) notice("上一批附件尚未处理；请稍后重新打开")
            } catch (cancelled: android.os.OperationCanceledException) {
                throw CancellationException("attachment_cancelled").apply { initCause(cancelled) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("附件接收失败；原草稿保留，请重新打开文件") }
            finally {
                if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { documents.forEach(store::remove) }
                finish()
            }
        }
    }
    override fun onStop() { super.onStop(); cancellation.cancel(); receiving?.cancel() }
    private fun notice(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
