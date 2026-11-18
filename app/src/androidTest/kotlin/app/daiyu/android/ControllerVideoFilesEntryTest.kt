package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.VideoAttachmentOwner
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
            val manifest = fixture.histories.single().first().blocks.filterIsInstance<MessageBlock.Text>().last().text
            assertTrue(manifest.contains("Linux 路径:"))
            assertTrue(manifest.contains("/workspace/.daiyu-video-inputs/"))
            assertFalse(manifest.contains("data:video/"))
            val hostLine = manifest.lineSequence().single { it.startsWith("Android 路径:") }.substringAfter(": ")
            val hostPath = org.json.JSONArray("[$hostLine]").getString(0)
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
            assertTrue(fixture.histories.last().first().blocks.filterIsInstance<MessageBlock.Text>()
                .any { it.text.contains(hostPath) })
            assertEquals("high", org.json.JSONObject(frozen.last().requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
            assertTrue(clip.readBytes().contentEquals(File(hostPath).readBytes()))
            follow.release.countDown()
            awaitControl("continuation complete") { fixture.controller.active == null }
        } finally {
            controlMain { ui?.let(fixture.controller::detachVideoUi) }
            fixture.close()
        }
    }
}
