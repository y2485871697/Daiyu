package app.daiyu.android

import android.content.Intent
import android.test.InstrumentationTestCase
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keeps the existing JUnit3 fixture lifecycle, with Compose's root registry around the whole test.
 * AndroidJUnitRunner runs both the legacy suite and this rule-backed adapter.
 */
@Suppress("DEPRECATION")
abstract class ComposeActivityTestCase : InstrumentationTestCase() {
    protected val ui = createEmptyComposeRule()
    private fun runLegacy() = super.runBare()
    override fun runBare() {
        ui.apply(object : Statement() { override fun evaluate() { runLegacy() } },
            Description.createTestDescription(javaClass, name)).evaluate()
    }
    protected fun mainThread(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        instrumentation.waitForIdleSync()
    }
    protected fun node(tag: String) = ui.onNodeWithTag(tag)
    protected fun reveal(tag: String, page: String = "模型设置"): SemanticsNodeInteraction {
        node("page:$page").performScrollToNode(hasTestTag(tag))
        return node(tag)
    }
    protected fun editModel(label: String, text: String) { reveal("settings-field:$label").performTextReplacement(text) }
    protected fun modelField(label: String) = reveal("settings-field:$label")
    protected fun saveModel() { reveal("model-save").performClick() }
    protected fun openModels() { node("chat-model").performClick() }
    protected fun menu(label: String) {
        ui.onNodeWithContentDescription("更多").performClick()
        ui.onNodeWithText(label).performClick()
    }
    protected fun setDraft(text: String) { node("chat-draft").performTextReplacement(text) }
    protected fun assertDraft(text: String) { node("chat-draft").assertTextEquals(text) }
    protected fun setSelection(start: Int, end: Int) {
        node("chat-draft").performSemanticsAction(SemanticsActions.SetSelection) { it(start, end, false) }
    }
    protected fun assertSelection(start: Int, end: Int) {
        val range = node("chat-draft").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(start, range.start); assertEquals(end, range.end)
    }
    protected fun retainedClick(tag: String): () -> Unit {
        val action = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        return { action(); Unit }
    }
    protected fun backToChat(activity: MainActivity) {
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
    }
    protected fun recreateActivity(activity: MainActivity): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            mainThread { activity.recreate() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? MainActivity
            assertNotNull("framework recreation must resume a new Activity", replacement)
            ui.waitForIdle()
            assertTrue(activity.isDestroyed)
            return checkNotNull(replacement)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    protected fun startMain(intent: Intent = Intent(instrumentation.targetContext, MainActivity::class.java)): MainActivity {
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ui.waitForIdle()
        return activity
    }
}

/** Shared real-controller fixture; tests still assert database bytes and model-call counts. */
@Suppress("DEPRECATION")
abstract class WiredActivityTestCase : ComposeActivityTestCase() {
    internal lateinit var fixture: RunControlFixture
    private lateinit var original: ChatController
    protected lateinit var activity: MainActivity
    override fun setUp() {
        super.setUp()
        fixture = RunControlFixture(instrumentation.targetContext)
        awaitControl("controller ready") { fixture.controller.ready }
        mainThread {
            val app = instrumentation.targetContext.applicationContext as ChatApplication
            original = app.controller; app.controller = fixture.controller
        }
        activity = startMain()
    }
    override fun tearDown() {
        try {
            mainThread { activity.finish() }
            mainThread { (instrumentation.targetContext.applicationContext as ChatApplication).controller = original }
            fixture.close()
        } finally { super.tearDown() }
    }
}
