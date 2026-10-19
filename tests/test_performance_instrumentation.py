from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/acceptance'))
import performance_instrumentation as p
from instrumentation_result import ResultError, instrumentation

OWNER = 'a' * 32
ID = '3528cba0-00b4-4554-bd1e-71610ef61e2a'


def runner(cls, method, code):
    fields = {'id': 'AndroidJUnitRunner', 'class': cls, 'test': method,
              'current': '1', 'numtests': '1', 'stream': '.' if code == 0 else ''}
    return ''.join(f'INSTRUMENTATION_STATUS: {k}={v}\n' for k, v in fields.items()) + f'INSTRUMENTATION_STATUS_CODE: {code}\n'


def transcript(cls, method, fields):
    telemetry = ''.join(f'INSTRUMENTATION_STATUS: {k}={v}\n' for k, v in fields.items()) + 'INSTRUMENTATION_STATUS_CODE: 0\n'
    return (runner(cls, method, 1) + telemetry + runner(cls, method, 0)
            + 'INSTRUMENTATION_RESULT: stream=\n\nTime: 0.5\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n').encode()


class PerformanceInstrumentationTests(unittest.TestCase):
    def cold(self):
        return transcript(p.COLD, 'testCollectOneColdStart',
                          {'cold_start_sample': f'{p.EXTERNAL}/cold-start-{OWNER}/sample-0.json'})

    def validate_cold(self, raw, **kwargs):
        return p.validate(raw, p.COLD, 'testCollectOneColdStart', exit_code=0,
                          owner=OWNER, round_number=0, **kwargs)

    def test_all_five_methods_and_unchanged_smoke_gate(self):
        cases = [
            (p.COLD, 'testPreparePersistentColdStartDataset', {'cold_start_owner': p.OWNER_FILE}),
            (p.COLD, 'testVerifyPersistentColdStartDataset', {'cold_start_dataset_retained': 'true'}),
            (p.COLD, 'testCollectOneColdStart', {'cold_start_sample': f'{p.EXTERNAL}/cold-start-{OWNER}/sample-0.json'}),
        ]
        for method in ('testCollectRawFramesAndSyntheticInputUpperBounds', 'testCollectFullScaleScrollInputAndBackendMigration'):
            cases.append((p.SYNTHETIC, method, {'synthetic_performance_status': f'{p.EXTERNAL}/status-{ID}.json',
                          'synthetic_performance_report': f'{p.EXTERNAL}/raw-{ID}.jsonl', 'business_acceptance': 'false'}))
        for cls, method, fields in cases:
            with self.subTest(method=method):
                raw = transcript(cls, method, fields)
                result = p.validate(raw, cls, method, exit_code=0, owner=OWNER, round_number=0)
                self.assertTrue(result['valid'])
                self.assertFalse(result['collection_files_verified'])
                self.assertFalse(result['business_acceptance'])
                with self.assertRaises(ResultError): instrumentation(raw, 1)

    def test_wrong_owner_round_and_storage_rejected(self):
        for before, after in [(OWNER, 'b' * 32), ('sample-0', 'sample-00'), ('sample-0', 'sample-1'),
                              ('app.daiyu.android.synthetic/files', 'app.daiyu.android/files'),
                              ('/sample-0', '/../sample-0')]:
            with self.subTest(after=after), self.assertRaises(ResultError):
                self.validate_cold(self.cold().replace(before.encode(), after.encode()))

    def test_unknown_mixed_duplicate_and_missing_telemetry_rejected(self):
        raw = self.cold()
        for replacement in [b'INSTRUMENTATION_STATUS: unknown=x\n',
                            b'INSTRUMENTATION_STATUS: class='+p.COLD.encode()+b'\n',
                            b'INSTRUMENTATION_STATUS: cold_start_sample=x\n']:
            with self.assertRaises(ResultError):
                self.validate_cold(raw.replace(b'INSTRUMENTATION_STATUS: cold_start_sample=', replacement+b'INSTRUMENTATION_STATUS: cold_start_sample='))
        with self.assertRaises(ResultError):
            self.validate_cold(transcript(p.COLD, 'testCollectOneColdStart', {}))

    def test_no_identity_only_summary_and_wrong_method_rejected(self):
        with self.assertRaises(ResultError):
            self.validate_cold(b'OK (1 test)\nINSTRUMENTATION_CODE: -1\n')
        with self.assertRaises(ResultError):
            self.validate_cold(self.cold().replace(b'testCollectOneColdStart', b'testVerifyPersistentColdStartDataset'))

    def test_failure_in_raw_is_not_removed_by_telemetry_filter(self):
        raw = self.cold()
        with self.assertRaisesRegex(ResultError, '^negative_report_signal$'):
            self.validate_cold(raw.replace(b'INSTRUMENTATION_RESULT: stream=',
                                          b'java.lang.AssertionError: bad\nINSTRUMENTATION_RESULT: stream='))
        telemetry = (f'INSTRUMENTATION_STATUS: cold_start_sample={p.EXTERNAL}/cold-start-{OWNER}/sample-0.json\n'
                     'INSTRUMENTATION_STATUS_CODE: 0\n').encode()
        with self.assertRaisesRegex(ResultError, '^telemetry_status_conflict$'):
            self.validate_cold(raw.replace(telemetry, telemetry.replace(b'STATUS_CODE: 0', b'STATUS_CODE: -2')))
        completion = runner(p.COLD, 'testCollectOneColdStart', 0).encode()
        with self.assertRaisesRegex(ResultError, '^performance_method_completion_conflict$'):
            self.validate_cold(raw.replace(completion, completion.replace(b'STATUS_CODE: 0', b'STATUS_CODE: -2')))
        with self.assertRaisesRegex(ResultError, '^instrumentation_terminal_conflict$'):
            self.validate_cold(raw.replace(b'INSTRUMENTATION_CODE: -1', b'INSTRUMENTATION_CODE: 0'))
        with self.assertRaisesRegex(ResultError, '^performance_command_failed$'):
            p.validate(raw, p.COLD, 'testCollectOneColdStart', exit_code=1, owner=OWNER, round_number=0)

    def test_prepare_transcript_does_not_claim_owner_file_verification(self):
        raw = transcript(p.COLD, 'testPreparePersistentColdStartDataset', {'cold_start_owner': p.OWNER_FILE})
        for owner in (OWNER, 'b' * 32):
            result = p.validate(raw, p.COLD, 'testPreparePersistentColdStartDataset', exit_code=0, owner=owner)
            self.assertFalse(result['collection_files_verified'])
            self.assertFalse(result['business_acceptance'])

    def test_telemetry_outside_method_and_extra_execution_rejected(self):
        raw = self.cold()
        telemetry = f'INSTRUMENTATION_STATUS: cold_start_sample={p.EXTERNAL}/cold-start-{OWNER}/sample-0.json\nINSTRUMENTATION_STATUS_CODE: 0\n'.encode()
        for bad in [telemetry + raw, raw + telemetry, raw.replace(telemetry, telemetry*2),
                    runner(p.COLD, 'testCollectOneColdStart', 1).encode()+raw]:
            with self.assertRaises(ResultError): self.validate_cold(bad)

    def test_missing_completion_and_duplicate_summary_rejected(self):
        raw = self.cold()
        for bad in [raw.replace(runner(p.COLD, 'testCollectOneColdStart', 0).encode(), b''),
                    raw.replace(b'OK (1 test)', b'OK (1 test)\nOK (1 test)')]:
            with self.assertRaises(ResultError): self.validate_cold(bad)

    def test_synthetic_uuid_pair_and_acceptance_rejected(self):
        method = 'testCollectFullScaleScrollInputAndBackendMigration'
        fields = {'synthetic_performance_status': f'{p.EXTERNAL}/status-{ID}.json',
                  'synthetic_performance_report': f'{p.EXTERNAL}/raw-{ID}.jsonl', 'business_acceptance': 'false'}
        for key, value in [('business_acceptance','true'), ('synthetic_performance_report',f'{p.EXTERNAL}/raw-other.jsonl'),
                           ('synthetic_performance_status',f'{p.EXTERNAL}/status-{ID.upper()}.json')]:
            with self.assertRaises(ResultError):
                p.validate(transcript(p.SYNTHETIC, method, dict(fields, **{key:value})), p.SYNTHETIC, method, exit_code=0)
