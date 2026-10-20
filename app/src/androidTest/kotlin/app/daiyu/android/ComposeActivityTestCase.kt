package app.daiyu.android

import android.content.Intent
import android.os.Bundle
import android.test.InstrumentationTestCase
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keeps the existing JUnit3 fixture lifecycle, with Compose's root registry around the whole test.
 * AndroidJUnitRunner registers the instrumentation and its arguments before tests run.
 */
@Suppress("DEPRECATION")
abstract class ComposeActivityTestCase : InstrumentationTestCase() {
    @OptIn(ExperimentalTestApi::class)
    protected val ui = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private var currentHost: MainActivity? = null
    private var voicePreferenceSnapshot: Pair<Boolean, String?>? = null
    protected open val engineeringUi: Boolean = false
    private fun runLegacy() = super.runBare()
    override fun runBare() {
        try {
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as ChatApplication).engineeringUiForTests = engineeringUi
            }
            ui.apply(object : Statement() { override fun evaluate() { runLegacy() } },
                Description.createTestDescription(javaClass, name)).evaluate()
        } finally {
            voicePreferenceSnapshot?.let { (hadValue, value) ->
                val editor = instrumentation.targetContext.getSharedPreferences("chat-voice-entry", 0).edit()
                if (hadValue) editor.putString("mode", value) else editor.remove("mode")
                check(editor.commit())
            }
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as ChatApplication).engineeringUiForTests = false
            }
        }
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
        android.util.Log.d("DaiyuTestStep", "node-ancestors:$tag")
        if (found != null && generateSequence(found.parent) { it.parent }.any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "chat-extra-actions"
            }) interaction.performScrollTo()
        return interaction
    }
    protected fun awaitUniqueTag(tag: String, hostState: () -> String = { "" }) {
        ui.mainClock.advanceTimeByFrame()
        try {
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            fun nodes(unmerged: Boolean) = ui.onAllNodesWithTag(tag, useUnmergedTree = unmerged)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .joinToString(prefix = "[", postfix = "]") { "${it.id}:${it.parent?.id}" }
            var state = ""
            instrumentation.runOnMainSync { state = hostState() }
            throw AssertionError("tag=$tag merged=${nodes(false)} unmerged=${nodes(true)} $state", timeout)
        }
        node(tag).assertExists()
    }

    protected fun awaitHostWindowFocus(host: MainActivity) {
        awaitControl("host resumed before focused-window check") {
            !host.isDestroyed && !host.isFinishing &&
                host.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
        check(currentHost === host)
        focusedWindowRoot()
    }

    protected fun focusedWindowRoot(): android.view.View {
        val host = checkNotNull(currentHost)
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var lastNoRoot: androidx.test.espresso.NoMatchingRootException? = null
        while (true) {
            mainThread { check(!host.isFinishing && !host.isDestroyed) }
            var root: android.view.View? = null
            try {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                    .inRoot(object : org.hamcrest.TypeSafeMatcher<androidx.test.espresso.Root>() {
                        override fun describeTo(description: org.hamcrest.Description) {
                            description.appendText("attached window with current focus")
                        }
                        override fun matchesSafely(candidate: androidx.test.espresso.Root): Boolean {
                            val decor = candidate.decorView
                            val hostDecor = host.window.decorView
                            val activityToken = host.window.attributes.token
                            val params = candidate.windowLayoutParams.orNull() ?: return false
                            var windowContext = decor.context
                            while (windowContext is android.content.ContextWrapper && windowContext !is android.app.Activity) {
                                windowContext = windowContext.baseContext
                            }
                            // Compose application Dialogs may have an independent layout token on API 26.
                            val owned = windowContext === host || decor === hostDecor ||
                                (activityToken != null && params.token == activityToken) ||
                                (hostDecor.windowToken != null && decor.applicationWindowToken == hostDecor.windowToken)
                            return owned && params.type in android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
                                android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW &&
                                decor.isAttachedToWindow && decor.hasWindowFocus()
                        }
                    })
                    .perform(object : androidx.test.espresso.ViewAction {
                        override fun getConstraints(): org.hamcrest.Matcher<android.view.View> =
                            androidx.test.espresso.matcher.ViewMatchers.isRoot()
                        override fun getDescription() = "capture focused window root"
                        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                            if (view.isAttachedToWindow && view.hasWindowFocus()) root = view
                        }
                    })
            } catch (failure: androidx.test.espresso.NoMatchingRootException) {
                lastNoRoot = failure
            }
            root?.let { return it }
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                var hostState = ""
                mainThread {
                    val decor = host.window.decorView
                    hostState = "host=${System.identityHashCode(host)} task=${host.taskId} " +
                        "attached=${decor.isAttachedToWindow} focused=${decor.hasWindowFocus()} " +
                        "token=${decor.windowToken}"
                }
                throw AssertionError("No attached focused test-process window within focus wait: $hostState", lastNoRoot)
            }
            android.os.SystemClock.sleep(20)
        }
    }
    protected fun dismissIme(knownRoot: android.view.View? = null) {
        val root = knownRoot ?: run {
            ui.waitForIdle()
            focusedWindowRoot()
        }
        fun requestHide() {
            check(root.isAttachedToWindow && root.hasWindowFocus()) { "ime_window_changed" }
            val controller = androidx.core.view.ViewCompat.getWindowInsetsController(root)
            if (controller != null) controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            else {
                val focus = root.findFocus() ?: root
                checkNotNull(root.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
                    .hideSoftInputFromWindow(checkNotNull(focus.windowToken), 0)
            }
        }
        instrumentation.runOnMainSync { requestHide() }
        ui.waitUntil(5_000) {
            var hidden = false
            instrumentation.runOnMainSync {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
                hidden = root.isAttachedToWindow && insets != null &&
                    !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                if (!hidden) requestHide()
            }
            hidden
        }
        ui.waitForIdle()
    }
    protected fun reveal(tag: String, page: String = "模型设置"): SemanticsNodeInteraction {
        dismissIme()
        val title = if (page == "模型设置" && ui.onAllNodesWithTag("page:主模型服务商").fetchSemanticsNodes().isNotEmpty())
            "主模型服务商" else page
        awaitUniqueTag("page:$title")
        val pageTag = "page:$title"
        val pageNode = ui.onNodeWithTag(pageTag).assert(hasScrollAction())
        val matcher = hasTestTag(tag) and hasAnyAncestor(hasTestTag(pageTag))
        val target = ui.onNode(matcher)
        data class Frame(val viewport: Rect, val target: Rect?, val clipped: Rect?, val scroll: Float)
        fun rawBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
            val coordinates = node.layoutInfo.coordinates
            var root = coordinates
            while (true) root = root.parentLayoutCoordinates ?: break
            return root.localBoundingBoxOf(coordinates, clipBounds = false)
        }
        fun frame(): Frame {
            val container = pageNode.fetchSemanticsNode()
            val matches = ui.onAllNodes(matcher).fetchSemanticsNodes()
            assertTrue("Duplicate target $tag in $pageTag", matches.size <= 1)
            val found = matches.singleOrNull()
            val bounds = container.boundsInRoot
            val bar = ui.onAllNodesWithTag("page-topbar:$title").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val viewport = Rect(bounds.left, maxOf(bounds.top, bar?.bottom ?: bounds.top), bounds.right, bounds.bottom)
            assertTrue("Empty reachable viewport for $pageTag: $viewport", viewport.width > 0f && viewport.height > 0f)
            val axis = checkNotNull(container.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            return Frame(viewport, found?.let(::rawBounds), found?.boundsInRoot, axis.value())
        }
        if (frame().target == null && pageNode.fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollToIndex) != null) {
            pageNode.performScrollToIndex(0)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        ui.waitUntil(timeoutMillis = 5_000) {
            val before = frame()
            val raw = before.target
            val clipped = before.clipped
            if (raw != null && clipped != null && clipped.width > 0f && clipped.height > 0f &&
                before.viewport.contains(raw.center) && before.viewport.contains(clipped.center) && target.isDisplayed()) {
                true
            } else {
                val limit = before.viewport.height * 0.5f
                val dy = if (raw == null) limit else (raw.center.y - before.viewport.center.y).coerceIn(-limit, limit)
                assertTrue("Target not reachable by scrolling: $tag; $before", kotlin.math.abs(dy) >= 0.5f)
                val axis = checkNotNull(pageNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                pageNode.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                    assertTrue("ScrollBy rejected for $tag", scroll(0f, if (axis.reverseScrolling) -dy else dy))
                }
                // ScrollBy accepts a queued coroutine; Espresso idle does not start that task.
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                val after = frame()
                assertTrue("Scroll made no progress for $tag: before=$before after=$after", after != before)
                false
            }
        }
        return target.assertIsDisplayed()
    }
    protected fun retainedSettingsEntry(id: String, page: String = "设置"): () -> Unit {
        val entry = app.daiyu.android.ui.screens.settings.SettingsCatalog.entry(id)
        val open = checkNotNull(node("page:$page").fetchSemanticsNode().config.getOrNull(
            app.daiyu.android.ui.screens.settings.RetainedSettingsEntryAction))
        return { open(entry) }
    }
    protected fun openRetainedSettingsEntry(id: String, expectedTag: String) {
        val open = retainedSettingsEntry(id)
        mainThread { open() }
        awaitUniqueTag(expectedTag)
    }
    protected fun editModel(label: String, text: String) {
        reveal("settings-field:$label").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(androidx.compose.ui.text.AnnotatedString(text)))
        }
    }
    protected fun modelField(label: String) = reveal("settings-field:$label")
    protected fun inlineBodyControl(tag: String): SemanticsNodeInteraction = reveal(tag)
    protected fun openInlineBodyFields() {
        inlineBodyControl("model-body-fields")
        if (ui.onAllNodesWithTag("model-body-field-add").fetchSemanticsNodes().isEmpty()) {
            node("model-body-fields").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) {
                assertTrue(it())
            }
        }
        inlineBodyControl("model-body-field-add").assertExists()
    }
    protected fun addInlineBodyField(name: String, jsonValue: String, index: Int = 0) {
        openInlineBodyFields()
        inlineBodyControl("model-body-field-add").assertIsEnabled().performClick()
        inlineBodyControl("model-body-field-name:$index").performTextReplacement(name)
        inlineBodyControl("model-body-field-value:$index").performTextReplacement(jsonValue)
    }
    protected fun attemptBlockedInlineBodySave() {
        val submit = reveal("model-save").assertIsNotEnabled().fetchSemanticsNode()
            .config[app.daiyu.android.ui.screens.settings.RetainedModelSaveAction]
        mainThread { submit() }
        ui.waitForIdle()
    }
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
    protected fun openProviderManagement() {
        openSettings()
        reveal("settings-row:模型提供商", "设置").performClick()
        awaitUniqueTag("provider-management")
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("provider-add") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        reveal("provider-open-primary", "模型提供商").assertIsDisplayed().assertIsEnabled()
    }
    protected fun selectProviderTab(title: String) {
        val tag = "settings-radio:$title"
        val tab = reveal(tag).assertIsEnabled()
        if (tab.fetchSemanticsNode().config[SemanticsProperties.Selected] != true) {
            tab.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        }
        reveal(tag).assertIsSelected()
    }
    protected fun chooseProviderProtocol(label: String) {
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:$label").assertIsEnabled().performClick()
    }
    protected fun assertProviderProtocol(label: String) {
        reveal("provider-endpoint-select").performClick()
        node("settings-radio:$label").assertIsSelected().performClick()
    }
    protected fun openModels() {
        openProviderManagement()
        reveal("provider-open-primary", "模型提供商").performClick()
        awaitModelEditor()
    }
    protected fun awaitModelEditor() {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("page:主模型服务商").fetchSemanticsNodes().size == 1 ||
                ui.onAllNodesWithTag("page:模型设置").fetchSemanticsNodes().size == 1
        }
        reveal("settings-field:Base URL")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("settings-field:Base URL").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }
    protected fun openSettings() {
        dismissIme()
        ui.onNode(hasContentDescription("打开侧边栏") and hasClickAction())
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        val settings = ui.onNode(hasContentDescription(
            instrumentation.targetContext.getString(R.string.route_settings)) and hasClickAction())
        ui.waitUntil(timeoutMillis = 5_000) { settings.isDisplayed() }
        settings.assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("page:设置").fetchSemanticsNodes().size == 1 }
    }
    protected fun openVoiceConversationPanel() {
        if (voicePreferenceSnapshot == null) {
            val preferences = instrumentation.targetContext.getSharedPreferences("chat-voice-entry", 0)
            voicePreferenceSnapshot = preferences.contains("mode") to preferences.getString("mode", null)
        }
        ui.onNodeWithContentDescription("语音输入").assertIsEnabled().performTouchInput { longClick() }
        node("chat-menu:VOICE_CHAT").assertIsEnabled().performClick()
        node("voice-universal-control").assertDoesNotExist()
        ui.onNodeWithContentDescription("语音输入").assertIsEnabled().performClick()
        awaitUniqueTag("voice-universal-control")
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
        if (tag == "model-save") reveal(tag) else node(tag).assertExists()
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
            "model-save" -> checkNotNull(node(tag).fetchSemanticsNode()
                .config.getOrNull(app.daiyu.android.ui.screens.settings.RetainedModelSaveAction))
            "chat-control" -> retainedControls(activity).onPrimaryTap
            else -> {
                val title = tag.removePrefix("settings-row:")
                val entry = app.daiyu.android.ui.screens.settings.SettingsCatalog.entries.single { it.title == title }
                retainedSettingsEntry(entry.id)
            }
        }
    }
    protected fun retainedControls(activity: MainActivity) = invokeHost(activity, "chatActions") as
        app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
    protected fun invokeHost(activity: MainActivity, name: String, vararg arguments: Any): Any? {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
        try { return method.apply { isAccessible = true }.invoke(activity, *arguments) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }
    protected fun backToChat(activity: MainActivity) {
        dismissIme()
        repeat(8) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            var chatExposed = false
            mainThread {
                assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed)
                chatExposed = invokeHost(activity, "getRetainedPage") == "chat" &&
                    invokeHost(activity, "getSidebarVisible") == false
            }
            if (chatExposed &&
                ui.onAllNodesWithTag("chat-draft").fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1) {
                node("chat-draft").assertIsDisplayed()
                return
            }
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        }
        mainThread {
            assertEquals("chat", invokeHost(activity, "getRetainedPage"))
            assertEquals(false, invokeHost(activity, "getSidebarVisible"))
        }
        awaitUniqueTag("chat-draft")
    }
    protected fun backToPage(activity: MainActivity, expectedTag: String) {
        dismissIme()
        ui.waitForIdle()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        mainThread { assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed) }
        awaitUniqueTag(expectedTag) {
            "retainedPage=${invokeHost(activity, "getRetainedPage")} sidebarVisible=${invokeHost(activity, "getSidebarVisible")}"
        }
        node(expectedTag).assertIsDisplayed()
    }
    /** Real framework pause/resume, retaining this Activity without onStop or recreation. */
    protected fun realPauseResume(original: MainActivity, beforePause: () -> Unit = {}, atPause: () -> Unit = {}) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val debugCover = android.content.ComponentName(original.packageName, "app.daiyu.android.LifecycleCoverActivity")
        val debugCoverPresent = try { original.packageManager.getActivityInfo(debugCover, 0); true }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        if (!debugCoverPresent) {
            releasePauseResume(original, beforePause, atPause)
            return
        }
        val app = original.application
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var cover: android.app.Activity? = null
        var paused = false
        var resumed = false
        var stopped = false
        fun capture(action: () -> Unit) {
            try { action() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }
        val before = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) capture(beforePause)
        }
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {
                if (activity.componentName.className == "app.daiyu.android.LifecycleCoverActivity") cover = activity
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (activity === original) {
                    paused = true
                    capture { assertFalse(stopped); atPause() }
                }
            }
            override fun onActivityResumed(activity: android.app.Activity) { if (activity === original) resumed = true }
            override fun onActivityStopped(activity: android.app.Activity) { if (activity === original) stopped = true }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        mainThread {
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, original.lifecycle.currentState)
            app.registerActivityLifecycleCallbacks(callbacks)
            original.lifecycle.addObserver(before)
        }
        var primary: Throwable? = null
        try {
            val started = instrumentation.startActivitySync(Intent().setComponent(android.content.ComponentName(
                instrumentation.targetContext.packageName, "app.daiyu.android.LifecycleCoverActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            mainThread { assertSame(cover, started); assertEquals(original.taskId, started.taskId) }
            awaitControl("original paused under transparent cover") { paused && !stopped }
            failure.get()?.let { throw it }
        } catch (problem: Throwable) {
            primary = problem
            throw problem
        } finally {
            try {
                mainThread { cover?.let { if (!it.isDestroyed) it.finish() } }
                awaitControl("same Activity fully resumed after cover") {
                    (cover == null || cover!!.isDestroyed) && (!paused || resumed) &&
                        !original.isDestroyed && !original.isFinishing &&
                        original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                }
                mainThread { assertFalse("pause-only cover must not stop original", stopped) }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            } finally {
                mainThread { original.lifecycle.removeObserver(before); app.unregisterActivityLifecycleCallbacks(callbacks) }
            }
        }
        awaitHostWindowFocus(original)
        ui.waitForIdle()
    }

    /** Release keeps its APK free of test components; this cover belongs only to the test APK. */
    private fun releasePauseResume(original: MainActivity, beforePause: () -> Unit, atPause: () -> Unit) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var finishCover: android.os.ResultReceiver? = null
        var coverReady = false
        var coverDestroyed = false
        var coverTask = -1
        var coverPid = -1
        var coverUid = -1
        var cleanupRequested = false
        var paused = false
        var resumed = false
        var stopped = false
        fun capture(action: () -> Unit) {
            try { action() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }
        val before = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) capture(beforePause)
        }
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: android.app.Activity) {
                if (activity === original) { paused = true; capture { assertFalse(stopped); atPause() } }
            }
            override fun onActivityResumed(activity: android.app.Activity) { if (activity === original) resumed = true }
            override fun onActivityStopped(activity: android.app.Activity) { if (activity === original) stopped = true }
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        val reply = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
            @Suppress("DEPRECATION")
            override fun onReceiveResult(code: Int, data: Bundle?) {
                when (code) {
                    0 -> {
                        finishCover = data?.getParcelable("finish")
                        coverTask = data?.getInt("task", -1) ?: -1
                        coverPid = data?.getInt("pid", -1) ?: -1
                        coverUid = data?.getInt("uid", -1) ?: -1
                        if (cleanupRequested) finishCover?.send(0, null)
                    }
                    1 -> coverReady = true
                    2 -> coverDestroyed = true
                }
            }
        }
        mainThread {
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, original.lifecycle.currentState)
            original.application.registerActivityLifecycleCallbacks(callbacks)
            original.lifecycle.addObserver(before)
        }
        var primary: Throwable? = null
        var launched = false
        try {
            mainThread {
                val parcel = android.os.Parcel.obtain()
                val remoteReply = try {
                    reply.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    android.os.ResultReceiver.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                original.startActivity(Intent().setComponent(android.content.ComponentName(
                    instrumentation.context.packageName, "app.daiyu.android.ReleaseLifecycleCoverActivity"))
                    .putExtra("reply", remoteReply))
                launched = true
            }
            awaitControl("original paused under test APK cover") {
                paused && !stopped && !resumed && coverReady && !coverDestroyed && finishCover != null &&
                    original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.STARTED
            }
            mainThread {
                assertEquals(android.os.ResultReceiver::class.java, checkNotNull(finishCover).javaClass)
                assertEquals(original.taskId, coverTask)
                assertTrue("cover must use another process", coverPid > 0 && coverPid != android.os.Process.myPid())
                val installedTestUid = instrumentation.context.packageManager
                    .getApplicationInfo(instrumentation.context.packageName, 0).uid
                assertEquals("cover UID must match the installed test package", installedTestUid, coverUid)
                assertTrue("cover must use the test APK UID", coverUid != android.os.Process.myUid())
            }
            failure.get()?.let { throw it }
        } catch (problem: Throwable) {
            primary = problem
            throw problem
        } finally {
            try {
                mainThread { cleanupRequested = true }
                if (launched) {
                    awaitControl("test APK cover finish channel") { finishCover != null || coverDestroyed }
                    mainThread { finishCover?.send(0, null) }
                    awaitControl("same release Activity resumed after test cover") {
                        coverDestroyed && (!paused || resumed) && !original.isDestroyed && !original.isFinishing &&
                            original.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                    }
                }
                mainThread { assertFalse("pause-only cover must not stop original", stopped) }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            } finally {
                mainThread { original.lifecycle.removeObserver(before); original.application.unregisterActivityLifecycleCallbacks(callbacks) }
            }
        }
        awaitHostWindowFocus(original)
        ui.waitForIdle()
    }

    protected fun recreateActivity(activity: MainActivity, afterDestroyed: () -> Unit = {}): MainActivity {
        dismissIme()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            mainThread { androidx.core.app.ActivityCompat.recreate(activity) }
            awaitControl("original Activity destroyed before recreated UI waits") { activity.isDestroyed }
            afterDestroyed()
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? MainActivity
            assertNotNull("framework recreation must resume a new Activity", replacement)
            val created = checkNotNull(replacement)
            try {
                ui.waitForIdle()
                assertTrue(activity.isDestroyed)
                currentHost = created
                awaitHostWindowFocus(created)
                return created
            } catch (failure: Throwable) {
                try {
                    mainThread { created.finish() }
                    awaitControl("failed-recreation Activity destroyed") { created.isDestroyed }
                } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    protected fun startMain(intent: Intent = Intent(instrumentation.targetContext, MainActivity::class.java)): MainActivity {
        val app = instrumentation.targetContext.applicationContext as ChatApplication
        val created = mutableListOf<MainActivity>()
        var cleanupRequested = false
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {
                if (activity is MainActivity) {
                    created.add(activity)
                    if (cleanupRequested) activity.finish()
                }
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        mainThread { app.registerActivityLifecycleCallbacks(callbacks) }
        try {
            instrumentation.runOnMainSync { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            awaitControl("MainActivity created by explicit launch") { created.isNotEmpty() }
            val activity = created.single()
            currentHost = activity
            awaitHostWindowFocus(activity)
            return activity
        } catch (failure: Throwable) {
            try {
                mainThread { cleanupRequested = true; created.forEach { if (!it.isDestroyed) it.finish() } }
                awaitControl("failed-start Activities destroyed") { created.all { it.isDestroyed } }
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            mainThread { app.unregisterActivityLifecycleCallbacks(callbacks) }
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
    protected open fun cleanupFixture() {
        if (activityRef == null && fixtureRef == null && original == null) return
        val closingActivity = activityRef
        mainThread { closingActivity?.finish() }
        awaitControl("fixture Activity destroyed") { closingActivity?.isDestroyed != false }
        mainThread {
            original?.let {
                (instrumentation.targetContext.applicationContext as ChatApplication).controller = it
            }
        }
        fixtureRef?.close()
        activityRef = null
        fixtureRef = null
        original = null
    }
}
