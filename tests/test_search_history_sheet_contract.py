"""检查搜索历史弹层展示的源码连接。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class SearchHistorySheetContract(unittest.TestCase):
    def test_blank_query_is_silent_without_falling_into_loading(self):
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        self.assertIn('request.query.isBlank() -> Unit', dialog)
        self.assertNotIn('search_history_empty', dialog)
        self.assertNotIn('"search:prompt"', dialog)
        self.assertIn('search_history_no_results', dialog)
        self.assertIn('phase == SearchPhase.ReadError', dialog)

    def test_both_consumers_use_the_same_search_field(self):
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        picker = (SRC / 'ui/screens/assistants/AssistantPickerDialogs.kt').read_text()
        self.assertIn('AssistantSearchField(', dialog)
        self.assertIn('AssistantSearchField(', picker)
        self.assertNotIn('AssistantPreviewField', dialog)
        self.assertIn('fieldModifier = Modifier.testTag("search:query")', dialog)
        self.assertIn('Modifier.testTag("assistant-search-input")', picker)
        field = (SRC / 'ui/screens/assistants/AssistantSearchField.kt').read_text()
        for token in ('44.dp * s', '42.dp * s', '13.dp * s', '11.dp * s', '8.dp * s',
                      '18.dp * s', '(14f * s).sp', '(20f * s).sp', 'managedInput()',
                      'imeCursorBrush(colors.accent)', 'if (focused) colors.accent else colors.line',
                      'AssistantPreviewIcons.Search', 'if (value.isEmpty())'):
            self.assertIn(token, field)

    def test_only_history_opts_into_the_existing_default_height(self):
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        shell = (SRC / 'ui/components/UsagePresentation.kt').read_text()
        self.assertIn('useDefaultHeight = true', dialog)
        self.assertIn('useDefaultHeight: Boolean = false', shell)
        self.assertIn('if (useDefaultHeight && !compact)', shell)
        self.assertIn('Modifier.height(defaultBottomSheetHeight(maxHeight))', shell)
        self.assertIn('else Modifier.heightIn(max = minOf(if (compact) 620.dp else 550.dp, available))', shell)
        self.assertLess(shell.index('.padding(10.dp)'), shell.index('Modifier.height(defaultBottomSheetHeight(maxHeight))'))

    def test_results_fill_the_remainder_without_the_old_360dp_cap(self):
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        self.assertNotIn('fill = false', dialog)
        self.assertNotIn('360.dp', dialog)
        self.assertIn('LazyColumn(Modifier.fillMaxSize()', dialog)
        self.assertIn('state = if (useResultsPosition) listState else loadingListState', dialog)

    def test_search_and_owner_guards_remain_wired(self):
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        for token in ('it.take(1000)', 'MessageSearchRequest(query.trim(), conversationId)',
                      'debounceMillis = 180', 'now.generation == generation', 'hit.conversationId == conversationId',
                      'requests.cancel(); onDismiss()', 'SearchAppendPhase.Invalidated'):
            self.assertIn(token, dialog)

    def test_body_only_search_preserves_roles_and_centers_only_initial_loading(self):
        source = (SRC / 'ui/RetainedMessageText.kt').read_text()
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        self.assertIn('filterIsInstance<MessageBlock.Text>()', source)
        self.assertNotIn('block.invocation', source)
        self.assertIn('"${hit.roleLabel} · ${hit.snippet}"', dialog)
        self.assertIn('contentAlignment = Alignment.Center', dialog)
        self.assertIn('SearchHistoryLoading { cancelSearch() }', dialog)
        self.assertIn('SearchAppendPhase.Loading -> SearchLoading', dialog)
        self.assertIn('testTag("search-history-content")', dialog)

    def test_resume_keeps_request_and_results_without_automatic_reread(self):
        dialog = (SRC / 'ui/app/SearchHistoryDialog.kt').read_text()
        self.assertIn('searchedQuery != query', dialog)
        self.assertIn('searchedQuery = query', dialog)
        self.assertIn('visit = Any()', dialog)
        self.assertNotIn('if (!resumed) requests.cancel()', dialog)
        self.assertNotIn('restoreCount', dialog)
        self.assertNotIn('requests.loadMore()\n                else', dialog)
        self.assertIn('onDispose { lifecycle.removeObserver(observer); requests.cancel() }', dialog)

    def test_android_regressions_keep_page_prompt_but_remove_dialog_prompt(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/SearchHistoryDialogTest.kt').read_text()
        host = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/SearchHostActivityTest.kt').read_text()
        self.assertIn('testSameAssistantHeightAcrossBlankLoadingResultsEmptyAndClear', test)
        self.assertIn('AssistantPreviewDialog(true', test)
        self.assertIn('node("search:prompt").assertDoesNotExist()', test)
        manage = host.split('fun testManageMessageEntryHasNoFakeCurrentScopeAndBackDoesNotExitManage()', 1)[1].split('\n    fun ', 1)[0]
        self.assertIn('node("search:prompt").assertExists()', manage)
        failure = host.split('fun testRealReaderFailureIsRetryableAndDifferentFromEmptyAndBlank()', 1)[1].split('\n    fun ', 1)[0]
        self.assertIn('node("search:prompt").assertDoesNotExist()', failure)

    def test_history_hit_retires_tail_display_before_publishing_owner_window(self):
        host = (SRC / 'MainActivity.kt').read_text()
        action = host.split('private fun openHistoryHit(hit: MessageSearchHit)', 1)[1].split('private fun toast', 1)[0]
        steps = ('hit.ownerMessageId', 'val window = controller.database.messages',
                 'openRetainedConversation(hit.conversationId)', 'displayWindow = null',
                 'shown = window', 'prepareMessageActionSnapshot()', 'publishChat()',
                 'requestScroll(hit.messageId, false, hit.segmentIndex)')
        positions = [action.index(step) for step in steps]
        self.assertEqual(sorted(positions), positions)
