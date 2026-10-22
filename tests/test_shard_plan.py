"""Allocation estimates cannot alter discovered coverage or terminal result validation."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('shard_plan', ROOT / 'scripts/acceptance/shard_plan.py')
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


class ShardPlanTests(unittest.TestCase):
    def fixture(self, root, times):
        rows = {}
        for name, (count, milliseconds) in times.items():
            source = Path('app/src/androidTest') / (name + '.kt')
            (root / source).parent.mkdir(parents=True, exist_ok=True)
            (root / source).write_text(name)
            rows[name] = dict(tests=count, milliseconds=milliseconds, source_path=str(source),
                              source_sha256=hashlib.sha256(name.encode()).hexdigest())
        (root / plan.DURATIONS).parent.mkdir(parents=True, exist_ok=True)
        (root / plan.DURATIONS).write_text(json.dumps(dict(schema_version=1, classes=rows)))

    def test_missing_data_preserves_count_lpt_and_all_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            classes = {name: 1 for name in 'abcdefghi'}
            groups, receipt = plan.allocate(classes, 4, Path(directory))
            self.assertEqual([sorted(g) for g in groups], [list('aei'), list('bf'), list('cg'), list('dh')])
            self.assertEqual(receipt['strategy'], 'method_count_lpt')
            self.assertTrue(receipt['estimates_only'])
            self.assertFalse(receipt['formal_acceptance'])

    def test_real_duration_beats_equal_method_counts_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = dict.fromkeys('abcdefghi', 1)
            self.fixture(root, {n: (1, t) for n, t in zip(classes, (100, 100, 100, 100, 900, 100, 100, 100, 100))})
            groups, receipt = plan.allocate(classes, 4, root)
            self.assertEqual(receipt['estimated_shard_ms'], [900, 300, 300, 200])
            self.assertEqual(plan.allocate(dict(reversed(list(classes.items()))), 4, root), (groups, receipt))
            self.assertEqual(sum(map(len, groups)), len(classes))
            self.assertEqual(set().union(*groups), set(classes))
            self.assertEqual(sum(sum(g.values()) for g in groups), 9)
            # Count LPT put a/e/i together: 1100ms; weighted LPT isolates e: 900ms.
            self.assertLess(max(receipt['estimated_shard_ms']), 1100)

    def test_partial_data_and_source_or_count_change_fall_back_without_omission(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'a': (1, 200), 'b': (2, 400), 'c': (1, 300)})
            (root / 'app/src/androidTest/c.kt').write_text('edited')
            groups, receipt = plan.allocate({'a': 1, 'b': 3, 'c': 1, 'new': 2}, 2, root)
            self.assertEqual(receipt['measured_classes'], ['a'])
            self.assertEqual(receipt['fallback_classes'], ['b', 'c', 'new'])
            self.assertEqual(receipt['fallback_ms_per_test'], 200)
            self.assertEqual(sum(map(len, groups)), 4)
            self.assertEqual(sum(sum(g.values()) for g in groups), 7)
            self.assertEqual(sum(receipt['estimated_shard_ms']), 1400)

    def test_invalid_data_is_error_not_a_success_or_silent_empty_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for count, milliseconds in ((True, 1), (1, -1), (1, 0), (1, 1.5)):
                self.fixture(root, {'a': (count, milliseconds)})
                with self.assertRaises(ValueError):
                    plan.allocate({'a': 1}, 1, root)
            self.fixture(root, {'a': (1, 100)})
            path = root / plan.DURATIONS
            value = json.loads(path.read_text())
            value['classes']['a']['source_path'] = '../escape.kt'
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                plan.allocate({'a': 1}, 1, root)

    def test_shared_receipt_does_not_depend_on_api_or_remove_filtered_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'a': (2, 900), 'b': (1, 300)})
            groups, receipt = plan.allocate({'a': 2, 'b': 1, 'c': 4, 'd': 1}, 4, root)
            self.assertEqual(len(groups), 4)
            self.assertTrue(all(groups))
            self.assertEqual(sum(sum(g.values()) for g in groups), 8)
            self.assertEqual(len(receipt['duration_data_sha256']), 64)


if __name__ == '__main__':
    unittest.main()
