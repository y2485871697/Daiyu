#!/usr/bin/env python3
"""Validate one isolated performance method without weakening the smoke protocol."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import uuid

from instrumentation_result import MAX_TEXT_BYTES, ResultError, instrumentation, report_text, require

COLD = 'app.daiyu.android.PersistentColdStartPerformanceTest'
SYNTHETIC = 'app.daiyu.android.ReleaseSyntheticPerformanceTest'
METHODS = {
    (COLD, 'testPreparePersistentColdStartDataset'): {'cold_start_owner'},
    (COLD, 'testVerifyPersistentColdStartDataset'): {'cold_start_dataset_retained'},
    (COLD, 'testCollectOneColdStart'): {'cold_start_sample'},
    (SYNTHETIC, 'testCollectRawFramesAndSyntheticInputUpperBounds'):
        {'synthetic_performance_status', 'synthetic_performance_report', 'business_acceptance'},
    (SYNTHETIC, 'testCollectFullScaleScrollInputAndBackendMigration'):
        {'synthetic_performance_status', 'synthetic_performance_report', 'business_acceptance'},
}
TELEMETRY = set().union(*METHODS.values())
EXTERNAL = '/storage/emulated/0/Android/data/app.daiyu.android.synthetic/files/synthetic-performance'
OWNER_FILE = '/data/user/0/app.daiyu.android.synthetic/no_backup/synthetic-cold-start-v1/owner.json'


def validate(raw, class_name, method, *, exit_code, owner=None, round_number=None,
             external=EXTERNAL, owner_file=OWNER_FILE):
    require(exit_code == 0 and type(exit_code) is int, 'performance_command_failed')
    require(len(raw) <= MAX_TEXT_BYTES, 'file_too_large')
    text = report_text(raw)
    expected = METHODS.get((class_name, method))
    require(expected is not None, 'unsupported_performance_method')
    require(external == EXTERNAL and owner_file == OWNER_FILE, 'unsupported_device_storage_context')
    if class_name == COLD:
        require(isinstance(owner, str) and re.fullmatch('[0-9a-f]{32}', owner), 'invalid_owner')
    if method == 'testCollectOneColdStart':
        require(type(round_number) is int and 0 <= round_number <= 19, 'invalid_round')
    filtered, pending, telemetry = [], [], {}
    started, finished = False, False
    for line in text.split('\n'):
        if line.startswith('INSTRUMENTATION_STATUS: ') or pending:
            pending.append(line)
            if not line.startswith('INSTRUMENTATION_STATUS_CODE:'):
                continue
            fields = {}
            for entry in pending[:-1]:
                if entry.startswith('INSTRUMENTATION_STATUS: '):
                    key, separator, value = entry[len('INSTRUMENTATION_STATUS: '):].partition('=')
                    require(separator and key not in fields, 'duplicate_or_invalid_status_field')
                    fields[key] = value
            custom = set(fields) & TELEMETRY
            if custom:
                require(started and not finished, 'telemetry_outside_method')
                require(set(fields) <= expected and custom == set(fields), 'mixed_or_unexpected_telemetry')
                require(line == 'INSTRUMENTATION_STATUS_CODE: 0', 'telemetry_status_conflict')
                require(all(entry.startswith('INSTRUMENTATION_STATUS: ') or not entry
                            for entry in pending[:-1]), 'invalid_telemetry_frame')
                require(not set(telemetry) & set(fields), 'duplicate_telemetry')
                telemetry.update(fields)
            else:
                require(fields.get('id') == 'AndroidJUnitRunner'
                        and fields.get('class') == class_name and fields.get('test') == method
                        and fields.get('current') == '1' and fields.get('numtests') == '1',
                        'performance_method_identity_mismatch')
                if line == 'INSTRUMENTATION_STATUS_CODE: 1':
                    require(not started and not finished, 'duplicate_method_start')
                    started = True
                else:
                    require(line == 'INSTRUMENTATION_STATUS_CODE: 0' and started and not finished,
                            'performance_method_completion_conflict')
                    finished = True
                filtered.extend(pending)
            pending = []
        else:
            require(not line.startswith('INSTRUMENTATION_STATUS_CODE:'), 'orphan_status_code')
            filtered.append(line)
    require(not pending and started and finished, 'incomplete_performance_method')
    require(set(telemetry) == expected, 'missing_performance_telemetry')
    for key, value in telemetry.items():
        require(value and not any(char in value for char in '\t\r\n='), 'invalid_telemetry_value')
        if key == 'cold_start_owner':
            require(value == owner_file, 'owner_path_mismatch')
        elif key == 'cold_start_dataset_retained':
            require(value == 'true', 'dataset_not_retained')
        elif key == 'cold_start_sample':
            require(value == f'{external}/cold-start-{owner}/sample-{round_number}.json',
                    'sample_path_mismatch')
        elif key == 'business_acceptance':
            require(value == 'false', 'collection_not_business_acceptance')
    if class_name == SYNTHETIC:
        status = re.fullmatch(re.escape(external) + r'/status-([0-9a-f-]{36})\.json',
                              telemetry['synthetic_performance_status'])
        require(status is not None, 'status_path_mismatch')
        try:
            report_id = uuid.UUID(status[1])
        except ValueError:
            raise ResultError('invalid_report_id') from None
        require(str(report_id) == status[1] and report_id.version == 4, 'invalid_report_id')
        require(telemetry['synthetic_performance_report'] == f'{external}/raw-{report_id}.jsonl',
                'raw_report_path_mismatch')
    instrumentation('\n'.join(filtered).encode('utf-8'), 1)
    return {'valid': True, 'tests_passed': 1, 'class': class_name, 'method': method,
            'raw_sha256': hashlib.sha256(raw).hexdigest(), 'telemetry': telemetry,
            'collection_files_verified': False, 'business_acceptance': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--class-name', required=True)
    parser.add_argument('--method', required=True)
    parser.add_argument('--exit-code', required=True, type=int)
    parser.add_argument('--owner')
    parser.add_argument('--round', dest='round_number', type=int)
    args = parser.parse_args(argv)
    try:
        with args.output.open('rb') as stream:
            raw = stream.read(MAX_TEXT_BYTES + 1)
        result = validate(raw, args.class_name, args.method, exit_code=args.exit_code,
                          owner=args.owner, round_number=args.round_number)
    except (ResultError, OSError) as error:
        print(json.dumps({'valid': False, 'error': str(error)}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
