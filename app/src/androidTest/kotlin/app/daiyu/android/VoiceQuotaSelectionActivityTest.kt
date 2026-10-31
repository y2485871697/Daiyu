package app.daiyu.android

import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
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
import okio.Buffer

/** Local quota fixtures; transport and playback require explicit per-test opt-in. */
class VoiceQuotaSelectionActivityTest : WiredActivityTestCase() {
    private val requests = AtomicInteger()
    private val plays = AtomicInteger()
    private val stops = AtomicInteger()
    private val roots = mutableListOf<File>()
    private val importAccountCleanup = mutableListOf<() -> Unit>()
    private var importQueryFails = false
    private var importVoiceQueryFails = false
    private var importAliasFails = false
    private var remoteImportAlias = "remote name"
    private var creationQueryFails = false
    private var creationRemoteIds = listOf("S_available")
    private var creationRemaining = 3
    private var creationRemoteState = "Unknown"
    private lateinit var index: File
    private lateinit var beforeIndex: String
    private lateinit var beforePreferences: Map<String, *>
    private lateinit var resources: VoiceAccountResources
    private lateinit var frozen: VoiceConfiguration

    private fun mount(available: Boolean, unavailable: Boolean = false, secondAvailable: Boolean = false,
        flow: String = "CREATE", ready: Boolean = false, uncertain: Boolean = false, queryStatus: Int? = null,
        active: Boolean = false, cloned: Boolean = false, verifySlot: Boolean = false, namePending: Boolean = false) {
        frozen = VoiceConfiguration(engine = "DOUBAO", provider = "quota-fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-${UUID.randomUUID()}",
            model = "seed-tts-2.0", voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)
        val account = VoiceAccountIdentity.from(frozen.speech()).fingerprint
        val root = File(instrumentation.targetContext.cacheDir, "quota-picker-${UUID.randomUUID()}").apply { check(mkdirs()) }
        roots += root
        index = File(root, "voices.json")
        fun slot(id: String, owner: String, remaining: Int) = JSONObject().put("id", id).put("name", id)
            .put("account", owner).put("catalogState", if (active) "Active" else if (ready || flow == "IMPORT") "Success" else if (namePending) "Training" else "Unknown")
            .put("status", if (active) 4 else if (ready || flow == "IMPORT") 2 else if (namePending) 1 else -1).put("remaining", remaining)
            .put("models", if (ready) JSONArray().put(4) else JSONArray())
            .put("demo", if (ready) "https://preview.invalid/demo.mp3" else "").put("uncertain", uncertain)
            .put("nameSyncPending", namePending)
        index.writeText(JSONArray().apply {
            if (available) put(slot("S_available", account, 3))
            if (unavailable) put(slot("S_exhausted", account, 0))
            if (secondAvailable) put(slot("S_second", account, 3))
            if (cloned) put(slot("S_cloned", account, 3).put("name", "已复刻声音")
                .put("status", 2).put("catalogState", "Success"))
            put(slot("S_other_account", "0".repeat(64), 3))
        }.toString())
        beforeIndex = index.readText()
        val transport = AccountHttpTransport { request, _ ->
            requests.incrementAndGet()
            val result = if (flow == "IMPORT") {
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                check(!body.has("audio")) { "Import must not upload" }
                if (request.url.host == "open.volcengineapi.com") {
                    check(!importQueryFails) { "Synthetic import query failure" }
                    when (request.url.queryParameter("Action")) {
                        "ResourcePacksStatus" -> JSONObject().put("Result", JSONObject().put("TotalCount", 1)
                            .put("Packs", JSONArray().put(JSONObject().put("TrainID", "S_available")
                                .put("InstanceNumber", "import-resource").put("Alias", remoteImportAlias))))
                        "AliasResourcePack" -> {
                            check(!importAliasFails) { "Synthetic alias failure" }
                            check(body.getString("TrainID") == "S_available")
                            check(body.getString("InstanceNumber") == "import-resource")
                            remoteImportAlias = body.getString("Alias")
                            JSONObject().put("Result", JSONObject().put("TrainID", "S_available")
                                .put("InstanceNumber", "import-resource").put("Alias", remoteImportAlias))
                        }
                        else -> {
                            val state = body.getString("State")
                            val ids = when (state) {
                                "Unknown" -> listOf("S_unused")
                                "Training" -> listOf("S_training")
                                "Success" -> buildList {
                                    if (available) add("S_available")
                                    if (unavailable) add("S_exhausted")
                                    if (secondAvailable) add("S_second")
                                }
                                else -> emptyList()
                            }
                            JSONObject().put("Result", JSONObject().put("TotalCount", ids.size)
                                .put("Statuses", JSONArray().apply { ids.forEach {
                                    put(JSONObject().put("SpeakerID", it).put("State", state).put("Alias", it)
                                        .put("AvailableTrainingTimes", if (it == "S_exhausted") 0 else 3))
                                } }))
                        }
                    }
                } else {
                    check(request.url.encodedPath.endsWith("/get_voice")) { "Import must not train" }
                    if (importVoiceQueryFails) throw PersonalVoiceQueryFailure(
                        "音色查询权限不足：HTTP 403，请核对语音 API Key 与音色所属项目。")
                    JSONObject().put("speaker_id", body.getString("speaker_id")).put("status", 2)
                        .put("available_training_times", if (body.getString("speaker_id") == "S_exhausted") 0 else 3)
                        .put("speaker_status", JSONArray().put(JSONObject().put("model_type", 4)))
                }
            } else if (verifySlot) {
                check(!creationQueryFails) { "Synthetic creation query failure" }
                check(request.url.host == "open.volcengineapi.com") { "Validation must not upload or train" }
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                check(!body.has("audio"))
                val state = body.getString("State")
                val ids = if (state == creationRemoteState) creationRemoteIds else emptyList()
                JSONObject().put("Result", JSONObject().put("TotalCount", ids.size)
                    .put("Statuses", JSONArray().apply { ids.forEach {
                        put(JSONObject().put("SpeakerID", it).put("State", state)
                            .put("AvailableTrainingTimes", creationRemaining))
                    } }))
            } else {
                check(queryStatus != null) { "No implicit request" }
                JSONObject().put("code", 0).put("status", queryStatus).put("speaker_status", JSONArray())
            }
            AccountResponse(200, result.toString().toByteArray())
        }
        resources = VoiceAccountResources(PersonalVoices(index, transport),
            VoiceCatalogPreferences(VoiceCatalogSecretStore(instrumentation.targetContext)), transport)
        if (flow == "IMPORT" || verifySlot) {
            val preferences = resources.preferences
            preferences.save(account, "synthetic-import-ak", "synthetic-import-sk", "default")
            importAccountCleanup += { preferences.clear(account) }
        }
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
            val row = if (flow == "IMPORT") "选取我的名额" else "选择音色名额"
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
    private fun assertNoSideEffects(expectedRequests: Int = 0) {
        assertEquals(beforeIndex, index.readText())
        assertEquals(beforePreferences, instrumentation.targetContext
            .getSharedPreferences("voice_account_control_secret_v1", 0).all)
        assertEquals(expectedRequests, requests.get())
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
        node("voice-create-slot:S_second").assertIsSelected()
        node("voice-create-slot:S_available").assertIsNotSelected()
        ui.mainClock.advanceTimeBy(3_000)
        node("voice-create-slot-picker").assertIsDisplayed()
        node("voice-create-slot:S_available").performClick().assertIsSelected()
        node("voice-create-slot:S_second").assertIsNotSelected()
        node("voice-create-slot:S_second").performClick().assertIsSelected()
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
        node("voice-create-manual-card").assertDoesNotExist()
        reveal("settings-row:高级：新建后付费音色", "制作声音").assertExists()
        assertNoSideEffects()
    }

    fun testPostpaidHidesSelectionAndFallbackAndNeedsNoSlotQuery() {
        mount(available = true)
        enterDraftName("postpaid voice")
        openPicker()
        node("voice-create-slot:S_available").performClick()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        awaitPickerClosed()
        reveal("settings-row:高级：新建后付费音色", "制作声音").performClick()
        reveal("settings-switch:新建后付费音色", "制作声音").performClick()
        node("settings-switch:新建后付费音色").assertIsOn()
        node("voice-create-sync-card").assertDoesNotExist()
        node("voice-create-manual-card").assertDoesNotExist()
        reveal("settings-row:下一步：选择录音", "制作声音").performClick()
        awaitUniqueTag("voice-training-sample")
        assertNoSideEffects()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("voice-create-name-card")
        reveal("settings-switch:新建后付费音色", "制作声音").performClick()
        reveal("voice-create-sync-card", "制作声音").assertExists()
        reveal("voice-create-manual-card", "制作声音").assertExists()
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
        assertNoSideEffects()
    }

    private fun enterManualSlot(id: String) {
        reveal("settings-row:备用方式：从控制台复制 ID", "制作声音").performClick()
        reveal("settings-field:名额 ID", "制作声音").performTextReplacement(id)
        dismissIme()
    }

    fun testManualInputHidesOtherSourcesAndClearingRestoresThemWithoutCopyingSelection() {
        mount(available = true)
        enterManualSlot("S_manual")
        node("voice-create-advanced-card").assertDoesNotExist()
        node("voice-create-sync-card").assertDoesNotExist()
        reveal("settings-field:名额 ID", "制作声音").assertTextContains("S_manual")
            .performTextReplacement("")
        dismissIme()
        reveal("voice-create-advanced-card", "制作声音").assertExists()
        reveal("voice-create-sync-card", "制作声音").assertExists()
        openPicker()
        node("voice-create-slot:S_available").performClick()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        awaitPickerClosed()
        node("voice-create-manual-card").assertDoesNotExist()
        assertNoSideEffects()
    }

    fun testManualSlotIsVerifiedBeforeRecordingWithoutUploadOrSave() {
        mount(available = false, verifySlot = true)
        enterDraftName("manual voice")
        enterManualSlot("S_available")
        reveal("settings-row:下一步：选择录音", "制作声音").performClick()
        awaitUniqueTag("voice-training-sample")
        assertNoSideEffects(expectedRequests = 4)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("voice-create-name-card")
        node("voice-create-sync-card").assertDoesNotExist()
        reveal("settings-field:名额 ID", "制作声音").assertTextContains("S_available")
        assertNoSideEffects(expectedRequests = 4)
    }

    fun testSelectedClonedSlotPassesFreshVerificationAndKeepsItsId() {
        creationRemoteState = "Success"
        mount(available = true, ready = true, verifySlot = true)
        enterDraftName("overwrite voice")
        openPicker()
        node("voice-create-slot:S_available").performClick()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        awaitPickerClosed()
        reveal("settings-row:下一步：选择录音", "制作声音").performClick()
        awaitUniqueTag("voice-training-sample")
        assertNoSideEffects(expectedRequests = 4)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("voice-create-name-card")
        reveal("settings-row:选择音色名额", "制作声音").assertTextContains("S_available", substring = true)
        node("voice-create-manual-card").assertDoesNotExist()
        assertNoSideEffects(expectedRequests = 4)
    }

    private fun awaitCreationRejected(message: String) {
        val toast = instrumentation.uiAutomation.executeAndWaitForEvent(
            { reveal("settings-row:下一步：选择录音", "制作声音").performClick() },
            { it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && it.text.any { text -> text.toString() == message } },
            5_000)
        try { assertTrue(toast.text.any { it.toString() == message }) }
        finally { toast.recycle() }
        node("voice-training-sample").assertDoesNotExist()
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasClickAction()
    }

    fun testMissingManualSlotToastsAndKeepsDraftOnFirstStep() {
        mount(available = true, verifySlot = true)
        enterDraftName("missing manual")
        enterManualSlot("S_missing")
        awaitCreationRejected("没有该名额 ID")
        reveal("settings-field:名额 ID", "制作声音").assertTextContains("S_missing")
        assertNoSideEffects(expectedRequests = 4)
    }

    fun testSelectedSlotRemovedFromCloudIsNotTrustedFromLocalCache() {
        creationRemoteIds = emptyList()
        mount(available = true, verifySlot = true)
        enterDraftName("stale selection")
        openPicker()
        node("voice-create-slot:S_available").performClick()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        awaitPickerClosed()
        awaitCreationRejected("没有该名额 ID")
        reveal("settings-row:选择音色名额", "制作声音").assertTextContains("S_available", substring = true)
        assertNoSideEffects(expectedRequests = 4)
    }

    fun testCloudExhaustionRejectsWithoutTreatingSlotAsMissing() {
        creationRemaining = 0
        mount(available = true, verifySlot = true)
        enterDraftName("exhausted")
        enterManualSlot("S_available")
        awaitCreationRejected("该名额正在制作、已锁定或次数已用完")
        assertNoSideEffects(expectedRequests = 4)
    }

    fun testCloudFailureDoesNotAdvanceOrClaimTheIdIsMissing() {
        creationQueryFails = true
        mount(available = true, verifySlot = true)
        enterDraftName("query failure")
        enterManualSlot("S_available")
        reveal("settings-row:下一步：选择录音", "制作声音").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("名额校验失败", substring = true).fetchSemanticsNodes().size == 1 }
        node("voice-training-sample").assertDoesNotExist()
        ui.onAllNodesWithText("没有该名额 ID").assertCountEquals(0)
        assertNoSideEffects(expectedRequests = 1)
    }

