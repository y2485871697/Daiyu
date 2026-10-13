"""Durable checks coordinator. State/evidence live outside the source checkout."""
import argparse
from contextlib import contextmanager
try:
    import fcntl
except ImportError:
    fcntl = None
    import msvcrt
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
import uuid
import zipfile
from evidence import SCOPES, atomic_json, configuration, coverage, digest, read_json, verify_bundle

REPO = 'y2485871697/Daiyu'
WORKFLOW = '383377276'
ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def gh(path):
    return json.loads(subprocess.check_output(['gh', 'api', path]))


def remote_quote(text):
    return "'" + text.replace("'", "''") + "'"


@contextmanager
def locked(state):
    state.mkdir(parents=True, exist_ok=True)
    with (state / 'coordinator.lock').open('a+b') as f:
        if fcntl is not None:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        else:
            f.seek(0, 2)
            if f.tell() == 0:
                f.write(b'0')
                f.flush()
            f.seek(0)
            try:
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as e:
                raise BlockingIOError('coordinator_locked') from e
        try:
            yield
        finally:
            if fcntl is None:
                f.seek(0)
                msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)


def snapshot(state, sha, config):
    archive = state / 'snapshot.zip'
    if not archive.exists():
        subprocess.run(['git', '-C', str(ROOT), 'archive', '--format=zip', '-o', str(archive), sha], check=True)
    expected = subprocess.check_output(['git', '-C', str(ROOT), 'archive', '--format=zip', sha])
    if hashlib.sha256(expected).hexdigest() != digest(archive):
        raise ValueError('snapshot_archive_not_exact_commit')
    files = []
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if entry.is_dir():
                continue
            p = PurePosixPath(entry.filename)
            if p.is_absolute() or '..' in p.parts or ':' in entry.filename or '\\' in entry.filename or ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError('unsafe_snapshot')
            files.append(dict(path=entry.filename, sha256=hashlib.sha256(z.read(entry)).hexdigest()))
    manifest = dict(source_sha=sha, config_id=config, archive_sha256=digest(archive), files=files)
    path = state / 'manifest.json'
    if path.exists() and read_json(path) != manifest:
        raise ValueError('snapshot_state_mismatch')
    atomic_json(path, manifest)
    return manifest


def sync_pc(client, state, manifest, remote):
    from ssh_transport import execute
    # Only root-local paths constructed from the validated SHA; no shell data from credentials.
    script = "$p=" + remote_quote(remote) + "; $q=$p; while($q -and $q -ne 'E:\\'){if(Test-Path -LiteralPath $q){if((Get-Item -LiteralPath $q -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'reparse_refused'}};$q=Split-Path -Parent $q}; New-Item -ItemType Directory -Path $p -Force | Out-Null"
    if execute(client, script)[0]:
        raise ValueError('pc_path_preflight_failed')
    with client.open_sftp() as s:
        try:
            with s.open(remote + '/manifest.json') as f:
                existing = json.loads(f.read().decode('utf-8-sig'))
            if existing != manifest:
                raise ValueError('remote_snapshot_conflict')
        except FileNotFoundError:
            s.put(str(state / 'snapshot.zip'), remote + '/snapshot.zip')
            s.put(str(state / 'manifest.json'), remote + '/manifest.json')
    script = r'''
$p=REMOTE; $m=Get-Content -LiteralPath "$p/manifest.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if((Get-FileHash -LiteralPath "$p/snapshot.zip" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $m.archive_sha256){throw 'archive_hash_mismatch'}
if(-not(Test-Path -LiteralPath "$p/source")){Add-Type -AssemblyName System.IO.Compression.FileSystem;[IO.Compression.ZipFile]::ExtractToDirectory("$p/snapshot.zip","$p/source")}
foreach($f in $m.files){$full=Join-Path "$p/source" $f.path;if((Get-Item -LiteralPath $full -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'source_reparse'};if((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.sha256){throw 'source_hash_mismatch'}}
@{source_sha=$m.source_sha;config_id=$m.config_id;files_verified=$m.files.Count;archive_sha256=$m.archive_sha256} | ConvertTo-Json
'''.replace('REMOTE', remote_quote(remote))
    code, out, _ = execute(client, script)
    if code:
        raise ValueError('pc_snapshot_verification_failed')
    result = json.loads(out)
    if (result['source_sha'] != manifest['source_sha'] or result['files_verified'] != len(manifest['files'])
            or result['config_id'] != manifest['config_id'] or result['archive_sha256'] != manifest['archive_sha256']):
        raise ValueError('pc_snapshot_identity_mismatch')
    atomic_json(state / 'snapshot-verified.json', result)


