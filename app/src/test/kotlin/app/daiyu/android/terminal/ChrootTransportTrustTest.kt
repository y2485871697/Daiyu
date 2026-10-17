package app.daiyu.android.terminal

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ChrootTransportTrustTest {
    private fun path(value: String): String =
        File(File(".").absoluteFile.toPath().root.toFile(), value.removePrefix("/")).path
    private fun entries() = mutableMapOf(
        path("/") to ChrootTransportTrust.Entry(0, 0x41ed),
        path("/system") to ChrootTransportTrust.Entry(0, 0x41ed),
        path("/system/bin") to ChrootTransportTrust.Entry(0, 0x41e9),
        path("/system/bin/su") to ChrootTransportTrust.Entry(0, 0x81ed),
        path("/system/bin/toybox") to ChrootTransportTrust.Entry(0, 0x81ed),
        path("/system/bin/chroot") to ChrootTransportTrust.Entry(0, 0xa1ff, "toybox"),
        path("/system/bin/env") to ChrootTransportTrust.Entry(0, 0xa1ff, path("/system/bin/toybox"))
    )
    private fun verify(path: String, entries: Map<String, ChrootTransportTrust.Entry>) =
        ChrootTransportTrust.requireTrusted(File(path)) { entries.getValue(it.path) }

    @Test fun rootOwnedSuAndRelativeAndAbsoluteToyboxLinksAreTrusted() {
        for (path in listOf("su", "chroot", "env")) verify(path("/system/bin/$path"), entries())
        for (mode in listOf(0x81c0, 0x89c0, 0x89ed, 0x8ded)) {
            verify(path("/system/bin/su"), entries().apply { this[path("/system/bin/su")] = ChrootTransportTrust.Entry(0, mode) })
        }
    }

    @Test fun writableOrForeignOwnedFilesAndAncestorsAreRejected() {
        for (path in listOf(path("/"), path("/system"), path("/system/bin"), path("/system/bin/su"), path("/system/bin/toybox"))) {
            for (change in listOf<(ChrootTransportTrust.Entry) -> ChrootTransportTrust.Entry>(
                { it.copy(uid = 10441) }, { it.copy(mode = it.mode or 0x10) },
                { it.copy(mode = it.mode or 2) }
            )) {
                val source = entries().apply { this[path] = change(getValue(path)) }
                val target = if (path.endsWith("toybox")) path("/system/bin/chroot") else path("/system/bin/su")
                assertThrows("$path", IllegalArgumentException::class.java) { verify(target, source) }
            }
        }
    }

    @Test fun symlinkCannotBypassWritableTargetAncestorOrForeignOwnership() {
        val source = entries().apply {
            this[path("/system/bin/chroot")] = ChrootTransportTrust.Entry(0, 0xa1ff, path("/data/tool"))
            this[path("/data")] = ChrootTransportTrust.Entry(0, 0x41ff)
            this[path("/data/tool")] = ChrootTransportTrust.Entry(0, 0x81ed)
        }
        assertThrows(IllegalArgumentException::class.java) { verify(path("/system/bin/chroot"), source) }
        source[path("/system/bin/chroot")] = ChrootTransportTrust.Entry(0, 0xa1ff, "../bin/toybox")
        assertThrows(IllegalArgumentException::class.java) { verify(path("/system/bin/chroot"), source) }
        source[path("/system/bin/chroot")] = ChrootTransportTrust.Entry(10441, 0xa1ff, "toybox")
        assertThrows(IllegalArgumentException::class.java) { verify(path("/system/bin/chroot"), source) }
    }

    @Test fun missingCyclicAndWrongTypeTransportsAreRejected() {
        assertThrows(NoSuchElementException::class.java) { verify(path("/system/bin/missing"), entries()) }
        val source = entries().apply { this[path("/system/bin/chroot")] = ChrootTransportTrust.Entry(0, 0xa1ff, "chroot") }
        assertThrows(IllegalArgumentException::class.java) { verify(path("/system/bin/chroot"), source) }
        for (mode in listOf(0x81a4, 0x41ed, 0x21ed)) {
            source[path("/system/bin/su")] = ChrootTransportTrust.Entry(0, mode)
            assertThrows(IllegalArgumentException::class.java) { verify(path("/system/bin/su"), source) }
        }
    }

    @Test fun identityTracksFileAncestorAndLinkTargetReplacement() {
        val source = entries()
        val su = path("/system/bin/su")
        val first = verify(su, source)
        assertEquals(first, verify(su, source))
        source[su] = source.getValue(su).copy(dev = 7, ino = 101)
        assertNotEquals(first, verify(su, source))
        val second = verify(su, source)
        val ancestor = path("/system/bin")
        source[ancestor] = source.getValue(ancestor).copy(ino = 102)
        assertNotEquals(second, verify(su, source))
        val chroot = path("/system/bin/chroot")
        val linked = verify(chroot, source)
        source[chroot] = source.getValue(chroot).copy(link = path("/system/bin/toybox"))
        assertNotEquals(linked, verify(chroot, source))
        val absoluteLink = verify(chroot, source)
        val toybox = path("/system/bin/toybox")
        source[toybox] = source.getValue(toybox).copy(ino = 103)
        assertNotEquals(absoluteLink, verify(chroot, source))
    }

    @Test fun identityStillRejectsUntrustedReplacement() {
        val source = entries()
        val su = path("/system/bin/su")
        assertTrue(verify(su, source).isNotEmpty())
        source[su] = source.getValue(su).copy(mode = 0x81ff, ino = 201)
        assertThrows(IllegalArgumentException::class.java) { verify(su, source) }
    }
}
