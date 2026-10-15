package app.daiyu.android.browser.download

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Roots come from an explicit host grant. No implicit /sdcard or guest filesystem access. */
class BrowserPathMapper(privateRoot: Path, mounts: Map<String, Path> = emptyMap()) {
    private val privateRoot = checkedRoot(privateRoot)
    private val mounts = mounts.mapValues { (name, path) ->
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "BROWSER_MOUNT_NAME" }
        checkedRoot(path)
    }

    fun privateFile(relative: String): Path = resolve(privateRoot, relative)
    fun mappedFile(guestPath: String): Path {
        val prefix = "/workspace/mounts/"
        require(guestPath.startsWith(prefix)) { "BROWSER_MAPPING_NOT_GRANTED" }
        val parts = guestPath.removePrefix(prefix).split('/', limit = 2)
        require(parts.size == 2) { "BROWSER_MAPPING_PATH" }
        return resolve(mounts[parts[0]] ?: error("BROWSER_MAPPING_NOT_GRANTED"), parts[1])
    }

    fun recheckPrivate(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(privateRoot) && normalized != privateRoot) { "BROWSER_PATH_OUTSIDE_ROOT" }
        return resolve(privateRoot, privateRoot.relativize(normalized).toString())
    }

    private fun resolve(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains('\u0000')) { "BROWSER_PATH_INVALID" }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && root.toRealPath() == root) { "BROWSER_ROOT_CHANGED" }
        require(!java.nio.file.Paths.get(relative).isAbsolute) { "BROWSER_PATH_ABSOLUTE" }
        val parts = relative.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "BROWSER_PATH_TRAVERSAL" }
        val candidate = root.resolve(relative).normalize()
        require(candidate.startsWith(root) && candidate != root) { "BROWSER_PATH_OUTSIDE_ROOT" }
        var current = root
        require(!Files.isSymbolicLink(root)) { "BROWSER_PATH_SYMLINK" }
        for (part in parts) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "BROWSER_PATH_SYMLINK" }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                require(current.toRealPath().startsWith(root)) { "BROWSER_PATH_OUTSIDE_ROOT" }
        }
        return candidate
    }
    private fun checkedRoot(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized)) {
            "BROWSER_ROOT_MISSING"
        }
        require(normalized.toRealPath() == normalized) { "BROWSER_ROOT_SYMLINK" }
        return normalized
    }
}
