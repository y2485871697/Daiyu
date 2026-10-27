package app.daiyu.android.browser

import app.daiyu.android.browser.cookie.BrowserCookiePolicy
import app.daiyu.android.browser.download.BrowserPathMapper
import app.daiyu.android.browser.session.BrowserTabLease
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Production host bindings for the run-owned product permission. No guessed guest mount:
 * cookieEnvPath returns the actual app-private native file produced by BrowserCookieBackend.
 * The backend still owns POSIX 0600 creation, same-origin/live-lease checks and cleanup.
 */
internal class BrowserRunHostDefaults private constructor(
    private val authorization: BrowserRunAuthorization,
    val root: Path,
) {
    private val cookieRoot = root.resolve("cookie-offloads")

    fun authorizeCookieName(lease: BrowserTabLease, url: String, name: String): Boolean =
        authorization.allowsHostDefault(lease, "get_cookies") &&
            Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}").matches(name) && validOrigin(url)

    fun allowHistory(lease: BrowserTabLease, url: String): Boolean =
        authorization.allowsHostDefault(lease) && validOrigin(url)

    fun cookieEnvPath(lease: BrowserTabLease, file: Path): String {
        require(authorization.allowsHostDefault(lease, "get_cookies")) { "browser_cookie_owner_revoked" }
        val mapper = BrowserPathMapper(cookieRoot)
        val checked = mapper.recheckPrivate(file)
        require(checked.parent == cookieRoot && checked.fileName.toString().matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.env"))) { "browser_cookie_offload_path" }
        require(Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(checked)) { "browser_cookie_offload_path" }
        require(Files.getPosixFilePermissions(checked, LinkOption.NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------")) {
            "browser_cookie_offload_permissions"
        }
        return checked.toRealPath().toString()
    }

    private fun validOrigin(url: String): Boolean = try { BrowserCookiePolicy.origin(url); true } catch (_: Exception) { false }

    companion object {
        /** Native construction only, never preview: a fresh, unguessable directory per retained host. */
        fun prepare(authorization: BrowserRunAuthorization, privateRoot: Path): BrowserRunHostDefaults {
            val mapper = BrowserPathMapper(privateRoot)
            val root = mapper.privateFile("run-${UUID.randomUUID()}")
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            mapper.recheckPrivate(root)
            return BrowserRunHostDefaults(authorization, root)
        }
    }
}
