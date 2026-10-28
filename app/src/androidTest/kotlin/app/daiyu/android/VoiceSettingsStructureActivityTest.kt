package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
    private var voicePreferencesSnapshot: Map<String, *>? = null
    override fun cleanupAfterLegacy() {
        try {
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
        node("settings-switch:豆包语音识别").assertExists()
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
        node("settings-radio:本机识别").assertExists()
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
    fun testRecognitionHomeOmitsExtraOfflineEntryAndKeepsAlignedCards() {
        systemProfiles(); enter()
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
        node("settings-radio:本机识别").assertExists()
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
        val serviceNames = listOf("识别 2.0 · 按时长", "识别 2.0 · 按并发", "识别 1.0 · 按时长", "识别 1.0 · 按并发")
        serviceNames.forEach { title ->
            node("settings-radio:$title").assert(hasAnyAncestor(hasTestTag("voice-asr-service-sheet")))
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
        awaitHostWindowFocus(activity)
        node("voice-asr-service-sheet").assertDoesNotExist()
        assertEquals("", fixture.controller.voiceRuntime.configuration.value?.asrKey)
        node("settings-field:ASR API Key").assertTextContains("synthetic-asr-key")
        reveal("voice-save", "语音设置").assertIsEnabled()
        val save = node("voice-save").fetchSemanticsNode().boundsInRoot
        val keyBounds = ui.onNodeWithTag("settings-field:ASR API Key-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(save.top >= keyBounds.bottom - 1f)
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
        awaitHostWindowFocus(activity)
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
        node("settings-radio:${voices.first { it.id == saved.voice }.name}").assertIsSelected()
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
        listOf("Vivi", "小何", "云舟", "小天").forEach { node("settings-radio:$it").assertExists() }
        backFromSelectionSheet()
        assertEquals(readingBefore, fixture.controller.voiceRuntime.configuration.value)
        node("settings-row:选择音色").performClick()
        assertSelectionSheet("voice-tone-dialog")
        node("settings-radio:云舟").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.duplexVoice ==
                app.daiyu.android.voice.providers.realtimeSpeechVoices[2].id
        }
        awaitHostWindowFocus(activity)
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
            "当前项目" to "project", "读取我的名额" to "read", "保存账户信息" to "storage",
            "打开火山访问密钥管理" to "console",
        )) {
            reveal("settings-row:$title", "同步音色名额")
                .assert(hasAnyAncestor(hasTestTag("voice-sync-$tag-card")))
        }
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
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-only", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        val account = fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO")
        enter()
        val outer = node("voice-recognition-input-card").fetchSemanticsNode().boundsInRoot
        section("我的声音"); node("voice-account-open").performClick()
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
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
        reveal("settings-field:声音名称", "制作声音").performTextReplacement("local creation draft")
        awaitImeFrame(node("settings-field:声音名称"), visible = true)
        reveal("settings-row:备用方式：从控制台复制 ID", "制作声音").performClick()
        reveal("settings-field:名额 ID", "制作声音").performTextReplacement("invalid")
        ui.onAllNodesWithText("在豆包控制台进入声音复刻音色列表", substring = true).assertCountEquals(0)
        ui.onAllNodesWithText("优先使用已有或免费名额", substring = true).assertCountEquals(0)
        awaitImeFrame(node("settings-field:名额 ID"), visible = true)
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
        val slotField = reveal("settings-field:名额 ID", "制作声音")
        slotField.performTouchInput { click() }
        awaitImeFrame(slotField, visible = true)
        slotField.performTextReplacement("S_synthetic_slot")
        reveal("settings-row:下一步：选择录音", "制作声音").performClick()
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
    }

}
