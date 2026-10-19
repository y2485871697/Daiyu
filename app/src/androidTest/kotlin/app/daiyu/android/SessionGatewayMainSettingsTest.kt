package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.model.sessiongateway.SessionGatewayRule

/** Real Main navigation + Controller worker + encrypted synthetic fixture settings. No provider. */
@Suppress("DEPRECATION")
class SessionGatewayMainSettingsTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private val page = "保留会话网关"
    private fun openGateway() {
        openSettings()
        node("open-session-gateway").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("page:$page").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun edit(label: String, text: String) {
        reveal("settings-field:$label", page).performTextReplacement(text)
    }
    private fun saveGateway() {
        reveal("session-gateway-save", page).performClick()
        awaitControl("gateway save finished") { !fixture.controller.dataBusy }
        ui.waitForIdle()
    }
    private fun hostField(name: String): Any? = (MainActivity::class.java.getDeclaredField("$name\$delegate")
        .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>).value
    private fun staleSave(owner: ModelSettingsSaveOwner, rule: SessionGatewayRule) {
        MainActivity::class.java.getDeclaredMethod("saveSessionGateway", ModelSettingsSaveOwner::class.java,
            SessionGatewayRule::class.java).apply { isAccessible = true }.invoke(activity, owner, rule)
    }

    fun testMainSavesAllFiveFieldsThroughControllerWithoutExecutingUnknownSemantics() {
        val before = fixture.settings.load()
        openGateway()
        edit("模型正则（全匹配）", "relay/.*")
        edit("完整 URL 正则（包含匹配）", "gateway.invalid/v1/responses")
        edit("retention", "unverified-retention")
        edit("keySource", "unverified-source")
        edit("顶层身份键", "literal.key")
        saveGateway()
        val expected = SessionGatewayRule("relay/.*", "gateway.invalid/v1/responses",
            "unverified-retention", "unverified-source", "literal.key")
        assertEquals(before.copy(requestOptions = before.requestOptions.copy(sessionGateway = expected)), fixture.settings.load())
        assertNotNull(expected.unsupportedSemantics())
        assertEquals(0, fixture.opens.get())
        node("page:设置").assertExists()
    }

    fun testOrdinaryModelEditorDoesNotRebuildAndDiscardTheFiveGatewayFields() {
        val before = fixture.settings.load()
        val rule = SessionGatewayRule("custom.*", "custom/path", "unknown-ttl", "unknown-source", "my.key")
        fixture.settings.save(before.copy(requestOptions = before.requestOptions.copy(sessionGateway = rule)))
        openModels()
        editModel("模型名", "changed-model")
        editModel("自定义请求体", "{\"temperature\":0.2}")
        saveModel()
        val saved = fixture.settings.load()
        assertEquals("changed-model", saved.model)
        assertEquals(rule, saved.requestOptions.sessionGateway)
        assertEquals("{\"temperature\":0.2}", saved.requestOptions.customBody)
        assertEquals(0, fixture.opens.get())
    }

    fun testDepartureAndRecreationRejectRetainedSaveCallbacks() {
        val before = fixture.settings.load()
        openGateway()
        lateinit var owner: ModelSettingsSaveOwner
        lateinit var draft: SessionGatewayRule
        mainThread { owner = hostField("gatewayOwner") as ModelSettingsSaveOwner; draft = hostField("gatewayDraft") as SessionGatewayRule }
        backToPage(activity, "page:设置") // gateway -> settings; revokes the visit even if later reopened.
        mainThread { staleSave(owner, draft) }
        assertEquals(before, fixture.settings.load())
        node("open-session-gateway").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("page:$page").fetchSemanticsNodes().isNotEmpty() }
        val oldActivity = activity
        mainThread { owner = hostField("gatewayOwner") as ModelSettingsSaveOwner; draft = hostField("gatewayDraft") as SessionGatewayRule }
        activity = recreateActivity(activity)
        mainThread {
            MainActivity::class.java.getDeclaredMethod("saveSessionGateway", ModelSettingsSaveOwner::class.java,
                SessionGatewayRule::class.java).apply { isAccessible = true }.invoke(oldActivity, owner, draft)
        }
        assertEquals(before, fixture.settings.load()); assertEquals(0, fixture.opens.get())
    }

    fun testChangedProviderIsNotOverwrittenByAnOldLoadedGatewayEditor() {
        val before = fixture.settings.load()
        openGateway()
        edit("顶层身份键", "new.key")
        val newer = before.copy(model = "new-provider-selection")
        fixture.settings.save(newer)
        saveGateway()
        assertEquals(newer, fixture.settings.load())
        node("page:$page").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testUnreadableOptionsRequireExplicitReplacementAndKeepOriginalProtocolAndCredential() {
        val before = fixture.settings.load()
        fixture.application.getSharedPreferences("model-settings", 0).edit().putString("request-options", "not-json").commit()
        openGateway()
        reveal("session-gateway-save", page).assertIsNotEnabled()
        reveal("settings-switch:授权替换策略", page).performClick()
        saveGateway()
        assertEquals(before, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