    fun testEmptyQuotasNavigateToSyncWithoutReadingOrSavingAndPreserveDraft() {
        mount(available = false)
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

    fun testOnlyExhaustedCloneStillAppearsButCannotBeSelectedOrSubmitted() {
        mount(available = false, unavailable = true, ready = true)
        enterDraftName("keep draft")
        openPicker()
        node("voice-create-slot:S_exhausted").assertIsNotEnabled().performTouchInput { click() }
        node("voice-create-slot-picker").assertExists()
        node("voice-create-slot:S_exhausted").assertIsNotSelected()
        node("voice-create-slot:S_other_account").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasNoClickAction()
        assertNoSideEffects()
    }

    fun testExistingSuccessfulCloneUsesOriginalQuotaWithoutSelectionSideEffects() {
        mount(available = true, ready = true)
        enterDraftName("replacement voice")
        openPicker()
        val card = node("voice-create-slot:S_available").assertIsEnabled()
        card.assertTextContains("剩余 3 次", substring = true)
        card.assertTextContains("再次制作消耗本名额次数并覆盖已有声音", substring = true)
        card.performClick()
        awaitPickerClosed()
        reveal("settings-row:选择音色名额", "制作声音").assertTextContains("S_available", substring = true)
        reveal("settings-row:下一步：选择录音", "制作声音").assertHasClickAction()
        reveal("settings-field:声音名称", "制作声音").assertTextContains("replacement voice")
        assertNoSideEffects()
    }

    fun testClonedVoiceAppearsBeforeUnusedQuotasAndActivatedVoiceRemainsVisibleButLocked() {
        mount(available = true, cloned = true)
        openPicker()
        val cloned = node("voice-create-slot:S_cloned").assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val unused = node("voice-create-slot:S_available").assertIsEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue(cloned.bottom < unused.top)
        assertNoSideEffects()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPickerClosed()
        mount(available = true, ready = true, active = true)
        openPicker()
        node("voice-create-slot:S_available").assertIsNotEnabled()
            .assertTextContains("已激活，无法再次制作", substring = true).performTouchInput { click() }
        node("voice-create-slot-picker").assertExists()
        node("voice-create-slot:S_available").assertIsNotSelected()
        assertNoSideEffects()
    }

    fun testImportUsesFreshCloudCardsAndKeepsNameAndExistingIdDraftWithoutSave() {
        mount(available = true, unavailable = true, secondAvailable = true, flow = "IMPORT")
        reveal("settings-field:备注名称（可空）", "导入声音").performTextReplacement("import draft")
        awaitImeFrame(node("settings-field:备注名称（可空）"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:备注名称（可空）"), visible = false)
        reveal("settings-row:选取我的名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        node("voice-import-slot-picker").assert(hasAnyAncestor(hasTestTag("chat-selection-sheet")))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot:S_second").fetchSemanticsNodes().size == 1 }
        node("voice-import-slot:S_other_account").assertDoesNotExist()
        node("voice-import-slot:S_unused").assertDoesNotExist()
        node("voice-import-slot:S_training").assertDoesNotExist()
        node("voice-import-slot:S_exhausted").assertIsEnabled()
        node("voice-import-slot:S_second").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        ui.mainClock.advanceTimeBy(3_000)
        node("voice-import-slot-picker").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot-picker").fetchSemanticsNodes().isEmpty() }
        reveal("settings-row:选取我的名额", "导入声音").assertTextContains("S_second", substring = true).performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot:S_second").fetchSemanticsNodes().size == 1 }
        node("voice-import-slot:S_second").assertIsSelected()
        node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot-picker").fetchSemanticsNodes().isEmpty() }
        reveal("settings-field:备注名称（可空）", "导入声音").assertTextContains("import draft")
        reveal("settings-row:备用方式：从控制台复制 ID", "导入声音").performClick()
        reveal("settings-field:已有音色 ID", "导入声音").assertTextContains("S_second")
        assertNoSideEffects(expectedRequests = 14)
    }

    fun testImportEmptyCloudKeepsTheSameSheetAndDoesNotReuseLocalRecords() {
        mount(available = false, flow = "IMPORT")
        reveal("settings-row:选取我的名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        val empty = hasText("云端暂无可导入的已复刻声音。") and
            hasAnyAncestor(hasTestTag("voice-import-slot-picker"))
        ui.waitUntil(5_000) { ui.onAllNodes(empty).fetchSemanticsNodes().size == 1 }
        ui.onNode(empty).assertIsDisplayed()
        node("voice-import-slot:S_other_account").assertDoesNotExist()
        node("voice-import-slot:S_unused").assertDoesNotExist()
        node("voice-import-slot:S_training").assertDoesNotExist()
        assertNoSideEffects(expectedRequests = 4)
    }

    fun testImportFailedReopenClearsPreviouslyVerifiedCards() {
        mount(available = true, flow = "IMPORT")
        reveal("settings-row:选取我的名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot:S_available").fetchSemanticsNodes().size == 1 }
        node("voice-import-slot:S_available").assertIsEnabled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot-picker").fetchSemanticsNodes().isEmpty() }
        importQueryFails = true
        reveal("settings-row:选取我的名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        val error = hasText("已复刻名额查询失败", substring = true) and
            hasAnyAncestor(hasTestTag("voice-import-slot-picker"))
        ui.waitUntil(5_000) { ui.onAllNodes(error).fetchSemanticsNodes().size == 1 }
        ui.onNode(error).assertIsDisplayed()
        node("voice-import-slot:S_available").assertDoesNotExist()
        assertNoSideEffects(expectedRequests = 6)
    }

    fun testImportQueryReasonSurvivesPickerAndRecoversAfterReopen() {
        mount(available = true, flow = "IMPORT")
        importVoiceQueryFails = true
        reveal("settings-row:选取我的名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        val reason = hasText("音色查询权限不足：HTTP 403，请核对语音 API Key 与音色所属项目。") and
            hasAnyAncestor(hasTestTag("voice-import-slot-picker"))
        ui.waitUntil(5_000) { ui.onAllNodes(reason).fetchSemanticsNodes().size == 1 }
        ui.onNode(reason).assertIsDisplayed()
        ui.onNode(hasText("已复刻名额查询失败", substring = true) and
            hasAnyAncestor(hasTestTag("voice-import-slot-picker"))).assertDoesNotExist()
        node("voice-import-slot:S_available").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot-picker").fetchSemanticsNodes().isEmpty() }
        importVoiceQueryFails = false
        reveal("settings-row:选取我的名额", "导入声音").performClick()
        awaitUniqueTag("voice-import-slot-picker")
        awaitNodeWindowFocus(node("voice-import-slot-picker"))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("voice-import-slot:S_available").fetchSemanticsNodes().size == 1 }
        node("voice-import-slot:S_available").assertIsEnabled()
        ui.onNode(reason).assertDoesNotExist()
        assertNoSideEffects(expectedRequests = 10)
    }

    private fun importDirectly(remark: String) {
        reveal("settings-field:备注名称（可空）", "导入声音").performTextReplacement(remark)
        reveal("settings-row:备用方式：从控制台复制 ID", "导入声音").performClick()
        reveal("settings-field:已有音色 ID", "导入声音").performTextReplacement("S_available")
        reveal("settings-row:导入声音", "导入声音").performClick()
        ui.onNodeWithText("确认本次操作").assertDoesNotExist()
    }

    fun testImportWithoutRemarkExecutesDirectlyWithoutAliasOrClone() {
        mount(available = true, flow = "IMPORT")
        importDirectly("")
        val success = "声音已导入；没有重新制作。"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(success).fetchSemanticsNodes().size == 1 }
        assertEquals(1, requests.get())
        assertFalse(resources.voices.state.value.single { it.id == "S_available" }.nameSyncPending)
        assertEquals("remote name", remoteImportAlias)
        assertEquals(0, plays.get()); assertEquals(0, fixture.opens.get())
    }

    fun testImportWithRemarkNamesRemoteResourceWithoutConfirmationOrClone() {
        mount(available = true, flow = "IMPORT")
        importDirectly("我的备注")
        val success = "声音已导入，名称已同步到云端。"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(success).fetchSemanticsNodes().size == 1 }
        assertEquals(3, requests.get()); assertEquals("我的备注", remoteImportAlias)
        val voice = resources.voices.state.value.single { it.id == "S_available" }
        assertEquals("我的备注", voice.name); assertFalse(voice.nameSyncPending)
        assertEquals(0, plays.get()); assertEquals(0, fixture.opens.get())
    }

    fun testImportAliasFailureKeepsVoiceAndDirectMenuRetryOnlyRenames() {
        mount(available = true, flow = "IMPORT")
        importAliasFails = true
        importDirectly("重试备注")
        val failure = "声音已导入，名称同步失败；可在原音色菜单重试名称同步。"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(failure).fetchSemanticsNodes().size == 1 }
        assertEquals(3, requests.get())
        assertTrue(resources.voices.state.value.single { it.id == "S_available" }.nameSyncPending)
        importAliasFails = false
        node("voice-menu:S_available").performClick()
        awaitUniqueTag("voice-choice-dialog")
        awaitNodeWindowFocus(node("voice-choice-dialog"))
        node("settings-radio:重试名称同步").performClick()
        ui.onNodeWithText("确认本次操作").assertDoesNotExist()
        ui.waitUntil(5_000) { resources.voices.state.value.none { it.id == "S_available" && it.nameSyncPending } }
        assertEquals(5, requests.get()); assertEquals("重试备注", remoteImportAlias)
        assertEquals(0, plays.get()); assertEquals(0, fixture.opens.get())
    }

