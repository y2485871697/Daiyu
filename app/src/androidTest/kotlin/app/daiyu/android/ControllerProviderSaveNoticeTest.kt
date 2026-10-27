package app.daiyu.android

import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerProviderSaveNoticeTest : InstrumentationTestCase() {
    fun testSuccessfulProviderSaveHasNoNoticeAndStillCommits() = save(revoked = false)
    fun testRejectedProviderSaveKeepsFailureNoticeAndOldSettings() = save(revoked = true)

    fun testOtherDataTaskStillPublishesItsSuccessNotice() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val completed = CountDownLatch(1)
            val result = AtomicReference<Boolean>()
            controlMain {
                fixture.controller.dataTask(completed = {
                    result.set(it)
                    completed.countDown()
                }, work = { "other task completed" })
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(true, result.get())
            controlMain {
                assertFalse(fixture.controller.dataBusy)
                assertEquals("other task completed", fixture.controller.consumeDataNotice())
            }
        } finally { fixture.close() }
    }

    private fun save(revoked: Boolean) {
        val fixture = RunControlFixture(instrumentation.targetContext)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val before = fixture.settings.load()
            val editor = fixture.controller.providers.activeEditor()
            val editing = fixture.controller.providers.withEditor(editor) { it.loadForEditing() }
            val value = before.copy(model = "saved-provider-model")
            val owner = ModelSettingsSaveOwner()
            if (revoked) owner.revoke()
            val completed = CountDownLatch(1)
            val result = AtomicReference<Boolean>()
            controlMain {
                fixture.controller.saveProviderSettings(editor, value, editing,
                    protocolChangeAuthorized = false, requestOptionsReplacementAuthorized = false, owner = owner) {
                    result.set(it)
                    completed.countDown()
                }
                assertTrue(fixture.controller.dataBusy)
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(!revoked, result.get())
            controlMain {
                assertFalse(fixture.controller.dataBusy)
                if (revoked) assertEquals("提供商或余额保存未完整确认；请重新打开详情核对", fixture.controller.consumeDataNotice())
                else assertNull(fixture.controller.consumeDataNotice())
            }
            if (revoked) assertEquals(before, fixture.settings.load())
            else assertEquals(value, fixture.settings.load())
            assertEquals(0, fixture.opens.get())
        } finally { fixture.close() }
    }
}
