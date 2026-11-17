"""Readable active and archived forms share the production question-card layout."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CARD = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/QuestionCard.kt'
DEVICE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/QuestionCardReadabilityTest.kt'
WORKFLOW = ROOT / '.github/workflows/chat-workflow-checks.yml'


class QuestionCardReadabilityContractTest(unittest.TestCase):
    def test_question_and_option_content_use_readable_sizes(self):
        text = CARD.read_text()
        self.assertIn('Text(value.question.question, color = colors.text, style = questionTextStyle(16, 1.6f))', text)
        option = text.split('private fun QuestionOptionRow(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('questionTextStyle(15, 1.6f, FontWeight.Medium)', option)
        self.assertIn('questionTextStyle(14, 1.6f)', option)
        self.assertIn('heightIn(min = 48.dp)', option)
        self.assertIn('.size(18.dp)', option)
        self.assertTrue(all(int(n) >= 11 for n in re.findall(r'questionTextStyle\((\d+),', text)))

    def test_other_and_note_keep_original_full_width_multiline_input_style(self):
        text = CARD.read_text()
        field = text.split('private fun QuestionInput(', 1)[1].split('\n@Composable', 1)[0]
        for token in ('val container = daiyuInputContainerColor()', 'RoundedCornerShape(14.dp)',
                      'modifier.managedInput().fillMaxWidth()', 'minLines = 1', 'maxLines = 6',
                      'heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp)',
                      'contentAlignment = Alignment.CenterStart', 'colors.accent.copy(alpha = .45f)'):
            self.assertIn(token, field)
        self.assertNotIn('max = 160.dp', field)
        self.assertNotIn('Modifier.padding(start = ', text)

    def test_archive_uses_confirmed_answer_and_full_option_layout_without_fading(self):
        text = CARD.read_text()
        for token in ('val archived = confirmedAnswer != null', 'val selection = confirmedAnswer ?: draft.selection',
                      'confirmedAnswer.text else draft.other', 'confirmedAnswer.note.orEmpty() else draft.note',
                      'if (value.interrupted && !archived)', 'if (editable || archived) 1f else .5f',
                      'if (!archived) {', 'mutableStateOf(!archived)'):
            self.assertIn(token, text)
        self.assertNotIn('已选择：', text)
        self.assertEqual(1, text.count('value.question.options.forEachIndexed'))

    def test_only_confirmed_submission_collapses_and_choices_stay_open(self):
        text = CARD.read_text()
        self.assertIn('LaunchedEffect(owner, archived)', text)
        self.assertIn('if (archived && expanded)', text)
        effect = text.split('LaunchedEffect(owner, archived)', 1)[1].split('val headingInteraction', 1)[0]
        self.assertIn('disclosure.toggle(opening = false)', effect)
        self.assertIn('expanded = false', effect)
        choices = text.split('value.question.options.forEachIndexed', 1)[1].split('if (value.question.allowNote', 1)[0]
        self.assertNotIn('expanded =', choices)
        self.assertNotIn('disclosure.toggle', choices)
        self.assertNotIn('selectAnswer(', text)
        for token in ('testEveryPendingChoiceStaysExpandedWithoutSubmittingAndKeepsDraft',
                      'testSubmissionWaitsForConfirmedSaveBeforeCollapsingAndCanBeReopened',
                      'testRejectedAndUnconfirmedSubmissionsStayExpanded',
                      'testSavedRefreshFailureStillArchivesWithoutEnablingResubmission',
                      'private fun openArchived()', 'assertCollapsed()', 'Reply.UNCONFIRMED'):
            self.assertIn(token, DEVICE.read_text())

    def test_confirmed_answer_handoff_keeps_one_card_callsite_without_restoring_authority(self):
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        dedicated = host.split('private fun DedicatedQuestion(', 1)[1].split('private fun ', 1)[0]
        self.assertEqual(1, dedicated.count('QuestionCard('))
        self.assertIn('QuestionCard(question.identity, question.record, canAnswer, question.binding)', dedicated)
        self.assertNotIn('if (binding != null)', dedicated)
        projection = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ChatController.kt').read_text().split(
            'internal data class QuestionProjection(', 1)[1].split('@Synchronized internal fun questionProjection(', 1)[0]
        for token in ('record.answer ?: binding?.savedAnswer(identity)',
                      'it.identity == identity && it.record.question == record.question',
                      'it.record.answer ?: it.binding?.savedAnswer(identity)',
                      'confirmed().copy(binding = null, canAnswer = false)'):
            self.assertIn(token, projection)
        card = CARD.read_text()
        self.assertIn('canAnswer && binding != null', card)
        self.assertIn('if (binding != null) binding.submit(identity, answer, done) else done(Reply.STOPPED)', card)
        binding = (ROOT / 'app/src/main/kotlin/app/daiyu/android/question/QuestionCardBinding.kt').read_text()
        accessor = binding.split('internal fun savedAnswer(', 1)[1].split('init {', 1)[0]
        self.assertIn('drafts[identity]?.saved', accessor)
        self.assertNotIn('selection', accessor)
        self.assertIn('testConfirmedSubmissionStaysAnsweredAndKeepsDisclosureAcrossReadOnlyRefresh', DEVICE.read_text())

    def test_archived_field_is_complete_static_text_not_an_input_target(self):
        text = CARD.read_text()
        field = text.split('private fun QuestionInput(', 1)[1].split('\n@Composable', 1)[0]
        archived = field.split('if (archived) {', 1)[1].split('return', 1)[0]
        self.assertIn('Text(value.ifEmpty { hint }', archived)
        self.assertIn('.background(container)', archived)
        self.assertIn('.border(1.dp, colors.line, shape)', archived)
        for token in ('BasicTextField', 'managedInput', 'clickable', 'maxLines', '.alpha('):
            self.assertNotIn(token, archived)
        self.assertEqual(2, text.count('archived = archived)'))

    def test_blank_archived_inputs_and_their_spacing_are_omitted_only_after_confirmation(self):
        text = CARD.read_text()
        normalized = ' '.join(text.split())
        self.assertIn('if (selection is QuestionAnswer.Other && (!archived || otherText.isNotBlank())) {', normalized)
        note_guard = 'if (value.question.allowNote && selection !is QuestionAnswer.Other && (!archived || noteText.isNotBlank())) {'
        self.assertIn(note_guard, normalized)
        note_block = normalized.split(note_guard, 1)[1].split('if (!archived)', 1)[0]
        self.assertIn('Spacer(Modifier.height(9.dp))', note_block)
        self.assertIn('QuestionInput(noteText,', note_block)
        self.assertEqual(1, text.count('Spacer(Modifier.height(9.dp))'))
        for token in ('testBlankArchivedInputsAreHiddenWhilePendingInputsRemainAvailable',
                      'testBlankNoteDisappearsAfterLiveSubmissionAndStaysHiddenOnRemount',
                      'for (blank in listOf<String?>(null, "",',
                      'QuestionAnswer.Other(blank.orEmpty())', 'assertEquals(expected, submitted)',
                      'node("question-note").assertDoesNotExist()'):
            self.assertIn(token, DEVICE.read_text())

    def test_other_hides_note_without_clearing_its_draft_or_submitting_it(self):
        text = CARD.read_text()
        self.assertIn('value.question.allowNote && selection !is QuestionAnswer.Other', text)
        self.assertIn('value.question.allowNote && draft.selection !is QuestionAnswer.Other && it.isNotEmpty()', text)
        select = text.split('fun select(answer: QuestionAnswer)', 1)[1].split('fun editOther', 1)[0]
        self.assertNotIn('draft.note =', select)
        self.assertIn('QuestionAnswer.Other(draft.other, note)', text)

    def test_option_indicator_and_text_block_center_within_symmetric_background(self):
        option = CARD.read_text().split('private fun QuestionOptionRow(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('verticalAlignment = Alignment.CenterVertically', option)
        self.assertIn('.padding(horizontal = 7.dp, vertical = 9.dp)', option)
        self.assertNotIn('Alignment.Top', option)
        self.assertNotIn('padding(top = 3.dp)', option)
        self.assertIn('question-option-indicator', option)
        self.assertIn('question-option-text', option)

    def test_draft_owner_and_submission_authority_are_unchanged(self):
        text = CARD.read_text()
        for token in ('val session = remember(owner, binding)', 'session.editOther(it)', 'session.editNote(it)',
                      'enabled = submitEnabled, role = Role.Button, onClick = session::submit',
                      'questionTextLimit(it)', 'WorkflowExpandableContent(visibility, disclosure)',
                      'if (!editable) return', 'Reply.SAVED || reply == Reply.SAVED_REFRESH_FAILED'):
            self.assertIn(token, text)

    def test_text_measurement_keeps_real_bounds_and_rejects_clipping(self):
        device = DEVICE.read_text()
        for token in ('measuredTextLayout(raw)', 'assertEquals(raw.size, it.size)',
                      'TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0)',
                      'constraints = Constraints.fixed(size.width, size.height)',
                      'IntSize(1, result.size.height)', 'IntSize(result.size.width, 1)',
                      'it.hasVisualOverflow', 'it.getLineEnd(it.lineCount - 1, visibleEnd = true)'):
            self.assertIn(token, device)

    def test_exact_cloud_scope_and_real_device_assertions_cover_new_behaviors(self):
        workflow = WORKFLOW.read_text()
        self.assertIn('message-actions, question-card]', workflow)
        self.assertIn("patterns = ('test_question_card_readability_contract.py', 'test_stream_question_projection_contract.py', 'test_question_card_smoke_scope.py')", workflow)
        self.assertIn('filters=(--tests app.daiyu.android.question.QuestionCheckpointEvidenceTest', workflow)
        self.assertIn("expected = {'QuestionCheckpointEvidenceTest', 'QuestionToolBridgeTest'}", workflow)
        device = DEVICE.read_text()
        for token in ('assertArchived(0)', 'assertArchived(1)', 'assertArchived(2)',
                      'QuestionAnswer.Delegate(longText)', 'QuestionAnswer.Other(custom)',
                      'node("question-note").assertDoesNotExist()', 'hasSetTextAction()',
                      'isFocused()', 'Indicator centered', 'Text centered', 'SAVED_REFRESH_FAILED',
                      'twoLineLabel = true', 'assertEquals(2, layout(', 'assertEquals(1, layout('):
            self.assertIn(token, device)
