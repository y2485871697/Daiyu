package app.daiyu.android

import android.net.Uri
import android.os.CancellationSignal
import app.daiyu.android.media.*
import app.daiyu.android.ui.screens.chat.attachments.*
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import java.util.UUID

/** A temporary attachment owner for one edit visit, distinct from the normal composer draft. */
internal class MessageEditImageDraft(conversation: String, private val originalCount: Int,
    private val source: AndroidImageAttachments, private val scope: CoroutineScope,
    private val changed: () -> Unit, private val report: (String) -> Unit) {
    val owner = ImageAttachmentOwner(conversation, "message-edit-${UUID.randomUUID()}")
    val attachments = ImageAttachmentsState(ImageAttachmentDraft(owner, 0))
    private var live = true
    private val signals = mutableSetOf<CancellationSignal>()
    val ready get() = attachments.state.value.ready.map { it.attachment }
    val blocked get() = attachments.state.value.let { it.selection != null || it.isImporting ||
        it.items.any { item -> item is ImageAttachmentItem.Failed } }
    val loader: ImageAttachmentPreviewLoader = { image, edge ->
        withContext(Dispatchers.IO) { source.decodePreview(owner, image, edge).asImageBitmap() }
    }
    fun select(): ImageAttachmentSelection? {
        if (!live || originalCount + attachments.state.value.items.size >= 8) {
            report("每条消息最多8张图片"); return null
        }
        return attachments.requestSelection().also { changed() }
    }
    fun picked(selection: ImageAttachmentSelection, uris: List<Uri>) {
        if (!live || uris.size > 8 - originalCount - attachments.state.value.items.size) {
            attachments.cancelSelection(selection)
            if (live) { report("每条消息最多8张图片"); changed() }
            return
        }
        val keys = attachments.beginImport(selection, uris.size)
        changed()
        keys.zip(uris).forEach { (key, uri) ->
            val signal = CancellationSignal().also { signals.add(it) }
            scope.launch {
                var imported: ImageAttachment? = null
                var adopted = false
                try {
                    imported = withContext(Dispatchers.IO) { source.import(owner, uri, signal) }
                    if (live) adopted = attachments.importSucceeded(key, imported)
                } catch (failure: ImageAttachmentException) {
                    if (live) attachments.importFailed(key, failure.failure)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    if (live) attachments.importFailed(key, ImageAttachmentFailure.SOURCE_UNAVAILABLE)
                } finally {
                    signals.remove(signal)
                    if (!adopted) withContext(NonCancellable + Dispatchers.IO) {
                        // A cancelled provider may finish after close; clear only this private draft.
                        if (!live) source.store.clear(owner)
                        else imported?.let { source.store.remove(owner, it) }
                    }
                    if (live) changed()
                }
            }
        }
    }
    fun cancelSelection(selection: ImageAttachmentSelection) {
        attachments.cancelSelection(selection); if (live) changed()
    }
    val actions get() = ImageAttachmentsActions(onSelect = {},
        onPreview = { if (live) attachments.openPreview(it) },
        onDismissPreview = { attachments.closePreview(it) },
        onRemove = { key -> if (live) {
            val removed = attachments.remove(key)
            if (removed is ImageAttachmentItem.Ready) scope.launch(Dispatchers.IO) {
                source.store.remove(owner, removed.attachment)
            }
            changed()
        } })
    fun close() {
        if (!live) return
        live = false
        signals.forEach { it.cancel() }
        scope.launch(NonCancellable + Dispatchers.IO) { source.store.clear(owner) }
    }
}
