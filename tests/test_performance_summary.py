"""Offline synthetic fixtures: no device measurements or acceptance claims."""
import copy
import importlib.util
import json
import os
import contextlib
import io
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
    evidence = load('summary_evidence_api', 'evidence.py')
    with patch.dict(sys.modules, {'evidence': evidence}):
        performance = load('summary_performance_api', 'performance.py')
        with patch.dict(sys.modules, {'performance': performance}):
            return load('summary_api', 'performance_summary.py')


summary = load_summary()


def capture():
    env = {'fixture_mode': 'full_scale', 'scroll_window_includes_driver_idle_time': True, 'collection_id': 'fixture', 'diagnostics_mode': 'off', 'diagnostics_state_verified': True,
           'collection_completed': True, 'cleanup_succeeded': True,
           'scroll_start_ns': 0, 'scroll_end_ns': 30_000_000_000}
    rows = [{'type': 'metadata', 'schema': summary.SCHEMA, 'clock': 'System.nanoTime',
             'framework_dropped_reports': 0, 'collector_overflow_count': 0, 'frame_count': 6,
             'metric_names': ['total_duration_ns', 'intended_vsync_timestamp_ns'], 'environment': env}]
    for i, (vsync, duration) in enumerate([(0, 16_700_000), (29_999_999_999, 16_700_001),
                                          (30_000_000_000, 50_000_000), (59_999_999_999, 50_000_001),
                                          (60_000_000_000, 700_000_000), (89_999_999_999, 1)]):
        rows.append({'type': 'frame', 'index': i, 'framework_drop_count': 0,
                     'callback_ns': 999_000_000_000, 'scenario_hint': 'wrong', 'metrics': [duration, vsync]})
    for i, scenario in enumerate(['synthetic_streaming', 'synthetic_input_connection'], 1):
        rows.append({'type': 'scenario_window', 'scenario': scenario, 'completed': True,
                     'start_ns': i * 30_000_000_000, 'end_ns': (i + 1) * 30_000_000_000,
                     'frame_count_by_intended_vsync': 2})
    methods = {'input_sample': 'semantics_text_replacement_to_verified_text_next_draw',
               'stop_sample': 'controller_stop_request_to_verified_idle_next_draw',
               'long_entry_sample': 'select_to_visible_tail_action_and_enabled_editor_next_draw'}
    for kind, method in methods.items():
        for i in range(20):
            rows.append({'type': kind, 'round': i, 'method': method, 'latency_kind': 'instrumented_upper_bound',
                         'start_ns': 100, 'semantics_verified_ns': 100, 'tail_operable_verified_ns': 100,
                         'next_draw_ns': 100 + (i + 1) * 1_000_000, 'upper_bound_ns': (i + 1) * 1_000_000,
                         'first_visible_feedback_measured': False, 'entry_kind': 'warm_reentry',
                         'prewarmed_long_selection_count': 1, 'tail_action_execution_verified': False,
                         'navigation_gesture_measured': False})
    return rows


def ime_capture():
    rows = capture()
    rows[0]['frame_count'] += 1
    rows.insert(7, {'type': 'frame', 'index': 6, 'framework_drop_count': 0,
                   'metrics': [16_000_000, 90_000_000_001]})
    rows.append({'type': 'scenario_window', 'scenario': 'system_ime_user_input', 'completed': True,
        'start_ns': 90_000_000_000, 'end_ns': 125_000_000_000, 'frame_count_by_intended_vsync': 1,
        'method': 'selected_system_ime_user_input_to_observed_editor_changes',
        'input_source': 'user_via_selected_system_ime', 'ime_id': 'test.keyboard/.Ime',
        'last_observed_ime_id': 'test.keyboard/.Ime', 'workload_id': 'manual-nihao-v1',
        'text_changes': 20, 'poll_interval_ms': 50, 'polling_overhead_included': True,
        'target_window_only': True, 'hardware_input_latency': False, 'ime_commit_latency': False,
        'first_visible_feedback_measured': False, 'present_latency': False, 'system_ime_change_observed': False,
        'observations': [{'observed_ns': 90_000_000_000 + i * 50_000_000, 'text_utf16_length': i + 1,
                          'selection': 'TextRange(1, 1)', 'text_changed': True} for i in range(20)]})
    return rows


