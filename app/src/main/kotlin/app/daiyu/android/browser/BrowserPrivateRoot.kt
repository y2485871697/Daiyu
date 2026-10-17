package app.daiyu.android.browser

import app.daiyu.android.browser.download.BrowserPathMapper
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Explicit worker I/O, NEVER called by preview/confirm. filesDir is the trusted Android app
 * files directory, not a model path. Only one fixed child may be created. Canonicalizing the
 * trusted filesDir accepts Android's /data/data alias; all descendants are checked NOFOLLOW.
 * Each call revalidates existing entries; a file, symlink or junction is not a usable root.
 * A returned root does not grant an effect, download destination, cookie or shared mount.
 */
internal object BrowserPrivateRoot {
    fun prepare(filesDir: Path): Path {
        val files = filesDir.toRealPath()
        val mapper = BrowserPathMapper(files)
        val root = mapper.privateFile("browser-runs")
        try { Files.createDirectory(root) } catch (_: FileAlreadyExistsException) {
            // Concurrent worker creation is fine ONLY after the same full validation below.
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "BROWSER_PRIVATE_ROOT_REQUIRED"
        }
        require(mapper.recheckPrivate(root) == root) { "BROWSER_PRIVATE_ROOT_REQUIRED" }
        // Reuse the production Windows junction/ancestor and canonical-root checks too.
        BrowserPathMapper(root)
        return root.toRealPath()
    }
}
