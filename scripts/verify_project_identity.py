"""Check current source identities, including names inside bundled source archives."""
from pathlib import Path
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PREVIOUS_NAME = ''.join(map(chr, (101, 116, 97)))
PREVIOUS_PACKAGE = '.'.join(('io', 'github', 'man' + 'gi', PREVIOUS_NAME))
WORD = re.compile(r'(?<![A-Za-z])' + PREVIOUS_NAME + r'(?![A-Za-z])', re.I)
CAMEL = re.compile(r'\b' + PREVIOUS_NAME + r'[A-Z]|' + PREVIOUS_NAME.title() + r'[A-Z]')
SKIP = {'.git', '.gradle', 'build', '__pycache__', '.pytest_cache'}


def forbidden(value):
    return PREVIOUS_PACKAGE in value or WORD.search(value) is not None or CAMEL.search(value) is not None


def source_files(root):
    result = subprocess.run(['git', '-C', str(root), 'ls-files', '-z', '--cached', '--others', '--exclude-standard'],
                            capture_output=True)
    if result.returncode == 0:
        paths = (root / name.decode() for name in result.stdout.split(b'\0') if name)
    else:
        paths = root.rglob('*')
    return sorted({p for p in paths if p.is_file() and not SKIP.intersection(p.relative_to(root).parts)})


def check_text(data, label, failures):
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        return
    for number, line in enumerate(text.splitlines(), 1):
        if forbidden(line):
            failures.append(f'{label}:{number}')


def verify_tree(root=ROOT):
    failures = []
    files = source_files(root)
    for path in files:
        name = path.relative_to(root).as_posix()
        if forbidden(name):
            failures.append(name)
        if path.suffix == '.tgz':
            with tarfile.open(path, 'r:gz') as archive:
                for member in archive:
                    label = name + ':' + member.name
                    if forbidden(member.name):
                        failures.append(label)
                    if member.isfile():
                        if member.size > 32 * 1024 * 1024:
                            raise ValueError('source archive member exceeds inspection limit: ' + label)
                        with archive.extractfile(member) as source:
                            check_text(source.read(), label, failures)
        else:
            check_text(path.read_bytes(), name, failures)
    if failures:
        raise ValueError('unsupported historical project identities: ' + ', '.join(failures))
    return len(files)


if __name__ == '__main__':
    print(f'Project identity: passed ({verify_tree()} current source files, including bundled source archives)')
