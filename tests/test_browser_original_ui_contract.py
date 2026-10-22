"""Pinned original UI contracts, independent of a locally installed Eta checkout."""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PORT = ROOT / 'app/src/main/kotlin/app/daiyu/android/browser/ported'
BASELINE = ROOT / 'tests/fixtures/browser_original_ui.json'
UI_CALLS = {
    'Text', 'Icon', 'IconButton', 'Button', 'MinisTextButton', 'MinisButton',
    'MinisOutlinedButton', 'RadioButton', 'Column', 'Row', 'Box', 'Spacer',
    'Surface', 'OutlinedTextField', 'BasicTextField', 'HorizontalDivider',
    'LinearProgressIndicator', 'CircularProgressIndicator', 'BadgedBox', 'Badge',
    'LazyColumn', 'LazyRow', 'StandardChatSheet', 'StandardChatSheetHeader',
    'CompactDragHandle', 'ModalBottomSheet', 'AlertDialog', 'TabChip',
    'ToolbarIcon', 'BrowserAddressBarIcon', 'AgentBrowsingOverlay',
    'BrowserHistorySheet', 'BrowserSettingsSheet', 'BrowserDownloadsSheet',
    'ViewportSection', 'UaMismatchBanner', 'DaiyuDialog', 'PreviewDialog',
    'SettingsNote', 'SettingsRow', 'SettingsSectionTitle', 'SettingsCard',
}
VISUAL_BINDINGS = {
    'ui/browser/BrowserSheet.kt': ('accent', 'secondaryBg', 'tertiaryBg', 'title', 'domain',
                                   'displayTitle', 'bg', 'borderColor', 'textColor', 'angle', 'breathingAlpha'),
    'ui/browser/BrowserSettingsSheet.kt': ('notSetPlaceholder', 'label', 'uaSubtitle',
                                          'viewportPresets', 'active', 'bg', 'fg', 'warning'),
    'ui/browser/BrowserDownloadsSheet.kt': ('(icon, tint)', 'doneText', 'totalText', 'pct', 'statusText'),
}
TOKEN = re.compile(r'(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)|(?P<string>"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|[A-Za-z_]\w*|[^\s]')


HISTORY_PATH = 'ui/browser/BrowserHistorySheet.kt'


def approved_original_ui_source(path, source):
    if path != HISTORY_PATH:
        return source
    source = source.replace('import androidx.compose.ui.platform.LocalLocale', 'import java.util.Locale')
    return source.replace('SimpleDateFormat("HH:mm", LocalLocale.current.platformLocale)',
                          'SimpleDateFormat("HH:mm", Locale.getDefault())')


def approved_input_behavior_source(source):
    """Match code only: strings and comments must remain part of the frozen UI contract."""
    protected = []
    def stash(match):
        protected.append(match.group())
        return f'__INPUT_LITERAL_{len(protected) - 1}__'
    literal = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    source = literal.sub(stash, source)
    source = re.sub(r'(?m)^import app\.daiyu\.android\.ui\.components\.(?:managedInput|imeCursorBrush|materialImeCursorColor)\n', '', source)
    source = source.replace('.managedInput()', '')
    source = source.replace('imeCursorBrush(accent)', 'androidx.compose.ui.graphics.SolidColor(accent)')
    colors = ('colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors('
              'cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), '
              'errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),')
    source = source.replace(colors, '')
    return re.sub(r'__INPUT_LITERAL_(\d+)__', lambda m: protected[int(m.group(1))], source)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def tokens(source):
    source = approved_input_behavior_source(source)
    source = source.replace('BrowserUiPool', 'BrowserTabPool')
    source = re.sub(r'(?m)^(?:package|import) [^\n]*\n', '', source)
    return [m.group() for m in TOKEN.finditer(source) if m.lastgroup != 'comment']


def end_group(items, start):
    matching = {'(': ')', '{': '}', '[': ']'}
    stack = [matching[items[start]]]
    for index in range(start + 1, len(items)):
        token = items[index]
        if token in matching:
            stack.append(matching[token])
        elif token == stack[-1]:
            stack.pop()
            if not stack:
                return index + 1
    raise AssertionError('unclosed Kotlin group')


