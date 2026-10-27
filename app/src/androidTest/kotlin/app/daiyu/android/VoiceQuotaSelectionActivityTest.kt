package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.voice.account.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Isolated local quota fixtures; all transport, playback and selection callbacks fail if invoked. */
class VoiceQuotaSelectionActivityTest : WiredActivityTestCase() {
    private val requests = AtomicInteger()
    private val roots = mutableListOf<File>()
    private lateinit var index: File
    private lateinit var beforeIndex: String
    private lateinit var beforePreferences: Map<String, *>
    private lateinit var resources: VoiceAccountResources
    private lateinit var frozen: VoiceConfiguration

    private fun mount(available: Boolean, unavailable: Boolean = false, secondAvailable: Boolean = false) {
        frozen = VoiceConfiguration(engine = "DOUBAO", provider = "quota-fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-${UUID.randomUUID()}",
            model = "seed-tts-2.0", voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)
        val account = VoiceAccountIdentity.from(frozen.speech()).fingerprint
        val root = File(instrumentation.targetContext.cacheDir, "quota-picker-${UUID.randomUUID()}").apply { check(mkdirs()) }
        roots += root
        index = File(root, "voices.json")
        fun slot(id: String, owner: String, remaining: Int) = JSONObject().put("id", id).put("name", id)
            .put("account", owner).put("catalogState", "Unknown").put("remaining", remaining)
        index.writeText(JSONArray().apply {
            if (available) put(slot("S_available", account, 3))
            if (unavailable) put(slot("S_exhausted", account, 0))
            if (secondAvailable) put(slot("S_second", account, 3))
            put(slot("S_other_account", "0".repeat(64), 3))
        }.toString())
        beforeIndex = index.readText()
        val transport = AccountHttpTransport { _, _ -> requests.incrementAndGet(); error("No implicit request") }
        resources = VoiceAccountResources(PersonalVoices(index, transport),
            VoiceCatalogPreferences(VoiceCatalogSecretStore(instrumentation.targetContext)), transport)
        beforePreferences = instrumentation.targetContext
            .getSharedPreferences("voice_account_control_secret_v1", 0).all.toMap()
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { key(visit) {
                VoiceAccountPanel(frozen, resources, VoiceAccountPreviewPort { _, _ -> error("No playback") },
                    isCurrent = { it == frozen }, onSelect = { _, _ -> error("No TTS selection") }, onBack = {},
                    stopPreviewStrictly = { true })
            } }
        } }
        awaitUniqueTag("voice-account-add")
        awaitNodeWindowFocus(node("voice-account-add"))
        node("voice-account-add").performClick()
        node("voice-personal-action:CREATE").performClick()
        awaitUniqueTag("page:制作声音")
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("settings-row:选择音色名额") and hasClickAction())
                .fetchSemanticsNodes().size == 1
        }
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
        node("voice-create-slot:S_available").assertIsNotSelected()
        node("voice-create-slot:S_second").assertIsNotSelected().performClick()
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

    override fun cleanupAfterLegacy() {
        try {
            mainThread { activity.setContent {} }
            roots.forEach { it.deleteRecursively() }
        } finally { super.cleanupAfterLegacy() }
    }
}
