package app.daiyu.android

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.terminal.*
import app.daiyu.android.ui.terminalApprovalSections
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Host-only contracts: no Context, actual PTY, model, network, installation, or dispatcher. */
class ControllerTerminalPreparationTest {
    private val hostCwd = File(System.getProperty("java.io.tmpdir")).absoluteFile.toPath().normalize().toFile()
    private val conversation = Conversation(ConversationId("conversation"), "title", "", 0)
    private val draft = TerminalDraftSnapshot(TerminalDraftOwner(conversation.id, 2, 3), "exact draft")
    private val assistant = AssistantProfile("profile", "profile name", "frozen instructions")
    private val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid", "fixture-model", "synthetic-key", false))
    private fun plan(
        launch: TerminalPtyLaunch = TerminalPtyLaunch.Android(listOf("/system/bin/sh"), mapOf("PATH" to "/system/bin"), hostCwd),
        bytes: ByteArray = "printf approved\n".toByteArray(),
    ) = TerminalPtyPlan(conversation.id, "session", "run", "environment", "generation", launch,
        File(hostCwd, "terminal-preparation-journal"), "scope", listOf(
            TerminalPtyStep.Open("open"), TerminalPtyStep.Input("input", "request", 17, bytes),
            TerminalPtyStep.Resize("resize", "resize_request", 4, 30, 100),
            TerminalPtyStep.Interrupt("interrupt", "interrupt_request", 5),
        ), listOf(TerminalPtyRead("page", 0, 16384), TerminalPtyRead("tail", 16384, 21)))

    private class Fixture(val plan: TerminalPtyPlan, profile: AssistantProfile? = null) {
        val approvals = AtomicInteger()
        val opens = AtomicInteger()
        val tokens = mutableListOf<TaskAuthorization>()
        private val owner = ControllerTerminalPreparationTest()
        val preparation = ControllerTerminalPreparation.prepareOwned(owner.conversation, owner.draft,
            profile ?: owner.assistant, owner.provider, plan, MessageId("assistant-message")) { frozen, token ->
            approvals.incrementAndGet()
            tokens.add(token)
            CatalogTerminalBinding.approved(frozen, token) { opens.incrementAndGet(); error("must not open") }
        }
    }


    @Test fun defaultConsentKeepsEachFrozenPtyInputIsolatedAndCancellationCannotTransfer() {
        val first = Fixture(plan(bytes = "echo first\n".toByteArray()))
        val second = Fixture(plan(bytes = "echo second\n".toByteArray()))
        assertFalse(app.daiyu.android.integration.tools.CommandPermissionDefaults.REQUIRE_INTERACTIVE_APPROVAL)
        for (f in listOf(first, second)) {
            val p = f.preparation
            assertTrue(p.confirm(p.preview, draft, p.assistant, p.provider))
            assertEquals(f.plan.effects.values.toSet(), f.tokens.single().allowedWrites)
            assertEquals(0, f.opens.get())
        }
        assertNotEquals(first.tokens.single().allowedWrites, second.tokens.single().allowedWrites)
        val pending = first.preparation
        pending.cancel()
        assertNull(pending.takeConfirmed(pending.preview, draft, pending.assistant, pending.provider))
        assertFalse(pending.confirm(pending.preview, draft, pending.assistant, pending.provider))
        assertTrue(pending.awaitCleanup(1))
        second.preparation.cancel()
        assertTrue(second.preparation.awaitCleanup(1))
    }

    @Test fun providerAndProfileAreCheckedAtAuthorizationAndTransfer() {
        val f = Fixture(plan())
        val p = f.preparation
        assertFalse(p.confirm(p.preview, draft, p.assistant.copy(prompt = "changed"), p.provider))
        val changedProvider = ProviderSnapshot.capture(ModelSettings("https://other.invalid", "fixture-model", "synthetic-key", false))
        assertFalse(p.confirm(p.preview, draft, p.assistant, changedProvider))
        assertEquals(0, f.approvals.get())
        assertTrue(p.confirm(p.preview, draft, p.assistant, p.provider))
        assertNull(p.takeConfirmed(p.preview, draft, p.assistant, changedProvider))
        assertEquals(ControllerTerminalPreparation.State.CONFIRMED, p.state)
        val accepted = checkNotNull(p.takeConfirmed(p.preview, draft, p.assistant, p.provider))
        accepted.binding.close()
        assertTrue(accepted.binding.awaitTermination(1))
    }

