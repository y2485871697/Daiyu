package app.daiyu.android

import android.app.AlertDialog
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.skills.SkillBundle
import app.daiyu.android.skills.SkillStore
import app.daiyu.android.skills.SkillToolRegistry
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class IntegrationSettingsActivityTest:android.test.InstrumentationTestCase() {
    private lateinit var fixture:RunControlFixture
    private lateinit var original:ChatController
    private lateinit var directory:File
    private var activity:MainActivity?=null
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun activityButton(label:String)=views(checkNotNull(activity).window.decorView)
        .filterIsInstance<Button>().single {it.text.toString()==label}
    private fun settingsDialog()=MainActivity::class.java.getDeclaredField("settingsDialog")
        .apply {isAccessible=true}.get(checkNotNull(activity)) as AlertDialog
    private fun skillDialog():AlertDialog {
        val host=MainActivity::class.java.getDeclaredField("skillManagementDialog")
            .apply {isAccessible=true}.get(checkNotNull(activity))
        return host.javaClass.getDeclaredField("window").apply {isAccessible=true}.get(host) as AlertDialog
    }
    private fun start() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
    }
    override fun setUp() {
        super.setUp()
        val base=instrumentation.targetContext
        directory=File(base.cacheDir,"integration-settings-${UUID.randomUUID()}").apply {check(mkdirs())}
        fixture=RunControlFixture(object:ContextWrapper(base) {override fun getFilesDir()=directory})
        awaitControl("controller ready") {fixture.controller.ready}
        controlMain {
            val app=base.applicationContext as ChatApplication
            original=app.controller;app.controller=fixture.controller
        }
        start()
    }
    override fun tearDown() {
        try {
            controlMain {activity?.finish()}
            instrumentation.waitForIdleSync()
            controlMain {(instrumentation.targetContext.applicationContext as ChatApplication).controller=original}
            fixture.application.getSharedPreferences("runtime-skills-v1-settings",0).edit().clear().commit()
            fixture.close();directory.deleteRecursively()
        } finally {super.tearDown()}
    }
    private fun openSettings() {
        controlMain {activityButton("模型设置").performClick()}
        instrumentation.waitForIdleSync()
    }
    private fun body(dialog:AlertDialog)=views(dialog.window!!.decorView).filterIsInstance<EditText>()
        .single {it.hint?.toString()?.startsWith("自定义请求 JSON")==true}
    private fun catalogDialog()=MainActivity::class.java.getDeclaredField("modelCatalogDialog")
        .apply {isAccessible=true}.get(checkNotNull(activity)) as AlertDialog?
    private fun catalogButton()=views(settingsDialog().window!!.decorView).filterIsInstance<Button>()
        .single {it.text.toString()=="获取模型目录（使用当前草稿）"}
    private fun catalogModel()=views(settingsDialog().window!!.decorView).filterIsInstance<EditText>()
        .single {it.hint?.toString()=="模型标识"}
    private class CatalogConnection(url:java.net.URL,val entered:java.util.concurrent.CountDownLatch,
        val release:java.util.concurrent.CountDownLatch,val body:String,val status:Int):java.net.HttpURLConnection(url) {
        @Volatile var disconnected=false
        override fun connect() {}
        override fun disconnect() {disconnected=true}
        override fun usingProxy()=false
        override fun getResponseCode():Int {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return status
        }
        override fun getInputStream()=body.byteInputStream()
    }
    private fun catalogue(body:String="""{"data":[{"id":"first"},{"id":"second"}]}""",
        status:Int=200,held:Boolean=false):Triple<java.util.concurrent.CountDownLatch,java.util.concurrent.CountDownLatch,java.util.concurrent.atomic.AtomicReference<CatalogConnection>> {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(if(held) 1 else 0)
        val connection=java.util.concurrent.atomic.AtomicReference<CatalogConnection>()
        fixture.controller.modelCatalog=app.daiyu.android.model.catalog.ModelCatalogService(
            {error("draft must not load saved credentials")},app.daiyu.android.model.catalog.RemoteModelFetcher {url ->
                CatalogConnection(url,entered,release,body,status).also {connection.set(it)}
            })
        return Triple(entered,release,connection)
    }
    fun testCatalogUsesCurrentEmptyKeyDraftAndOnlyExplicitSavePersistsSelection() {
        val (_,_,connection)=catalogue()
        val before=fixture.settings.load()
        openSettings()
        controlMain {
            val edits=views(settingsDialog().window!!.decorView).filterIsInstance<EditText>()
            edits.single {it.hint?.toString()?.startsWith("HTTPS")==true}.setText("https://catalog.invalid/v1/responses")
            edits.single {it.hint?.toString()?.startsWith("API密钥")==true}.setText("")
            catalogModel().setText("");catalogButton().performClick()
        }
        awaitControl("catalog selection") {catalogDialog()?.isShowing==true}
        controlMain {catalogDialog()!!.listView.performItemClick(null,1,1)}
        assertEquals(before,fixture.settings.load())
        assertEquals("https://catalog.invalid/v1/models",connection.get().url.toString())
        assertNull(connection.get().getRequestProperty("Authorization"))
        controlMain {
            assertEquals("second",catalogModel().text.toString())
            views(settingsDialog().window!!.decorView).filterIsInstance<EditText>()
                .single {it.hint?.toString()?.startsWith("API密钥")==true}.setText("new-key")
            settingsDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals("second",fixture.settings.load().model)
        assertEquals("new-key",fixture.settings.load().apiKey)
    }
    fun testCatalogChangedAndRestoredDraftRevokesLateResponseAndCanRetry() {
        val (entered,release,connection)=catalogue(held=true)
        openSettings();controlMain {catalogButton().performClick()}
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
        try {controlMain {val edit=catalogModel();val old=edit.text.toString();edit.setText("changed");edit.setText(old)}}
        finally {release.countDown()}
        awaitControl("old catalog finished") {connection.get().disconnected}
        instrumentation.waitForIdleSync()
        controlMain {assertNull(catalogDialog());catalogButton().performClick()}
        awaitControl("new catalog selection") {catalogDialog()?.isShowing==true}
        controlMain {settingsDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()}
        awaitControl("catalog closes with parent") {catalogDialog()==null}
    }
    fun testCatalogSaveFailureAndDismissRevokePendingResponse() {
        for(save in listOf(true,false)) {
            val (entered,release,connection)=catalogue(held=true)
            openSettings();controlMain {
                if(save) catalogModel().setText("")
                catalogButton().performClick()
            }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            try {controlMain {settingsDialog().getButton(if(save) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()}}
            finally {release.countDown()}
            awaitControl("revoked catalog finished") {connection.get().disconnected}
            instrumentation.waitForIdleSync();controlMain {assertNull(catalogDialog());if(save) settingsDialog().dismiss()}
        }
    }
    fun testCatalogEmptyAndFailurePreserveSettingsAndHideResponseDetails() {
        val before=fixture.settings.load()
        for((body,status) in listOf("""{"data":[]}""" to 200,"SECRET_RESPONSE_SENTINEL" to 401,"SECRET_RESPONSE_SENTINEL" to 200)) {
            catalogue(body,status);openSettings();controlMain {catalogButton().performClick()}
            awaitControl("catalog outcome") {catalogButton().isEnabled}
            controlMain {
                assertNull(catalogDialog())
                val texts=views(settingsDialog().window!!.decorView).filterIsInstance<android.widget.TextView>().map {it.text.toString()}
                assertFalse(texts.any {it.contains("SECRET_RESPONSE_SENTINEL")})
                assertTrue(texts.any {it.contains(if(status==200 && body.contains("data")) "供应商返回空模型目录" else "获取失败")})
                settingsDialog().dismiss()
            }
            assertEquals(before,fixture.settings.load())
        }
    }
    fun testAdvancedRequestOptionsSaveReopenAndRejectInvalidBodyAndTier() {
        openSettings()
        controlMain {
            val dialog=settingsDialog();body(dialog).setText("{\"temperature\":0.25}")
            views(dialog.window!!.decorView).filterIsInstance<Spinner>().single().setSelection(2)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
        }
        val expected=ProviderRequestOptions("{\"temperature\":0.25}",GptSpeedMode.FAST,false)
        assertEquals(expected,fixture.settings.load().requestOptions)
        openSettings()
        controlMain {
            val dialog=settingsDialog();assertEquals(expected.customBody,body(dialog).text.toString())
            assertEquals(2,views(dialog.window!!.decorView).filterIsInstance<Spinner>().single().selectedItemPosition)
            body(dialog).setText("{\"messages\":[]}")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing)
        }
        assertEquals(expected,fixture.settings.load().requestOptions)
        controlMain {
            val dialog=settingsDialog();body(dialog).setText(expected.customBody)
            views(dialog.window!!.decorView).filterIsInstance<Spinner>().single().setSelection(3)
            views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                .single {it.text.toString()=="使用 Codex 服务档位规则"}.isChecked=true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertEquals(expected,fixture.settings.load().requestOptions)
        controlMain {activity!!.finish()};instrumentation.waitForIdleSync();start();openSettings()
        controlMain {
            val dialog=settingsDialog();assertEquals(expected.customBody,body(dialog).text.toString())
            assertEquals(2,views(dialog.window!!.decorView).filterIsInstance<Spinner>().single().selectedItemPosition)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }
    fun testSkillsEntryPersistsIndividualAndGlobalSwitchesAndClosesWithActivity() {
        val name="integration-demo"
        SkillStore(fixture.application).install(listOf(SkillBundle(mapOf("SKILL.md" to
            "---\nname: $name\ndescription: UI fixture\n---\nFixture instructions".toByteArray()))))
        controlMain {activityButton("技能管理").performClick()}
        awaitControl("skill entry loaded") {views(skillDialog().window!!.decorView).filterIsInstance<Button>().any {it.text.toString()=="关闭 $name"}}
        controlMain {views(skillDialog().window!!.decorView).filterIsInstance<Button>().single {it.text.toString()=="关闭 $name"}.performClick()}
        awaitControl("individual switch read back") {views(skillDialog().window!!.decorView).filterIsInstance<Button>().any {it.text.toString()=="开启 $name"}}
        assertFalse(SkillStore(fixture.application).list().single().enabled)
        controlMain {views(skillDialog().window!!.decorView).filterIsInstance<Button>().single {it.text.toString()=="技能总开关：已开启（点击关闭）"}.performClick()}
        awaitControl("global switch read back") {views(skillDialog().window!!.decorView).filterIsInstance<Button>().any {it.text.toString()=="技能总开关：已关闭（点击开启）"}}
        assertFalse(SkillToolRegistry(fixture.application).enabled)
        var old:AlertDialog?=null
        controlMain {old=skillDialog();activity!!.finish()}
        instrumentation.waitForIdleSync()
        awaitControl("activity destroyed before checking skill dialog closure") {checkNotNull(activity).isDestroyed}
        controlMain {assertFalse(checkNotNull(old).isShowing)}
        start();controlMain {activityButton("技能管理").performClick()}
        awaitControl("reopened settings keep both switches") {
            val buttons=views(skillDialog().window!!.decorView).filterIsInstance<Button>().map {it.text.toString()}
            "开启 $name" in buttons && "技能总开关：已关闭（点击开启）" in buttons
        }
        assertTrue(SkillToolRegistry(fixture.application).snapshot().skills.isEmpty())
    }
    fun testDamagedSkillListStillAllowsGlobalOffAndDefaultNextCaptureDoesNotReadIt() {
        val blocked=File(directory,"agent-builtin-skills-v1/installed")
        check(blocked.parentFile!!.mkdirs());blocked.writeText("not a directory")
        controlMain {activityButton("技能管理").performClick()}
        awaitControl("global switch available despite directory failure") {
            views(skillDialog().window!!.decorView).filterIsInstance<Button>()
                .any {it.text.toString()=="技能总开关：已开启（点击关闭）"}
        }
        controlMain {
            val nodes=views(skillDialog().window!!.decorView)
            assertTrue(nodes.filterIsInstance<android.widget.TextView>().any {it.text.toString().startsWith("技能列表暂不可用")})
            nodes.filterIsInstance<Button>().single {it.text.toString()=="技能总开关：已开启（点击关闭）"}.performClick()
        }
        awaitControl("off state read back despite directory failure") {
            views(skillDialog().window!!.decorView).filterIsInstance<Button>()
                .any {it.text.toString()=="技能总开关：已关闭（点击开启）"}
        }
        val reopened=SkillToolRegistry(fixture.application)
        assertFalse(reopened.enabled)
        val owner=app.daiyu.android.domain.tools.TaskAuthorization("disabled-skill-test","next")
        val scope=reopened.captureForRun(app.daiyu.android.domain.tools.SessionCapabilities(
            owner.sessionId,setOf(app.daiyu.android.skills.SkillRunSession.READ)),owner)
        try {assertTrue(scope.availableTools.isEmpty())} finally {scope.close()}
        assertTrue(blocked.isFile)
    }

    fun testUnavailableRequestPolicyRequiresExplicitReplacementWithoutLosingOriginalOnFailure() {
        val prefs=fixture.application.getSharedPreferences("model-settings",0)
        check(prefs.edit().putString("request-options","not-json").commit())
        val before=HashMap(prefs.all)
        openSettings()
        controlMain {
            val dialog=settingsDialog();val nodes=views(dialog.window!!.decorView)
            val replacement=nodes.filterIsInstance<CheckBox>().single {it.text.toString().startsWith("原请求策略不可用")}
            assertEquals(View.VISIBLE,replacement.visibility);assertFalse(replacement.isChecked)
            nodes.filterIsInstance<EditText>().single {it.hint?.toString()=="API密钥（加密保存）"}.setText("replacement-key")
            body(dialog).setText("{\"temperature\":0.5}")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing)
            assertEquals(before,prefs.all)
            replacement.isChecked=true;body(dialog).setText("{\"tools\":[]}")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing)
            assertEquals(before,prefs.all)
            body(dialog).setText("{\"temperature\":0.5}")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(dialog.isShowing)
        }
        assertEquals(ProviderRequestOptions("{\"temperature\":0.5}"),fixture.settings.load().requestOptions)
        assertEquals("replacement-key",fixture.settings.load().apiKey)
        openSettings()
        controlMain {
            val dialog=settingsDialog()
            val replacement=views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                .single {it.text.toString().startsWith("原请求策略不可用")}
            assertEquals(View.GONE,replacement.visibility);assertFalse(replacement.isChecked)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }

}
