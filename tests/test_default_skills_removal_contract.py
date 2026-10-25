"""Source contracts for removing default packages; device behavior is tested separately."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
NAMES = ('self-improving-agent', 'skill-creator', 'skill-installer')

class DefaultSkillsRemovalContractTest(unittest.TestCase):
    def test_no_packaged_defaults_or_default_assistant_selection(self):
        catalog = (SRC / 'skills/BuiltinSkillCatalog.kt').read_text()
        self.assertIn('DEFAULT_NAMES: Set<String> = emptySet()', catalog)
        self.assertIn('ENABLED_SKILL_IDS: List<String> = emptyList()',
                      (SRC / 'assistants/Assistant.kt').read_text())
        for name in NAMES:
            self.assertFalse((ROOT / 'app/src/main/assets/agent-builtin-skills-v1' / name / 'SKILL.md').exists())

    def test_retirement_is_scoped_to_production_builtin_store(self):
        registry = (SRC / 'skills/SkillToolRegistry.kt').read_text()
        builtin = registry.split('private val builtin =', 1)[1].split('private val builtinCatalog', 1)[0]
        user = registry.split('private val userStore', 1)[1].split('private val preferences', 1)[0]
        self.assertIn('retiredNames = BuiltinSkillCatalog.RETIRED_NAMES', builtin)
        self.assertNotIn('retiredNames', user)
        store = (SRC / 'skills/SkillStore.kt').read_text()
        access = store.split('private fun <T> access(', 1)[1].split('fun recover()', 1)[0]
        self.assertLess(access.index('recoverLocked()'), access.index('retiredNames.forEach'))
        self.assertLess(access.index('retiredNames.forEach'), access.index('block()'))
        self.assertIn('SkillFiles.remove(SkillFiles.path(installed, it))', access)
        self.assertIn('private val retiredNames = retiredNames.toSet()', store)
        self.assertIn('require(names.none { it in retiredNames })', store)

    def test_empty_editor_skills_contains_only_single_line_none(self):
        editor = (SRC / 'ui/screens/assistants/AssistantEditScreen.kt').read_text()
        section = editor.split('Modifier.testTag("assistant-skills")) {', 1)[1].split('Modifier.testTag("assistant-memory")', 1)[0]
        empty, populated = section.split('} else {', 1)
        self.assertIn('installedSkills.isEmpty()', empty)
        self.assertIn('R.string.assistant_skills_none', empty)
        self.assertIn('maxLines = 1', empty)
        self.assertEqual(1, empty.count('Text('))
        self.assertNotIn('assistant_skills_caption', empty)
        self.assertNotIn('assistant_independent_switches', empty)
        self.assertNotIn('AssistantSkillSwitchRow(', empty)
        for token in ('assistant_skills_caption', 'assistant_independent_switches', 'installedSkills.forEachIndexed', 'AssistantSkillSwitchRow('):
            self.assertIn(token, populated)
        self.assertNotIn('enabledSkillIds = empty', section)

    def test_empty_text_is_localized_without_extra_explanation(self):
        for directory, expected in (('values', 'None'), ('values-b+zh+Hans', '无'), ('values-b+zh+Hant', '無')):
            strings = ET.parse(ROOT / 'app/src/main/res' / directory / 'assistant_ui.xml').getroot()
            self.assertEqual(expected, strings.find("string[@name='assistant_skills_none']").text)

    def test_empty_catalog_retains_memory_and_selected_user_skills(self):
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ControllerModelPreparationTest.kt').read_text()
        base = tests.split('private val expectedBaseCatalog =', 1)[1].split('private class', 1)[0]
        self.assertNotIn('skills_list', base)
        self.assertIn('assistant_memory_read', base)
        self.assertIn('assistant_memory_search', base)
        self.assertIn('assertEquals(setOf("user:demo"), ids.toSet())', tests)
        self.assertIn('selection = assistantContext.enabledSkillIds.toSet()', (SRC / 'ChatController.kt').read_text())

    def test_existing_builtin_safety_tests_use_injected_not_packaged_defaults(self):
        fixture = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/skills/SkillInstallManagementTest.kt').read_text()
        self.assertIn('fixtureBuiltins', fixture)
        self.assertNotIn('packagedBuiltins', fixture)
        self.assertIn('BuiltinSkillCatalog(mapOf("builtin-demo"', fixture)
        self.assertIn('testEmptySkillsShowsSingleLineNoneWithoutDirtyingProfile',
                      (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AssistantEditVisualTest.kt').read_text())

if __name__ == '__main__':
    unittest.main()
