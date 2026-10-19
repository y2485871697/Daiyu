#!/usr/bin/env python3
"""Summarize twenty isolated cold-process captures, not pure app startup or AC acceptance."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys

from evidence import InvalidEvidence, bounded_read, require, _unique_object
from performance_instrumentation import COLD, validate, MAX_TEXT_BYTES, ResultError
from performance_summary import CaptureParser, finite_float, integer
from performance import nearest_rank_p95


MAX_JSON_BYTES = 16 * 1024


def read_json(path):
    raw = bounded_read(path, MAX_JSON_BYTES)
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object,
                           parse_float=finite_float,
                           parse_constant=lambda _: (_ for _ in ()).throw(InvalidEvidence('invalid_json_number')))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise InvalidEvidence('invalid_cold_start_json') from None
    return value, hashlib.sha256(raw).hexdigest()


def timestamp(value):
    require(integer(value, minimum=1) <= 2**63 - 1, 'invalid_timestamp')
    return value


def summarize(directory, expected_owner):
    require(isinstance(expected_owner, str) and re.fullmatch('[0-9a-f]{32}', expected_owner), 'invalid_owner')
    directory = Path(directory)
    owner, owner_digest = read_json(directory / 'owner.json')
    require(isinstance(owner, dict), 'invalid_owner_record')
    require(owner.get('schema') == 'daiyu.synthetic.cold-start.owner.v1'
            and owner.get('owner_token') == expected_owner and owner.get('state') == 'prepared'
            and owner.get('application_id') == 'app.daiyu.android.synthetic'
            and owner.get('database_path') == '/data/user/0/app.daiyu.android.synthetic/databases/conversations.db'
            and owner.get('production_delivery') is False, 'owner_identity_mismatch')
    dataset = owner.get('dataset_sha256')
    require(isinstance(dataset, str) and re.fullmatch('[0-9a-f]{64}', dataset), 'invalid_dataset_digest')
    prepared = timestamp(owner.get('prepared_process_start_ns'))
    require({p.name for p in directory.glob('sample-*.json')} ==
            {f'sample-{i}.json' for i in range(20)}, 'sample_inventory_mismatch')
    observations, sources = [], []
    previous_draw, previous_process = prepared, prepared
    for i in range(20):
        sample, digest = read_json(directory / f'sample-{i}.json')
        require(isinstance(sample, dict), 'invalid_sample')
        require(sample.get('schema') == 'daiyu.synthetic.cold-start.sample.v1'
                and sample.get('owner_token') == expected_owner
                and type(sample.get('round')) is int and sample['round'] == i
                and sample.get('dataset_sha256') == dataset
                and sample.get('clock') == 'elapsed_realtime_boottime', 'sample_identity_mismatch')
        integer(sample.get('pid'), minimum=1)
        process = timestamp(sample.get('process_start_elapsed_ns'))
        start = timestamp(sample.get('start_elapsed_ns'))
        enter = timestamp(sample.get('test_enter_elapsed_ns'))
        ready = timestamp(sample.get('input_available_verified_ns'))
        draw = timestamp(sample.get('next_draw_ns'))
        require(previous_process < process <= enter and process >= start - 10_000_000,
                'fresh_process_required')
        require(previous_draw < start <= enter <= ready <= draw
                and enter - start < 30_000_000_000, 'invalid_elapsed_order')
        for field, value in [('input_ready_upper_bound_ns', ready - start),
                             ('next_draw_upper_bound_ns', draw - start)]:
            require(integer(sample.get(field)) == value, 'latency_arithmetic_mismatch')
        require(sample.get('latency_kind') == 'instrumented_upper_bound', 'unsupported_latency_kind')
        for field in ('includes_instrumentation_startup', 'collection_completed', 'activity_destroyed',
                      'cleanup_succeeded', 'dataset_integrity_verified', 'timed_endpoint_and_dataset_verified'):
            require(sample.get(field) is True, 'incomplete_cold_start_sample')
        for field in ('present_latency', 'hardware_input_latency', 'production_delivery',
                      'business_acceptance', 'dataset_deletion_attempted'):
            require(sample.get(field) is False, 'unsupported_sample_scope')
        require('failure_class' in sample and sample['failure_class'] is None, 'failed_cold_start_sample')
        exit_code, exit_digest = read_json(directory / f'exit-{i}.json')
        raw = bounded_read(directory / f'instrumentation-{i}.txt', MAX_TEXT_BYTES)
        receipt = validate(raw, COLD, 'testCollectOneColdStart', exit_code=exit_code,
                           owner=expected_owner, round_number=i)
        sources.append({'round': i, 'sample_sha256': digest, 'exit_sha256': exit_digest,
                        'instrumentation_sha256': receipt['raw_sha256']})
        observations.append({'round': i, 'pid': sample['pid'], 'process_start_elapsed_ns': process,
                             'start_elapsed_ns': start, 'next_draw_ns': draw,
                             'input_ready_upper_bound_ns': ready - start, 'next_draw_upper_bound_ns': draw - start})
        previous_draw, previous_process = draw, process
    metrics = []
    for field in ('input_ready_upper_bound_ns', 'next_draw_upper_bound_ns'):
        values = [row[field] for row in observations]
        metrics.append({'metric': field, 'count': len(values), 'samples_ns': values,
                        'min_ns': min(values), 'max_ns': max(values), 'p95_ns': nearest_rank_p95(values),
                        'p95_ms': nearest_rank_p95(values) / 1_000_000, 'percentile_method': 'nearest_rank'})
    return {'valid': True, 'schema': 'daiyu.synthetic.cold-start.summary.v1',
            'owner_token': expected_owner, 'owner_sha256': owner_digest, 'dataset_sha256': dataset,
            'sources': sources, 'observations': observations, 'metrics': metrics,
            'includes_instrumentation_startup': True, 'latency_kind': 'instrumented_upper_bound',
            'present_latency': False, 'hardware_input_latency': False, 'production_delivery': False,
            'measurement_ready': False, 'collection_authenticated': False, 'business_acceptance': False,
            'limitations': ['Includes instrumentation startup, assertions and scheduling; not pure app launch latency.',
                            'Next draw is a requested OnDraw callback, not first frame presentation.',
                            'Owner and dataset are capture declarations; manifest contents are not verified here.',
                            'Fresh process timestamps do not independently prove force-stop or cold page caches.',
                            'Boot identity and external elapsed origin are not independently authenticated.',
                            'Cleanup verifies Activity destruction, not dataset deletion or device restoration.']}


def main(argv=None):
    parser = CaptureParser(description=__doc__)
    try:
        parser.add_argument('--directory', type=Path, required=True)
        parser.add_argument('--owner', required=True)
        args = parser.parse_args(argv)
        result = summarize(args.directory, args.owner)
    except (InvalidEvidence, ResultError, OSError, ValueError, TypeError, OverflowError):
        print(json.dumps({'valid': False, 'error': 'invalid_cold_start_capture', 'business_acceptance': False}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
