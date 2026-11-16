"""Keep the motion regression exact without weakening source or class validation."""
import json
from pathlib import Path
import tempfile
import unittest
from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as discovery


class DoubaoMotionScopeTest(unittest.TestCase):
    def test_contract_scope_requires_exact_classes_and_smoke_mode(self):
        classes = recorder.DOUBAO_MOTION_PLATFORM_CLASSES
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("doubao-motion-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.DOUBAO_MOTION_MODULES, "-v"], command)
            self.assertNotIn("discover", command)
        for raw in (",".join(classes[:-1]), ",".join((*classes, classes[0])), ",".join((*classes, "app.daiyu.android.OtherTest"))):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=raw))[1])
        self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="release", SMOKE_TEST_CLASSES=",".join(classes)))[1])

    def fixture(self, root, classes=None):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for i, name in enumerate(classes or recorder.DOUBAO_MOTION_PLATFORM_CLASSES):
            package, _, short = name.rpartition(".")
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + "\nclass " + short + " : TestCase() {\n" +
                            "".join(" fun testCase" + str(n) + "() {}\n" for n in range(i + 1)) + "}\n")
        return helper

    def test_exact_discovery_ignores_unrelated_ambiguity_and_covers_each_class_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); helper = self.fixture(root)
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(recorder.DOUBAO_MOTION_PLATFORM_CLASSES))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: i + 1 for i, name in enumerate(recorder.DOUBAO_MOTION_PLATFORM_CLASSES)}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
                self.assertEqual(sum(inventory["all_classes"][name] for name in selection["selected_classes"]), selection["expected_tests"])
            self.assertCountEqual(recorder.DOUBAO_MOTION_PLATFORM_CLASSES, selected)

    def test_requested_missing_or_ambiguous_source_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); helper = self.fixture(root)
            name = recorder.DOUBAO_MOTION_PLATFORM_CLASSES[0]
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.write_text(path.read_text() + "class UnexpectedTest : TestCase() {}\n")
            result = helper.select(root, classes=",".join(recorder.DOUBAO_MOTION_PLATFORM_CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            path.unlink()
            result = helper.select(root, classes=",".join(recorder.DOUBAO_MOTION_PLATFORM_CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_reasoning_extension_is_exact_and_discovers_all_four_classes(self):
        classes = recorder.DOUBAO_MOTION_REASONING_PLATFORM_CLASSES
        self.assertEqual(4, len(classes))
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("doubao-motion-targeted", scope)
            self.assertNotIn("discover", command)
        for selected in (classes[1:], (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); helper = self.fixture(root, classes)
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: i + 1 for i, name in enumerate(classes)}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(classes, selected)
            source = root / "app/src/androidTest/kotlin/app/daiyu/android/ControllerReasoningFreezeTest.kt"
            source.unlink()
            self.assertNotEqual(0, helper.select(root, classes=",".join(classes)).returncode)

    def test_floating_extension_requires_all_six_classes_and_discovers_each_once(self):
        classes = recorder.DOUBAO_MOTION_FLOATING_PLATFORM_CLASSES
        self.assertEqual(6, len(classes))
        self.assertEqual("app.daiyu.android.VoiceCallFloatingWindowTest", classes[0])
        self.assertEqual(recorder.DOUBAO_MOTION_VOICE_PLATFORM_CLASSES, classes[1:])
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("doubao-motion-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.DOUBAO_MOTION_MODULES, "-v"], command)
            self.assertNotIn("discover", command)
        # Removing the floating class is the intentionally supported legacy five-class scope.
        invalid = [classes[:i] + classes[i + 1:] for i in range(1, len(classes))]
        invalid += [(*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")]
        for selected in invalid:
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="release", SMOKE_TEST_CLASSES=",".join(classes)))[1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); helper = self.fixture(root, classes)
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: i + 1 for i, name in enumerate(classes)}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(classes, selected)
            source = root / "app/src/androidTest/kotlin/app/daiyu/android/VoiceCallFloatingWindowTest.kt"
            source.unlink()
            self.assertNotEqual(0, helper.select(root, classes=",".join(classes)).returncode)

    def test_floating_scope_is_shared_by_workflow_and_native_discovery(self):
        root = Path(__file__).resolve().parents[1]
        full = ",".join(recorder.DOUBAO_MOTION_FLOATING_PLATFORM_CLASSES)
        workflow = (root / ".github/workflows/doubao-call-motion-checks.yml").read_text()
        self.assertIn("  SMOKE_TEST_CLASSES: " + full, workflow)
        self.assertIn("run: python3 .motion-validation-tools/scripts/acceptance/record_tool_tests.py --python python3", workflow)
        self.assertNotIn('GITHUB_SHA="$EXPECTED_SHA" python3 scripts/acceptance/record_tool_tests.py', workflow.split("  layout:", 1)[1])
        script = (root / "scripts/smoke_boot.sh").read_text()
        self.assertIn('doubao_motion_floating_target = doubao_motion_voice_target | {"app.daiyu.android.VoiceCallFloatingWindowTest"}', script)
        self.assertIn("sorted(doubao_motion_floating_target)", script)
        setup = script.split('  if test "$api" = 26 && test "${SMOKE_MODE:-}" = smoke &&', 1)[1].split('  instrumentation_options=()', 1)[0]
        self.assertIn('test "${SMOKE_TEST_CLASSES:-}" = "' + full + '"', setup)
