package app.daiyu.android

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import app.daiyu.android.ui.FeatureCatalog
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class FeatureHubActivityTest : android.test.InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private lateinit var original: ChatController
    private var activity: MainActivity? = null
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun mainViews() = views(checkNotNull(activity).window.decorView)
    private fun input() = mainViews().filterIsInstance<EditText>().single()
    private fun mainButton(label: String) = mainViews().filterIsInstance<Button>().single { it.text.toString() == label }
    private fun dialog(): AlertDialog {
        val host = MainActivity::class.java.getDeclaredField("featureHubDialog").apply { isAccessible = true }.get(activity)
        return host.javaClass.getDeclaredField("window").apply { isAccessible = true }.get(host) as AlertDialog
    }
    private fun slot(window: AlertDialog, id: String) = views(window.window!!.decorView)
        .filterIsInstance<Button>().single { it.tag == "feature:$id" }
    private fun openHub(): AlertDialog {
        controlMain { mainButton("功能总览").performClick() }
        instrumentation.waitForIdleSync()
        lateinit var window: AlertDialog
        controlMain { window = dialog() }
        return window
    }
    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext)
        awaitControl("controller ready") { fixture.controller.ready }
        controlMain {
            val app = instrumentation.targetContext.applicationContext as ChatApplication
            original = app.controller; app.controller = fixture.controller
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
    }
    override fun tearDown() {
        try {
            controlMain { activity?.finish() }; instrumentation.waitForIdleSync()
            controlMain { (instrumentation.targetContext.applicationContext as ChatApplication).controller = original }
            fixture.close()
        } finally { super.tearDown() }
    }
    fun testCatalogSlotsAppearOutsideExistingChatTreeWithoutStateLabels() {
        val window = openHub()
        controlMain {
            assertEquals(1, mainViews().filterIsInstance<EditText>().size)
            assertEquals(1, mainViews().filterIsInstance<ScrollView>().size)
            assertEquals(FeatureCatalog.groups.sumOf { it.entries.size },
                views(window.window!!.decorView).filterIsInstance<Button>().count { it.tag?.toString()?.startsWith("feature:") == true })
            FeatureCatalog.groups.forEach { group -> group.entries.forEach { entry ->
                assertEquals(entry.title, slot(window, entry.id).text.toString())
                assertTrue(slot(window, entry.id).isEnabled)
            } }
            assertFalse(views(window.window!!.decorView).filterIsInstance<TextView>().any {
                listOf("尚未接入", "未实现", "占位", "开发中").any { marker -> marker in it.text.toString() }
            })
            window.dismiss()
        }
    }
    fun testUnwiredButtonsDoNothingAndKeepDraftDatabaseSettingsAndWindow() {
        controlMain { input().setText("Gemini UI 草稿") }
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        val window = openHub()
        controlMain {
            FeatureCatalog.groups.flatMap { it.entries }.filter { it.route == null }.forEach { entry ->
                val button = slot(window, entry.id)
                assertFalse(entry.id, button.hasOnClickListeners())
                assertFalse(entry.id, button.performClick())
                assertTrue(window.isShowing)
            }
        }
        instrumentation.waitForIdleSync()
        controlMain {
            assertTrue(window.isShowing)
            assertEquals("Gemini UI 草稿", input().text.toString())
            assertNull(fixture.controller.active)
        }
        assertEquals(0, fixture.opens.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.loadForEditing().settings)
    }
    fun testConnectedModelEntryOpensTheExistingSettingsWithoutSaving() {
        controlMain { input().setText("原草稿") }
        val settings = fixture.settings.loadForEditing().settings
        val window = openHub()
        controlMain { slot(window, "P02").performClick() }
        instrumentation.waitForIdleSync()
        controlMain {
            assertFalse(window.isShowing)
            val settingsWindow = MainActivity::class.java.getDeclaredField("settingsDialog")
                .apply { isAccessible = true }.get(activity) as AlertDialog
            assertTrue(settingsWindow.isShowing)
            assertEquals(5, views(settingsWindow.window!!.decorView).filterIsInstance<EditText>().size)
            settingsWindow.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals("原草稿", input().text.toString())
        }
        assertEquals(0, fixture.opens.get()); assertEquals(settings, fixture.settings.loadForEditing().settings)
    }
    fun testRecreationClosesHubAndStaleButtonsCannotOpenTheNewActivity() {
        controlMain { input().setText("重建保留草稿") }
        val oldActivity = checkNotNull(activity)
        val window = openHub()
        lateinit var oldButton: Button
        controlMain { oldButton = slot(window, "R03") }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            controlMain { checkNotNull(activity).recreate() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? MainActivity
            assertNotNull(replacement)
            assertNotSame(oldActivity, replacement)
            activity = checkNotNull(replacement)
            instrumentation.waitForIdleSync()
        } finally { instrumentation.removeMonitor(monitor) }
        controlMain {
            assertFalse(window.isShowing); oldButton.performClick()
        }
        instrumentation.waitForIdleSync()
        controlMain {
            assertNull(MainActivity::class.java.getDeclaredField("settingsDialog").apply { isAccessible = true }.get(oldActivity))
            assertNull(MainActivity::class.java.getDeclaredField("settingsDialog").apply { isAccessible = true }.get(activity))
            assertEquals("重建保留草稿", input().text.toString())
        }
        val fresh = openHub(); controlMain { assertTrue(fresh.isShowing); fresh.dismiss() }
        assertEquals(0, fixture.opens.get())
    }
    fun testChatControlNavigationDoesNotSendPauseOrStopAnActiveRun() {
        val session = fixture.session()
        controlMain { input().setText("运行正文"); mainButton("发送").performClick(); input().setText("保留追加草稿") }
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        val window = openHub()
        controlMain {
            FeatureCatalog.groups.flatMap { it.entries }.filter { it.route == null }.forEach { entry ->
                assertFalse(entry.id, slot(window, entry.id).performClick())
            }
        }
        instrumentation.waitForIdleSync()
        controlMain {
            assertTrue(window.isShowing)
            assertEquals(owner.id, fixture.controller.active!!.id)
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            assertEquals(1L, session.cancelled.count)
            assertEquals("保留追加草稿", input().text.toString())
            slot(window, "P13").performClick()
        }
        instrumentation.waitForIdleSync()
        controlMain {
            assertFalse(window.isShowing); assertEquals(owner.id, fixture.controller.active!!.id)
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            assertEquals(1L, session.cancelled.count); assertEquals("保留追加草稿", input().text.toString())
        }
        assertEquals(1, fixture.opens.get())
        session.release.countDown(); awaitControl("original run finishes") { fixture.controller.active == null }
    }
}
