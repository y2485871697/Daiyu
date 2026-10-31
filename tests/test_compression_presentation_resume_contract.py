"""Compression presentation survives backgrounding; operation authority does not."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class CompressionPresentationResumeContractTest(unittest.TestCase):
    def setUp(self):
        self.main = (SRC / 'MainActivity.kt').read_text()
        self.panel = (SRC / 'ui/screens/chat/context/ContextManagementPanel.kt').read_text()

    def test_lifecycle_preserves_only_presentation(self):
        for start,end in [('onPause()', 'onStop()'), ('onStop()', 'onDestroy()')]:
            body=self.main.split('override fun '+start,1)[1].split('override fun '+end,1)[0]
            self.assertIn('revokeContextManagementEntry(closeRoute = false, preservePresentation = true)',body)
        revoke=self.main.split('private fun revokeContextManagementEntry(',1)[1].split('private class BrowserResumeTarget',1)[0]
        self.assertIn('contextManagementVisit = null',revoke)
        self.assertIn('controller.invalidateContextManagement(visit.adapter)',revoke)
        self.assertIn('if (!preservePresentation || submitted) contextManagementPage = null',revoke)
        self.assertLess(revoke.index('val submitted ='),revoke.index('controller.invalidateContextManagement'))

    def test_auto_reopen_rechecks_owner_and_only_reads(self):
        body=self.main.split('private fun resumeContextManagementPage(',1)[1].split('private var retainedPageState',1)[0]
        for guard in ('contextManagementPage !== page','selected?.id != page.conversation',
                      'editorGeneration != page.generation','contextManagementEntryEpoch != epoch',
                      'contextManagementVisit != null','!voiceForeground','!generationResumed()','sidebarVisible'):
            self.assertIn(guard,body)
        self.assertIn('ContextManagementOwner(conversation, epoch)',body)
        self.assertIn('controller.openContextManagement(owner) ?: return',body)
        self.assertNotIn('submitCompression(',body)
        self.assertNotIn('confirmCompression(',body)
        refresh=self.main.split('private fun refresh() {',1)[1].split('childStopChoice =',1)[0]
        self.assertIn('resumeContextManagementPage(contextManagementPage, contextManagementEntryEpoch)',refresh)

    def test_stable_sheet_replaces_paused_shell(self):
        body=self.main.split('if (retainedPage == "context-management") {\n                    val page',1)[1].split('if (retainedPage == "browser")',1)[0]
        self.assertIn('key(page)',body)
        self.assertIn('ContextManagementRoute(visit?.adapter,',body)
        self.assertIn('presentation = page.presentation',body)
        self.assertIn('contextManagementEntryEpoch == epoch',body)
        self.assertNotIn('key(visit)',body)
        self.assertNotIn('context-management-paused',self.main)

    def test_draft_keeps_exact_binding_without_carrying_consent(self):
        holder=self.panel.split('internal class ContextManagementPresentation {',1)[1].split('/** Conversation compression',1)[0]
        self.assertIn('CompressionDialogDraft?',holder)
        self.assertIn('SummaryModelBinding?',holder)
        for forbidden in ('ContextManagementControllerAdapter','ContextManagementSnapshot','ContextManagementOwner',
                          'compressionAccepted','confirmingCompression','SettingsModelChoice','rememberSaveable'):
            self.assertNotIn(forbidden,holder)
        self.assertIn('else presentation.binding',self.panel)
        self.assertIn('state.operation == null && !state.requiresRefresh',self.panel)
        self.assertIn('ContextManagementSelection(if (draft.customModel) binding else null, draft.protocol)',self.panel)
        self.assertIn('if (!isCurrent() || adapter == null) return@confirm',self.panel)
        self.assertIn('LaunchedEffect(adapter) { adapter?.refresh() }',self.panel)

if __name__ == '__main__': unittest.main()
