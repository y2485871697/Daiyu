package app.daiyu.android

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.TopBarBackdrop
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import app.daiyu.android.ui.components.topBarContainerColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import app.daiyu.android.ui.FeatureDetails
import app.daiyu.android.ui.FeatureControlKind
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
    private val entries get() = FeatureCatalog.groups.flatMap { it.entries }
    private fun tagged(window: AlertDialog, tag: String) = views(window.window!!.decorView).single { it.tag == tag }
    private fun action(window: AlertDialog, id: String) = tagged(window, "feature-control:$id:open") as Button
    private fun walkDetails(window: AlertDialog, verify: () -> Unit = {}) {
        entries.forEach { entry ->
            controlMain {
                assertTrue(slot(window, entry.id).performClick())
                assertTrue(window.isShowing)
                val body = tagged(window, "feature-detail:${entry.id}")
                val expected = FeatureDetails.sections(entry.id)
                val all = views(window.window!!.decorView)
                assertFalse(all.any { it.tag?.toString()?.startsWith("feature:") == true })
                assertEquals(expected.sumOf { it.controls.size }, all.count { it.tag?.toString()?.startsWith("feature-control:") == true })
                assertTrue(views(body).filterIsInstance<EditText>().isEmpty())
                expected.forEach { section ->
                    assertEquals(section.title, (tagged(window, "feature-section:${entry.id}:${section.id}") as TextView).text.toString())
                    section.controls.forEach { control ->
                        val row = tagged(window, "feature-control:${entry.id}:${control.id}") as TextView
                        assertEquals(control.title, row.text.toString())
                        assertEquals(control.kind == FeatureControlKind.ACTION, row is Button)
                        if (control.kind != FeatureControlKind.ACTION) {
                            val parent = row.parent as ViewGroup
                            val label = parent.getChildAt(parent.indexOfChild(row) + 1) as TextView
                            assertEquals(control.kind.label, label.text.toString())
                        }
                        if (control.route == null) {
                            assertFalse(row.hasOnClickListeners()); assertFalse(row.performClick())
                        } else assertTrue(row.hasOnClickListeners())
                    }
                }
                assertFalse(all.filterIsInstance<TextView>().any { text ->
                    listOf("尚未接入", "未实现", "占位", "开发中").any { it in text.text.toString() }
                })
                verify()
            }
            instrumentation.waitForIdleSync()
            controlMain {
                assertTrue(window.isShowing); verify()
                window.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                assertNotNull(tagged(window, "feature-overview"))
                assertEquals(entries.size, views(window.window!!.decorView).count { it.tag?.toString()?.startsWith("feature:") == true })
            }
        }
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
        walkDetails(window) {
            assertEquals("Gemini UI 草稿", input().text.toString())
            assertNull(fixture.controller.active)
        }
        controlMain { assertTrue(window.isShowing) }
        assertEquals(0, fixture.opens.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.loadForEditing().settings)
    }
    fun testConnectedModelEntryOpensTheExistingSettingsWithoutSaving() {
        controlMain { input().setText("原草稿") }
        val settings = fixture.settings.loadForEditing().settings
        val window = openHub()
        controlMain { slot(window, "P02").performClick(); assertTrue(window.isShowing); action(window, "P02").performClick() }
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
        lateinit var oldAction: Button
        controlMain { oldButton = slot(window, "R03"); oldButton.performClick(); oldAction = action(window, "R03") }
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
            assertFalse(window.isShowing); oldButton.performClick(); oldAction.performClick()
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
        val session = fixture.session(holdSeconds = 120)
        controlMain { input().setText("运行正文"); mainButton("发送").performClick(); input().setText("保留追加草稿") }
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        val unchanged: () -> Unit = {
            assertEquals(owner.id, fixture.controller.active!!.id)
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            assertEquals(1L, session.cancelled.count)
            assertEquals("保留追加草稿", input().text.toString())
            assertEquals(1, fixture.opens.get())
        }
        try {
            val window = openHub()
            walkDetails(window, unchanged)
            controlMain { window.dismiss() }
            listOf("P11", "P12", "P13", "P23").forEach { id ->
                val nav = openHub()
                controlMain { slot(nav, id).performClick(); unchanged(); assertTrue(nav.isShowing); action(nav, id).performClick() }
                instrumentation.waitForIdleSync()
                controlMain { assertFalse(nav.isShowing); unchanged() }
            }
        } finally { session.release.countDown() }
        awaitControl("original run finishes") { fixture.controller.active == null }
        assertEquals(1, fixture.opens.get())
    }
    fun testButtonsFromPreviousDetailPageAreInert() {
        val window = openHub()
        controlMain {
            val entry = slot(window, "R03"); entry.performClick()
            val oldOpen = action(window, "R03")
            window.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            entry.performClick(); oldOpen.performClick()
            assertTrue(window.isShowing); assertNotNull(tagged(window, "feature-overview"))
            slot(window, "P01").performClick(); oldOpen.performClick()
            assertTrue(window.isShowing); assertNotNull(tagged(window, "feature-detail:P01"))
            window.dismiss()
        }
        assertEquals(0, fixture.opens.get())
    }


    private class SemanticProbe {
        var visited = 0
        var refreshFailed = 0
        var visible = 0
        var hiddenMatch = false
        override fun toString() = "nodes=$visited,refreshFailed=$refreshFailed,visible=$visible,hiddenMatch=$hiddenMatch"
    }
    private fun containsVisibleSemanticText(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
        probe: SemanticProbe,
        deadline: Long,
    ): Boolean {
        try {
            if (android.os.SystemClock.uptimeMillis() >= deadline) return false
            probe.visited++
            if (!node.refresh()) { probe.refreshFailed++; return false }
            if (node.isVisibleToUser) probe.visible++
            if (node.text?.toString() == text) {
                if (node.isVisibleToUser) return true
                probe.hiddenMatch = true
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (containsVisibleSemanticText(child, text, probe, deadline)) return true
            }
            return false
        } finally {
            node.recycle()
        }
    }
    private fun awaitSemanticText(text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var last = "root=null"
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val probe = SemanticProbe()
            last = if (root == null) "root=null" else
                "package=${root.packageName},class=${root.className},window=${root.windowId}"
            if (root != null && containsVisibleSemanticText(root, text, probe, deadline)) return
            last += ";$probe"
            android.os.SystemClock.sleep(50)
        }
        var viewState = ""
        controlMain {
            val decor = checkNotNull(activity).window.decorView
            viewState = "attached=${decor.isAttachedToWindow},focus=${decor.hasWindowFocus()},inputs=${mainViews().filterIsInstance<EditText>().size}"
        }
        fail("Original Compose text missing: $text; $last; $viewState")
    }
    fun testOriginalManageAndUsagePagesOpenAndBackRestoresRealChatDraft() {
        controlMain { input().setText("原聊天草稿") }
        listOf("P22" to R.string.history_page_title, "P21" to R.string.stats_page_title).forEach { (id, title) ->
            val window = openHub()
            controlMain {
                assertTrue("detail click $id", slot(window, id).performClick())
                assertTrue("open click $id", action(window, id).performClick())
                assertFalse("hub dismissed for $id", window.isShowing)
            }
            instrumentation.waitForIdleSync()
            awaitSemanticText(instrumentation.targetContext.getString(title))
            controlMain { assertFalse(window.isShowing); checkNotNull(activity).onBackPressedDispatcher.onBackPressed() }
            instrumentation.waitForIdleSync()
            awaitControl("chat input reattached after $id") {
                mainViews().filterIsInstance<EditText>().size == 1
            }
            controlMain { assertEquals("原聊天草稿", input().text.toString()) }
        }
        assertEquals(0, fixture.opens.get())
        assertTopBarBlurCompatibility()
    }
    private fun assertTopBarBlurCompatibility() {
        AppearanceTopBarBlurStyle.entries.forEach { style ->
            listOf(true, false).forEach { enabled ->
                val drawn = CountDownLatch(1)
                val observation = AtomicReference<Triple<Boolean, Boolean, Boolean>>()
                controlMain {
                    checkNotNull(activity).setContent {
                        AgentAppTheme(
                            AppearanceSettings(blurEnabled = enabled, topBarBlurStyle = style),
                            applyInterfaceScale = true,
                        ) {
                            val backdrop = rememberTopBarBackdrop()
                            val capture = Modifier.captureForTopBar(backdrop)
                            val solid = topBarContainerColor(backdrop) == MiuixTheme.colorScheme.surface
                            SideEffect { observation.set(Triple(backdrop != null, solid, capture === Modifier)) }
                            Box(Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                drawn.countDown()
                            }) {
                                Box(Modifier.fillMaxSize().then(capture))
                                TopBarBackdrop(backdrop) {
                                    Box(Modifier.fillMaxWidth().height(64.dp))
                                }
                            }
                        }
                    }
                }
                assertTrue("Top bar did not draw: $style enabled=$enabled", drawn.await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val expected = enabled && android.os.Build.VERSION.SDK_INT >= 33
                val actual = checkNotNull(observation.get())
                assertEquals("Backdrop: $style enabled=$enabled", expected, actual.first)
                assertEquals("Solid surface: $style enabled=$enabled", !expected, actual.second)
                assertEquals("Capture: $style enabled=$enabled", !expected, actual.third)
            }
        }
    }
    fun testOriginalSidebarOpensOverChatAndBackDoesNotLoseOrSendDraft() {
        controlMain { input().setText("抽屉草稿") }
        val window = openHub()
        controlMain { slot(window, "P07").performClick(); action(window, "P07").performClick() }
        instrumentation.waitForIdleSync()
        awaitSemanticText(instrumentation.targetContext.getString(R.string.app_name))
        controlMain {
            assertFalse(window.isShowing)
            val sidebar = MainActivity::class.java.getDeclaredField("sidebarVisible\$delegate")
                .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>
            assertEquals(true, sidebar.value)
            checkNotNull(activity).onBackPressedDispatcher.onBackPressed()
        }
        instrumentation.waitForIdleSync()
        controlMain { assertEquals("抽屉草稿", input().text.toString()); assertTrue(input().isEnabled) }
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
