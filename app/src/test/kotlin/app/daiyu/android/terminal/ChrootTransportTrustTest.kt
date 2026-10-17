package app.daiyu.android.terminal

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ChrootTransportTrustTest {
    private fun entries() = mutableMapOf(
        "/" to ChrootTransportTrust.Entry(0, 0x41ed),
        "/system" to ChrootTransportTrust.Entry(0, 0x41ed),
        "/system/bin" to ChrootTransportTrust.Entry(0, 0x41e9),
        "/system/bin/su" to ChrootTransportTrust.Entry(0, 0x81ed),
        "/system/bin/toybox" to ChrootTransportTrust.Entry(0, 0x81ed),
        "/system/bin/chroot" to ChrootTransportTrust.Entry(0, 0xa1ff, "toybox"),
        "/system/bin/env" to ChrootTransportTrust.Entry(0, 0xa1ff, "/system/bin/toybox")
    )
    private fun verify(path: String, entries: Map<String, ChrootTransportTrust.Entry>) =
        ChrootTransportTrust.requireTrusted(File(path)) { entries.getValue(it.path) }

    @Test fun rootOwnedSuAndRelativeAndAbsoluteToyboxLinksAreTrusted() {
        for (path in listOf("su", "chroot", "env")) verify("/system/bin/$path", entries())
        for (mode in listOf(0x81c0, 0x89c0, 0x89ed, 0x8ded)) {
            verify("/system/bin/su", entries().apply { this["/system/bin/su"] = ChrootTransportTrust.Entry(0, mode) })
        }
    }

    @Test fun writableOrForeignOwnedFilesAndAncestorsAreRejected() {
        for (path in listOf("/", "/system", "/system/bin", "/system/bin/su", "/system/bin/toybox")) {
            for (change in listOf<(ChrootTransportTrust.Entry) -> ChrootTransportTrust.Entry>(
                { it.copy(uid = 10441) }, { it.copy(mode = it.mode or 0x10) },
                { it.copy(mode = it.mode or 2) }
            )) {
                val source = entries().apply { this[path] = change(getValue(path)) }
                val target = if (path.endsWith("toybox")) "/system/bin/chroot" else "/system/bin/su"
                assertThrows("$path", IllegalArgumentException::class.java) { verify(target, source) }
            }
        }
    }

    @Test fun symlinkCannotBypassWritableTargetAncestorOrForeignOwnership() {
        val source = entries().apply {
            this["/system/bin/chroot"] = ChrootTransportTrust.Entry(0, 0xa1ff, "/data/tool")
            this["/data"] = ChrootTransportTrust.Entry(0, 0x41ff)
            this["/data/tool"] = ChrootTransportTrust.Entry(0, 0x81ed)
        }
        assertThrows(IllegalArgumentException::class.java) { verify("/system/bin/chroot", source) }
        source["/system/bin/chroot"] = ChrootTransportTrust.Entry(0, 0xa1ff, "../bin/toybox")
        assertThrows(IllegalArgumentException::class.java) { verify("/system/bin/chroot", source) }
        source["/system/bin/chroot"] = ChrootTransportTrust.Entry(10441, 0xa1ff, "toybox")
        assertThrows(IllegalArgumentException::class.java) { verify("/system/bin/chroot", source) }
    }

    @Test fun missingCyclicAndWrongTypeTransportsAreRejected() {
        assertThrows(NoSuchElementException::class.java) { verify("/system/bin/missing", entries()) }
        val source = entries().apply { this["/system/bin/chroot"] = ChrootTransportTrust.Entry(0, 0xa1ff, "chroot") }
        assertThrows(IllegalArgumentException::class.java) { verify("/system/bin/chroot", source) }
        for (mode in listOf(0x81a4, 0x41ed, 0x21ed)) {
            source["/system/bin/su"] = ChrootTransportTrust.Entry(0, mode)
            assertThrows(IllegalArgumentException::class.java) { verify("/system/bin/su", source) }
        }
    }
}
