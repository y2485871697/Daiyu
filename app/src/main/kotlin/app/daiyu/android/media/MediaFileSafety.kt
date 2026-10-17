package app.daiyu.android.media

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Private app directories only. Checks are repeated, not just performed at construction.
 * This is not a sandbox against another process with write access to the same app UID: the
 * host must use one app-scoped store and serialize mutation with its acceptance/deletion lock.
 */
/** Anchor the host-supplied app root once. Android may alias /data/user/0 to /data/data;
 * canonicalizing that trusted parent is safe, while a symlink at the asset root is rejected.
 * All subsequent accesses use this anchor and reject replacement symlinks below it.
 */
internal fun initializeMediaRoot(root: File): File {
    val supplied = root.toPath().toAbsolutePath().normalize().toFile()
    if (Files.isSymbolicLink(supplied.toPath())) throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
    val parent = supplied.parentFile
    val anchored = if (parent == null) supplied else {
        require(parent.mkdirs() || parent.isDirectory)
        File(parent.toPath().toRealPath().toFile(), supplied.name)
    }
    checkMediaPath(anchored)
    require(anchored.mkdirs() || anchored.isDirectory)
    checkMediaPath(anchored)
    return anchored
}

internal fun checkMediaPath(file: File) {
    var path = file.toPath().toAbsolutePath().normalize()
    while (true) {
        if (Files.isSymbolicLink(path)) throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
        path = path.parent ?: break
    }
}

internal fun mediaDirectory(root: File, name: String, create: Boolean = false): File {
    checkMediaPath(root)
    if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
        throw ImageAttachmentException(ImageAttachmentFailure.STORAGE)
    }
    require(Regex("[0-9a-f]{64}(\\.(pending|deleting))?").matches(name))
    val directory = File(root, name)
    checkMediaPath(directory)
    if (create && !(directory.mkdir() || Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))) {
        throw ImageAttachmentException(ImageAttachmentFailure.STORAGE)
    }
    if (Files.exists(directory.toPath(), NOFOLLOW_LINKS) && !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
        throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
    }
    return directory
}

internal fun validateImageMetadata(value: ImageAttachment, limits: ImageAttachmentLimits) {
    if (!IMAGE_ID.matches(value.id) || !IMAGE_HASH.matches(value.sha256) || value.bytes <= 0) {
        throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT)
    }
    if (value.bytes > limits.maxImageBytes) throw ImageAttachmentException(ImageAttachmentFailure.TOO_LARGE)
    validateImageDimensions(ImageDimensions(value.mime, value.width, value.height), limits)
}

internal fun validateImageDimensions(value: ImageDimensions, limits: ImageAttachmentLimits) {
    if (value.mime !in ImageAttachmentStore.SUPPORTED_MIMES) {
        throw ImageAttachmentException(ImageAttachmentFailure.UNSUPPORTED_IMAGE)
    }
    if (value.width <= 0 || value.height <= 0) throw ImageAttachmentException(ImageAttachmentFailure.INVALID_IMAGE)
    if (value.width.toLong() * value.height > limits.maxPixels) {
        throw ImageAttachmentException(ImageAttachmentFailure.TOO_LARGE)
    }
}

internal val IMAGE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal val IMAGE_HASH = Regex("[0-9a-f]{64}")
