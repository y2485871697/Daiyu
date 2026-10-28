"""Ordinary dialog presentation survives pause; foreground capabilities never do."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
APP=R/'app/src/main/kotlin/app/daiyu/android'

class OrdinaryDialogLifecycleContract(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.main=(APP/'MainActivity.kt').read_text()
 def section(self,start,end):
  return self.main.split(start,1)[1].split(end,1)[0]
 def test_pause_revokes_actions_without_deleting_six_presentations(self):
  pause=self.section('override fun onPause()', 'override fun onStop()')
  stop=self.section('override fun onStop()', 'override fun onDestroy()')
  self.assertIn('suspendOrdinaryDialogActions()',pause)
  for source in (pause,stop):
   for forbidden in ('revokeWorkspaceVisit()', 'dismissChatModels()', 'revokeTerminalJobsVisit()', 'composerShortcutPick = null'):
    self.assertNotIn(forbidden,source)
  self.assertIn('preserveShortcut = true',pause)
  self.assertIn('preserveShortcut = true',stop)
  self.assertIn('cancelBrowserDownloadVisit(preservePresentation = true)',pause)
  self.assertIn('preserveOutputPresentation = true',pause)
 def test_resume_creates_new_grant_and_only_rereads_metadata(self):
  resume=self.section('private fun resumeOrdinaryDialogActions()', 'private var workspaceVisit')
  self.assertIn('ordinaryDialogLease?.revoke()',resume)
  self.assertIn('DialogActionLease()',resume)
  self.assertIn('revalidateWorkspacePresentation()',resume)
  self.assertIn('reloadChatModels(it, retainPresentation = true)',resume)
  self.assertIn('resumeOutputPresentations()',resume)
  for forbidden in ('saveConversationWorkspace(', 'chooseComposerShortcut(', 'sendTerminal', 'startActivityForResult', 'prepareGeneration'):
   self.assertNotIn(forbidden,resume)
 def test_workspace_mount_and_callbacks_use_separate_predicates(self):
  surface=self.section('workspaceVisit?.let { visit ->','val owner = collaborationOwner')
  self.assertIn('if (workspacePresented(visit))',surface)
  self.assertNotIn('if (workspaceCurrent(visit))',surface)
  self.assertGreaterEqual(surface.count('dialogActionsCurrent(lease)'),9)
  self.assertIn('workspaceVisit === visit',surface)
  worker=self.section('private fun persistConversationWorkspaces(', 'private var childStatusVisit')
  self.assertLess(worker.index('check(lease.isActive)'),worker.index('check(change())'))
  self.assertIn('workspacePresented(saving)',worker)
 def test_terminal_workers_capture_atomic_lease_and_display_is_not_foreground_gated(self):
  surface=self.section('@Composable private fun TerminalTasksSurface()', 'private fun terminalTaskSummary')
  self.assertIn('takeIf { terminalJobsPresented(it) }',surface)
  self.assertGreaterEqual(surface.count('dialogActionsCurrent(lease)'),4)
  worker=self.section('private fun refreshTerminalJobs(', 'private fun prepareTerminal(')
  self.assertGreaterEqual(worker.count('lease.isActive && !visit.revoked.get()'),4)
  self.assertIn('terminalJobsPresented(visit)',worker)
 def test_models_reload_without_unmounting_the_searchable_picker(self):
  reload=self.section('private fun reloadChatModels(', 'private fun selectChatModel(')
  self.assertIn('if (!retainPresentation)',reload)
  self.assertIn('chatModelPicker?.takeIf { retainPresentation }?.copy(',reload)
  select=self.section('private fun selectChatModel(', 'private fun gatewayCurrent')
  self.assertIn('cancelled.get() || !lease.isActive',select)
  self.assertIn('dialogActionsCurrent(lease)',select)
 def test_output_displays_keep_their_owner_but_do_not_keep_saf_capabilities(self):
  clear=self.section('private fun clearGenerationOutputs(', 'private fun openGenerationOutputs()')
  self.assertIn('if (!preservePresentation)',clear)
  self.assertIn('cancelGenerationMedia()',clear)
  self.assertIn('cancelStaticGenerationVisit()',clear)
  browser=self.section('private fun cancelBrowserDownloadVisit(', 'private fun browserDownloadPresented')
  self.assertIn('browserDownloadVisit?.active?.set(false)',browser)
  self.assertIn('pendingBrowserDownload = null',browser)
  self.assertIn('if (!preservePresentation) browserDownloadOutputs = null',browser)
  self.assertIn('generationOutputsOwner != null && generationOutputsOwner == selected?.id?.let',self.main)
 def test_readonly_resume_never_orphans_exact_saf_static_save_grant(self):
  reload=self.section('private fun reloadGenerationOutputs()', 'private fun pickStaticGenerationOriginal(')
  self.assertIn('staticGenerationVisit?.takeIf { it.get() }',reload)
  self.assertIn('?: java.util.concurrent.atomic.AtomicBoolean(true).also { staticGenerationVisit = it }',reload)
  cancel=self.section('private fun cancelStaticGenerationVisit()', 'private fun cancelGenerationOutputs()')
  self.assertIn('staticGenerationVisit?.set(false); staticGenerationVisit = null',cancel)
 def test_sensitive_confirmations_still_revoke_on_pause(self):
  pause=self.section('override fun onPause()', 'override fun onStop()')
  for required in ('revokeCommandApproval()', 'cancelShizukuEditor()', 'cancelTaskLocation', 'cancelRestorePreview()', 'voiceConversationDialogOwner = null', 'stopCascadeVoice()', 'cancelBrowserEditor('):
   self.assertIn(required,pause)
 def test_explicit_exit_still_discards_presentation_and_never_saves_grants(self):
  for marker in ('private var retainedPage: String','@Volatile private var selected: Conversation?'):
   self.assertIn('revokeWorkspaceVisit()',self.main.split(marker,1)[1][:650])
  save=self.section('override fun onSaveInstanceState(', 'override fun onNewIntent(')
  for key in ('ordinaryDialogLease', 'workspaceVisit', 'generationOutputsOwner', 'chatModelSession'):
   self.assertNotIn(key,save)
 def test_shared_exit_animation_is_cancelled_at_pause_not_delivered_to_new_lease(self):
  motion=(APP/'ui/components/DialogMotion.kt').read_text()
  self.assertIn('if (event == Lifecycle.Event.ON_PAUSE) motion.dismissSource = null',motion)
  self.assertIn('lifecycle.removeObserver(observer)',motion)
  self.assertIn('rememberUpdatedState(onDismiss)',motion)
  lease=(APP/'ui/components/DialogActionLease.kt').read_text()
  self.assertIn('AtomicBoolean(true)',lease)
  self.assertIn('active.set(false)',lease)
  self.assertNotIn('active.set(true)',lease)

if __name__=='__main__': unittest.main()
