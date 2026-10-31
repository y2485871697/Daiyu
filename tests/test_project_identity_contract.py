import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('project_identity', ROOT / 'scripts/verify_project_identity.py')
IDENTITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IDENTITY)


class ProjectIdentityContractTest(unittest.TestCase):
    def test_current_source_and_archive_members_have_only_current_project_identities(self):
        self.assertGreater(IDENTITY.verify_tree(ROOT), 0)

    def test_checker_catches_historical_words_packages_and_camel_identifiers(self):
        name = IDENTITY.PREVIOUS_NAME
        for value in [name, name.upper(), name.title(), '.' + name + '-reference',
                      name + 'Clone', 'remember' + name.title() + 'MenuState',
                      IDENTITY.PREVIOUS_PACKAGE, 'archives/' + name + '-native-build.tgz']:
            self.assertTrue(IDENTITY.forbidden(value), value)

    def test_standard_technical_names_and_third_party_names_are_not_rebranded(self):
        for value in ['metadata.user_id', 'META-INF/LICENSE', 'ensureMetaViewport',
                      'getaddrinfo', 'com.android.chrome.beta', 'OpenMinis', 'SherpaNcnn', 'DaiyuFormTextField']:
            self.assertFalse(IDENTITY.forbidden(value), value)

    def test_browser_provenance_separates_upstream_from_local_baseline(self):
        import json
        value = json.loads((ROOT / 'tests/fixtures/browser_original_ui.json').read_text())
        self.assertEqual('OpenMinis/OpenMinis', value['upstream_repository'])
        self.assertEqual('local-adaptation-baseline', value['snapshot_kind'])
        self.assertNotEqual(value['upstream_revision'], value['local_snapshot_revision'])