def collect_pc(client, state, manifest, remote, scope, attempt=1):
    base = remote + '/cooperation-' + str(attempt) + '-' + scope
    dest = state / 'evidence' / ('pc-' + str(attempt)) / scope
    dest.mkdir(parents=True, exist_ok=True)
    with client.open_sftp() as s:
        for name, local in ((scope + '-record.json', 'record.json'),
                            (scope + '-reports.zip', 'reports.zip'), (scope + '.log', 'check.log')):
            s.get(base + '/' + name, str(dest / local))
        for name in ('status.json', 'environment.json', 'write-policy.json'):
            s.get(base + '/' + name, str(dest / name))
    record = read_json(dest / 'record.json')
    if record.get('check_attempt') != attempt or record.get('check_id') != 'pc-' + manifest['source_sha'][:12]:
        raise ValueError('pc_attempt_identity_mismatch')
    status = read_json(dest / 'status.json')
    if status.get('result') == 'failed' or status.get('exit_code') not in (None, 0):
        atomic_json(state / 'known-failure.json', dict(scope=scope, source_sha=manifest['source_sha'], status=status))
        raise ValueError('known_check_failure')
    if status.get('result') != 'passed' or status.get('exit_code') != 0:
        raise ValueError('pc_check_not_terminal')
    atomic_json(dest / 'proof.json', dict(origin='windows-pc', files={n: digest(dest / n) for n in ('record.json', 'reports.zip', 'check.log')}))
    return verify_bundle(dest, manifest['source_sha'], manifest['config_id'], scope)


def run_pc(state, manifest, known_hosts, interrupt_after=None):
    from ssh_transport import connect, execute
    client = connect(known_hosts)
    remote = 'E:/daiyu/checks/cooperate-' + manifest['source_sha'][:12]
    journal = state / 'pc-dispatch.json'
    old = read_json(journal) if journal.exists() else {}
    try:
        if (state / 'pc-unavailable.json').exists():
            raise RuntimeError('pc_already_migrated_no_redispatch')
        sync_pc(client, state, manifest, remote)
        # Execute only the hash-verified entry inside the immutable source snapshot.
        for scope in SCOPES:
            if scope in coverage(state, manifest['source_sha'], manifest['config_id']):
                continue
            entry = old.get(scope)
            if entry:
                if entry.get('remote_exit_code') not in (None, 0):
                    raise ValueError('known_check_failure')
                try:
                    collect_pc(client, state, manifest, remote, scope)
                    continue
                except FileNotFoundError:
                    raise RuntimeError('pc_previous_execution_unknown_no_restart')
            command = '& ' + remote_quote(remote + '/source/scripts/cooperation/pc_checks.ps1') + ' -Snapshot ' + remote_quote(remote) + ' -Scope ' + scope + ' -Mode run'
            old[scope] = dict(state='dispatching', source_sha=manifest['source_sha'], check_attempt=1)
            atomic_json(journal, old)  # persist before side effect; never blindly replay
            code, _, _ = execute(client, command)
            old[scope]['remote_exit_code'] = code
            old[scope]['state'] = 'completed'
            atomic_json(journal, old)
            if code:
                # Save failed source evidence if available. Failure is not a missing item.
                try:
                    collect_pc(client, state, manifest, remote, scope)
                finally:
                    raise ValueError('known_check_failure')
            collect_pc(client, state, manifest, remote, scope)
            print('PC_UPLOADED_VERIFIED', scope, flush=True)
            if interrupt_after == scope:
                atomic_json(state / 'controlled-disconnect.json', dict(after=scope, connection_closed=True,
                            next_pc_task_dispatched=False, reason='controlled transport loss at item boundary'))
                raise ConnectionAbortedError('controlled_disconnect')
    finally:
        client.close()


