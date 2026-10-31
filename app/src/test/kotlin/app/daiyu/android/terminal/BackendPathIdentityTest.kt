package app.daiyu.android.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.junit.Assert.assertFalse
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

    @Test fun rootFixedTransportUsesTrustedIdentityWithoutNioAndDetectsReplacement() {
        val file = virtualTransport("/system/bin/su")
        val first = BackendSelection.pathIdentities(listOf(file), false, setOf(file.path), mapOf(file.path to "su")) { "trusted:1" }
        assertEquals(listOf(BackendPathIdentity.ChrootTransport(file.path, "trusted:1")), first)
        assertNotEquals(first, BackendSelection.pathIdentities(listOf(file), false, setOf(file.path)) { "trusted:2" })
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(file), false, setOf(file.path), mapOf(file.path to "su")) {
                throw SecurityException("private path detail")
            }
        }
        assertEquals("backend_path_su_transport_identity_denied", failure.code)
        assertEquals(failure.code, failure.message)
    }

    @Test fun cwdDiagnosticKeepsRoleAndStageWithoutLeakingPath() {
        val missing = File(temporary.root, "private-missing-directory")
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(missing), false, roles = mapOf(missing.path to "cwd"))
        }
        assertEquals("backend_path_cwd_realpath_missing", failure.code)
        assertFalse(failure.message!!.contains("private-missing"))
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
        assertEquals("backend_path_path_transport_identity_unavailable", failure.code)
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
        assertEquals("backend_path_path_realpath_missing", failure.code)
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
        assertEquals("backend_path_path_realpath_missing", failure.code)
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
        val attributes = Files.readAttributes(workspace.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        assertEquals(attributes.fileKey()?.toString(), identity.key)
        assertEquals(if (identity.key == null) attributes.creationTime() else null, identity.created)
        assertEquals(snapshot, BackendSelection.pathIdentities(files, chroot = true) { "trusted" })
    }

    @Test fun keyedIdentityIgnoresAndroidCreationTimeFallbackAfterWorkspaceWrites() {
        val workspace = temporary.newFolder("writable-workspace")
        val real = workspace.toPath().toRealPath()
        fun snapshot(): BackendPathIdentity.FileSystem {
            val attributes = Files.readAttributes(real, java.nio.file.attribute.BasicFileAttributes::class.java)
            val androidAttributes = object : java.nio.file.attribute.BasicFileAttributes by attributes {
                override fun creationTime() = attributes.lastModifiedTime()
                override fun fileKey(): Any = attributes.fileKey() ?: "stable-workspace-key"
            }
            return BackendSelection.fileSystemIdentity(workspace, real, androidAttributes)
        }
        val first = snapshot()
        val before = Files.getLastModifiedTime(real)
        File(workspace, "git-config").writeText("configured")
        val changed = java.nio.file.attribute.FileTime.fromMillis(before.toMillis() + 60_000L)
        Files.setLastModifiedTime(real, changed)
        assertNotEquals(before, Files.getLastModifiedTime(real))
        assertEquals(first, snapshot())
        assertTrue(File(workspace, "git-config").delete())
        Files.setLastModifiedTime(real, java.nio.file.attribute.FileTime.fromMillis(changed.toMillis() + 60_000L))
        assertEquals(first, snapshot())
    }

    @Test fun keyedIdentityIgnoresFallbackTimeWhenExistingFileIsWritten() {
        val launcher = temporary.newFile("writable-launcher")
        val real = launcher.toPath().toRealPath()
        val attributes = Files.readAttributes(real, java.nio.file.attribute.BasicFileAttributes::class.java)
        fun identity(time: Long, key: Any = "same-inode") = BackendSelection.fileSystemIdentity(launcher, real,
            object : java.nio.file.attribute.BasicFileAttributes by attributes {
                override fun creationTime() = java.nio.file.attribute.FileTime.fromMillis(time)
                override fun fileKey(): Any = key
            })
        val first = identity(1)
        launcher.writeText("new content")
        assertEquals(first, identity(2))
        assertNotEquals(first, identity(2, "different-inode"))
    }

    @Test fun unkeyedIdentityRetainsCreationTimeFallback() {
        val file = temporary.newFile("unkeyed")
        val real = file.toPath().toRealPath()
        val attributes = Files.readAttributes(real, java.nio.file.attribute.BasicFileAttributes::class.java)
        fun identity(time: Long) = BackendSelection.fileSystemIdentity(file, real,
            object : java.nio.file.attribute.BasicFileAttributes by attributes {
                override fun creationTime() = java.nio.file.attribute.FileTime.fromMillis(time)
                override fun fileKey(): Any? = null
            })
        assertEquals(identity(1), identity(1))
        assertNotEquals(identity(1), identity(2))
    }

    @Test fun ordinaryDirectoryReplacementStillChangesSnapshot() {
        val workspace = temporary.newFolder("original-workspace")
        val first = BackendSelection.pathIdentities(listOf(workspace), chroot = false)
        val retained = File(temporary.root, "retained-workspace")
        Files.move(workspace.toPath(), retained.toPath())
        assertTrue(workspace.mkdir())
        val identity = first.single() as BackendPathIdentity.FileSystem
        val replaced = Files.readAttributes(workspace.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        if (identity.key == null && replaced.fileKey() == null) {
            val created = java.nio.file.attribute.FileTime.fromMillis(requireNotNull(identity.created).toMillis() + 86_400_000L)
            Files.getFileAttributeView(workspace.toPath(), java.nio.file.attribute.BasicFileAttributeView::class.java)
                .setTimes(null, null, created)
            assertEquals(created, Files.readAttributes(workspace.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java).creationTime())
        }
        assertNotEquals(first, BackendSelection.pathIdentities(listOf(workspace), chroot = false))
    }

    @Test fun canonicalTargetStillParticipatesInKeyedIdentity() {
        val workspace = temporary.newFolder("target-one")
        val other = temporary.newFolder("target-two")
        val attributes = Files.readAttributes(workspace.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        assertNotEquals(BackendSelection.fileSystemIdentity(workspace, workspace.toPath().toRealPath(), attributes),
            BackendSelection.fileSystemIdentity(workspace, other.toPath().toRealPath(), attributes))
    }

    @Test fun ordinaryPathReplacementStillChangesSnapshot() {
        val file = temporary.newFile("launcher")
        val first = BackendSelection.pathIdentities(listOf(file), chroot = true) { error("not a transport") }
        val replacement = temporary.newFile("replacement")
        Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val originalIdentity = first.single() as BackendPathIdentity.FileSystem
        val replaced = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        if (originalIdentity.key == null && replaced.fileKey() == null) {
            val replacementCreated = java.nio.file.attribute.FileTime.fromMillis(requireNotNull(originalIdentity.created).toMillis() + 86_400_000L)
            Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.BasicFileAttributeView::class.java)
                .setTimes(null, null, replacementCreated)
            assertEquals(replacementCreated,
                Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime())
        }
        assertNotEquals(first, BackendSelection.pathIdentities(listOf(file), chroot = true) { error("not a transport") })
    }

    @Test fun missingOrdinaryPathStillFailsClosed() {
        val file = temporary.newFile("rootfs")
        assertTrue(file.delete())
        val failure = assertThrows(BackendUnavailable::class.java) {
            BackendSelection.pathIdentities(listOf(file), chroot = true) { error("not a transport") }
        }
        assertEquals("backend_path_path_realpath_missing", failure.code)
    }
}
