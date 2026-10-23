"""Synthetic process tests; these do not claim Android instrumentation coverage."""
import importlib.util
import hashlib
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
    def test_native_framebuffer_does_not_change_default_test_canvas(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        hook = (ROOT / 'scripts/prepare_smoke_emulator.sh').read_text()
        self.assertIn('"hw.lcd.width":"1216"', hook)
        self.assertIn('"hw.lcd.height":"2432"', hook)
        self.assertIn('"hw.lcd.density":"160"', hook)
        self.assertNotIn("<<", hook)  # This action executes each nonblank line in a separate shell.
        self.assertLess(hook.index('p.write_text('), hook.index('cp "$ANDROID_AVD_HOME/test.avd/config.ini"'))
        command = next(line.strip() for line in hook.splitlines() if line.strip().startswith('python3 -c '))
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / 'test.avd/config.ini'
            config.parent.mkdir()
            config.write_text('hw.lcd.width=320\nhw.lcd.width=640\nhw.lcd.height=640\nother=preserved\n')
            result = subprocess.run(['sh', '-c', command], env=dict(os.environ, ANDROID_AVD_HOME=temporary),
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            actual = config.read_text()
            self.assertEqual(actual.count('hw.lcd.width='), 1)
            for entry in ('hw.lcd.width=1216', 'hw.lcd.height=2432', 'hw.lcd.density=160',
                          'skin.name=1216x2432', 'skin.path=1216x2432', 'other=preserved'):
                self.assertIn(entry + '\n', actual)
        self.assertNotIn('          resolution:', workflow)
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        self.assertLess(script.index('verify_display_baseline before'), script.index('adb_stage install-app'))
        self.assertLess(script.index('verify_display_baseline after'), script.index('adb_stage launch'))

    def test_smoke_nested_and_outer_budgets_cover_all_stages(self):
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        workflow = (ROOT / '.github/workflows/build.yml').read_text().split('  smoke:', 1)[1]
        wrapper = int(re.search(r'export ADB_COMMAND_TIMEOUT=(\d+)', script).group(1))
        stages = [(phase, int(seconds)) for phase, seconds in re.findall(r'adb_stage ([a-z-]+) (\d+) ', script)]
        instrumentation = next(seconds for phase, seconds in stages if phase == 'instrumentation')
        self.assertGreaterEqual(instrumentation, 5400)
        self.assertLessEqual(instrumentation, 7200)
        self.assertGreater(wrapper, instrumentation + 5)
        self.assertIn('adb_stage launch 30 shell am start -S -W -n app.daiyu.android/.MainActivity', script)
        step = int(re.search(r'        timeout-minutes: (\d+)', workflow).group(1)) * 60
        job = int(re.search(r'^    timeout-minutes: (\d+)', workflow, re.M).group(1)) * 60
        # 600s emulator boot + all one-shot stages; loops include kill grace.
        maximum = 600 + sum(seconds + 5 for _, seconds in stages)
        maximum += 60 * (3 * (5 + 5) + 2) + 19 * (2 * (10 + 5) + 1)
        # EXIT diagnostics, two extra display-function calls, and final UI sleep.
        maximum += 190 + 80 + 1
        self.assertGreater(step, maximum)
        self.assertGreaterEqual(job - step, 600)

    def test_workflow_safely_wires_filter_and_rejects_non_smoke_before_setup(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('SMOKE_MODE: ${{ inputs.mode }}', workflow)
        self.assertIn('SMOKE_TEST_CLASSES: ${{ inputs.smoke_classes }}', workflow)
        guard = 'test -z "$SMOKE_TEST_CLASSES" || test "$SMOKE_MODE" = smoke'
        for job in ('checks', 'build', 'release', 'synthetic'):
            body = workflow.split('  ' + job + ':\n', 1)[1].split('    steps:\n', 1)[1]
            first = body.split('      - uses:', 1)[0]
            self.assertIn('shell: bash', first)
            self.assertIn('run: ' + guard, first)
        # Execute the exact workflow guard, not a copy with interpolated inputs.
        if os.name != 'nt':  # Execute bash only on the smoke runner platform.
            for mode in ('build', 'checks', 'synthetic', 'performance', 'smoke'):
                for value in ('', 'app.daiyu.android.FirstTest', '$(exit 99)'):
                    result = subprocess.run(['bash', '-c', guard], capture_output=True,
                                            env=dict(os.environ, SMOKE_MODE=mode, SMOKE_TEST_CLASSES=value))
                    self.assertEqual(result.returncode == 0, not value or mode == 'smoke')
        suffix = "${{ inputs.smoke_classes != '' && '-filtered' || '' }}"
        for name in ("    name: ${{ inputs.mode == 'candidate' && '定向候选验证' || '启动验证' }} API ${{ matrix.api }} 分片${{ matrix.shard }}",
                     "          name: ${{ inputs.mode == 'candidate' && '代鱼定向候选证据' || '代鱼启动证据' }}-API${{ matrix.api }}-分片${{ matrix.shard }}",
                     '          name: 代鱼工具证据-smoke-API${{ matrix.api }}-分片${{ matrix.shard }}'):
            self.assertIn(name + suffix, workflow)
        smoke = workflow.split('  smoke:\n', 1)[1]
        self.assertIn("SMOKE_SHARD_COUNT: '4'", smoke)
        matrix = re.search(r"\|\| '(\[[^']*\])'\) }}", smoke).group(1)
        self.assertEqual({(v['api'], v['shard']) for v in json.loads(matrix)},
                         {(api, index) for api in ('26', '37.2') for index in range(4)})

    def test_api26_has_pinned_emulator_without_changing_image_or_formal_matrix(self):
        smoke = (ROOT / '.github/workflows/build.yml').read_text().split('  smoke:\n', 1)[1]
        matrices = [json.loads(value) for value in re.findall(r"'(\[[^']*\])'", smoke)]
        self.assertEqual(len(matrices), 3)
        for matrix in matrices:
            for item in matrix:
                self.assertEqual(item['target'], 'google_apis' if item['api'] == '26' else 'google_apis_ps16k')
        self.assertIn("emulator-build: ${{ matrix.api == '26' && '13610412' || '' }}", smoke)
        self.assertIn("-gpu ${{ matrix.api == '26' && 'swiftshader_indirect' || 'swiftshader' }} ", smoke)
        self.assertIn("grep -Fx 'Pkg.Revision=35.6.11'", (ROOT / 'scripts/prepare_smoke_emulator.sh').read_text())
        self.assertIn("grep -Fx 'Pkg.BuildId=13610412'", (ROOT / 'scripts/prepare_smoke_emulator.sh').read_text())
        self.assertIn('"hw.lcd.density":"160"', (ROOT / 'scripts/prepare_smoke_emulator.sh').read_text())
        self.assertEqual({(item['api'], item['shard']) for item in matrices[-1]},
                         {(api, shard) for api in ('26', '37.2') for shard in range(4)})

    def test_prelaunch_hook_is_atomic_and_gates_smoke_after_action_catches_failure(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('pre-emulator-launch-script: bash scripts/prepare_smoke_emulator.sh "${{ matrix.api }}"', workflow)
        self.assertIn('sudo apt-get install --no-install-recommends -y libpulse0', workflow)
        self.assertLess(workflow.index('          rm -f smoke/prelaunch-ready.txt'),
                        workflow.index('      - name: 安装并检查启动'))
        self.assertLess(workflow.index('sudo apt-get install --no-install-recommends -y libpulse0'),
                        workflow.index('      - name: 安装并检查启动'))
        wrapper = re.search(r"          script: (bash -c '.*')", workflow).group(1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'smoke').mkdir()
            (root / 'scripts').mkdir()
            (root / 'scripts/smoke_boot.sh').write_text('#!/bin/sh\ntouch invoked\nexit 7\n')
            for ready, expected in ((False, 1), (True, 7)):
                with self.subTest(ready=ready):
                    if ready:
                        (root / 'smoke/prelaunch-ready.txt').write_text('26\n')
                    result = subprocess.run(['sh', '-c', wrapper], cwd=root, capture_output=True)
                    self.assertEqual(result.returncode, expected, result.stderr)
                    self.assertEqual((root / 'invoked').exists(), ready)
                    self.assertEqual((root / 'smoke/exit-code.txt').read_text().strip(), str(expected))

    def test_prelaunch_pin_checks_actual_metadata_and_version_command(self):
        script = ROOT / 'scripts/prepare_smoke_emulator.sh'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            (root / 'scripts/install_bounded_adb.sh').write_text(
                '#!/bin/sh\ntouch installer-invoked\nexit "${INSTALL_EXIT:-0}"\n')
            emulator = root / 'emulator'
            emulator.mkdir()
            binary = emulator / 'emulator'
            avd = root / 'avd'
            (avd / 'test.avd').mkdir(parents=True)
            for api, revision, build, exit_code, install_exit, target, expected in (
                    ('26', '35.6.11', '13610412', 0, 0, '26', True),
                    ('26', '37.2.12', '16428233', 0, 0, '26', False),
                    ('26', '35.6.11', 'other', 0, 0, '26', False),
                    ('26', '35.6.11', '13610412', 127, 0, '26', False),
                    ('26', '35.6.11', '13610412', 0, 7, '26', False),
                    ('26', '35.6.11', '13610412', 0, 0, '37.2', False),
                    ('37.2', '37.2.12', '16428233', 0, 0, '37.2', True)):
                with self.subTest(api=api, revision=revision, build=build, exit_code=exit_code,
                                  install_exit=install_exit, target=target):
                    shutil.rmtree(root / 'smoke', ignore_errors=True)
                    (root / 'smoke').mkdir()
                    # A stale success receipt must not survive any failed preparation.
                    (root / 'smoke/prelaunch-ready.txt').write_text('stale\n')
                    (root / 'installer-invoked').unlink(missing_ok=True)
                    binary.write_text('#!/bin/sh\necho emulator-version\nexit ' + str(exit_code) + '\n')
                    binary.chmod(0o755)
                    (emulator / 'source.properties').write_text(
                        f'Pkg.Revision={revision}\nPkg.BuildId={build}\n')
                    (avd / 'test.ini').write_text(f'target=android-{target}\n')
                    (avd / 'test.avd/config.ini').write_text('hw.lcd.width=320\nhw.lcd.height=640\n')
                    result = subprocess.run(['sh', '-c', f'bash "{script.as_posix()}" "{api}"'], cwd=root,
                                            env=dict(os.environ, ANDROID_HOME=temporary,
                                                     ANDROID_AVD_HOME=str(avd), INSTALL_EXIT=str(install_exit)),
                                            capture_output=True)
                    self.assertEqual(result.returncode == 0, expected, result.stderr)
                    self.assertEqual((root / 'smoke/prelaunch-ready.txt').exists(), expected)
                    if expected:
                        self.assertEqual((root / 'smoke/prelaunch-ready.txt').read_text(), api + '\n')
                        self.assertIn('hw.lcd.width=1216\n', (root / 'smoke/avd-config.ini').read_text())
                    if exit_code:
                        self.assertFalse((root / 'installer-invoked').exists())
                        self.assertFalse((root / 'smoke/emulator-package.txt').exists())
                        self.assertNotIn('hw.lcd.width=1216', (avd / 'test.avd/config.ini').read_text())

    def test_identity_and_unchanged_application(self):
        env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123')
        record = dict(source_sha='a' * 40, run_id='123', attempt='1', application_id='app.daiyu.android', variant='debug', debuggable=True)
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
        for changed in (['app/src/androidTest/java/app/daiyu/android/ChangedTest.java'], ['app/build.gradle.kts'], ['core-domain/src/main/Foo.kt'],
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

    def test_reuse_requires_explicit_matching_release_variant(self):
        env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123', SOURCE_VARIANT='release')
        record = dict(source_sha='a' * 40, run_id='123', attempt='1', application_id='app.daiyu.android',
                      variant='release', debuggable=False, production_delivery=True, profileable=False,
                      test_application_id='app.daiyu.android.test')
        run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main', workflow_id=383377276,
                   status='completed', conclusion='failure')
        jobs = [dict(name='单测、检查与生产Release', conclusion='success')]
        source.verify(env, record, run, jobs, ['tests/test_release_package.py'])
        for field, value in (('variant', 'debug'), ('debuggable', True), ('debuggable', 0),
                             ('production_delivery', False), ('profileable', True),
                             ('test_application_id', 'app.daiyu.android.synthetic.test')):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                source.verify(env, dict(record, **{field: value}), run, jobs, [])
        for variant in ('debug', 'performance', ''):
            with self.subTest(variant=variant), self.assertRaises(ValueError):
                source.verify(dict(env, SOURCE_VARIANT=variant), record, run, jobs, [])
        with self.assertRaises(ValueError):
            source.verify(env, record, run, [dict(name='单测、检查与开发包', conclusion='success')], [])
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        smoke = workflow.split('  smoke:\n', 1)[1]
        self.assertIn("inputs.mode == 'smoke' && inputs.source_variant == 'release'", smoke)
        self.assertIn('SOURCE_VARIANT: ${{ inputs.source_variant }}', smoke)


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

    def test_display_baseline_is_applied_and_leaks_are_rejected(self):
        for api in (26, 37):
            with self.subTest(api=api):
                result = self.smoke(api=api)
                self.assertEqual(result.returncode, 0, result.stderr)
                calls = (self.root / 'smoke/adb-calls.txt').read_text()
                self.assertLess(calls.index('shell wm size 320x640'), calls.index('shell am instrument'))
                self.assertIn('Override size: 320x640', (self.root / 'smoke/display-size-after.txt').read_text())
        for case in ('display-fail', 'wrong-native-display', 'missing-display-override', 'wrong-density', 'display-leak'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                calls = (self.root / 'smoke/adb-calls.txt').read_text()
                self.assertNotIn('shell am start', calls)
                if case != 'display-leak':
                    self.assertNotIn('install -r', calls)
                    self.assertNotIn('shell am instrument', calls)
                else:
                    self.assertIn('shell am instrument', calls)

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
              source_name='Test.kt', extra_sources=None, expected_classes=None, reported_count=None, shard_count=1, shard_index=0,
              test_classes=None, mode="smoke"):
        (self.root / 'delivery/tests').mkdir(parents=True, exist_ok=True)
        (self.root / 'delivery/app.apk').touch()
        (self.root / 'delivery/tests/platform-test.apk').touch()
        source_root = self.root / 'app/src/androidTest'
        shutil.rmtree(source_root, ignore_errors=True)
        source_root.mkdir(parents=True, exist_ok=True)
        for name in ('adb-calls.txt', 'instrumentation-args.txt', 'launch-args.txt', 'display-size-state', 'display-density-state'):
            (self.root / 'smoke' / name).unlink(missing_ok=True)
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
        (self.root / 'smoke/rebooted').unlink(missing_ok=True)
        (self.root / 'smoke/boot-id-polls').unlink(missing_ok=True)
        (self.root / 'smoke/fixture-ime-selected').unlink(missing_ok=True)
        (self.root / 'smoke/ime-enabled-corrupted').unlink(missing_ok=True)
        fake_ui = self.root / 'fake-ui.xml'
        fake_ui.write_text('<broken' if case == 'malformed-ui' else
                           '代鱼 · 聊天 发送' if case == 'old-title-only' else
                           ET.tostring(ui, encoding='unicode'), encoding='utf-8')
        self.executable('adb', r'''#!/bin/sh
printf '%s\n' "$*" >> smoke/adb-calls.txt
fake_boot_id() {
  if [ -f smoke/rebooted ] && [ "$CASE" != reboot-unchanged ]; then
   polls=0; [ ! -f smoke/boot-id-polls ] || polls=$(cat smoke/boot-id-polls)
   polls=$((polls + 1)); printf '%s' "$polls" > smoke/boot-id-polls
   if [ "$CASE" = reboot-delayed ] && [ "$polls" -le 20 ]; then
    printf '11111111-1111-1111-1111-111111111111\n'
   else printf '22222222-2222-2222-2222-222222222222\n'; fi
  else printf '11111111-1111-1111-1111-111111111111\n'; fi
}
case "$*" in
 'shell wm size 320x640') [ "$CASE" != display-fail ] || exit 7; printf '320x640' > smoke/display-size-state ;;
 'shell wm density 160') printf '160' > smoke/display-density-state ;;
 'shell wm size')
  physical=1216x2432; [ "$CASE" != wrong-native-display ] || physical=320x640
  [ "$CASE" != missing-display-override ] || { printf 'Physical size: 320x640\n'; exit 0; }
  printf 'Physical size: %s\nOverride size: %s\n' "$physical" "$(cat smoke/display-size-state)" ;;
 'shell wm density')
  density=$(cat smoke/display-density-state); [ "$CASE" != wrong-density ] || density=320
  printf 'Physical density: 160\nOverride density: %s\n' "$density" ;;
 'shell getprop ro.build.version.sdk') printf '%s\n' "$API" ;;
 'shell getprop sys.boot_completed') printf '1\n' ;;
 'shell cat /proc/sys/kernel/random/boot_id') fake_boot_id ;;
 'shell settings get secure default_input_method')
  if [ -f smoke/rebooted ] && [ "$CASE" = reboot-ime-changed ]; then printf 'other/.Ime\n'
  elif [ -f smoke/fixture-ime-selected ]; then
   if [ "$CASE" = fixture-wrong-default ]; then printf 'other/.Ime\n'
   else printf 'jp.co.omronsoft.openwnn/.OpenWnnJAJP\n'; fi
  else printf 'original/.Ime\n'; fi ;;
 'shell settings get secure enabled_input_methods')
  if [ -f smoke/ime-enabled-corrupted ]; then printf 'jp.co.omronsoft.openwnn/.OpenWnnJAJP\n'; exit 0; fi
  printf 'original/.Ime'
  if [ -f smoke/fixture-ime-selected ] && [ "$CASE" != fixture-enable-missing ]; then
   printf ':jp.co.omronsoft.openwnn/.OpenWnnJAJP'
  fi
  printf '\n' ;;
 'shell pm path jp.co.omronsoft.openwnn')
  [ "$CASE" != fixture-package-missing ] || exit 7
  printf 'package:/system/app/OpenWnn/OpenWnn.apk\n' ;;
 'shell ime enable jp.co.omronsoft.openwnn/.OpenWnnJAJP')
  [ "$CASE" != fixture-enable-fail ] || exit 7 ;;
 'shell ime set jp.co.omronsoft.openwnn/.OpenWnnJAJP')
  [ "$CASE" != fixture-set-fail ] || exit 7
  touch smoke/fixture-ime-selected ;;
 'reboot') [ "$CASE" != reboot-fail ] || exit 7; touch smoke/rebooted ;;

 *pidof*) fake_boot_id; printf '123\n456\n' ;;
 'shell pm path android') printf 'package:/system/framework/framework-res.apk\n' ;;
 'shell getprop debug.hwui.renderer') printf '\n' ;;
 'shell dumpsys webviewupdate')
  [ "$CASE" != webview-command-fail ] || exit 8
  [ "$CASE" != webview-garbage ] || { printf 'not a service dump\n'; exit 0; }
  if [ "$CASE" = webview-null ]; then printf 'Current WebView package is null\n'
  else printf 'Current WebView package (name, version): (com.android.chrome, 61.0)\n'; fi
  dirty=false; [ "$CASE" != webview-dirty ] || dirty=true
  finished=2; [ "$CASE" != webview-relro ] || finished=1
  printf 'Any WebView package installed: true\nWebView package dirty: %s\nNumber of relros started: 2\nNumber of relros finished: %s\n' "$dirty" "$finished" ;;
 'shell pm path com.android.chrome')
  [ "$CASE" = webview-no-path ] || printf 'package:/system/app/Chrome/Chrome.apk\n' ;;

 'shell cat /proc/meminfo') printf 'MemTotal: 5000000 kB\n' ;;
 'install '*) printf 'Success\n'; [ "$CASE" != install-fail ] || exit 7 ;;
 'shell am instrument '*)
  printf '%s\n' "$@" > smoke/instrumentation-args.txt
  [ "$4" = -w ] && [ "$5" = -r ] || exit 11
  if [ "$SMOKE_MODE" = candidate ]; then
   [ "$#" -eq 12 ] && [ "$6" = -e ] && [ "$7" = timeout_msec ] && [ "$8" = 180000 ] || exit 11
   shift 3
  else
   [ "$#" -eq 9 ] || exit 11
  fi
  [ "$6" = -e ] && [ "$7" = class ] && [ "$8" = "$TEST_CLASSES" ] &&
   [ "$9" = app.daiyu.android.test/androidx.test.runner.AndroidJUnitRunner ] || exit 11
  if [ "$CASE" = androidx-ok ] || [ "$CASE" = androidx-error-name ] || [ "$CASE" = androidx-exception-name ]; then
   method=testOne
   [ "$CASE" != androidx-error-name ] || method=testPreferenceWriteFailureKeepsConfirmedChoiceAndShowsError
   [ "$CASE" != androidx-exception-name ] || method=testRejectsException
   printf 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: class=app.daiyu.android.FirstTest\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n' "$method"
   printf 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: class=app.daiyu.android.FirstTest\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nTime: 0.01\n' "$method"
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
  [ "$CASE" != display-leak ] || printf '1216x2432' > smoke/display-size-state
  [ "$CASE" != fixture-enabled-leak ] || touch smoke/ime-enabled-corrupted
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
 'logcat '*)
  case "$CASE" in
   pre-crash) printf 'FATAL EXCEPTION\n' ;;
   reboot-crash) [ ! -f smoke/rebooted ] || printf 'Fatal signal 6 (SIGABRT)\n' ;;

   pre-native-crash) printf 'Fatal signal 6 (SIGABRT)\n' ;;
   late-pre-native-crash) grep -q 'shell pm path android' smoke/adb-calls.txt && printf 'Fatal signal 6 (SIGABRT)\n'; true ;;
   pre-gl-crash) printf 'GL errors! frameworks/base/libs/hwui/pipeline/skia/SkiaOpenGLPipeline.cpp:110\n' ;;
   crash) [ ! -f smoke/launch-args.txt ] || printf 'FATAL EXCEPTION\n' ;;
   native-crash) [ ! -f smoke/launch-args.txt ] || printf 'Fatal signal 6 (SIGABRT)\n' ;;
  esac ;;
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
                   SMOKE_SHARD_COUNT=str(shard_count), SMOKE_SHARD_INDEX=str(shard_index), SMOKE_MODE=mode)
        env.pop('SMOKE_TEST_CLASSES', None)
        if test_classes is not None:
            env['SMOKE_TEST_CLASSES'] = test_classes
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
        self.assertIn('phase=instrumentation budget=5400s', (self.root / 'smoke/stages.txt').read_text())
        args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
        self.assertEqual(args[3:8], ['-w', '-r', '-e', 'class', 'app.daiyu.android.FirstTest'])
        self.assertEqual(args[8], 'app.daiyu.android.test/androidx.test.runner.AndroidJUnitRunner')

    def test_webview_ready_and_renderer_observed_without_framework_restart(self):
        for api in (26, 37):
            result = self.smoke(api=api)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
            probe = calls.index('shell dumpsys webviewupdate')
            install = next(i for i, value in enumerate(calls) if value.startswith('install '))
            self.assertLess(probe, install)
            self.assertIn('shell pm path com.android.chrome', calls)
            self.assertNotIn('root', calls)
            self.assertFalse(any('setprop debug.hwui.renderer' in value or 'stop &&' in value for value in calls))
            self.assertLess(calls.index('shell getprop debug.hwui.renderer'), probe)
            self.assertEqual((self.root / 'smoke/hwui-renderer.txt').read_text(), '\n')

    def test_api26_reboots_once_after_override_without_losing_first_boot_crashes(self):
        for api in (26, 37):
            with self.subTest(api=api):
                result = self.smoke(api=api)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
                self.assertEqual(calls.count('reboot'), int(api == 26))
                if api == 26:
                    restart = calls.index('reboot')
                    self.assertLess(calls.index('shell wm size 320x640'), restart)
                    self.assertLess(calls.index('logcat -d -b all'), restart)
                    self.assertLess(restart, next(i for i, c in enumerate(calls) if c.startswith('install ')))
                    self.assertTrue((self.root / 'smoke/logcat-first-boot.txt').exists())
                    self.assertEqual((self.root / 'smoke/ime-default-before.txt').read_bytes(),
                                     (self.root / 'smoke/ime-default-restored.txt').read_bytes())
                self.assertFalse(any('logcat -c' in c or 'force-stop' in c for c in calls))

    def test_api26_waits_for_new_boot_even_when_old_pids_stay_stable(self):
        result = self.smoke(case='reboot-delayed')
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        readiness = [row for row in (self.root / 'smoke/readiness.txt').read_text().splitlines()
                     if ' attempt=' in row]
        self.assertEqual(len(readiness), 35)
        self.assertTrue(all('stable=0 boot_changed=0' in row for row in readiness[:20]))
        self.assertIn('stable=15 boot_changed=1', readiness[-1])
        calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
        self.assertEqual(calls.count('reboot'), 1)
        self.assertLess(calls.index('reboot'), calls.index('install delivery/app.apk'))

    def test_api26_selects_one_preinstalled_real_ime_before_tests_without_disabling_original(self):
        for api in (26, 37):
            with self.subTest(api=api):
                result = self.smoke(api=api)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
                switch = 'shell ime set jp.co.omronsoft.openwnn/.OpenWnnJAJP'
                self.assertEqual(calls.count(switch), int(api == 26))
                self.assertFalse(any('ime disable' in call for call in calls))
                if api == 26:
                    self.assertLess(calls.index('reboot'), calls.index(switch))
                    self.assertLess(calls.index(switch), calls.index('install delivery/app.apk'))
                    self.assertEqual((self.root / 'smoke/ime-default-restored.txt').read_text(), 'original/.Ime\n')
                    self.assertEqual((self.root / 'smoke/ime-fixture-selected.txt').read_text(),
                                     'jp.co.omronsoft.openwnn/.OpenWnnJAJP\n')

    def test_api26_rejects_enabled_ime_leak_even_when_default_fixture_is_unchanged(self):
        result = self.smoke(case='fixture-enabled-leak')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / 'smoke/ime-fixture-selected.txt').read_bytes(),
                         (self.root / 'smoke/ime-fixture-after.txt').read_bytes())
        self.assertNotEqual((self.root / 'smoke/ime-enabled-selected.txt').read_bytes(),
                            (self.root / 'smoke/ime-enabled-after.txt').read_bytes())
        calls = (self.root / 'smoke/adb-calls.txt').read_text()
        self.assertIn('shell am instrument ', calls)
        self.assertNotIn('shell am start ', calls)
        self.assertEqual(calls.count('shell ime set '), 1)

    def test_invalid_explicit_ime_fixture_fails_before_installation_without_fallback(self):
        for case in ('fixture-package-missing', 'fixture-enable-fail', 'fixture-set-fail',
                     'fixture-wrong-default', 'fixture-enable-missing'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                calls = (self.root / 'smoke/adb-calls.txt').read_text()
                self.assertNotIn('install delivery/app.apk', calls)
                self.assertNotIn('ime disable', calls)
                self.assertLessEqual(calls.count('shell ime set '), 1)

    def test_api26_reboot_failure_identity_ime_and_new_crashes_fail_closed(self):
        for case in ('reboot-fail', 'reboot-unchanged', 'reboot-ime-changed', 'reboot-crash'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
                self.assertFalse(any(c.startswith('install ') or 'am instrument' in c for c in calls))
                self.assertTrue((self.root / 'smoke/logcat-first-boot.txt').exists())
                self.assertFalse(any('logcat -c' in c for c in calls))

    def test_startup_java_and_native_crashes_cannot_start_tests(self):
        for case in ('pre-crash', 'pre-native-crash', 'pre-gl-crash', 'late-pre-native-crash'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
                self.assertFalse(any(value.startswith('install ') for value in calls))
                self.assertFalse(any('am instrument' in value for value in calls))
                self.assertTrue((self.root / 'smoke/logcat-before.txt').read_text())
                self.assertFalse(any('logcat -c' in value for value in calls))

    def test_unready_webview_cannot_start_tests(self):
        for case in ('webview-command-fail', 'webview-garbage', 'webview-null',
                     'webview-dirty', 'webview-relro', 'webview-no-path'):
            with self.subTest(case=case):
                result = self.smoke(case=case)
                self.assertNotEqual(result.returncode, 0)
                calls = (self.root / 'smoke/adb-calls.txt').read_text().splitlines()
                self.assertFalse(any(value.startswith('install ') for value in calls))
                self.assertFalse(any('am instrument' in value for value in calls))
                self.assertTrue((self.root / 'smoke/logcat-exit.txt').exists())

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

    def test_error_and_exception_method_names_pass_live_smoke_on_both_apis(self):
        for api in (26, 37):
            for case in ('androidx-error-name', 'androidx-exception-name'):
                with self.subTest(api=api, case=case):
                    result = self.smoke(api=api, case=case)
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

    def test_duration_plan_is_identical_on_both_apis_and_preserves_inventory(self):
        import hashlib
        sources = {f"Extra{i}.kt": f"package app.daiyu.android\nclass Extra{i}Test : android.test.InstrumentationTestCase() {{\n fun testOne() {{}}\n}}\n" for i in range(8)}
        rows = {f'app.daiyu.android.Extra{i}Test': dict(tests=1,
                milliseconds=900 if i == 4 else 100,
                source_path=f'app/src/androidTest/Extra{i}.kt',
                source_sha256=hashlib.sha256(sources[f'Extra{i}.kt'].encode()).hexdigest())
                for i in range(8)}
        data = self.root / 'scripts/acceptance/instrumentation-durations.json'
        data.parent.mkdir(parents=True, exist_ok=True)
        data.write_text(json.dumps(dict(schema_version=1, classes=rows)))
        # FirstTest is unmeasured and receives the measured median (100ms).
        expected = [[4], [0, 3, 7], [1, 5], [2, 6]]
        groups = [[f'app.daiyu.android.Extra{i}Test' for i in group] for group in expected]
        groups[2].append('app.daiyu.android.FirstTest')
        reference = None
        for api, index in [(26, i) for i in range(4)] + [(37, i) for i in range(4)]:
            result = self.smoke(api=api, mode='candidate', extra_sources=sources,
                                test_classes=','.join(sorted(rows) + ['app.daiyu.android.FirstTest']),
                                expected_classes=','.join(groups[index]),
                                reported_count=len(groups[index]), shard_count=4, shard_index=index)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            inventory = json.loads((self.root / 'smoke/instrumentation-shards.json').read_text())
            self.assertEqual([shard['classes'] for shard in inventory['shards']], groups)
            self.assertEqual(inventory['total_tests'], 9)
            receipt = json.loads((self.root / 'smoke/shard-plan.json').read_text())
            self.assertEqual(receipt['strategy'], 'duration_lpt')
            self.assertEqual(receipt['estimated_shard_ms'], [900, 300, 300, 200])
            self.assertEqual(receipt['duration_data_sha256'], hashlib.sha256(data.read_bytes()).hexdigest())
            self.assertTrue(receipt['estimates_only'])
            self.assertFalse(receipt['formal_acceptance'])
            if reference is not None:
                self.assertEqual(receipt, reference)
            reference = receipt

    def test_candidate_runs_new_selected_class_on_both_apis(self):
        selected = 'app.daiyu.android.other.SecondTest'
        for api in (26, 37):
            result = self.smoke(api=api, mode='candidate',
                extra_sources={'Second.java': JAVA_SECOND_SOURCE}, test_classes=selected,
                expected_classes=selected, reported_count=4)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            args = (self.root / 'smoke/instrumentation-args.txt').read_text().splitlines()
            self.assertEqual(args, ['shell', 'am', 'instrument', '-w', '-r',
                '-e', 'timeout_msec', '180000', '-e', 'class', selected,
                'app.daiyu.android.test/androidx.test.runner.AndroidJUnitRunner'])
            selection = self.selection()
            self.assertEqual(selection['mode'], 'candidate')
            self.assertIs(selection['formal_acceptance'], False)
            self.assertEqual(selection['selected_classes'], [selected])
            self.assertEqual(selection['expected_tests'], 4)
            self.assertEqual(selection['status'], 'completed')
            inventory = json.loads((self.root / 'smoke/instrumentation-shards.json').read_text())
            self.assertEqual(inventory['shard_count'], 1)
            self.assertEqual(inventory['total_tests'], 5)

    def test_candidate_rejects_empty_invalid_filter_before_adb(self):
        for value in (None, '', ' ', 'app.daiyu.android.MissingTest',
                      'app.daiyu.android.FirstTest#testOne', '$(touch bad)'):
            result = self.smoke(mode='candidate', test_classes=value)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / 'smoke/adb-calls.txt').exists())

    def test_candidate_rejects_invalid_shards_before_adb(self):
        for count, index in ((2, 0), (3, 1), (1, 1), (4, 4)):
            result = self.smoke(mode='candidate', test_classes='app.daiyu.android.FirstTest',
                                shard_count=count, shard_index=index)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / 'smoke/adb-calls.txt').exists())

    def test_candidate_failure_is_not_completed(self):
        for case in ('count', 'instrument-timeout', 'instrument-fail'):
            result = self.smoke(mode='candidate', test_classes='app.daiyu.android.FirstTest', case=case)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(self.selection()['status'], 'planned')
            self.assertFalse((self.root / 'smoke/launch-args.txt').exists())

    def selection(self):
        return json.loads((self.root / 'smoke/selection.json').read_text())

    def test_unset_and_empty_filter_preserve_original_inventory(self):
        manifests = []
        for value in (None, ''):
            result = self.smoke(test_classes=value, mode='build')
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            manifests.append((self.root / 'smoke/instrumentation-shards.json').read_bytes())
            selection = self.selection()
            self.assertEqual(selection['scope'], 'full')
            self.assertEqual(selection['requested_classes'], [])
            self.assertEqual(selection['selected_classes'], ['app.daiyu.android.FirstTest'])
            self.assertEqual(selection['status'], 'completed')
        self.assertEqual(*manifests)

    def test_filtered_subset_deduplicates_but_keeps_real_count_and_runner(self):
        for api in (26, 37):
            selected = 'app.daiyu.android.other.SecondTest'
            result = self.smoke(api=api, extra_sources={'Second.java': JAVA_SECOND_SOURCE},
                                test_classes=selected + ',' + selected,
                                expected_classes=selected, reported_count=4)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text().splitlines(), ['4', selected])
            selection = self.selection()
            self.assertEqual(selection['scope'], 'targeted')
            self.assertEqual(selection['requested_classes'], [selected])
            self.assertEqual(selection['selected_classes'], [selected])
            self.assertEqual(selection['expected_tests'], 4)
            self.assertEqual(selection['status'], 'completed')
            for role in ('inventory', 'instrumentation'):
                ref = selection[role]
                self.assertEqual(ref['sha256'], hashlib.sha256((self.root / 'smoke' / ref['path']).read_bytes()).hexdigest())
            inventory = json.loads((self.root / 'smoke/instrumentation-shards.json').read_text())
            self.assertEqual(inventory['total_tests'], 5)
            self.assertEqual(len(inventory['all_classes']), 2)

    def test_selecting_all_classes_is_still_targeted(self):
        result = self.smoke(test_classes='app.daiyu.android.FirstTest')
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(self.selection()['scope'], 'targeted')
        self.assertEqual(self.selection()['requested_classes'], self.selection()['selected_classes'])

    def test_invalid_or_unknown_filters_fail_before_any_adb(self):
        known = 'app.daiyu.android.FirstTest'
        for value in (' ', known + ',', ',' + known, known + ', ' + known,
                      known + '#testOne', 'app.daiyu.android', known + '*',
                      'app.daiyu.android.MissingTest', known + ',app.daiyu.android.MissingTest',
                      known + '\n', '$(touch bad)', 'app.daiyu.android.Foo;echo bad'):
            with self.subTest(value=value):
                result = self.smoke(test_classes=value)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / 'smoke/adb-calls.txt').exists())
                self.assertFalse((self.root / 'smoke/instrumentation-args.txt').exists())
                self.assertFalse((self.root / 'smoke/selection.json').exists())
        result = self.smoke(test_classes=known, source_text='class Helper {}')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'smoke/adb-calls.txt').exists())

    def test_non_smoke_filter_is_rejected_before_any_adb(self):
        for mode in ('build', 'checks', 'synthetic', 'performance', ''):
            result = self.smoke(mode=mode, test_classes='app.daiyu.android.FirstTest')
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / 'smoke/adb-calls.txt').exists())

    def test_filter_balances_requested_classes_preserves_inventory_and_empty_shards_still_launch(self):
        sources = {f"Extra{i}.kt": f"package app.daiyu.android\nclass Extra{i}Test : android.test.InstrumentationTestCase() {{\n fun testOne() {{}}\n}}\n" for i in range(8)}
        all_names = sorted(['app.daiyu.android.FirstTest'] + [f'app.daiyu.android.Extra{i}Test' for i in range(8)])
        groups = [all_names[i::4] for i in range(4)]
        requested = [all_names[0], all_names[-1]]
        remaining = [name for name in all_names if name not in requested]
        groups = [sorted(([requested[i]] if i < len(requested) else []) + remaining[i::4]) for i in range(4)]
        for api in (26, 37):
            observed = set()
            for index in range(4):
                selected = sorted(set(groups[index]).intersection(requested))
                # Seed stale success files to prove the script itself removes them.
                (self.root / 'smoke').mkdir(exist_ok=True)
                (self.root / 'smoke/stages.txt').write_text('stale-instrumentation-success\n')
                (self.root / 'smoke/settings-ui').mkdir(exist_ok=True)
                (self.root / 'smoke/settings-ui/old.png').write_text('stale settings preview')
                for name in ('platform-tests.txt', 'instrumentation-result.json'):
                    (self.root / 'smoke' / name).write_text('stale success')
                result = self.smoke(api=api, extra_sources=sources, shard_count=4, shard_index=index,
                                    test_classes=','.join(reversed(requested)),
                                    expected_classes=','.join(selected), reported_count=len(selected))
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                inventory = json.loads((self.root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual(inventory['shards'][index]['classes'], groups[index])
                selection = self.selection()
                self.assertEqual(selection['requested_classes'], requested)
                self.assertEqual(selection['selected_classes'], selected)
                self.assertEqual(selection['expected_tests'], len(selected))
                observed.update(selected)
                self.assertNotIn('stale-instrumentation-success', (self.root / 'smoke/stages.txt').read_text())
                self.assertFalse((self.root / 'smoke/settings-ui').exists())
                if not selected:
                    self.assertEqual(selection['status'], 'filtered-empty-shard')
                    self.assertNotIn('instrumentation', selection)
                    for name in ('platform-tests.txt', 'instrumentation-result.json', 'instrumentation-args.txt'):
                        self.assertFalse((self.root / 'smoke' / name).exists())
                    self.assertIn('reason=filtered-empty-shard', (self.root / 'smoke/stages.txt').read_text())
                    self.assertEqual((self.root / 'smoke/instrumentation-targets.txt').read_text(), '0\n\n')
                self.assertTrue((self.root / 'smoke/launch-args.txt').exists())
                self.assertTrue((self.root / 'smoke/启动.png').exists())
            self.assertEqual(observed, set(requested))

    def test_targeted_count_mismatch_or_runner_failure_is_not_success(self):
        for case in ('count', 'instrument-timeout', 'instrument-fail'):
            result = self.smoke(case=case, test_classes='app.daiyu.android.FirstTest')
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(self.selection()['status'], 'planned')
            self.assertNotIn('instrumentation', self.selection())
            self.assertFalse((self.root / 'smoke/launch-args.txt').exists())

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
        for case in ['count', 'failure', 'old-package', 'crash', 'native-crash', 'png', 'foreign-ui',
                     'missing-send', 'missing-editor', 'malformed-ui', 'old-title-only']:
            result = self.smoke(case=case)
            with self.subTest(case=case):
                self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
