#!/usr/bin/env python3
"""Derive full-scale synthetic raw observations; never grants AC acceptance."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
import sys

from evidence import (InvalidEvidence, MAX_ARTIFACT_BYTES, bounded_read, load_json,
                      require, _unique_object)
from performance import nearest_rank_p95

SCHEMA = 'daiyu.synthetic.performance.raw.v1'
MAX_LINES = 51000


class CaptureParser(argparse.ArgumentParser):
    def error(self, message):
        raise InvalidEvidence('invalid_arguments')


def collection_id(value):
    require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value) is not None,
            'invalid_collection_id')
    return value


def finite_float(value):
    from math import isfinite
    parsed = float(value)
    require(isfinite(parsed), 'invalid_json_number')
    return parsed


def integer(value, code='invalid_integer', minimum=0):
    require(type(value) is int and value >= minimum, code)
    return value


def read_raw(path):
    raw = bounded_read(Path(path), MAX_ARTIFACT_BYTES)
    lines = raw.splitlines()
    require(0 < len(lines) <= MAX_LINES, 'invalid_raw_line_count')
    try:
        rows = [json.loads(line.decode('utf-8'), object_pairs_hook=_unique_object, parse_float=finite_float,
                           parse_constant=lambda _: (_ for _ in ()).throw(InvalidEvidence('invalid_json_number')))
                for line in lines]
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise InvalidEvidence('invalid_raw_json') from None
    require(all(isinstance(row, dict) for row in rows), 'invalid_raw_row')
    return rows, hashlib.sha256(raw).hexdigest()


def latency(rows, kind):
    selected = [row for row in rows if row.get('type') == kind]
    require(len(selected) == 20, 'latency_round_count')
    require(all(type(row.get('round')) is int for row in selected), 'invalid_latency_round')
    require(sorted(row['round'] for row in selected) == list(range(20)), 'latency_round_identity')
    selected.sort(key=lambda row: row['round'])
    methods = {row.get('method') for row in selected}
    expected = {
        'input_sample': 'semantics_text_replacement_to_verified_text_next_draw',
        'stop_sample': 'controller_stop_request_to_verified_idle_next_draw',
        'long_entry_sample': 'select_to_visible_tail_action_and_enabled_editor_next_draw',
    }
    require(kind in expected and methods == {expected[kind]}, 'latency_method_mismatch')
    samples = []
    for row in selected:
        require(row.get('latency_kind') == 'instrumented_upper_bound', 'latency_endpoint_mismatch')
        start = integer(row.get('start_ns'))
        verified = integer(row.get('tail_operable_verified_ns' if kind == 'long_entry_sample' else 'semantics_verified_ns'))
        draw = integer(row.get('next_draw_ns'))
        upper = integer(row.get('upper_bound_ns'))
        require(start <= verified <= draw and upper == draw - start, 'latency_timestamp_mismatch')
        if kind == 'stop_sample':
            require(row.get('first_visible_feedback_measured') is False, 'unsupported_stop_endpoint')
        samples.append(upper / 1_000_000)
    scope = {}
    if kind == 'long_entry_sample':
        require(all(row.get('entry_kind') == 'warm_reentry'
                    and type(row.get('prewarmed_long_selection_count')) is int
                    and row['prewarmed_long_selection_count'] == 1
                    and row.get('tail_action_execution_verified') is False
                    and row.get('navigation_gesture_measured') is False for row in selected),
                'unsupported_long_entry_scope')
        scope = {'entry_kind': 'warm_reentry', 'prewarmed_long_selection_count': 1,
                 'tail_action_execution_verified': False, 'navigation_gesture_measured': False}
    return {**scope, 'metric': kind + '_upper_bound', 'count': 20, 'rounds': list(range(20)),
            'samples_ms': samples, 'min_ms': min(samples), 'max_ms': max(samples),
            'p95_ms': nearest_rank_p95(samples), 'method': expected[kind],
            'formal_feedback_endpoint_verified': False}


def system_ime_window(rows):
    selected = [row for row in rows if row.get('type') == 'scenario_window'
                and row.get('scenario') == 'system_ime_user_input']
    require(len(selected) <= 1, 'duplicate_system_ime_window')
    if not selected:
        return None
    row = selected[0]
    require(row.get('completed') is True and row.get('method') ==
            'selected_system_ime_user_input_to_observed_editor_changes'
            and row.get('input_source') == 'user_via_selected_system_ime'
            and row.get('workload_id') == 'manual-nihao-v1', 'invalid_system_ime_method')
    ime = row.get('ime_id')
    require(isinstance(ime, str) and 0 < len(ime) <= 256 and '/' in ime
            and row.get('last_observed_ime_id') == ime, 'system_ime_identity_mismatch')
    for field in ('polling_overhead_included', 'target_window_only'):
        require(row.get(field) is True, 'unsupported_system_ime_scope')
    for field in ('hardware_input_latency', 'ime_commit_latency', 'first_visible_feedback_measured',
                  'present_latency', 'system_ime_change_observed'):
        require(row.get(field) is False, 'unsupported_system_ime_scope')
    require(type(row.get('poll_interval_ms')) is int and row['poll_interval_ms'] == 50,
            'invalid_system_ime_poll_interval')
    start, end = integer(row.get('start_ns')), integer(row.get('end_ns'))
    require(end - start >= 35_000_000_000, 'system_ime_duration_insufficient')
    observed = row.get('observations')
    require(isinstance(observed, list) and 20 <= len(observed) <= 1000, 'invalid_system_ime_observations')
    previous, changes, previous_length = start, 0, 0
    for index, entry in enumerate(observed):
        require(isinstance(entry, dict) and set(entry) == {
            'observed_ns', 'text_utf16_length', 'selection', 'text_changed'}, 'invalid_system_ime_observation')
        timestamp = integer(entry.get('observed_ns'))
        require(previous <= timestamp <= end and (index == 0 or timestamp - previous >= 50_000_000),
                'invalid_system_ime_observation_time')
        previous = timestamp
        length = integer(entry.get('text_utf16_length'))
        require(length <= 4096, 'system_ime_text_limit')
        require(type(entry.get('text_changed')) is bool, 'invalid_system_ime_change')
        require((entry['text_changed'] or length == previous_length)
                and (not entry['text_changed'] or length != 0 or previous_length != 0),
                'inconsistent_system_ime_text_change')
        previous_length = length
        selection = entry.get('selection')
        require(selection is None or isinstance(selection, str) and len(selection) <= 100,
                'invalid_system_ime_selection')
        changes += entry['text_changed']
    require(type(row.get('text_changes')) is int and row['text_changes'] == changes and changes >= 20,
            'system_ime_change_count_mismatch')
    require(observed[-1]['text_utf16_length'] > 0, 'system_ime_final_text_missing')
    return row


def frames(rows):
    require(rows and rows[0].get('type') == 'metadata' and rows[0].get('schema') == SCHEMA, 'raw_schema_mismatch')
    meta = rows[0]
    require(sum(row.get('type') == 'metadata' for row in rows) == 1, 'duplicate_metadata')
    require(meta.get('clock') == 'System.nanoTime', 'clock_mismatch')
    require(meta.get('framework_dropped_reports') == 0 and type(meta.get('framework_dropped_reports')) is int
            and meta.get('collector_overflow_count') == 0 and type(meta.get('collector_overflow_count')) is int,
            'incomplete_frame_collection')
    names = meta.get('metric_names')
    require(isinstance(names, list) and all(isinstance(n, str) for n in names)
            and len(names) == len(set(names)), 'invalid_metric_names')
    require('intended_vsync_timestamp_ns' in names and 'total_duration_ns' in names, 'required_metric_missing')
    ti, di = names.index('intended_vsync_timestamp_ns'), names.index('total_duration_ns')
    captured = [row for row in rows[1:] if row.get('type') == 'frame']
    require(type(meta.get('frame_count')) is int and len(captured) == meta['frame_count'] and captured, 'frame_count_mismatch')
    values = []
    for index, row in enumerate(captured):
        require(type(row.get('index')) is int and row['index'] == index, 'frame_index_mismatch')
        require(type(row.get('framework_drop_count')) is int and row['framework_drop_count'] == 0, 'dropped_frame_report')
        metrics = row.get('metrics')
        require(isinstance(metrics, list) and len(metrics) == len(names), 'frame_metrics_mismatch')
        values.append((integer(metrics[ti], 'frame_timestamp_unavailable'), integer(metrics[di], 'frame_duration_unavailable')))
    env = meta.get('environment')
    require(isinstance(env, dict) and env.get('collection_completed') is True
            and env.get('cleanup_succeeded') is True, 'collection_not_complete')
    require(env.get('fixture_mode') == 'full_scale', 'unsupported_fixture_mode')
    require(env.get('scroll_window_includes_driver_idle_time') is True, 'unsupported_scroll_scope')
    collection_id(env.get('collection_id'))
    require(env.get('diagnostics_mode') in ('off', 'on') and env.get('diagnostics_state_verified') is True, 'diagnostics_unverified')
    windows = [('scroll', env.get('scroll_start_ns'), env.get('scroll_end_ns'), None)]
    for scenario in ('synthetic_streaming', 'synthetic_input_connection'):
        selected = [r for r in rows if r.get('type') == 'scenario_window' and r.get('scenario') == scenario]
        require(len(selected) == 1 and selected[0].get('completed') is True, 'scenario_window_missing')
        row = selected[0]
        windows.append((scenario, row.get('start_ns'), row.get('end_ns'), row.get('frame_count_by_intended_vsync')))
    system_ime = system_ime_window(rows)
    if system_ime is not None:
        windows.append(('system_ime_user_input', system_ime['start_ns'], system_ime['end_ns'],
                        system_ime.get('frame_count_by_intended_vsync')))
    result = []
    previous_end = -1
    for scenario, start, end, count in windows:
        start, end = integer(start), integer(end)
        require(start >= previous_end, 'overlapping_or_unordered_windows')
        previous_end = end
        require(end - start >= 30_000_000_000, 'scenario_duration_insufficient')
        durations = [duration for vsync, duration in values if start <= vsync < end]
        require(durations, 'scenario_frames_missing')
        if scenario != 'scroll':
            require(type(count) is int and count == len(durations), 'scenario_frame_count_mismatch')
        scope = {'window_includes_driver_idle_time': True} if scenario == 'scroll' else {}
        if scenario == 'system_ime_user_input':
            scope = {key: system_ime[key] for key in ('input_source', 'ime_id', 'workload_id',
                     'text_changes', 'polling_overhead_included', 'target_window_only', 'ime_commit_latency',
                     'hardware_input_latency', 'present_latency', 'first_visible_feedback_measured')}
        result.append({**scope, 'scenario': scenario, 'start_ns': start, 'end_ns': end, 'frame_count': len(durations),
                       'over_16_7_ms_ratio': sum(n > 16_700_000 for n in durations) / len(durations),
                       'over_50_ms_ratio': sum(n > 50_000_000 for n in durations) / len(durations),
                       'max_duration_ms': max(durations) / 1_000_000})
    return result


def memory(report, expected_collection_id, diagnostics):
    require(isinstance(report, dict) and report.get('schema') == 'daiyu.synthetic.memory.raw.v1', 'memory_schema_mismatch')
    collection_id(expected_collection_id)
    collection_id(report.get('collection_id'))
    require(report['collection_id'] == expected_collection_id and report.get('diagnostics_mode') == diagnostics, 'memory_identity_mismatch')
    require(report.get('clock') == 'System.nanoTime' and report.get('collection_completed') is True, 'memory_incomplete')
    require(report.get('peak_memory_measured') is False, 'unsupported_peak_memory_claim')
    require(report.get('stable_recovery_verified') is False, 'unsupported_stable_recovery_claim')
    rows = report.get('samples')
    require(isinstance(rows, list) and len(rows) == 111 and all(isinstance(r, dict) for r in rows), 'memory_sample_count')
    expected = [('before_switches', 0)] + [('switch_completed', i) for i in range(1, 101)] + [('post_gc_observation', i) for i in range(1, 11)]
    require([(r.get('phase'), r.get('ordinal')) for r in rows] == expected
            and all(type(r.get('ordinal')) is int for r in rows), 'memory_sample_identity')
    previous = -1
    for row in rows:
        start, end = integer(row.get('sample_start_ns')), integer(row.get('sample_end_ns'))
        require(previous < start <= end, 'memory_clock_order')
        previous = end
        integer(row.get('total_pss_kib'))
    observations = rows[-10:]
    xs = [(r['sample_start_ns'] - observations[0]['sample_start_ns']) / 1e9 for r in observations]
    ys = [r['total_pss_kib'] * 1024 / 1_000_000 for r in observations]
    xm, ym = sum(xs) / 10, sum(ys) / 10
    slope = sum((x - xm) * (y - ym) for x, y in zip(xs, ys)) / sum((x - xm) ** 2 for x in xs)
    return {'metric': 'observed_pss_after_gc_request', 'unit': 'decimal_MB', 'samples_mb': ys,
            'last_minus_before_switches_mb': ys[-1] - rows[0]['total_pss_kib'] * 1024 / 1_000_000,
            'observed_ols_slope_mb_per_second': slope, 'stable_recovery_verified': False,
            'peak_memory_measured': False}


def summarize(rows, memory_report=None):
    frame_summary = frames(rows)
    env = rows[0]['environment']
    result = {'schema_version': 1, 'frames': frame_summary,
              'fixture_mode': 'full_scale', 'collection_id': env['collection_id'], 'diagnostics_mode': env['diagnostics_mode'],
              'latencies': [latency(rows, k) for k in ('input_sample', 'stop_sample', 'long_entry_sample')],
              'measurement_ready': False, 'collection_authenticated': False, 'business_acceptance': False,
              'limitations': ['system_ime_round_trip_not_measured', 'first_visible_stop_feedback_not_measured',
                              'stable_memory_recovery_not_verified', 'external_baseline_pin_required',
                              'build_and_physical_device_identity_require_independent_binding']}
    if memory_report is not None:
        result['memory'] = memory(memory_report, env.get('collection_id'), env['diagnostics_mode'])
    return result


def main(argv=None):
    parser = CaptureParser(description=__doc__)
    parser.add_argument('--raw', required=True, type=Path)
    parser.add_argument('--memory', type=Path)
    try:
        args = parser.parse_args(argv)
        rows, digest = read_raw(args.raw)
        mem, mem_digest = load_json(args.memory) if args.memory else (None, None)
        if args.memory:
            require(isinstance(mem, dict), 'memory_schema_mismatch')
        report = summarize(rows, mem)
        report['raw_sha256'] = digest
        if mem_digest: report['memory_sha256'] = mem_digest
        print(json.dumps(report, ensure_ascii=False, allow_nan=False))
        return 0
    except (InvalidEvidence, OSError, ValueError, TypeError, KeyError, OverflowError) as problem:
        error = 'unsupported_fixture_mode' if isinstance(problem, InvalidEvidence) and str(problem) == 'unsupported_fixture_mode' else 'invalid_performance_capture'
        print(json.dumps({'valid': False, 'error': error, 'business_acceptance': False}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
