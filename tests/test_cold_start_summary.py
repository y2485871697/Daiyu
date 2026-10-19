"""Offline synthetic captures only; no device collection or AC acceptance."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1] / 'scripts' / 'acceptance'


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_summary():
    evidence = load('cold_evidence_api', 'evidence.py')
    with patch.dict(sys.modules, {'evidence': evidence}):
        performance = load('cold_performance_api', 'performance.py')
        with patch.dict(sys.modules, {'performance': performance}):
            raw_summary = load('cold_raw_summary_api', 'performance_summary.py')
            with patch.dict(sys.modules, {'performance_summary': raw_summary}), \
                    patch.object(sys, 'path', [str(ROOT), *sys.path]):
                return load('cold_summary_api', 'cold_start_summary.py')


summary = load_summary()
OWNER = 'a' * 32
DATASET = 'b' * 64


def transcript(i):
    def frame(code, **fields):
        return ''.join(f'INSTRUMENTATION_STATUS: {k}={v}\n' for k, v in fields.items()) + f'INSTRUMENTATION_STATUS_CODE: {code}\n'
    fields = dict(id='AndroidJUnitRunner', **{'class': summary.COLD}, test='testCollectOneColdStart', current='1', numtests='1', stream='.')
    return (frame(1, **fields) + frame(0, cold_start_sample=f'/storage/emulated/0/Android/data/app.daiyu.android.synthetic/files/synthetic-performance/cold-start-{OWNER}/sample-{i}.json') +
            frame(0, **fields) + 'INSTRUMENTATION_RESULT: stream=\n\nTime: 0.5\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n')


@unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                     "safe POSIX file operations unavailable")
class ColdStartSummaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.write('owner.json', {'schema': 'daiyu.synthetic.cold-start.owner.v1', 'owner_token': OWNER,
                                 'state': 'prepared', 'application_id': 'app.daiyu.android.synthetic',
                                 'database_path': '/data/user/0/app.daiyu.android.synthetic/databases/conversations.db',
                                 'production_delivery': False, 'dataset_sha256': DATASET,
                                 'prepared_process_start_ns': 1})
        for i in range(20):
            start = (i + 1) * 1_000_000_000
            latency = (i + 1) * 1_000_000
            self.write(f'sample-{i}.json', {
                'schema': 'daiyu.synthetic.cold-start.sample.v1', 'owner_token': OWNER, 'round': i,
                'dataset_sha256': DATASET, 'clock': 'elapsed_realtime_boottime', 'pid': 42,
                'process_start_elapsed_ns': start - 9_000_000, 'start_elapsed_ns': start,
                'test_enter_elapsed_ns': start, 'input_available_verified_ns': start + latency,
                'next_draw_ns': start + latency + 1, 'input_ready_upper_bound_ns': latency,
                'next_draw_upper_bound_ns': latency + 1, 'latency_kind': 'instrumented_upper_bound',
                'includes_instrumentation_startup': True, 'collection_completed': True,
                'activity_destroyed': True, 'cleanup_succeeded': True, 'dataset_integrity_verified': True,
                'timed_endpoint_and_dataset_verified': True, 'present_latency': False,
                'hardware_input_latency': False, 'production_delivery': False, 'business_acceptance': False,
                'dataset_deletion_attempted': False, 'failure_class': None})
            self.write(f'exit-{i}.json', 0)
            (self.root / f'instrumentation-{i}.txt').write_text(transcript(i))

    def write(self, name, value):
        (self.root / name).write_text(json.dumps(value))

    def mutate(self, name, **updates):
        value = json.loads((self.root / name).read_text())
        value.update(updates)
        self.write(name, value)

    def verify(self):
        return summary.summarize(self.root, OWNER)

    def test_exact_p95_identity_and_honest_scope_pid_reuse_allowed(self):
        result = self.verify()
        self.assertTrue(result['valid'])
        self.assertEqual(result['metrics'][0]['p95_ms'], 19)
        self.assertEqual(result['metrics'][1]['p95_ns'], 19_000_001)
        self.assertEqual(len(result['sources']), 20)
        for field in ('production_delivery', 'business_acceptance', 'collection_authenticated', 'measurement_ready', 'present_latency', 'hardware_input_latency'):
            self.assertFalse(result[field])
        self.assertTrue(result['includes_instrumentation_startup'])
        self.assertEqual(result['latency_kind'], 'instrumented_upper_bound')

    def test_missing_extra_wrong_round_bool_and_owner_rejected(self):
        for field, value in [('round', True), ('round', 1), ('owner_token', 'c' * 32), ('dataset_sha256', 'c' * 64)]:
            with self.subTest(field=field):
                path = self.root / 'sample-0.json'; original = path.read_bytes()
                self.mutate(path.name, **{field: value})
                with self.assertRaises(summary.InvalidEvidence): self.verify()
                path.write_bytes(original)
        self.write('sample-20.json', {})
        with self.assertRaises(summary.InvalidEvidence): self.verify()
        (self.root / 'sample-20.json').unlink()
        (self.root / 'sample-19.json').unlink()
        with self.assertRaises(summary.InvalidEvidence): self.verify()

    def test_fresh_process_timing_and_arithmetic_rejected(self):
        for field, value in [('process_start_elapsed_ns', 1), ('process_start_elapsed_ns', 2_000_000_000),
                             ('start_elapsed_ns', True), ('test_enter_elapsed_ns', 31_000_000_000),
                             ('input_available_verified_ns', 999_999_999), ('next_draw_ns', 999_999_999),
                             ('input_ready_upper_bound_ns', True), ('next_draw_upper_bound_ns', 9), ('clock', 'System.nanoTime')]:
            path = self.root / 'sample-0.json'; original = path.read_bytes()
            with self.subTest(field=field):
                self.mutate(path.name, **{field: value})
                with self.assertRaises(summary.InvalidEvidence): self.verify()
            path.write_bytes(original)
        self.mutate('sample-1.json', process_start_elapsed_ns=991_000_000)
        with self.assertRaises(summary.InvalidEvidence): self.verify()

    def test_failed_cleanup_scope_and_missing_failure_field_rejected(self):
        for field, value in [('collection_completed', False), ('cleanup_succeeded', False),
                             ('dataset_integrity_verified', False), ('failure_class', 'IOException'),
                             ('present_latency', True), ('production_delivery', True),
                             ('dataset_deletion_attempted', True), ('includes_instrumentation_startup', False)]:
            path = self.root / 'sample-0.json'; original = path.read_bytes()
            with self.subTest(field=field):
                self.mutate(path.name, **{field: value})
                with self.assertRaises(summary.InvalidEvidence): self.verify()
            path.write_bytes(original)
        value = json.loads(path.read_text()); del value['failure_class']; self.write(path.name, value)
        with self.assertRaises(summary.InvalidEvidence): self.verify()

    def test_original_runner_transcript_and_exact_exit_required(self):
        for value in (True, 1, None, {}, '0'):
            self.write('exit-0.json', value)
            with self.subTest(value=value), self.assertRaises((summary.ResultError, summary.InvalidEvidence)):
                self.verify()
        self.write('exit-0.json', 0)
        (self.root / 'instrumentation-0.txt').write_text(transcript(1))
        with self.assertRaises(summary.ResultError): self.verify()

    def test_json_duplicate_nonfinite_size_and_symlink_rejected(self):
        path = self.root / 'sample-0.json'; original = path.read_bytes()
        for content in ('{"round":0,"round":0}', '{"x":1e999}', '{"x":NaN}', ' ' * (summary.MAX_JSON_BYTES + 1), '\ufffd'):
            with self.subTest(content=content[:20]):
                path.write_text(content)
                with self.assertRaises(summary.InvalidEvidence): self.verify()
        path.write_bytes(original)
        real = self.root / 'retained.json'; path.rename(real); path.symlink_to(real)
        with self.assertRaises(OSError): self.verify()

    def test_cli_success_and_failure_json_do_not_leak_path(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(summary.main(['--directory', str(self.root), '--owner', OWNER]), 0)
        self.assertTrue(json.loads(output.getvalue())['valid'])
        for args in (['--directory', '/private/missing', '--owner', OWNER], [], ['--directory', str(self.root), '--owner', '../private']):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(summary.main(args), 2)
            self.assertEqual(json.loads(output.getvalue()), {'valid': False, 'error': 'invalid_cold_start_capture', 'business_acceptance': False})


if __name__ == '__main__':
    unittest.main()
