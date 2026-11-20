"""Strict, opt-in single-class or single-method diagnostic selection."""
from pathlib import Path
import re

CLASS = re.compile(r"app\.daiyu\.android\.(?:[A-Za-z_][A-Za-z_0-9]*\.)*[A-Za-z_][A-Za-z_0-9]*")
METHOD = re.compile(r"test[A-Za-z_0-9]+")


def diagnostic_selection(root, env):
    enabled = env.get('SMOKE_DIAGNOSTIC', '')
    method = env.get('SMOKE_TEST_METHOD', '')
    if not enabled and not method:
        return None
    if enabled != 'true' or env.get('SMOKE_MODE') != 'smoke':
        raise ValueError('Method filtering requires explicit smoke diagnostic mode')
    name = env.get('SMOKE_TEST_CLASSES', '')
    if not CLASS.fullmatch(name) or (method and not METHOD.fullmatch(method)):
        raise ValueError('Expected one complete class and one plain test method name')
    if env.get('SMOKE_SHARD_COUNT', '1') != '1' or env.get('SMOKE_SHARD_INDEX', '0') != '0':
        raise ValueError('Diagnostics require exactly one nonempty shard')
    root = Path(root).resolve()
    relative = Path(*name.split('.'))
    paths = [root / 'app/src/androidTest' / language / relative.with_suffix(suffix)
             for language, suffix in (('kotlin', '.kt'), ('java', '.java'))]
    paths = [p for p in paths if p.is_file()]
    if len(paths) != 1 or paths[0].is_symlink() or not paths[0].resolve().is_relative_to(root):
        raise ValueError('Expected one local instrumentation source')
    path = paths[0]
    text = path.read_text()
    package, _, simple = name.rpartition('.')
    packages = re.findall(r'^package\s+([\w.]+)\s*;?\s*$', text, re.M)
    declarations = re.findall(r'^\s*(?:public\s+(?:final\s+)?)?class\s+(\w+)', text, re.M)
    pattern = (r'^\s*(?:public\s+)?fun\s+(test\w+)\s*\(\s*\)' if path.suffix == '.kt'
               else r'^\s*public\s+void\s+(test\w+)\s*\(\s*\)')
    methods = re.findall(pattern, text, re.M)
    if packages != [package] or declarations != [simple] or not methods or len(set(methods)) != len(methods):
        raise ValueError('Ambiguous diagnostic source or test methods')
    if method and method not in methods:
        raise ValueError('Unknown instrumentation method')
    return dict(class_name=name, method=method, source=str(path.relative_to(root)),
                target=name + ('#' + method if method else ''),
                expected_tests=1 if method else len(methods))
