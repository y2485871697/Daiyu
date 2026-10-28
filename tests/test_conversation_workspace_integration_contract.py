from pathlib import Path
import unittest
r=Path(__file__).resolve().parents[1]/'app/src/main/kotlin/app/daiyu/android'
class WorkspaceIntegration(unittest.TestCase):
 def test_gesture_to_owner_guarded_entry(self):
  s=(r/'MainActivity.kt').read_text()
  self.assertIn('onOpenWorkspace = { if (currentChat()) openConversationWorkspace() }',s)
  self.assertIn('workspaceOpenToken = token',s)
  self.assertIn('workspaceOpenToken === token && activityUsable() &&',s)
  self.assertIn('workspacePresented(visit) && voiceForeground',s)
  self.assertIn('selected?.id == owner && editorGeneration == generation',s)
  pause=s.split('override fun onPause()',1)[1].split('override fun onStop()',1)[0]
  self.assertIn('suspendOrdinaryDialogActions()',pause)
  self.assertNotIn('revokeWorkspaceVisit()',pause)
  for n in ['private var retainedPage: String', '@Volatile private var selected: Conversation?']:
   self.assertIn('revokeWorkspaceVisit()',s.split(n,1)[1][:650])
 def test_worker_owns_pin_before_cancellable_delivery(self):
  s=(r/'MainActivity.kt').read_text().split('private fun createTerminalSession',1)[1].split('private fun openTerminal',1)[0]
  self.assertIn('withContext(Dispatchers.IO) { pending.set(controller.freezeConversationWorkspaceOnWorker(conversation)) }',s)
  self.assertIn('pending.getAndSet(null)?.close()',s)
  self.assertIn('if (application.terminalSessions.create(conversation, directory, frozen)) {',s)
 def test_catalog_and_jobs_use_frozen_directory(self):
  s=(r/'ChatController.kt').read_text()
  self.assertIn('workspace.resolved.hostDirectory',s)
  self.assertIn('workspacePin = workspace',s)
  self.assertIn('workspace.retain()',s)
  s=(r/'integration/tools/TerminalJobSession.kt').read_text()
  self.assertIn('while (!owned.awaitJobTermination(daemon, 30_000))',s)
  self.assertIn('plan.releaseWorkspace()',s)
 def test_binding_passes_through_media_and_backups(self):
  for n in ['ControllerVideoSending.kt','ControllerImageSending.kt','storage/ConversationDatabase.kt']:
   self.assertIn('workspace_binding',(r/n).read_text())
if __name__=='__main__':unittest.main()
