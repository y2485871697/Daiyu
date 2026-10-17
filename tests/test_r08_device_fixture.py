"""Source/packaging contracts only; these do not execute or certify root task delivery."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
NAME = 'app.daiyu.android.vd.R08TaskDeliveryHelperActivity'


class R08DeviceFixtureTests(unittest.TestCase):
    def test_helper_belongs_only_to_android_test_apk(self):
        manifest = ET.parse(ROOT / 'app/src/androidTest/AndroidManifest.xml').getroot()
        helpers = [x for x in manifest.findall('application/activity') if x.get(ANDROID + 'name') == NAME]
        self.assertEqual(1, len(helpers))
        helper = helpers[0]
        self.assertEqual('true', helper.get(ANDROID + 'exported'))
        self.assertEqual('standard', helper.get(ANDROID + 'launchMode'))
        self.assertEqual('', helper.get(ANDROID + 'taskAffinity'))
        self.assertEqual('false', helper.get(ANDROID + 'allowTaskReparenting'))
        self.assertEqual('true', helper.get(ANDROID + 'resizeableActivity'))
        self.assertEqual([], helper.findall('intent-filter'))
        for source in ('main', 'debug', 'release', 'performanceTest'):
            for path in (ROOT / 'app/src' / source).rglob('AndroidManifest.xml'):
                self.assertNotIn(NAME, path.read_text())
        self.assertIn('setRoot("src/performanceTest")', (ROOT / 'app/build.gradle.kts').read_text())

    def test_opt_in_and_exact_isolated_identity_precede_real_owner(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/vd/R08RealTaskDeliveryBackendTest.kt').read_text()
        gate = source.index('getString("r08TaskDeliveryOptIn") != "true") return')
        identity = source.index('assertEquals("app.daiyu.android.synthetic", target.packageName)')
        host = source.index('val host = AndroidVirtualDisplayOwnerHost(target)')
        self.assertLess(gate, identity)
        self.assertLess(identity, host)
        self.assertIn('private fun verifyDelivery()', source)
        self.assertIn('"previous_r08_delivery_unresolved_no_new_owner"', source)
        for forbidden in ('shutdownUninitialized(', '.reconnect(', 'forceStop', 'Host {', 'assumeTrue('):
            self.assertNotIn(forbidden, source)
        self.assertIn('assertEquals(selected, ids(handedOff, "keptTaskIds"))', source)
        self.assertIn('assertEquals(original.ownerPid, drained.ownerPid)', source)
        self.assertIn('assertTrue(drained.stopped && drained.ownerExitObserved)', source)
        self.assertIn('assertEquals(0, drained.launcherExit ?: -1)', source)

    def test_helper_keeps_real_owner_marker_and_survives_display_zero(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/vd/R08TaskDeliveryHelperActivity.kt').read_text()
        self.assertIn('marker?.scheme != "daiyu-vd"', source)
        self.assertIn('marker.host != "session"', source)
        self.assertNotIn('setData(', source)
        self.assertNotIn('displayId', source)
        self.assertNotIn('startActivity(', source)


if __name__ == '__main__':
    unittest.main()
