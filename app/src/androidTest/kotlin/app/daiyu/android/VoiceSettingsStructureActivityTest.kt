package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.voice.account.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.ui.screens.settings.SettingsModelSource
import app.daiyu.android.ui.screens.settings.SettingsModelProtocols

/** Navigation and draft edits only: no microphone, model download, synthesis or paid account call. */
class VoiceSettingsStructureActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()

        reveal("settings-row:语音", "设置").performClick()
        node("voice-settings").assertExists()
    }
    private fun section(title: String) {
        node("voice-section-select").performClick()
        ui.onNode(hasText(title, substring = false) and hasClickAction() and
            !hasTestTag("voice-section-select") and hasAnyAncestor(hasTestTag("voice-settings")))
            .scrollToVisible().performClick()
        node("voice-section-select").assertTextContains(title)
    }
    fun testSectionSelectorKeepsCardWidthAndMenuHeightAnimates() {
        enter()
        val selector = node("voice-section-selector-card")
        val collapsed = selector.fetchSemanticsNode().boundsInRoot
        val home = node("voice-recognition-input-card").fetchSemanticsNode().boundsInRoot
        assertEquals("Entry selector keeps ordinary card width", home.width, collapsed.width, 1f)
        assertEquals("Entry selector keeps the left card edge", home.left, collapsed.left, 1f)
        assertEquals("Entry selector keeps the right card edge", home.right, collapsed.right, 1f)
        assertTrue("Only the entry height is more compact", collapsed.height < home.height)
        node("voice-section-select").assertHeightIsAtLeast(48.dp)
        ui.mainClock.autoAdvance = false
        try {
            node("voice-section-select").performClick()
            ui.mainClock.advanceTimeBy(80)
            ui.waitForIdle()
            val openingHeight = selector.fetchSemanticsNode().boundsInRoot.height
            ui.mainClock.advanceTimeBy(300)
            ui.waitForIdle()
            val expanded = selector.fetchSemanticsNode().boundsInRoot
            val openHeight = expanded.height
            assertEquals(home.width, expanded.width, 1f)
            assertEquals(home.left, expanded.left, 1f)
            assertEquals(home.right, expanded.right, 1f)
            assertTrue("Opening has an intermediate height", openingHeight > collapsed.height && openingHeight < openHeight)
            node("voice-section-select").performClick()
            ui.mainClock.advanceTimeBy(80)
            ui.waitForIdle()
            val closingHeight = selector.fetchSemanticsNode().boundsInRoot.height
            assertTrue("Closing has an intermediate height", closingHeight > collapsed.height && closingHeight < openHeight)
            ui.mainClock.advanceTimeBy(300)
            ui.waitForIdle()
            node("voice-section-option:READ_ALOUD").assertDoesNotExist()
            assertEquals(collapsed.height, selector.fetchSemanticsNode().boundsInRoot.height, 1f)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testSectionSelectorClosingOptionsCannotReceiveTouch() {
        enter()
        node("voice-section-select").performClick()
        val option = node("voice-section-option:READ_ALOUD").fetchSemanticsNode()
        val optionRoot = option.root
        val root = ui.onNode(isRoot() and SemanticsMatcher("voice section option root") { it.root === optionRoot }, useUnmergedTree = true)
        val touch = option.boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
        ui.mainClock.autoAdvance = false
        try {
            node("voice-section-select").performClick()
            ui.mainClock.advanceTimeBy(16)
            ui.waitForIdle()
            node("voice-section-option:READ_ALOUD").assertDoesNotExist()
            root.performTouchInput { click(touch) }
            ui.mainClock.advanceTimeBy(300)
            ui.waitForIdle()
            node("voice-section-select").assertTextContains("语音识别")
            node("voice-section-option:READ_ALOUD").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    private var voicePreferencesSnapshot: Map<String, *>? = null
    private val creationCleanups = mutableListOf<() -> Unit>()
    override fun cleanupAfterLegacy() {
        try {
            creationCleanups.forEach { it() }
            voicePreferencesSnapshot?.let { values ->
                val editor = instrumentation.targetContext.getSharedPreferences("voice-settings", 0).edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(editor.commit())
            }
        } finally { super.cleanupAfterLegacy() }
    }
    private fun systemProfiles() = runBlocking {
        val store = instrumentation.targetContext.getSharedPreferences("voice-settings", 0)
        if (voicePreferencesSnapshot == null) voicePreferencesSnapshot = store.all.toMap()
        VoiceSettingsPreferences(instrumentation.targetContext).apply {
            setCloudEnabled(VoiceProfile.READ_ALOUD, false)
            setCloudEnabled(VoiceProfile.CONVERSATION, false)
        }
        fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.READ_ALOUD)
        fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.CONVERSATION)
    }
    fun testAllFiveGroupsUseSeparateHomesWithoutCloudForms() {
        systemProfiles(); enter()
        node("voice-input-enable").assertExists()
        reveal("voice-recognition-method", "语音设置").assertExists()
        section("朗读")
        node("voice-cloud-enable").assertExists()
        reveal("voice-preview", "语音设置").assertExists()
        node("voice-model-open").assertDoesNotExist()
        node("settings-field:TTS API key").assertDoesNotExist()
        section("语音对话")
        node("settings-switch:启用语音对话").assertExists()
        node("settings-switch:豆包语音识别").assertDoesNotExist()
        reveal("voice-recognition-method", "语音设置").assertExists()
        reveal("voice-preview", "语音设置").assertExists()
        section("豆包实时通话")
        node("settings-switch:启用豆包实时通话").assertExists()
        node("settings-row:提供商").assertExists()
        section("我的声音")
        node("voice-account-open").assertExists()
        node("voice-mimo-open").assertExists()
        node("settings-field:声音名称").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testRecognitionAndMimoReturnToTheirOwnHomes() {
        systemProfiles(); enter()
        reveal("voice-recognition-method", "语音设置").performClick()
        node("voice-asr-method:system").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-section-select").assertTextContains("语音识别")
        section("我的声音")
        node("voice-mimo-open").performClick()
        node("voice-sample-import").assertExists()
        reveal("voice-sample-save", "语音设置").assertIsNotEnabled()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-account-open").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testMimoPreviewEditorHasVisibleContainerAndKeepsMultilineDraftOnReturn() {
        systemProfiles(); enter()
        val outer = node("voice-recognition-input-card").fetchSemanticsNode().boundsInRoot
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        val accounts = fixture.controller.voiceRuntime.accountConfigurations.value
        section("我的声音"); node("voice-mimo-open").performClick()
        val card = reveal("voice-mimo-preview-card", "MiMo 语音").fetchSemanticsNode().boundsInRoot
        assertEquals(outer.left, card.left, 1f)
        assertEquals(outer.right, card.right, 1f)
        val container = node("settings-field:试听文字-container").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("voice-mimo-preview-card"))).fetchSemanticsNode().boundsInRoot
        assertTrue(container.left > card.left && container.right < card.right)
        val cardPixels = node("voice-mimo-preview-card").captureToImage().toPixelMap()
        val inputPixels = node("settings-field:试听文字-container").captureToImage().toPixelMap()
        val marginY = ((container.top - card.top) / 2).toInt().coerceIn(0, cardPixels.height - 1)
        val cardFill = cardPixels[cardPixels.width / 2, marginY]
        val inputFill = inputPixels[inputPixels.width / 2, inputPixels.height * 3 / 4]
        assertTrue("Input fill remains distinguishable from its card",
            kotlin.math.abs(cardFill.red - inputFill.red) + kotlin.math.abs(cardFill.green - inputFill.green) +
                kotlin.math.abs(cardFill.blue - inputFill.blue) > .02f)
        val editor = node("settings-field:试听文字").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("settings-field:试听文字-container")))
            .assertTextContains("你好，这是我的声音。")
        val text = "第一行试听\n第二行试听"
        awaitNodeWindowFocus(editor)
        editor.performTouchInput { click() }
        editor.performTextReplacement(text)
        awaitImeFrame(editor, visible = true)
        dismissIme(); awaitImeFrame(editor, visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("voice-mimo-open")
        node("voice-mimo-open").performClick()
        val draft = reveal("settings-field:试听文字", "MiMo 语音").assertTextEquals(text)
        awaitNodeWindowFocus(draft)
        draft.performTouchInput { click() }
        draft.performTextReplacement("声".repeat(320))
        val reopened = node("settings-field:试听文字").assertTextEquals("声".repeat(300))
        awaitImeFrame(reopened, visible = true)
        dismissIme(); awaitImeFrame(reopened, visible = false)
        reopened.performTextReplacement("")
        node("settings-field:试听文字-container").assertIsDisplayed()
        node("settings-field:试听文字").assertTextEquals("")
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(accounts, fixture.controller.voiceRuntime.accountConfigurations.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testRecognitionHomeOmitsExtraOfflineEntryAndKeepsAlignedCards() {
        systemProfiles(); enter()
        node("offline-speech-enable").assertDoesNotExist()
        ui.onAllNodesWithText("离线听写", substring = false).assertCountEquals(0)
        node("voice-input-enable").assert(hasAnyAncestor(hasTestTag("voice-recognition-input-card")))
        node("voice-offline-settings").assertDoesNotExist()
        ui.onAllNodesWithText("离线语音", substring = false).assertCountEquals(0)
        val inputBounds = node("voice-recognition-input-card").fetchSemanticsNode().boundsInRoot
        val packBounds = reveal("voice-offline-pack-card", "语音设置").fetchSemanticsNode().boundsInRoot
        val methodBounds = reveal("voice-recognition-method-card", "语音设置").fetchSemanticsNode().boundsInRoot
        for (bounds in listOf(packBounds, methodBounds)) {
            assertEquals(inputBounds.left, bounds.left, 1f)
            assertEquals(inputBounds.right, bounds.right, 1f)
        }
        reveal("voice-recognition-method", "语音设置")
            .assert(hasAnyAncestor(hasTestTag("voice-recognition-method-card"))).performClick()
        node("voice-section-select").assertDoesNotExist()
        node("voice-asr-method:system").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testConversationDownloadCardMatchesSiblingCardGeometry() {
        systemProfiles(); enter(); section("语音对话")
        val cloud = reveal("voice-cloud-enable", "语音设置").fetchSemanticsNode().boundsInRoot
        val pack = reveal("voice-offline-pack-card", "语音设置").fetchSemanticsNode().boundsInRoot
        val download = ui.onNode(hasAnyAncestor(hasTestTag("voice-offline-pack-card")) and
            (hasTestTag("offline-speech-download") or hasTestTag("offline-speech-cancel-download")))
            .assertHeightIsAtLeast(64.dp).fetchSemanticsNode().boundsInRoot
        assertEquals(cloud.left, download.left, 1f)
        assertEquals(cloud.right, download.right, 1f)
        assertTrue(download.left >= pack.left && download.right <= pack.right)
        assertEquals(0, fixture.opens.get())
    }

    fun testConversationRecognitionEntryKeepsConfigurationAndReturnsToConversation() {
        systemProfiles()
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(asrKey = "synthetic-reading-asr-key",
                instructions = "reading instructions"), VoiceProfile.READ_ALOUD)
            fixture.controller.voiceRuntime.save(VoiceConfiguration(asrKey = "synthetic-conversation-original-key",
                asrResource = app.daiyu.android.voice.providers.DoubaoAsrProtocol.resources[1],
                instructions = "conversation instructions"), VoiceProfile.CONVERSATION)
        }
        val reading = checkNotNull(fixture.controller.voiceRuntime.configuration.value)
        val originalConversation = checkNotNull(fixture.controller.voiceRuntime.conversationConfiguration.value)
        val accounts = fixture.controller.voiceRuntime.accountConfigurations.value
        enter()
        val referenceBounds = reveal("voice-recognition-method-card", "语音设置").fetchSemanticsNode().boundsInRoot
        section("语音对话")
        node("settings-switch:启用语音对话").assertExists()
        node("settings-switch:豆包语音识别").assertDoesNotExist()
        node("settings-field:ASR API Key").assertDoesNotExist()
        node("settings-row:识别服务").assertDoesNotExist()
        val method = reveal("voice-recognition-method", "语音设置")
            .assert(hasAnyAncestor(hasTestTag("voice-recognition-method-card")))
            .assertTextContains("本机识别 · 无需账户")
        val methodBounds = node("voice-recognition-method-card").fetchSemanticsNode().boundsInRoot
        assertEquals(referenceBounds.left, methodBounds.left, 1f)
        assertEquals(referenceBounds.right, methodBounds.right, 1f)
        assertEquals(referenceBounds.height, methodBounds.height, 1f)
        method.performClick()
        node("voice-section-select").assertDoesNotExist()
        node("voice-asr-method:system").assertIsSelected()
        assertRecognitionSegmentsAreCompact()
        assertRecognitionSelectedColors("voice-asr-method:system")
        node("voice-asr-method:doubao").performClick()
        ui.waitUntil { fixture.controller.voiceRuntime.conversationConfiguration.value?.dictation == "DOUBAO" }
        ui.waitForIdle()
        node("voice-asr-method:doubao").assertIsSelected()
        assertRecognitionSelectedColors("voice-asr-method:doubao")
        val cloudConversation = checkNotNull(fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(originalConversation.copy(dictation = "DOUBAO", revision = cloudConversation.revision), cloudConversation)
        node("settings-field:ASR API Key").assert(hasAnyAncestor(hasTestTag("voice-asr-account-card")))
            .performTextReplacement("synthetic-conversation-asr-key")
        awaitImeFrame(node("settings-field:ASR API Key"), visible = true)
        dismissIme(); awaitImeFrame(node("settings-field:ASR API Key"), visible = false)
        assertRecognitionSaveMatchesSelectedTab()
        node("voice-save").assertIsEnabled().performClick()
        ui.waitUntil { fixture.controller.voiceRuntime.conversationConfiguration.value?.asrKey == "synthetic-conversation-asr-key" }
        ui.waitForIdle()
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        val savedConversation = checkNotNull(fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(cloudConversation.copy(asrKey = "synthetic-conversation-asr-key", revision = savedConversation.revision), savedConversation)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-section-select").assertTextContains("语音对话")
        reveal("voice-recognition-method", "语音设置").assertTextContains("豆包识别 · 需要联网").performClick()
        node("voice-asr-method:doubao").assertIsSelected()
        node("settings-field:ASR API Key").assertTextContains("synthetic-conversation-asr-key")
        node("voice-asr-method:system").performClick()
        ui.waitUntil { fixture.controller.voiceRuntime.conversationConfiguration.value?.dictation == "SYSTEM" }
        ui.waitForIdle()
        node("voice-asr-account-card").assertDoesNotExist()
        val localConversation = checkNotNull(fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(savedConversation.copy(dictation = "SYSTEM", revision = localConversation.revision), localConversation)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-section-select").assertTextContains("语音对话")
        reveal("voice-recognition-method", "语音设置").assertTextContains("本机识别 · 无需账户").performClick()
        node("voice-asr-method:system").assertIsSelected()
        assertEquals("synthetic-conversation-asr-key", fixture.controller.voiceRuntime.conversationConfiguration.value?.asrKey)
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(accounts, fixture.controller.voiceRuntime.accountConfigurations.value)
        assertEquals(0, fixture.opens.get())
    }

    private fun assertRecognitionSegmentsAreCompact() {
        val page = node("page:语音设置").getUnclippedBoundsInRoot()
        val group = node("voice-asr-method-card").getUnclippedBoundsInRoot()
        val pageWidth = (page.right - page.left).value
        val groupWidth = (group.right - group.left).value
        assertEquals(minOf(300f, pageWidth - 32f), groupWidth, 1f)
        assertEquals(page.left.value + page.right.value, group.left.value + group.right.value, 1f)
        val local = node("voice-asr-method:system").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val cloud = node("voice-asr-method:doubao").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertEquals(local.width, cloud.width, 1f)
    }

    private fun assertRecognitionSaveMatchesSelectedTab() {
        val tab = node("voice-asr-method:doubao").assertIsSelected().captureToImage().toPixelMap()
        val button = node("voice-save").assertIsEnabled().captureToImage().toPixelMap()
        val selectedFill = tab[tab.width / 5, tab.height / 2]
        val buttonFill = button[button.width / 5, button.height / 2]
        assertEquals(selectedFill.red, buttonFill.red, .025f)
        assertEquals(selectedFill.green, buttonFill.green, .025f)
        assertEquals(selectedFill.blue, buttonFill.blue, .025f)
    }

    private fun assertRecognitionSelectedColors(tag: String) {
        val pixels = node(tag).captureToImage().toPixelMap()
        fun Color.matches(expected: Color) = abs(red - expected.red) < .035f &&
            abs(green - expected.green) < .035f && abs(blue - expected.blue) < .035f
        var backgroundCount = 0
        var foregroundCount = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.matches(Color(0xff82728f))) backgroundCount++
            if (pixel.matches(Color.White)) foregroundCount++
        }
        assertTrue("Recognition uses the workspace selected background", backgroundCount > pixels.width * pixels.height * .04f)
        assertTrue("Recognition keeps the workspace white foreground", foregroundCount > 8)
    }

    fun testRecognitionSegmentsPersistSelectionAndKeepOtherProfiles() {
        systemProfiles()
        val reading = checkNotNull(fixture.controller.voiceRuntime.configuration.value)
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter(); reveal("voice-recognition-method", "语音设置").performClick()
        node("voice-asr-method:system").assertIsSelected()
        assertRecognitionSegmentsAreCompact()
        assertRecognitionSelectedColors("voice-asr-method:system")
        ui.onNodeWithText("无需账户。上一页下载语音包后，即可离线识别。").assertDoesNotExist()
        node("voice-asr-method:doubao").assertIsNotSelected()
        for (tag in listOf("voice-asr-method:system", "voice-asr-method:doubao")) {
            node(tag).assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
        }
        val local = node("voice-asr-method:system").fetchSemanticsNode().boundsInRoot
        val cloud = node("voice-asr-method:doubao").fetchSemanticsNode().boundsInRoot
        assertEquals(local.width, cloud.width, 1f)
        assertEquals(local.center.y, cloud.center.y, 1f)
        node("voice-asr-method:doubao").performClick()
        ui.waitUntil { fixture.controller.voiceRuntime.configuration.value?.dictation == "DOUBAO" }
        ui.waitForIdle()
        ui.onNodeWithText("已保存").assertDoesNotExist()
        node("voice-asr-method:doubao").assertIsSelected()
        assertRecognitionSelectedColors("voice-asr-method:doubao")
        node("voice-asr-method:system").assertIsNotSelected()
        node("voice-asr-account-card").assertExists()
        ui.onAllNodesWithText("识别音频会发送到豆包", substring = true).assertCountEquals(0)
        assertEquals(reading.asrKey, fixture.controller.voiceRuntime.configuration.value?.asrKey)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        node("voice-asr-method:system").performClick()
        ui.waitUntil { fixture.controller.voiceRuntime.configuration.value?.dictation == "SYSTEM" }
        ui.waitForIdle()
        ui.onNodeWithText("已保存").assertDoesNotExist()
        ui.onNodeWithText("无需账户。上一页下载语音包后，即可离线识别。").assertDoesNotExist()
        node("voice-asr-method:system").assertIsSelected()
        node("voice-asr-account-card").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        reveal("voice-recognition-method", "语音设置").performClick()
        node("voice-asr-method:system").assertIsSelected()
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testRecognitionAccountGroupEyeAndSaveBelowKeyPreserveExplicitPersistence() {
        systemProfiles()
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO"), VoiceProfile.READ_ALOUD)
        }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter(); reveal("voice-recognition-method", "语音设置").performClick()
        node("voice-section-select").assertDoesNotExist()
        val group = hasAnyAncestor(hasTestTag("voice-asr-account-card"))
        node("settings-field:ASR API Key").assert(group)
        node("voice-save").assert(group).assertIsNotEnabled()
        node("voice-asr-advanced").assert(group)
        node("voice-asr-console").assert(group)
        node("settings-field:ASR API Key").performTextReplacement("synthetic-asr-key")
        awaitImeFrame(node("settings-field:ASR API Key"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:ASR API Key"), visible = false)
        node("settings-field:ASR API Key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        ui.onAllNodesWithText("显示密钥").assertCountEquals(0)
        val container = ui.onNodeWithTag("settings-field:ASR API Key-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val eye = node("settings-secret-visibility:ASR API Key").assertContentDescriptionEquals("显示密钥")
            .fetchSemanticsNode().boundsInRoot
        val field = node("settings-field:ASR API Key").fetchSemanticsNode().boundsInRoot
        assertTrue(eye.left >= field.right - 1f)
        assertTrue(eye.right <= container.right + 1f && eye.top >= container.top - 1f && eye.bottom <= container.bottom + 1f)
        node("settings-secret-visibility:ASR API Key").performClick()
        node("settings-field:ASR API Key").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        awaitImeFrame(node("settings-field:ASR API Key"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:ASR API Key"), visible = false)
        node("settings-secret-visibility:ASR API Key").assertContentDescriptionEquals("隐藏密钥").performClick()
        node("settings-field:ASR API Key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        awaitImeFrame(node("settings-field:ASR API Key"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:ASR API Key"), visible = false)
        reveal("voice-asr-advanced", "语音设置").performClick()
        awaitNodeWindowFocus(node("voice-asr-service-sheet").assert(hasAnyAncestor(hasTestTag("chat-selection-sheet"))))
        ui.onNodeWithText("仅在控制台开通了不同服务时更改。", useUnmergedTree = true).assertDoesNotExist()
        val serviceNames = listOf("识别 2.0 · 按时长", "识别 2.0 · 按并发", "识别 1.0 · 按时长", "识别 1.0 · 按并发")
        serviceNames.forEach { title ->
            node("settings-radio:$title").assert(hasAnyAncestor(hasTestTag("voice-asr-service-sheet")))
                .assertHeightIsAtLeast(56.dp)
        }
        node("settings-radio:${serviceNames.first()}").assertIsSelected()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitHostWindowFocus(activity)
        node("voice-asr-service-sheet").assertDoesNotExist()
        node("voice-section-select").assertDoesNotExist()
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        reveal("voice-asr-advanced", "语音设置").performClick()
        awaitNodeWindowFocus(node("voice-asr-service-sheet"))
        node("settings-radio:${serviceNames[1]}").performClick()
        val resource = app.daiyu.android.voice.providers.DoubaoAsrProtocol.resources[1]
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.asrResource == resource
        }
        assertSelectionSheet("voice-asr-service-sheet")
        node("settings-radio:${serviceNames[1]}").assertIsSelected()
        backFromSelectionSheet()
        node("voice-asr-service-sheet").assertDoesNotExist()
        assertEquals("", fixture.controller.voiceRuntime.configuration.value?.asrKey)
        node("settings-field:ASR API Key").assertTextContains("synthetic-asr-key")
        reveal("voice-save", "语音设置").assertIsEnabled()
        val save = node("voice-save").fetchSemanticsNode().boundsInRoot
        val keyBounds = ui.onNodeWithTag("settings-field:ASR API Key-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(save.top >= keyBounds.bottom - 1f)
        assertRecognitionSaveMatchesSelectedTab()
        node("voice-save").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.asrKey == "synthetic-asr-key"
        }
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun backFromSelectionSheet() {
        val sheet = node("chat-selection-sheet")
        awaitNodeWindowFocus(sheet)
        val root = (sheet.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        dismissIme(root)
        awaitDialogImeFrame(sheet, visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        var sheetGone = false
        var hostFocused = false
        try {
            ui.waitUntil(timeoutMillis = 5000) {
                sheetGone = ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
                instrumentation.runOnMainSync { hostFocused = activity.window.decorView.hasWindowFocus() }
                sheetGone && hostFocused
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Picker BACK: sheetGone=$sheetGone hostFocused=$hostFocused", timeout)
        }
        node("chat-selection-sheet").assertDoesNotExist()
    }
    private fun assertSelectionSheet(tag: String) {
        awaitNodeWindowFocus(node(tag).assert(hasAnyAncestor(hasTestTag("chat-selection-sheet"))))
        ui.onAllNodesWithText("关闭", substring = false).assertCountEquals(0)
        ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
    }
    private fun awaitSpeechPicker() {
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodes(hasTestTag("chat-model-picker") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "模型目录读取完成"))
                .fetchSemanticsNodes().size == 1
        }
        assertSelectionSheet("voice-model-dialog")
        node("chat-model-search").assertExists()
    }

    fun testReadAloudUsesSharedPickerFiltersNonTtsAndPreservesOtherProfiles() {
        systemProfiles()
        val original = VoiceConfiguration(dictation = "DOUBAO", asrKey = "synthetic-asr",
            asrResource = app.daiyu.android.voice.providers.DoubaoAsrProtocol.resources.last(),
            duplexKey = "synthetic-duplex", duplexProvider = "saved-realtime", instructions = "saved role")
        runBlocking { fixture.controller.voiceRuntime.save(original, VoiceProfile.READ_ALOUD) }
        fixture.settings.save(fixture.settings.load().copy(model = "tts-1"))
        val snapshot = fixture.settings.modelCatalogSnapshot()
        val extras = listOf("gpt-4o", "whisper-1", "qwen3-tts-voicedesign", "tts-1-hd")
            .filter { id -> snapshot.models.none { it.id == id } }
            .map { SavedCatalogModel(it, it, CatalogModelParameters()) }
        assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(
            snapshot.providerIdentity, snapshot.revision, extras) { false })
        val catalogBefore = checkNotNull(fixture.settings.localModelCatalogSnapshot())
        val modelBefore = fixture.settings.load()
        val record = catalogBefore.models.single { it.id == "tts-1" }
        val readingBefore = fixture.controller.voiceRuntime.configuration.value
        val conversationBefore = fixture.controller.voiceRuntime.conversationConfiguration.value
        val source = SettingsModelSource(fixture.application, fixture.controller.providers)
        assertTrue(source.choices(SettingsModelProtocols).any { it.selection.record.id == "gpt-4o" })
        enter(); section("朗读")
        node("voice-cloud-enable").performClick()
        reveal("voice-model-open", "语音设置").performClick(); awaitSpeechPicker()
        listOf("gpt-4o", "whisper-1", "qwen3-tts-voicedesign").forEach {
            ui.onAllNodesWithText(it, substring = false).assertCountEquals(0)
        }
        node("chat-model-list").performScrollToKey("model:${fixture.controller.providers.snapshot().providers.single().slotId}:local:${record.settings.localIdentity}")
        node("chat-model-option:local:${record.settings.localIdentity}").assertIsNotSelected()
        backFromSelectionSheet()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversationBefore, fixture.controller.voiceRuntime.conversationConfiguration.value)
        reveal("voice-model-open", "语音设置").performClick(); awaitSpeechPicker()
        node("chat-model-option:local:${record.settings.localIdentity}").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.modelIdentity == record.settings.localIdentity
        }
        assertSelectionSheet("voice-model-dialog")
        node("chat-model-option:local:${record.settings.localIdentity}").assertIsSelected()
        backFromSelectionSheet()
        node("voice-model-dialog").assertDoesNotExist()
        val saved = checkNotNull(fixture.controller.voiceRuntime.configuration.value)
        assertEquals("tts-1", saved.model)
        assertEquals("OPENAI", saved.engine)
        assertTrue(saved.voice.isNotBlank())
        assertEquals(original.asrKey, saved.asrKey)
        assertEquals(original.asrResource, saved.asrResource)
        assertEquals(original.duplexKey, saved.duplexKey)
        assertEquals(original.duplexProvider, saved.duplexProvider)
        assertEquals(original.instructions, saved.instructions)
        assertEquals(conversationBefore, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(modelBefore, fixture.settings.load())
        assertEquals(catalogBefore, fixture.settings.localModelCatalogSnapshot())
        reveal("voice-model-open", "语音设置").performClick(); awaitSpeechPicker()
        node("chat-model-option:local:${record.settings.localIdentity}").assertIsSelected()
        backFromSelectionSheet()
        reveal("voice-voice-open", "语音设置").performClick()
        assertSelectionSheet("voice-tone-dialog")
        val voices = app.daiyu.android.voice.providers.SpeechVoices.catalog(
            app.daiyu.android.voice.providers.SpeechEngine.OPENAI, "tts-1")
        node("voice-tone-option:${saved.voice}").assertIsSelected()
        backFromSelectionSheet()
        assertEquals(saved, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testReadAloudPickerBackHidesImeBeforeCancellingSelection() {
        systemProfiles(); enter(); section("朗读")
        node("voice-cloud-enable").performClick()
        val readingBefore = fixture.controller.voiceRuntime.configuration.value
        val conversationBefore = fixture.controller.voiceRuntime.conversationConfiguration.value
        val modelBefore = fixture.settings.load()
        val catalogBefore = fixture.settings.localModelCatalogSnapshot()
        reveal("voice-model-open", "语音设置").performClick(); awaitSpeechPicker()
        node("chat-model-search").performClick()
        awaitDialogImeFrame(node("chat-selection-sheet"), visible = true)
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitDialogImeFrame(node("chat-selection-sheet"), visible = false)
        node("chat-selection-sheet").assertIsDisplayed()
        node("voice-model-dialog").assertExists()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversationBefore, fixture.controller.voiceRuntime.conversationConfiguration.value)
        backFromSelectionSheet()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversationBefore, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(modelBefore, fixture.settings.load())
        assertEquals(catalogBefore, fixture.settings.localModelCatalogSnapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testRealtimeToneAndProviderEmptyStateUseSharedSheetWithoutClose() {
        systemProfiles(); enter(); section("豆包实时通话")
        val readingBefore = fixture.controller.voiceRuntime.configuration.value
        val conversationBefore = fixture.controller.voiceRuntime.conversationConfiguration.value
        node("settings-row:提供商").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodesWithText("请先在供应商中配置豆包语音账户。", substring = false)
                .fetchSemanticsNodes().size == 1
        }
        assertSelectionSheet("voice-provider-dialog")
        backFromSelectionSheet()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        node("settings-row:选择音色").performClick()
        assertSelectionSheet("voice-tone-dialog")
        listOf("女声", "男声").forEach { ui.onNodeWithText(it, substring = false).assertExists() }
        val realtimeVoices = app.daiyu.android.voice.providers.realtimeSpeechVoices
        node("voice-tone-search").performTextReplacement("女声")
        realtimeVoices.take(2).forEach { node("voice-tone-option:${it.id}").assertExists() }
        node("voice-tone-search").performTextReplacement("男声")
        realtimeVoices.drop(2).forEach { node("voice-tone-option:${it.id}").assertExists() }
        backFromSelectionSheet()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        node("settings-row:选择音色").performClick()
        assertSelectionSheet("voice-tone-dialog")
        node("voice-tone-search").performTextReplacement("云舟")
        node("voice-tone-option:${app.daiyu.android.voice.providers.realtimeSpeechVoices[2].id}").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.duplexVoice ==
                app.daiyu.android.voice.providers.realtimeSpeechVoices[2].id
        }
        assertSelectionSheet("voice-tone-dialog")
        node("voice-tone-option:${app.daiyu.android.voice.providers.realtimeSpeechVoices[2].id}").assertIsSelected()
        backFromSelectionSheet()
        node("voice-tone-dialog").assertDoesNotExist()
        assertEquals(readingBefore?.copy(duplexVoice = app.daiyu.android.voice.providers.realtimeSpeechVoices[2].id),
            fixture.controller.voiceRuntime.configuration.value?.copy(revision = readingBefore?.revision.orEmpty()))
        assertEquals(conversationBefore, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testCloneAccountEyeAndThemeSaveStayBelowKeyAndDoNotChangePlaybackProfiles() {
        systemProfiles()
        val readingBefore = fixture.controller.voiceRuntime.configuration.value
        val conversationBefore = fixture.controller.voiceRuntime.conversationConfiguration.value
        val accountsBefore = fixture.controller.voiceRuntime.accountConfigurations.value
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account-empty").assertExists()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("voice-personal-action:SYNC").assertIsNotEnabled()
        ui.onNodeWithTag("voice-personal-action:ACCOUNT").performClick()
        val fieldTag = "settings-field:声音复刻 API Key"
        awaitNodeWindowFocus(node(fieldTag).assertIsDisplayed())
        node("voice-account-save").assertIsNotEnabled()
        node(fieldTag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        ui.onAllNodesWithText("显示密钥", substring = false).assertCountEquals(0)
        node(fieldTag).performTextReplacement("synthetic-clone-account")
        awaitImeFrame(node(fieldTag), visible = true); dismissIme()
        awaitImeFrame(node(fieldTag), visible = false)
        val container = ui.onNodeWithTag("$fieldTag-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val eye = node("settings-secret-visibility:声音复刻 API Key")
            .assertContentDescriptionEquals("显示密钥").fetchSemanticsNode().boundsInRoot
        val field = node(fieldTag).fetchSemanticsNode().boundsInRoot
        assertTrue(eye.left >= field.right - 1f)
        assertTrue(eye.right <= container.right + 1f && eye.top >= container.top - 1f && eye.bottom <= container.bottom + 1f)
        node("settings-secret-visibility:声音复刻 API Key").performClick()
        node(fieldTag).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        awaitImeFrame(node(fieldTag), visible = true)
        dismissIme()
        awaitImeFrame(node(fieldTag), visible = false)
        node("settings-secret-visibility:声音复刻 API Key").assertContentDescriptionEquals("隐藏密钥").performClick()
        node(fieldTag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        awaitImeFrame(node(fieldTag), visible = true)
        dismissIme()
        awaitImeFrame(node(fieldTag), visible = false)
        val savedContainer = ui.onNodeWithTag("$fieldTag-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val save = node("voice-account-save").assertIsEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue(save.top >= savedContainer.bottom - 1f)
        assertEquals(accountsBefore, fixture.controller.voiceRuntime.accountConfigurations.value)
        node("voice-account-save").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.accountConfigurations.value["DOUBAO"]?.key == "synthetic-clone-account"
        }
        awaitUniqueTag("page:我的声音")
        node("voice-account").assertExists()
        node("voice-account-add").assertIsDisplayed()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversationBefore, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testDoubaoWithoutAccountKeepsHomeSettingsAndBottomAddBeforeConfiguration() {
        systemProfiles()
        val accounts = fixture.controller.voiceRuntime.accountConfigurations.value
        assertNull(accounts["DOUBAO"])
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account-empty").assertExists()
        node("voice-account-configure").assertIsDisplayed()
        val add = node("voice-account-add").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = node("voice-account-menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val page = node("voice-account").fetchSemanticsNode().boundsInRoot
        assertTrue(add.top > menu.bottom)
        assertTrue(add.center.x > page.center.x && add.center.y > page.center.y)
        node("voice-account-add").performClick()
        val key = node("settings-field:声音复刻 API Key").assertIsDisplayed()
        awaitNodeWindowFocus(key)
        dismissIme(); awaitImeFrame(key, visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-account-empty").assertExists()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("voice-personal-action:SYNC").assertIsNotEnabled()
        backFromSelectionSheet()
        assertEquals(accounts, fixture.controller.voiceRuntime.accountConfigurations.value)
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testSavedDoubaoAccountEditReturnsToVoicesAndAddContainsBothOriginalFlows() {
        systemProfiles()
        runBlocking { fixture.controller.voiceRuntime.saveAccount(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-before", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("voice-personal-action:ACCOUNT").performClick()
        val key = node("settings-field:声音复刻 API Key").assertIsDisplayed()
        awaitNodeWindowFocus(key)
        key.performTextReplacement("synthetic-after")
        awaitImeFrame(key, visible = true); dismissIme(); awaitImeFrame(key, visible = false)
        node("voice-account-save").assertIsEnabled().performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.accountConfigurations.value["DOUBAO"]?.key == "synthetic-after"
        }
        awaitUniqueTag("page:我的声音")
        awaitNodeWindowFocus(node("voice-account-add").assertIsDisplayed())
        node("voice-account-add").performClick()
        ui.onNodeWithTag("voice-personal-action:CREATE").assertExists()
        ui.onNodeWithTag("voice-personal-action:IMPORT").performClick()
        val name = node("settings-field:备注名称（可空）").assertIsDisplayed()
        awaitNodeWindowFocus(name)
        dismissIme(); awaitImeFrame(name, visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitUniqueTag("page:我的声音")
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testDoubaoSavedAccountRevisionReopensHomeWithoutDependingOnSaveCallback() {
        systemProfiles()
        val original = VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-before", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)
        runBlocking { fixture.controller.voiceRuntime.saveAccount(original) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("voice-personal-action:ACCOUNT").performClick()
        val field = node("settings-field:声音复刻 API Key").assertIsDisplayed()
        awaitNodeWindowFocus(field)
        dismissIme(); awaitImeFrame(field, visible = false)
        runBlocking { fixture.controller.voiceRuntime.saveAccount(original.copy(key = "synthetic-externally-saved")) }
        awaitUniqueTag("page:我的声音")
        node("voice-account-add").assertIsDisplayed()
        node("settings-field:声音复刻 API Key").assertDoesNotExist()
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testDoubaoSubpagesDoNotStartNetworkAndBackPreservesWizard() {
        systemProfiles()
        runBlocking { fixture.controller.voiceRuntime.saveAccount(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-only", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        val account = fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO")
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account").assertExists()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("voice-personal-action:SYNC").performClick()
        awaitNodeWindowFocus(node("settings-field:AK").assertIsDisplayed())
        dismissIme()
        awaitImeFrame(node("settings-field:AK"), visible = false)
        val credentialGroup = hasAnyAncestor(hasTestTag("voice-sync-credentials-card"))
        for (key in listOf("AK", "SK")) {
            node("settings-field:$key").assert(credentialGroup)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            node("settings-secret-visibility:$key").assertContentDescriptionEquals("显示密钥")
            ui.onAllNodesWithText("显示密钥", substring = false).assertCountEquals(0)
        }
        val cardBounds = node("voice-sync-credentials-card").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("voice-sync-project-card", "voice-sync-read-card", "voice-sync-storage-card", "voice-sync-console-card")) {
            val bounds = reveal(tag, "同步音色名额").fetchSemanticsNode().boundsInRoot
            assertEquals(cardBounds.left, bounds.left, 1f)
            assertEquals(cardBounds.right, bounds.right, 1f)
        }
        for ((title, tag) in listOf(
            "当前项目" to "project", "读取我的名额" to "read",
            "打开火山访问密钥管理" to "console",
        )) {
            reveal("settings-row:$title", "同步音色名额")
                .assert(hasAnyAncestor(hasTestTag("voice-sync-$tag-card")))
        }
        reveal("voice-sync-save", "同步音色名额").assertTextContains("保存账户信息")
            .assertIsNotEnabled().assert(hasAnyAncestor(hasTestTag("voice-sync-storage-card")))
        node("settings-row:保存账户信息").assertDoesNotExist()
        for (removed in listOf("获取项目列表", "刷新项目列表", "手动填写项目（备用）", "清除已记住的信息")) {
            node("settings-row:$removed").assertDoesNotExist()
        }
        ui.onAllNodesWithText("AK / SK 与语音 API Key 不同。", substring = true).assertCountEquals(0)
        ui.onAllNodesWithText("修改后请点击保存账户信息", substring = true).assertCountEquals(0)
        reveal("settings-field:AK", "同步音色名额").performTextReplacement("synthetic-ak-draft")
        awaitImeFrame(node("settings-field:AK"), visible = true)
        dismissIme(); awaitImeFrame(node("settings-field:AK"), visible = false)
        node("settings-secret-visibility:AK").performClick()
        node("settings-field:AK").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        node("settings-field:SK").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        awaitImeFrame(node("settings-field:AK"), visible = true)
        dismissIme(); awaitImeFrame(node("settings-field:AK"), visible = false)
        node("settings-secret-visibility:AK").assertContentDescriptionEquals("隐藏密钥").performClick()
        node("settings-field:AK").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        node("settings-field:AK").assertTextContains("synthetic-ak-draft")
        awaitImeFrame(node("settings-field:AK"), visible = true)
        dismissIme(); awaitImeFrame(node("settings-field:AK"), visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitUniqueTag("page:我的声音")
        awaitNodeWindowFocus(node("voice-account-add").assertIsDisplayed())
        node("voice-account-add").performClick()
        ui.onNodeWithTag("voice-personal-action:CREATE").performClick()
        node("settings-field:声音名称").performTextReplacement("local draft")
        // Complete the pending show before reveal hides it and navigates to another editor page.
        awaitImeFrame(node("settings-field:声音名称"), visible = true)
        reveal("settings-row:选择音色名额", "制作声音").performClick()
        awaitNodeWindowFocus(node("settings-field:AK").assertIsDisplayed())
        dismissIme()
        awaitImeFrame(node("settings-field:AK"), visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitUniqueTag("page:制作声音")
        awaitNodeWindowFocus(node("settings-field:声音名称").assertIsDisplayed())
        node("settings-field:声音名称").assertTextContains("local draft")
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(account, fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO"))
        assertEquals(0, fixture.opens.get())
    }

    fun testCreationButtonsMatchOuterSettingsCardsAndKeepDraftGuards() {
        systemProfiles()
        runBlocking { fixture.controller.voiceRuntime.saveAccount(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-${UUID.randomUUID()}", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        val account = fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO")
        enter()
        val outer = node("voice-recognition-input-card").fetchSemanticsNode().boundsInRoot
        val requests = AtomicInteger()
        val root = File(instrumentation.targetContext.cacheDir, "creation-cards-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val index = File(root, "voices.json")
        val transport = AccountHttpTransport { request, _ ->
            requests.incrementAndGet()
            check(request.url.host == "open.volcengineapi.com") { "No synthesis or training" }
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            check(!body.has("audio"))
            check(body.getString("ProjectName") == "default")
            val state = body.getString("State")
            val items = JSONArray().apply { if (state == "Unknown") put(JSONObject()
                .put("SpeakerID", "S_synthetic_slot").put("State", "Unknown").put("AvailableTrainingTimes", 3)) }
            AccountResponse(200, JSONObject().put("Result", JSONObject().put("TotalCount", items.length())
                .put("Statuses", items)).toString().toByteArray())
        }
        val resources = VoiceAccountResources(PersonalVoices(index, transport),
            VoiceCatalogPreferences(VoiceCatalogSecretStore(instrumentation.targetContext)), transport)
        val owner = VoiceAccountIdentity.from(account.speech()).fingerprint
        creationCleanups += { resources.preferences.clear(owner); root.deleteRecursively() }
        runBlocking { resources.preferences.save(owner, "synthetic-ak", "synthetic-sk", "default") }
        val preferences = instrumentation.targetContext.getSharedPreferences("voice_account_control_secret_v1", 0).all.toMap()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                VoiceAccountPanel(account, resources, VoiceAccountPreviewPort { _, _ -> error("No playback") },
                    isCurrent = { it == fixture.controller.voiceRuntime.accountConfigurations.value["DOUBAO"] },
                    onSelect = { _, _ -> error("No voice selection") }, onBack = {}, stopPreviewStrictly = { true })
            }
        } }
        awaitUniqueTag("voice-account-add")
        awaitNodeWindowFocus(node("voice-account-add"))
        node("voice-account-add").performClick()
        ui.onNodeWithTag("voice-personal-action:CREATE").performClick()
        awaitUniqueTag("page:制作声音")
        for ((tag, title) in listOf(
            "name" to null, "advanced" to "高级：新建后付费音色", "sync" to "选择音色名额",
            "manual" to "备用方式：从控制台复制 ID", "next-sample" to "下一步：选择录音",
        )) {
            val card = reveal("voice-create-$tag-card", "制作声音").fetchSemanticsNode().boundsInRoot
            assertEquals(outer.left, card.left, 1f)
            assertEquals(outer.right, card.right, 1f)
            if (title != null) reveal("settings-row:$title", "制作声音")
                .assert(hasAnyAncestor(hasTestTag("voice-create-$tag-card")))
        }
        val advancedTag = "settings-row:高级：新建后付费音色"
        reveal(advancedTag, "制作声音").performClick()
        reveal("settings-switch:新建后付费音色", "制作声音").assertIsDisplayed()
        reveal(advancedTag, "制作声音").assertTextContains("高级：新建后付费音色")
        node("settings-row:收起高级设置").assertDoesNotExist()
        reveal(advancedTag, "制作声音").performClick()
        node("settings-switch:新建后付费音色").assertDoesNotExist()
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
        reveal("settings-field:声音名称", "制作声音").performTextReplacement("local creation draft")
        awaitImeFrame(node("settings-field:声音名称"), visible = true)
        reveal("settings-row:备用方式：从控制台复制 ID", "制作声音").performClick()
        reveal("settings-field:名额 ID", "制作声音").performTextReplacement("invalid")
        awaitImeFrame(node("settings-field:名额 ID"), visible = true)
        reveal("settings-row:备用方式：从控制台复制 ID", "制作声音").performClick()
        node("settings-field:名额 ID").assertDoesNotExist()
        reveal("settings-row:备用方式：从控制台复制 ID", "制作声音").performClick()
        // Check the invalid draft before reveal() dismisses the active editing session.
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
        val reopenedSlot = reveal("settings-field:名额 ID", "制作声音").assertTextContains("invalid")
        awaitNodeWindowFocus(reopenedSlot)
        reopenedSlot.performTouchInput { click() }
        ui.onAllNodesWithText("在豆包控制台进入声音复刻音色列表", substring = true).assertCountEquals(0)
        ui.onAllNodesWithText("优先使用已有或免费名额", substring = true).assertCountEquals(0)
        awaitImeFrame(reopenedSlot, visible = true)
        reopenedSlot.performTextReplacement("S_synthetic_slot")
        assertEquals(0, requests.get())
        reveal("settings-row:下一步：选择录音", "制作声音").performClick()
        awaitUniqueTag("voice-training-sample")
        assertEquals(4, requests.get())
        node("voice-training-sample").assert(hasAnyAncestor(hasTestTag("voice-create-sample-card")))
        ui.onAllNodesWithText("选择文件不会上传", substring = false).assertCountEquals(0)
        ui.onAllNodesWithText("支持 WAV / MP3 / OGG / M4A / AAC", substring = true).assertCountEquals(0)
        reveal("settings-row:下一步：确认制作", "制作声音")
            .assert(hasAnyAncestor(hasTestTag("voice-create-next-confirm-card"))).assertHasNoClickAction()
        for (tag in listOf("sample", "next-confirm")) {
            val card = reveal("voice-create-$tag-card", "制作声音").fetchSemanticsNode().boundsInRoot
            assertEquals(outer.left, card.left, 1f)
            assertEquals(outer.right, card.right, 1f)
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitUniqueTag("page:制作声音")
        reveal("settings-field:声音名称", "制作声音").assertTextContains("local creation draft")
        reveal("settings-field:名额 ID", "制作声音").assertTextContains("S_synthetic_slot")
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(account, fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO"))
        assertEquals(0, fixture.opens.get())
        assertFalse(index.exists())
        assertEquals(preferences, instrumentation.targetContext.getSharedPreferences("voice_account_control_secret_v1", 0).all)
        assertEquals(4, requests.get())
    }


    fun testImportedVoiceFallbackKeepsTitleAndDraftAcrossCollapse() {
        systemProfiles()
        runBlocking { fixture.controller.voiceRuntime.saveAccount(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-only", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) }
        val before = fixture.controller.voiceRuntime.accountConfigurations.value
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account-add").performClick()
        ui.onNodeWithTag("voice-personal-action:IMPORT").performClick()
        awaitUniqueTag("page:导入声音")
        val header = "settings-row:备用方式：从控制台复制 ID"
        node("settings-field:已有音色 ID").assertDoesNotExist()
        reveal(header, "导入声音").performClick()
        reveal("settings-field:已有音色 ID", "导入声音").performTextReplacement("S_local_draft")
        awaitImeFrame(node("settings-field:已有音色 ID"), visible = true)
        reveal(header, "导入声音").assertTextContains("备用方式：从控制台复制 ID").performClick()
        node("settings-field:已有音色 ID").assertDoesNotExist()
        reveal(header, "导入声音").performClick()
        reveal("settings-field:已有音色 ID", "导入声音").assertTextContains("S_local_draft")
        assertEquals(before, fixture.controller.voiceRuntime.accountConfigurations.value)
        assertEquals(0, fixture.opens.get())
    }

}
