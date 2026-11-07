from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
K = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class GuiAppLaunchContract(unittest.TestCase):
    def test_native_launch_is_in_the_production_ordinary_gui_scope(self):
        scope = (K / 'integration/tools/RunOrdinaryGuiTools.kt').read_text()
        controller = (K / 'ChatController.kt').read_text()
        self.assertIn('scopes.add(RunOrdinaryGuiTools(realOwner, task, location, screenToolImages))', controller)
        self.assertIn('ToolDefinition("launch_app", 1,', scope)
        self.assertIn('GuiScreenToolFactory.CAPABILITY, ToolEffect.WRITE', scope)
        self.assertIn('write(request.effect(GuiOwner(', scope)
        self.assertIn('withSession(request.packageName, context)', scope)
        self.assertIn('session.dynamicLaunch(request, context.authorization, context.cancelled)', scope)

    def test_launch_uses_native_explicit_intent_not_input_or_shell(self):
        runtime = (K / 'gui/GuiAccessibilityService.kt').read_text()
        launch = runtime.split('internal fun launchApp(', 1)[1].split('internal fun approveReads(', 1)[0]
        self.assertIn('current.packageManager.getLaunchIntentForPackage(packageName)', runtime)
        self.assertIn('intent.component?.packageName != request.packageName', launch)
        self.assertIn('Intent.FLAG_ACTIVITY_NEW_TASK', launch)
        self.assertIn('GuiResult.Unknown("APPLICATION_LAUNCH_UNCONFIRMED")', launch)
        self.assertIn('gate.retireObservation(lease); generation++; clearNodes()', launch)
        for bypass in ('Runtime.getRuntime', 'ProcessBuilder', 'performGlobalAction', 'shell', 'uiautomator'):
            self.assertNotIn(bypass, launch)

    def test_self_protection_remains_and_prompt_explains_the_valid_launch_path(self):
        runtime = (K / 'gui/GuiAccessibilityService.kt').read_text()
        prompt = (K / 'assistants/AssistantRunContext.kt').read_text()
        self.assertIn('"SELF_APP_GLOBAL_ACTION_NOT_ALLOWED_NOT_EXECUTED"', runtime)
        self.assertIn('pkg == current.packageName && !self -> "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED"', runtime)
        self.assertIn('直接launch_app，再观察目标界面', prompt)
        self.assertIn('不是Android触摸权限不足', prompt)
        self.assertIn('不得改用 shell、Shizuku、su、input', prompt)
        gui = (K / 'gui/GuiScreenToolFactory.kt').read_text()
        scope = (K / 'integration/tools/RunOrdinaryGuiTools.kt').read_text()
        self.assertIn('To open another app, use launch_app first', gui)
        self.assertIn('target_package only selects a window, it does not launch an app', gui)
        self.assertIn('Self-app observations do not authorize HOME/BACK/RECENTS/NOTIFICATIONS', gui)
        self.assertIn('不需要先观察助手或返回桌面', scope)
        self.assertIn('不确定包名时先用search_apps搜索', scope)
        self.assertIn('UNKNOWN不得重发', scope)
        self.assertNotIn('屏幕任务先观察当前窗口', prompt)


if __name__ == '__main__':
    unittest.main()
