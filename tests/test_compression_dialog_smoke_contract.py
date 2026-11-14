"""压缩弹窗四类回归只发现目标源码，原包与冻结检查身份独立。"""
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TARGETS = tuple('app.daiyu.android.' + name for name in (
    'ContextManagementPanelTest', 'CompressionConversationDialogTest',
    'ContextManagementEntryActivityTest', 'ControllerContextManagementEntryTest',
))


class CompressionDialogInventoryTests(unittest.TestCase):
    def inventory(self, raw=None, *, mode='smoke', shard=0, shards=4,
                  omit=None, zero=None, ambiguous=None, duplicate=None, forbid_scan=False):
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        body = script.split("target_spec=$(python3 - <<'PYCOUNT'\n", 1)[1].split('\nPYCOUNT\n', 1)[0]
        if forbid_scan:
            body = ('from pathlib import Path\n'
                    'Path.rglob = lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("unrelated scan"))\n' + body)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'smoke').mkdir()
            expected = {}
            for index, name in enumerate(TARGETS):
                if name == omit:
                    continue
                path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
                path.parent.mkdir(parents=True, exist_ok=True)
                declared = duplicate[1] if duplicate and name == duplicate[0] else name
                count = 0 if name == zero else index + 1
                methods = ''.join('    fun testCase%d() {}\n' % n for n in range(count))
                text = 'package app.daiyu.android\nclass %s : BaseTest() {\n%s}\n' % (declared.rsplit('.', 1)[1], methods)
                if name == ambiguous:
                    text += 'class AnotherTest : BaseTest() {\n    fun testOther() {}\n}\n'
                path.write_text(text)
                expected[name] = count
            unrelated = root / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/DoubaoCallOverlayTest.kt'
            unrelated.parent.mkdir(parents=True, exist_ok=True)
            unrelated.write_text('package app.daiyu.android.ui.screens.chat.conversation\n'
                                 'class DoubaoCallOverlayTest : BaseTest() {\n fun testOne() {}\n}\n'
                                 'class AmbiguousOverlayTest : BaseTest() {\n fun testTwo() {}\n}\n')
            result = subprocess.run(
                [sys.executable, '-c', body], cwd=root, capture_output=True, text=True,
                env=dict(os.environ, SMOKE_MODE=mode,
                         SMOKE_TEST_CLASSES=','.join(TARGETS) if raw is None else raw,
                         SMOKE_API='all', SMOKE_SHARD_COUNT=str(shards),
                         SMOKE_SHARD_INDEX=str(shard), SMOKE_SCRIPT_DIR=str(ROOT / 'scripts')),
                timeout=15)
            inventory = root / 'smoke/instrumentation-shards.json'
            selection = root / 'smoke/selection.json'
            plan = root / 'smoke/shard-plan.json'
            return (result, json.loads(inventory.read_text()) if inventory.exists() else None,
                    json.loads(selection.read_text()) if selection.exists() else None,
                    json.loads(plan.read_text()) if plan.exists() else None, expected)

    def test_every_exact_order_reads_only_four_sources_without_rglob(self):
        for order in itertools.permutations(TARGETS):
            with self.subTest(order=order):
                result, inventory, selection, plan, expected = self.inventory(','.join(order), forbid_scan=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(inventory['discovery_scope'], 'targeted')
                self.assertEqual(inventory['all_classes'], expected)
                self.assertEqual(inventory['total_tests'], sum(expected.values()))
                self.assertEqual(selection['requested_classes'], sorted(TARGETS))
                self.assertEqual(selection['scope'], 'targeted')
                self.assertEqual(plan['allocation_scope'], 'targeted')
                self.assertEqual(plan['allocated_classes'], sorted(TARGETS))

    def test_four_shards_cover_each_class_exactly_once_and_keep_method_counts(self):
        seen = set()
        inventories = []
        total = 0
        for shard in range(4):
            result, inventory, selection, plan, expected = self.inventory(shard=shard, forbid_scan=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            selected = set(selection['selected_classes'])
            self.assertTrue(selected)
            self.assertFalse(selected & seen)
            seen.update(selected)
            self.assertEqual(selection['expected_tests'], sum(expected[name] for name in selected))
            total += selection['expected_tests']
            inventories.append(inventory['shards'])
        self.assertEqual(seen, set(TARGETS))
        self.assertEqual(total, 10)
        self.assertTrue(all(value == inventories[0] for value in inventories))

    def test_subsets_duplicates_and_extra_classes_do_not_claim_exact_scope(self):
        variants = [','.join(name for name in TARGETS if name != removed) for removed in TARGETS]
        variants += [','.join((*TARGETS, duplicate)) for duplicate in TARGETS]
        variants.append(','.join((*TARGETS, 'app.daiyu.android.ExtraTest')))
        for raw in variants:
            with self.subTest(raw=raw):
                result, inventory, *_ = self.inventory(raw)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr)
                self.assertIsNone(inventory)

    def test_full_and_candidate_keep_the_original_strict_discovery(self):
        for mode, raw in (('smoke', ''), ('candidate', ','.join(TARGETS))):
            with self.subTest(mode=mode):
                result, inventory, *_ = self.inventory(raw, mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr)
                self.assertIsNone(inventory)

    def test_missing_zero_ambiguous_and_duplicate_target_sources_still_fail(self):
        cases = [(dict(omit=TARGETS[0]), 'Unknown instrumentation test class'),
                 (dict(zero=TARGETS[0]), 'Unknown instrumentation test class'),
                 (dict(ambiguous=TARGETS[0]), 'Ambiguous instrumentation test declaration'),
                 (dict(duplicate=(TARGETS[0], TARGETS[1])), 'Duplicate instrumentation test class')]
        for arguments, message in cases:
            with self.subTest(arguments=arguments):
                result, inventory, *_ = self.inventory(**arguments, forbid_scan=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertIsNone(inventory)


class CompressionDialogWorkflowTests(unittest.TestCase):
    def test_frozen_sources_current_inventory_and_dual_api_receipts_are_wired(self):
        workflow = (ROOT / '.github/workflows/compression-dialog-checks.yml').read_text()
        for value in ("api: ['26', '37.2']", 'shard: [0, 1, 2, 3]',
                      'ref: ${{ inputs.expected_sha }}', 'ref: ${{ github.workflow_sha }}',
                      'GITHUB_SHA="$EXPECTED_SHA" python3 scripts/verify_smoke_source.py',
                      'source_sha=os.environ[\'SOURCE_SHA\']', 'event_sha=os.environ[\'GITHUB_SHA\']',
                      'workflow_sha=os.environ[\'WORKFLOW_SHA\']', 'checked_sha=os.environ[\'EXPECTED_SHA\']',
                      'apk_built=False', 'validation_inputs=inputs',
                      'sha256=hashlib.sha256(source.read_bytes()).hexdigest()',
                      'full_regression_passed=False', 'cd delivery && sha256sum -c SHA256SUMS',
                      'name: 压缩弹窗-API${{ matrix.api }}-分片${{ matrix.shard }}',
                      'printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"',
                      'PYTHONPATH: .validation-inputs/tests',
                      'if path == "scripts/smoke_boot.sh":'):
            self.assertIn(value, workflow)
        for name in TARGETS:
            self.assertIn(name, workflow)
        self.assertNotIn('test "$SOURCE_SHA" = "$EXPECTED_SHA"', workflow)
        self.assertNotIn('./gradlew', workflow)
        self.assertNotIn('assembleRelease', workflow)
        self.assertEqual(workflow.count('shutil.copyfile(source, path)'), 1)
        self.assertIn('daiyu-compression-dialog-${{ github.ref }}', workflow)


if __name__ == '__main__':
    unittest.main()
