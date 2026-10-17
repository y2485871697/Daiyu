"""Synthetic process tests; these do not claim Android instrumentation coverage."""
import importlib.util
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('smoke_source', ROOT / 'scripts/verify_smoke_source.py')
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)

JAVA_FIRST_SOURCE = (
    'package app.daiyu.android;\n'
    'public class FirstTest extends android.test.InstrumentationTestCase {\n'
    ' public void testOne() throws Exception {}\n'
    '}\n')
JAVA_SECOND_SOURCE = (
    'package app.daiyu.android.other;\n'
    'public class SecondTest extends android.test.InstrumentationTestCase {\n'
    ' public void testTwo() {}\n'
    ' public void testThree() throws Exception {}\n'
    ' public void testFour() {}\n'
    ' public void testFive() {}\n'
    ' private void testPrivateHelper() {}\n'
    ' public void testWithArgument(int ignored) {}\n'
    '}\n')


class SourceTests(unittest.TestCase):
    def test_smoke_nested_and_outer_budgets_cover_all_stages(self):
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        workflow = (ROOT / '.github/workflows/build.yml').read_text().split('  smoke:', 1)[1]
        wrapper = int(re.search(r'export ADB_COMMAND_TIMEOUT=(\d+)', script).group(1))
        stages = [(phase, int(seconds)) for phase, seconds in re.findall(r'adb_stage ([a-z-]+) (\d+) ', script)]
        instrumentation = next(seconds for phase, seconds in stages if phase == 'instrumentation')
        self.assertGreater(wrapper, instrumentation + 5)
        step = int(re.search(r'        timeout-minutes: (\d+)', workflow).group(1)) * 60
        job = int(re.search(r'^    timeout-minutes: (\d+)', workflow, re.M).group(1)) * 60
        # 600s emulator boot + all one-shot stages; loops include kill grace.
        maximum = 600 + sum(seconds + 5 for _, seconds in stages)
        maximum += 60 * (3 * (5 + 5) + 2) + 19 * (2 * (10 + 5) + 1) + 100
        self.assertGreater(step, maximum)
        self.assertGreaterEqual(job - step, 600)

    def test_identity_and_unchanged_application(self):
        env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123')
        record = dict(source_sha='a' * 40, run_id='123', attempt='1', application_id='app.daiyu.android')
        run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main', workflow_id=383377276, status='completed')
        jobs = [dict(name='单测、检查与开发包', conclusion='success')]
        source.verify(env, record, run, jobs, ['scripts/smoke_boot.sh', 'docs/acceptance/final-delivery-entry.md',
                                              'scripts/acceptance/cloud_report.py', 'tests/test_smoke_boot.py'])
        for changed in (['app/build.gradle.kts'], ['core-domain/src/main/Foo.kt'],
                        ['scripts/prepare_signing.py'], ['scripts/verify_signing.py'],
                        ['scripts/prepare-speech-runtime.py'], ['scripts/new-build-input.py'],
                        ['scripts/cooperation/checks_only.init.gradle'], ['.github/workflows/build.yml'],
                        ['docs/重构清单.md'], ['scripts/acceptance/../prepare-speech-runtime.py'],
                        ['tests/../app/build.gradle.kts']):
            with self.assertRaises(ValueError):
                source.verify(env, record, run, jobs, changed)
        for field, value in [('run_attempt', 2), ('head_sha', 'b' * 40), ('status', 'in_progress')]:
            with self.assertRaises(ValueError):
                source.verify(env, record, dict(run, **{field: value}), jobs, [])
        with self.assertRaises(ValueError):
            source.verify(env, record, run, [], [])
        with self.assertRaises(ValueError):
            source.verify(env, dict(record, attempt='2'), run, jobs, [])


