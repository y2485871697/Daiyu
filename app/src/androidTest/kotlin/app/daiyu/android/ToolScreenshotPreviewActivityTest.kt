package app.daiyu.android

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.SQLiteRuntimeStore
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Real committed-receipt/controller/Compose path; synthetic PNG, no screen capture or model request. */
class ToolScreenshotPreviewActivityTest : WiredActivityTestCase() {
    private lateinit var owner: WorkStepOwnerUi
    private val callId = "preview-call"
    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext,
        beforeController = { database, _ ->
            val conversation = database.create("default")
            val user = Message(MessageId("preview-user"), conversation.id, RunId("preview-run"),
                Message.USER_ROLE, listOf(MessageBlock.Text("user", "read")), RunStatus.COMPLETED)
            val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val png = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
            bitmap.recycle()
            val record = ToolRecord(ToolInvocation(ToolCallId(callId), "screen", """{"operation":"screenshot"}"""),
                ToolDisposition.SUCCEEDED, JSONObject().put("capture_id", "fixture")
                    .put("width", 4).put("height", 3).put("mime_type", "image/png")
                    .put("data_base64", Base64.encodeToString(png, Base64.NO_WRAP)).toString())
            val initial = user.copy(id = MessageId("preview-assistant"), role = Message.ASSISTANT_ROLE,
                status = RunStatus.RUNNING, blocks = listOf(MessageBlock.Tool(record.invocation, record.result)))
            owner = WorkStepOwnerUi(conversation.id.value, initial.runId.value, initial.id.value)
            SQLiteRuntimeStore.acquire(database).use { runtime ->
                runtime.recoverPending()
                database.send(user, initial)
                runtime.commitCheckpoint(RunCheckpoint(initial.copy(status = RunStatus.COMPLETED), listOf(user),
                    ExecutionPhase.TERMINAL, listOf(record), owner = "preview-owner", revision = 1))
            }
        })

    private fun loaded(): Bitmap {
        val done = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>()
        mainThread { assertTrue(fixture.controller.loadToolScreenshot(owner, callId) { image, cleared ->
            assertFalse(cleared); result.set(image); done.countDown()
        }) }
        assertTrue("preview read timed out", done.await(10, TimeUnit.SECONDS))
        return checkNotNull(result.get())
    }
    private fun executor() = ChatController::class.java.getDeclaredField("screenshotExecutor")
        .apply { isAccessible = true }.get(fixture.controller) as ExecutorService

    fun testReentryAndTwoReadersDisplayWarmPreviewWhileDiskWorkerIsBlocked() {
        val image = loaded()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        executor().execute {
            entered.countDown()
            try { check(release.await(20, TimeUnit.SECONDS)) } finally { exited.countDown() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val visible = mutableStateOf(true)
            val step = WorkStepUi(callId, "查看屏幕", "", WorkStepState.OK, owner, hasScreenScreenshot = true)
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                if (visible.value) Column {
                    ToolScreenshotContent(fixture.controller, step)
                    ToolScreenshotContent(fixture.controller, step)
                }
            } } }
            repeat(3) {
                ui.onAllNodesWithTag("tool-screenshot:$callId").assertCountEquals(2)
                ui.onNodeWithText("正在加载…").assertDoesNotExist()
                mainThread {
                    assertSame(image, fixture.controller.cachedToolScreenshot(owner, callId)!!.image)
                    visible.value = false
                }
                ui.onAllNodesWithTag("tool-screenshot:$callId").assertCountEquals(0)
                mainThread { visible.value = true }
            }
            ui.onAllNodesWithTag("tool-screenshot:$callId").assertCountEquals(2)
            assertFalse(image.isRecycled)
            assertEquals("worker must remain blocked throughout reentry assertions", 1L, exited.count)
        } finally { release.countDown() }
    }

    fun testWarmLoaderCompletesSynchronouslyAndWrongOwnerCannotBorrowPreview() {
        val image = loaded()
        mainThread {
            var completed = false
            assertTrue(fixture.controller.loadToolScreenshot(owner, callId) { bitmap, cleared ->
                assertSame(image, bitmap); assertFalse(cleared); completed = true
            })
            assertTrue("warm loader must not queue IO", completed)
            for (other in listOf(owner.copy(conversationId = "other"), owner.copy(runId = "other"),
                owner.copy(messageId = "other"))) assertNull(fixture.controller.cachedToolScreenshot(other, callId))
            assertNull(fixture.controller.cachedToolScreenshot(owner, "other"))
        }
    }

    fun testQueuedReadCannotRepopulateCacheAfterControllerCloses() {
        val cache = ChatController::class.java.getDeclaredField("toolScreenshotPreviews")
            .apply { isAccessible = true }.get(fixture.controller) as ToolScreenshotPreviewCache
        val key = ToolScreenshotPreviewCache.Key(owner, callId,
            fixture.controller.dataRevision, fixture.controller.screenScreenshotRevision)
        mainThread { cache.clear() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>()
        executor().execute { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            mainThread {
                assertTrue(fixture.controller.loadToolScreenshot(owner, callId) { bitmap, cleared ->
                    assertFalse(cleared); result.set(bitmap); done.countDown()
                })
                fixture.controller.close()
            }
        } finally { release.countDown() }
        assertTrue("late read completion timed out", done.await(10, TimeUnit.SECONDS))
        assertNull(result.get())
        assertNull(cache[key])
    }

    fun testClosingControllerDropsCacheAndRejectsNewLoadsWithoutRecyclingReaders() {
        val image = loaded()
        mainThread {
            fixture.controller.close()
            assertNull(fixture.controller.cachedToolScreenshot(owner, callId))
            assertFalse(fixture.controller.loadToolScreenshot(owner, callId) { _, _ -> fail("closed callback") })
        }
        assertFalse(image.isRecycled)
    }
}
