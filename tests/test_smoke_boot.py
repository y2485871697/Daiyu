"""Synthetic process tests; these do not claim Android instrumentation coverage."""
import importlib.util
import json
import os
import re
from pathlib import Path
import shutil
import signal
import time
import xml.etree.ElementTree as ET
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
        self.assertIn('adb_stage launch 30 shell am start -S -W -n app.daiyu.android/.MainActivity', script)
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
        run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main', workflow_id=383377276, status='completed', conclusion='success')
        jobs = [dict(name='单测、检查与开发包', conclusion='success')]
        source.verify(env, record, run, jobs, ['scripts/smoke_boot.sh', 'docs/acceptance/final-delivery-entry.md',
                                              'scripts/acceptance/cloud_report.py', 'tests/test_smoke_boot.py'])
        source.verify(env, record, dict(run, conclusion='failure'), jobs, [])
        for conclusion in (None, 'cancelled', 'timed_out', 'skipped', 'neutral', 'action_required'):
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                source.verify(env, record, dict(run, conclusion=conclusion), jobs, [])
        with self.assertRaises(ValueError):
            source.verify(env, record, {k: v for k, v in run.items() if k != 'conclusion'}, jobs, [])
        with self.assertRaises(ValueError):
            source.verify(env, record, dict(run, run_attempt=True), jobs, [])
        for conclusion in ('failure', 'cancelled', 'skipped', None):
            with self.subTest(build_conclusion=conclusion), self.assertRaises(ValueError):
                source.verify(env, record, dict(run, conclusion='failure'), [dict(jobs[0], conclusion=conclusion)], [])
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
              source_name='Test.kt', extra_sources=None, expected_classes=None, reported_count=None, shard_count=1, shard_index=0):
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
        # Build UI bytes once; fake adb must not launch Python at every readiness/retry step.
        ui = ET.Element('hierarchy')
        package = 'other.app' if case == 'foreign-ui' else 'app.daiyu.android'
        for description in ('打开侧边栏', '更多', '发送'):
            if case == 'missing-send' and description == '发送':
                continue
            ET.SubElement(ui, 'node', {'package': package, 'class': 'android.view.View',
                                      'content-desc': description})
        if case != 'missing-editor':
            ET.SubElement(ui, 'node', {'package': package, 'class': 'android.widget.EditText'})
        ET.SubElement(ui, 'node', {'package': package, 'class': 'android.widget.TextView', 'text': '代鱼'})
        fake_ui = self.root / 'fake-ui.xml'
        fake_ui.write_text('<broken' if case == 'malformed-ui' else
                           '代鱼 · 聊天 发送' if case == 'old-title-only' else
                           ET.tostring(ui, encoding='unicode'), encoding='utf-8')
        self.executable('adb', r'''#!/bin/sh
case "$*" in
 'shell getprop ro.build.version.sdk') printf '%s\n' "$API" ;;
 'shell getprop sys.boot_completed') printf '1\n' ;;
 *pidof*) printf '123\n456\n' ;;
 'shell pm path android') printf 'package:/system/framework/framework-res.apk\n' ;;
 'shell cat /proc/meminfo') printf 'MemTotal: 5000000 kB\n' ;;
 'install '*) printf 'Success\n'; [ "$CASE" != install-fail ] || exit 7 ;;
 'shell am instrument '*)
  printf '%s\n' "$@" > smoke/instrumentation-args.txt
  [ "$#" -eq 9 ] && [ "$4" = -w ] && [ "$5" = -r ] && [ "$6" = -e ] && [ "$7" = class ] &&
   [ "$8" = "$TEST_CLASSES" ] && [ "$9" = app.daiyu.android.test/androidx.test.runner.AndroidJUnitRunner ] || exit 11
  if [ "$CASE" = androidx-ok ]; then
   printf 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: class=app.daiyu.android.FirstTest\nINSTRUMENTATION_STATUS: test=testOne\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n'
   printf 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: class=app.daiyu.android.FirstTest\nINSTRUMENTATION_STATUS: test=testOne\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nTime: 0.01\n'
  fi
  [ "$CASE" != status-skip ] || printf 'INSTRUMENTATION_STATUS_CODE: -3\n'
  [ "$CASE" != status-ignore ] || printf 'INSTRUMENTATION_STATUS_CODE: -4\n'
  [ "$CASE" != text-skip ] || printf 'skipped\n'
  [ "$CASE" != text-ignore ] || printf 'ignored\n'
  summary="OK ($TEST_COUNT tests)"
  [ "$CASE" != terminal-before ] || printf 'INSTRUMENTATION_CODE: -1\n'
  case "$CASE" in
   substring-ok) printf 'prefix %s suffix\n' "$summary" ;;
   indented-ok) printf ' %s\n' "$summary" ;;
   *) printf '%s\n' "$summary" ;;
  esac
  [ "$CASE" != duplicate-ok ] || printf '%s\n' "$summary"
  [ "$CASE" != failure ] || printf 'FAILURES!!!\n'
  [ "$CASE" != status-fail ] || printf 'INSTRUMENTATION_STATUS_CODE: -2\n'
  case "$CASE" in
   missing-terminal) : ;;
   terminal-conflict) printf 'INSTRUMENTATION_CODE: 0\n' ;;
   *) printf 'INSTRUMENTATION_CODE: -1\n' ;;
  esac
  [ "$CASE" != duplicate-terminal ] || printf 'INSTRUMENTATION_CODE: -1\n'
  [ "$CASE" != after-terminal ] || printf 'INSTRUMENTATION_STATUS_CODE: 0\n'
  case "$CASE" in instrument-timeout) exit 124 ;; instrument-fail) exit 9 ;; esac
  : > smoke/fake-app-running ;;
 'shell am start '*)
  printf '%s\n' "$@" > smoke/launch-args.txt
  [ "$#" -eq 7 ] && [ "$4" = -S ] && [ "$5" = -W ] && [ "$6" = -n ] &&
    [ "$7" = app.daiyu.android/.MainActivity ] || exit 12
  rm -f smoke/fake-app-running
  case "$CASE" in
   launch-bad-status) printf 'Status: timeout\n' ;;
   *) printf 'Status: ok\n' ;;
  esac
  case "$CASE" in launch-timeout) exit 124 ;; launch-fail) exit 9 ;; esac ;;
 'shell pm list '*)
  if [ "$CASE" = old-package ]; then printf 'package:io.github.mangi.eta\n'
  else printf 'package:app.daiyu.android\n'; fi ;;
 'shell uiautomator dump '*) [ "$CASE" != dump-fail ] || exit 8 ;;
 'pull '*) [ "$CASE" != pull-fail ] || exit 8; cat "$FAKE_UI" > "$3" ;;
 'logcat '*) [ "$CASE" != crash ] || printf 'FATAL EXCEPTION\n' ;;
 'exec-out '*)
  if [ "$CASE" = png ]; then printf bad; else printf '\211PNG\r\n\032\n'; fi ;;
 'emu screenrecord screenshot '*) printf '\211PNG\r\n\032\n' > "$4/Screenshot_fake.png" ;;
esac
exit 0
''')
        if expected_classes is None:
            expected_classes = ('app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest'
                                if case == 'multi' else 'app.daiyu.android.FirstTest')
        if reported_count is None:
            reported_count = 3 if case == 'multi' else 2 if case == 'count' else 1
        env = dict(self.env, API=str(api), CASE=case,
                   TEST_CLASSES=expected_classes, TEST_COUNT=str(reported_count), FAKE_UI=str(fake_ui),
                   SMOKE_SHARD_COUNT=str(shard_count), SMOKE_SHARD_INDEX=str(shard_index))
        return self.run_process(['bash', str(ROOT / 'scripts/smoke_boot.sh')], env=env)

    def run_process(self, argv, env=None, timeout=15):
        with subprocess.Popen(argv, cwd=self.root, env=env or self.env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              start_new_session=True) as process:
            try:
                stdout, stderr = process.communicate(timeout=timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate()
                raise
            return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)

    def test_fixture_timeout_reaps_pipe_holding_descendants(self):
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_process(['/bin/sh', '-c', '/bin/sleep 30 & wait'], timeout=0.2)
        self.assertLess(time.monotonic() - started, 5)


    def test_api26_success_and_png_isolation(self):
        result = self.smoke()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual((self.root / 'smoke/启动.png').read_bytes(), b'\x89PNG\r\n\x1a\n')
        self.assertIn('phase=instrumentation exit=0', (self.root / 'smoke/stages.txt').read_text())
        self.assertIn('phase=instrumentation budget=2400s', (self.root / 'smoke/stages.txt').read_text())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[3:8], ['-w', '-r', '-e', 'class', 'app.daiyu.android.FirstTest'])
        self.assertEqual(args[8], 'app.daiyu.android.test/androidx.test.runner.AndroidJUnitRunner')

    def test_launch_isolated_from_instrumentation_on_both_apis(self):
        for api in (26, 37):
            with self.subTest(api=api):
                result = self.smoke(api=api)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                self.assertEqual((self.root / 'smoke/launch-args.txt').read_text().splitlines(),
                                 ['shell', 'am', 'start', '-S', '-W', '-n', 'app.daiyu.android/.MainActivity'])
                self.assertFalse((self.root / 'smoke/fake-app-running').exists())
                self.assertTrue((self.root / 'smoke/启动.png').is_file())

    def test_launch_timeout_preserved_even_with_ok_status(self):
        result = self.smoke(case='launch-timeout')
        self.assertEqual(result.returncode, 124)
        self.assertTrue(json.loads((self.root / 'smoke/instrumentation-result.json').read_text())['valid'])
        self.assertIn('Status: ok', (self.root / 'smoke/start.txt').read_text())
        self.assertIn('phase=launch exit=124', (self.root / 'smoke/stages.txt').read_text())
        self.assertFalse((self.root / 'smoke/boot.xml').exists())
        self.assertFalse((self.root / 'smoke/启动.png').exists())

    def test_launch_failure_is_not_hidden(self):
        result = self.smoke(case='launch-fail')
        self.assertEqual(result.returncode, 9)
        self.assertFalse((self.root / 'smoke/启动.png').exists())

    def test_launch_requires_ok_status(self):
        result = self.smoke(case='launch-bad-status')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'smoke/启动.png').exists())

    def test_androidx_junit3_status_frames_are_accepted_on_both_apis(self):
        for api in (26, 37):
            with self.subTest(api=api):
                result = self.smoke(api=api, case='androidx-ok')
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                self.assertTrue((self.root / 'smoke/启动.png').is_file())

    def test_pretty_ok_cannot_replace_the_raw_terminal_receipt(self):
        output = self.root / 'pretty-or-raw.txt'
        parser = ROOT / 'scripts/acceptance/instrumentation_result.py'
        for raw in (False, True):
            with self.subTest(raw=raw):
                output.write_text('Time: 394.412\n\nOK (419 tests)\n\n' +
                                  ('INSTRUMENTATION_CODE: -1\n' if raw else ''))
                result = subprocess.run([os.sys.executable, str(parser), '--output', str(output),
                                         '--expected-tests', '419'], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0 if raw else 2)
                report = json.loads(result.stdout)
                self.assertEqual(report['valid'], raw)
                if raw:
                    self.assertEqual(report['tests_passed'], 419)
                else:
                    self.assertEqual(report['error'], 'instrumentation_terminal_conflict')

    def test_strict_shared_result_rejects_ambiguous_and_failed_protocol(self):
        for case in ('substring-ok', 'indented-ok', 'duplicate-ok', 'missing-terminal',
                     'terminal-conflict', 'duplicate-terminal', 'terminal-before',
                     'after-terminal', 'status-fail', 'status-skip', 'status-ignore', 'text-skip', 'text-ignore'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                raw = (self.root / 'smoke/platform-tests.txt').read_bytes()
                self.assertIn(b'OK (1 tests)', raw)
                self.assertNotIn(b'phase=instrumentation', raw)
                # Failure occurs before any UI/launch evidence can be created.
                self.assertFalse((self.root / 'smoke/启动.png').exists())

    def test_four_shards_are_disjoint_deterministic_and_complete(self):
        sources = {f"Extra{i}.kt": f"package app.daiyu.android\nclass Extra{i}Test : android.test.InstrumentationTestCase() {{\n fun testOne() {{}}\n}}\n" for i in range(8)}
        all_names = sorted(["app.daiyu.android.FirstTest"] + [f"app.daiyu.android.Extra{i}Test" for i in range(8)])
        groups = [all_names[i::4] for i in range(4)]
        observed, previous = set(), None
        for index in range(4):
            result = self.smoke(extra_sources=sources, expected_classes=",".join(groups[index]),
                                reported_count=len(groups[index]), shard_count=4, shard_index=index)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            manifest = json.loads((self.root / "smoke/instrumentation-shards.json").read_text())
            canonical = {k: v for k, v in manifest.items() if k != "shard_index"}
            if previous is not None:
                self.assertEqual(canonical, previous)
            previous = canonical
            self.assertEqual(manifest["shard_index"], index)
            names = manifest["shards"][index]["classes"]
            self.assertEqual(names, groups[index])
            self.assertFalse(observed.intersection(names))
            observed.update(names)
        self.assertEqual(observed, set(all_names))
        self.assertEqual(manifest["total_tests"], 9)

    def test_invalid_shard_parameters_fail_before_instrumentation(self):
        for count, index in [(0, 0), (4, -1), (4, 4), (17, 0), (4, 0), ("bad", 0)]:
            result = self.smoke(shard_count=count, shard_index=index)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / "smoke/instrumentation-args.txt").exists())

    def test_all_source_classes_are_selected(self):
        result = self.smoke(case='multi', extra_source=(
            'package app.daiyu.android.other\nclass SecondTest : android.test.InstrumentationTestCase() {\n fun testTwo() {}\n fun testThree() {}\n}\n'))
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[7], 'app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest')
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
                self.assertEqual(args[3:8], ['-w', '-r', '-e', 'class', classes])
                self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(),
                                 ['5', classes])

    def test_kotlin_and_java_classes_are_selected(self):
        classes = 'app.daiyu.android.FirstTest,app.daiyu.android.other.SecondTest'
        result = self.smoke(
            extra_sources={'java/other/SecondTest.java': JAVA_SECOND_SOURCE},
            expected_classes=classes, reported_count=5)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[3:8], ['-w', '-r', '-e', 'class', classes])
        self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(),
                         ['5', classes])

    def test_final_java_test_class_is_discovered(self):
        result = self.smoke(source_name="FinalTest.java",
                            source_text=JAVA_FIRST_SOURCE.replace("public class", "public final class"))
        self.assertEqual(result.returncode, 0, result.stderr.decode())

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
            result = self.smoke(case=case)
            with self.subTest(case=case):
                self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
