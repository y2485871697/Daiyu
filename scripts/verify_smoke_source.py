"""Bind API26-only regression to an existing, unchanged application build."""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request


def verify(env, record, source_run, jobs, changed):
    sha = env['SOURCE_SHA']
    run_id = env['SOURCE_RUN_ID']
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdecimal():
        raise ValueError('补检必须指定完整源码SHA与run ID')
    if (str(source_run['id']) != run_id or source_run['run_attempt'] != 1
            or source_run['head_sha'] != sha or source_run['head_branch'] != 'main'
            or source_run['workflow_id'] != 383377276 or source_run['status'] != 'completed'):
        raise ValueError('源run身份或终态不符')
    if not any(j['name'] == '单测、检查与开发包' and j['conclusion'] == 'success' for j in jobs):
        raise ValueError('源开发包构建没有成功')
    if (record['source_sha'] != sha or str(record['run_id']) != run_id
            or str(record['attempt']) != '1' or record['application_id'] != 'app.daiyu.android'):
        raise ValueError('开发包记录与源run不符')
    # Only validation tooling/docs may differ; never credit an old APK for app changes.
    allowed = ('scripts/', 'tests/', 'docs/', '.github/workflows/')
    if any(not path.startswith(allowed) for path in changed):
        raise ValueError('应用或构建输入已变化，不能复用旧开发包')
    if any(path in ('scripts/prepare_signing.py', 'scripts/verify_signing.py') for path in changed):
        raise ValueError('签名配置已变化')


def main():
    env = os.environ
    run_id = env['SOURCE_RUN_ID']
    if not run_id.isdecimal() or not re.fullmatch(r'[0-9a-f]{40}', env['SOURCE_SHA']):
        raise ValueError('补检参数无效')
    base = f"https://api.github.com/repos/{env['GITHUB_REPOSITORY']}/actions/runs/{run_id}"
    def get(url):
        request = urllib.request.Request(url, headers={'Authorization': f"Bearer {env['GH_TOKEN']}", 'Accept': 'application/vnd.github+json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    source_run = get(base)
    jobs = get(base + '/attempts/1/jobs?per_page=100')['jobs']
    subprocess.run(['git', 'merge-base', '--is-ancestor', env['SOURCE_SHA'], env['GITHUB_SHA']], check=True)
    changed = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', env['SOURCE_SHA'], env['GITHUB_SHA']], text=True).split('\0')[:-1]
    record = json.loads(Path('delivery/构建记录.json').read_text())
    verify(env, record, source_run, jobs, changed)
    Path('smoke').mkdir(exist_ok=True)
    Path('smoke/source.json').write_text(json.dumps(dict(build_sha=env['SOURCE_SHA'], build_run=run_id,
        build_attempt=1, check_sha=env['GITHUB_SHA'], check_run=env['GITHUB_RUN_ID'], check_attempt=env['GITHUB_RUN_ATTEMPT'],
        changed_validation_files=changed), ensure_ascii=False) + '\n')
    print('既有开发包身份及应用源码一致性核对通过')


if __name__ == '__main__':
    main()
