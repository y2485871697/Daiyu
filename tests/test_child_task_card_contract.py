"""Chat task cards expose progress, not protocol diagnostics or a record-page route."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class ChildTaskCardContractTest(unittest.TestCase):
    def test_task_status_and_body_use_one_projection_without_protocol_fields(self):
        source = (CHAT / 'ToolRichDetails.kt').read_text()
        branch = source.split('"delegation", "child_control" -> {', 1)[1].split('"mcp" -> {', 1)[0]
        self.assertIn('childTaskReceiptEntries(output)', branch)
        self.assertIn('!launchingDelegation', branch)
        for label in ('读取偏移', '记录状态', '任务详情', '已读取结果'):
            self.assertNotIn(label, branch)
        card = (CHAT / 'ChildTaskCardPresentation.kt').read_text()
        for signal in ('journal_unconfirmed', 'child_result_unconfirmed', 'live_state_available', 'settled'):
            self.assertIn(signal, card)
        self.assertIn('childTaskProgressText', card)
        identity = (CHAT / 'ChildTaskCardIdentity.kt').read_text()
        self.assertIn('childTaskReceiptTargets', identity)
        self.assertIn('names[owner]', identity)
        self.assertIn('presentation.details.rich.bodyRestricted', identity)
        self.assertNotIn('available_profile', identity)
        mapper = (CHAT / 'ChatTurnMapper.kt').read_text()
        display = mapper[mapper.index('internal fun mapDisplay('):mapper.index('private data class RenderMessage')]
        self.assertIn('childTaskNames: Map<ChildRunOwner, String>', display)
        self.assertIn('{ emptyList() }, reconnects, childTaskNames)', display)
        activity = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('Snapshot.read(controller.database, rows, display)', activity)
        self.assertIn('childTaskNames = visibleChildNames', activity)
        create = activity[activity.index('internal fun createNewConversation()'):activity.index('internal fun select(conversation: Conversation?)')]
        self.assertIn('subAgentRepository.createConversation', create)
        self.assertIn('source?.let { id ->', create)
        self.assertNotIn('applyPreset', create)



    def test_short_long_and_completed_content_share_a_non_code_frame(self):
        source = (CHAT / 'ChildTaskCardContent.kt').read_text()
        for fragment in ('RoundedCornerShape(8.dp)', '.background(colors.soft)', '.border(1.dp, colors.line, shape)',
                         'ChildTaskFrame(Modifier.testTag("tool-child-receipt:', 'maxLines = 6', 'TextOverflow.Ellipsis', 'var truncated by remember(body)'):
            self.assertIn(fragment, source)
        self.assertNotIn('FontFamily.Monospace', source)
        self.assertIn('if (truncated) Modifier.conversationClickable', source)
        self.assertIn('onTextLayout = { truncated = it.hasVisualOverflow }', source)
        self.assertIn('titleOverride = agentName, plainText = true', source)
        self.assertIn('var expanded by rememberSaveable(index, entry.title)', source)
        self.assertIn('tool-child-receipt-arrow:$index', source)
        self.assertIn('if (expanded) ChildTaskProgressContent(', source)
        self.assertNotIn('toolOutputPreview(body)', source)
        self.assertNotIn('Int.MAX_VALUE', source)
        self.assertNotIn('ToolMiniAction', source)

    def test_live_cards_use_frozen_identity_progress_and_existing_exact_controls(self):
        source = (CHAT / 'ChildTaskToolContent.kt').read_text()
        for fragment in ('owners.associateWith(controller::childTaskProgress)', 'live.forEach { merged[it.record.owner] = it.record }',
                         'ChildTaskFrame(Modifier.testTag("tool-child-frame:', 'ChildTaskProgressContent(',
                         'ToolCardAction.PAUSE_TASK', 'ToolCardAction.RESUME_TASK', 'ToolCardAction.STOP_TASK'):
            self.assertIn(fragment, source)
        self.assertNotIn('SettingsExpandableContent', source)
        self.assertNotIn('tool-child-batch-summary', source)
        self.assertIn('verticalAlignment = Alignment.CenterVertically', source)
        self.assertIn('.heightIn(min = 28.dp)', source)
        self.assertIn('var expanded by rememberSaveable(row.owner)', source)
        self.assertIn('tool-child-arrow:${row.owner.childRunId}', source)
        self.assertIn('if (expanded) {\n            details()\n            controls()\n        }', source)
        self.assertNotIn('else preview()', source)
        self.assertNotIn('preview: @Composable', source)
        self.assertIn('settingsDisclosureRotation(expanded, expandedDegrees = 180f)', source)
        for debug in ('READ_TASK_RESULT', 'ToolDisclosureUi("原因"', 'ToolDisclosureUi("任务"', 'scrub(row.model'):
            self.assertNotIn(debug, source)

    def test_child_category_stops_before_generic_debug_body(self):
        source = (CHAT / 'ToolCategoryContent.kt').read_text()
        child = source.index('ChildTaskReceiptContent(details)')
        self.assertLess(child, source.index('Fields(body.fields)'))
        self.assertIn('return@Column', source[child:source.index('Fields(body.fields)')])
        cards = (CHAT / 'ChildTaskCardContent.kt').read_text()
        self.assertLess(cards.index('if (rich.bodyRestricted)'), cards.index('rich.entries.forEachIndexed'))


if __name__ == '__main__':
    unittest.main()
