"""Model-switch presentation wiring; Android regressions own pixels and input."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = SRC / "ui/screens/chat/conversation"


class ModelSwitchFeedbackContractTest(unittest.TestCase):
    def test_switching_is_distinct_from_disabled_and_keeps_real_input_lock(self):
        text = (UI / "ChatModelPicker.kt").read_text()
        self.assertIn("val switching: Boolean = false", text)
        self.assertIn("val canSelect = state.enabled && !state.loading && !state.switching", text)
        self.assertIn("val visuallyEnabled = (state.enabled || state.switching) && !state.loading", text)
        self.assertIn("!state.enabled && !state.switching ->", text)
        self.assertIn(".alpha(if (visuallyEnabled) 1f else .45f)", text)
        self.assertIn(".selectable(selected = selected, enabled = canSelect", text)

    def test_wrapper_forwards_switching_and_fences_both_provider_callbacks(self):
        text = (UI / "LocalCatalogChatModelPicker.kt").read_text().split("internal fun LocalCatalogChatModelPicker(", 1)[1]
        self.assertIn("switching: Boolean = false", text)
        self.assertIn("switching = switching", text)
        self.assertEqual(2, text.count("if (enabled && !loading && !switching)"))

    def test_host_marks_pending_before_controller_and_rejects_duplicate_selection(self):
        text = (SRC / "MainActivity.kt").read_text()
        render = text.split("else LocalCatalogChatModelPicker(session", 1)[1].split("composerShortcutPick", 1)[0]
        self.assertIn("switching = picker.switching", render)
        method = text.split("private fun selectChatModel(", 1)[1].split("private fun gatewayCurrent", 1)[0]
        self.assertIn("if (picker.switching) return", method)
        pending = method.index("chatModelPicker = picker.copy(switching = true)")
        self.assertLess(pending, method.index("controller.selectActiveProviderCatalogModel("))
        self.assertIn("!controller.taskConfigurationBusy(conversation) && !controller.dataBusy", method)
        self.assertIn("chatModelOwner === owner && chatModelSession === session", method)
        self.assertIn("if (success) { reloadChatModels(owner, retainPresentation = true); refresh() }", method)
        self.assertNotIn("dismissChatModels()", method)

    def test_success_reload_retains_switching_without_loading_or_reenabling_old_tickets(self):
        text = (SRC / "MainActivity.kt").read_text()
        reload = text.split("private fun reloadChatModels(", 1)[1].split("private fun selectChatModel(", 1)[0]
        self.assertIn("val selectionPending = retainPresentation && chatModelPicker?.switching == true", reload)
        self.assertIn("loading = !selectionPending, enabled = false, switching = selectionPending", reload)
        self.assertIn('?: ChatModelPickerState("", emptyList(), loading = true, enabled = false)', reload)
        for guard in ("chatModelOwner !== owner", "cancelled.get()", "chatModelCancelled !== cancelled",
                      "selected?.id != conversation", "editorGeneration != generation"):
            self.assertIn(guard, reload)
        self.assertIn("chatModelSession = session", reload)
        self.assertIn(".copy(loading = false, enabled = result != null", reload)

    def test_activity_regression_holds_the_real_retained_reload_and_waits_for_fresh_session(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/IntegrationSettingsActivityTest.kt").read_text()
        method = text.split("fun testRetainedChatModelReloadKeepsPresentationWhileRevalidating()", 1)[1].split("fun testOldPickerSelection", 1)[0]
        for fragment in ("fixture.settings.modelCatalog.configurationChange", "release.await(10, TimeUnit.SECONDS)",
                         'getDeclaredMethod("reloadChatModels", Any::class.java, Boolean::class.javaPrimitiveType)',
                         "copy(switching = true)", "assertFalse(pending.loading)", "assertTrue(pending.switching)",
                         "assertFalse(pending.enabled)", "assertSame(previousSession, sessionField.get(activity))",
                         'ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()',
                         'ui.onNodeWithText("当前任务结束后可切换模型").assertDoesNotExist()',
                         "sessionField.get(activity) !== previousSession", "!state.switching && !state.loading",
                         'assertChatBinding("second")', 'assertDraft("复读模型时保留草稿")',
                         "assertChatPickerOffline(calls)", "finally { release.countDown(); holder.join(10_000) }"):
            self.assertIn(fragment, method)
        held = method.split("awaitCatalogEntered(entered)", 1)[1].split("release.countDown(); holder.join(5_000)", 1)[0]
        self.assertIn("assertEquals(committedBinding, fixture.database.modelBinding(null))", held)
        self.assertNotIn("assertChatBinding(", held)
        self.assertNotIn("expectedChatBinding(", held)
        helper = text.split("private fun awaitChatPickerLoaded()", 1)[1].split("private fun closeChatPicker()", 1)[0]
        self.assertIn("!state.loading && !state.switching", helper)

    def test_failure_releases_switching_without_hiding_error_or_reenabling_stale_session(self):
        text = (SRC / "MainActivity.kt").read_text().split("private fun selectChatModel(", 1)[1].split("private fun gatewayCurrent", 1)[0]
        self.assertEqual(2, text.count("copy(enabled = false, switching = false,"))
        self.assertEqual(2, text.count("切换未保存，请关闭后重新打开选择模型"))
        self.assertEqual(2, text.count("chatModelSelectionFailed = true"))

    def test_android_regression_covers_pending_pixels_both_provider_locks_failure_and_real_busy(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatModelPickerLayoutTest.kt").read_text()
        method = text.split("fun testModelSwitchKeepsPixelsAndSuppressesTaskHintWithoutAcceptingMoreSelections()", 1)[1].split("fun testHtmlStructure", 1)[0]
        for part in ("for (dark in listOf(false, true))", "for (enabled in listOf(true, false))",
                     "state.copy(enabled = enabled, switching = true)", "captureScreenNode(node(tag))",
                     "expected[x, y] != actual[x, y]", "assertEquals(callbacksBefore, selected.size)",
                     'node("chat-model-option:b:same").assertIsNotEnabled()',
                     "state.copy(enabled = false, message = failure)", "state.copy(enabled = false)",
                     ".performTouchInput { click(center) }"):
            self.assertIn(part, method)

    def test_voice_selection_uses_switching_without_reloading_or_dimming(self):
        text = (SRC / "VoiceSettingsDialogs.kt").read_text().split("internal fun VoiceModelDialog(", 1)[1].split("internal val SettingsSpeechModelChoice.speechPickerId", 1)[0]
        self.assertIn("loading = choices == null, enabled = choices != null && displayed.isNotEmpty()", text)
        self.assertIn("switching = selecting", text)
        self.assertIn("if (alive.get() && !selecting && current())", text)
        pending = text.index("selecting = true")
        self.assertLess(pending, text.index('error = ""', pending))
        self.assertLess(pending, text.index("source.withSpeechModel(choice)"))
        self.assertIn("finally { selecting = false }", text)
        self.assertIn("if (alive.get() && current()) onSave(next)", text)
        self.assertIn("LaunchedEffect(source, fixedEngine)", text)
        self.assertNotIn("choices == null ||", text)
        self.assertIn("模型列表读取失败，请重新打开。", text)
        self.assertIn("模型连接已变化或不可读取，请重新选择。", text)
        self.assertIn("throw cancelled", text)

    def test_voice_regression_blocks_real_read_lease_without_invalidating_ticket(self):
        text = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/VoiceModelDialogSelectionTest.kt").read_text()
        for part in ("fixture.settings.modelCatalog.readConfiguration", "release.await(30, TimeUnit.SECONDS)",
                     "assertIsNotEnabled().performTouchInput", "captureScreenNode", "assertEquals(0, changed)",
                     'copy(revision = "saved-${saved.size}")', "assertSame(sheetRoot", "holder.join(5_000)",
                     "testFirstDirectoryReadStillShowsLoading", "testStaleConnectionStillShowsFailureWithoutSaving"):
            self.assertIn(part, text)
        self.assertNotIn("configurationChange", text)

    def test_all_switch_success_notices_are_silent_but_failures_and_data_gate_stay(self):
        text = (SRC / "ChatController.kt").read_text()
        self.assertNotIn('"模型已切换"', text)
        for name in ("selectProviderCatalogModel", "selectActiveProviderCatalogModel", "selectConfiguredModel", "selectConfiguredCatalogModel"):
            method = text.split("internal fun " + name + "(", 1)[1].split("\n    internal fun ", 1)[0]
            if name == "selectActiveProviderCatalogModel":
                self.assertIn("conversationConfigurationTask(conversation, failureNotice", method)
                self.assertNotIn("dataTask(", method)
            else:
                self.assertIn("dataTask(failureNotice", method)
                self.assertRegex(method, r"\n            null\n        \}\)")
            self.assertRegex(method, r"completed\s*=\s*completed")
        self.assertIn('check(idleAvailable() && !hasLiveChildTasks())', text)
        self.assertIn("dataBusy=true;notifyChanged()", text)
        self.assertIn("try { completed(success) }", text)


if __name__ == "__main__":
    unittest.main()