    @Test fun obsoleteAndDisposedUiCallbacksCannotPrepareOrApprove() {
        val f = Fixture(plan())
        val p = f.preparation
        val gate = app.daiyu.android.ui.TerminalApprovalCallbackGate()
        val initial = gate.update(null, draft, assistant, provider, null)
        val capturedPrepare = { gate.prepare(initial) { error("obsolete prepare") } }
        val valid = gate.update(p, draft, p.assistant, p.provider, p.state)
        assertFalse(capturedPrepare())
        var confirmations = 0
        val capturedConfirm = { gate.confirm(valid) { _, _, _ -> confirmations++; true } }
        gate.update(p, draft, p.assistant.copy(prompt = "changed"), provider, p.state)
        assertFalse(capturedConfirm())
        val last = gate.update(p, draft, p.assistant, p.provider, p.state)
        gate.close()
        assertFalse(gate.confirm(last) { _, _, _ -> confirmations++; true })
        assertEquals(0, confirmations)
        assertEquals(0, f.approvals.get())
        p.cancel()
        assertTrue(p.awaitCleanup(1))
    }

    @Test fun confirmationChecksOriginalPreviewAndEveryDraftFieldBeforeIssuingOneExactToken() {
        val f = Fixture(plan())
        val p = f.preparation
        val copiedPreview = TerminalRunPreview(p.identity, p.preview.plan)
        assertFalse(p.confirm(copiedPreview, draft, p.assistant, p.provider))
        listOf(
            draft.copy(text = "changed"),
            draft.copy(owner = draft.owner.copy(conversationId = ConversationId("other"))),
            draft.copy(owner = draft.owner.copy(editorGeneration = 4)),
            draft.copy(owner = draft.owner.copy(draftRevision = 4)),
        ).forEach { assertFalse(p.confirm(p.preview, it, p.assistant, p.provider)) }
        assertEquals(0, f.approvals.get())
        assertTrue(p.confirm(p.preview, draft, p.assistant, p.provider))
        assertFalse(p.confirm(p.preview, draft, p.assistant, p.provider))
        assertEquals(1, f.approvals.get())
        val confirmed = checkNotNull(p.takeConfirmed(p.preview, draft, p.assistant, p.provider))
        assertSame(f.tokens.single(), confirmed.authorization)
        assertSame(confirmed.authorization, confirmed.binding.authorization)
        assertEquals(f.plan.effects.values.toSet(), confirmed.authorization.allowedWrites)
        assertEquals("session", confirmed.authorization.sessionId)
        assertEquals("run", confirmed.authorization.taskId)
        assertSame(f.plan, confirmed.binding.plan)
        assertNull(p.takeConfirmed(p.preview, draft, p.assistant, p.provider))
        p.cancel() // After transfer, cleanup belongs only to parent.
        assertEquals(ControllerTerminalPreparation.State.TRANSFERRED, p.state)
        assertEquals(0, f.opens.get())
        confirmed.binding.close()
        assertTrue(confirmed.binding.awaitTermination(1))
    }

