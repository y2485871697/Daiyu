package app.daiyu.android.browser

import app.daiyu.android.browser.download.BrowserPathMapper
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

class BrowserPathMapperTest {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private fun rejects(work: () -> Unit) {
        try { work(); fail("redirected root accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private fun link(path: Path, target: Path) {
        if (windows) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", path.toString(), target.toString())
                .redirectErrorStream(true).start()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "junction creation timed out" }
            check(process.exitValue() == 0) { "junction fixture creation failed" }
            check(Files.exists(path) && Files.isSameFile(path, target))
        } else Files.createSymbolicLink(path, target)
    }
    @Test fun canonicalRootsSupportNestedRechecksWithoutAcceptingExternalBackslashes() {
        val root = Files.createTempDirectory("browser root canonical")
        try {
            val mapper = BrowserPathMapper(root, mapOf("granted" to root))
            Files.createDirectory(root.resolve("sub"))
            val file = mapper.privateFile("sub/file")
            Files.write(file, byteArrayOf(1))
            assertEquals(root.toRealPath().resolve("sub/file"), file)
            assertEquals(file, mapper.recheckPrivate(file))
            assertEquals(file, mapper.mappedFile("/workspace/mounts/granted/sub/file"))
            rejects { mapper.privateFile("sub\\file") }
            rejects { mapper.privateFile("../escape") }
            rejects { mapper.mappedFile("/workspace/mounts/ungranted/file") }
            if (windows) {
                val alias = Path.of(root.toString().uppercase(Locale.ROOT))
                assertTrue(Files.isSameFile(root, alias))
                val aliased = BrowserPathMapper(alias)
                assertEquals(file, aliased.privateFile("sub/file"))
                assertEquals(file, aliased.recheckPrivate(file))
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun redirectedRootsAndAncestorsAreRejectedForPrivateAndMountedPaths() {
        val base = Files.createTempDirectory("browser redirect test")
        val target = Files.createDirectory(base.resolve("target"))
        val child = Files.createDirectory(target.resolve("child"))
        val alias = base.resolve("alias")
        try {
            link(alias, target)
            for (root in listOf(alias, alias.resolve("child"))) {
                rejects { BrowserPathMapper(root) }
                rejects { BrowserPathMapper(child, mapOf("granted" to root)) }
            }
        } finally { Files.deleteIfExists(alias); base.toFile().deleteRecursively() }
    }
    @Test fun rootReplacementCannotRedirectEitherGrantOrRecheck() {
        val base = Files.createTempDirectory("browser replacement test")
        val root = Files.createDirectory(base.resolve("root"))
        val target = Files.createDirectory(base.resolve("target"))
        try {
            val mapper = BrowserPathMapper(root, mapOf("granted" to root))
            val path = mapper.privateFile("file")
            Files.move(root, base.resolve("original")); link(root, target)
            rejects { mapper.privateFile("file") }
            rejects { mapper.mappedFile("/workspace/mounts/granted/file") }
            rejects { mapper.recheckPrivate(path) }
            Files.list(target).use { assertEquals(0L, it.count()) }
        } finally { Files.deleteIfExists(root); base.toFile().deleteRecursively() }
    }
    @Test fun ancestorReplacementCannotRedirectEitherGrantOrRecheck() {
        val base = Files.createTempDirectory("browser ancestor test")
        val parent = Files.createDirectory(base.resolve("parent"))
        val root = Files.createDirectory(parent.resolve("root"))
        val target = Files.createDirectory(base.resolve("target"))
        Files.createDirectory(target.resolve("root"))
        try {
            val mapper = BrowserPathMapper(root, mapOf("granted" to root))
            val path = mapper.privateFile("file")
            Files.move(parent, base.resolve("original")); link(parent, target)
            rejects { mapper.privateFile("file") }
            rejects { mapper.mappedFile("/workspace/mounts/granted/file") }
            rejects { mapper.recheckPrivate(path) }
            Files.list(target.resolve("root")).use { assertEquals(0L, it.count()) }
        } finally { Files.deleteIfExists(parent); base.toFile().deleteRecursively() }
    }
}
