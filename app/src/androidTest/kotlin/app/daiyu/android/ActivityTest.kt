package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
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
        ui.mainClock.advanceTimeByFrame()
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

    fun testVideoLifecycleBindingStaysWithRouteAcrossEditorAndActivityReplacement() {
        val host = fixture.controller.videoHost
        val first = fixture.database.create("default")
        val second = fixture.database.create("default")
        for (conversation in listOf(first, second, first, first)) {
            mainThread { activity.select(conversation) }
            awaitControl("video route visible after selection") {
                host.state.visible && host.state.conversation == conversation.id.value
            }
            mainThread {
                assertTrue("single binding guard remains active", runCatching { host.bindLifecycle(activity) }.isFailure)
            }
            node("chat-draft").assertIsDisplayed()
            awaitControl("current composer controls survive subcomposition replacement") {
                invokeHost(activity, "getVideoComposerControls") != null
            }
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        mainThread { assertFalse(host.state.visible) }
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitControl("video route restored after sidebar") { host.state.visible }
        openSettings()
        mainThread { assertFalse(host.state.visible) }
        backToChat(activity)
        awaitControl("video route restored after settings") { host.state.visible }
        val previous = activity
        activity = recreateActivity(activity)
        assertNotSame(previous, activity)
        awaitControl("replacement owns retained video route") {
            host.state.visible && host.state.conversation == first.id.value
        }
        mainThread {
            assertSame(host, fixture.controller.videoHost)
            assertTrue("replacement still has exactly one binding", runCatching { host.bindLifecycle(activity) }.isFailure)
        }
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
        node("responses-path-select").assertDoesNotExist()
        node("settings-field:模型名").assertDoesNotExist()
        modelField("API Key").assertEditableText("")
        editModel("API Key", "replacement-key"); saveModel()
        assertEquals(config.copy(apiKey = "replacement-key"), fixture.settings.load())
        awaitModelEditor()
        backToChat(activity)
        assertDraft(text); assertEquals(text, fixture.database.draft()); assertEquals(0, fixture.opens.get())
    }

    fun testSelectionSemanticsKeepTraversalAndToolbarRangesAndCollapsedCursor() {
        setDraft("选择范围回归")
        setSelection(1, 3, relativeToOriginalText = true)
        setSelection(2, 4, relativeToOriginalText = false)
        node("chat-draft").assertIsFocused()
        setSelection(4, 4, relativeToOriginalText = false)
        setSelection(1, 3, relativeToOriginalText = true)
        assertDraft("选择范围回归")
        assertSelection(1, 3)
        assertEquals(0, fixture.opens.get())
    }

    fun testDraftSlotsRunningSwitchAndActivityRecreation() {
        val preferences = fixture.application.getSharedPreferences("model-settings", 0)
        val originalIv = checkNotNull(preferences.getString("iv", null))
        preferences.edit().remove("iv").commit()
        val unattached = " 未创建\n草稿  "
        setDraft(unattached); setSelection(2, 4, relativeToOriginalText = true); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertDraft(unattached); assertSelection(2, 4); assertTrue(fixture.database.conversations().isEmpty())
        val a = fixture.database.create("default"); val b = fixture.database.create("default")
        mainThread { activity.select(a) }; setDraft("A草稿"); setSelection(1, 2, relativeToOriginalText = true)
        val firstAEditor = retainedControls(activity)
        mainThread { activity.select(b) }; setDraft(" B草稿\n "); setSelection(0, 1, relativeToOriginalText = true)
        val oldBEditor = retainedControls(activity)
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.NEW_CHAT) }; ui.waitForIdle()
        mainThread { oldBEditor.onTextChange("stale B"); oldBEditor.onSelectionChange(0, 0) }
        mainThread {
            val created = fixture.database.conversations().single { it.id != a.id && it.id != b.id }
            assertEquals(created.id.value, fixture.controller.preferences.getString("selected", null))
            assertTrue(fixture.database.messages(created.id).isEmpty())
            assertEquals("unattached slot survives the menu action", unattached, fixture.database.draft())
            assertEquals("previous slot survives the menu action", " B草稿\n ", fixture.database.conversations().single { it.id == b.id }.draft)
        }
        assertDraft(""); assertSelection(0, 0)
        mainThread { activity.select(null) }
        assertDraft(unattached); assertSelection(2, 4)
        mainThread {
            activity.select(a)
            firstAEditor.onTextChange("stale A after round trip"); firstAEditor.onSelectionChange(0, 0)
        }
        assertDraft("A草稿"); assertSelection(1, 2)
        assertTrue(preferences.edit().putString("iv", originalIv).commit())
        val session = fixture.session("A回复", holdSeconds = 120)
        val beforeSendEditor = retainedControls(activity)
        node("chat-send").performClick(); assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        awaitTextSendResult(activity)
        mainThread { beforeSendEditor.onTextChange("stale sent text"); beforeSendEditor.onSelectionChange(1, 1) }
        node("chat-draft").assertEditableText(""); assertSelection(0, 0)
        setDraft("A下一条")
        mainThread { activity.select(b) }; assertDraft(" B草稿\n ")
        setDraft(" B新稿\n "); setSelection(1, 2, relativeToOriginalText = true)
        node("chat-control").assertDoesNotExist()
        node("chat-send").performClick() // Existing send rejects another active owner; no new run.
        awaitTextSendResult(activity)
        assertDraft(" B新稿\n "); assertEquals(1, fixture.opens.get())
        session.release.countDown(); awaitControl("A completed") { fixture.controller.active == null }
        ui.waitForIdle(); assertDraft(" B新稿\n "); assertSelection(1, 2)
        ui.onNodeWithText("A回复").assertDoesNotExist(); assertTrue(fixture.database.messages(b.id).isEmpty())
        fun selectionCheckpoint(phase: String) {
            val range = node("chat-draft").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            mainThread {
                fun endpoint(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(activity)
                android.util.Log.d("DaiyuSelection", "$phase ui=(${range.start},${range.end}) " +
                    "host=(${endpoint("selectionStart")},${endpoint("selectionEnd")}) retained=${fixture.controller.selections[b.id]}")
            }
        }
        selectionCheckpoint("before-hide")
        activity = recreateActivity(activity, afterImeHidden = {
            selectionCheckpoint("after-hide-before-recreate")
            assertDraft(" B新稿\n "); assertSelection(1, 2)
        }, afterDestroyed = {
            mainThread { android.util.Log.d("DaiyuSelection", "after-destroy retained=${fixture.controller.selections[b.id]}") }
        })
        selectionCheckpoint("replacement-focused")
        assertDraft(" B新稿\n "); assertSelection(1, 2)
        mainThread { activity.select(a) }; assertDraft("A下一条"); ui.onNodeWithText("A回复").assertExists()
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.NEW_CHAT) }; assertDraft(""); assertSelection(0, 0)
        assertEquals(4, fixture.database.conversations().size)
        mainThread { activity.select(null) }; assertDraft(unattached); assertSelection(2, 4)
        mainThread {
            val expected = highestSupportedRefreshRate(activity.window.decorView.display?.supportedModes?.map { it.refreshRate }.orEmpty())
            assertTrue(expected > 0f); assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
            val pause = MainActivity::class.java.getDeclaredMethod("onPause").apply { isAccessible = true }
            val resume = MainActivity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
            pause.invoke(activity)
            assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
            val mode = activity.window.attributes.preferredDisplayModeId
            resume.invoke(activity); assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
            pause.invoke(activity); assertEquals(expected, activity.window.attributes.preferredRefreshRate, 0f)
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
            mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.NEW_CHAT) }; assertDraft("")
            assertEquals(imported, fixture.database.draft())
            val conversation = fixture.database.create(); fixture.database.organize(conversation.id, "测试文件夹", true)
            mainThread { activity.select(conversation); activity.select(null) }; assertDraft(imported)
            val organized = fixture.database.conversations().single { it.id == conversation.id }
            assertTrue(organized.pinned); assertEquals("测试文件夹", organized.folder)
            assertEquals(2, fixture.database.conversations().size)
            assertEquals(0, fixture.opens.get())
            mainThread { fixture.controller.dataTask(work = { error("synthetic failure") }) }
            awaitControl("failure clears busy") { !fixture.controller.dataBusy }
        } finally { release.countDown(); from.close(); source.deleteDatabase("conversations.db") }
    }

    fun testMessagesVersionIsHiddenAndPreservedWhenSavingAndReopening() {
        val original = fixture.settings.load()
        for (version in listOf(null, "2023-06-02")) {
            val initial = original.copy(protocol = ProviderProtocol.MESSAGES, responsesPath = "",
                requestOptions = original.requestOptions.copy(speedMode = null, codex = false, anthropicVersion = version))
            fixture.settings.save(initial, protocolChangeAuthorized = true)
            openModels()
            assertModelSecret(initial.apiKey)
            node("settings-field:Anthropic 版本").assertDoesNotExist()
            editModel("API Key", "hidden-version-synthetic-key"); saveModel()
            val saved = initial.copy(apiKey = "hidden-version-synthetic-key")
            assertEquals(saved, fixture.settings.load())
            awaitModelEditor()
            backToChat(activity)
            openModels()
            assertModelSecret(saved.apiKey)
            node("settings-field:Anthropic 版本").assertDoesNotExist()
            assertEquals(saved, fixture.settings.load())
            backToChat(activity)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testProtocolSelectionPreservesFieldsExplicitSaveRebindsAndSurvivesReopen() {
        val config = fixture.settings.load().copy(responsesPath = "/responses")
        fixture.settings.save(config)
        setDraft("协议切换保留草稿"); openModels()
        chooseProviderProtocol("OpenAI Chat Completions")
        assertProviderProtocol("OpenAI Chat Completions")
        assertModelSecret(config.apiKey)
        node("responses-path-select").assertDoesNotExist()
        node("settings-field:Anthropic 版本").assertDoesNotExist()
        assertEquals(config, fixture.settings.load())
        node("settings-switch:重输密钥并绑定").assertDoesNotExist()
        saveModel()
        val chat = config.copy(protocol = ProviderProtocol.CHAT_COMPLETIONS)
        assertEquals(chat, fixture.settings.load())
        awaitModelEditor()
        backToChat(activity)
        openModels(); assertProviderProtocol("OpenAI Chat Completions")
        assertModelSecret(config.apiKey)
        chooseProviderProtocol("OpenAI Responses")
        assertProviderProtocol("OpenAI Responses")
        assertModelSecret(config.apiKey)
        node("responses-path-select").assertDoesNotExist()
        assertEquals(chat, fixture.settings.load())
        node("settings-switch:重输密钥并绑定").assertDoesNotExist()
        saveModel()
        assertEquals(config, fixture.settings.load())
        assertFalse(fixture.application.getSharedPreferences("model-settings", 0).contains("protocol"))
        awaitModelEditor()
        backToChat(activity)
        openModels(); assertProviderProtocol("OpenAI Responses"); assertModelSecret(config.apiKey)
        assertEquals(config, fixture.settings.load())
        backToChat(activity)
        assertDraft("协议切换保留草稿"); assertTrue(fixture.database.conversations().isEmpty()); assertEquals(0, fixture.opens.get())
    }
}