    @Test fun freezesProfileProviderDraftAndMutablePlanInputs() {
        val skills = mutableListOf("skill-a")
        val arguments = mutableListOf("/system/bin/sh", "-c", "printf untouched")
        val environment = mutableMapOf("PATH" to "/system/bin", "SPECIAL" to "a\nb")
        val bytes = byteArrayOf(0, 13, 10, -1)
        val originalBytes = bytes.copyOf()
        val frozenPlan = plan(TerminalPtyLaunch.Android(arguments, environment, hostCwd), bytes)
        val f = Fixture(frozenPlan, assistant.copy(enabledSkillIds = skills))
        val p = f.preparation
        skills.add("later")
        arguments[2] = "changed"
        environment["SPECIAL"] = "changed"
        bytes.fill(65)
        assertEquals(listOf("skill-a"), p.assistant.enabledSkillIds)
        assertEquals(provider, p.provider)
        assertEquals(draft, p.draft)
        assertEquals(MessageId("assistant-message"), p.identity.assistantMessageId)
        assertEquals(conversation.id, p.identity.conversationId)
        val launch = frozenPlan.launch as TerminalPtyLaunch.Android
        assertEquals("printf untouched", launch.argv.last())
        assertEquals("a\nb", launch.environment["SPECIAL"])
        assertArrayEquals(originalBytes, (frozenPlan.steps[1] as TerminalPtyStep.Input).bytes())
        p.cancel()
        assertFalse(p.confirm(p.preview, draft, p.assistant, p.provider))
        assertTrue(p.awaitCleanup(1))
        assertEquals(0, f.approvals.get())
    }

