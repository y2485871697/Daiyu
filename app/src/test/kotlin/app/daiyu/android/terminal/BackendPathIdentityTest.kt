package app.daiyu.android.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendPathIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    // Keep Android's fixed spelling on every host and model su without a NIO-visible inode.
    private fun virtualTransport(path: String): File = object : File(path) {
        override fun getPath(): String = path
        override fun toPath(): Path = throw NoSuchFileException(path)
    }

    @Test fun chrootFixedTransportsDoNotRequireNioRealpath() {
        val files = listOf("su", "chroot", "env", "sh").map { virtualTransport("/system/bin/$it") }
        val inspected = mutableListOf<String>()
        val snapshot = BackendSelection.pathIdentities(files, chroot = true) {
            inspected += it.path
            "trusted:${it.path}"
        }
        assertEquals(files.map { it.path }, inspected)
        assertEquals(files.map { BackendPathIdentity.ChrootTransport(it.path, "trusted:${it.path}") }, snapshot)
    }

    @Test fun trustedTransportReplacementChangesSnapshot() {
        val files = listOf(virtualTransport("/system/bin/su"))
        val first = BackendSelection.pathIdentities(files, chroot = true) { "ancestor-link-target:1" }
        assertEquals(first, BackendSelection.pathIdentities(files, chroot = true) { "ancestor-link-target:1" })
        assertNotEquals(first, BackendSelection.pathIdentities(files, chroot = true) { "ancestor-link-target:2" })
    }

    @Test fun rejectedTransportNeverFallsBackToFilesystemIdentity() {
        val existing = temporary.newFile("untrusted-su")
        val transport = object : File(existing.path) {
            override fun getPath(): String = "/system/bin/su"
        }
        assertTrue(Files.exists(transport.toPath()))
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(transport), chroot = true) {
                throw IllegalStateException("untrusted transport")
            }
        }
        assertEquals("backend_path_unavailable", failure.code)
    }

    @Test fun transportPreflightFailureIsBackendUnavailableNotJournalUnknown() {
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.chrootTransportFiles { throw IllegalStateException("private transport details") }
        }
        assertEquals("linux_chroot_transport_unavailable", failure.code)
        assertEquals("linux_chroot_transport_unavailable", failure.message)
    }

    @Test fun successfulTransportPreflightPreservesTheFixedFiles() {
        val files = listOf(virtualTransport("/system/bin/su"), virtualTransport("/system/bin/chroot"))
        assertEquals(files, BackendSelection.chrootTransportFiles { files })
    }

    @Test fun androidAndProotKeepStrictNioEvenForFixedSpelling() {
        var inspected = false
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(virtualTransport("/system/bin/su")), chroot = false) {
                inspected = true
                "trusted"
            }
        }
        assertEquals("backend_path_unavailable", failure.code)
        assertEquals(false, inspected)
    }

    @Test fun chrootNeverAppliesTransportExceptionToOtherPaths() {
        var inspected = false
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(virtualTransport("/private/rootfs")), chroot = true) {
                inspected = true
                "trusted"
            }
        }
        assertEquals("backend_path_unavailable", failure.code)
        assertEquals(false, inspected)
    }

    @Test fun mixedChrootSnapshotKeepsRootfsAndWorkspaceFilesystemIdentity() {
        val rootfs = temporary.newFolder("rootfs")
        val workspace = temporary.newFolder("workspace")
        val files = listOf(rootfs, virtualTransport("/system/bin/chroot"), workspace)
        val snapshot = BackendSelection.pathIdentities(files, chroot = true) { "trusted" }
        assertTrue(snapshot[0] is BackendPathIdentity.FileSystem)
        assertTrue(snapshot[1] is BackendPathIdentity.ChrootTransport)
        assertTrue(snapshot[2] is BackendPathIdentity.FileSystem)
        val identity = snapshot[2] as BackendPathIdentity.FileSystem
        assertEquals(workspace.toPath().toRealPath().toString(), identity.realPath)
        assertEquals(Files.readAttributes(workspace.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString(), identity.key)
        assertEquals(snapshot, BackendSelection.pathIdentities(files, chroot = true) { "trusted" })
    }

    @Test fun ordinaryPathReplacementStillChangesSnapshot() {
        val file = temporary.newFile("launcher")
        val first = BackendSelection.pathIdentities(listOf(file), chroot = true) { error("not a transport") }
        val replacement = temporary.newFile("replacement")
        Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertNotEquals(first, BackendSelection.pathIdentities(listOf(file), chroot = true) { error("not a transport") })
    }

    @Test fun missingOrdinaryPathStillFailsClosed() {
        val file = temporary.newFile("rootfs")
        assertTrue(file.delete())
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(file), chroot = true) { error("not a transport") }
        }
        assertEquals("backend_path_unavailable", failure.code)
    }
}
