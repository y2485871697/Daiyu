from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
K = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class OrdinaryToolWiringContract(unittest.TestCase):
    def test_normal_run_registers_real_scopes_and_capabilities(self):
        controller = (K / 'ChatController.kt').read_text()
        for name in ('RunOrdinaryPlatformTools', 'RunOrdinaryBrowserTools', 'RunOrdinaryGuiTools',
                     'RunOrdinaryShizukuTools', 'RunOrdinaryGenerationTools', 'RunOrdinaryJobTools'):
            self.assertIn(name + '(', controller)
        catalog = (K / 'integration/tools/RunCatalogTools.kt').read_text()
        self.assertIn('ordinary.flatMap { it.tools }.forEach { registry.register(it) }', catalog)
        self.assertIn('ordinary.forEach { scope -> observeResourceClose(scope, scope::observeClose) }', catalog)
        self.assertIn('TerminalJobSession.CAPABILITY', controller)
        self.assertIn('setOf(RunGenerationTools.CAPABILITY)', controller)

    def test_gui_target_is_validated_before_session_mutation(self):
        gui = (K / 'integration/tools/RunOrdinaryGuiTools.kt').read_text()
        self.assertIn('GuiUserActionRequest.validTargetPackage(requestedTarget)', gui)
        self.assertIn('session != null && requested == target', gui)
        self.assertNotIn('requested == null || requested == target', gui)
        self.assertIn('current = null; target = null', gui)
        self.assertIn('backgroundSupported = false', gui)
        self.assertIn('TASK_SURFACE_BACKGROUND_UNSUPPORTED', gui)

    def test_job_prepare_has_no_backend_open_and_retains_workspace(self):
        job = (K / 'integration/tools/RunOrdinaryJobTools.kt').read_text()
        prepare, execute = job.split('return write(effect)', 1)
        self.assertNotIn('launch(environment)', prepare)
        self.assertIn('TerminalJobPlan.effectFor(', prepare)
        self.assertIn('pinWorkspace()', execute)
        self.assertIn('it.effect == effect', execute)
        self.assertIn('session.start(context)', execute)

    def test_unknown_tool_fallback_does_not_match_known_dynamic_names(self):
        presentation = (K / 'ui/screens/chat/conversation/ToolCardPresentation.kt').read_text()
        for name in ('inspect_child_tasks', 'get_child_task', 'control_child_task', 'conversation_reference_read'):
            self.assertIn('"' + name + '"', presentation)
        self.assertIn('isMcpToolName(toolName)', presentation)

if __name__ == '__main__':
    unittest.main()