def appearance(items):
    """Only instrumentation and gesture callbacks are non-drawing modifiers."""
    result = []
    index = 0
    while index < len(items):
        if (items[index:index + 2] in (['.', 'testTag'], ['.', 'clickable'])
                and index + 2 < len(items) and items[index + 2] in ('(', '{')):
            index = end_group(items, index + 2)
            if index < len(items) and items[index] == '{':
                index = end_group(items, index)
        else:
            result.append(items[index])
            index += 1
    return result


def arguments(items):
    parts = []
    start = 0
    index = 0
    while index < len(items):
        if items[index] in ('(', '{', '['):
            index = end_group(items, index)
            continue
        if items[index] == ',':
            parts.append(items[start:index])
            start = index + 1
        index += 1
    parts.append(items[start:])
    visible = []
    for part in parts:
        if not part:
            continue
        if len(part) >= 3 and part[1] == '=':
            # Slot contents are compared as their own render calls below; callbacks
            # remain wired to the new backend and are checked by the wiring suite.
            if part[0].startswith('on') or part[2] == '{':
                continue
        part = appearance(part)
        if part == ['modifier', '=', 'Modifier']:
            continue
        if part and part[0] == 'Modifier':
            part = ['modifier', '='] + part
        visible.append(part)
    return visible


def render_contract(source):
    items = tokens(source)
    calls = []
    for index, token in enumerate(items[:-1]):
        if token in UI_CALLS and items[index + 1] == '(' and (index == 0 or items[index - 1] != 'fun'):
            end = end_group(items, index + 1)
            calls.append([token, arguments(items[index + 2:end - 1])])
    return json.dumps(calls, ensure_ascii=False, separators=(',', ':'))


def body_contract(source):
    items = appearance(tokens(source))
    index = 0
    while index + 3 < len(items):
        if items[index:index + 3] == ['modifier', '=', 'Modifier'] and items[index + 3] in (',', ')'):
            if index > 0 and items[index - 1] == ',' and items[index + 3] == ')':
                del items[index - 1:index + 3]
                index = max(0, index - 1)
            else:
                end = index + 4 if items[index + 3] == ',' else index + 3
                del items[index:end]
        else:
            index += 1
    return json.dumps(items, ensure_ascii=False, separators=(',', ':'))


def visual_binding_contract(source, name):
    """Keep the initializer behind rendered color, copy, animation and list bindings."""
    source = source.replace('BrowserUiPool', 'BrowserTabPool')
    declaration = re.search(r'(?m)^[ \t]*(?:private )?val ' + re.escape(name) + r'(?=\s|:|=)', source)
    if declaration is None:
        raise AssertionError('missing visual binding: ' + name)
    items = []
    stack = []
    previous_end = declaration.start()
    matching = {'(': ')', '{': '}', '[': ']'}
    for match in TOKEN.finditer(source, declaration.start()):
        if match.lastgroup == 'comment':
            continue
        token = match.group()
        if (items and not stack and '\n' in source[previous_end:match.start()]
                and token not in ('.', '?', ':', 'else', '&', '|')
                and items[-1] not in ('=', 'by')):
            break
        items.append(token)
        if token in matching:
            stack.append(matching[token])
        elif stack and token == stack[-1]:
            stack.pop()
        previous_end = match.end()
    return json.dumps(items, ensure_ascii=False, separators=(',', ':'))


def imports_contract(source):
    source = approved_input_behavior_source(source)
    imports = set(re.findall(r'(?m)^import (.*)$', source))
    normalized = set()
    for name in imports:
        name = name.replace('io.github.mangi.eta.agent.browser.ported', 'app.daiyu.android.browser.ported')
        name = name.replace('io.github.mangi.eta.R', 'app.daiyu.android.R')
        name = name.replace('app.daiyu.android.ui.haptics.material.', 'androidx.compose.material3.')
        name = name.replace('app.daiyu.android.ui.haptics.clickable', 'androidx.compose.foundation.clickable')
        name = name.replace('app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog', 'androidx.compose.material3.AlertDialog')
        name = name.replace('app.daiyu.android.browser.ui.BrowserUiPool', 'app.daiyu.android.browser.ported.browser.BrowserTabPool')
        name = name.replace('com.openminis.app.browser.', 'app.daiyu.android.browser.ported.browser.')
        if name in ('androidx.compose.ui.platform.testTag', 'androidx.compose.runtime.LaunchedEffect',
                    'app.daiyu.android.browser.ported.logging.AppLogger'):
            continue
        normalized.add(name)
    return '\n'.join(sorted(normalized))


