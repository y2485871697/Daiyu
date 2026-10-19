"""Synthetic manifest/tool fixtures; never a real Release build or device result."""
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_release_package as package

APP = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android" android:versionName="0.1.0" android:versionCode="1"><application android:name="app.daiyu.android.ChatApplication"><provider android:authorities="app.daiyu.android.shizuku"/></application></manifest>'''
TEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.daiyu.android.test"><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="app.daiyu.android"/></manifest>'''
SIGNATURE = ('Number of signers: 1\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
             'Signer #1 certificate SHA-256 digest: ' + package.EXPECTED_CERT + '\n')


class ReleasePackageTests(unittest.TestCase):
    def test_manifest_requires_production_identity_and_release_flags(self):
        package.verify_manifests(APP, TEST)
        package.verify_manifests(APP.replace('<application ', '<application android:debuggable="false" android:testOnly="false" '), TEST)
        for old, new in (
            ('package="app.daiyu.android"', 'package="app.daiyu.android.synthetic"'),
            ('versionName="0.1.0"', 'versionName="other"'), ('versionCode="1"', 'versionCode="2"'),
            ('<application ', '<application android:debuggable="true" '),
            ('<application ', '<application android:testOnly="true" '),
            ('</application>', '<profileable android:shell="true"/></application>'),
            ('ChatApplication', 'OtherApplication'),
            ('package="app.daiyu.android"', 'android:sharedUserId="shared" package="app.daiyu.android"'),
            ('app.daiyu.android.shizuku', 'old.app.shizuku'),
            ('android:authorities="app.daiyu.android.shizuku"', ''),
            ('</application>', '</application><application/>'),
        ):
            with self.subTest(new=new), self.assertRaises(ValueError):
                package.verify_manifests(APP.replace(old, new), TEST)
        for old, new in (
            ('app.daiyu.android.test', 'app.daiyu.android.synthetic.test'),
            ('targetPackage="app.daiyu.android"', 'targetPackage="app.daiyu.android.synthetic"'),
            ('AndroidJUnitRunner', 'OtherRunner'),
            ('</manifest>', '<instrumentation/></manifest>'),
            ('</manifest>', '<application><provider android:authorities="old.app.test"/></application></manifest>'),
            ('package="app.daiyu.android.test"', 'android:sharedUserId="shared" package="app.daiyu.android.test"'),
        ):
            with self.subTest(new=new), self.assertRaises(ValueError):
                package.verify_manifests(APP, TEST.replace(old, new))

    def test_pair_actual_manifests_signatures_and_hashes_before_record(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / 'tests').mkdir()
            app = root / '代鱼-0.1.0-aaaaaaaa.apk'; app.write_bytes(b'app')
            test = root / 'tests/platform-test.apk'; test.write_bytes(b'tests')
            env = dict(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1')
            calls = []
            def run(argv, **kwargs):
                calls.append(argv)
                output = SIGNATURE if argv[1] == 'verify' else TEST if Path(argv[-1]) == test else APP
                return SimpleNamespace(stdout=output)
            record = package.verify_delivery('apkanalyzer', 'apksigner', root, env, run)
            self.assertEqual(4, len(calls))
            self.assertEqual('release', record['variant'])
            self.assertIs(record['debuggable'], False)
            self.assertIs(record['profileable'], False)
            self.assertEqual(hashlib.sha256(b'app').hexdigest(), record['sha256'][app.name])
            for name, digest in record['manifest_sha256'].items():
                self.assertEqual(hashlib.sha256((root / name).read_bytes()).hexdigest(), digest)
            self.assertEqual(record, json.loads((root / '构建记录.json').read_text()))
            def debug_app(argv, **kwargs):
                result = run(argv, **kwargs)
                if argv[1] == 'manifest' and Path(argv[-1]) == app:
                    result.stdout = APP.replace('<application ', '<application android:debuggable="true" ')
                return result
            with self.assertRaisesRegex(ValueError, 'release_debug_or_test_only_forbidden'):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, debug_app)
            self.assertFalse((root / '构建记录.json').exists())
            def wrong_signer(argv, **kwargs):
                result = run(argv, **kwargs)
                if argv[1] == 'verify' and Path(argv[-1]) == test:
                    result.stdout = result.stdout.replace(package.EXPECTED_CERT, '0' * 64)
                return result
            with self.assertRaises(ValueError):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, wrong_signer)
            self.assertFalse((root / '构建记录.json').exists())
            def failed(argv, **kwargs):
                raise subprocess.CalledProcessError(1, argv)
            with self.assertRaises(subprocess.CalledProcessError):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, failed)
            for key, value in [('GITHUB_SHA', 'a' * 8), ('GITHUB_RUN_ID', '0'), ('GITHUB_RUN_ATTEMPT', 'bad')]:
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'release_source_identity_invalid'):
                    package.verify_delivery('apkanalyzer', 'apksigner', root, {**env, key: value}, run)
            app.write_bytes(b'')
            with self.assertRaisesRegex(ValueError, 'release_apk_pair_missing'):
                package.verify_delivery('apkanalyzer', 'apksigner', root, env, run)

    def test_release_checks_use_the_same_signed_production_configuration_as_packaging(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        release = workflow.split('  release:\n', 1)[1].split('  synthetic:\n', 1)[0]
        def step(name):
            body = release.split(f'      - name: {name}\n', 1)[1]
            return re.split(r'^      - (?:name:|uses:)', body, maxsplit=1, flags=re.MULTILINE)[0]
        prepare = step('准备固定签名')
        unit = step('单测与 Lint')
        assemble = step('构建生产 Release 与同变体测试 APK')
        self.assertIn('python3 scripts/prepare_signing.py', prepare)
        self.assertLess(release.index('- name: 准备固定签名'), release.index('- name: 单测与 Lint'))
        self.assertLess(release.index('- name: 单测与 Lint'), release.index('- name: 构建生产 Release 与同变体测试 APK'))
        for body in (unit, assemble):
            self.assertIn('-PdaiyuProductionRelease=true', body)
            for declaration in (
                "DAIYU_REQUIRE_SIGNING: 'true'",
                'DAIYU_RELEASE_STORE_FILE: ${{ runner.temp }}/daiyu-release.p12',
                'DAIYU_RELEASE_KEY_ALIAS: ${{ secrets.DAIYU_RELEASE_KEY_ALIAS }}',
                'DAIYU_RELEASE_KEY_PASSWORD: ${{ secrets.DAIYU_RELEASE_KEY_PASSWORD }}',
                'DAIYU_RELEASE_STORE_PASSWORD: ${{ secrets.DAIYU_RELEASE_STORE_PASSWORD }}',
            ):
                self.assertIn(declaration, body)
        self.assertIn(':core-domain:test :app:testReleaseUnitTest :app:lintRelease', unit)
        self.assertIn(':app:assembleRelease :app:assembleReleaseAndroidTest', assemble)
        self.assertLess(release.index('- name: 构建生产 Release 与同变体测试 APK'), release.index('- name: 清理临时签名文件'))
        cleanup = step('清理临时签名文件')
        self.assertIn('if: always()', cleanup)
        self.assertIn('rm -f -- "$RUNNER_TEMP/daiyu-release.p12"', cleanup)

    def test_release_workflow_is_separate_full_unfiltered_dual_api_path(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        release = workflow.split('  release:\n', 1)[1].split('  synthetic:\n', 1)[0]
        smoke = workflow.split('  smoke:\n', 1)[1]
        self.assertIn("if: inputs.mode == 'release' && github.ref == 'refs/heads/main'", release)
        self.assertIn(':core-domain:test :app:testReleaseUnitTest :app:lintRelease', release)
        self.assertIn('-PdaiyuProductionRelease=true :app:assembleRelease :app:assembleReleaseAndroidTest', release)
        self.assertNotIn('daiyuSyntheticApplication', release)
        self.assertIn('scripts/verify_release_package.py --apkanalyzer', release)
        self.assertIn('scripts/verify_license_bundle.py', release)
        self.assertIn('name: 代鱼正式候选包', release)
        self.assertLess(release.index('scripts/verify_release_package.py'), release.index('name: 上传生产Release候选包'))
        self.assertIn('needs: [build, release]', smoke)
        self.assertIn("inputs.mode == 'release' && needs.release.result == 'success'", smoke)
        self.assertIn("(inputs.mode == 'release' || (inputs.mode == 'smoke' && inputs.source_variant == 'release')) && '代鱼正式候选包' || '代鱼开发包'", smoke)
        self.assertIn('test -z "$SMOKE_TEST_CLASSES" || test "$SMOKE_MODE" = smoke', release)
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('!daiyuProductionRelease || (!daiyuSyntheticApplication && !daiyuPhysicalPerformance)', gradle)
        self.assertIn('if (daiyuPhysicalPerformance || daiyuProductionRelease) testBuildType = "release"', gradle)
        self.assertIn('check(!daiyuProductionRelease || fixedSigning)', gradle)

    def test_focused_window_uses_activity_layout_token_for_dialog_ownership(self):
        harness = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        focus = harness.split('protected fun focusedWindowRoot()', 1)[1].split('protected fun dismissIme()', 1)[0]
        for fragment in ('candidate.windowLayoutParams.orNull() ?: return false',
                         'activityToken != null && params.token == activityToken',
                         'hostDecor.windowToken != null && decor.applicationWindowToken == hostDecor.windowToken',
                         'FIRST_APPLICATION_WINDOW', 'LAST_SUB_WINDOW',
                         'decor.isAttachedToWindow && decor.hasWindowFocus()'):
            self.assertIn(fragment, focus)
        self.assertNotIn('candidate.decorView.applicationWindowToken == host.window.decorView.applicationWindowToken', focus)
        lifecycle = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ActivityLifecycleTest.kt').read_text()
        regression = lifecycle.split('fun testFocusedRootBelongsToHostWhenImportDialogOwnsFocus()', 1)[1].split('    fun ', 1)[0]
        for fragment in ('start(open = true)', 'confirmation()', 'focusedWindowRoot()',
                         'assertNotSame(current.window.decorView, focused)',
                         'assertEquals(current.window.attributes.token, params.token)'):
            self.assertIn(fragment, regression)

    def test_both_cross_process_receivers_use_framework_parcelable_identity(self):
        cover = (ROOT / 'app/src/androidTest/java/app/daiyu/android/ReleaseLifecycleCoverActivity.java').read_text()
        harness = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        for source, receiver in [(cover, 'control'), (harness, 'reply')]:
            self.assertIn(receiver + '.writeToParcel(parcel, 0)', source)
            self.assertIn('parcel.setDataPosition(0)', source)
            self.assertIn('ResultReceiver.CREATOR.createFromParcel(parcel)', source)
            self.assertIn('parcel.recycle()', source)
        self.assertNotIn('data.putParcelable("finish", control)', cover)
        self.assertNotIn('.putExtra("reply", reply)', harness)
        self.assertIn('.putExtra("reply", remoteReply)', harness)

    def test_cross_process_cover_is_platform_only_and_never_a_production_component(self):
        cover = ROOT / 'app/src/androidTest/java/app/daiyu/android/ReleaseLifecycleCoverActivity.java'
        source = cover.read_text()
        self.assertFalse((ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ReleaseLifecycleCoverActivity.kt').exists())
        imports = re.findall(r'^import ([\w.]+);$', source, flags=re.MULTILINE)
        self.assertTrue(imports)
        self.assertTrue(all(name.startswith('android.') for name in imports))
        for value in ('data.putParcelable("finish", ResultReceiver.CREATOR.createFromParcel(parcel))', 'data.putInt("task", getTaskId())',
                      'data.putInt("pid", android.os.Process.myPid())',
                      'data.putInt("uid", android.os.Process.myUid())',
                      'reply.send(0, data)', 'reply.send(1, null)', 'reply.send(2, null)',
                      'handler.removeCallbacks(expiry)'):
            self.assertIn(value, source)
        self.assertNotIn('finishAndRemoveTask', source)
        for name in ('main', 'release'):
            manifest = ROOT / f'app/src/{name}/AndroidManifest.xml'
            if manifest.exists():
                self.assertNotIn('LifecycleCoverActivity', manifest.read_text())
        harness = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        for value in ('coverPid != android.os.Process.myPid()',
                      'assertEquals(instrumentation.context.applicationInfo.uid, coverUid)',
                      'coverUid != android.os.Process.myUid()',
                      'if (cleanupRequested) finishCover?.send(0, null)',
                      'assertEquals(original.taskId, coverTask)'):
            self.assertIn(value, harness)

    def test_component_fixtures_do_not_depend_on_the_debug_only_activity(self):
        sources = list((ROOT / 'app/src/androidTest').rglob('*.kt'))
        for path in sources:
            self.assertNotIn('ComponentActivity::class.java', path.read_text(), str(path))
        fixtures = ('R08PlanEditorScreenTest', 'R08CleanupApprovalScreenTest',
                    'VirtualDisplayApprovalScreenTest', 'ImageAttachmentsUiTest',
                    'VideoAttachmentsUiTest', 'MessageMarkdownTest', 'ReportedReasoningCardTest',
                    'ToolOperationCardTest', 'SubAgentStatusDialogTest', 'ChatMessageListMeasurePhaseTest')
        for name in fixtures:
            matches = [p for p in sources if p.stem == name]
            self.assertEqual(1, len(matches), name)
            source = matches[0].read_text()
            self.assertIn(f'class {name} : WiredActivityTestCase()', source)
            self.assertNotIn('private lateinit var activity', source)
            self.assertNotIn('activity.finish()', source)
        for name in ('ImageAttachmentsUiTest', 'VideoAttachmentsUiTest'):
            source = next(p for p in sources if p.stem == name).read_text()
            self.assertIn('activity = recreateActivity(activity)', source)
