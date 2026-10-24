"""Source-only integration guards for the composer controls; not runtime acceptance."""
from pathlib import Path
import math
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
DOMAIN = ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain'


class RunControlsContractTest(unittest.TestCase):
    def test_active_text_uses_supplement_without_long_press_stop(self):
        composer = (APP / 'ui/screens/chat/conversation/ChatComposer.kt').read_text()
        model = (APP / 'ui/screens/chat/conversation/ChatScreenModels.kt').read_text()
        self.assertIn('val textAction = hasText', composer)
        self.assertIn('else actions.onSupplement(state.text)', composer)
        self.assertIn('onLongClick = if (textAction) ({}) else if (state.phase != ComposerPhase.IDLE)', composer)
        self.assertIn('ComposerPhase.PAUSED, ComposerPhase.RUNNING -> text.isNotBlank() && supplementAvailable', model)

    def test_append_outline_ink_center_and_stroke_stay_inside_viewport(self):
        icons = (APP / 'ui/icons/ChatPreviewIcons.kt').read_text()
        self.assertIn('m19 5-6 18-4-8-8-4 18-6ZM9 15 19 5', icons)
        # Length-weighted stroke centroid, including the internal fold.
        points = [(19, 5), (13, 23), (9, 15), (1, 11), (19, 5)]
        segments = list(zip(points, points[1:])) + [((9, 15), (19, 5))]
        lengths = [math.dist(a, b) for a, b in segments]
        center = [sum((a[i] + b[i]) / 2 * length for (a, b), length in zip(segments, lengths)) / sum(lengths) for i in (0, 1)]
        self.assertLess(math.dist(center, (12, 12)), .15)
        self.assertGreaterEqual(min(min(p) for p in points) - .85, 0)
        self.assertLessEqual(max(max(p) for p in points) + .85, 24)

    def test_runtime_uses_atomic_live_admission_and_preserves_barrier_generation(self):
        runner = (DOMAIN / 'ChatRunner.kt').read_text()
        admission = runner.split('fun admitSupplement(', 1)[1].split('private fun completeAdmission', 1)[0]
        self.assertNotIn('!pauseRequested ||', admission)
        self.assertIn('pendingSupplements.addLast(pending)', admission)
        self.assertIn('if (pauseRequested) { pauseRequested = false; pauseRevision++ }', admission)
        self.assertIn('val interrupt = requestOpening || execution.phase == ExecutionPhase.STREAMING ||', admission)
        self.assertIn('if (interrupt) {', admission)
        self.assertIn('queuedSupplementMessages()', admission)
        self.assertIn('rawText.length > 64_000', admission)
        self.assertIn('queued.size >= 16', admission)
        self.assertIn('pendingSupplements.isNotEmpty()', runner)
        store = (APP / 'storage/SQLiteRuntimeStore.kt').read_text()
        self.assertIn('checkpoint.phase == ExecutionPhase.PAUSED || checkpoint.phase == ExecutionPhase.BOUNDARY', store)

    def test_receipt_and_exact_editor_survive_activity_recreation(self):
        controller = (APP / 'ChatController.kt').read_text()
        activity = (APP / 'MainActivity.kt').read_text()
        state = (APP / 'ui/actions/ConversationActionState.kt').read_text()
        self.assertIn('internal val supplementDraft =', controller)
        self.assertIn('supplementDraft.receive(result)', controller)
        self.assertIn('private val supplementDraft get() = controller.supplementDraft', activity)
        self.assertIn('supplement-editor-generation', activity)
        self.assertIn('supplementMountEditor == current', activity)
        self.assertIn('initialSupplementRestore', activity)
        self.assertIn('ticket.editor == current', state)
        self.assertIn('result.messageId == ticket.messageId && receipt == null', state)
        settlement = activity.split('private fun settleSupplementReceipt()', 1)[1].split('private fun supplementMediaBlocked', 1)[0]
        self.assertLess(settlement.index('updateDraft("")'), settlement.index('supplementDraft.finish('))
        self.assertIn('if (draft.isNotEmpty()) return', settlement)

    def test_owner_fence_manual_browser_and_video_authority_gate_admission(self):
        controller = (APP / 'ChatController.kt').read_text()
        self.assertIn('!it.fenced && it.runner === runner', controller)
        self.assertIn('it.browserVisual?.automationHeld != true', controller)
        self.assertIn('videoSession?.assistant?.runId != owner.runId', controller)
        activity = (APP / 'MainActivity.kt').read_text()
        self.assertIn('editorMatches() && currentChatControlAvailable() && renderedOwner != null', activity)
        self.assertIn('!supplementMediaBlocked()', activity)
        self.assertIn('追加已排队，等待当前提问处理', activity)

    def test_parent_pause_epoch_is_atomic_and_new_reservations_inherit(self):
        source = (DOMAIN / 'delegation/RetainedChildTasks.kt').read_text()
        self.assertEqual(3, source.count('inheritPauseGroup('))  # two reservations plus declaration
        self.assertIn('binding.pauseGroup ?: ChildTaskPauseGroup(binding)', source)
        self.assertIn('task.initial.owner !in group.manual', source)
        self.assertIn('it.members.remove(owner); it.manual.add(owner)', source)
        self.assertIn('if (group.consumed || binding.pauseGroup !== group) return false', source)
        self.assertIn('group.consumed = true; binding.pauseGroup = null', source)
        resume = source.split('fun resumeGroup(', 1)[1].split('fun finishGroup(', 1)[0]
        stop = source.split('fun finishGroup(', 1)[1].split('fun pause(binding:', 1)[0]
        for action in (resume, stop):
            self.assertIn('it.record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)', action)
            self.assertIn('!it.journalUnconfirmed', action)
            self.assertIn('!it.settled', action)

    def test_main_stop_and_cancel_tree_have_distinct_cleanup(self):
        controller = (APP / 'ChatController.kt').read_text()
        stop_main = controller.split('internal fun stopMain(', 1)[1].split('// True acknowledges', 1)[0]
        self.assertIn('capturePausedChildren(lifetime)', stop_main)
        self.assertIn('childStopChoices.addLast', stop_main)
        self.assertIn('lifetime.runner.stop()', stop_main)
        self.assertNotIn('revokeChildren()', stop_main)
        self.assertIn('lifetime.runner.stop();lifetime.tools.revokeChildren();lifetime.tools.close()', controller)
        activity = (APP / 'MainActivity.kt').read_text()
        self.assertIn('controller.stopMain(owner)', activity)
        self.assertIn('onDismissRequest = { finish(false) }', activity)
        self.assertIn('main-stop-keep-children', activity)
        self.assertIn('main-stop-cancel-children', activity)

    def test_child_stop_choice_has_observable_projection_before_refresh_early_returns(self):
        activity = (APP / 'MainActivity.kt').read_text()
        self.assertIn('private var childStopChoice by mutableStateOf<ChatController.ChildStopChoice?>(null)', activity)
        refresh = activity.split('private fun refresh() {', 1)[1]
        self.assertLess(refresh.index('childStopChoice = controller.childStopChoice()'), refresh.index('if (controller.dataBusy)'))
        surface = activity.split('@Composable private fun ChildStopChoiceSurface()', 1)[1].split('@Composable private fun UnknownBranchSurface()', 1)[0]
        self.assertIn('val choice = childStopChoice ?: return', surface)
        self.assertNotIn('val choice = controller.childStopChoice()', surface)
        self.assertLess(surface.index('controller.finishChildStopChoice(choice, stop)'), surface.index('refresh()'))

    def test_explicit_resume_releases_same_group_before_completion_can_publish(self):
        controller = (APP / 'ChatController.kt').read_text()
        resume = controller.split('internal fun resume(owner:Message)', 1)[1].split('internal fun stopMain(', 1)[0]
        self.assertTrue(resume.lstrip().startswith(':Boolean = synchronized(this)'))
        self.assertEqual(1, resume.count('ownedRunner(owner)'))
        self.assertLess(resume.index('ownedRunner(owner)'), resume.index('lifetime.runner.resume()'))
        self.assertIn('resumePausedChildren(lifetime, lifetime.childPauseEpoch)', resume)

    def test_supplement_receipt_resumes_only_original_pause_epoch(self):
        controller = (APP / 'ChatController.kt').read_text()
        self.assertIn('lifetime.childPauseEpoch != expectedEpoch', controller)
        self.assertIn('lifetime.runner.state().pauseRequested || lifetime.fenced', controller)
        self.assertIn('result is SupplementAdmission.Accepted && lifetime != null', controller)
        self.assertIn('resumePausedChildren(lifetime, pausedChildrenEpoch)', controller)
        self.assertIn('lifetime.childrenPauseRequested = false; lifetime.pausedChildren = null', controller)


if __name__ == '__main__':
    unittest.main()