def helper_contract(source, name):
    items = tokens(source)
    start = next(i for i, token in enumerate(items[:-1]) if token == 'fun' and items[i + 1] == name)
    body = items.index('{', start)
    return json.dumps(items[start:end_group(items, body)], ensure_ascii=False, separators=(',', ':'))


class BrowserOriginalUiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = json.loads(BASELINE.read_text())

    def test_pinned_original_file_set(self):
        self.assertEqual('3196270e361c82cc1f399218d914ec49de0d83cc', self.baseline['revision'])
        self.assertEqual(set(self.baseline['files']), {p.relative_to(PORT).as_posix() for p in PORT.rglob('*.kt')})

    def test_original_rendered_copy_controls_and_layout_arguments(self):
        for path, expected in self.baseline['files'].items():
            with self.subTest(path=path):
                source = approved_original_ui_source(path, (PORT / path).read_text())
                contract = body_contract if expected['mode'] == 'body' else render_contract
                self.assertEqual(expected['contract_sha256'], digest(contract(source)), 'original UI contract changed: ' + path)
                self.assertEqual(expected['imports_sha256'], digest(imports_contract(source)), 'non-approved UI import: ' + path)

    def test_history_locale_uses_only_the_approved_observable_adaptation(self):
        source = (PORT / HISTORY_PATH).read_text()
        self.assertEqual(1, source.count('import androidx.compose.ui.platform.LocalLocale'))
        self.assertEqual(1, source.count('SimpleDateFormat("HH:mm", LocalLocale.current.platformLocale)'))
        self.assertNotIn('Locale.getDefault()', source)
        normalized = approved_original_ui_source(HISTORY_PATH, source)
        self.assertEqual(self.baseline['files'][HISTORY_PATH]['contract_sha256'], digest(render_contract(normalized)))
        for old, new in [('"HH:mm"', '"hh:mm"'), ('"HH:mm"', '"HH:mm:ss"'),
                         ('Date(entry.timestamp)', 'Date(0)'), ('fontSize = 11.sp', 'fontSize = 12.sp')]:
            changed = source.replace(old, new)
            self.assertNotEqual(render_contract(normalized),
                                render_contract(approved_original_ui_source(HISTORY_PATH, changed)))
        self.assertEqual(source, approved_original_ui_source('ui/browser/BrowserSettingsSheet.kt', source))

    def test_input_policy_keeps_visible_cursor_and_layout_frozen(self):
        for path in ('ui/browser/BrowserHistorySheet.kt', 'ui/browser/BrowserSettingsSheet.kt', 'ui/browser/BrowserSheet.kt'):
            source = (PORT / path).read_text()
            contract = body_contract if self.baseline['files'][path]['mode'] == 'body' else render_contract
            expected = contract(source)
            self.assertIn('.managedInput()', source)
            if path.endswith('BrowserSheet.kt'):
                changed = source.replace('imeCursorBrush(accent)', 'imeCursorBrush(Color.Red)')
                self.assertNotEqual(expected, contract(changed))
            else:
                changed = source.replace('materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary)',
                                         'materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)')
                self.assertNotEqual(expected, contract(changed))
                changed = source.replace('errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)',
                                         'errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary)')
                self.assertNotEqual(expected, contract(changed))
            self.assertNotEqual(expected, contract(source.replace('.managedInput()', '.padding(1.dp).managedInput()', 1)))
            self.assertNotEqual(expected, contract(source.replace('.managedInput()', '.managedInput(Color.Red)', 1)))

    def test_input_normalization_never_erases_literal_copy(self):
        for literal in ('.managedInput()', 'imeCursorBrush(accent)', 'colors = cursorColor'):
            original = 'Text("original")'
            changed = 'Text("original' + literal + '")'
            self.assertNotEqual(render_contract(original), render_contract(changed))

    def test_original_visual_bindings_and_preset_lists(self):
        self.assertEqual(set(VISUAL_BINDINGS), set(self.baseline['visual_bindings']))
        for path, names in VISUAL_BINDINGS.items():
            source = (PORT / path).read_text()
            self.assertEqual(set(names), set(self.baseline['visual_bindings'][path]))
            for name in names:
                with self.subTest(path=path, binding=name):
                    self.assertEqual(self.baseline['visual_bindings'][path][name],
                                     digest(visual_binding_contract(source, name)))

    def test_binding_comparator_rejects_color_changes_and_additional_presets(self):
        main = (PORT / 'ui/browser/BrowserSheet.kt').read_text()
        changed = main.replace('val accent = MaterialTheme.colorScheme.primary', 'val accent = Color.Red')
        self.assertNotEqual(visual_binding_contract(main, 'accent'), visual_binding_contract(changed, 'accent'))
        settings = (PORT / 'ui/browser/BrowserSettingsSheet.kt').read_text()
        changed = settings.replace('private val viewportPresets = listOf(',
                                   'private val viewportPresets = listOf(Triple("extra action", 1000, 800),')
        self.assertNotEqual(visual_binding_contract(settings, 'viewportPresets'),
                            visual_binding_contract(changed, 'viewportPresets'))

    def test_original_bare_host_recognition_helper(self):
        pool = (ROOT / 'app/src/main/kotlin/app/daiyu/android/browser/ui/BrowserUiPool.kt').read_text()
        self.assertEqual(self.baseline['navigation_helper_sha256'], digest(helper_contract(pool, 'looksLikeUrl')))

    def test_original_helper_warning_copy_is_preserved(self):
        source = (PORT / 'ui/browser/BrowserSettingsSheet.kt').read_text()
        for name, expected in self.baseline['settings_helpers'].items():
            self.assertEqual(expected, digest(helper_contract(source, name)), name)

    def test_original_three_language_resource_bytes(self):
        for locale, expected in self.baseline['resources'].items():
            source = ROOT / 'app/src/main/res' / locale / 'openminis_browser.xml'
            self.assertEqual(expected, hashlib.sha256(source.read_bytes()).hexdigest(), locale)

    def test_no_added_task_description_controls_or_notice_area(self):
        settings = (PORT / 'ui/browser/BrowserSettingsSheet.kt').read_text()
        main = (PORT / 'ui/browser/BrowserSheet.kt').read_text()
        for text in ('tabPool.taskOwned', 'browser-visual-restore', 'browser-visual-stop-owner',
                     '归还浏览器并继续任务', '查看网页会暂停当前任务'):
            self.assertNotIn(text, settings)
        self.assertNotIn('browserNotice', main)
        self.assertNotIn('tabPool.notice', main)

    def test_comparator_rejects_new_description_button_or_layout_change(self):
        source = (PORT / 'ui/browser/BrowserSettingsSheet.kt').read_text()
        contract = render_contract(source)
        self.assertNotEqual(contract, render_contract(source + '\n@Composable fun Extra() { Text("extra description") }'))
        self.assertNotEqual(contract, render_contract(source + '\n@Composable fun Extra() { MinisTextButton(onClick = {}) { Text("extra action") } }'))
        self.assertNotEqual(contract, render_contract(source.replace('height(16.dp)', 'height(17.dp)', 1)))

    def test_comparator_allows_only_nonvisual_tags_and_backend_callbacks(self):
        original = 'IconButton(onClick = { oldOwner.takeover() }, modifier = Modifier.size(36.dp)) { Text("original") }'
        adapted = 'IconButton(onClick = { visit.takeover() }, modifier = Modifier.size(36.dp).testTag("browser-original")) { Text("original") }'
        self.assertEqual(render_contract(original), render_contract(adapted))