    @Test fun simultaneousConfirmationCannotManufactureTwoAuthorizations() {
        val f = Fixture(plan())
        val go = CountDownLatch(1)
        val accepted = AtomicInteger()
        val workers = (1..8).map {
            Thread { go.await(); if (f.preparation.confirm(f.preparation.preview, draft, f.preparation.assistant, f.preparation.provider)) accepted.incrementAndGet() }
        }
        workers.forEach(Thread::start)
        go.countDown()
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, accepted.get())
        assertEquals(1, f.approvals.get())
        assertEquals(0, f.opens.get())
        f.preparation.cancel()
        assertTrue(f.preparation.awaitCleanup(1))
    }

    @Test fun reusesFinitePlanValidationRatherThanBroadCommandGrant() {
        fun build(steps: List<TerminalPtyStep>) = TerminalPtyPlan(conversation.id, "session", "run",
            "environment", "generation", TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), hostCwd),
            File(hostCwd, "journal"), "scope", steps)
        listOf(emptyList(), listOf(TerminalPtyStep.Input("input", "request", 0, byteArrayOf(1))),
            listOf(TerminalPtyStep.Open("open")) + (1..64).map { TerminalPtyStep.Interrupt("step_$it", "request_$it", it.toLong()) }
        ).forEach { steps ->
            try { build(steps); fail("invalid finite plan accepted") } catch (_: IllegalArgumentException) {}
        }
        val maximal = build(listOf(TerminalPtyStep.Open("open")) +
            (1..63).map { TerminalPtyStep.Interrupt("step_$it", "request_$it", it.toLong()) })
        val f = Fixture(maximal)
        assertTrue(f.preparation.confirm(f.preparation.preview, draft, f.preparation.assistant, f.preparation.provider))
        assertEquals(64, f.tokens.single().allowedWrites.size)
        f.preparation.cancel()
        assertTrue(f.preparation.awaitCleanup(1))
    }

    @Test fun presentationIncludesEntireBinaryInputEffectsLaunchAndReadPagesWithoutSecrets() {
        val bytes = ByteArray(65536) { (it % 256).toByte() }
        val f = Fixture(plan(bytes = bytes))
        val sections = terminalApprovalSections(f.preparation)
        val inputPages = sections.filter { it.id.startsWith("terminal-input-1-") }
        val recovered = inputPages.flatMap { section ->
            Base64.getDecoder().decode(section.text.substringAfter('\n')).toList()
        }.toByteArray()
        assertArrayEquals(bytes, recovered)
        val effectText = sections.filter { it.id.startsWith("terminal-effect-1-") }.joinToString("") { it.text }
        assertTrue(effectText.endsWith(f.plan.effects.getValue("input").change))
        val all = sections.joinToString("\n") { it.text }
        assertTrue(all.contains("argv[0]=\"/system/bin/sh\""))
        assertTrue(all.contains("cwd=${org.json.JSONObject.quote(hostCwd.path)}"))
        assertTrue(all.contains("\"PATH\"=\"/system/bin\""))
        assertTrue(all.contains("id=tail offset=16384 limit=21"))
        assertTrue(all.contains("inputSequence=17"))
        assertTrue(all.contains("controlSequence=4"))
        assertTrue(all.contains("controlSequence=5"))
        assertFalse(all.contains(provider.apiKey))
        assertEquals(0, f.approvals.get())
        assertEquals(0, f.opens.get())
        f.preparation.cancel()
    }

    @Test fun presentationPagesDoNotSplitUnicodeScalarsOrDropLongArgumentSuffixes() {
        val argument = "x".repeat(4086) + "\uD83D\uDE42".repeat(100) + "ARGUMENT-END"
        val f = Fixture(plan(TerminalPtyLaunch.Android(listOf("/system/bin/sh", argument), emptyMap(), hostCwd)))
        val pages = terminalApprovalSections(f.preparation).filter { it.id.startsWith("terminal-argv-1-") }
        assertTrue(pages.size > 1)
        assertEquals("argv[1]=${org.json.JSONObject.quote(argument)}", pages.joinToString("") { it.text })
        assertTrue(pages.none { Character.isHighSurrogate(it.text.last()) || Character.isLowSurrogate(it.text.first()) })
        f.preparation.cancel()
    }

    private class LinuxFixture : AutoCloseable {
        val root = Files.createTempDirectory("terminal-preparation-").toFile()
        private val rootfs = File(root, "rootfs").apply { File(this, "bin").mkdirs(); File(this, "bin/sh").writeText("not executable") }
        private val proot = File(root, "proot").apply { writeText("never executed"); check(setExecutable(true)) }
        private val loader = File(root, "loader").apply { writeText("fixture") }
        val temp = File(root, "tmp").apply { mkdirs() }
        val cwd = File(root, "cwd").apply { mkdirs() }
        val ref = LinuxGenerationRef("environment", "generation")
        private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val store = LinuxEnvironmentStore(File(root, "selection")).apply {
            select(LinuxInstallation("fixture", ref.environmentId, ref.generation, rootfs, proot, hash(proot), loader, hash(loader)))
        }
        fun freeze() = TerminalPtyLaunch.Linux.freeze(store, ref, temp, cwd)
        fun assertPinned() {
            try { store.clearSelection(); fail("lost lease") }
            catch (failure: IllegalStateException) { assertEquals("linux_environment_in_use", failure.message) }
        }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun linuxUnclaimedCancelReleasesButConfirmedCancelWaitsForBindingDrain() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        LinuxFixture().use { linux ->
            val p = Fixture(plan(linux.freeze())).preparation
            linux.assertPinned()
            p.cancel()
            assertEquals(ControllerTerminalPreparation.State.DRAINED, p.state)
            linux.store.clearSelection()
        }
        LinuxFixture().use { linux ->
            val launch = linux.freeze()
            val f = Fixture(plan(launch))
            val p = f.preparation
            assertTrue(p.confirm(p.preview, draft, p.assistant, p.provider))
            p.cancel()
            assertEquals(ControllerTerminalPreparation.State.CLOSING, p.state)
            linux.assertPinned()
            try { launch.close(); fail("claimed launch closed as preview") } catch (_: IllegalStateException) {}
            assertTrue(p.awaitCleanup(1))
            linux.store.clearSelection()
            assertEquals(0, f.opens.get())
        }
    }

    @Test fun ambiguousApprovalRetainsClaimedLinuxResourceAndNeverReapprovesOrGuessesDrain() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        LinuxFixture().use { linux ->
            var retained: CatalogTerminalBinding? = null
            val count = AtomicInteger()
            val p = ControllerTerminalPreparation.prepareOwned(conversation, draft, assistant, provider, plan(linux.freeze())) { plan, token ->
                count.incrementAndGet()
                retained = CatalogTerminalBinding.approved(plan, token) { error("must not start") }
                error("ambiguous publication")
            }
            assertFalse(p.confirm(p.preview, draft, p.assistant, p.provider))
            assertEquals(ControllerTerminalPreparation.State.UNKNOWN, p.state)
            p.cancel()
            assertFalse(p.awaitCleanup(1))
            assertFalse(p.confirm(p.preview, draft, p.assistant, p.provider))
            assertEquals(1, count.get())
            linux.assertPinned()
            checkNotNull(retained).close()
            assertTrue(checkNotNull(retained).awaitTermination(1))
            linux.store.clearSelection()
        }
    }
}
