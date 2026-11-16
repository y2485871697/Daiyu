"""Keep settled-message editing wired to fresh authoritative snapshots and unchanged commit CAS."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class MessageEditRevalidationContract(unittest.TestCase):
    def test_snapshot_acceptance_rebinds_only_a_checked_edit_and_revokes_its_old_visit(self):
        source = (SRC / 'MainActivity.kt').read_text()
        accepted = source.split('messageActionSnapshot = candidate; messageActionKey = key', 1)[1].split('publishChat()', 1)[0]
        self.assertIn('candidate.refreshedEditTarget(editor.key, editor.target, editor.baseline)', accepted)
        self.assertIn('target = target, baseline = candidate, visit = Any()', accepted)
        self.assertNotIn('onSaveEdit', accepted)
        self.assertNotIn('onSend(', accepted)

    def test_initial_editor_captures_the_snapshot_that_authorized_its_source(self):
        source = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('val baseline: app.daiyu.android.storage.MessageActionSnapshot', source)
        self.assertIn('InlineMessageEditor(key, outcome.text, target,\n                                checkNotNull(actionSnapshot)', source)
        self.assertIn('messageActionSnapshot == null -> "暂时无法核对消息；未保存编辑已保留"', source)

    def test_revalidation_keeps_owner_binding_and_history_structure_checks(self):
        source = (SRC / 'storage/MessageActionStore.kt').read_text()
        method = source.split('fun refreshedEditTarget(', 1)[1].split('fun matchesRendered', 1)[0]
        self.assertIn('original != (previous.renderedTargets[key] ?: previous.targets[key])', method)
        self.assertIn('current != original.copy(revision = revision)', method)
        self.assertIn('previous.editBaseline?.canRefresh(editBaseline, original) == true', method)
        baseline = (SRC / 'storage/MessageEditBaseline.kt').read_text()
        for required in ('rows.size != next.rows.size', 'index > source', 'before.run == target.runId',
                         'before.tailRevision == after.tailRevision', 'history.none { it.status == RunStatus.RUNNING }',
                         'segment.role != Message.ASSISTANT_ROLE'):
            self.assertIn(required, baseline)

    def test_final_storage_compare_and_swap_is_not_relaxed(self):
        source = (SRC / 'storage/MessageActionStore.kt').read_text()
        self.assertIn('ContextRevision.digest(history) == target.revision', source)
        self.assertIn('check(history == revision.original)', source)
        self.assertIn('history.none { it.status == RunStatus.RUNNING }', source)

    def test_cloud_unit_filter_and_activity_regression_are_wired(self):
        workflow = (ROOT / '.github/workflows/media-inline-checks.yml').read_text()
        self.assertIn("--tests '*MessageEditBaselineTest'", workflow)
        self.assertIn("'MessageEditBaselineTest'", workflow)
        self.assertIn('test_message_edit_revalidation_contract.py', workflow)
        activity = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/MessageActionsActivityTest.kt').read_text()
        self.assertIn('testStoppedAssistantSettlingRevalidatesTheUnchangedUserWithoutLosingItsEdit', activity)
        self.assertIn('testSameConversationRevisionChangeKeepsUnsavedTextAndBlocksOldTarget', activity)

if __name__ == '__main__':
    unittest.main()
