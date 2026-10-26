package app.daiyu.android.media

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** One app-scoped instance. Private draft assets are distinct from committed message/backup blobs. */
class ImageAttachmentStore(
    root: File,
    private val inspector: ImageInspector,
    private val limits: ImageAttachmentLimits = ImageAttachmentLimits(),
) {
    private val root = initializeMediaRoot(root)
    private val gate = Any()

    /** Takes ownership of input, including on rejection. Blocking IO must run off the UI thread. */
    fun import(
        owner: ImageAttachmentOwner,
        input: InputStream,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): ImageAttachment {
        val bytes = try {
            input.use {
                checkImageCancellation(isCancelled)
                readSource(it, isCancelled)
            }
        } catch (failure: IOException) {
            throw ImageAttachmentException(if (isCancelled()) ImageAttachmentFailure.CANCELLED
                else ImageAttachmentFailure.SOURCE_UNAVAILABLE, failure)
        }
        val dimensions = inspector.inspect(bytes)
        checkImageCancellation(isCancelled)
        validateDimensions(dimensions)
        return synchronized(gate) {
            checkImageCancellation(isCancelled)
            val current = listLocked(owner)
            if (current.size >= limits.maxImages) fail(ImageAttachmentFailure.TOO_MANY)
            if (current.sumOf { image -> image.bytes.toLong() } + bytes.size > limits.maxDraftBytes) {
                fail(ImageAttachmentFailure.TOO_LARGE)
            }
            val attachment = ImageAttachment(owner, UUID.randomUUID().toString(), dimensions.mime,
                bytes.size, imageDigest(bytes), dimensions.width, dimensions.height)
            val directory = ownerDirectory(owner, create = true)
            val data = File(directory, attachment.id + ".image")
            val metadata = File(directory, attachment.id + ".json")
            val pending = File(directory, attachment.id + ".pending")
            try {
                writeSynced(data, bytes)
                writeSynced(pending, encode(attachment).toByteArray(Charsets.UTF_8))
                checkImageCancellation(isCancelled)
                Files.move(pending.toPath(), metadata.toPath(), ATOMIC_MOVE)
                attachment
            } catch (failure: Exception) {
                pending.delete()
                data.delete()
                if (failure is ImageAttachmentException) throw failure
                throw ImageAttachmentException(ImageAttachmentFailure.STORAGE, failure)
            }
        }
    }

    fun list(owner: ImageAttachmentOwner): List<ImageAttachment> = synchronized(gate) { listLocked(owner) }

    fun read(owner: ImageAttachmentOwner, attachment: ImageAttachment): ByteArray = synchronized(gate) {
        verifyOwner(owner, attachment)
        val directory = ownerDirectory(owner, create = false)
        val metadata = File(directory, attachment.id + ".json")
        if (!metadata.exists()) fail(ImageAttachmentFailure.MISSING)
        if (decode(owner, metadata) != attachment) fail(ImageAttachmentFailure.CORRUPT)
        val file = File(directory, attachment.id + ".image")
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) fail(ImageAttachmentFailure.MISSING)
        if (file.length() != attachment.bytes.toLong()) fail(ImageAttachmentFailure.CORRUPT)
        val bytes = try { file.readBytes() } catch (failure: IOException) {
            throw ImageAttachmentException(ImageAttachmentFailure.STORAGE, failure)
        }
        if (imageDigest(bytes) != attachment.sha256) fail(ImageAttachmentFailure.CORRUPT)
        bytes
    }

    /** No request or deletion occurs here. Failure to freeze any image rejects the whole snapshot. */
    fun freeze(
        owner: ImageAttachmentOwner,
        attachments: List<ImageAttachment>,
        imageInputSupported: Boolean,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): List<FrozenImageAttachment> = synchronized(gate) {
        val references = attachments.toList()
        checkImageCancellation(isCancelled)
        if (references.isNotEmpty() && !imageInputSupported) fail(ImageAttachmentFailure.MODEL_UNSUPPORTED)
        if (references.size > limits.maxImages) fail(ImageAttachmentFailure.TOO_MANY)
        if (references.map { it.id }.toSet().size != references.size) fail(ImageAttachmentFailure.CORRUPT)
        if (references.sumOf { it.bytes.toLong() } > limits.maxDraftBytes) fail(ImageAttachmentFailure.TOO_LARGE)
        references.map {
            checkImageCancellation(isCancelled)
            FrozenImageAttachment.source(it, File(ownerDirectory(owner, create = false), it.id + ".image")) { synchronized(gate) { openVerified(owner, it, isCancelled) } }
                .also { image -> image.openSource().use { } }
        }.also { checkImageCancellation(isCancelled) }
    }

    private fun openVerified(owner: ImageAttachmentOwner, attachment: ImageAttachment,
        cancelled: () -> Boolean): java.io.FileInputStream {
        verifyOwner(owner, attachment)
        val directory = ownerDirectory(owner, create = false)
        val metadata = File(directory, attachment.id + ".json")
        if (!metadata.exists()) fail(ImageAttachmentFailure.MISSING)
        if (decode(owner, metadata) != attachment) fail(ImageAttachmentFailure.CORRUPT)
        val file = File(directory, attachment.id + ".image")
        checkMediaPath(file)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) fail(ImageAttachmentFailure.MISSING)
        val input = java.io.FileInputStream(file)
        try {
            if (input.channel.size() != attachment.bytes.toLong()) fail(ImageAttachmentFailure.CORRUPT)
            copyVerifiedImage(input, object : java.io.OutputStream() {
                override fun write(value: Int) {}
                override fun write(buffer: ByteArray, offset: Int, length: Int) {}
            }, attachment.bytes.toLong(), attachment.sha256, cancelled)
            input.channel.position(0)
            return input
        } catch (failure: Exception) { input.close(); throw failure }
    }

    /** Draft-only deletion. Accepted history must use MessageImageAssetStore references. */
    fun remove(owner: ImageAttachmentOwner, attachment: ImageAttachment): Boolean = synchronized(gate) {
        verifyOwner(owner, attachment)
        val directory = ownerDirectory(owner, create = false)
        val metadata = File(directory, attachment.id + ".json")
        if (!metadata.exists()) return@synchronized false
        if (decode(owner, metadata) != attachment) fail(ImageAttachmentFailure.CORRUPT)
        if (!metadata.delete()) fail(ImageAttachmentFailure.STORAGE)
        val data = File(directory, attachment.id + ".image")
        if (data.exists() && !data.delete()) fail(ImageAttachmentFailure.STORAGE)
        true
    }

    fun clear(owner: ImageAttachmentOwner) = synchronized(gate) {
        listLocked(owner).forEach { remove(owner, it) }
        cleanUnpublished(owner)
    }

    /** Only this draft's abandoned staging/data files; committed references are retained. */
    fun cleanUnpublished(owner: ImageAttachmentOwner) = synchronized(gate) {
        val directory = ownerDirectory(owner, create = false)
        if (!directory.exists()) return@synchronized
        val files = directory.listFiles() ?: fail(ImageAttachmentFailure.STORAGE)
        files.filter { it.name.endsWith(".pending") ||
            (it.name.endsWith(".image") && !File(directory, it.name.removeSuffix(".image") + ".json").exists())
        }.forEach { if (!it.delete()) fail(ImageAttachmentFailure.STORAGE) }
    }

    private fun listLocked(owner: ImageAttachmentOwner): List<ImageAttachment> {
        val directory = ownerDirectory(owner, create = false)
        if (!directory.exists()) return emptyList()
        return (directory.listFiles() ?: fail(ImageAttachmentFailure.STORAGE))
            .filter { it.name.endsWith(".json") }.map { decode(owner, it) }.sortedBy { it.id }
    }

    private fun ownerDirectory(owner: ImageAttachmentOwner, create: Boolean): File {
        return mediaDirectory(root, owner.directoryName(), create)
    }

    private fun readSource(input: InputStream, isCancelled: () -> Boolean): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val result = ByteArrayOutputStream()
        try {
            while (true) {
                checkImageCancellation(isCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    if (result.size() >= limits.maxImageBytes) fail(ImageAttachmentFailure.TOO_LARGE)
                    result.write(byte)
                } else {
                    if (result.size().toLong() + count > limits.maxImageBytes) fail(ImageAttachmentFailure.TOO_LARGE)
                    result.write(buffer, 0, count)
                }
            }
        } catch (failure: IOException) {
            throw ImageAttachmentException(if (isCancelled()) ImageAttachmentFailure.CANCELLED
                else ImageAttachmentFailure.SOURCE_UNAVAILABLE, failure)
        }
        if (result.size() == 0) fail(ImageAttachmentFailure.INVALID_IMAGE)
        return result.toByteArray()
    }

    private fun validateDimensions(value: ImageDimensions) {
        validateImageDimensions(value, limits)
    }

    private fun verifyOwner(owner: ImageAttachmentOwner, value: ImageAttachment) {
        if (value.owner != owner) fail(ImageAttachmentFailure.OWNER_MISMATCH)
        if (!ID.matches(value.id)) fail(ImageAttachmentFailure.CORRUPT)
    }

    private fun encode(value: ImageAttachment): String = JSONObject()
        .put("version", 1).put("conversation", value.owner.conversationId).put("draft", value.owner.draftId)
        .put("id", value.id).put("mime", value.mime).put("bytes", value.bytes).put("sha256", value.sha256)
        .put("width", value.width).put("height", value.height).toString()

    private fun decode(owner: ImageAttachmentOwner, file: File): ImageAttachment {
        try {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > 4096) fail(ImageAttachmentFailure.CORRUPT)
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("version") != 1) fail(ImageAttachmentFailure.CORRUPT)
            val value = ImageAttachment(ImageAttachmentOwner(json.getString("conversation"), json.getString("draft")),
                json.getString("id"), json.getString("mime"), json.getInt("bytes"), json.getString("sha256"),
                json.getInt("width"), json.getInt("height"))
            verifyOwner(owner, value)
            if (file.name != value.id + ".json" || !HASH.matches(value.sha256) || value.bytes !in 1..limits.maxImageBytes) {
                fail(ImageAttachmentFailure.CORRUPT)
            }
            validateDimensions(ImageDimensions(value.mime, value.width, value.height))
            return value
        } catch (failure: Exception) {
            if (failure is ImageAttachmentException) throw failure
            throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT, failure)
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        if (!file.createNewFile()) fail(ImageAttachmentFailure.STORAGE)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun fail(reason: ImageAttachmentFailure): Nothing = throw ImageAttachmentException(reason)

    companion object {
        val SUPPORTED_MIMES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
