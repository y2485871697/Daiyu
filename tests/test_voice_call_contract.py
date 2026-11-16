"""Targeted wiring contracts; call state behavior is covered by JVM tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
class VoiceCallContractTest(unittest.TestCase):
    def test_call_personal_segments_and_existing_diagnostics_are_wired_at_production_entry(self):
        main = (SRC / 'MainActivity.kt').read_text()
        host = (SRC / 'ActivityUniversalVoiceHost.kt').read_text()
        adapter = (SRC / 'voice/control/AndroidUniversalPlayback.kt').read_text()
        self.assertIn('diagnostics = controller.diagnostics,', main)
        self.assertIn('RemoteVoiceHostPlayer(app, scope, runtime, speech, diagnostics = diagnostics,', host)
        self.assertIn('segmentCodePoints = if (personal == null) 300 else 32', host)
        self.assertIn('VoiceChain(gate, trackedOutput, segmentCodePoints)', adapter)
        self.assertIn('personal.consume(requestId, exactText, UNIVERSAL_PERSONAL_SEGMENT_AUDIO_MS)', host)
        self.assertIn('SPEECH_PERSONAL_AUTHORIZATION_DENIED', host)
        self.assertIn('override fun failed(reason: String) = events.failed(reason)', host)

    def test_call_inherits_strict_source_reasoning_and_cleans_only_temporary_preference(self):
        controller = (SRC / 'ChatController.kt').read_text()
        create = controller.split('internal fun createVoiceCall(', 1)[1].split('internal fun discardVoiceCall', 1)[0]
        self.assertIn('val inheritedReasoning = reasoningPreference.snapshot(source?.value)', create)
        self.assertLess(create.index('snapshot(source?.value)'), create.index('database.create('))
        self.assertIn('else reasoningPreference.set(temporary.id.value, defaultVoiceReasoning(temporary.id, binding))', create)
        self.assertIn('VoiceReasoningSource(database.modelBinding(source), inheritedReasoning)', create)
        self.assertNotIn('ReasoningEffort.OFF', create)
        discard = controller.split('internal fun discardVoiceCall(', 1)[1].split('internal fun sendText', 1)[0]
        self.assertLess(discard.index('reasoningPreference.forget(owner.value)'), discard.index('database.delete(owner)'))
        self.assertIn('abandoned.forEach { reasoningPreference.forget(it.value); database.delete(it) }', controller)
        self.assertIn('temporary.forEach { reasoningPreference.forget(it.value); database.delete(it) }', controller)

    def test_call_buttons_and_mini_tap_use_existing_touch_feedback_without_changing_drag(self):
        overlay = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/DoubaoCallOverlay.kt").read_text()
        self.assertIn("import app.daiyu.android.ui.haptics.clickable", overlay)
        self.assertIn("import app.daiyu.android.ui.haptics.hapticClick", overlay)
        self.assertNotIn("import androidx.compose.foundation.clickable", overlay)
        self.assertIn("rememberUpdatedState(hapticClick(enabled, onExpand))", overlay)
        self.assertIn("if (enabled) detectTapGestures { latestExpand() }", overlay)
        self.assertIn(".clickable(enabled = enabled, role = Role.Button, onClick = onClick)", overlay)
        self.assertIn("val interactive = !morphing", overlay)
        self.assertNotIn("android.os.Vibrator", overlay)

    def test_voice_uses_its_own_model_and_depth_and_both_calls_use_the_conversation_character_clock(self):
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertIn('setVoice(expected.owner.value, expected.binding.providerSlotId', controller)
        self.assertIn('rememberedVoiceModel(binding.providerSlotId', controller)
        database = (SRC / 'storage/ConversationDatabase.kt').read_text()
        self.assertIn('if (temporaryVoice) ConversationModelSelections.write', database)
        self.assertIn('VoiceCallModelMemory.readOrInitialize(db)', database)
        self.assertIn('isTemporaryVoice(id.value)) VoiceCallModelMemory.write(db, replacement)', database)
        main = (SRC / 'MainActivity.kt').read_text()
        actions = main.split('private fun voiceCallActions(external: Boolean = false)', 1)[1].split('private fun closeDoubaoCall(', 1)[0]
        self.assertIn('onModelLongPress = { if (current()) {\n'
                      '                if (external) returnToVoiceCall(key) { openVoiceThinking(key) } else openVoiceThinking(key)\n'
                      '            } },', actions)
        self.assertIn('controller.setVoiceCallReasoning(voice, effort)', main)
        self.assertIn('needsModelSelection = model == null', controller)
        self.assertIn('if (created.needsModelSelection)', main)
        self.assertIn('if (resumeReady) resume()', main)
        reveal = (SRC / 'ui/screens/chat/conversation/CallCaptionReveal.kt').read_text()
        self.assertIn('MarkdownTypingState(emptyList())', reveal)
        self.assertIn('live = true, paceTerminal = true', reveal)
        self.assertIn('withFrameNanos', reveal)
        self.assertNotIn('Text(text,', (SRC / 'ui/screens/chat/conversation/DoubaoCallOverlay.kt').read_text())

    def test_reasoning_rejection_is_a_closed_diagnostic_not_a_network_title_or_spoken_text(self):
        observer = (SRC / 'voice/control/AndroidUniversalOwnerObserver.kt').read_text()
        self.assertIn('RequestReasoningEffort.entries.firstOrNull { it.unsupportedMessage == message.error }', observer)
        self.assertIn('unsupportedReasoning = unsupportedReasoning', observer)
        controller = (SRC / 'voice/control/VoiceCallController.kt').read_text()
        self.assertIn('value.unsupportedReasoning.takeIf { value.status == VoiceBodyStatus.FAILED }', controller)
        owner = (SRC / 'VoiceCallPresentationOwner.kt').read_text()
        self.assertIn('errorTitle = "通话遇到问题"', owner)
        self.assertIn('value.unsupportedReasoning?.unsupportedMessage', owner)
        overlay = (SRC / 'ui/screens/chat/conversation/DoubaoCallOverlay.kt').read_text()
        self.assertIn('val errorTitle: String = "连接遇到问题"', overlay)
        self.assertIn('DoubaoCallVisualState.ERROR -> errorTitle', overlay)

    def test_start_publishes_active_identity_before_asynchronous_recognition(self):
        controller = (SRC / 'voice/control/VoiceCallController.kt').read_text()
        listening = controller.split('private fun listen(session: Call)', 1)[1].split('private fun recognized', 1)[0]
        self.assertLess(listening.index('session.input = input'), listening.index('publish(session,'))
        self.assertLess(listening.index('VoiceControlPhase.CONNECTING'), listening.index('ports.recognize('))
        self.assertIn('if (session.input !== input || !admitted(session)) return', listening)
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('permit != null && universalVoice.state.value.active', main)

    def test_call_content_is_owned_by_each_call_and_cleaned_at_stop(self):
        controller = (SRC / 'voice/control/VoiceCallController.kt').read_text()
        self.assertIn('transcript: String = session.transcript', controller)
        self.assertIn('session.transcript = transcript', controller)
        stop = controller.split('private fun end(session: Call,', 1)[1]
        self.assertIn('mutable.value = VoiceControlState(', stop)
        self.assertNotIn('state.value.copy(', stop)
        owner = (SRC / 'VoiceCallPresentationOwner.kt').read_text()
        self.assertIn('responseLabel = "助手"', owner)
        self.assertIn('val responseLabel: String = "豆包"', (SRC / 'ui/screens/chat/conversation/DoubaoCallOverlay.kt').read_text())

    def test_personal_call_freezes_reference_and_checks_a_shared_transport_budget(self):
        host = (SRC / 'ActivityUniversalVoiceHost.kt').read_text()
        self.assertNotIn('require(speech.reference == null && !speech.isPersonal)', host)
        self.assertIn('PersonalVoiceCallAuthorization(identity,', host)
        self.assertIn('internal const val UNIVERSAL_PERSONAL_SEGMENT_AUDIO_MS = 15_000L', host)
        self.assertIn('universalPersonalVoiceCascadeLimits(limits)', host)
        self.assertIn('maxSegments.toLong() * UNIVERSAL_PERSONAL_SEGMENT_AUDIO_MS, limits.durationMillis)', host)
        self.assertIn('}, UNIVERSAL_PERSONAL_SEGMENT_AUDIO_MS)', host)
        self.assertIn('personal?.reserve(turn)', host)
        self.assertIn('personal?.accepted(turn, PersonalVoiceCascadeOwner(', host)
        self.assertIn('binding.personal?.answer(turn,', host)
        self.assertIn('personal.consume(requestId, exactText, UNIVERSAL_PERSONAL_SEGMENT_AUDIO_MS)', host)
        self.assertIn('personal?.close(); playback.closeAndDrain()', host)
        self.assertIn('binding.personal?.revoke()', host)
        player = (SRC / 'RemoteVoiceHostPlayer.kt').read_text()
        self.assertIn('frozen.upload?.current() != false', player)
        self.assertGreaterEqual(player.count('speechCurrent()'), 6)
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('voiceRuntime.authorizeReference(prepared)', main)
        self.assertNotIn('if (mode == ChatMenuAction.VOICE_CHAT && offlineReady) return null', main)

    def test_voice_uses_temporary_owner_without_editor_mutation(self):
        main = (SRC / "MainActivity.kt").read_text()
        send = main.split("submit = { turn, text, result ->", 1)[1].split("observe =", 1)[0]
        self.assertIn("voiceCallConversation", send)
        self.assertIn("voiceConversation = true", send)
        self.assertNotIn("updateDraft(", send)
        self.assertIn('仅对话前可更改', main)
        self.assertIn('voiceCallModelName = ticket.record.id', main)
        self.assertIn('voiceCallPresentation.begin(voiceCallAnchor', main)
    def test_temporary_storage_is_excluded_from_normal_history_and_backups(self):
        db = (SRC / 'storage/ConversationDatabase.kt').read_text()
        self.assertIn("id NOT GLOB 'voice-call-*'", db)
        self.assertIn("conversation_id NOT GLOB 'voice-call-*'", db)
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertGreaterEqual(controller.count('VoiceCallTempFiles.recover(application.cacheDir)'), 2)
        self.assertIn('database.delete(owner)', controller)
        self.assertIn('blobs.forConversation', (SRC / 'context/ContextService.kt').read_text())
    def test_background_call_keeps_service_but_task_removal_hangs_up(self):
        main = (SRC / 'MainActivity.kt').read_text()
        for method, end in [('onPause()', 'override fun onStop'), ('onStop()', 'override fun onDestroy')]:
            body = main.split('override fun ' + method, 1)[1].split(end, 1)[0]
            self.assertIn('pauseNonCallVoice()', body)
            self.assertNotIn('invalidateConfiguredVoice()', body)
        service = (SRC / 'voice/control/VoiceCallService.kt').read_text()
        self.assertIn('onTaskRemoved(rootIntent: Intent?) { end()', service)
        self.assertIn('START_NOT_STICKY', service)
    def test_model_output_is_text_only_and_non_search_device_tools_are_not_exposed(self):
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertIn('requestOptions.copy(voiceTextOnly = true)', controller)
        self.assertIn('allowedToolNames = if (voiceConversation) setOf(RunBrowserTools.READ_TOOL_NAME, RunBrowserTools.WRITE_TOOL_NAME) else null', controller)
        self.assertIn('listOfNotNull(ordinaryBrowserScope(realOwner, task))', controller)
        self.assertIn('val ordinaryRun = !voiceConversation', controller)
        self.assertIn('followChatModelWindow = voiceConversation', controller)
        self.assertIn('!voiceConversation && conversationChildrenEnabled', controller)
    def test_voice_browser_reuses_the_ordinary_leaf_before_any_device_preparation(self):
        controller = (SRC / 'ChatController.kt').read_text()
        voice = controller.split('ordinaryFactory = if (voiceConversation)', 1)[1].split('else if (!ordinaryRun)', 1)[0]
        self.assertIn('listOfNotNull(ordinaryBrowserScope(realOwner, task))', voice)
        for forbidden in ('RunScreenLocation', 'TerminalPtyLaunch', 'RunOrdinaryGuiTools', 'RunOrdinaryGenerationTools'):
            self.assertNotIn(forbidden, voice)
        self.assertEqual(1, controller.count('return RunOrdinaryBrowserTools(application, realOwner, task'))
        self.assertIn('if (ordinaryRun || voiceConversation) browserSettings.snapshot()', controller)
        self.assertIn('settings.access == app.daiyu.android.browser.session.BrowserAccess.DISABLED', controller)

    def test_failed_answer_is_turn_local_and_never_automatically_resubmitted(self):
        controller = (SRC / 'voice/control/VoiceCallController.kt').read_text()
        failure = controller.split('if (value.status in setOf(VoiceBodyStatus.FAILED, VoiceBodyStatus.CANCELLED)', 1)[1]
        failure = failure.split('if (value.status !in', 1)[0]
        self.assertIn('session.responseFailed = true', failure)
        self.assertIn('session.searching = false; session.searchCompletion = null', failure)
        self.assertIn('releaseAnswer(session, false)', failure)
        self.assertNotIn('ports.submit', failure)
        self.assertNotIn('end(session', failure)

    def test_search_is_native_model_policy_and_not_a_separate_local_tool(self):
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertIn('frozen.requestOptions.copy(voiceTextOnly = true)', controller)
        self.assertNotIn('RunVoiceSearchTools', controller)
        self.assertIn('onNativeSearch = { event -> publishNativeSearch(assistant, event) }', controller)
        owner = (SRC / 'voice/control/AndroidUniversalOwnerObserver.kt').read_text()
        self.assertIn('controller.nativeSearchState(owner)', owner)
        indicator = (SRC / 'ui/screens/chat/conversation/VoiceCallCompressionIndicator.kt').read_text()
        self.assertIn('正在搜索', indicator)
        self.assertIn('LaunchedEffect(compressing, completion, searching, searchCompletion, resetKey, running, browsing)', indicator)
        self.assertIn('if (running && !busy && (compressed || searched))', indicator)
        self.assertIn('delay(2_000)', indicator)
    def test_call_picker_and_initial_model_are_scoped_to_actual_call_binding(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('voiceCallModelName = checkNotNull(created).modelName', main)
        close = main.split('private fun closeVoiceCall(key: Long)', 1)[1].split('private fun voiceCallActions(external: Boolean = false)', 1)[0]
        self.assertLess(close.index('universalVoice::stop'), close.index('dismissChatModels()'))
        self.assertIn('chatModelConversation = if (forVoiceCall) voiceCallConversation?.id else selected?.id', main)
    def test_compaction_success_only_after_durable_application(self):
        runtime = (SRC / 'context/ProductionRunContext.kt').read_text()
        candidate = runtime.split('override fun compactRequests', 1)[1]
        self.assertNotIn('succeeded = true', candidate)
        self.assertIn('override fun compactionApplied()', runtime)
        runner = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/ChatRunner.kt').read_text()
        self.assertIn('applied = true\n                runtime?.compactionApplied()', runner)
    def test_caption_and_completion_state_live_above_presentation_branch(self):
        overlay = (SRC / 'ui/screens/chat/conversation/DoubaoCallOverlay.kt').read_text()
        session = overlay.split('private fun DoubaoCallSession(', 1)[1].split('private fun callClock', 1)[0]
        self.assertIn('val captionScroll = rememberScrollState()', session)
        self.assertIn('val compressionState = rememberVoiceCallCompressionState', session)
        self.assertIn('captionScroll = captionScroll', session)


class FloatingVoiceCallContract(unittest.TestCase):
    def test_navigation_keeps_only_service_backed_calls_and_the_original_send_owner(self):
        activity = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('if (!continuingVoiceCall()) invalidateConfiguredVoice()', activity)
        self.assertIn('!exactActive || callBlocked || (!continuing && navigationExcluded)', activity)
        self.assertIn('target?.id?.value != turn.permit.conversationId', activity)
        self.assertIn('pending != null && !universalTickets.containsKey(pending.ticket)', activity)
        sidebar = activity.split('private var sidebarVisible: Boolean', 1)[1].split('private class ContextManagementVisit', 1)[0]
        self.assertIn('pauseNonCallVoice(keepDictation = true)', sidebar)
        self.assertNotIn('invalidateConfiguredVoice()', sidebar)
        self.assertIn('foreground = activityUsable() && voiceForeground && retainedPage == "chat" && !sidebarVisible', activity)

    def test_handoff_prepares_the_same_surface_and_waits_for_an_inline_draw(self):
        activity = (SRC / 'MainActivity.kt').read_text()
        floating = (SRC / 'VoiceCallFloatingWindow.kt').read_text()
        overlay = (SRC / 'ui/screens/chat/conversation/DoubaoCallOverlay.kt').read_text()
        self.assertIn('visible = !inline || returning', activity)
        self.assertIn('floatingCallHandoffEpoch == epoch', activity)
        self.assertIn('window.decorView.postOnAnimation', activity)
        self.assertIn('skipEntryAnimation = true', activity)
        pause = activity.split('override fun onPause() {', 1)[1].split('chatReturnRefresh = null', 1)[0]
        self.assertIn('voiceForeground = false', pause)
        self.assertIn('refreshFloatingCall()', pause)
        self.assertIn('it.presented && !it.departing', floating)
        self.assertIn('alpha = if (initiallyVisible) 1f else 0f', floating)
        self.assertIn('previous.detached', floating)
        self.assertIn('!current.presented || !current.isLaidOut', floating)
        self.assertIn('firstPresentation && !state.skipEntryAnimation', overlay)
        self.assertIn('presentationView.post { ready(state.sessionKey) }', overlay)
        self.assertIn('!closing && !morphing && progress.value == 1f', overlay)

    def test_external_close_is_visual_only_and_detaches_after_its_own_animation(self):
        floating = (ROOT / 'app/src/main/kotlin/app/daiyu/android/VoiceCallFloatingWindow.kt').read_text()
        self.assertIn('TYPE_APPLICATION_OVERLAY', floating)
        self.assertIn('FLAG_NOT_FOCUSABLE', floating)
        self.assertIn('FLAG_NOT_TOUCH_MODAL', floating)
        self.assertIn('FLAG_NOT_TOUCHABLE', floating)
        self.assertIn('alpha(0f).scaleX(.88f).scaleY(.88f)', floating)
        self.assertIn('window === current', floating)
        self.assertIn('withEndAction', floating)
        self.assertNotIn('startUniversalVoice', floating)
        self.assertNotIn('startDuplex', floating)
        self.assertNotIn('RECORD_AUDIO', floating)
        self.assertIn('VoiceCallFloatingWindowTest', (ROOT / '.github/workflows/doubao-call-motion-checks.yml').read_text())

if __name__ == '__main__':
    unittest.main()
