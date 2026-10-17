package app.daiyu.android

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import android.test.InstrumentationTestCase
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keeps the existing JUnit3 fixture lifecycle, with Compose's root registry around the whole test.
 * The suite still runs under android.test.InstrumentationTestRunner (smoke scripts parse its output),
 * so the androidx registry is populated here before the Compose rule needs it.
 */
@Suppress("DEPRECATION")
abstract class ComposeActivityTestCase : InstrumentationTestCase() {
    protected val ui = createEmptyComposeRule()
    private fun runLegacy() = super.runBare()
    override fun runBare() {
        InstrumentationRegistry.registerInstance(instrumentation, Bundle())
        ui.apply(object : Statement() { override fun evaluate() { runLegacy() } },
            Description.createTestDescription(javaClass, name)).evaluate()
    }
    protected fun mainThread(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        instrumentation.waitForIdleSync()
    }
    protected fun node(tag: String): SemanticsNodeInteraction {
        val interaction = ui.onNodeWithTag(tag)
        val found = ui.onAllNodesWithTag(tag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
        if (found != null && generateSequence(found.parent) { it.parent }.any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "chat-extra-actions"
            }) interaction.performScrollTo()
        return interaction
    }
    protected fun reveal(tag: String, page: String = "模型设置"): SemanticsNodeInteraction {
        node("page:$page").performScrollToNode(hasTestTag(tag))
        return node(tag)
    }
    protected fun editModel(label: String, text: String) { reveal("settings-field:$label").performTextReplacement(text) }
    protected fun modelField(label: String) = reveal("settings-field:$label")
    protected fun SemanticsNodeInteraction.assertEditableText(text: String): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 5_000) {
            fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }
        return assert(SemanticsMatcher("EditableText.text exactly equals <$text>") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        })
    }
    protected fun saveModel() {
        reveal("model-save").performClick()
        awaitControl("model settings completion") {
            !(instrumentation.targetContext.applicationContext as ChatApplication).controller.dataBusy
        }
        ui.waitForIdle()
    }
    protected fun openModels() {
        openSettings()
        reveal("settings-row:模型提供商", "设置").performClick()
    }
    protected fun openSettings() {
        ui.onNode(hasContentDescription("打开侧边栏") and hasClickAction())
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        val settings = ui.onNode(hasContentDescription(
            instrumentation.targetContext.getString(R.string.route_settings)) and hasClickAction())
        ui.waitUntil(timeoutMillis = 5_000) { settings.isDisplayed() }
        settings.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("page:设置").fetchSemanticsNodes().size == 1 }
    }
    protected fun menu(label: String) {
        ui.onNodeWithContentDescription("更多").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText(label).performScrollTo().performClick()
    }
    // sendText posts its receipt separately from runner/model events. Waiting for active or
    // Session.entered alone does not guarantee Activity consumption + acknowledgement.
    // Null also covers synchronous queue rejection (which intentionally has no callback).
    // Requires the retained result API introduced in f63a5f9.
    protected fun awaitTextSendResult(activity: MainActivity) {
        val controller = (instrumentation.targetContext.applicationContext as ChatApplication).controller
        ui.waitUntil(timeoutMillis = 5_000) {
            var complete = false
            instrumentation.runOnMainSync {
                complete = invokeHost(activity, "getPendingTextSend") == null && controller.textSendState() == null
            }
            complete
        }
        ui.waitForIdle()
    }
    protected fun setDraft(text: String) { node("chat-draft").performTextReplacement(text) }
    protected fun assertDraft(text: String) { node("chat-draft").assertEditableText(text) }
    protected fun setSelection(start: Int, end: Int) {
        node("chat-draft").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(start, end, false)) }
        ui.waitForIdle()
        assertSelection(start, end)
    }
    protected fun assertSelection(start: Int, end: Int) {
        val range = node("chat-draft").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(start, range.start); assertEquals(end, range.end)
    }
    // Retain host capabilities or app-owned semantics, not ClickableNode wrappers.
    // Capture before navigation; never refetch a replacement callback after a visit changes.
    protected fun retainedClick(activity: MainActivity, tag: String): () -> Unit {
        node(tag).assertExists()
        return when (tag) {
            "platform-action-confirm" -> {
                val action = checkNotNull(node(tag).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick].action);
                { assertTrue(action()) }
            }
            "dialog-confirm" -> {
                val prompt = invokeHost(activity, "getPrompt")!!
                when (prompt.javaClass.simpleName) {
                    "InputPrompt" -> {
                        val value = node("prompt-input").fetchSemanticsNode()
                            .config[SemanticsProperties.EditableText].text
                        val action: () -> Unit = { invokeHost(activity, "confirmInputPrompt", prompt, value); Unit }
                        action
                    }
                    "ConfirmPrompt" -> {
                        val action: () -> Unit = { invokeHost(activity, "confirmPrompt", prompt); Unit }
                        action
                    }
                    else -> error("Unsupported prompt: ${prompt.javaClass.name}")
                }
            }
            "model-save" -> {
                val draft = invokeHost(activity, "getModelDraft")!!;
                { invokeHost(activity, "saveModelSettings", draft); Unit }
            }
            "chat-control" -> retainedControls(activity).onPrimaryTap
            else -> {
                val title = tag.removePrefix("settings-row:")
                val route = checkNotNull(app.daiyu.android.ui.screens.settings.SettingsCatalog.entries
                    .single { it.title == title }.route);
                { invokeHost(activity, "openSettingsFromPage", route); Unit }
            }
        }
    }
    protected fun retainedControls(activity: MainActivity) = invokeHost(activity, "chatActions") as
        app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
    private fun invokeHost(activity: MainActivity, name: String, vararg arguments: Any): Any? {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
        try { return method.apply { isAccessible = true }.invoke(activity, *arguments) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
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
        try {
            ui.waitForIdle()
            return activity
        } catch (failure: Throwable) {
            try {
                mainThread { activity.finish() }
                awaitControl("failed-start Activity destroyed") { activity.isDestroyed }
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

/** Shared real-controller fixture; tests still assert database bytes and model-call counts. */
@Suppress("DEPRECATION")
abstract class WiredActivityTestCase : ComposeActivityTestCase() {
    private var fixtureRef: RunControlFixture? = null
    internal var fixture: RunControlFixture
        get() = checkNotNull(fixtureRef)
        set(value) { fixtureRef = value }
    private var original: ChatController? = null
    private var activityRef: MainActivity? = null
    protected var activity: MainActivity
        get() = checkNotNull(activityRef)
        set(value) { activityRef = value }
    internal open fun createFixture() = RunControlFixture(instrumentation.targetContext)
    override fun setUp() {
        super.setUp()
        fixture = createFixture()
        awaitControl("controller ready") { fixture.controller.ready }
        mainThread {
            val app = instrumentation.targetContext.applicationContext as ChatApplication
            original = app.controller; app.controller = fixture.controller
        }
        activity = startMain()
    }
    override fun runBare() {
        try { super.runBare() }
        catch (failure: Throwable) {
            try { cleanupFixture() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    override fun tearDown() {
        try { cleanupFixture() } finally { super.tearDown() }
    }
    private fun cleanupFixture() {
        if (activityRef == null && fixtureRef == null && original == null) return

        val closingActivity = activityRef
        val closingFixture = fixtureRef
        val restore = original
        try {
            mainThread { closingActivity?.finish() }
            awaitControl("fixture Activity destroyed") { closingActivity?.isDestroyed != false }
        } finally {
            try {
                mainThread {
                    restore?.let {
                        (instrumentation.targetContext.applicationContext as ChatApplication).controller = it
                    }
                }
            } finally {
                try {
                    closingFixture?.close()
                } finally {
                    activityRef = null
                    fixtureRef = null
                    original = null
                }
            }
        }
    }
}
