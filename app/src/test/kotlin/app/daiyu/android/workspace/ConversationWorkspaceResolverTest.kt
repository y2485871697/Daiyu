package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversationWorkspaceResolverTest {
    private fun fixture(work: (File, File) -> Unit) {
        val temporary = Files.createTempDirectory("conversation-workspace-").toFile().canonicalFile
        val files = File(temporary, "files").apply { mkdir() }
        try {
            work(files, temporary)
        } finally {
            // Do not follow any of the directory symlinks installed by these tests.
            Files.walk(temporary.toPath()).use { paths ->
                paths.sorted(compareByDescending { it.nameCount }).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun approved(files: File): WorkspaceRootfsRoot = WorkspaceRootfsRoot(
        "environment", "generation", File(files, "linux/environment/generation/rootfs").apply { mkdirs() },
    )

    private fun rootBinding(path: String = "/", generation: String = "generation") =
        ConversationWorkspaceBinding.Rootfs("environment", generation, path)

    private fun expectFailure(message: String, action: () -> Unit) {
        try {
            action()
            fail("Expected $message")
        } catch (failure: RuntimeException) {
            assertEquals(message, failure.message)
        }
    }

    @Test fun constructionDoesNotInspectOrCreateStorage() = fixture { _, temporary ->
        val missing = File(temporary, "missing-files")
        ConversationWorkspaceResolver(missing)
        assertFalse(missing.exists())
        val conflict = File(temporary, "file-not-storage").apply { writeText("preserve") }
        ConversationWorkspaceResolver(conflict)
        assertEquals("preserve", conflict.readText())
    }

    @Test fun defaultPrivateCreatesOnlyWorkspaceAndPreservesExistingFiles() = fixture { files, _ ->
        val resolver = ConversationWorkspaceResolver(files)
        assertArrayEquals(emptyArray<String>(), files.list())
        val binding = ConversationWorkspaceBinding.Private()
        val first = resolver.resolve(binding)
        assertEquals(binding, first.binding)
        assertEquals(File(files, "workspace"), first.hostDirectory)
        assertNull(first.guestDirectory)
        assertNull(first.rootfs)
        val saved = File(first.hostDirectory, "saved.txt").apply { writeText("preserve") }
        assertEquals(first, resolver.resolve(binding))
        assertEquals("preserve", saved.readText())
        assertArrayEquals(arrayOf("workspace"), files.list())
    }

    @Test fun existingCustomPrivateDoesNotCreateDefaultOrRequireApproval() = fixture { files, _ ->
        val custom = File(files, "conversations/one").apply { mkdirs() }
        val saved = File(custom, "saved").apply { writeText("preserve") }
        val binding = ConversationWorkspaceBinding.Private(custom.path)
        val result = ConversationWorkspaceResolver(files).resolve(binding)
        assertEquals(binding, result.binding)
        assertEquals(custom, result.hostDirectory)
        assertNull(result.guestDirectory)
        assertNull(result.rootfs)
        assertFalse(File(files, "workspace").exists())
        assertEquals("preserve", saved.readText())
    }

    @Test fun privateRejectsExternalPrefixSiblingAndStorageRoot() = fixture { files, temporary ->
        val outside = File(temporary, "outside").apply { mkdir() }
        val sibling = File(temporary, "${files.name}-sibling").apply { mkdir() }
        val resolver = ConversationWorkspaceResolver(files)
        for (path in listOf(outside.path, sibling.path, files.path, File(outside, "not-created").path)) {
            expectFailure("workspace_private_root_required") {
                resolver.resolve(ConversationWorkspaceBinding.Private(path))
            }
        }
        assertFalse(File(outside, "not-created").exists())
        assertFalse(File(files, "workspace").exists())
    }

    @Test fun privateRejectsTraversalRelativeAndNonNormalizedPaths() = fixture { files, _ ->
        File(files, "existing").mkdir()
        val resolver = ConversationWorkspaceResolver(files)
        for (path in listOf("relative", "", "${files.path}/existing/../new", "${files.path}/./existing",
            "${files.path}//existing", "${files.path}/existing/", "${files.path}/bad\u0000path")) {
            expectFailure("workspace_private_root_required") {
                resolver.resolve(ConversationWorkspaceBinding.Private(path))
            }
        }
        assertFalse(File(files, "new").exists())
    }

    @Test fun missingCustomDirectoryAndFileConflictsAreNeverCreatedOrReplaced() = fixture { files, _ ->
        val resolver = ConversationWorkspaceResolver(files)
        val missing = File(files, "missing/child")
        expectFailure("workspace_path_unavailable") {
            resolver.resolve(ConversationWorkspaceBinding.Private(missing.path))
        }
        assertFalse(missing.parentFile.exists())
        val custom = File(files, "custom").apply { writeText("custom-file") }
        val default = File(files, "workspace").apply { writeText("default-file") }
        for (binding in listOf(ConversationWorkspaceBinding.Private(custom.path), ConversationWorkspaceBinding.Private())) {
            expectFailure("workspace_path_unavailable") { resolver.resolve(binding) }
        }
        assertEquals("custom-file", custom.readText())
        assertEquals("default-file", default.readText())
    }

    @Test fun trustedStorageAliasAcceptsAliasAndCanonicalPrivatePaths() = fixture { files, temporary ->
        val custom = File(files, "custom").apply { mkdir() }
        // Include an alias nested under its canonical target to exercise overlapping prefixes.
        for (alias in listOf(File(temporary, "files-alias"), File(files, "self-alias"))) {
            Files.createSymbolicLink(alias.toPath(), files.toPath())
            val resolver = ConversationWorkspaceResolver(alias)
            for (path in listOf(File(alias, "custom").path, custom.path)) {
                assertEquals(custom, resolver.resolve(ConversationWorkspaceBinding.Private(path)).hostDirectory)
            }
            assertEquals(File(files, "workspace"), resolver.resolve(ConversationWorkspaceBinding.Private()).hostDirectory)
        }
    }

    @Test fun privateRejectsRootIntermediateAndDanglingSymlinks() = fixture { files, temporary ->
        val target = File(files, "target/child").apply { mkdirs() }
        val outside = File(temporary, "outside").apply { mkdir() }
        val links = listOf(
            File(files, "workspace") to outside,
            File(files, "linked-root") to target,
            File(files, "linked-parent") to target.parentFile,
            File(files, "dangling") to File(temporary, "not-created"),
        )
        for ((link, destination) in links) Files.createSymbolicLink(link.toPath(), destination.toPath())
        val resolver = ConversationWorkspaceResolver(files)
        for (binding in listOf(ConversationWorkspaceBinding.Private(),
            ConversationWorkspaceBinding.Private(File(files, "linked-root").path),
            ConversationWorkspaceBinding.Private(File(files, "linked-parent/child").path),
            ConversationWorkspaceBinding.Private(File(files, "dangling").path))) {
            expectFailure("workspace_symlink_not_supported") { resolver.resolve(binding) }
        }
        assertTrue(target.isDirectory)
        assertArrayEquals(emptyArray<String>(), outside.list())
        assertFalse(File(temporary, "not-created").exists())
    }

    @Test fun rootfsMapsGuestRootAndNestedDirectoryWithoutCreatingPrivateDefault() = fixture { files, _ ->
        val approved = approved(files)
        val nested = File(approved.rootfs, "home/user/project").apply { mkdirs() }
        val resolver = ConversationWorkspaceResolver(files)
        for ((guest, host) in listOf("/" to approved.rootfs, "/home/user/project" to nested)) {
            val binding = rootBinding(guest)
            val result = resolver.resolve(binding, approved)
            assertEquals(binding, result.binding)
            assertEquals(host, result.hostDirectory)
            assertEquals(guest, result.guestDirectory)
            assertEquals(approved, result.rootfs)
            val relative = approved.rootfs.toPath().relativize(result.hostDirectory.toPath())
                .joinToString("/") { it.toString() }
            assertEquals(guest, if (relative.isEmpty()) "/" else "/$relative")
        }
        assertFalse(File(files, "workspace").exists())
    }

    @Test fun rootfsRequiresExactExplicitApprovalAndNeverFallsBack() = fixture { files, _ ->
        val approved = approved(files)
        val resolver = ConversationWorkspaceResolver(files)
        expectFailure("workspace_rootfs_changed") { resolver.resolve(rootBinding()) }
        expectFailure("workspace_rootfs_changed") { resolver.resolve(rootBinding(generation = "old"), approved) }
        expectFailure("workspace_rootfs_changed") {
            resolver.resolve(ConversationWorkspaceBinding.Rootfs("other-environment", "generation", "/"), approved)
        }
        expectFailure("workspace_rootfs_changed") {
            resolver.resolve(rootBinding(), approved.copy(generation = "new"))
        }
        assertFalse(File(files, "workspace").exists())
        assertArrayEquals(emptyArray<String>(), approved.rootfs.list())
    }

    @Test fun rootfsRejectsExternalRootsPrefixSiblingsStorageItselfAndTraversal() = fixture { files, temporary ->
        val outside = File(temporary, "outside").apply { mkdir() }
        val sibling = File(temporary, "${files.name}-sibling").apply { mkdir() }
        val resolver = ConversationWorkspaceResolver(files)
        for (root in listOf(outside, sibling, files, File("relative"), File(files, "linux/../other"),
            File(files, "bad\u0000root"))) {
            expectFailure("workspace_private_root_required") {
                resolver.resolve(rootBinding(), WorkspaceRootfsRoot("environment", "generation", root))
            }
        }
        assertArrayEquals(emptyArray<String>(), outside.list())
        assertFalse(File(files, "other").exists())
    }

    @Test fun rootfsRejectsMalformedGuestPathsBeforeAnyCreation() = fixture { files, temporary ->
        val approved = approved(files)
        val resolver = ConversationWorkspaceResolver(files)
        for (guest in listOf("", "relative", "/../outside", "/home/../other", "/./home", "//home", "/home//user",
            "/home/", "/bad\u0000path", "${temporary.path}/new/../../outside")) {
            expectFailure("workspace_path_unavailable") { resolver.resolve(rootBinding(guest), approved) }
        }
        assertArrayEquals(emptyArray<String>(), approved.rootfs.list())
        assertFalse(File(temporary, "outside").exists())
    }

    @Test fun missingRootfsAndGuestDirectoriesDoNotCreateAnything() = fixture { files, temporary ->
        val resolver = ConversationWorkspaceResolver(files)
        val missingRoot = File(files, "missing/rootfs")
        expectFailure("workspace_path_unavailable") {
            resolver.resolve(rootBinding(), WorkspaceRootfsRoot("environment", "generation", missingRoot))
        }
        assertFalse(missingRoot.parentFile.exists())
        val approved = approved(files)
        val hostLookingGuest = File(temporary, "must-not-be-created")
        expectFailure("workspace_path_unavailable") {
            resolver.resolve(rootBinding(hostLookingGuest.path), approved)
        }
        expectFailure("workspace_path_unavailable") {
            resolver.resolve(rootBinding("/missing/child"), approved)
        }
        assertFalse(hostLookingGuest.exists())
        assertFalse(File(approved.rootfs, "missing").exists())
        assertFalse(File(files, "workspace").exists())
    }

    @Test fun rootfsAndGuestFileConflictsArePreserved() = fixture { files, _ ->
        val resolver = ConversationWorkspaceResolver(files)
        val rootFile = File(files, "root-file").apply { writeText("root-content") }
        expectFailure("workspace_path_unavailable") {
            resolver.resolve(rootBinding(), WorkspaceRootfsRoot("environment", "generation", rootFile))
        }
        val approved = approved(files)
        val guestFile = File(approved.rootfs, "file").apply { writeText("guest-content") }
        for (guest in listOf("/file", "/file/child")) {
            expectFailure("workspace_path_unavailable") { resolver.resolve(rootBinding(guest), approved) }
        }
        assertEquals("root-content", rootFile.readText())
        assertEquals("guest-content", guestFile.readText())
    }

    @Test fun approvedRootfsCanUseTrustedStorageAliasButNotSelectedLinks() = fixture { files, temporary ->
        val approved = approved(files)
        val alias = File(temporary, "files-alias")
        Files.createSymbolicLink(alias.toPath(), files.toPath())
        val resolver = ConversationWorkspaceResolver(alias)
        val relative = files.toPath().relativize(approved.rootfs.toPath()).toString()
        for (root in listOf(approved.rootfs, File(alias, relative))) {
            val descriptor = approved.copy(rootfs = root)
            val result = resolver.resolve(rootBinding(), descriptor)
            assertEquals(approved.rootfs, result.hostDirectory)
            assertEquals(descriptor, result.rootfs)
        }
        val linkedRoot = File(files, "linked-root")
        Files.createSymbolicLink(linkedRoot.toPath(), approved.rootfs.toPath())
        expectFailure("workspace_symlink_not_supported") {
            resolver.resolve(rootBinding(), approved.copy(rootfs = linkedRoot))
        }
        val linkedParent = File(files, "linked-parent")
        Files.createSymbolicLink(linkedParent.toPath(), approved.rootfs.parentFile.toPath())
        expectFailure("workspace_symlink_not_supported") {
            resolver.resolve(rootBinding(), approved.copy(rootfs = File(linkedParent, "rootfs")))
        }
    }

    @Test fun guestRejectsAbsoluteRelativeIntermediateAndDanglingLinks() = fixture { files, temporary ->
        val approved = approved(files)
        val local = File(approved.rootfs, "real/child").apply { mkdirs() }
        val outside = File(temporary, "outside/child").apply { mkdirs() }
        val absolute = File(approved.rootfs, "absolute")
        val relative = File(approved.rootfs, "relative")
        val intermediate = File(approved.rootfs, "intermediate")
        val dangling = File(approved.rootfs, "dangling")
        Files.createSymbolicLink(absolute.toPath(), outside.toPath())
        Files.createSymbolicLink(relative.toPath(), File("real").toPath())
        Files.createSymbolicLink(intermediate.toPath(), local.parentFile.toPath())
        Files.createSymbolicLink(dangling.toPath(), File("/unavailable-guest-target").toPath())
        val resolver = ConversationWorkspaceResolver(files)
        for (guest in listOf("/absolute", "/relative", "/relative/child", "/intermediate/child", "/dangling")) {
            expectFailure("workspace_symlink_not_supported") { resolver.resolve(rootBinding(guest), approved) }
        }
        assertTrue(Files.isSymbolicLink(absolute.toPath()))
        assertArrayEquals(emptyArray<String>(), outside.list())
        assertTrue(local.isDirectory)
    }

    @Test fun independentBindingsAndApprovalsDoNotPolluteEachOther() = fixture { files, _ ->
        val approved = approved(files)
        val second = WorkspaceRootfsRoot("second", "generation-2", File(files, "second-root").apply { mkdir() })
        val resolver = ConversationWorkspaceResolver(files)
        val privateBinding = ConversationWorkspaceBinding.Private()
        val privateResult = resolver.resolve(privateBinding, approved)
        File(privateResult.hostDirectory, "private-only").writeText("private")
        val guestResult = resolver.resolve(rootBinding(), approved)
        File(guestResult.hostDirectory, "guest-only").writeText("guest")
        val secondBinding = ConversationWorkspaceBinding.Rootfs("second", "generation-2", "/")
        assertEquals(second.rootfs, resolver.resolve(secondBinding, second).hostDirectory)
        expectFailure("workspace_rootfs_changed") { resolver.resolve(rootBinding(), second) }
        expectFailure("workspace_rootfs_changed") { resolver.resolve(rootBinding()) }
        assertEquals(privateResult, resolver.resolve(privateBinding, second))
        assertEquals(guestResult, resolver.resolve(rootBinding(), approved))
        assertNull(privateResult.rootfs)
        assertNull(privateResult.guestDirectory)
        assertFalse(File(privateResult.hostDirectory, "guest-only").exists())
        assertFalse(File(guestResult.hostDirectory, "private-only").exists())
        assertArrayEquals(emptyArray<String>(), second.rootfs.list())
    }
}