    fun testPendingNameShowsRetryWithoutImplicitRequestsOrRecreation() {
        mount(available = true, flow = "LIST", ready = true, namePending = true)
        ui.onNode(hasText("名称待同步", substring = true) and
            hasAnyAncestor(hasTestTag("settings-row:S_available")), useUnmergedTree = true).assertIsDisplayed()
        assertNoSideEffects()
        node("voice-menu:S_available").performClick()
        awaitUniqueTag("voice-choice-dialog")
        awaitNodeWindowFocus(node("voice-choice-dialog"))
        node("settings-radio:重试名称同步").assertIsEnabled()
        node("settings-radio:移除本机记录").assertIsEnabled()
        node("settings-radio:查看状态").assertDoesNotExist()
        ui.onAllNodes(hasAnyAncestor(hasTestTag("voice-choice-dialog")) and hasClickAction()).assertCountEquals(2)
        assertNoSideEffects()
        node("settings-radio:重试名称同步").performClick()
        ui.onNodeWithText("确认本次操作").assertDoesNotExist()
        val failure = "名称同步失败；请检查已保存的 AK／SK 与项目后重试，不要重新制作。"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(failure).fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText(failure).assertIsDisplayed()
        val voice = resources.voices.state.value.single { it.id == "S_available" }
        assertTrue(voice.nameSyncPending); assertFalse(voice.uncertain)
        assertNoSideEffects()
    }

