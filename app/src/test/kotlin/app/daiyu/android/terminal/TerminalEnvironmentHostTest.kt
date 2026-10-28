package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerminalEnvironmentHostTest {
    private fun request() = TerminalOpenRequest(PtyRunOwner(ConversationId("terminal-test"),
        TaskAuthorization("session", "run"), TerminalEnvironmentHost.ANDROID_ENVIRONMENT,
        TerminalEnvironmentHost.ANDROID_GENERATION), "test-scope")

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("host state did not converge", condition())
    }

    private class Resource : TerminalOwnedResource {
        @Volatile var exited = false
        @Volatile var waitStatus: Int? = null
        @Volatile var output = "real output\n"
        val closes = AtomicInteger()
        val writes = CopyOnWriteArrayList<Triple<String, Long, String>>()
        val threads = CopyOnWriteArrayList<String>()
        var throwCloseOnce = false
        var throwWrite = false
        override fun read(caller: TaskAuthorization, offset: Int, limit: Int): JSONObject {
            threads += Thread.currentThread().name
            val bytes = output.toByteArray(Charsets.UTF_8)
            val end = minOf(bytes.size, offset + limit)
            return JSONObject().put("ok", true).put("stdout_base64", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
                .put("next_offset", end).put("has_more", end < bytes.size).put("log_truncated", false)
                .put("state", if (waitStatus == null) "running" else "exited")
                .put("wait_status", waitStatus ?: JSONObject.NULL).put("error", JSONObject.NULL)
        }
        override fun write(caller: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject {
            threads += Thread.currentThread().name
            writes += Triple(requestId, sequence, String(bytes, Charsets.UTF_8))
            if (throwWrite) error("delivery already attempted")
            return JSONObject().put("ok", true).put("execution", "executed")
        }
        override fun close() {
            threads += Thread.currentThread().name
            if (closes.incrementAndGet() == 1 && throwCloseOnce) error("close failed")
        }
        override fun awaitTermination(timeoutMillis: Long): Boolean {
            threads += Thread.currentThread().name
            return exited
        }
    }

    private class Backend(val resource: Resource = Resource()) : TerminalEnvironmentBackend {
        val opens = AtomicInteger()
        val inspections = AtomicInteger()
        val selections = AtomicInteger()
        val opening = CountDownLatch(1)
        var releaseOpen: CountDownLatch? = null
        var startupFailure = false
        var inspectionStarted: CountDownLatch? = null
        var releaseInspection: CountDownLatch? = null
        override fun open(request: TerminalOpenRequest): TerminalOwnedResource {
            opens.incrementAndGet(); opening.countDown()
            check(releaseOpen?.await(5, TimeUnit.SECONDS) != false)
            if (startupFailure) throw TerminalOwnedStartupFailure(resource, IllegalStateException("failed after spawn"))
            return resource
        }
        override fun linuxStatus(): TerminalLinuxState {
            inspections.incrementAndGet()
            inspectionStarted?.countDown()
            check(releaseInspection?.await(5, TimeUnit.SECONDS) != false)
            return TerminalLinuxState("NOT_SELECTED")
        }
        override fun selectLinux(reference: LinuxGenerationRef?) { selections.incrementAndGet() }
    }

    @Test fun slowLinuxRefreshDoesNotBlockOwnerInputAndClose() {
        val backend = Backend()
        val host = TerminalEnvironmentHost(backend)
        val request = request()
        val controls = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            assertTrue(host.open(request))
            await { host.state.value.acceptsInput }
            val started = CountDownLatch(1)
            backend.inspectionStarted = started
            backend.releaseInspection = release
            host.refreshLinux()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            controls.submit {
                assertNotNull(host.prepareInput(request.owner.authorization, "pending\n"))
                assertTrue(host.requestClose(request.owner.authorization))
                host.close()
            }.get(2, TimeUnit.SECONDS)
            assertEquals(1L, release.count)
            assertTrue(host.state.value.ownsResources)
            assertFalse(host.state.value.acceptsInput)
            release.countDown()
            backend.resource.exited = true
            await { !host.state.value.ownsResources }
        } finally {
            release.countDown()
            backend.resource.exited = true
            host.close()
            controls.shutdownNow()
        }
    }

    @Test fun slowPostSelectionInspectionDoesNotBlockOwnerTeardown() {
        val backend = Backend()
        val host = TerminalEnvironmentHost(backend)
        val controls = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(1)
            backend.inspectionStarted = started
            backend.releaseInspection = release
            val selecting = host.selectLinux(null)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            controls.submit { host.close() }.get(2, TimeUnit.SECONDS)
            assertEquals(1L, release.count)
            release.countDown()
            assertTrue(selecting.get(2, TimeUnit.SECONDS).ok)
            assertEquals(0, backend.opens.get())
        } finally {
            release.countDown()
            host.close()
            controls.shutdownNow()
        }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun linuxFixture(test: (File, LinuxInstallation) -> Unit) {
        val root = Files.createTempDirectory("daiyu-terminal-trust").toFile()
        val rootfs = File(root, "guest").apply { mkdirs() }
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("test-only shell; never executed")
        val proot = File(root, "proot").apply { writeText("test-only proot; never executed"); setExecutable(true) }
        val loader = File(root, "loader").apply { writeText("test-only loader; never executed") }
        val mount = File(root, "shared").apply { mkdirs() }
        val spec = LinuxInstallation("debian", "linux-env", "generation-1", rootfs, proot, hash(proot),
            loader, hash(loader), "/workspace", mapOf(mount.path to "/workspace/shared"))
        try { test(root, spec) } finally { root.deleteRecursively() }
    }

    private fun hostRootfsAccess(root: File,
        fileKey: (java.nio.file.Path) -> Any? = ::hostLinuxFileKey) =
        LinuxRootfsBackupAccess(root, directorySync = ::hostDirectorySync, fileKey = fileKey)

    private fun assertLinuxRefused(root: File, gate: TerminalTrustedLinuxSelection, reference: LinuxGenerationRef) {
        val state = gate.status()
        assertNotEquals("FILES_READY", state.state)
        assertNull(state.selected)
        assertNull(state.distribution)
        try {
            gate.withSelection(reference) {
                File(root, "user-terminal/home").mkdirs()
                fail("untrusted selection reached working-directory creation / PTY launch")
            }
            fail("untrusted selection accepted")
        } catch (_: Exception) { }
        assertFalse(File(root, "user-terminal/home").exists())
        assertFalse(File(root, "user-terminal/tmp").exists())
        assertFalse(File(root, "user-terminal/journal").exists())
    }

    @Test fun missingIdentityFailsClosedWithoutReplacingApprovedSelection() = linuxFixture { root, spec ->
        val directory = File(root, "selection")
        hostLinuxStore(directory).select(spec)
        val selection = File(directory, "selected-linux.json")
        val before = selection.readBytes()
        val unavailable = LinuxEnvironmentStore(directory, ::hostDirectorySync) { null }
        try { unavailable.select(spec); fail("missing key must reject") }
        catch (failure: IllegalStateException) { assertEquals("linux_path_identity_unavailable", failure.message) }
        assertTrue(before.contentEquals(selection.readBytes()))
        assertEquals("BROKEN", unavailable.status().getString("state"))
        try { unavailable.acquire(); fail("missing identity must not lease") }
        catch (failure: IllegalStateException) { assertEquals("linux_path_identity_unavailable", failure.message) }
    }

    @Test fun replacementAtSamePathInvalidatesOriginalLease() = linuxFixture { root, spec ->
        val store = hostLinuxStore(File(root, "selection")).apply { select(spec) }
        store.acquire().use { lease ->
            val original = requireNotNull(spec.loader).toPath()
            val moved = original.resolveSibling("retired-loader")
            Files.move(original, moved)
            Files.copy(moved, original)
            HostLinuxIdentity.replaced(original)
            try { lease.backend(root); fail("same-path replacement must reject original lease") }
            catch (failure: IllegalStateException) { assertEquals("linux_installation_changed", failure.message) }
        }
        store.acquire().use { it.backend(root) }
    }

    @Test fun emptyTrustedListIgnoresResidualSelectionAndCreatesNothing() = linuxFixture { root, spec ->
        val reference = LinuxGenerationRef(spec.environmentId, spec.generation)
        val directory = File(root, "user-terminal/linux-selection")
        val gate = TerminalTrustedLinuxSelection(directory, emptyList(), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey)
        assertLinuxRefused(root, gate, reference)
        assertEquals("UNAVAILABLE", gate.status().state)
        assertTrue(gate.status().choices.isEmpty())
        assertFalse(File(root, "user-terminal").exists())
        val store = hostLinuxStore(directory).apply { select(spec) }
        assertEquals("FILES_READY", store.status().getString("state")) // The legacy-only check would accept it.
        val selection = File(directory, "selected-linux.json")
        val before = selection.readText()
        assertLinuxRefused(root, gate, reference)
        assertEquals("UNAVAILABLE", gate.status().state)
        assertEquals(before, selection.readText())
    }

    @Test fun trustedListWithoutSelectionIsReadOnlyAndDoesNotCreateWorkingDirectories() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey)
        assertEquals("NOT_SELECTED", gate.status().state)
        assertLinuxRefused(root, gate, LinuxGenerationRef(spec.environmentId, spec.generation))
        assertFalse(File(root, "user-terminal").exists())
    }

    @Test fun trustedStatusNeverClaimsEvenATemporaryCommonPin() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        hostLinuxStore(directory).select(spec)
        val common = File(root, "linux-rootfs-access-v1")
        var sawPin = false
        val observedKey: (java.nio.file.Path) -> Any? = { path ->
            if (common.listFiles()!!.any { it.name.startsWith(".runner-pin-") }) sawPin = true
            hostLinuxFileKey(path)
        }
        val access = hostRootfsAccess(root, observedKey)
        val selection = File(directory, "selected-linux.json")
        val before = selection.readBytes()
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync,
            fileKey = observedKey, rootfsAccess = access)
        repeat(3) { assertEquals("FILES_READY", gate.status().state) }
        assertFalse("status must not create even a temporary pin", sawPin)
        assertArrayEquals(before, selection.readBytes())
        for (dir in listOf(directory, common)) assertTrue(dir.listFiles()!!.none { it.name.startsWith(".runner-pin-") })
        hostLinuxStore(directory).clearSelection()
    }

    @Test fun trustedStatusStillRejectsUnknownCommonPinWithoutChangingIt() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        hostLinuxStore(directory).select(spec)
        val common = hostRootfsAccess(root)
        val pin = File(root, "linux-rootfs-access-v1/.runner-pin-orphan").apply { writeText("unknown") }
        val before = pin.readBytes()
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync,
            fileKey = ::hostLinuxFileKey, rootfsAccess = common)
        repeat(3) { assertEquals("RUNNER_UNKNOWN", gate.status().state) }
        assertArrayEquals(before, pin.readBytes())
        assertEquals(listOf(pin.name), pin.parentFile.listFiles()!!.filter { it.name.startsWith(".runner-pin-") }.map { it.name })
        assertTrue(directory.listFiles()!!.none { it.name.startsWith(".runner-pin-") })
    }

    @Test fun trustedStatusChecksMetadataAndExportWithoutCreatingCommonPins() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        val store = hostLinuxStore(directory).apply { select(spec) }
        val common = hostRootfsAccess(root)
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync,
            fileKey = ::hostLinuxFileKey, rootfsAccess = common)
        repeat(3) { assertEquals("FILES_READY", gate.status().state) }
        assertTrue(File(root, "linux-rootfs-access-v1").listFiles()!!.none { it.name.startsWith(".runner-pin-") })
        val nativeKeys = listOf(File(root, "user-terminal"), directory).map {
            Files.readAttributes(it.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
        }
        if (nativeKeys.all { it != null }) {
            common.acquire(spec, listOf("root"), export = true).use { assertEquals("BROKEN", gate.status().state) }
        } else {
            // Runner-pin host identities do not replace the native legacy-directory identity check.
            assertEquals('\\', File.separatorChar)
            val failure = assertThrows(IllegalStateException::class.java) {
                common.acquire(spec, listOf("root"), export = true)
            }
            assertEquals("linux_rootfs_unknown", failure.message)
        }
        assertEquals("FILES_READY", gate.status().state)
        for (dir in listOf(File(root, "linux-rootfs-access-v1"), directory)) {
            assertTrue(dir.listFiles()!!.none { it.name.startsWith(".runner-pin-") })
        }
        store.select(spec.copy(distribution = "other"))
        assertEquals("BROKEN", gate.status().state)
    }

    @Test fun matchingTrustedConfigurationIsReadyAndGuardLeaseBlocksSelectionReplacement() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        val store = hostLinuxStore(directory).apply { select(spec) }
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey)
        val reference = LinuxGenerationRef(spec.environmentId, spec.generation)
        assertEquals("FILES_READY", gate.status().state)
        assertEquals(reference, gate.status().selected)
        assertEquals(spec.distribution, gate.status().distribution)
        gate.withSelection(reference) {
            try { store.clearSelection(); fail("launch validation must freeze selection") }
            catch (_: IllegalStateException) { }
        }
        store.clearSelection() // Inspection / guard released; no lease leak.
        assertEquals("NOT_SELECTED", gate.status().state)
        assertFalse(File(root, "user-terminal/home").exists())
    }

    @Test fun sameIdsCannotAuthorizeDifferentInstallationMetadata() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        val store = hostLinuxStore(directory)
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey)
        val reference = LinuxGenerationRef(spec.environmentId, spec.generation)
        val otherRoot = File(root, "other-guest").apply { mkdirs() }
        File(otherRoot, "bin").mkdirs()
        File(otherRoot, "bin/sh").writeText("other shell; never executed")
        val otherProot = File(root, "other-proot").apply { writeBytes(requireNotNull(spec.proot).readBytes()); setExecutable(true) }
        val otherLoader = File(root, "other-loader").apply { writeBytes(requireNotNull(spec.loader).readBytes()) }
        val otherMount = File(root, "other-shared").apply { mkdirs() }
        val variants = listOf(
            spec.copy(distribution = "other-distro"),
            spec.copy(environmentId = "other-env"),
            spec.copy(generation = "other-generation"),
            spec.copy(rootfs = otherRoot),
            spec.copy(proot = otherProot), // Even identical bytes do not authorize a different executable path.
            spec.copy(loader = otherLoader),
            spec.copy(guestWorkingDirectory = "/other-workspace"),
            spec.copy(sharedMounts = emptyMap()),
            spec.copy(sharedMounts = mapOf(otherMount.path to "/workspace/shared")),
            spec.copy(sharedMounts = spec.sharedMounts.mapValues { "/other-guest-mount" }),
        )
        for (variant in variants) {
            store.select(variant)
            assertEquals("FILES_READY", store.status().getString("state"))
            val before = File(directory, "selected-linux.json").readText()
            assertLinuxRefused(root, gate, reference)
            assertEquals(before, File(directory, "selected-linux.json").readText())
            store.clearSelection() // A rejection must not leak its validation lease.
        }
        store.select(spec)
        val ambiguous = TerminalTrustedLinuxSelection(directory, listOf(spec, spec.copy(rootfs = otherRoot)), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey)
        assertLinuxRefused(root, ambiguous, reference)
    }

    @Test fun forgedSelectionHashesCannotOverrideParentTrustedDigests() = linuxFixture { root, spec ->
        val directory = File(root, "user-terminal/linux-selection")
        val store = hostLinuxStore(directory).apply { select(spec) }
        val gate = TerminalTrustedLinuxSelection(directory, listOf(spec), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey)
        val reference = LinuxGenerationRef(spec.environmentId, spec.generation)
        val selection = File(directory, "selected-linux.json")
        val proot = requireNotNull(spec.proot)
        val loader = requireNotNull(spec.loader)
        for ((file, field) in listOf(proot to "proot_sha256", loader to "loader_sha256")) {
            val originalFile = file.readBytes()
            val originalSelection = selection.readText()
            file.writeText("forged replacement with self-reported hash")
            selection.writeText(JSONObject(originalSelection).put(field, hash(file)).toString())
            assertEquals("FILES_READY", store.status().getString("state"))
            assertLinuxRefused(root, gate, reference)
            file.writeBytes(originalFile)
            selection.writeText(originalSelection)
        }
        assertEquals("FILES_READY", gate.status().state)
        assertLinuxRefused(root, TerminalTrustedLinuxSelection(directory,
            listOf(spec.copy(prootSha256 = "0".repeat(64))), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey), reference)
        assertLinuxRefused(root, TerminalTrustedLinuxSelection(directory,
            listOf(spec.copy(loaderSha256 = "0".repeat(64))), directorySync = ::hostDirectorySync, fileKey = ::hostLinuxFileKey), reference)
    }

    @Test fun constructionAndRepeatedObservationNeverOpenOrSelect() {
        val backend = Backend()
        val host = TerminalEnvironmentHost(backend)
        try {
            assertEquals("IDLE", host.state.value.phase)
            assertEquals(0, backend.inspections.get())
            repeat(3) { host.refreshLinux() }
            await { backend.inspections.get() == 3 }
            assertEquals("NOT_SELECTED", host.state.value.linux.state)
            assertEquals(0, backend.opens.get())
            assertEquals(0, backend.selections.get())
        } finally { host.close() }
    }

    @Test fun exactOwnerAndStableEventIdentityArePreservedOnWorker() {
        val backend = Backend()
        val host = TerminalEnvironmentHost(backend)
        val request = request()
        try {
            assertTrue(host.open(request))
            await { host.state.value.acceptsInput }
            val copied = TaskAuthorization("session", "run")
            assertNull(host.prepareInput(copied, "denied"))
            assertFalse(host.requestClose(copied))
            val event = checkNotNull(host.prepareInput(request.owner.authorization, "hello\n"))
            assertEquals(0L, event.sequence)
            assertFalse(host.submit(copied, event).get(2, TimeUnit.SECONDS).ok)
            assertTrue(host.submit(request.owner.authorization, event).get(2, TimeUnit.SECONDS).ok)
            assertTrue(host.submit(request.owner.authorization, event).get(2, TimeUnit.SECONDS).ok)
            assertEquals(2, backend.resource.writes.size)
            assertEquals(backend.resource.writes[0], backend.resource.writes[1])
            val next = checkNotNull(host.prepareInput(request.owner.authorization, "next\n"))
            assertEquals(1L, next.sequence)
            assertNotEquals(event.requestId, next.requestId)
            assertTrue(backend.resource.threads.all { it == "daiyu-terminal-host" })
            assertEquals("real output\n", host.state.value.output)
            assertEquals("input_written_command_completion_unknown", host.state.value.lastOperation)
            assertFalse(host.selectLinux(null).get(2, TimeUnit.SECONDS).ok)
            assertEquals(0, backend.selections.get())
        } finally { backend.resource.exited = true; host.close() }
    }

    @Test fun lateCreationAfterCloseKeepsResponsibilityUntilActualExit() {
        val backend = Backend().apply { releaseOpen = CountDownLatch(1) }
        val host = TerminalEnvironmentHost(backend)
        val request = request()
        try {
            assertTrue(host.open(request))
            assertTrue(backend.opening.await(2, TimeUnit.SECONDS))
            assertTrue(host.requestClose(request.owner.authorization))
            host.close() // This must NOT cancel the in-flight creation or discard its result.
            assertTrue(host.state.value.ownsResources)
            backend.releaseOpen!!.countDown()
            await { backend.resource.closes.get() > 0 }
            assertTrue(host.state.value.ownsResources)
            assertFalse(host.state.value.terminationConfirmed)
            assertFalse(host.open(request))
            backend.resource.exited = true
            await { !host.state.value.ownsResources }
            assertTrue(host.state.value.terminationConfirmed)
            assertEquals("UNKNOWN", host.state.value.phase)
            assertNull(host.state.value.exitCode)
        } finally { backend.releaseOpen!!.countDown(); backend.resource.exited = true; host.close() }
    }

    @Test fun failedStartupAndThrowingCloseRetainCleanupAndRetryJoin() {
        val backend = Backend().apply { startupFailure = true; resource.throwCloseOnce = true }
        val host = TerminalEnvironmentHost(backend)
        try {
            assertTrue(host.open(request()))
            await { backend.resource.closes.get() >= 2 }
            assertTrue(host.state.value.ownsResources)
            assertFalse(host.state.value.canOpen)
            assertFalse(host.state.value.terminationConfirmed)
            backend.resource.exited = true
            await { !host.state.value.ownsResources }
            assertTrue(host.state.value.terminationConfirmed)
            assertEquals("FAILED", host.state.value.phase)
            assertNull(host.state.value.exitCode)
        } finally { backend.resource.exited = true; host.close() }
    }

    @Test fun finalOutputIsDecodedAcrossBytePagesAndWaitStatusIsNotCompletionUntilJoin() {
        val backend = Backend().apply {
            resource.output = "a".repeat(65535) + "🙂\n"
            resource.waitStatus = 7 shl 8
        }
        val host = TerminalEnvironmentHost(backend)
        try {
            assertTrue(host.open(request()))
            await { host.state.value.waitStatus != null }
            assertEquals(backend.resource.output, host.state.value.output)
            assertEquals(7, host.state.value.exitCode)
            assertFalse(host.state.value.acceptsInput)
            assertTrue(host.state.value.ownsResources)
            assertFalse(host.state.value.terminationConfirmed)
            backend.resource.exited = true
            await { host.state.value.terminationConfirmed }
            assertEquals("EXITED", host.state.value.phase)
            assertTrue(host.state.value.canOpen)
        } finally { backend.resource.exited = true; host.close() }
    }

    @Test fun thrownWriteIsUnknownNotNotExecutedAndDoesNotDropCleanup() {
        val backend = Backend().apply { resource.throwWrite = true }
        val host = TerminalEnvironmentHost(backend)
        val request = request()
        try {
            host.open(request)
            await { host.state.value.acceptsInput }
            val event = checkNotNull(host.prepareInput(request.owner.authorization, "possibly-delivered\n"))
            val answer = host.submit(request.owner.authorization, event).get(2, TimeUnit.SECONDS)
            assertFalse(answer.ok)
            assertEquals("unknown", answer.execution)
            await { backend.resource.closes.get() > 0 }
            assertFalse(host.submit(request.owner.authorization, event).get(2, TimeUnit.SECONDS).ok)
            assertEquals("UNKNOWN input must never be redelivered", 1, backend.resource.writes.size)
            assertTrue(host.state.value.ownsResources)
            assertFalse(host.state.value.acceptsInput)
            backend.resource.exited = true
            await { host.state.value.terminationConfirmed }
        } finally { backend.resource.exited = true; host.close() }
    }

    @Test fun oldEventsCannotCrossRunEvenWithSameAuthorization() {
        val backend = Backend()
        val host = TerminalEnvironmentHost(backend)
        val request = request()
        try {
            host.open(request)
            await { host.state.value.acceptsInput }
            val old = checkNotNull(host.prepareInput(request.owner.authorization, "old\n"))
            host.requestClose(request.owner.authorization)
            backend.resource.exited = true
            await { !host.state.value.ownsResources }
            backend.resource.exited = false
            host.open(request.copy(sessionScope = "new-scope"))
            await { host.state.value.acceptsInput }
            val result = host.submit(request.owner.authorization, old).get(2, TimeUnit.SECONDS)
            assertEquals("pty_run_owner_mismatch", result.error)
            assertTrue(backend.resource.writes.isEmpty())
        } finally { backend.resource.exited = true; host.close() }
    }
}