def run_scope(run):
    # Title uniquely encodes checks mode/scope/token; never infer mode from actor or SHA alone.
    match = re.fullmatch(r'代鱼检查 (all|unit|lint) (.*)', run.get('display_title', ''))
    return match.group(1) if match else None


def all_pages(path, key):
    result = []
    for page in range(1, 101):
        data = gh(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')[key]
        result.extend(data)
        if len(data) < 100:
            return result
    raise ValueError('pagination_limit_exceeded')


def cloud_candidates(sha, scope, token=None):
    runs = all_pages(f'repos/{REPO}/actions/workflows/{WORKFLOW}/runs?branch=main&head_sha={sha}', 'workflow_runs')
    return [r for r in runs if r['head_sha'] == sha and r['head_branch'] == 'main'
            and r['workflow_id'] == int(WORKFLOW) and run_scope(r) in (scope, 'all')
            and (token is not None or r['status'] != 'completed' or r['conclusion'] == 'success')
            and (token is None or r['display_title'].endswith(token))]


def cache_cloud(state, run, sha, config, wanted):
    rid, attempt = run['id'], run['run_attempt']
    if run['status'] != 'completed' or run['conclusion'] != 'success' or run['head_sha'] != sha:
        raise ValueError('cloud_not_success_for_sha')
    base = state / 'cloud' / str(rid)
    base.mkdir(parents=True, exist_ok=True)
    jobs = all_pages(f'repos/{REPO}/actions/runs/{rid}/attempts/{attempt}/jobs', 'jobs')
    artifacts = all_pages(f'repos/{REPO}/actions/runs/{rid}/artifacts', 'artifacts')
    atomic_json(base / 'run.json', run)
    atomic_json(base / 'jobs.json', jobs)
    seen = set()
    for art in artifacts:
        if not art['name'].startswith('代鱼检查报告-') or art['expired']:
            continue
        os_label = art['name'].split('报告-', 1)[1]
        job = [j for j in jobs if j['name'] == '只检查 ' + os_label and j['conclusion'] == 'success']
        if len(job) != 1:
            raise ValueError('cloud_job_not_verified')
        archive = base / (str(art['id']) + '.zip')
        with archive.open('wb') as f:
            subprocess.run(['gh', 'api', f'repos/{REPO}/actions/artifacts/{art["id"]}/zip'], stdout=f, check=True)
        if 'sha256:' + digest(archive) != art.get('digest'):
            raise ValueError('github_artifact_digest_mismatch')
        atomic_json(base / (str(art['id']) + '.json'), art)
        with zipfile.ZipFile(archive) as z:
            for scope in wanted:
                names = {n: next((p for p in z.namelist() if p.replace('\\', '/').endswith(scope + '/' + n)), None)
                         for n in ('record.json', 'reports.zip', 'check.log')}
                if not all(names.values()):
                    continue
                dest = state / 'evidence' / (f'github-{rid}-{attempt}-{os_label}') / scope
                dest.mkdir(parents=True, exist_ok=True)
                for name, path in names.items():
                    (dest / name).write_bytes(z.read(path))
                record = read_json(dest / 'record.json')
                if record.get('check_id') != f'github-{rid}-{record["environment"]["os"]}' or record.get('check_attempt') != attempt:
                    raise ValueError('cloud_attempt_mismatch')
                atomic_json(dest / 'proof.json', dict(origin='github', run_id=rid, attempt=attempt,
                            artifact_id=art['id'], artifact_sha256=digest(archive), job_id=job[0]['id'],
                            files={n: digest(dest / n) for n in names}))
                verify_bundle(dest, sha, config, scope, receiving=True)
                seen.add((os_label, scope))
    if seen != {(platform, scope) for platform in ('ubuntu-26.04','windows-latest') for scope in wanted}:
        raise ValueError('cloud_artifacts_missing')
    atomic_json(base / 'complete.json', dict(source_sha=sha, attempt=attempt, scopes=sorted(wanted), platforms=['ubuntu-26.04','windows-latest']))


def cloud_fill(state, sha, config):
    if (state / 'known-failure.json').exists():
        raise ValueError('known_check_failure')
    have = coverage(state, sha, config)
    missing = [s for s in SCOPES if s not in have]
    if not missing:
        return
    requested = missing[0] if len(missing) == 1 else 'all'
    journal = state / 'cloud-dispatch.json'
    if journal.exists():
        dispatch = read_json(journal)
        if dispatch['source_sha'] != sha:
            raise ValueError('dispatch_state_sha_mismatch')
        if dispatch.get('run_id'):
            run = gh(f'repos/{REPO}/actions/runs/{dispatch["run_id"]}')
        else:
            candidates = [r for r in cloud_candidates(sha, requested, dispatch['token']) if r['display_title'].endswith(dispatch['token'])]
            if len(candidates) != 1:
                raise RuntimeError('dispatch_outcome_unknown_do_not_repeat')
            run = candidates[0]
    else:
        candidates = cloud_candidates(sha, requested)
        if candidates:
            run = candidates[0]
            dispatch = dict(source_sha=sha, scope=requested, run_id=run['id'], reused=True)
        else:
            if gh(f'repos/{REPO}/git/ref/heads/main')['object']['sha'] != sha:
                raise ValueError('main_moved_no_dispatch')
            token = uuid.uuid4().hex
            dispatch = dict(source_sha=sha, scope=requested, token=token, state='dispatching')
            atomic_json(journal, dispatch)
            subprocess.run(['gh', 'workflow', 'run', WORKFLOW, '-R', REPO, '--ref', 'main',
                            '-f', 'mode=checks', '-f', 'expected_sha=' + sha, '-f', 'check_scope=' + requested, '-f', 'check_id=' + token], check=True)
            candidates = []
            for _ in range(12):
                candidates = [r for r in cloud_candidates(sha, requested, token) if r['display_title'].endswith(token)]
                if candidates:
                    break
                time.sleep(3)
            if len(candidates) != 1:
                raise RuntimeError('dispatch_outcome_unknown_do_not_repeat')
            run = candidates[0]
        dispatch['run_id'] = run['id']
    if run['head_sha'] != sha or run_scope(run) not in (requested, 'all') or run['workflow_id'] != int(WORKFLOW):
        raise ValueError('bound_cloud_identity_mismatch')
    atomic_json(journal, dispatch)
    print('CLOUD_BOUND', run['id'], 'scope', requested, flush=True)
    while run['status'] != 'completed':
        time.sleep(15)
        run = gh(f'repos/{REPO}/actions/runs/{run["id"]}')
    cache_cloud(state, run, sha, config, missing)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('action', choices=('run', 'offline', 'cloud'))
    p.add_argument('--state', type=Path, required=True)
    p.add_argument('--known-hosts', type=Path)
    p.add_argument('--interrupt-after', choices=('unit',))
    a = p.parse_args()
    state = a.state.resolve()
    if state.is_relative_to(ROOT):
        raise ValueError('state_must_be_outside_repository')
    sha, config = git('rev-parse', 'HEAD'), configuration(ROOT)
    with locked(state):
        if a.action == 'offline':
            have = coverage(state, sha, config)
        else:
            if git('status', '--porcelain'):
                raise ValueError('dirty_checkout')
            manifest = snapshot(state, sha, config)
            if a.action == 'run' and len(coverage(state, sha, config)) != len(SCOPES):
                if not a.known_hosts:
                    raise ValueError('known_hosts_required')
                try:
                    run_pc(state, manifest, a.known_hosts, a.interrupt_after)
                except ValueError:
                    raise  # real failed checks or integrity faults must not become passes
                except Exception as e:
                    atomic_json(state / 'pc-unavailable.json', dict(error_type=type(e).__name__, state='unknown_or_unavailable',
                                incomplete=[s for s in SCOPES if s not in coverage(state, sha, config)]))
            cloud_fill(state, sha, config)
            have = coverage(state, sha, config)
        result = dict(source_sha=sha, config_id=config, offline=a.action == 'offline',
                      passed=sorted(have), missing=[s for s in SCOPES if s not in have],
                      origins={s: r['origin'] for s, r in have.items()}, apk_built=False)
        atomic_json(state / ('offline-result.json' if a.action == 'offline' else 'result.json'), result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if len(have) == len(SCOPES) else 3


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as e:
        # Error messages from SSH/HTTP may include connection fields; log only types.
        print('COOPERATION_ERROR', type(e).__name__, file=sys.stderr)
        sys.exit(1)
