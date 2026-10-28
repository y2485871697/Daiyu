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

    def test_root_configuration_reaches_both_command_and_job_without_model_identity_override(self):
        controller = (K / 'ChatController.kt').read_text()
        command = (K / 'integration/tools/RunCommandTools.kt').read_text()
        job = (K / 'integration/tools/RunOrdinaryJobTools.kt').read_text()
        self.assertIn('RunCommandTools.androidConfiguration(application, workspace.resolved.hostDirectory)', controller)
        self.assertIn('mutableMapOf("android" to androidCommands)', controller)
        self.assertIn('androidConfiguration = androidCommands, shizukuUid = ordinaryShizuku?.uid', controller)
        self.assertIn('androidConfiguration = frozen.takeIf { environment == "android" }', job)
        self.assertIn('mode == app.daiyu.android.runningmode.RunningMode.ROOT', command)
        self.assertIn('.put("root", config?.root).put("root_executable", config?.rootExecutable)', command)
        self.assertNotIn('ArgumentField("root"', command)

    def test_selected_shizuku_uses_real_shared_backend_without_fallback(self):
        controller = (K / 'ChatController.kt').read_text()
        command = (K / 'integration/tools/RunCommandTools.kt').read_text()
        job = (K / 'integration/tools/RunOrdinaryJobTools.kt').read_text()
        plan = (K / 'integration/tools/TerminalJobSession.kt').read_text()
        scope = (K / 'integration/tools/RunOrdinaryShizukuTools.kt').read_text()
        self.assertIn('"shizuku" -> ShizukuCommandTransport(', command)
        self.assertIn('shizukuBackend = { ordinaryShizukuScope?.backendForCommands() }', controller)
        self.assertIn('backendForCommands()', scope)
        self.assertEqual(scope.count('access.connect(uid)'), 1)
        self.assertIn('androidConfiguration = if (launch is TerminalPtyLaunch.Android)', controller)
        before = job.split('return write(effect)', 1)[0]
        self.assertIn('if (frozen.backend == "shizuku")', before)
        self.assertIn('admit = { denial(it) ?: refused }', before)
        self.assertIn('require(it.backend != "shizuku")', plan)
        self.assertIn('.put("backend", config?.backend).put("shizuku_uid",', command)

    def test_each_job_has_an_independent_transport_scope(self):
        job = (K / 'integration/tools/TerminalJobSession.kt').read_text()
        self.assertIn('transportScope = java.util.UUID.randomUUID().toString()', job)
        self.assertIn('registry.configure(plan.transportScope, plan.configuration', job)
        self.assertNotIn('registry.configure(plan.sessionId, plan.configuration', job)
        catalog = (K / 'integration/tools/RunCatalogTools.kt').read_text()
        self.assertIn('ordinary = createResource { ordinaryFactory?.invoke(', catalog)

    def test_image_edit_uses_only_current_message_verified_snapshot(self):
        generation = (K / 'integration/tools/RunOrdinaryGenerationTools.kt').read_text()
        host = (K / 'ControllerImageSending.kt').read_text()
        self.assertIn('OPENAI_IMAGE_EDIT_MULTIPART -> !video && sources.isNotEmpty()', generation)
        self.assertIn('references = listOfNotNull(source), mask = mask', generation)
        self.assertIn('private val inputs = inputAssets.toMap()', generation)
        self.assertIn('app.daiyu.android.domain.MessageImages.validate(message)', host)
        self.assertIn('GenerationInput(assets.resolver.read(expected, stored), ref.mime)', host)
        self.assertIn('messageImages.generationInputs(user)', (K / 'ChatController.kt').read_text())
        for forbidden in ('ArgumentField("path"', 'ArgumentField("url"', 'ArgumentField("base64"'):
            self.assertNotIn(forbidden, generation)

    def test_unknown_tool_fallback_does_not_match_known_dynamic_names(self):
        presentation = (K / 'ui/screens/chat/conversation/ToolCardPresentation.kt').read_text()
        for name in ('inspect_child_tasks', 'get_child_task', 'control_child_task', 'conversation_reference_read'):
            self.assertIn('"' + name + '"', presentation)
        self.assertIn('isMcpToolName(toolName)', presentation)

if __name__ == '__main__':
    unittest.main()
