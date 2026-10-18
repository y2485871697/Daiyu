package app.daiyu.android

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ConversationDatabase
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ActivityTest : WiredActivityTestCase() {
    private fun refresh() { mainThread { MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity) } }
    fun testStreamingFollowStopsOnTouchAndEarlierHistory() {
        val db = fixture.database
        val conversation = db.create("default")
        repeat(70) { i ->
            val run = RunId("scroll-$i")
            val user = Message(MessageId("su-$i"), conversation.id, run, "user", listOf(MessageBlock.Text("u", "历史用户 $i")), RunStatus.COMPLETED)
            val assistant = Message(MessageId("sa-$i"), conversation.id, run, "assistant", listOf(MessageBlock.Text("a", "历史正文 $i\n".repeat(12))), RunStatus.RUNNING)
            db.send(user, assistant)
            if (i < 69) db.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        mainThread { activity.select(conversation) }
        node("chat-turn:sa-69").assertIsDisplayed()
        val tail = db.messages(conversation.id).last().second
        mainThread { db.save(tail.copy(blocks = listOf(MessageBlock.Text("a", "流式正文\n".repeat(80))))) }
        refresh()
        val viewport = node("chat-messages").fetchSemanticsNode().boundsInRoot
        val bottom = node("chat-turn:sa-69").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("stream growth follows the measured bottom", bottom <= viewport.bottom + 2f)
        val followingRange = node("chat-messages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("stream reaches actual scroll end", followingRange.maxValue(), followingRange.value(), 0.01f)
        node("chat-messages").performTouchInput { swipeDown(startY = height * 0.7f, endY = height * 0.9f) }
        node("chat-messages").performScrollToIndex(8)
        ui.waitForIdle()
        val candidates = ui.onAllNodes(SemanticsMatcher("turn tag") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chat-turn:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
        val anchor = candidates.first { it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom }
        val tag = anchor.config[SemanticsProperties.TestTag]
        val top = anchor.boundsInRoot.top
        mainThread { db.save(tail.copy(blocks = listOf(MessageBlock.Text("a", "新正文\n".repeat(100))))) }
        refresh()
        assertEquals("touch stops streaming follow", top, node(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.LOAD_EARLIER) }
        assertEquals("prepend preserves visible key offset", top, node(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
        val shownField = MainActivity::class.java.getDeclaredField("shown").apply { isAccessible = true }
        mainThread {
            @Suppress("UNCHECKED_CAST") val shown = shownField.get(activity) as List<Pair<Long, Message>>
            assertTrue(shown.size > 50)
            assertEquals(RunStatus.RUNNING, shown.last().second.status)
        }
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.LOCATE_USER) }
        node("prompt-choice:1").performClick()
        // After prepend, the second loaded user is su-21 (the original page began at su-45).
        node("chat-turn:su-21").assertIsDisplayed()
        assertTrue(node("chat-turn:su-21").fetchSemanticsNode().boundsInRoot.top >= viewport.top - 2f)
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.BOTTOM) }; node("chat-turn:sa-69").assertIsDisplayed()
        node("chat-messages").performTouchInput { click(center) }
        mainThread { db.save(tail.copy(blocks = listOf(MessageBlock.Text("a", "点击后正文\n".repeat(160))))) }
        refresh()
        val touchedRange = node("chat-messages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("a down without a drag also stops follow", touchedRange.value() < touchedRange.maxValue())
        mainThread { activity.select(null) }
        node("chat-turn:sa-69").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testCredentialFailureCanBeReenteredFromSettings() {
        val config = fixture.settings.load()
        fixture.application.getSharedPreferences("model-settings", 0).edit().remove("iv").commit()
        val text = "  保留草稿\n精确尾空格  "
        setDraft(text); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertDraft(text); assertTrue(fixture.database.conversations().isEmpty()); assertEquals(0, fixture.opens.get())
        openModels()
        modelField("Base URL").assertEditableText(config.endpoint)
        modelField("Responses 路径").assertEditableText(config.responsesPath)
        modelField("模型名").assertEditableText(config.model)
        modelField("API Key").assertEditableText("")
        editModel("API Key", "replacement-key"); saveModel()
        assertEquals(config.copy(apiKey = "replacement-key"), fixture.settings.load())
        assertDraft(text); assertEquals(text, fixture.database.draft()); assertEquals(0, fixture.opens.get())
    }

    fun testDraftSlotsRunningSwitchAndActivityRecreation() {
        val preferences = fixture.application.getSharedPreferences("model-settings", 0)
        val original = fixture.settings.load()
        preferences.edit().remove("iv").commit()
        val unattached = " 未创建\n草稿  "
        setDraft(unattached); setSelection(2, 4); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertDraft(unattached); assertSelection(2, 4); assertTrue(fixture.database.conversations().isEmpty())
        val a = fixture.database.create("default"); val b = fixture.database.create("default")
        mainThread { activity.select(a) }; setDraft("A草稿"); setSelection(1, 2)
        val firstAEditor = retainedControls(activity)
        mainThread { activity.select(b) }; setDraft(" B草稿\n "); setSelection(0, 1)
        val oldBEditor = retainedControls(activity)
        menu("新建会话"); ui.waitForIdle()
        mainThread { oldBEditor.onTextChange("stale B"); oldBEditor.onSelectionChange(0, 0) }
        mainThread {
            assertNull("new-chat menu must select the unattached slot", fixture.controller.preferences.getString("selected", null))
            assertEquals("unattached slot survives the menu action", unattached, fixture.database.draft())
            assertEquals("previous slot survives the menu action", " B草稿\n ", fixture.database.conversations().single { it.id == b.id }.draft)
        }
        assertDraft(unattached); assertSelection(2, 4)
        mainThread {
            activity.select(a)
            firstAEditor.onTextChange("stale A after round trip"); firstAEditor.onSelectionChange(0, 0)
        }
        assertDraft("A草稿"); assertSelection(1, 2)
        fixture.settings.save(original)
        val session = fixture.session("A回复", holdSeconds = 120)
        val beforeSendEditor = retainedControls(activity)
        node("chat-send").performClick(); assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        awaitTextSendResult(activity)
        mainThread { beforeSendEditor.onTextChange("stale sent text"); beforeSendEditor.onSelectionChange(1, 1) }
        node("chat-draft").assertEditableText(""); assertSelection(0, 0)
        setDraft("A下一条")
        mainThread { activity.select(b) }; assertDraft(" B草稿\n ")
        setDraft(" B新稿\n "); setSelection(1, 2)
        node("chat-control").assertDoesNotExist()
        node("chat-send").performClick() // Existing send rejects another active owner; no new run.
        awaitTextSendResult(activity)
        assertDraft(" B新稿\n "); assertEquals(1, fixture.opens.get())
        session.release.countDown(); awaitControl("A completed") { fixture.controller.active == null }
        ui.waitForIdle(); assertDraft(" B新稿\n "); assertSelection(1, 2)
        ui.onNodeWithText("A回复").assertDoesNotExist(); assertTrue(fixture.database.messages(b.id).isEmpty())
        activity = recreateActivity(activity)
        assertDraft(" B新稿\n "); assertSelection(1, 2)
        mainThread { activity.select(a) }; assertDraft("A下一条"); ui.onNodeWithText("A回复").assertExists()
        menu("新建会话"); assertDraft(unattached); assertSelection(2, 4)
        assertEquals(2, fixture.database.conversations().size)
        mainThread {
            val expected = highestSupportedRefreshRate(activity.window.decorView.display?.supportedModes?.map { it.refreshRate }.orEmpty())
            assertTrue(expected > 0f); assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
            val pause = MainActivity::class.java.getDeclaredMethod("onPause").apply { isAccessible = true }
            val resume = MainActivity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
            pause.invoke(activity)
            val attributes = activity.window.attributes; attributes.preferredRefreshRate = 13f; activity.window.attributes = attributes
            val mode = attributes.preferredDisplayModeId
            resume.invoke(activity); assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
            pause.invoke(activity); assertEquals(13f, activity.window.attributes.preferredRefreshRate, 0f)
            assertEquals(mode, activity.window.attributes.preferredDisplayModeId)
            resume.invoke(activity)
            val external = activity.window.attributes; external.preferredRefreshRate = 17f; activity.window.attributes = external
            val retry = MainActivity::class.java.getDeclaredField("refreshRateRequest").apply { isAccessible = true }
                .get(activity) as Runnable
            retry.run(); assertEquals(17f, activity.window.attributes.preferredRefreshRate, 0f)
            retry.run(); assertEquals(17f, activity.window.attributes.preferredRefreshRate, 0f)
            pause.invoke(activity); assertEquals(17f, activity.window.attributes.preferredRefreshRate, 0f)
            resume.invoke(activity); pause.invoke(activity); assertEquals(17f, activity.window.attributes.preferredRefreshRate, 0f)
            resume.invoke(activity)
        }
    }

    fun testImportDraftSurvivesBusyActivityReplacementAndSelection() {
        val source = testContext(instrumentation.targetContext, "backup-source-${UUID.randomUUID()}-")
        val from = ConversationDatabase(source)
        val imported = " 导入的未创建草稿\n  "
        from.draft(imported); val backup = from.exportBackup()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            mainThread { fixture.controller.dataTask(importing = true, work = {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); fixture.database.importBackup(backup); "完成"
            }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); node("chat-draft").assertIsNotEnabled()
            assertNull(fixture.controller.send(null, "busy attempt"))
            mainThread {
                try { activity.select(null); fail("busy selection must reject") } catch (_: IllegalStateException) {}
            }
            activity = recreateActivity(activity); node("chat-draft").assertIsNotEnabled()
            release.countDown(); awaitControl("import completes") { !fixture.controller.dataBusy && fixture.controller.dataRevision > 0 }
            ui.waitForIdle(); node("chat-draft").assertIsEnabled(); assertDraft(imported)
            menu("新建会话"); assertDraft(imported)
            val conversation = fixture.database.create(); fixture.database.organize(conversation.id, "测试文件夹", true)
            mainThread { activity.select(conversation); activity.select(null) }; assertDraft(imported)
            assertTrue(fixture.database.conversations().single().pinned); assertEquals("测试文件夹", fixture.database.conversations().single().folder)
            assertEquals(0, fixture.opens.get())
            mainThread { fixture.controller.dataTask(work = { error("synthetic failure") }) }
            awaitControl("failure clears busy") { !fixture.controller.dataBusy }
        } finally { release.countDown(); from.close(); source.deleteDatabase("conversations.db") }
    }

    fun testProtocolSelectionClearsCredentialRequiresRebindAndSurvivesReopen() {
        val config = fixture.settings.load().copy(responsesPath = "/responses")
        fixture.settings.save(config)
        setDraft("协议切换保留草稿"); openModels()
        reveal("settings-radio:Anthropic Messages").performClick()
        modelField("API Key").assertEditableText(""); modelField("Responses 路径").assertEditableText("").assertIsNotEnabled()
        editModel("API Key", "synthetic-messages-key"); saveModel()
        assertEquals(config, fixture.settings.load())
        reveal("settings-switch:重输密钥并绑定").performClick(); saveModel()
        val messages = fixture.settings.load()
        assertEquals(ProviderProtocol.MESSAGES, messages.protocol); assertEquals("synthetic-messages-key", messages.apiKey)
        assertEquals("", messages.responsesPath)
        assertTrue(app.daiyu.android.model.ModelFactory.create(messages) is app.daiyu.android.model.MessagesModel)
        openModels(); reveal("settings-radio:Anthropic Messages").assertIsSelected()
        reveal("settings-radio:OpenAI Responses").assertIsEnabled().performClick()
        reveal("settings-radio:OpenAI Responses").assertIsSelected()
        reveal("settings-radio:Anthropic Messages").assertIsNotSelected()
        modelField("API Key").assertEditableText("")
        editModel("Responses 路径", "/responses"); editModel("API Key", "explicit-responses-key")
        reveal("settings-switch:重输密钥并绑定").performClick(); saveModel()
        assertEquals(config.copy(apiKey = "explicit-responses-key"), fixture.settings.load())
        assertFalse(fixture.application.getSharedPreferences("model-settings", 0).contains("protocol"))
        assertDraft("协议切换保留草稿"); assertTrue(fixture.database.conversations().isEmpty()); assertEquals(0, fixture.opens.get())
    }
}
