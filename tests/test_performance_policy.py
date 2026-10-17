"""Approval-policy checks only; never physical-device performance measurements."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ApprovedPerformancePolicyTest(unittest.TestCase):
    def test_explicit_approval_retains_all_original_limits_without_measurements(self):
        p = json.loads((ROOT / 'docs/acceptance/performance-policy-v1.json').read_text())
        self.assertEqual('approve_original_candidates', p['approval']['option_id'])
        self.assertEqual('explicit_user_choice', p['approval']['source'])
        self.assertEqual('question-e42f35b1-7b70-4b16-8655-05dcbada5a75', p['approval']['question_id'])
        self.assertEqual(dict(device_model='V2419A', os_version='15', sdk=35,
                              device_type='physical', build_type='release', refresh_rate_hz=60),
                         p['environment'])
        self.assertEqual(dict(conversations=1000, messages=10000, text_mb=10,
                              attachments=100, tool_steps=500, migration_package_mb=100),
                         p['dataset'])
        ac72 = p['AC-072']
        self.assertEqual(20, ac72['min_rounds_per_latency'])
        self.assertEqual(1500, ac72['cold_start_input_ready_p95_ms_max'])
        self.assertEqual(1000, ac72['long_conversation_tail_operable_ms_max'])
        self.assertEqual(100, ac72['input_feedback_p95_ms_max'])
        self.assertEqual(100, ac72['stop_feedback_p95_ms_max'])
        self.assertEqual(['scroll', 'streaming', 'ime'], ac72['frame_scenarios'])
        self.assertEqual(30, ac72['min_duration_seconds_per_frame_scenario'])
        self.assertEqual(0.05, ac72['frame_over_16_7_ms_ratio_max'])
        self.assertEqual(0.01, ac72['frame_over_50_ms_ratio_max'])
        self.assertEqual(700, ac72['frame_duration_ms_max'])
        self.assertEqual(0, ac72['anr_count_max'])
        ac73 = p['AC-073']
        self.assertEqual(100, ac73['conversation_switches'])
        self.assertEqual(20, ac73['memory_growth_mb_max'])
        self.assertEqual('non_positive_after_stable_reclamation', ac73['memory_growth_trend'])
        self.assertEqual(30, ac73['migration_seconds_max'])
        self.assertTrue(ac73['migration_progress_must_be_visible'])
        self.assertEqual(1, ac73['migration_integrity_ratio'])
        self.assertEqual(['off', 'on'], ac73['diagnostics_modes'])
        self.assertEqual('report_both_modes_and_difference_no_unapproved_threshold',
                         ac73['diagnostics_overhead'])
        self.assertEqual('not_run', p['measurement_status'])
        self.assertFalse(p['collection_authenticated'])
        self.assertFalse(p['business_acceptance'])


class Candidate120HzPerformancePolicyTest(unittest.TestCase):
    def test_candidate_tightens_only_smoothness_without_inheriting_approval(self):
        base = json.loads((ROOT / 'docs/acceptance/performance-policy-v1.json').read_text())
        candidate = json.loads((ROOT / 'docs/acceptance/performance-profile-120hz-candidate-v1.json').read_text())
        self.assertEqual({"schema_version", "profile_id", "status", "approval", "base_policy_id",
                          "inherit_constraints_only", "environment_overrides", "frame_budget_ms",
                          "AC-072_overrides", "measurement_status", "collection_authenticated",
                          "business_acceptance"}, set(candidate))
        self.assertEqual(1, candidate['schema_version'])
        self.assertEqual('vivo-v2419a-ac072-073-120hz-candidate-v1', candidate['profile_id'])
        self.assertEqual('candidate', candidate['status'])
        self.assertIsNone(candidate['approval'])
        self.assertEqual(base['policy_id'], candidate['base_policy_id'])
        self.assertIs(True, candidate['inherit_constraints_only'])
        self.assertEqual(dict(refresh_rate_hz=120), candidate['environment_overrides'])
        self.assertEqual(8.333, candidate['frame_budget_ms'])
        limits = candidate['AC-072_overrides']
        self.assertEqual(dict(input_feedback_p95_ms_max=50, stop_feedback_p95_ms_max=50,
                              frame_over_8_333_ms_ratio_max=0.05, frame_over_16_7_ms_ratio_max=0.01,
                              frame_over_50_ms_ratio_max=0.001, frame_duration_ms_max=100), limits)
        for key in set(limits) & set(base['AC-072']):
            self.assertLess(limits[key], base['AC-072'][key])
        self.assertEqual('not_run', candidate['measurement_status'])
        self.assertIs(False, candidate['collection_authenticated'])
        self.assertIs(False, candidate['business_acceptance'])
        self.assertEqual(60, base['environment']['refresh_rate_hz'])
        self.assertEqual('approve_original_candidates', base['approval']['option_id'])
