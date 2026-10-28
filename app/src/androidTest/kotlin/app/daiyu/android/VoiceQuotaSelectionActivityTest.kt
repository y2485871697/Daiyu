package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.voice.account.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Local quota fixtures; transport and playback require explicit per-test opt-in. */
class VoiceQuotaSelectionActivityTest : WiredActivityTestCase() {
    private val requests = AtomicInteger()
    private val plays = AtomicInteger()
    private val stops = AtomicInteger()
    private val roots = mutableListOf<File>()
    private lateinit var index: File
    private lateinit var beforeIndex: String
    private lateinit var beforePreferences: Map<String, *>
    private lateinit var resources: VoiceAccountResources
    private lateinit var frozen: VoiceConfiguration

    private fun mount(available: Boolean, unavailable: Boolean = false, secondAvailable: Boolean = false,
        flow: String = "CREATE", ready: Boolean = false, uncertain: Boolean = false, queryStatus: Int? = null) {
        frozen = VoiceConfiguration(engine = "DOUBAO", provider = "quota-fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-${UUID.randomUUID()}",
            model = "seed-tts-2.0", voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)
        val account = VoiceAccountIdentity.from(frozen.speech()).fingerprint
        val root = File(instrumentation.targetContext.cacheDir, "quota-picker-${UUID.randomUUID()}").apply { check(mkdirs()) }
        roots += root
        index = File(root, "voices.json")
        fun slot(id: String, owner: String, remaining: Int) = JSONObject().put("id", id).put("name", id)
            .put("account", owner).put("catalogState", if (ready || flow == "IMPORT") "Success" else "Unknown")
            .put("status", if (ready || flow == "IMPORT") 2 else -1).put("remaining", remaining)
            .put("models", if (ready) JSONArray().put(4) else JSONArray())
            .put("demo", if (ready) "https://preview.invalid/demo.mp3" else "").put("uncertain", uncertain)
        index.writeText(JSONArray().apply {
            if (available) put(slot("S_available", account, 3))
            if (unavailable) put(slot("S_exhausted", account, 0))
            if (secondAvailable) put(slot("S_second", account, 3))
            put(slot("S_other_account", "0".repeat(64), 3))
        }.toString())
        beforeIndex = index.readText()
        val transport = AccountHttpTransport { _, _ ->
            requests.incrementAndGet()
            check(queryStatus != null) { "No implicit request" }
            AccountResponse(200, JSONObject().put("code", 0).put("status", queryStatus)
                .put("speaker_status", JSONArray()).toString().toByteArray())
        }
        resources = VoiceAccountResources(PersonalVoices(index, transport),
            VoiceCatalogPreferences(VoiceCatalogSecretStore(instrumentation.targetContext)), transport)
        beforePreferences = instrumentation.targetContext
            .getSharedPreferences("voice_account_control_secret_v1", 0).all.toMap()
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { key(visit) {
                VoiceAccountPanel(frozen, resources, VoiceAccountPreviewPort { _, listener ->
                    plays.incrementAndGet(); check(ready) { "No playback" }; listener.started()
                    VoiceAccountPreviewPort.Lease { stops.incrementAndGet() }
                },
                    isCurrent = { it == frozen }, onSelect = { _, _ -> error("No TTS selection") }, onBack = {},
                    stopPreviewStrictly = { true })
            } }
        } }
        awaitUniqueTag("voice-account-add")
        awaitNodeWindowFocus(node("voice-account-add"))
        if (flow != "LIST") {
            node("voice-account-add").performClick()
            node("voice-personal-action:$flow").performClick()
            val title = if (flow == "IMPORT") "导入声音" else "制作声音"
            val row = if (flow == "IMPORT") "选择我的音色名额" else "选择音色名额"
            awaitUniqueTag("page:$title")
            ui.waitUntil(5_000) {
                ui.onAllNodes(hasTestTag("settings-row:$row") and hasClickAction())
                    .fetchSemanticsNodes().size == 1
            }
        } else awaitUniqueTag("voice-menu:S_available")
    }

    private fun enterDraftName(value: String) {
        reveal("settings-field:声音名称", "制作声音").performTextReplacement(value)
        awaitImeFrame(node("settings-field:声音名称"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:声音名称"), visible = false)
    }
    private fun openPicker() {
        reveal("settings-row:选择音色名额", "制作声音").performClick()
        awaitUniqueTag("voice-create-slot-picker")
        awaitNodeWindowFocus(node("voice-create-slot-picker"))
    }
    private fun awaitPickerClosed() {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("voice-create-slot-picker").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun assertNoSideEffects() {
        assertEquals(beforeIndex, index.readText())
        assertEquals(beforePreferences, instrumentation.targetContext
            .getSharedPreferences("voice_account_control_secret_v1", 0).all)
        assertEquals(0, requests.get())
        assertEquals(0, plays.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testSelectionUsesSharedSheetUpdatesDraftAndKeepsDisabledAndForeignQuotasOut() {
        mount(available = true, unavailable = true, secondAvailable = true)
        enterDraftName("draft name")
        openPicker()
        node("voice-create-slot-picker").assert(hasAnyAncestor(hasTestTag("chat-selection-sheet")))
        node("voice-create-slot:S_other_account").assertDoesNotExist()
        node("voice-create-slot:S_exhausted").assertIsNotEnabled().performTouchInput { click() }
        node("voice-create-slot-picker").assertExists()
        node("voice-create-slot:S_exhausted").assertIsNotSelected()
        val firstCard = node("voice-create-slot:S_available").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val nextCard = node("voice-create-slot:S_exhausted").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val lastCard = node("voice-create-slot:S_second").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(nextCard.top > firstCard.bottom)
        assertTrue(lastCard.top > nextCard.bottom)
        node("voice-create-slot:S_second").performClick()
        awaitPickerClosed()
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasClickAction()
        reveal("settings-row:选择音色名额", "制作声音").assertTextContains("S_second", substring = true)
        openPicker()
        node("voice-create-slot:S_second").assertIsSelected()
        node("voice-create-slot:S_available").assertIsNotSelected()
        ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        awaitUniqueTag("page:制作声音")
        reveal("settings-field:声音名称", "制作声音").assertTextContains("draft name")
        openPicker()
        node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
        awaitPickerClosed()
        awaitUniqueTag("page:制作声音")
        reveal("settings-field:声音名称", "制作声音").assertTextContains("draft name")
        reveal("settings-row:备用方式：从控制台复制 ID", "制作声音").performClick()
        reveal("settings-field:名额 ID", "制作声音").assertTextContains("S_second")
        assertNoSideEffects()
    }

    fun testPostpaidDoesNotHideExistingQuotasAndSelectionSwitchesBackToPrepaid() {
        mount(available = true)
        reveal("settings-row:高级：新建后付费音色", "制作声音").performClick()
        reveal("settings-switch:新建后付费音色", "制作声音").performClick()
        node("settings-switch:新建后付费音色").assertIsOn()
        openPicker()
        node("voice-create-slot:S_available").assertIsNotSelected().performClick()
        awaitPickerClosed()
        reveal("settings-switch:新建后付费音色", "制作声音").assertIsOff()
        assertNoSideEffects()
    }

    fun testEmptyAndExhaustedQuotasNavigateToSyncWithoutReadingOrSavingAndPreserveDraft() {
        for (unavailable in listOf(false, true)) {
            mount(available = false, unavailable = unavailable)
            enterDraftName("keep draft")
            reveal("settings-row:选择音色名额", "制作声音").performClick()
            awaitUniqueTag("page:同步音色名额")
            node("voice-create-slot-picker").assertDoesNotExist()
            reveal("settings-row:读取我的名额", "同步音色名额").assertExists()
            assertNoSideEffects()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitUniqueTag("page:制作声音")
            reveal("settings-field:声音名称", "制作声音").assertTextContains("keep draft")
            reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
            assertNoSideEffects()
        }
    }

    fun testImportUsesCardsAndKeepsNameAndExistingIdDraftWithoutNetworkOrSave() {
        mount(available = true, unavailable = true, secondAvailable = true, flow = "IMPORT")
        reveal("settings-field:备注名称（可空）", "导入声音").performTextReplacement("import draft")
        awaitImeFrame(node("settings-field:备注名称（可空）"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:备注名称（可空）"), visible = false)
        reveal("settings-row:选择我的音色名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        node("voice-import-slot-picker").assert(hasAnyAncestor(hasTestTag("chat-selection-sheet")))
        node("voice-import-slot:S_other_account").assertDoesNotExist()
        node("voice-import-slot:S_exhausted").assertIsEnabled()
        node("voice-import-slot:S_second").assertHeightIsAtLeast(48.dp).performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot-picker").fetchSemanticsNodes().isEmpty() }
        reveal("settings-row:选择我的音色名额", "导入声音").assertTextContains("S_second", substring = true).performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        node("voice-import-slot:S_second").assertIsSelected()
        node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot-picker").fetchSemanticsNodes().isEmpty() }
        reveal("settings-field:备注名称（可空）", "导入声音").assertTextContains("import draft")
        reveal("settings-row:备用方式：从控制台复制 ID", "导入声音").performClick()
        reveal("settings-field:已有音色 ID", "导入声音").assertTextContains("S_second")
        assertNoSideEffects()
    }

    fun testImportEmptyOrOnlyForeignQuotasNavigateToSyncAndReturnToDraft() {
        mount(available = false, flow = "IMPORT")
        reveal("settings-field:备注名称（可空）", "导入声音").performTextReplacement("keep import")
        awaitImeFrame(node("settings-field:备注名称（可空）"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:备注名称（可空）"), visible = false)
        reveal("settings-row:选择我的音色名额", "导入声音").performClick()
        awaitUniqueTag("page:同步音色名额")
        node("voice-import-slot-picker").assertDoesNotExist()
        reveal("settings-row:读取我的名额", "同步音色名额").assertExists()
        assertNoSideEffects()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("page:导入声音")
        reveal("settings-field:备注名称（可空）", "导入声音").assertTextContains("keep import")
        assertNoSideEffects()
    }

    fun testRecordHasIconActionsTwoMenuChoicesAndQueryUpdatesSummary() {
        mount(available = true, flow = "LIST", ready = true, queryStatus = 1)
        val play = node("voice-preview:S_available").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .assertContentDescriptionEquals("播放试听").fetchSemanticsNode().boundsInRoot
        val menu = node("voice-menu:S_available").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .assertContentDescriptionEquals("音色菜单").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.left >= play.right - 1f)
        ui.onAllNodesWithText("试听", substring = false).assertCountEquals(0)
        ui.onAllNodesWithText("更多", substring = false).assertCountEquals(0)
        ui.onNode(
            hasText("训练成功", substring = true) and hasAnyAncestor(hasTestTag("settings-row:S_available")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertNoSideEffects()
        node("voice-menu:S_available").performClick()
        awaitUniqueTag("voice-choice-dialog")
        awaitNodeWindowFocus(node("voice-choice-dialog"))
        node("settings-radio:查看状态").assertIsEnabled()
        node("settings-radio:移除本机记录").assertIsEnabled()
        ui.onAllNodes(hasAnyAncestor(hasTestTag("voice-choice-dialog")) and hasClickAction()).assertCountEquals(2)
        node("settings-radio:用于朗读").assertDoesNotExist()
        node("settings-radio:错误详情").assertDoesNotExist()
        node("settings-radio:查看状态").performClick()
        ui.onNodeWithText("确认").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { requests.get() == 1 && resources.voices.state.value.any { it.id == "S_available" && it.status == 1 } }
        ui.onNode(
            hasText("训练中", substring = true) and hasAnyAncestor(hasTestTag("settings-row:S_available")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        node("voice-preview:S_available").assertContentDescriptionEquals("播放试听")
            .performTouchInput { click() }
        assertEquals(1, requests.get()); assertEquals(0, plays.get()); assertEquals(0, fixture.opens.get())
        assertEquals(beforePreferences, instrumentation.targetContext.getSharedPreferences("voice_account_control_secret_v1", 0).all)
    }

    fun testPlayIconStopsExistingPreviewAndUncertainRecordCannotBeRemoved() {
        mount(available = true, flow = "LIST", ready = true)
        node("voice-preview:S_available").performClick()
        ui.waitUntil(5_000) { plays.get() == 1 }
        node("voice-preview:S_available").assertContentDescriptionEquals("停止试听").performClick()
        ui.waitUntil(5_000) { stops.get() == 1 }
        node("voice-preview:S_available").assertContentDescriptionEquals("播放试听")
        assertEquals(beforeIndex, index.readText()); assertEquals(0, requests.get())
        mount(available = true, flow = "LIST", ready = true, uncertain = true)
        node("voice-preview:S_available").assertContentDescriptionEquals("播放试听")
            .performTouchInput { click() }
        node("voice-menu:S_available").performClick()
        awaitUniqueTag("voice-choice-dialog")
        awaitNodeWindowFocus(node("voice-choice-dialog"))
        node("settings-radio:查看状态").assertIsEnabled()
        node("settings-radio:移除本机记录").assertIsNotEnabled().performTouchInput { click() }
        node("voice-choice-dialog").assertExists()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertEquals(beforeIndex, index.readText()); assertEquals(0, requests.get())
        assertEquals(1, plays.get()); assertEquals(1, stops.get())
    }

    override fun cleanupAfterLegacy() {
        try {
            roots.forEach { it.deleteRecursively() }
        } finally { super.cleanupAfterLegacy() }
    }
}
