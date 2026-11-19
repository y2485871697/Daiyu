from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = APP / 'ui/screens/chat/conversation'


class ChatSubmissionPreviewContractTest(unittest.TestCase):
    def test_append_success_is_silent_but_failure_feedback_and_receipt_cleanup_remain(self):
        main = (APP / 'MainActivity.kt').read_text()
        settlement = main.split('private fun settleSupplementReceipt()', 1)[1].split('private fun supplementMediaBlocked', 1)[0]
        admission = main.split('private fun submitSupplement(', 1)[1].split('private fun currentControlOwner()', 1)[0]
        self.assertIn('is SupplementAdmission.Accepted -> Unit', settlement)
        self.assertNotIn('追加内容已保存，将在当前任务的安全边界继续', main)
        self.assertNotIn('追加已排队，等待当前提问处理', main)
        self.assertIn('is SupplementAdmission.Rejected -> toast("追加未受理")', settlement)
        self.assertIn('is SupplementAdmission.Unconfirmed -> toast("追加结果未确认")', settlement)
        self.assertLess(settlement.index('updateDraft("")'), settlement.index('supplementDraft.finish('))
        self.assertIn('if (draft.isNotEmpty()) return', settlement)
        self.assertIn('controller.admitSupplement(owner, ticket.messageId, text)', admission)
        self.assertIn('if (queued) window.decorView.postDelayed(', admission)
        self.assertIn('toast("追加结果未确认，请查看会话")', admission)

    def test_preview_is_screen_projection_not_authoritative_rows(self):
        screen = (CHAT / 'ChatScreen.kt').read_text()
        self.assertIn('submissionTurns(state.turns, state.submissionPreview)', screen)
        self.assertIn('ChatMessageList(displayedTurns, actions', screen)
        preview = (CHAT / 'ChatSubmissionPreview.kt').read_text()
        self.assertIn('ChatTurnUi.User(preview.key, text, pending = true)', preview)
        self.assertIn('turns.take(assistantIndex) + pending + turns.drop(assistantIndex)', preview)
        self.assertIn('turns.take(index)', preview)
        for forbidden in ('database', 'Message(', 'RunStatus.COMPLETED', 'executor', 'controller'):
            self.assertNotIn(forbidden, preview)

    def test_preview_is_retired_by_exact_receipt_not_text_equality(self):
        preview = (CHAT / 'ChatSubmissionPreview.kt').read_text()
        self.assertIn('val settledId = preview.settledUserId ?: preview.settledAssistantId', preview)
        self.assertIn('turns.any { it.id == settledId }', preview)
        self.assertNotIn('it.text ==', preview)
        main = (APP / 'MainActivity.kt').read_text()
        self.assertIn('settledAssistantId = assistant.id.value', main)
        self.assertIn('pending.editorOwner', main)
        self.assertIn('preview.first == selected?.id', main)

    def test_pending_user_is_not_an_action_target(self):
        source = (CHAT / 'ChatMessageList.kt').read_text().split('internal fun UserTurn(', 1)[1].split('internal fun AssistantTurn(', 1)[0]
        self.assertIn('if (!turn.pending) showMenu = true', source)
        self.assertIn('if (actions.editingMessageKey != turn.listKey) MessageActionBar(', source)
        self.assertIn('messageActionsEnabled = !turn.pending', source)
        self.assertIn('branchEnabled = !turn.pending', source)
        self.assertIn('Modifier.testTag("chat-send-preview")', source)
        main = (APP / 'MainActivity.kt').read_text()
        copy = main.split('private fun copyMessage(id: String)', 1)[1].split('private fun cycleChatGptSpeed', 1)[0]
        self.assertIn('submissionTurns(chatState.turns, chatState.submissionPreview).filterIsInstance<ChatTurnUi.User>()', copy)
        self.assertIn('.firstOrNull { it.pending && it.listKey == id }', copy)
        self.assertLess(copy.index('chatState.submissionPreview'), copy.index('val turns = chatState.turns'))
        self.assertNotIn('正在准备发送', source)

    def test_normal_preparation_uses_existing_composer_control_not_extra_status_or_footer(self):
        main = (APP / 'MainActivity.kt').read_text()
        self.assertNotIn('正在准备上下文并核实发送结果', main)
        self.assertNotIn('text = "取消发送准备"', main)
        self.assertIn('val renderedTextSend = pendingTextSend', main)
        self.assertIn('onCancelSend = { renderedTextSend?.let(::cancelPendingTextSend) }', main)
        projection = main.split('preparingSendKey = pendingTextSend?.takeIf', 1)[1].split('editorGeneration =', 1)[0]
        self.assertIn('it.ticket === pending.ticket', projection)
        self.assertIn('?.ticket?.previewKey', projection)
        self.assertNotIn('it.result', projection)
        self.assertIn('statusText = controller.problem ?: when {', main)
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('if (preparingSend) actions.onCancelSend()', composer)
        self.assertNotIn('key(state.preparingSendKey ?: state.controlOwnerKey)', composer)
        self.assertIn('return pointerInput(enabled, owner)', composer)
        self.assertIn('val pressedActions = latest', composer)
        self.assertIn('finally { pressedActions.onControlRelease() }', composer)
        self.assertIn('if (preparingSend) Modifier else Modifier.controlGesture', composer)
        self.assertIn('preparingSend -> "取消发送"', composer)
        self.assertIn('!preparingSend && state.mainContextCompressing', composer)

    def test_pending_draft_is_only_hidden_for_its_exact_editor_and_ticket(self):
        main = (APP / 'MainActivity.kt').read_text()
        pending = main.split('val hideSubmittedDraft =', 1)[1].split('val regeneration =', 1)[0]
        for guard in ('inlineMessageEditor == null',
                      'pending.editorOwner == ChatController.TextSendOwner(selected?.id, editorGeneration, draftRevision)',
                      'pending.text == draft', 'controller.textSendState()?.ticket === pending.ticket'):
            self.assertIn(guard, pending)
        self.assertIn('ComposerUi(text = if (hideSubmittedDraft) "" else inlineMessageEditor?.text ?: draft', main)
        self.assertIn('selectionStart = if (hideSubmittedDraft) 0 else', main)
        self.assertIn('selectionEnd = if (hideSubmittedDraft) 0 else', main)
        self.assertNotIn('draft =', pending)
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('preparingSend -> ChatPreviewIcons.Pause', composer)
        self.assertNotIn('preparingSend -> app.daiyu.android.ui.icons.DaiyuIcons.Stop', composer)
        self.assertIn('preparingSend -> "取消发送"', composer)

    def test_unconsumed_receipt_has_real_activity_and_palette_regressions(self):
        activity = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/TextSendActivityTest.kt').read_text()
        for oracle in ('testCommittedReceiptKeepsPreparingControlUntilUiConsumesIt',
                       'testRejectedReceiptKeepsPreparingControlUntilUiConsumesIt',
                       'TextSendDeliveryGate(fixture.controller)', 'publish.invoke(activity, false, null)',
                       'assertEquals(submission.ticket.previewKey, projected.composer.preparingSendKey)',
                       'assertSame(submission.ticket, checkNotNull(fixture.controller.textSendState()).ticket)'):
            self.assertIn(oracle, activity)
        chrome = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatChromeActivityTest.kt').read_text()
        self.assertIn('testPreparingCancelUsesTheExistingActivePaletteInBothThemes', chrome)
        self.assertIn('colors.appendContent.compositeOver(fill)', chrome)
        self.assertIn('colors.appendContainer.compositeOver(behind)', chrome)

    def test_regeneration_preview_is_admission_scoped_and_rolls_back(self):
        controller = (APP / 'ChatController.kt').read_text()
        action = controller.split('internal fun messageAction(', 1)[1].split('internal fun skillManagement()', 1)[0]
        self.assertLess(action.index('if (!idleAvailable()'), action.index('regenerationPreview = RegenerationPreview(target)'))
        self.assertIn('val actionExecutor = if (stableBranch) messageBranchExecutor else executor', action)
        self.assertLess(action.index('regenerationPreview = RegenerationPreview(target)'), action.index('actionExecutor.execute'))
        self.assertIn('acceptedAssistantId = token?.admittedVoiceOwner?.id?.value', action)
        self.assertIn('regenerationPreview?.target === target', action)
        self.assertIn('RegenerationSendOutcome.ACCEPTED && acceptedAssistantId != null', action)
        self.assertIn('regenerationPreview?.copy(assistantId = acceptedAssistantId) else null', action)

    def test_real_activity_tests_hold_before_commit_and_keep_zero_open_oracle(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatSubmissionActivityTest.kt').read_text()
        for oracle in ('preparing.await(5, TimeUnit.SECONDS)', 'assertEquals(original, fixture.database.messages(conversation.id))',
                       'assertEquals(0, fixture.opens.get())', 'assertNull(fixture.controller.active)',
                       'testRegenerationHidesReplyWhilePreparingAndRestoresItOnRejection',
                       'testAcceptedRegenerationReplacesPreviewOnlyAfterExactNewReplyIsPublished'):
            self.assertIn(oracle, source)


    def test_send_motion_is_owned_outside_the_dual_page_host_and_actions_share_its_clock(self):
        main = (APP / 'MainActivity.kt').read_text()
        motion = (CHAT / 'UserMessageEntrance.kt').read_text()
        screen = (CHAT / 'ChatScreen.kt').read_text()
        rows = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertLess(main.index('val pageUserMessageEntrance ='), main.index('conversation.ChatPageHost('))
        self.assertIn('suppliedUserMessageEntrance = pageUserMessageEntrance', main)
        self.assertIn('val userMessageEntrance = suppliedUserMessageEntrance ?:', screen)
        self.assertNotIn('Animatable(if (request.claim()', motion)
        self.assertLess(motion.index('if (!active)'), motion.index('if (request.claim())'))
        self.assertLess(motion.index('snapshotFlow { !awaitPlacement || motion.positioned }'), motion.index('if (request.claim())'))
        self.assertIn('withTimeoutOrNull(240)', motion)
        self.assertIn('item.offset < layout.viewportEndOffset - currentOcclusionPx', rows)
        self.assertIn('alpha = userMessageActionEntranceAlpha(entering.progress.value)', rows)
        self.assertIn('interactionEnabled = actionsAvailable', rows)

    def test_send_first_paint_waits_for_current_tail_geometry_and_reserves_the_reply_gap(self):
        main = (APP / 'MainActivity.kt').read_text()
        send = main.split('private fun sendDraft(', 1)[1].split('private fun cancelPendingTextSend(', 1)[0]
        self.assertIn('publishChat(rowsChanged = true, scrollRequest =', send)
        self.assertIn('if (queued) ChatScrollRequest(++scrollSerial) else chatState.scrollRequest', send)
        self.assertNotIn('jumpToBottom()', send)
        rows = (CHAT / 'ChatMessageList.kt').read_text()
        for token in ('private val ChatTurnSpacing = 26.dp', 'val pendingReplyGap =',
                      'lastUser.matchesKey(it.key) || lastUser.matchesKey(it.messageId)',
                      'composerBottomInset + ChatTailClearance + pendingReplyGap',
                      'Arrangement.spacedBy(ChatTurnSpacing)',
                      'initialPositionPending || userMessageEntrance?.request?.claimed == false',
                      '(!followBottom || !listState.canScrollForward)',
                      'item.offset + item.size <= layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx + 1',
                      'entrance.updatePlacement(listState, positioned)'):
            self.assertIn(token, rows)
        motion = (CHAT / 'UserMessageEntrance.kt').read_text()
        self.assertIn('else if (placementOwner === owner)', motion)
        after_ime = motion.split('withTimeoutOrNull(240)', 1)[1]
        self.assertLess(after_ime.index('snapshotFlow { !awaitPlacement || motion.positioned }'), after_ime.index('if (request.claim())'))

    def test_media_only_send_and_encoded_mapper_keys_cannot_stall_entrance(self):
        preview = (CHAT / 'ChatSubmissionPreview.kt').read_text()
        rows = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('val text = preview.text.orEmpty()', preview)
        self.assertNotIn('preview.text ?: return turns', preview)
        readiness = rows.split('val entrance = userMessageEntrance ?: return@LaunchedEffect', 1)[1].split(
            '.collect { positioned -> entrance.updatePlacement(listState, positioned) }', 1)[0]
        for token in ('currentTurns.filterIsInstance<ChatTurnUi.User>()',
                      'it.matchesKey(entrance.request.key) || it.matchesKey(entrance.request.messageId)',
                      'map { it.listKey }.toSet()', 'item.key in enteringKeys'):
            self.assertIn(token, readiness)
        self.assertNotIn('item.key == entrance.request.messageId', readiness)
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/UserMessageMediaEntranceTest.kt').read_text()
        for token in ('ChatTurnMapper.map(', 'MessageBlock.Image(image)', 'MessageVideoUi(videoOwner, reference)',
                      'ChatSubmissionPreview(ticket.key)', 'ticket.messageId = durable.id',
                      'mapped media must not remain transparent', 'real thumbnail pixels must paint',
                      'advanceAnimationClock = false', 'message-video-play:1'):
            self.assertIn(token, device)

    def test_retained_chat_page_refreshes_only_ime_geometry_during_the_handoff(self):
        host = (CHAT / 'ChatPageHost.kt').read_text()
        self.assertIn('motionInsets: WindowInsets = WindowInsets.ime', host)
        self.assertIn('val imeBottom = motionInsets.getBottom(density)', host)
        self.assertIn('active && parentAuthorized && frame.imeBottom != imeBottom', host)
        self.assertIn('frame.imeBottom = imeBottom', host)
        self.assertLess(host.index('if (drawn) {'), host.index('val imeBottom ='))
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/UserMessageEntranceTest.kt').read_text()
        for oracle in ('testRetainedPagePaintKeepsFollowingImeUntilTheCandidateIsCommitted',
                       'testShortSendFirstPaintAndFastAcceptedReplyKeepTheFinalTailGap',
                       'testMultilineSendKeepsTheTailGapWhenAcceptanceArrivesAfterTheEntrance',
                       'testZeroDurationSendFromEarlierHistoryIsPlacedBeforeItsFirstPaint',
                       'testAnOldPageCannotRevokeTheNewPagesPlacement',
                       'cached paint must follow the same IME position, not just layout',
                       'the first visible frame must contain real bubble paint',
                       'accepted-preview', 'durable-history'):
            self.assertIn(oracle, device)

if __name__ == "__main__":
    unittest.main()