@unittest.skipIf(os.name == 'nt', 'POSIX executable/shebang fixtures; smoke runs on Ubuntu')
class ProcessTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'])

    def executable(self, name, body):
        path = self.bin / name
        path.write_text(body, encoding='utf-8', newline='\n')
        path.chmod(0o755)
        return path

    def test_wrapper_timeout_and_stdout(self):
        wrapper = self.bin / 'adb'
        shutil.copy(ROOT / 'scripts/adb_bounded.sh', wrapper)
        wrapper.chmod(0o755)
        self.executable('adb.real', '#!/bin/sh\nif [ "$1" = hang ]; then sleep 10; else printf "1\\n"; fi\n')
        env = dict(self.env, ADB_COMMAND_TIMEOUT='1')
        result = subprocess.run([str(wrapper), 'ok'], cwd=self.root, env=env, capture_output=True, timeout=10)
        self.assertEqual((result.returncode, result.stdout), (0, b'1\n'))
        result = subprocess.run([str(wrapper), 'hang'], cwd=self.root, env=env, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 124)
        self.assertIn(b'timed out', result.stderr)
        self.assertIn('exit=124', (self.root / 'smoke/adb-commands.txt').read_text())

    def test_installer_rejects_double_wrapping(self):
        sdk = self.root / 'sdk/platform-tools'
        sdk.mkdir(parents=True)
        adb = sdk / 'adb'
        adb.write_text('#!/bin/sh\nprintf "fake adb\\n"\n')
        adb.chmod(0o755)
        shutil.copytree(ROOT / 'scripts', self.root / 'scripts', ignore=shutil.ignore_patterns('__pycache__'))
        cmd = ['bash', 'scripts/install_bounded_adb.sh']
        env = dict(self.env, ANDROID_HOME=str(sdk.parent))
        self.assertEqual(subprocess.run(cmd, cwd=self.root, env=env, capture_output=True).returncode, 0)
        first = (sdk / 'adb.real').read_bytes()
        self.assertNotEqual(subprocess.run(cmd, cwd=self.root, env=env, capture_output=True).returncode, 0)
        self.assertEqual((sdk / 'adb.real').read_bytes(), first)

    def smoke(self, api=26, case='ok', source_text=None, extra_source=None,
              source_name='Test.kt', extra_sources=None, expected_classes=None, reported_count=None):
        (self.root / 'delivery/tests').mkdir(parents=True, exist_ok=True)
        (self.root / 'delivery/app.apk').touch()
        (self.root / 'delivery/tests/platform-test.apk').touch()
        source_root = self.root / 'app/src/androidTest'
        source_root.mkdir(parents=True, exist_ok=True)
        sources = {
            source_name: source_text if source_text is not None else
            'package app.daiyu.android\nclass FirstTest : android.test.InstrumentationTestCase() {\n fun testOne() {}\n}\n',
            'Helper.kt': 'class SupportHelper {}\n',
            'Helper.java': 'public class SupportHelper {}\n',
        }
        if extra_source is not None:
            sources['Second.kt'] = extra_source
        sources.update(extra_sources or {})
        for name, text in sources.items():
            path = source_root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        self.executable('sleep', '#!/bin/sh\nexit 0\n')
        self.executable('timeout', '#!/bin/sh\nshift 2\nexec "$@"\n')
        self.executable('adb', '''#!/usr/bin/env python3
import os,sys
from pathlib import Path
import xml.etree.ElementTree as ET
a=sys.argv[1:];case=os.environ['CASE'];api=os.environ['API']
if a==['shell','getprop','ro.build.version.sdk']: print(api)
elif a==['shell','getprop','sys.boot_completed']: print('1')
elif a and 'pidof' in a[-1]: print('123\\n456')
elif a==['shell','pm','path','android']: print('package:/system/framework/framework-res.apk')
elif a[:3]==['shell','cat','/proc/meminfo']: print('MemTotal: 5000000 kB')
elif a[0]=='install':
 print('Success');sys.exit(7 if case=='install-fail' else 0)
elif a[:3]==['shell','am','instrument']:
 Path('smoke/instrumentation-args.txt').write_text('\\n'.join(a))
 classes=os.environ['TEST_CLASSES']
 if a!=['shell','am','instrument','-w','-e','class',classes,'app.daiyu.android.test/android.test.InstrumentationTestRunner']: sys.exit(11)
 summary='OK ('+os.environ['TEST_COUNT']+' tests)'
 if case=='terminal-before': print('INSTRUMENTATION_CODE: -1')
 print('prefix '+summary+' suffix' if case=='substring-ok' else ' '+summary if case=='indented-ok' else summary,flush=True)
 if case=='duplicate-ok': print(summary)
 if case=='failure': print('FAILURES!!!')
 if case=='status-fail': print('INSTRUMENTATION_STATUS_CODE: -2')
 if case!='missing-terminal': print('INSTRUMENTATION_CODE: 0' if case=='terminal-conflict' else 'INSTRUMENTATION_CODE: -1')
 if case=='duplicate-terminal': print('INSTRUMENTATION_CODE: -1')
 if case=='after-terminal': print('INSTRUMENTATION_STATUS_CODE: 0')
 sys.exit(124 if case=='instrument-timeout' else 9 if case=='instrument-fail' else 0)
elif a[:3]==['shell','am','start']: print('Status: ok')
elif a[:3]==['shell','pm','list']: print('package:io.github.mangi.eta' if case=='old-package' else 'package:app.daiyu.android')
elif a[:3]==['shell','uiautomator','dump'] and case=='dump-fail': sys.exit(8)
elif a[0]=='pull':
 if case=='pull-fail': sys.exit(8)
 root=ET.Element('hierarchy')
 package='other.app' if case=='foreign-ui' else 'app.daiyu.android'
 for desc in ['打开侧边栏','更多','发送']:
  if case=='missing-send' and desc=='发送': continue
  ET.SubElement(root,'node',{'package':package,'class':'android.view.View','content-desc':desc})
 if case!='missing-editor': ET.SubElement(root,'node',{'package':package,'class':'android.widget.EditText'})
 ET.SubElement(root,'node',{'package':package,'class':'android.widget.TextView','text':'代鱼'})
 Path(a[-1]).write_text('<broken' if case=='malformed-ui' else '代鱼 · 聊天 发送' if case=='old-title-only' else ET.tostring(root,encoding='unicode'),encoding='utf-8')
elif a[0]=='logcat': print('FATAL EXCEPTION' if case=='crash' else '')
elif a[0]=='exec-out': sys.stdout.buffer.write(b'bad' if case=='png' else b'\\x89PNG\\r\\n\\x1a\\n')
elif a[:3]==['emu','screenrecord','screenshot']: Path(a[-1],'Screenshot_fake.png').write_bytes(b'\\x89PNG\\r\\n\\x1a\\n')
''')
        if expected_classes is None:
            expected_classes = ('app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest'
                                if case == 'multi' else 'app.daiyu.android.FirstTest')
        if reported_count is None:
            reported_count = 3 if case == 'multi' else 2 if case == 'count' else 1
        env = dict(self.env, API=str(api), CASE=case,
                   TEST_CLASSES=expected_classes, TEST_COUNT=str(reported_count))
        return subprocess.run(['bash', str(ROOT / 'scripts/smoke_boot.sh')], cwd=self.root, env=env, capture_output=True, timeout=15)

    def test_api26_success_and_png_isolation(self):
        result = self.smoke()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual((self.root / 'smoke/启动.png').read_bytes(), b'\x89PNG\r\n\x1a\n')
        self.assertIn('phase=instrumentation exit=0', (self.root / 'smoke/stages.txt').read_text())
        self.assertIn('phase=instrumentation budget=2400s', (self.root / 'smoke/stages.txt').read_text())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[3:7], ['-w', '-e', 'class', 'app.daiyu.android.FirstTest'])

    def test_strict_shared_result_rejects_ambiguous_and_failed_protocol(self):
        for case in ('substring-ok', 'indented-ok', 'duplicate-ok', 'missing-terminal',
                     'terminal-conflict', 'duplicate-terminal', 'terminal-before',
                     'after-terminal', 'status-fail'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                raw = (self.root / 'smoke/platform-tests.txt').read_bytes()
                self.assertIn(b'OK (1 tests)', raw)
                self.assertNotIn(b'phase=instrumentation', raw)
                # Failure occurs before any UI/launch evidence can be created.
                self.assertFalse((self.root / 'smoke/启动.png').exists())

    def test_all_source_classes_are_selected(self):
        result = self.smoke(case='multi', extra_source=(
            'package app.daiyu.android.other\nclass SecondTest : android.test.InstrumentationTestCase() {\n fun testTwo() {}\n fun testThree() {}\n}\n'))
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[6], 'app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest')
        self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines()[0], '3')

    def test_java_only_classes_are_selected_on_both_apis(self):
        classes = 'app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest'
        for api in (26, 37):  # API 37.2 images report integer SDK 37 to adb.
            with self.subTest(api=api):
                result = self.smoke(
                    api=api, source_name='java/FirstTest.java', source_text=JAVA_FIRST_SOURCE,
                    extra_sources={'java/other/SecondTest.java': JAVA_SECOND_SOURCE},
                    expected_classes=classes, reported_count=5)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
                self.assertEqual(args[3:7], ['-w', '-e', 'class', classes])
                self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(),
                                 ['5', classes])

    def test_kotlin_and_java_classes_are_selected(self):
        classes = 'app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest'
        result = self.smoke(
            extra_sources={'java/other/SecondTest.java': JAVA_SECOND_SOURCE},
            expected_classes=classes, reported_count=5)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[3:7], ['-w', '-e', 'class', classes])
        self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(),
                         ['5', classes])

    def test_java_source_count_must_match_instrumentation(self):
        result = self.smoke(
            source_name='FirstTest.java', source_text=JAVA_FIRST_SOURCE, reported_count=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(),
                         ['1', 'app.daiyu.android.FirstTest'])
        self.assertIn('OK (2 tests)', (self.root / 'smoke/platform-tests.txt').read_text())

    def test_mixed_source_count_must_include_java_methods(self):
        classes = 'app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest'
        result = self.smoke(
            extra_sources={'SecondTest.java': JAVA_SECOND_SOURCE},
            expected_classes=classes, reported_count=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(),
                         ['5', classes])
        self.assertIn('OK (1 tests)', (self.root / 'smoke/platform-tests.txt').read_text())

    def test_java_discovery_rejects_missing_or_ambiguous_declarations(self):
        for text in (
                JAVA_FIRST_SOURCE.replace('package app.daiyu.android;\n', ''),
                'package another;\n' + JAVA_FIRST_SOURCE,
                JAVA_FIRST_SOURCE + 'public class AnotherTest extends android.test.InstrumentationTestCase {}\n'):
            with self.subTest(source=text):
                result = self.smoke(source_name='FirstTest.java', source_text=text)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr.decode())
                self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())

    def test_java_discovery_rejects_duplicate_class(self):
        result = self.smoke(
            source_name='java/FirstTest.java', source_text=JAVA_FIRST_SOURCE,
            extra_sources={'other/FirstTest.java': JAVA_FIRST_SOURCE})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Duplicate instrumentation test class: app.daiyu.android.FirstTest', result.stderr.decode())
        self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())

    def test_mixed_discovery_rejects_duplicate_class(self):
        result = self.smoke(extra_sources={'FirstTest.java': JAVA_FIRST_SOURCE})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Duplicate instrumentation test class: app.daiyu.android.FirstTest', result.stderr.decode())
        self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())

    def test_discovery_rejects_missing_package(self):
        result = self.smoke(source_text='class FirstTest : android.test.InstrumentationTestCase() {\n fun testOne() {}\n}\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())

    def test_discovery_rejects_ambiguous_class(self):
        result = self.smoke(extra_source=(
            'package app.daiyu.android\nclass SecondTest : android.test.InstrumentationTestCase() {\n fun testTwo() {}\n}\n'
            'class ThirdTest : android.test.InstrumentationTestCase() {}\n'))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())

    def test_discovery_rejects_duplicate_class(self):
        result = self.smoke(extra_source=(
            'package app.daiyu.android\nclass FirstTest : android.test.InstrumentationTestCase() {\n fun testTwo() {}\n}\n'))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())

    def test_high_api_screenshot(self):
        result = self.smoke(api=37)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertTrue((self.root / 'smoke/启动.png').exists())

    def test_pipefail_preserves_install_failure(self):
        self.assertEqual(self.smoke(case='install-fail').returncode, 7)

    def test_instrumentation_timeout_rejects_even_complete_ok_text(self):
        result = self.smoke(case='instrument-timeout')
        self.assertEqual(result.returncode, 124)
        self.assertIn('OK (1 tests)', (self.root / 'smoke/platform-tests.txt').read_text())
        self.assertFalse((self.root / 'smoke/instrumentation-result.json').exists())
        self.assertFalse((self.root / 'smoke/start.txt').exists())
        self.assertFalse((self.root / 'smoke/启动.png').exists())

    def test_pipefail_preserves_instrumentation_failure(self):
        self.assertEqual(self.smoke(case='instrument-fail').returncode, 9)

    def test_failed_dump_or_pull_cannot_reuse_local_xml(self):
        result = self.smoke()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        for case in ['dump-fail', 'pull-fail']:
            with self.subTest(case=case):
                self.assertNotEqual(self.smoke(case=case).returncode, 0)
                self.assertFalse((self.root / 'smoke/boot.xml').exists())

    def test_business_gates_remain(self):
        for case in ['count', 'failure', 'old-package', 'crash', 'png', 'foreign-ui',
                     'missing-send', 'missing-editor', 'malformed-ui', 'old-title-only']:
            with self.subTest(case=case):
                self.assertNotEqual(self.smoke(case=case).returncode, 0)


if __name__ == '__main__':
    unittest.main()