def memory(slope=0):
    phases = [('before_switches', 0)] + [('switch_completed', i) for i in range(1, 101)] + [('post_gc_observation', i) for i in range(1, 11)]
    return {'schema': 'daiyu.synthetic.memory.raw.v1', 'collection_id': 'fixture', 'diagnostics_mode': 'off',
            'clock': 'System.nanoTime', 'collection_completed': True, 'stable_recovery_verified': False, 'peak_memory_measured': False,
            'samples': [{'phase': phase, 'ordinal': n, 'sample_start_ns': (i + 1) * 1_000_000_000,
                         'sample_end_ns': (i + 1) * 1_000_000_000 + 1,
                         'total_pss_kib': 1000 + slope * i} for i, (phase, n) in enumerate(phases)]}


class SummaryTests(unittest.TestCase):
    def migration_capture(self):
        rows = capture()
        rows[0]['environment'].update(dataset_sha256='a' * 64, migration_package_sha256='b' * 64,
            migration_package_bytes=100_000_000, mb_definition='decimal_1000000_bytes')
        sample = dict(type='backend_migration_sample', collection_id='fixture', clock='System.nanoTime',
            start_ns=100, commit_returned_ns=2_000_100, duration_ns=2_000_000, bytes_read=100_000_000,
            dataset_sha256='a' * 64, package_sha256='b' * 64, database_reopened=True,
            integrity_verified=True, ui_progress_measured=False, saf_measured=False,
            business_acceptance=False, collection_authenticated=False)
        return rows, sample

    def test_optional_backend_migration_preserves_existing_summary(self):
        rows, sample = self.migration_capture()
        before = summary.summarize(rows, memory())
        after = summary.summarize(rows, memory(), sample)
        migration = after.pop('backend_migration')
        self.assertEqual(before, after)
        self.assertEqual(migration['duration_ms'], 2.0)
        self.assertEqual(migration['bytes_read'], 100_000_000)
        self.assertFalse(migration['integrity_verification_in_duration'])
        self.assertFalse(migration['ui_progress_measured'])
        self.assertFalse(migration['saf_measured'])
        self.assertFalse(migration['business_acceptance'])

    def test_backend_migration_rejects_identity_timing_scope_and_types(self):
        for field, value in [('collection_id', 'other'), ('collection_id', True), ('clock', 'wall'),
            ('start_ns', True), ('commit_returned_ns', 99), ('duration_ns', 1), ('duration_ns', True),
            ('bytes_read', True), ('bytes_read', 99_999_999), ('dataset_sha256', 'c' * 64),
            ('package_sha256', 'B' * 64), ('database_reopened', False), ('integrity_verified', 1),
            ('ui_progress_measured', True), ('saf_measured', 0), ('business_acceptance', True),
            ('collection_authenticated', True)]:
            rows, sample = self.migration_capture(); sample[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(summary.InvalidEvidence):
                summary.summarize(rows, backend_migration_report=sample)
        for field in ('dataset_sha256', 'migration_package_sha256', 'migration_package_bytes', 'mb_definition'):
            rows, sample = self.migration_capture(); del rows[0]['environment'][field]
            with self.subTest(missing=field), self.assertRaises(summary.InvalidEvidence):
                summary.summarize(rows, backend_migration_report=sample)

    @unittest.skipUnless(hasattr(os, 'O_NOFOLLOW') and os.open in os.supports_dir_fd, 'safe POSIX unavailable')
    def test_cli_optional_backend_migration_and_fixed_failure(self):
        with tempfile.TemporaryDirectory() as root:
            rows, sample = self.migration_capture()
            raw = Path(root) / 'raw.jsonl'; migration = Path(root) / 'migration.json'
            raw.write_text('\n'.join(json.dumps(r) for r in rows)); migration.write_text(json.dumps(sample))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = summary.main(['--raw', str(raw), '--backend-migration', str(migration)])
            self.assertEqual(code, 0)
            report = json.loads(output.getvalue())
            self.assertEqual(report['backend_migration_sha256'], summary.hashlib.sha256(migration.read_bytes()).hexdigest())
            self.assertEqual(report['backend_migration']['duration_ms'], 2.0)
            for content in ('null', '{"type":1,"type":2}', json.dumps(dict(sample, collection_id='other'))):
                migration.write_text(content); output = io.StringIO(); errors = io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    code = summary.main(['--raw', str(raw), '--backend-migration', str(migration)])
                self.assertEqual(code, 2); self.assertEqual(errors.getvalue(), '')
                self.assertEqual(json.loads(output.getvalue()), {'valid': False,
                    'error': 'invalid_performance_capture', 'business_acceptance': False})


    def test_optional_system_ime_window_is_distinct_and_honest(self):
        result = summary.frames(ime_capture())
        self.assertEqual(len(result), 4)
        self.assertEqual(result[-1]['scenario'], 'system_ime_user_input')
        self.assertEqual(result[-1]['frame_count'], 1)
        self.assertFalse(result[-1]['ime_commit_latency'])
        self.assertTrue(result[-1]['polling_overhead_included'])
        self.assertEqual(len(summary.frames(capture())), 3)

    def test_system_ime_rejects_scope_identity_timing_counts_and_text_payload(self):
        invalid = [('completed', False), ('method', 'direct_connection'), ('input_source', 'semantics'),
            ('workload_id', 'anything'), ('last_observed_ime_id', 'other/.Ime'), ('ime_id', True),
            ('system_ime_change_observed', True), ('text_changes', True), ('text_changes', 19),
            ('poll_interval_ms', True), ('end_ns', 124_999_999_999), ('frame_count_by_intended_vsync', 2),
            ('present_latency', True), ('target_window_only', False), ('polling_overhead_included', False)]
        for key, value in invalid:
            rows = ime_capture(); rows[-1][key] = value
            with self.subTest(key=key), self.assertRaises(summary.InvalidEvidence): summary.frames(rows)
        for update in ({'observed_ns': 1}, {'observed_ns': 126_000_000_000}, {'text_changed': 1},
                       {'text_utf16_length': 4097}, {'text': 'private'}, {'selection': []}):
            rows = ime_capture(); rows[-1]['observations'][3].update(update)
            with self.subTest(update=update), self.assertRaises(summary.InvalidEvidence): summary.frames(rows)
        rows = ime_capture(); rows[-1]['observations'][-1]['text_utf16_length'] = 0
        with self.assertRaises(summary.InvalidEvidence): summary.frames(rows)
        rows = ime_capture(); rows.append(copy.deepcopy(rows[-1]))
        with self.assertRaises(summary.InvalidEvidence): summary.frames(rows)

    def test_system_ime_poll_spacing_and_inferable_text_changes(self):
        for mutate in (
            lambda observations: observations[1].update(observed_ns=observations[0]['observed_ns']),
            lambda observations: observations[1].update(observed_ns=observations[0]['observed_ns'] + 49_999_999),
            lambda observations: observations[0].update(text_utf16_length=0),
            lambda observations: observations[1].update(text_changed=False),
        ):
            rows = ime_capture(); mutate(rows[-1]['observations'])
            with self.assertRaises(summary.InvalidEvidence): summary.frames(rows)
        rows = ime_capture()
        # Same-length replacement can change text; never require length growth as proof.
        for observation in rows[-1]['observations']: observation['text_utf16_length'] = 1
        self.assertEqual(summary.frames(rows)[-1]['text_changes'], 20)

    def test_loading_isolated_from_cooperation_module_names_and_preserves_cache(self):
        import types
        foreign_evidence = types.ModuleType('evidence')
        foreign_performance = types.ModuleType('performance')
        before_path = list(sys.path)
        with patch.dict(sys.modules, {'evidence': foreign_evidence, 'performance': foreign_performance}):
            isolated = load_summary()
            self.assertFalse(isolated.summarize(capture())['business_acceptance'])
            self.assertIs(sys.modules['evidence'], foreign_evidence)
            self.assertIs(sys.modules['performance'], foreign_performance)
        self.assertEqual(sys.path, before_path)

    def test_frame_exact_thresholds_and_intended_vsync_not_callback(self):
        frames = summary.frames(capture())
        self.assertEqual(frames[0]['over_16_7_ms_ratio'], .5)
        self.assertEqual(frames[0]['over_50_ms_ratio'], 0)
        self.assertEqual(frames[1]['over_50_ms_ratio'], .5)
        self.assertEqual(frames[2]['max_duration_ms'], 700)
        self.assertTrue(all(f['frame_count'] == 2 for f in frames))

    def test_metric_order_is_discovered(self):
        rows = capture()
        rows[0]['metric_names'].reverse()
        for row in rows:
            if row['type'] == 'frame': row['metrics'].reverse()
        self.assertEqual(summary.frames(rows), summary.frames(capture()))

    def test_p95_rounds_and_honest_scope(self):
        report = summary.summarize(capture(), memory(-1))
        self.assertFalse(report['measurement_ready'])
        self.assertFalse(report['business_acceptance'])
        self.assertFalse(report['collection_authenticated'])
        self.assertFalse(report['memory']['stable_recovery_verified'])
        for row in report['latencies']:
            self.assertEqual(row['count'], 20)
            self.assertEqual(row['p95_ms'], 19)
            self.assertFalse(row['formal_feedback_endpoint_verified'])
        self.assertEqual(report['latencies'][-1]['entry_kind'], 'warm_reentry')
        self.assertTrue(report['frames'][0]['window_includes_driver_idle_time'])

    def test_missing_duplicate_and_bool_rounds_rejected(self):
        for change in ('missing', 'extra', 'duplicate', 'bool'):
            rows = capture()
            selected = [r for r in rows if r['type'] == 'input_sample']
            if change == 'missing': rows.remove(selected[-1])
            elif change == 'extra': rows.append(copy.deepcopy(selected[-1]))
            elif change == 'duplicate': selected[-1]['round'] = 18
            else: selected[0]['round'] = False
            with self.subTest(change=change), self.assertRaises(summary.InvalidEvidence):
                summary.latency(rows, 'input_sample')

    def test_timestamps_method_and_endpoint_tampering_rejected(self):
        for field, value in [('upper_bound_ns', 0), ('semantics_verified_ns', 2_000_000),
                             ('method', 'first_visible_feedback'), ('first_visible_feedback_measured', True)]:
            rows = capture()
            selected = next(r for r in rows if r['type'] == 'stop_sample')
            selected[field] = value
            with self.subTest(field=field), self.assertRaises(summary.InvalidEvidence):
                summary.latency(rows, 'stop_sample')

    def test_incomplete_frames_rejected(self):
        for field, value in [('frame_count', 5), ('framework_dropped_reports', 1),
                             ('collector_overflow_count', 1), ('clock', 'wall')]:
            rows = capture(); rows[0][field] = value
            with self.subTest(field=field), self.assertRaises(summary.InvalidEvidence): summary.frames(rows)
        for field, value in [('index', True), ('framework_drop_count', 1), ('metrics', [-1, 0])]:
            rows = capture(); rows[1][field] = value
            with self.subTest(field=field), self.assertRaises(summary.InvalidEvidence): summary.frames(rows)

    def test_window_duration_counts_and_completion_rejected(self):
        for field, value in [('end_ns', 31_000_000_000), ('frame_count_by_intended_vsync', 1), ('completed', False)]:
            rows = capture(); selected = next(r for r in rows if r['type'] == 'scenario_window'); selected[field] = value
            with self.subTest(field=field), self.assertRaises(summary.InvalidEvidence): summary.frames(rows)

    def test_memory_units_and_slope(self):
        for slope in (-1, 0, 1):
            result = summary.memory(memory(slope), 'fixture', 'off')
            self.assertAlmostEqual(result['observed_ols_slope_mb_per_second'], slope * .001024)
            self.assertAlmostEqual(result['last_minus_before_switches_mb'], slope * 110 * .001024)
            self.assertFalse(result['stable_recovery_verified'])
            self.assertFalse(result['peak_memory_measured'])

    def test_memory_identity_order_and_false_stability_rejected(self):
        for field, value in [('collection_id', 'other'), ('diagnostics_mode', 'on'),
                             ('stable_recovery_verified', True), ('collection_completed', False)]:
            report = memory(); report[field] = value
            with self.subTest(field=field), self.assertRaises(summary.InvalidEvidence): summary.memory(report, 'fixture', 'off')
        report = memory(); report['samples'][2]['ordinal'] = 1
        with self.assertRaises(summary.InvalidEvidence): summary.memory(report, 'fixture', 'off')
        report = memory(); report['samples'][2]['sample_start_ns'] = 1
        with self.assertRaises(summary.InvalidEvidence): summary.memory(report, 'fixture', 'off')

    @unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                         "safe POSIX file operations unavailable")
    def test_bounded_strict_raw_reader(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'raw.jsonl'
            path.write_text('\n'.join(json.dumps(r) for r in capture()))
            rows, digest = summary.read_raw(path)
            self.assertEqual(len(digest), 64)
            self.assertEqual(summary.frames(rows), summary.frames(capture()))
            valid_extension = '\n'.join(json.dumps(r) for r in capture())
            valid_extension = valid_extension.replace('"frame_count": 6', '"frame_count": 6, "ignored_extension": 1e999', 1)
            path.write_text(valid_extension)
            with self.assertRaises(summary.InvalidEvidence): summary.read_raw(path)
            for raw in ['{"type":"frame","type":"metadata"}', '{"x":NaN}', '{}\n\n{}', '[]']:
                path.write_text(raw)
                with self.subTest(raw=raw), self.assertRaises(summary.InvalidEvidence): summary.read_raw(path)


    def test_collection_identity_is_required_and_typed(self):
        for value in (None, '', True, 1, [], {}, 'a/b', 'x' * 129):
            rows = capture(); rows[0]['environment']['collection_id'] = value
            with self.subTest(value=value), self.assertRaises(summary.InvalidEvidence):
                summary.summarize(rows)
            report = memory(); report['collection_id'] = value
            with self.subTest(memory=value), self.assertRaises(summary.InvalidEvidence):
                summary.memory(report, value, 'off')
        rows = capture(); del rows[0]['environment']['collection_id']
        with self.assertRaises(summary.InvalidEvidence): summary.summarize(rows)

    def test_reused_or_reversed_scenario_windows_rejected(self):
        for start, end in [(0, 30_000_000_000), (29_000_000_000, 60_000_000_000)]:
            rows = capture(); row = next(r for r in rows if r['type'] == 'scenario_window')
            row.update(start_ns=start, end_ns=end)
            with self.subTest(start=start), self.assertRaises(summary.InvalidEvidence): summary.frames(rows)

    @unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                         "safe POSIX file operations unavailable")
    def test_cli_success_optional_and_valid_memory(self):
        with tempfile.TemporaryDirectory() as root:
            raw = Path(root) / 'raw.jsonl'; mem = Path(root) / 'memory.json'
            raw.write_text('\n'.join(json.dumps(r) for r in capture()))
            mem.write_text(json.dumps(memory()))
            for extra in ([], ['--memory', str(mem)]):
                output, errors = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    code = summary.main(['--raw', str(raw)] + extra)
                self.assertEqual(code, 0); self.assertEqual(errors.getvalue(), '')
                report = json.loads(output.getvalue())
                self.assertEqual('memory' in report, bool(extra))
                self.assertEqual('memory_sha256' in report, bool(extra))
                self.assertEqual(len(report['raw_sha256']), 64)

    def test_cli_invalid_inputs_have_fixed_private_failure(self):
        with tempfile.TemporaryDirectory(prefix='private-path-') as root:
            raw = Path(root) / 'raw.jsonl'; mem = Path(root) / 'memory.json'
            raw.write_text('\n'.join(json.dumps(r) for r in capture()))
            mem.write_text('null')
            cases = [[], ['--raw'], ['--private-argument'], ['--raw', str(raw), '--memory', str(mem)],
                     ['--raw', str(raw.parent / 'missing')]]
            for args in cases:
                output, errors = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    code = summary.main(args)
                self.assertEqual(code, 2); self.assertEqual(errors.getvalue(), '')
                self.assertEqual(json.loads(output.getvalue()), {'valid': False,
                    'error': 'invalid_performance_capture', 'business_acceptance': False})
            for content in ('private bad JSON', '{}', '{"x":1e999}'):
                raw.write_text(content)
                output = io.StringIO()
                with contextlib.redirect_stdout(output): code = summary.main(['--raw', str(raw)])
                self.assertEqual(code, 2); self.assertNotIn('private', output.getvalue())


    def test_producer_scope_cannot_be_silently_upgraded(self):
        for field, value in [('entry_kind', 'first_entry'), ('prewarmed_long_selection_count', True),
                             ('tail_action_execution_verified', True), ('navigation_gesture_measured', True)]:
            rows = capture(); row = next(r for r in rows if r['type'] == 'long_entry_sample'); row[field] = value
            with self.subTest(field=field), self.assertRaises(summary.InvalidEvidence):
                summary.latency(rows, 'long_entry_sample')
        report = memory(); report['peak_memory_measured'] = True
        with self.assertRaises(summary.InvalidEvidence): summary.memory(report, 'fixture', 'off')
        rows = capture(); rows[0]['environment']['scroll_window_includes_driver_idle_time'] = False
        with self.assertRaises(summary.InvalidEvidence): summary.frames(rows)

    @unittest.skipUnless(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd,
                         "safe POSIX file operations unavailable")
    def test_small_smoke_has_explicit_unsupported_mode(self):
        rows = capture(); rows[0]['environment']['fixture_mode'] = 'small_smoke'
        with tempfile.TemporaryDirectory() as root:
            raw = Path(root) / 'raw.jsonl'; raw.write_text('\n'.join(json.dumps(r) for r in rows))
            output = io.StringIO()
            with contextlib.redirect_stdout(output): code = summary.main(['--raw', str(raw)])
            self.assertEqual(code, 2)
            self.assertEqual(json.loads(output.getvalue())['error'], 'unsupported_fixture_mode')


if __name__ == '__main__': unittest.main()
