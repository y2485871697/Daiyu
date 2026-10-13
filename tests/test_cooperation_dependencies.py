import builtins
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/cooperation'))
import cooperate


class DependencyTests(unittest.TestCase):
    def test_missing_paramiko_fails_before_credentials_are_read(self):
        real_import = builtins.__import__

        def missing(name, *args, **kwargs):
            if name == 'paramiko':
                raise ModuleNotFoundError('synthetic', name='paramiko')
            return real_import(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as folder, patch.dict(sys.modules):
            sys.modules.pop('ssh_transport', None)
            with patch('builtins.__import__', side_effect=missing), \
                    patch.object(Path, 'read_text', side_effect=AssertionError('credential read')):
                with self.assertRaises(ModuleNotFoundError):
                    cooperate.run_pc(Path(folder), {}, Path(folder) / 'known-hosts')

    def test_migrated_state_never_imports_transport_or_connects(self):
        real_import = builtins.__import__

        def refuse(name, *args, **kwargs):
            if name == 'ssh_transport':
                raise AssertionError('transport imported after migration')
            return real_import(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder)
            (state / 'pc-unavailable.json').write_text('{}')
            with patch('builtins.__import__', side_effect=refuse):
                with self.assertRaisesRegex(RuntimeError, 'pc_already_migrated_no_redispatch'):
                    cooperate.run_pc(state, {}, state / 'known-hosts')

    def test_local_dependency_error_has_specific_reason_and_cloud_fallback(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder)
            filled = False

            def fill(*args):
                nonlocal filled
                filled = True

            def have(*args):
                return {s: {'origin': 'github'} for s in cooperate.SCOPES} if filled else {}

            out, err = io.StringIO(), io.StringIO()
            with patch.object(sys, 'argv', ['cooperate.py', 'run', '--state', str(state),
                                           '--known-hosts', str(state / 'known-hosts')]), \
                    patch.object(cooperate, 'git', side_effect=lambda *a: 'a' * 40 if a[0] == 'rev-parse' else ''), \
                    patch.object(cooperate, 'configuration', return_value='b' * 64), \
                    patch.object(cooperate, 'snapshot', return_value={}), \
                    patch.object(cooperate, 'coverage', side_effect=have), \
                    patch.object(cooperate, 'run_pc', side_effect=ModuleNotFoundError('secret-free synthetic')), \
                    patch.object(cooperate, 'cloud_fill', side_effect=fill), \
                    redirect_stdout(out), redirect_stderr(err):
                self.assertEqual(cooperate.main(), 0)
            reason = json.loads((state / 'pc-unavailable.json').read_text())
            self.assertEqual(reason['state'], 'local_dependency_unavailable')
            self.assertEqual(reason['incomplete'], ['unit', 'lint'])
            self.assertIn('uv run --locked --script', err.getvalue())
            self.assertNotIn('secret-free synthetic', err.getvalue())
            self.assertTrue(filled)

    def test_non_ssh_import_does_not_require_paramiko(self):
        real_import = builtins.__import__

        def refuse(name, *args, **kwargs):
            if name in ('paramiko', 'ssh_transport'):
                raise AssertionError('SSH dependency used by offline path')
            return real_import(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as folder, patch('builtins.__import__', side_effect=refuse):
            cooperate.coverage(Path(folder), 'a' * 40, 'b' * 64)
            self.assertIsNone(cooperate.run_scope({'display_title': 'build'}))
