"""Synthetic process tests; these do not claim Android instrumentation coverage."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('smoke_source', ROOT / 'scripts/verify_smoke_source.py')
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class SourceTests(unittest.TestCase):
    def test_identity_and_unchanged_application(self):
        env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123')
        record = dict(source_sha='a' * 40, run_id='123', attempt='1', application_id='app.daiyu.android')
        run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main', workflow_id=383377276, status='completed')
        jobs = [dict(name='单测、检查与开发包', conclusion='success')]
        source.verify(env, record, run, jobs, ['scripts/smoke_boot.sh', 'docs/重构清单.md'])
        for changed in (['app/build.gradle.kts'], ['core-domain/src/main/Foo.kt'], ['scripts/prepare_signing.py']):
            with self.assertRaises(ValueError):
                source.verify(env, record, run, jobs, changed)
        for field, value in [('run_attempt', 2), ('head_sha', 'b' * 40), ('status', 'in_progress')]:
            with self.assertRaises(ValueError):
                source.verify(env, record, dict(run, **{field: value}), jobs, [])
        with self.assertRaises(ValueError):
            source.verify(env, record, run, [], [])
        with self.assertRaises(ValueError):
            source.verify(env, dict(record, attempt='2'), run, jobs, [])


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
        path.write_text(body)
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

    def smoke(self, api=26, case='ok'):
        (self.root / 'delivery/tests').mkdir(parents=True, exist_ok=True)
        (self.root / 'delivery/app.apk').touch()
        (self.root / 'delivery/tests/platform-test.apk').touch()
        (self.root / 'app/src/androidTest').mkdir(parents=True, exist_ok=True)
        (self.root / 'app/src/androidTest/Test.kt').write_text('fun testOne() {}\n')
        self.executable('sleep', '#!/bin/sh\nexit 0\n')
        self.executable('timeout', '#!/bin/sh\nshift 2\nexec "$@"\n')
        self.executable('adb', '''#!/usr/bin/env python3
import os,sys
from pathlib import Path
a=sys.argv[1:];case=os.environ['CASE'];api=os.environ['API']
if a==['shell','getprop','ro.build.version.sdk']: print(api)
elif a==['shell','getprop','sys.boot_completed']: print('1')
elif a and 'pidof' in a[-1]: print('123\\n456')
elif a==['shell','pm','path','android']: print('package:/system/framework/framework-res.apk')
elif a[:3]==['shell','cat','/proc/meminfo']: print('MemTotal: 5000000 kB')
elif a[0]=='install':
 print('Success');sys.exit(7 if case=='install-fail' else 0)
elif a[:3]==['shell','am','instrument']:
 print('OK (2 tests)' if case=='count' else 'OK (1 tests)',flush=True)
 if case=='failure': print('FAILURES!!!')
 sys.exit(9 if case=='instrument-fail' else 0)
elif a[:3]==['shell','am','start']: print('Status: ok')
elif a[:3]==['shell','pm','list']: print('package:io.github.mangi.eta' if case=='old-package' else 'package:app.daiyu.android')
elif a[0]=='pull': Path(a[-1]).write_text('代鱼 · 聊天 发送')
elif a[0]=='logcat': print('FATAL EXCEPTION' if case=='crash' else '')
elif a[0]=='exec-out': sys.stdout.buffer.write(b'bad' if case=='png' else b'\\x89PNG\\r\\n\\x1a\\n')
elif a[:3]==['emu','screenrecord','screenshot']: Path(a[-1],'Screenshot_fake.png').write_bytes(b'\\x89PNG\\r\\n\\x1a\\n')
''')
        env = dict(self.env, API=str(api), CASE=case)
        return subprocess.run(['bash', str(ROOT / 'scripts/smoke_boot.sh')], cwd=self.root, env=env, capture_output=True, timeout=15)

    def test_api26_success_and_png_isolation(self):
        result = self.smoke()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual((self.root / 'smoke/启动.png').read_bytes(), b'\x89PNG\r\n\x1a\n')
        self.assertIn('phase=instrumentation exit=0', (self.root / 'smoke/stages.txt').read_text())

    def test_high_api_screenshot(self):
        result = self.smoke(api=37)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertTrue((self.root / 'smoke/启动.png').exists())

    def test_pipefail_preserves_install_failure(self):
        self.assertEqual(self.smoke(case='install-fail').returncode, 7)

    def test_pipefail_preserves_instrumentation_failure(self):
        self.assertEqual(self.smoke(case='instrument-fail').returncode, 9)

    def test_business_gates_remain(self):
        for case in ['count', 'failure', 'old-package', 'crash', 'png']:
            with self.subTest(case=case):
                self.assertNotEqual(self.smoke(case=case).returncode, 0)


if __name__ == '__main__':
    unittest.main()
