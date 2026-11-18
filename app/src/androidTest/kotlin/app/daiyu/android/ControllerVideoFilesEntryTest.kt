package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.VideoAttachmentOwner
import org.json.JSONTokener
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real controller, importer, acceptance and ordinary model boundary; no paid provider or phone GUI. */
@Suppress("DEPRECATION")
class ControllerVideoFilesEntryTest : InstrumentationTestCase() {
    fun testDraftVideoAndHistoricalContinuationReachCurrentThinkingModelWithLinuxFilePaths() {
        val frozen = java.util.concurrent.CopyOnWriteArrayList<app.daiyu.android.model.ProviderSnapshot>()
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { provider, _, _ -> frozen += provider })
        val clip = File(fixture.application.filesDir, "fixture-video.mp4")
        var ui: ControllerVideoUi? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            fixture.controller.reasoningPreference.set(null, ReasoningEffort.HIGH)
            encodeOfflineBackupMp4(clip)
            val owner = VideoAttachmentOwner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            val video = fixture.controller.videoAttachments.store.import(owner, clip.inputStream())
            val text = "请用当前Linux工具读取视频"
            fixture.database.draft(text)
            val request = VideoDraftRequest(null, owner, 7, text, listOf(video))
            val editor = ControllerVideoEditor(null, 1, 1, text, true)
            val receipt = AtomicReference<VideoDraftReceipt?>()
            ui = object : ControllerVideoUi { override fun editor() = editor }
            val session = fixture.session(holdSeconds = 60)
            controlMain {
                fixture.controller.attachVideoUi(checkNotNull(ui))
                assertTrue(fixture.controller.sendVideos(null, request, editor) { result, _ -> receipt.set(result) })
            }
            assertTrue(session.entered.await(20, TimeUnit.SECONDS))
            awaitControl("video receipt") { receipt.get() != null }
            assertEquals(VideoDraftOutcome.ACCEPTED, receipt.get()?.outcome)
            assertEquals(1, fixture.opens.get())
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(owner.conversationId))
            fun manifestPath(text: String, prefix: String): String {
                val encoded = text.lineSequence().single { it.startsWith(prefix) }.removePrefix(prefix)
                return JSONTokener(encoded).nextValue() as String
            }
            val manifest = fixture.histories.single().first().blocks.filterIsInstance<MessageBlock.Text>().last().text
            val linuxPath = manifestPath(manifest, "Linux 路径: ")
            assertTrue(linuxPath.startsWith("/workspace/.daiyu-video-inputs/"))
            assertFalse(manifest.contains("data:video/"))
            val hostPath = manifestPath(manifest, "Android 路径: ")
            assertTrue(clip.readBytes().contentEquals(File(hostPath).readBytes()))
            assertEquals("high", org.json.JSONObject(frozen.single().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            val conversation = fixture.database.conversations().single()
            val stored = ControllerVideoHistory(fixture.database).capture(conversation.id)
            assertEquals(1, stored.inputs.size)
            assertFalse(stored.rows.first().blocks.filterIsInstance<MessageBlock.Text>().any { it.text.contains("Linux 路径:") })
            controlMain { fixture.controller.acknowledgeVideoSend(request) }
            session.release.countDown()
            awaitControl("video complete") { fixture.controller.active == null }
            fixture.controller.videoAttachments.store.remove(owner, video)
            val follow = fixture.session(holdSeconds = 60)
            fixture.database.draft(conversation.id, "继续读取原视频")
            assertNotNull(fixture.controller.send(conversation, "继续读取原视频"))
            assertTrue(follow.entered.await(20, TimeUnit.SECONDS))
            assertEquals(2, fixture.opens.get())
            val followManifest = fixture.histories.last().first().blocks.filterIsInstance<MessageBlock.Text>().last().text
            assertEquals(linuxPath, manifestPath(followManifest, "Linux 路径: "))
            assertEquals(hostPath, manifestPath(followManifest, "Android 路径: "))
            assertEquals("high", org.json.JSONObject(frozen.last().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertTrue(clip.readBytes().contentEquals(File(hostPath).readBytes()))
            follow.release.countDown()
            awaitControl("continuation complete") { fixture.controller.active == null }
        } finally {
            controlMain { ui?.let(fixture.controller::detachVideoUi) }
            fixture.close()
        }
    }
    fun testVideoThumbnailResolvesLogicalOwnerThroughPhysicalHistorySidecar() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val clip = File(fixture.application.filesDir, "history-thumbnail-fixture.mp4")
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            encodeOfflineBackupMp4(clip)
            val conversation = fixture.database.create("default")
            val run = RunId(UUID.randomUUID().toString())
            val user = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("initial", "initial question")), RunStatus.COMPLETED)
            val logicalId = MessageId(UUID.randomUUID().toString())
            val assistantId = MessageId(UUID.randomUUID().toString())
            val assistant = user.copy(id = assistantId, role = Message.ASSISTANT_ROLE,
                blocks = emptyList(), status = RunStatus.RUNNING, historySegments = listOf(
                    MessageHistorySegment(assistantId, Message.ASSISTANT_ROLE, emptyList()),
                    MessageHistorySegment(logicalId, Message.USER_ROLE, listOf(MessageBlock.Text("caption", "video")))))
            val draftOwner = VideoAttachmentOwner(conversation.id.value, UUID.randomUUID().toString())
            val draft = fixture.controller.videoAttachments.store.import(draftOwner, clip.inputStream())
            val owner = app.daiyu.android.media.video.MessageVideoOwner(conversation.id.value, logicalId.value)
            val reference = fixture.controller.videoAttachments.store.prepare(owner, draft)
            fixture.database.send(user, assistant)
            app.daiyu.android.context.DatabaseTransactions { fixture.database.writableDatabase }.run { sql ->
                sql.execSQL("DELETE FROM message_video_witnesses WHERE message_id=?", arrayOf(assistant.id.value))
                app.daiyu.android.storage.SQLiteMessageVideoStore.insert(sql, assistant,
                    listOf(app.daiyu.android.model.videos.MessageVideoInput(owner, listOf(reference))))
            }
            fixture.database.readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id=?", arrayOf(logicalId.value)).use {
                assertFalse(it.moveToFirst())
            }
            val video = app.daiyu.android.ui.screens.chat.conversation.MessageVideoUi(owner, reference)
            val bitmap = fixture.controller.messageVideoThumbnail(conversation.id, video, 1024) { false }
            try {
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                assertEquals(reference.width.toFloat() / reference.height, bitmap.width.toFloat() / bitmap.height, 0.02f)
            } finally { bitmap.recycle() }
            assertEquals(0, fixture.opens.get())
        } finally { fixture.close() }
    }

    fun testVideoPreparationUsesProductionTokensForRetainedHistory() {
        // Harness surface estimation replaces the legacy byte-count send gate.
        verifyRetainedHistoryBudget(windowTokens = 22000)
    }

    fun testVideoRetainedHistoryWithFullToolCatalogDoesNotPauseOnLocalBudget() {
        verifyRetainedHistoryBudget(windowTokens = 20000)
    }

    private fun verifyRetainedHistoryBudget(windowTokens: Long) {
        val fixture = RunControlFixture(instrumentation.targetContext, onSession = { _, _, _ -> })
        val clip = File(fixture.application.filesDir, "budget-fixture-video.mp4")
        var ui: ControllerVideoUi? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            app.daiyu.android.context.ProductionCompressionSettingsStore(fixture.application).save(
                app.daiyu.android.context.ProductionCompressionSettings(contextWindowTokens = windowTokens))
            val conversation = fixture.database.create("default")
            val run = RunId(UUID.randomUUID().toString())
            val oldUser = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, "user",
                listOf(MessageBlock.Text("history", "历史记录".repeat(1600))), RunStatus.COMPLETED)
            val oldAssistant = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, "assistant",
                emptyList(), RunStatus.RUNNING)
            fixture.database.send(oldUser, oldAssistant)
            fixture.database.save(oldAssistant.copy(status = RunStatus.COMPLETED))
            assertTrue(app.daiyu.android.domain.context.estimateTokens(listOf(oldUser)) > windowTokens - 4096)
            fixture.controller.reasoningPreference.set(conversation.id.value, ReasoningEffort.HIGH)
            encodeOfflineBackupMp4(clip)
            val owner = VideoAttachmentOwner(conversation.id.value, UUID.randomUUID().toString())
            val video = fixture.controller.videoAttachments.store.import(owner, clip.inputStream())
            val text = "能看到视频吗"
            fixture.database.draft(conversation.id, text)
            val request = VideoDraftRequest(conversation.id.value, owner, 1, text, listOf(video))
            val editor = ControllerVideoEditor(conversation.id.value, 1, 1, text, true)
            val receipt = AtomicReference<VideoDraftReceipt?>()
            ui = object : ControllerVideoUi { override fun editor() = editor }
            val session = fixture.session(holdSeconds = 60)
            controlMain {
                fixture.controller.attachVideoUi(checkNotNull(ui))
                assertTrue(fixture.controller.sendVideos(conversation, request, editor) { result, _ -> receipt.set(result) })
            }
            awaitControl("video receipt") { receipt.get() != null }
            assertEquals(fixture.controller.problem, VideoDraftOutcome.ACCEPTED, receipt.get()?.outcome)
            val acceptedAssistant = fixture.database.messages(conversation.id).map { it.second }
                .last { it.role == Message.ASSISTANT_ROLE }
            fun checkpoint() = app.daiyu.android.storage.SQLiteRuntimeStore.readCheckpointSnapshot(
                fixture.database.readableDatabase, acceptedAssistant.id.value)
                if (!session.entered.await(20, TimeUnit.SECONDS)) {
                    val storedAssistant = fixture.database.messages(conversation.id).map { it.second }
                        .lastOrNull { it.role == Message.ASSISTANT_ROLE }
                    val checkpoint = storedAssistant?.let {
                        app.daiyu.android.storage.SQLiteRuntimeStore.readCheckpointSnapshot(
                            fixture.database.readableDatabase, it.id.value)
                    }
                    val state = AtomicReference<String>()
                    controlMain { state.set("active=${fixture.controller.active?.status}; problem=${fixture.controller.problem}") }
                    fail("video model did not enter: opens=${fixture.opens.get()}; ${state.get()}; " +
                        "storedStatus=${storedAssistant?.status}; storedError=${storedAssistant?.error}; " +
                        "phase=${checkpoint?.phase}; capacity=${checkpoint?.context?.capacity}; " +
                        "checkpointError=${checkpoint?.message?.error}")
                }
                assertEquals(1, fixture.opens.get())
                val projected = fixture.histories.single()
                assertEquals(oldUser, projected.first())
                assertTrue(projected.last().blocks.filterIsInstance<MessageBlock.Text>().last().text.contains("Linux 路径:"))
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(owner.conversationId))
            val stored = ControllerVideoHistory(fixture.database).capture(conversation.id)
            assertEquals(4, stored.rows.size)
            assertFalse(stored.rows.any { row -> row.blocks.filterIsInstance<MessageBlock.Text>().any {
                it.text.contains("Linux 路径:") } })
            controlMain { fixture.controller.acknowledgeVideoSend(request) }
            assertNull(checkpoint()?.context?.capacity)
            session.release.countDown()
            awaitControl("video complete") { fixture.controller.active == null }
        } finally {
            controlMain { ui?.let(fixture.controller::detachVideoUi) }
            fixture.close()
        }
    }

}