    fun testPendingNameDoesNotBlockTrainingStatusQueryWhenCredentialsAreMissing() {
        mount(available = true, flow = "LIST", ready = false, namePending = true, queryStatus = 2)
        node("voice-menu:S_available").performClick()
        awaitUniqueTag("voice-choice-dialog")
        awaitNodeWindowFocus(node("voice-choice-dialog"))
        node("settings-radio:查看状态").assertIsEnabled().performClick()
        ui.onNodeWithText("确认本次操作").assertDoesNotExist()
        val failure = "名称同步失败；请检查已保存的 AK／SK 与项目后重试，不要重新制作。"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(failure).fetchSemanticsNodes().size == 1 }
        assertEquals(1, requests.get())
        val voice = resources.voices.state.value.single { it.id == "S_available" }
        assertEquals(2, voice.status); assertTrue(voice.nameSyncPending); assertFalse(voice.uncertain)
        assertEquals(0, plays.get()); assertEquals(0, fixture.opens.get())
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
        ui.onNodeWithText("确认本次操作").assertDoesNotExist()
        ui.waitUntil(5_000) { requests.get() == 1 && resources.voices.state.value.any { it.id == "S_available" && it.status == 1 } }
        ui.onNode(
            hasText("训练中", substring = true) and hasAnyAncestor(hasTestTag("settings-row:S_available")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val disabledPreview = node("voice-preview:S_available").assertHasNoClickAction()
        disabledPreview.onChild().assertContentDescriptionEquals("播放试听")
        disabledPreview.performTouchInput { click() }
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
        val disabledPreview = node("voice-preview:S_available").assertHasNoClickAction()
        disabledPreview.onChild().assertContentDescriptionEquals("播放试听")
        disabledPreview.performTouchInput { click() }
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
            importAccountCleanup.forEach { it() }
            roots.forEach { it.deleteRecursively() }
        } finally { super.cleanupAfterLegacy() }
    }
}
