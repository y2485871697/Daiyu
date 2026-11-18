package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.LinuxTmpPngReadException
import app.daiyu.android.terminal.TerminalConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RunCommandToolsTest {
    private class Fixture(private val automaticConsent: Boolean = false, linuxState: app.daiyu.android.terminal.TerminalLinuxState? = null,
        androidConfiguration: TerminalConfiguration = TerminalConfiguration(workingDirectory = File("/fixed")),
        linuxLaunch: TerminalPtyLaunch.Linux? = null, fileImages: Boolean = false) {
        val owner = runnerOwner(Message(MessageId("m"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val task = TaskAuthorization("s", "r")
        var approved = true
        var prompts = 0
        var created = 0
        var executions = 0
        var imageReads = 0
        var imageReceipts = 0
        var imagePath: String? = null
        var afterImageRead: () -> Unit = {}
        var imageFailure: LinuxTmpPngReadException? = null
        var closes = 0
        var drains = 0
        var onCreate: () -> Unit = {}
        var terminated = true
        var failExecute = false
        var failClose = false
        var cancelled = false
        var onOpen: () -> Unit = {}
        var onExecute: () -> Unit = {}
        var openIdentity = JSONObject()
        var openError: String? = null
        val configurations = mutableListOf<TerminalConfiguration>()
        val disclosures = mutableListOf<String>()
        var execution = "executed"
        var exitCode: Int? = 0
        var pendingReads = 0
        var journalCommitUnknown = false
        var reads = 0
        var ownerCurrent = true
        var duringConsent: () -> Unit = {}
        val effects = mutableListOf<WriteEffect>()
        val executedCommands = mutableListOf<String>()
        val openRequests = mutableSetOf<String>()
        val executionRequests = mutableSetOf<String>()
        private val gate = CommandApprovalGate({ fail("automatic consent must not post UI") }, { ownerCurrent && it === owner })
        val leaf = RunCommandTools(androidConfiguration, owner, task, linuxLaunch,
            approve = { context, effect, disclosure ->
                assertSame(task, context.authorization)
                effects.add(effect)
                disclosures.add(disclosure)
                duringConsent()
                if (automaticConsent) gate.await(CommandApprovalRequest(owner, context.invocation.id.value,
                    effect, disclosure), context.cancelled)
                else { prompts++; approved }
            }, linuxState = linuxState, imageReceipt = if (!fileImages) null else { context, bytes ->
                assertSame(owner, context.owner)
                assertSame(task, context.authorization)
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                imageReceipts++
                "image-receipt"
            }, create = { _, configuration ->
                configurations.add(configuration)
                created++; onCreate()
                object : CommandTransport {
                    override fun execute(name: String, arguments: JSONObject): JSONObject {
                        if (name == "terminal_open") {
                            openRequests.add(arguments.getString("request_id"))
                            onOpen()
                            openError?.let { return JSONObject().put("ok", false).put("error", it) }
                            return JSONObject(openIdentity.toString()).put("ok", true).put("session_id", "session")
                        }
                        onExecute()
                        executions++
                        executionRequests.add(arguments.getString("request_id"))
                        if (failExecute) throw java.io.IOException("sent but receipt lost")
                        executedCommands.add(arguments.getString("command"))
                        return JSONObject().put("ok", true).put("execution", if (pendingReads > 0) "unknown" else execution)
                            .put("wait_pending", pendingReads > 0).put("exit_code", exitCode ?: JSONObject.NULL)
                            .put("job_id", "00000000-0000-0000-0000-000000000001").put("stdout", "exact\n").apply {
                                if (journalCommitUnknown) put("ok", false).put("execution", "unknown")
                                    .put("error", "journal_commit_unknown_not_replayed")
                            }
                    }
                    override fun read(id: String, offset: Long, limit: Int): JSONObject {
                        reads++
                        if (pendingReads > 0) pendingReads--
                        return JSONObject().put("ok", true).put("execution", if (pendingReads > 0) "unknown" else "executed")
                            .put("wait_pending", pendingReads > 0).put("exit_code", exitCode ?: JSONObject.NULL)
                            .put("job_id", id).put("stdout", "exact\n")
                    }
                    override fun readLinuxTmpPng(path: String, cancelled: () -> Boolean): ByteArray {
                        assertFalse(cancelled())
                        imageReads++; imagePath = path; afterImageRead()
                        imageFailure?.let { throw it }
                        return byteArrayOf(1, 2, 3)
                    }
                    override fun close() { closes++; if (failClose) throw java.io.IOException("close failed") }
                    override fun awaitTermination(timeoutMillis: Long): Boolean { drains++; return terminated }
                }
            })
        private val registry = ToolRegistry().also { registry -> leaf.tools().forEach { registry.register(it) } }
        private val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("s", setOf(RunCommandTools.CAPABILITY)),
            ToolLedger(InMemoryToolLedgerStore()))
        fun image(id: String = "image", path: String = "/tmp/daiyu-video-test/frame.png",
            dispatchOwner: RunExecutionOwner = owner) = dispatcher.dispatchOwned(task,
                listOf(ToolInvocation(ToolCallId(id), "read_image", JSONObject().put("path", path).toString())),
                dispatchOwner) { cancelled }.single().outcome
        fun hasImageTool() = leaf.tools().any { it.definition.name == "read_image" }
        fun dispatch(id: String = "call", environment: String = "android", timeout: Long = 30000,
            command: String = "echo exact", dispatchOwner: RunExecutionOwner = owner) = dispatcher.dispatchOwned(task,
            listOf(ToolInvocation(ToolCallId(id), "terminal_exec", JSONObject().put("command", command)
                .put("environment", environment).put("timeout_ms", timeout).toString())), dispatchOwner) { cancelled }.single().outcome
    }
    @Test fun syncWaitReadsTheOriginalJobUntilCompletionWithoutExecutingAgain() {
        val fixture = Fixture().apply { pendingReads = 2 }
        try {
            assertFalse((fixture.dispatch() as DispatchOutcome.Confirmed).isError)
            assertEquals(1, fixture.executions)
            assertEquals(2, fixture.reads)
            assertFalse((fixture.dispatch() as DispatchOutcome.Confirmed).isError)
            assertEquals(1, fixture.executions)
        } finally { fixture.leaf.close() }
    }

    @Test fun journalFailureWithPendingFlagRemainsUnknownWithoutReadingOrExecutingAgain() {
        val fixture = Fixture().apply { pendingReads = 2; journalCommitUnknown = true }
        try {
            assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
            assertEquals(0, fixture.reads)
            fixture.dispatch(id = "later-command", command = "echo never")
            assertEquals(1, fixture.executions)
            assertEquals(0, fixture.reads)
        } finally { fixture.leaf.close() }
    }

    @Test fun nonzeroExitIsConfirmedFailureAndMissingExitRemainsUnknown() {
        for (code in listOf(1, 127)) {
            val fixture = Fixture().apply { exitCode = code }
            try {
                assertTrue((fixture.dispatch() as DispatchOutcome.Confirmed).isError)
                assertEquals(1, fixture.executions)
            } finally { fixture.leaf.close() }
        }
        val missing = Fixture().apply { exitCode = null }
        try { assertTrue(missing.dispatch() is DispatchOutcome.Unknown) }
        finally { missing.leaf.close() }
    }

    @Test fun fileImagesRequireAHostReceiptAndTheExactCurrentOwner() {
        val absent = Fixture()
        assertFalse(absent.hasImageTool())
        absent.leaf.close()
        val fixture = Fixture(fileImages = true)
        try {
            assertTrue(fixture.hasImageTool())
            val foreign = runnerOwner(Message(MessageId("other"), ConversationId("c"), RunId("r"),
                Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
            assertTrue(fixture.image(dispatchOwner = foreign) is DispatchOutcome.NotExecuted)
            assertFalse(fixture.image("invalid", "/tmp/../frame.png") is DispatchOutcome.Confirmed)
            assertFalse(fixture.image("without-linux") is DispatchOutcome.Confirmed)
            assertEquals(0, fixture.created)
            assertEquals(0, fixture.imageReads)
            assertEquals(0, fixture.imageReceipts)
            assertTrue(fixture.effects.isEmpty())
        } finally { fixture.leaf.close() }
    }

    @Test fun fileImagesReuseTheFrozenLinuxTransportAndCancellationPreventsReceiptPublication() {
        org.junit.Assume.assumeFalse("Requires production POSIX directory fsync", System.getProperty("os.name").orEmpty().startsWith("Windows", true))
        val files = java.nio.file.Files.createTempDirectory("command-image-").toFile()
        try {
            val root = File(files, "rootfs").apply { File(this, "bin").mkdirs(); File(this, "bin/sh").writeText("never executed") }
            val proot = File(files, "proot").apply { writeText("never executed"); setExecutable(true) }
            val loader = File(files, "loader").apply { writeText("never executed") }
            fun hash(file: File) = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val installation = app.daiyu.android.terminal.LinuxInstallation("debian", "env", "generation", root, proot, hash(proot), loader, hash(loader))
            val access = app.daiyu.android.terminal.LinuxRootfsBackupAccess.forFiles(files)
            val store = app.daiyu.android.terminal.LinuxEnvironmentStore(File(files, "selection"),
                directorySync = app.daiyu.android.terminal.TerminalDurability::syncDirectory, rootfsAccess = access).apply { select(installation) }
            val launch = TerminalPtyLaunch.Linux.freeze(store, app.daiyu.android.terminal.LinuxGenerationRef("env", "generation"),
                File(files, "tmp").apply { mkdirs() }, files)
            val fixture = Fixture(linuxLaunch = launch, fileImages = true)
            try {
                assertTrue(fixture.dispatch("extract", environment = "linux") is DispatchOutcome.Confirmed)
                assertTrue(fixture.image() is DispatchOutcome.Confirmed)
                assertEquals("/tmp/daiyu-video-test/frame.png", fixture.imagePath)
                assertEquals(1, fixture.imageReads)
                assertEquals(1, fixture.imageReceipts)
                assertEquals(1, fixture.created)
                assertEquals(1, fixture.executions)
                assertEquals(1, fixture.prompts)
                assertTrue(fixture.configurations.single().backend == "linux")
                fixture.imageFailure = LinuxTmpPngReadException("linux_tmp_png_python3_required",
                    "请用当前已授权 Linux 的 terminal_exec 和包管理器安装 python3；不要切换环境")
                val missing = fixture.image("missing-python") as DispatchOutcome.Confirmed
                assertTrue(missing.isError)
                assertTrue(missing.payload.contains("python3"))
                assertTrue(missing.payload.contains("不要切换环境"))
                fixture.leaf.observeOutcomes(listOf(ToolDispatchResult(ToolCallId("missing-python"), missing)))
                val replay = fixture.image("missing-python") as DispatchOutcome.Confirmed
                assertTrue(replay.isError && replay.replayed)
                assertEquals(2, fixture.imageReads)
                assertTrue(fixture.dispatch("install-python", environment = "linux", command = "apt-get install -y python3") is DispatchOutcome.Confirmed)
                fixture.imageFailure = null
                fixture.afterImageRead = { fixture.cancelled = true }
                assertTrue(fixture.image("cancelled", "/tmp/daiyu-video-test/second.png") is DispatchOutcome.Unknown)
                assertEquals(3, fixture.imageReads)
                assertEquals(1, fixture.imageReceipts)
            } finally {
                fixture.leaf.close()
                val drained = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
                fixture.leaf.observeClose { drained.complete(it) }
                assertEquals(RunCatalogCloseResult.Drained, drained.get(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        } finally { files.deleteRecursively() }
    }

    @Test fun preFactoryLinuxClaimFailureCanDrainItsUnstartedMetadataLease() {
        org.junit.Assume.assumeFalse("Requires production POSIX directory fsync", System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        val files = java.nio.file.Files.createTempDirectory("command-unstarted-").toFile()
        try {
            val root = File(files, "rootfs").apply { File(this, "bin").mkdirs(); File(this, "bin/sh").writeText("never executed") }
            val proot = File(files, "proot").apply { writeText("never executed"); setExecutable(true) }
            val loader = File(files, "loader").apply { writeText("never executed") }
            fun hash(file: File) = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val installation = app.daiyu.android.terminal.LinuxInstallation("debian", "env", "generation", root, proot, hash(proot), loader, hash(loader))
            val gate = app.daiyu.android.terminal.LinuxRootfsBackupAccess.forFiles(files)
            val store = app.daiyu.android.terminal.LinuxEnvironmentStore(File(files, "user-terminal/linux-selection"),
                directorySync = app.daiyu.android.terminal.TerminalDurability::syncDirectory, rootfsAccess = gate).apply { select(installation) }
            val temporary = File(files, "tmp").apply { mkdirs() }
            val launch = TerminalPtyLaunch.Linux.freeze(store, app.daiyu.android.terminal.LinuxGenerationRef("env", "generation"), temporary, files)
            val fixture = Fixture(linuxLaunch = launch)
            val orphan = File(files, "linux-rootfs-access-v1/.runner-pin-orphan")
            fixture.duringConsent = { orphan.writeText("unknown") }
            assertTrue(fixture.dispatch(environment = "linux") is DispatchOutcome.Unknown)
            assertEquals(0, fixture.created)
            fixture.leaf.close()
            val closed = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
            fixture.leaf.observeClose { closed.complete(it) }
            assertEquals(RunCatalogCloseResult.Drained, closed.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(orphan.exists())
            orphan.delete() // Remove only this test's deliberately injected fence.
            store.acquireRootfsBackup(installation).close()
        } finally { files.deleteRecursively() }
    }

    @Test fun automaticDefaultExecutesDifferentCommandsAndTimeoutsWithSeparateExactGrants() {
        val fixture = Fixture(automaticConsent = true)
        val commands = listOf("echo first", "echo second", "echo second ")
        commands.forEachIndexed { index, command ->
            assertTrue(fixture.dispatch("call-$index", command = command) is DispatchOutcome.Confirmed)
        }
        assertTrue(fixture.dispatch("timeout", timeout = 60000, command = commands.last()) is DispatchOutcome.Confirmed)
        assertEquals(commands + commands.last(), fixture.executedCommands)
        assertEquals(4, fixture.effects.toSet().size)
        assertEquals(0, fixture.prompts)
        assertTrue(fixture.task.allowedWrites.isEmpty())
        assertTrue((fixture.dispatch("replay", command = commands.first()) as DispatchOutcome.Confirmed).replayed)
        assertEquals(4, fixture.executions)
        fixture.leaf.close()
    }
    @Test fun continuous130CommandsReuseOneFrozenSessionAndKeepExactCommandIdentities() {
        val fixture = Fixture(automaticConsent = true)
        try {
            repeat(130) { index ->
                assertTrue(fixture.dispatch("long-$index", command = "echo exact-$index") is DispatchOutcome.Confirmed)
            }
            assertEquals(1, fixture.created)
            assertEquals(setOf("open-android"), fixture.openRequests)
            assertEquals(130, fixture.executionRequests.size)
            assertEquals(130, fixture.effects.toSet().size)
            assertEquals(130, fixture.executions)
            assertTrue((fixture.dispatch("repeat-first", command = "echo exact-0") as DispatchOutcome.Confirmed).replayed)
            assertEquals(130, fixture.executions)
            assertTrue(fixture.task.allowedWrites.isEmpty())
        } finally { fixture.leaf.close() }
    }
    @Test fun automaticDefaultStillRejectsStopExpiredOwnerAndForeignOwner() {
        val stopped = Fixture(automaticConsent = true)
        stopped.duringConsent = { stopped.cancelled = true }
        assertTrue(stopped.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, stopped.created)
        val expired = Fixture(automaticConsent = true)
        expired.ownerCurrent = false
        assertTrue(expired.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, expired.created)
        val foreign = Fixture(automaticConsent = true)
        val other = runnerOwner(Message(MessageId("other"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        assertTrue(foreign.dispatch(dispatchOwner = other) is DispatchOutcome.NotExecuted)
        assertTrue(foreign.effects.isEmpty())
        assertEquals(0, foreign.created)
    }
    @Test fun automaticUnknownNeverReplaysOrPermitsADifferentCommand() {
        val fixture = Fixture(automaticConsent = true)
        fixture.execution = "unknown"
        assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("new-id") is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("different", command = "echo other") is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.executions)
        assertEquals(1, fixture.effects.size)
        assertEquals(0, fixture.prompts)
        fixture.leaf.close()
    }
    @Test fun consentExecutesExactBytesOnceAndConfirmedReplayDoesNotPrompt() {
        val fixture = Fixture()
        assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
        assertTrue((fixture.dispatch("new-id") as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
        assertTrue(fixture.task.allowedWrites.isEmpty())
    }
    @Test fun failedFreezeReportsActualTargetAndNeverRetriesAnotherEnvironment() {
        val selected = app.daiyu.android.terminal.LinuxGenerationRef("selected", "b")
        val workspace = app.daiyu.android.terminal.LinuxGenerationRef("workspace", "a")
        var freezes = 0
        val binding = CommandLinuxBinding.freeze(app.daiyu.android.terminal.TerminalLinuxState("FILES_READY", selected), workspace) {
            freezes++; error("linux_runner_generation_unknown")
        }
        assertEquals(1, freezes); assertNull(binding.launch)
        assertEquals(workspace, binding.state?.selected)
        assertEquals("RUNNER_UNKNOWN", binding.state?.state)
        val fixture = Fixture(linuxState = binding.state)
        assertEquals(DispatchOutcome.NotExecuted("linux_runner_generation_unknown"), fixture.dispatch(environment = "linux"))
        assertTrue(fixture.dispatch("android") is DispatchOutcome.Confirmed)
        fixture.leaf.close()
    }

    @Test fun missingWorkspaceBindingDoesNotInheritAnotherSelectionsState() {
        val selected = app.daiyu.android.terminal.LinuxGenerationRef("selected", "b")
        val workspace = app.daiyu.android.terminal.LinuxGenerationRef("workspace", "a")
        val binding = CommandLinuxBinding.freeze(app.daiyu.android.terminal.TerminalLinuxState("RUNNER_UNKNOWN", selected), workspace) { null }
        assertEquals(workspace, binding.state?.selected)
        assertEquals("UNAVAILABLE", binding.state?.state)
        assertEquals("linux_workspace_generation_unavailable", binding.state?.error)
    }

    @Test fun rootModeRejectionAndCancellationKeepTheirMeaning() {
        val rejected = CommandLinuxBinding.freeze(null, null) { error("linux_root_mode_required") }
        assertEquals("linux_root_mode_required", rejected.state?.error)
        assertNull(rejected.launch)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            CommandLinuxBinding.freeze(null, null) { throw java.util.concurrent.CancellationException("cancelled") }
        }
    }

    @Test fun unknownLinuxIsReportedAsFencedNotUnconfiguredAndNeverPromptsOrLaunches() {
        val fixture = Fixture(linuxState = app.daiyu.android.terminal.TerminalLinuxState("RUNNER_UNKNOWN",
            error = "linux_runner_generation_unknown"))
        val description = fixture.leaf.tools().first().definition.description
        assertTrue(description.contains("RUNNER_UNKNOWN"))
        assertTrue(description.contains("linux_runner_generation_unknown"))
        assertFalse(description.contains("未配置"))
        assertEquals(DispatchOutcome.NotExecuted("linux_runner_generation_unknown"), fixture.dispatch(environment = "linux"))
        assertEquals(0, fixture.prompts); assertEquals(0, fixture.created)
        assertTrue(fixture.dispatch("android", environment = "android") is DispatchOutcome.Confirmed)
        assertEquals(1, fixture.created)
        fixture.leaf.close()
    }

    @Test fun unavailableLinuxStatesHaveDistinctDescriptionsAndReceipts() {
        val states = mapOf("NOT_SELECTED" to "linux_environment_not_selected",
            "BROKEN" to "linux_environment_unavailable", "FILES_READY" to "linux_environment_not_bound_to_run")
        states.forEach { (state, reason) ->
            val fixture = Fixture(linuxState = app.daiyu.android.terminal.TerminalLinuxState(state))
            assertTrue(fixture.leaf.tools().first().definition.description.contains(state))
            assertEquals(DispatchOutcome.NotExecuted(reason), fixture.dispatch(environment = "linux"))
            assertEquals(0, fixture.prompts); assertEquals(0, fixture.created)
            fixture.leaf.close()
        }
    }

    @Test fun missingLinuxSnapshotDoesNotClaimThatNoInstallationExists() {
        val fixture = Fixture()
        val description = fixture.leaf.tools().first().definition.description
        assertTrue(description.contains("不能据此推断安装状态"))
        assertFalse(description.contains("未配置"))
    }

    @Test fun denialAndUnavailableLinuxNeverConstructBackend() {
        val denied = Fixture(); denied.approved = false
        assertTrue(denied.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, denied.created); assertEquals(0, denied.executions)
        val linux = Fixture()
        assertTrue(linux.dispatch(environment = "linux") is DispatchOutcome.NotExecuted)
        assertEquals(0, linux.prompts); assertEquals(0, linux.created)
    }
    @Test fun cancellationDuringOpenDoesNotSendCommand() {
        val fixture = Fixture(); fixture.onOpen = { fixture.cancelled = true }
        fixture.openIdentity = JSONObject().put("backend", "android").put("uid", 0).put("root", true)
        assertTrue(fixture.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.created); assertEquals(0, fixture.executions)
        assertTrue(fixture.owner.terminalTargets.isEmpty())
    }
    @Test fun unknownThenEquivalentNewCallRemainsUnknownWithoutNewConsentOrExecution() {
        val fixture = Fixture(); fixture.execution = "unknown"
        assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("new-id") is DispatchOutcome.Unknown)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
    }
    @Test fun changedTimeoutNeedsItsOwnExactConsentAndCommand() {
        val fixture = Fixture()
        assertTrue(fixture.dispatch(timeout = 30000) is DispatchOutcome.Confirmed)
        val next = fixture.dispatch("longer", timeout = 60000) as DispatchOutcome.Confirmed
        assertFalse(next.replayed)
        assertEquals(2, fixture.prompts); assertEquals(2, fixture.executions)
    }
    @Test fun closeDuringOpenNeverSendsCommandAndDrainObservesBackend() {
        val fixture = Fixture(); fixture.onOpen = { fixture.leaf.close() }
        assertTrue(fixture.dispatch() is DispatchOutcome.NotExecuted)
        assertEquals(0, fixture.executions); assertEquals(1, fixture.closes)
        val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
        fixture.leaf.observeClose { result.complete(it) }
        assertEquals(RunCatalogCloseResult.Drained, result.get(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, fixture.drains)
    }
    @Test fun closingWhileFactoryIsBlockedNeverReportsDrainedAndLateHandleIsClosed() {
        val fixture = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture.onCreate = { entered.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        val executing = java.util.concurrent.FutureTask { fixture.dispatch() }
        Thread(executing).start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); fixture.leaf.close()
            val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
            fixture.leaf.observeClose { result.complete(it) }
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is RunCatalogCloseResult.Unknown)
        } finally { release.countDown() }
        assertTrue(executing.get(2, java.util.concurrent.TimeUnit.SECONDS) is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.closes); assertEquals(0, fixture.executions)
    }
    @Test fun unconfirmedPhysicalTerminationNeverReportsDrained() {
        val fixture = Fixture(); fixture.terminated = false
        assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed); fixture.leaf.close()
        val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
        fixture.leaf.observeClose { result.complete(it) }
        assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is RunCatalogCloseResult.Unknown)
    }

    @Test fun thrownExecutionFencesDifferentEffectsBeforeOutcomeFeedback() {
        val fixture = Fixture(); fixture.failExecute = true
        assertTrue(fixture.dispatch() is DispatchOutcome.Unknown)
        assertTrue(fixture.dispatch("different", timeout = 60000) is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.prompts); assertEquals(1, fixture.executions)
    }
    @Test fun closeFailureIsRetainedEvenWhenTerminationProbeWouldSucceed() {
        val fixture = Fixture(); assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
        fixture.failClose = true
        try { fixture.leaf.close(); fail("close failure disappeared") } catch (_: java.io.IOException) {}
        val result = java.util.concurrent.CompletableFuture<RunCatalogCloseResult>()
        fixture.leaf.observeClose { result.complete(it) }
        assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is RunCatalogCloseResult.Unknown)
    }

    @Test fun selectedRootModeFreezesHostIdentityWithoutExecutingAProbe() {
        val cwd = File("/fixed")
        var discoveries = 0
        val root = RunCommandTools.androidConfiguration(app.daiyu.android.runningmode.RunningMode.ROOT,
            cwd, mapOf("PATH" to "/system/bin")) { discoveries++; "/system/bin/su" }
        assertTrue(root.root)
        assertEquals("/system/bin/su", root.rootExecutable)
        assertEquals(1, discoveries)
        for (mode in listOf(app.daiyu.android.runningmode.RunningMode.NORMAL, app.daiyu.android.runningmode.RunningMode.SHIZUKU)) {
            val normal = RunCommandTools.androidConfiguration(mode, cwd, emptyMap()) { fail("must not discover Root"); null }
            assertFalse(normal.root)
            assertEquals(if (mode == app.daiyu.android.runningmode.RunningMode.SHIZUKU) "shizuku" else "android", normal.backend)
        }
        val missing = RunCommandTools.androidConfiguration(app.daiyu.android.runningmode.RunningMode.ROOT,
            cwd, emptyMap()) { null }
        assertTrue(missing.root)
        assertEquals("/system/bin/su", missing.rootExecutable)
    }

    @Test fun rootIdentityAndExecutableArePartOfExactCommandGrant() {
        val normal = Fixture()
        val root = Fixture(androidConfiguration = TerminalConfiguration(root = true, workingDirectory = File("/fixed"), rootExecutable = "/system/bin/su"))
        val otherSu = Fixture(androidConfiguration = TerminalConfiguration(root = true, workingDirectory = File("/fixed"), rootExecutable = "/sbin/su"))
        normal.dispatch(); root.dispatch(); otherSu.dispatch()
        assertNotEquals(normal.effects.single().target, root.effects.single().target)
        assertNotEquals(root.effects.single().target, otherSu.effects.single().target)
        assertTrue(root.configurations.single().root)
        assertEquals("/system/bin/su", root.configurations.single().rootExecutable)
        assertTrue(root.disclosures.single().contains("Android Root"))
        assertFalse(normal.configurations.single().root)
        assertEquals(normal.executedCommands, root.executedCommands)
        normal.leaf.close(); root.leaf.close(); otherSu.leaf.close()
    }

    @Test fun failedRootOpenNeverCreatesAnAppUidFallbackOrDispatchesCommand() {
        val fixture = Fixture(androidConfiguration = TerminalConfiguration(root = true, workingDirectory = File("/fixed"), rootExecutable = "/system/bin/su"))
        fixture.openError = "root_unavailable"
        val result = fixture.dispatch()
        assertTrue(result is DispatchOutcome.NotExecuted)
        assertTrue(result.toString().contains("root_unavailable"))
        assertEquals(0, fixture.executions)
        assertTrue(fixture.configurations.all { it.root })
        fixture.leaf.close()
    }

    @Test fun resolvedTargetIsPublishedBeforeCommandCompletesWithoutConfirmingItsReceipt() {
        val fixture = Fixture()
        fixture.openIdentity = JSONObject().put("backend", "linux:debian").put("uid", 0)
            .put("guest_uid", 0).put("root", true).put("host_root", true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture.onExecute = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        val executing = java.util.concurrent.FutureTask { fixture.dispatch() }
        Thread(executing).start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(executing.isDone)
            val target = fixture.owner.terminalTargets.values.single()
            assertEquals("linux:debian", target.backend)
            assertEquals(0, target.guestUid)
            assertEquals(true, target.hostRoot)
            assertEquals(0, fixture.executions)
            assertEquals(1, fixture.created)
        } finally { release.countDown() }
        assertTrue(executing.get(5, java.util.concurrent.TimeUnit.SECONDS) is DispatchOutcome.Confirmed)
        assertEquals(1, fixture.executions)
        fixture.leaf.close()
    }

    @Test fun targetComesFromResolvedIdentityNotRequestedEnvironmentAndRemainsUnknownOnLostReceipt() {
        val fixture = Fixture()
        fixture.openIdentity = JSONObject().put("backend", "linux:alpine").put("uid", 1000)
            .put("guest_uid", 1000).put("root", false).put("host_root", false)
        fixture.failExecute = true
        assertTrue(fixture.dispatch(environment = "android") is DispatchOutcome.Unknown)
        val target = fixture.owner.terminalTargets.values.single()
        assertEquals("linux:alpine", target.backend)
        assertEquals(1000, target.guestUid)
        assertEquals(false, target.hostRoot)
        val protected = fixture.dispatch("next")
        assertTrue(protected is DispatchOutcome.Unknown)
        assertEquals("unknown_effect_protected", (protected as DispatchOutcome.Unknown).code)
        assertTrue(fixture.dispatch("different", command = "echo different") is DispatchOutcome.NotExecuted)
        assertEquals(1, fixture.executions)
        assertEquals(1, fixture.prompts)
        assertEquals(1, fixture.owner.terminalTargets.size)
        fixture.leaf.close()
    }

    @Test fun malformedIdentityValuesAreNotCoercedAndUnknownBackendIsNotPublished() {
        for (invalid in listOf<Any>("0", 0.5, -1, Long.MAX_VALUE)) {
            val fixture = Fixture()
            fixture.openIdentity = JSONObject().put("backend", "linux:debian").put("uid", invalid)
                .put("guest_uid", invalid).put("root", "true").put("host_root", "true")
            assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
            assertTrue(fixture.owner.terminalTargets.isEmpty())
            fixture.leaf.close()
        }
        val unknown = Fixture()
        unknown.openIdentity = JSONObject().put("backend", "future:unknown").put("uid", 0)
        assertTrue(unknown.dispatch() is DispatchOutcome.Confirmed)
        assertTrue(unknown.owner.terminalTargets.isEmpty())
        unknown.leaf.close()
    }

    @Test fun oneMalformedUidCannotDisappearAndPromoteTheOtherUidToRoot() {
        for (key in listOf("uid", "guest_uid")) {
            for (invalid in listOf<Any>("0", "bad", 0.5, -1, Long.MAX_VALUE)) {
                val fixture = Fixture()
                fixture.openIdentity = JSONObject().put("backend", "linux:debian").put("uid", 0)
                    .put("guest_uid", 0).put(key, invalid)
                try {
                    assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
                    assertTrue("Malformed $key=$invalid must not publish a stronger identity",
                        fixture.owner.terminalTargets.isEmpty())
                    assertEquals(1, fixture.executions)
                } finally { fixture.leaf.close() }
            }
        }
    }

    @Test fun missingAndExplicitNullUidKeepCompatibleResolvedMetadata() {
        for (key in listOf("uid", "guest_uid")) {
            for (explicitNull in listOf(false, true)) {
                val fixture = Fixture()
                fixture.openIdentity = JSONObject().put("backend", "linux:debian").put("uid", 0).put("guest_uid", 0)
                if (explicitNull) fixture.openIdentity.put(key, JSONObject.NULL) else fixture.openIdentity.remove(key)
                try {
                    assertTrue(fixture.dispatch() is DispatchOutcome.Confirmed)
                    val target = fixture.owner.terminalTargets.values.single()
                    assertEquals("linux:debian", target.backend)
                    assertEquals(if (key == "uid") null else 0, target.uid)
                    assertEquals(if (key == "guest_uid") null else 0, target.guestUid)
                } finally { fixture.leaf.close() }
            }
        }
    }

    @Test fun deniedOrFailedOpenNeverPublishesAnActualTarget() {
        val denied = Fixture().apply { approved = false }
        assertTrue(denied.dispatch() is DispatchOutcome.NotExecuted)
        assertTrue(denied.owner.terminalTargets.isEmpty())
        val failed = Fixture().apply { openError = "root_unavailable" }
        assertTrue(failed.dispatch() is DispatchOutcome.NotExecuted)
        assertTrue(failed.owner.terminalTargets.isEmpty())
        assertEquals(0, failed.executions)
        denied.leaf.close(); failed.leaf.close()
    }

}
