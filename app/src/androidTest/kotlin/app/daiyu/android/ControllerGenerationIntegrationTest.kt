package app.daiyu.android

import android.test.InstrumentationTestCase
import android.net.Uri
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.domain.*
import app.daiyu.android.media.GenerationAssetException
import app.daiyu.android.media.GenerationAssetFailure
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.storage.*
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.GenerationToolsCloseResult
import app.daiyu.android.integration.tools.RunGenerationTools
import app.daiyu.android.model.generation.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real controller, durable two-row admission and Runner-issued identity. No paid HTTP/tool call. */
@Suppress("DEPRECATION")
class ControllerGenerationIntegrationTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private val controller get() = fixture.controller
    private val editor = GenerationEditorSnapshot(23, "  exact generation draft\n ")
    private lateinit var store: GenerationSettingsStore
    private var configured: GenerationSettingsSnapshot? = null

    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext)
        awaitControl("controller ready") { controller.ready }
        store = GenerationSettingsStore(fixture.application)
        configured = null
        fixture.database.draft(editor.text)
    }

    override fun tearDown() {
        try {
            if (::fixture.isInitialized) {
                // A failed assertion must not strand an Accepted-but-unconsumed reservation.
                controlMain {
                    controller.currentGenerationEntry()?.let { entry ->
                        if (entry.submitted) entry.session?.let { session ->
                            controller.pendingGenerationReceiptNotice()?.let(controller::acknowledgeGenerationReceipt)
                        } else controller.abandonGeneration(entry)
                    }
                }
                fixture.close()
            }
        } finally { super.tearDown() }
    }

    private fun saveSettings(): GenerationSettingsSnapshot = configured ?: store.save(GenerationSettingsValues(
        listOf(GenerationProfileSettings(label = "explicit image profile", kind = GenerationKind.IMAGE,
            protocol = GenerationProtocol.OPENAI_IMAGES_JSON,
            endpoint = "https://generation.invalid/images/generations", model = "explicit-image-model",
            apiKey = "synthetic-generation-test-key")), maxPolls = 0,
        downloadsEnabled = false, downloadHosts = emptySet()), null).also { configured = it }

    private fun prepare(): ChatController.GenerationEntry {
        saveSettings()
        return checkNotNull(controller.prepareGeneration(null, 17, editor))
    }

    private fun confirm(entry: ChatController.GenerationEntry, submittedEditor: GenerationEditorSnapshot = editor) {
        val preparation = ControllerGenerationPreparation.prepare(entry.identity, editor, entry.settings.profiles,
            listOf(GenerationBatchSelection("batch", entry.settings.profiles.single().id,
                listOf(GenerationRunConfig.Item("item", "draw this exact prompt")))),
            entry.settings.maxPolls, entry.settings.downloads)
        controlMain {
            assertTrue(entry.handoff.confirm(preparation, editor, entry.identity) {
                controller.sendGeneration(entry, submittedEditor)
            })
        }
    }

    fun testMissingIndependentConfigAndCancelledPreviewNeverEnterModelOrDatabaseSend() {
        assertNull(controller.prepareGeneration(null, 17, editor))
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
        val entry = prepare()
        assertSame(entry, controller.currentGenerationEntry())
        assertNull(entry.handoff.token)
        assertNull(controller.send(null, "must not bypass reserved generation form"))
        controlMain { controller.abandonGeneration(entry) }
        assertNull(controller.currentGenerationEntry())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testParentRejectsDifferentRevisionEvenWhenTextWasEditedBack() {
        val entry = prepare()
        val preview = ControllerGenerationPreparation.prepare(entry.identity, editor, entry.settings.profiles,
            listOf(GenerationBatchSelection("batch", entry.settings.profiles.single().id,
                listOf(GenerationRunConfig.Item("item", "draw")))), 0, entry.settings.downloads)
        controlMain {
            assertFalse(entry.handoff.confirm(preview, editor, entry.identity) {
                controller.sendGeneration(entry, GenerationEditorSnapshot(editor.revision + 1, editor.text))
            })
        }
        awaitControl("rejected handoff releases only after real cleanup") { controller.currentGenerationEntry() == null }
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testRealAdmissionBindsOneOwnerAndOneTaskBeforeModelAndConsumesOnlyExactReceipt() {
        val model = fixture.session(held = true)
        val entry = prepare()
        confirm(entry)
        assertTrue(model.entered.await(10, TimeUnit.SECONDS))
        val session = checkNotNull(entry.session)
        val accepted = session.persistenceReceipt as GenerationPersistenceReceipt.Accepted
        val rows = fixture.database.messages(accepted.conversation.id)
        assertEquals(2, rows.size)
        assertEquals(entry.identity.assistantMessageId, rows.last().second.id)
        assertEquals(entry.identity.runId, rows.last().second.runId)
        assertEquals(entry.identity.conversationId, rows.last().second.conversationId)
        assertEquals(RunStatus.RUNNING, rows.last().second.status)
        assertEquals("", fixture.database.draft())
        assertEquals(1, fixture.opens.get())

        val catalog = checkNotNull(entry.catalog)
        val actualOwner = field(catalog, "owner") as RunExecutionOwner
        val actualTask = field(catalog, "boundAuthorization") as TaskAuthorization
        assertTrue(actualOwner.owns(rows.last().second))
        assertSame(actualOwner, field(session, "owner"))
        assertSame(actualTask, field(session, "authorization"))
        assertSame(field(controller, "generationResources"), field(session, "resources"))
        assertEquals(1, actualTask.allowedWrites.size)
        assertEquals(entry.identity.runId.value, actualTask.taskId)
        assertTrue(actualTask.allowedWrites.containsAll(session.token.paidWrites(actualOwner, editor, entry.identity)))
        assertNotNull(field(catalog, "generation"))

        val removals = AtomicInteger()
        controlMain {
            assertFalse(controller.consumeGenerationReceipt(entry, 18, editor) { removals.incrementAndGet() })
            assertFalse(controller.consumeGenerationReceipt(entry, 17,
                GenerationEditorSnapshot(24, editor.text)) { removals.incrementAndGet() })
            assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) { removals.incrementAndGet() })
            assertFalse(controller.consumeGenerationReceipt(entry, 17, editor) { removals.incrementAndGet() })
        }
        assertEquals(1, removals.get())
        assertSame(entry, controller.currentGenerationEntry())
        model.release.countDown()
        awaitControl("real runner and generation cleanup retire") { controller.currentGenerationEntry() == null }
        assertEquals(GenerationToolsCloseResult.Drained, session.closeResult)
    }

    fun testExplicitReceiptRecoveryPreservesReplacementDraftAndWaitsForRunningWorker() {
        val model = fixture.session(held = true)
        val entry = prepare()
        confirm(entry)
        assertTrue(model.entered.await(10, TimeUnit.SECONDS))
        val notice = checkNotNull(controller.pendingGenerationReceiptNotice())
        fixture.database.draft("replacement editor draft")
        controlMain {
            assertFalse(controller.acknowledgeGenerationReceipt(notice.copy(
                identity = notice.identity.copy(accountScope = "different-account"))))
            assertFalse(controller.acknowledgeGenerationReceipt(notice.copy(
                receipt = GenerationPersistenceReceipt.Accepted(notice.receipt.conversation))))
            assertTrue(controller.acknowledgeGenerationReceipt(notice))
            assertFalse(controller.acknowledgeGenerationReceipt(notice))
            assertNull(controller.pendingGenerationReceiptNotice())
        }
        assertEquals("replacement editor draft", fixture.database.draft())
        assertSame(entry, controller.currentGenerationEntry())
        assertFalse(checkNotNull(entry.session).canRetire)
        assertEquals(1, fixture.opens.get())
        model.release.countDown()
        awaitControl("acknowledged receipt still waits for physical worker") {
            controller.currentGenerationEntry() == null
        }
        assertEquals("replacement editor draft", fixture.database.draft())
    }

    fun testStopAcknowledgementDoesNotPretendNonCooperativeRunnerHasExited() {
        val model = fixture.session(held = true, holdSeconds = 30, releaseOnCancel = false)
        val entry = prepare()
        confirm(entry)
        assertTrue(model.entered.await(10, TimeUnit.SECONDS))
        controlMain {
            assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) {})
            controller.stop()
        }
        assertTrue(model.cancelled.await(5, TimeUnit.SECONDS))
        assertSame(entry, controller.currentGenerationEntry())
        assertNull(checkNotNull(entry.session).closeResult)
        assertFalse(checkNotNull(entry.session).canRetire)
        model.release.countDown()
        awaitControl("must wait for actual runner exit") { controller.currentGenerationEntry() == null }
        assertEquals(GenerationToolsCloseResult.Drained, checkNotNull(entry.session).closeResult)
    }

    fun testPersistedDraftCasFailureNeverFabricatesAcceptedOrClearsReplacement() {
        val entry = prepare()
        fixture.database.draft("newer persisted draft")
        confirm(entry)
        awaitControl("rejected preparation drains") { controller.currentGenerationEntry() == null }
        assertSame(GenerationPersistenceReceipt.Rejected, checkNotNull(entry.session).persistenceReceipt)
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals("newer persisted draft", fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testChangedGenerationConfigRequiresFreshPreviewAndNeverFallsBackToChatProfile() {
        val entry = prepare()
        store.save(entry.settings.values, entry.settings.revision)
        confirm(entry)
        awaitControl("stale config rejected") { controller.currentGenerationEntry() == null }
        assertSame(GenerationPersistenceReceipt.Rejected, checkNotNull(entry.session).persistenceReceipt)
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsSaveAloneCannotCreateApprovalOrGenerationAssets() {
        val saved = saveSettings()
        assertEquals("explicit-image-model", saved.profiles.single().model)
        assertNull(controller.currentGenerationEntry())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(editor.text, fixture.database.draft())
        assertTrue(ControllerGenerationResources.DOWNLOAD_STATUS.contains("未启用"))
        // Capability alone and a configured host are still not download permission.
        assertFalse(ControllerGenerationResources.DENY_DOWNLOADS.allows(
            app.daiyu.android.media.GenerationAssetOwner(saved.accountScope,
                app.daiyu.android.storage.GenerationAttemptBinding("conversation", "assistant", "run", "call",
                    RunGenerationTools.IMAGE_TOOL)), "https://cdn.example/video.mp4"))
    }

    fun testAssetReadUsesWorkerAndSideTableWithoutStartingGeneration() {
        saveSettings()
        val conversation = fixture.database.create("default")
        val done = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.atomic.AtomicReference<List<ChatController.GenerationAssetDisplay>?>()
        controlMain {
            assertTrue(controller.loadGenerationOutputs(conversation.id) {
                assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                returned.set(it); done.countDown()
            })
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNotNull(returned.get())
        assertTrue(checkNotNull(returned.get()).isEmpty())
        assertEquals(0, fixture.opens.get())
        assertNull(controller.currentGenerationEntry())
        assertTrue(fixture.database.messages(conversation.id).isEmpty())
    }

    private data class ExistingOutput(val conversation: Conversation, val owner: GenerationAssetOwner,
        val attempt: GenerationAttempt, val resources: ControllerGenerationResources,
        val assets: MessageGenerationAssets, val png: ByteArray)

    /** Offline fixture only: persist a real two-row owner with its original tool invocation, then
     * publish a static PNG and register it using the production filesystem/SQLite stores. No fake
     * Tool.result URL or fake output reader can make these tests pass. The central attempt is
     * durably UNKNOWN, not Confirmed; readback must not reserve a new paid attempt.
     */
    private fun existingOutput(): ExistingOutput {
        val settings = saveSettings()
        val conversation = fixture.database.create("default")
        val run = RunId(UUID.randomUUID().toString())
        val assistantId = MessageId(UUID.randomUUID().toString())
        val call = ToolInvocation(ToolCallId(UUID.randomUUID().toString()), RunGenerationTools.IMAGE_TOOL, "{}")
        val tool = MessageBlock.Tool(call)
        val assistant = Message(assistantId, conversation.id, run, Message.ASSISTANT_ROLE,
            listOf(tool), RunStatus.RUNNING, historySegments = listOf(
                MessageHistorySegment(assistantId, Message.ASSISTANT_ROLE, listOf(tool))))
        fixture.database.send(Message(MessageId(UUID.randomUUID().toString()), conversation.id, run,
            Message.USER_ROLE, listOf(MessageBlock.Text("prompt", "offline fixture")), RunStatus.COMPLETED), assistant)
        assertTrue(checkNotNull(loadOutputs(conversation.id)).isEmpty()) // constructs parent's resource
        val resources = field(controller, "generationResources") as ControllerGenerationResources
        val owner = GenerationAssetOwner(settings.accountScope, GenerationAttemptBinding(conversation.id.value,
            assistantId.value, run.value, call.id.value, call.name))
        val attempt = GenerationAttempt(owner.binding.identity("item"), "a".repeat(64),
            GenerationProtocol.OPENAI_IMAGES_JSON)
        val attempts = SQLiteGenerationAttemptStore(resources::database, owner.binding)
        assertEquals(GenerationReservation.Started, attempts.reserve(attempt))
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kF9sAAAAASUVORK5CYII=")
        val assets = resources.assets(resources.createFence(owner))
        val info = assets.inspect(GenerationKind.IMAGE, png, "image/png")
        val receipt = assets.commit(attempt.identity, 0, GenerationKind.IMAGE, png, info)
        SQLiteGenerationOutputStore(resources::database, owner, assets).register(attempt, receipt)
        attempts.finish(attempt, Submission.Unknown(GenerationFailure.TRANSPORT))
        val endedTool = tool.copy(result = "{\"status\":\"UNKNOWN\"}")
        fixture.database.save(assistant.copy(status = RunStatus.COMPLETED, blocks = listOf(endedTool),
            historySegments = listOf(MessageHistorySegment(assistantId, Message.ASSISTANT_ROLE, listOf(endedTool)))))
        assertTrue((attempts.reserve(attempt) as GenerationReservation.Existing).submission is Submission.Unknown)
        return ExistingOutput(conversation, owner, attempt, resources, assets, png)
    }

    private fun loadOutputs(id: ConversationId): List<ChatController.GenerationAssetDisplay>? {
        val done = CountDownLatch(1)
        val result = AtomicReference<List<ChatController.GenerationAssetDisplay>?>()
        controlMain {
            assertTrue(controller.loadGenerationOutputs(id) {
                assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                result.set(it); done.countDown()
            })
        }
        assertTrue("history reader completion", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun assertReadable(output: ExistingOutput) {
        val display = checkNotNull(loadOutputs(output.conversation.id)).single()
        assertEquals(output.png.size.toLong(), display.metadata.bytes)
        assertEquals(GenerationKind.IMAGE, display.metadata.kind)
        assertNotNull(display.thumbnail)
        assertSame(output.resources, field(controller, "generationResources"))
        output.resources.openOutputs(output.owner).use { reader ->
            assertTrue(output.png.contentEquals(checkNotNull(reader.openOriginal(output.attempt, 0)).use { it.readBytes() }))
        } // Closing a sibling reader must not seal the cached reader or a writer.
    }

    private fun dataOperation(start: () -> Unit): String {
        controlMain { controller.consumeDataNotice(); start() }
        awaitControl("data operation completed") { !controller.dataBusy && controller.dataNotice != null }
        return checkNotNull(controller.dataNotice)
    }

    fun testExistingOutputSurvivesFailedDuplicateAndUnrelatedAppendImports() {
        val output = existingOutput()
        assertReadable(output)
        val oldRows = fixture.database.messages(output.conversation.id)
        val cachedReaders = (field(controller, "generationOutputReaders") as Map<*, *>).values.toList()
        val file = File(fixture.application.cacheDir, "generation-import-${UUID.randomUUID()}.json")
        val source = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("source ready") { source.controller.ready }
            // Failure before parsing; the old cached and uncached owner fences remain usable.
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("导入失败"))
            assertReadable(output)

            // Actual transaction rollback AFTER inserting an unrelated conversation: global
            // draft conflict must not seal old assets or leave the new conversation behind.
            val appended = source.database.create("default")
            fixture.database.draft("keep existing draft")
            source.database.draft("conflicting incoming draft")
            file.writeText(source.database.exportBackup())
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), false) }.startsWith("导入失败"))
            assertFalse(fixture.database.conversations().any { it.id == appended.id })
            assertEquals("keep existing draft", fixture.database.draft())
            assertReadable(output)

            // A complete append, then that package's real idempotent duplicate path.
            source.database.draft("")
            file.writeText(NeutralBackupService(source.application, source.database).exportPackage())
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("已导入"))
            assertTrue(fixture.database.conversations().any { it.id == appended.id })
            assertReadable(output)
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), true) }.startsWith("此完整备份已导入过"))
            assertReadable(output)

            // The text importer's identity collision is a rollback, not owner replacement.
            file.writeText(source.database.exportBackup())
            assertTrue(dataOperation { controller.importUserBackup(Uri.fromFile(file), false) }.startsWith("导入失败"))
            assertReadable(output)
            assertEquals(oldRows, fixture.database.messages(output.conversation.id))
            val retainedReaders = (field(controller, "generationOutputReaders") as Map<*, *>).values.toList()
            assertEquals(cachedReaders.size, retainedReaders.size)
            cachedReaders.zip(retainedReaders).forEach { (before, after) -> assertSame(before, after) }
            assertEquals(0, fixture.opens.get())
        } finally { file.delete(); source.close() }
    }

    fun testHistoryAndSuccessiveRealSessionsKeepOneResourceAndStopDoesNotSealHistory() {
        val output = existingOutput()
        repeat(2) {
            fixture.database.draft(editor.text)
            val model = fixture.session(held = true)
            val entry = prepare()
            confirm(entry)
            assertTrue(model.entered.await(10, TimeUnit.SECONDS))
            assertSame(output.resources, field(checkNotNull(entry.session), "resources"))
            controlMain {
                assertTrue(controller.consumeGenerationReceipt(entry, 17, editor) {})
                controller.stop()
            }
            model.release.countDown()
            awaitControl("session physically retired") {
                controller.currentGenerationEntry() == null && controller.active == null && field(controller, "resources") == null
            }
            assertReadable(output)
        }
    }

    fun testConversationDeleteWaitsForFinalRegistrationValidatorAndSealsFutureFences() {
        val output = existingOutput()
        val other = existingOutput()
        assertSame(output.resources, other.resources)
        assertReadable(output)
        val info = output.assets.inspect(GenerationKind.IMAGE, output.png, "image/png")
        val pending = output.assets.commit(output.attempt.identity, 1, GenerationKind.IMAGE, output.png, info)
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val validations = AtomicInteger()
        // register() validates the file first, then re-enters the same owner gate for its
        // final SQLite transaction. Hold that second validator, not a fake output store.
        val racingFence = output.resources.createFence(output.owner) { owner ->
            val valid = output.resources.validateOwner(owner)
            if (validations.incrementAndGet() == 2) {
                validating.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
            valid
        }
        val racingAssets = output.resources.assets(racingFence)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try { SQLiteGenerationOutputStore(output.resources::database, output.owner, racingAssets)
                .register(output.attempt, pending) }
            catch (problem: Throwable) { failure.set(problem) }
        }
        val mutation = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val success = AtomicBoolean()
        writer.start()
        try {
            assertTrue(validating.await(10, TimeUnit.SECONDS))
            controlMain {
                controller.mutateGenerationOwners(setOf(output.conversation.id.value), completed = {
                    success.set(it); completed.countDown()
                }) {
                    assertNotSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    assertFalse(output.resources.database().inTransaction())
                    mutation.countDown()
                    fixture.database.delete(output.conversation.id)
                }
            } // This must return without waiting for the held publication gate.
            assertFalse("deletion ran before publication barrier", mutation.await(100, TimeUnit.MILLISECONDS))
            // Also prove the control monitor is available while the worker waits on the gate.
            controlMain { assertTrue(controller.dataBusy) }
        } finally { release.countDown(); writer.join(10000) }
        assertFalse(writer.isAlive)
        assertNotNull("sealed final registration must fail", failure.get())
        assertTrue(completed.await(10, TimeUnit.SECONDS)); assertTrue(success.get())
        assertEquals(2, validations.get())
        assertFalse(fixture.database.conversations().any { it.id == output.conversation.id })
        assertNull(loadOutputs(output.conversation.id))
        assertSealed(output.resources, output.owner)
        assertReadable(other) // A conversation seal is not an account seal.
        assertSame(output.resources, field(controller, "generationResources"))
    }

    fun testDeleteAllWaitsForPublicationSealsOldIdsAndAllowsNewConversations() {
        val output = existingOutput()
        val published = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val mutation = CountDownLatch(1)
        val success = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val fence = output.resources.createFence(output.owner)
        val writer = Thread {
            try {
                fence.admit {
                    // Hold a real SQL publication transaction through its commit. No file IO
                    // under the gate: this is the same gate -> DB order as final registration.
                    val db = output.resources.database()
                    db.beginTransaction()
                    try {
                        db.execSQL("UPDATE generation_outputs SET width=width WHERE operation_id=?",
                            arrayOf(output.attempt.identity.operationId))
                        published.countDown(); check(release.await(10, TimeUnit.SECONDS))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }
        writer.start()
        try {
            assertTrue(published.await(10, TimeUnit.SECONDS))
            controlMain {
                controller.mutateGenerationOwners(completed = { success.set(it); completed.countDown() }) {
                    mutation.countDown()
                    app.daiyu.android.ui.RetainedConversationData(fixture.database,
                        app.daiyu.android.context.ContextService(fixture.application, fixture.database)).deleteAll()
                }
            }
            assertFalse(mutation.await(100, TimeUnit.MILLISECONDS))
            controlMain { assertTrue(controller.dataBusy) }
        } finally { release.countDown(); writer.join(10000) }
        assertFalse(writer.isAlive); assertNull(failure.get())
        assertTrue(completed.await(10, TimeUnit.SECONDS)); assertTrue(success.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertSealed(output.resources, output.owner)
        val fresh = existingOutput()
        assertNotSame(output.conversation.id, fresh.conversation.id)
        assertSame(output.resources, fresh.resources)
        assertReadable(fresh)
        fixture.database.draft(editor.text)
        val next = prepare()
        controlMain { controller.abandonGeneration(next) }
        assertSealed(output.resources, output.owner)
    }

    fun testFailedDeleteRemainsSealedAndImportCannotResurrectIt() {
        val output = existingOutput()
        assertReadable(output)
        val succeeded = AtomicBoolean(true)
        dataOperation {
            controller.mutateGenerationOwners(setOf(output.conversation.id.value), completed = { succeeded.set(it) }) {
                error("synthetic delete failure before transaction")
            }
        }
        assertFalse(succeeded.get())
        assertTrue(fixture.database.conversations().any { it.id == output.conversation.id })
        assertSealed(output.resources, output.owner)
        assertNull(loadOutputs(output.conversation.id))
        // Even successful no-op data work marked as import must not clear a deletion seal.
        dataOperation { controller.dataTask(importing = true) { "no-op append" } }
        assertSame(output.resources, field(controller, "generationResources"))
        assertSealed(output.resources, output.owner)
        assertNull(loadOutputs(output.conversation.id))
    }

    fun testDeletionBeforeFirstGenerationConfigDoesNotRequireSettingsAndCarriesSealForward() {
        val conversation = fixture.database.create("default")
        val success = AtomicBoolean()
        dataOperation {
            controller.mutateGenerationOwners(setOf(conversation.id.value), completed = { success.set(it) }) {
                fixture.database.delete(conversation.id)
            }
        }
        assertTrue(success.get())
        assertNull(field(controller, "generationResources"))
        val output = existingOutput() // the first real resource inherits the earlier deletion
        assertReadable(output)
        assertSealed(output.resources, GenerationAssetOwner(output.owner.accountScope,
            GenerationAttemptBinding(conversation.id.value, "old-assistant", "old-run", "old-call", RunGenerationTools.IMAGE_TOOL)))
    }

    fun testDeleteAllBeforeFirstConfigOnlySealsExistingConversations() {
        val deleted = fixture.database.create("default")
        val succeeded = AtomicBoolean()
        dataOperation {
            controller.mutateGenerationOwners(completed = { succeeded.set(it) }) {
                app.daiyu.android.ui.RetainedConversationData(fixture.database,
                    app.daiyu.android.context.ContextService(fixture.application, fixture.database)).deleteAll()
            }
        }
        assertTrue(succeeded.get())
        assertNull(field(controller, "generationResources"))
        val fresh = existingOutput()
        assertReadable(fresh)
        assertSealed(fresh.resources, GenerationAssetOwner(fresh.owner.accountScope,
            GenerationAttemptBinding(deleted.id.value, "old-assistant", "old-run", "old-call", RunGenerationTools.IMAGE_TOOL)))
    }

    fun testControllerCloseWaitsForAdmittedHistoryReadBeforeClosingDatabase() {
        val output = existingOutput()
        assertReadable(output)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closeDone = field(controller, "closeDone") as CountDownLatch
        val fence = output.resources.createFence(output.owner)
        val blocker = Thread {
            try { fence.admit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            catch (problem: Throwable) { failure.set(problem) }
        }
        blocker.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            controlMain {
                assertTrue(controller.loadGenerationOutputs(output.conversation.id) { readDone.countDown() })
                assertEquals(1, field(controller, "generationReaders"))
                controller.close()
            }
            assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS))
            assertTrue(fixture.database.readableDatabase.isOpen)
        } finally { release.countDown(); blocker.join(10000) }
        assertFalse(blocker.isAlive); assertNull(failure.get())
        assertTrue(readDone.await(10, TimeUnit.SECONDS))
        assertTrue(closeDone.await(10, TimeUnit.SECONDS))
        assertSame(output.resources, field(controller, "generationResources"))
    }

    private fun assertSealed(resources: ControllerGenerationResources, owner: GenerationAssetOwner) {
        val validated = AtomicBoolean()
        try {
            resources.createFence(owner) { validated.set(true); true }.admit { fail("sealed owner admitted") }
            fail("expected cancellation")
        } catch (failure: GenerationAssetException) {
            assertEquals(GenerationAssetFailure.CANCELLED, failure.failure)
        }
        assertFalse("seal must reject before owner validation", validated.get())
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}
