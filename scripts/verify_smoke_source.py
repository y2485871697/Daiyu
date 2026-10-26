"""Bind dual-API regression to an existing, unchanged application build."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile


def verify(env, record, source_run, jobs, changed):
    sha = env['SOURCE_SHA']
    run_id = env['SOURCE_RUN_ID']
    variant = env.get('SOURCE_VARIANT', 'debug')
    if variant not in ('debug', 'release', 'daily'):
        raise ValueError('补检应用包变体无效')
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdecimal():
        raise ValueError('补检必须指定完整源码SHA与run ID')
    if (str(source_run['id']) != run_id
            or type(source_run['run_attempt']) is not int or source_run['run_attempt'] != 1
            or source_run['head_sha'] != sha or source_run['head_branch'] != 'main'
            or source_run['workflow_id'] != 397560064 or source_run['status'] != 'completed'
            or source_run.get('conclusion') not in ('success', 'failure')):
        raise ValueError('源run身份或终态不符')
    job_name = '单测、检查与生产Release' if variant in ('release', 'daily') else '单测、检查与开发包'
    if not any(j['name'] == job_name and j['conclusion'] == 'success' for j in jobs):
        raise ValueError('源应用包构建没有成功')
    if (record['source_sha'] != sha or str(record['run_id']) != run_id
            or str(record['attempt']) != '1' or record['application_id'] != 'app.daiyu.android'
            or record.get('variant') != ('release' if variant == 'daily' else variant) or record.get('debuggable') is not (variant == 'debug')):
        raise ValueError('应用包记录与源run或变体不符')
    if variant in ('release', 'daily') and (record.get('production_delivery') is not True
            or record.get('profileable') is not False
            or record.get('test_application_id') != 'app.daiyu.android.test'):
        raise ValueError('源应用包不是生产Release')
    if variant == 'daily' and (record.get('validation_profile') != 'daily'
            or record.get('full_regression_passed') is not False):
        raise ValueError('日常Release验证范围不符')
    if variant == 'release' and record.get('validation_profile') == 'daily':
        raise ValueError('日常Release不能冒充稳定版源包')
    # Fail closed: scripts/ is NOT a validation-only directory. In particular,
    # app/build.gradle.kts consumes prepare-speech-runtime.py when producing APKs.
    # New tooling must be reviewed here before it can authorize old-APK reuse.
    validation_files = {
        'scripts/smoke_boot.sh', 'scripts/verify_smoke_source.py',
        'scripts/adb_bounded.sh', 'scripts/install_bounded_adb.sh',
        'scripts/verify_boundaries.py', 'scripts/daily_release.py',
    }
    validation_dirs = ('scripts/acceptance/', 'tests/', 'docs/acceptance/')
    if any(not isinstance(path, str) or '\\' in path
           or any(part in ('', '.', '..') for part in path.split('/'))
           or not (path in validation_files or path.startswith(validation_dirs))
           for path in changed):
        raise ValueError('应用或构建输入已变化，不能复用旧应用包')


LEGACY_SMOKE_PRELAUNCH = '          pre-emulator-launch-script: |\n            "$ANDROID_HOME/emulator/emulator" -version > smoke/emulator-version.txt 2>&1 && cat smoke/emulator-version.txt\n            cp "$ANDROID_HOME/emulator/source.properties" smoke/emulator-package.txt\n            if test \'${{ matrix.api }}\' = 26; then grep -Fx \'Pkg.Revision=35.6.11\' smoke/emulator-package.txt && grep -Fx \'Pkg.BuildId=13610412\' smoke/emulator-package.txt; fi\n            bash scripts/install_bounded_adb.sh\n            python3 -c \'import os; from pathlib import Path; p=Path(os.environ["ANDROID_AVD_HOME"])/"test.avd/config.ini"; values={"hw.lcd.width":"1216","hw.lcd.height":"2432","hw.lcd.density":"160","skin.name":"1216x2432","skin.path":"1216x2432"}; lines=[line for line in p.read_text().splitlines() if line.split("=",1)[0].strip() not in values]; p.write_text("\\n".join(lines+[f"{key}={value}" for key,value in values.items()])+"\\n")\'\n            cp "$ANDROID_AVD_HOME/test.ini" smoke/avd.ini\n            cp "$ANDROID_AVD_HOME/test.avd/config.ini" smoke/avd-config.ini\n            cat smoke/avd.ini\n            grep -Fx \'target=android-${{ matrix.api }}\' smoke/avd.ini\n'
SMOKE_PRELAUNCH = '          pre-emulator-launch-script: bash scripts/prepare_smoke_emulator.sh "${{ matrix.api }}"\n'
SMOKE_HOST_DEPENDENCIES = '      - name: 安装模拟器宿主依赖\n        run: |\n          sudo apt-get update\n          sudo apt-get install --no-install-recommends -y libpulse0\n'

SMOKE_UWB_TRANSPORT = " ${{ matrix.api == '37.2' && '-feature -Uwb -qemu -chardev netsim,id=uwb -device virtserialport,chardev=uwb,name=uwb' || '' }}"


def candidate_workflow_inputs(workflow):
    # Only the reviewed API26 image, candidate-source wiring and 1-to-4 shard migration may differ.
    prefix, smoke = workflow.split('  smoke:\n', 1)
    prefix = prefix.replace('options: [debug, release, candidate]', 'options: [debug, release]')
    smoke = smoke.replace('"api":"26","target":"default"', '"api":"26","target":"google_apis"')
    smoke = smoke.replace("(inputs.mode == 'candidate' || (inputs.mode == 'smoke' && inputs.source_variant == 'candidate'))", "inputs.mode == 'candidate'")
    # Match the complete legacy expressions; arbitrary matrix/env edits stay rejected.
    def matrix(api, shards):
        return json.dumps([dict(api=api, target='google_apis' if api == '26' else 'google_apis_ps16k',
                                ram=4096 if api == '26' else 6144, shard=i) for i in shards],
                          separators=(',', ':'))
    one26, one37 = matrix('26', [0]), matrix('37.2', [0])
    four26, four37 = matrix('26', range(4)), matrix('37.2', range(4))
    both_one = one26[:-1] + ',' + one37[1:]
    both_four = four26[:-1] + ',' + four37[1:]
    legacy = (f"inputs.mode == 'candidate' && inputs.smoke_api == '26' && '{one26}' || "
              f"inputs.mode == 'candidate' && inputs.smoke_api == '37.2' && '{one37}' || "
              f"inputs.mode == 'candidate' && '{both_one}' || "
              f"inputs.mode == 'smoke' && inputs.smoke_api == '26' && '{four26}' || "
              f"inputs.mode == 'smoke' && inputs.smoke_api == '37.2' && '{four37}' || '{both_four}'")
    parallel = (f"(inputs.mode == 'smoke' || inputs.mode == 'candidate') && inputs.smoke_api == '26' && '{four26}' || "
                f"(inputs.mode == 'smoke' || inputs.mode == 'candidate') && inputs.smoke_api == '37.2' && '{four37}' || '{both_four}'")
    smoke = smoke.replace('include: ${{ fromJSON(' + legacy + ') }}',
                          'include: ${{ fromJSON(' + parallel + ') }}')
    smoke = smoke.replace("SMOKE_SHARD_COUNT: ${{ inputs.mode == 'candidate' && 1 || 4 }}",
                          "SMOKE_SHARD_COUNT: '4'")
    # Normalize only the exact reviewed host-only preparation migration.
    smoke = smoke.replace(LEGACY_SMOKE_PRELAUNCH, SMOKE_PRELAUNCH)
    smoke = smoke.replace(SMOKE_HOST_DEPENDENCIES, '')
    smoke = smoke.replace(SMOKE_UWB_TRANSPORT, '')
    smoke = smoke.replace('timeout-minutes: 175', 'timeout-minutes: 160')
    smoke = smoke.replace('timeout-minutes: 185', 'timeout-minutes: 170')
    smoke = smoke.replace('          rm -f smoke/prelaunch-ready.txt\n', '')
    smoke = smoke.replace('test -s smoke/prelaunch-ready.txt && bash scripts/smoke_boot.sh',
                          'bash scripts/smoke_boot.sh')
    return prefix + '  smoke:\n' + smoke


def verify_candidate(env, root, source_run, jobs, changed, source_workflow, current_workflow):
    from candidate_package import verify as verify_package
    sha, run_id = env['SOURCE_SHA'], env['SOURCE_RUN_ID']
    if (not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdecimal()
            or str(source_run['id']) != run_id or type(source_run['run_attempt']) is not int
            or source_run['run_attempt'] != 1 or source_run['head_sha'] != sha
            or source_run['head_branch'] != 'main' or source_run['workflow_id'] != 397560064
            or source_run['status'] != 'completed' or source_run.get('conclusion') not in ('success', 'failure')):
        raise ValueError('候选源run身份或终态不符')
    if not any(j['name'] == '定向候选编译（非正式交付）' and j['conclusion'] == 'success' for j in jobs):
        raise ValueError('候选源编译未成功')
    if env.get('SMOKE_MODE') != 'smoke' or not env.get('SMOKE_TEST_CLASSES'):
        raise ValueError('候选复用只允许非空定向补检')
    allowed_files = {'scripts/smoke_boot.sh', 'scripts/verify_smoke_source.py',
                     'scripts/adb_bounded.sh', 'scripts/install_bounded_adb.sh',
                     'scripts/prepare_smoke_emulator.sh', 'scripts/verify_boundaries.py', '.github/workflows/build.yml',
                     'docs/重构清单.md', '更新说明.md'}
    allowed_dirs = ('scripts/acceptance/', 'tests/', 'docs/acceptance/')
    if any(not isinstance(path, str) or '\\' in path
           or any(part in ('', '.', '..') for part in path.split('/'))
           or not (path in allowed_files or path.startswith(allowed_dirs)) for path in changed):
        raise ValueError('候选APK或构建输入已变化')
    if candidate_workflow_inputs(source_workflow) != candidate_workflow_inputs(current_workflow):
        raise ValueError('工作流包含未审阅的构建或验证变化')
    verify_package(root, dict(GITHUB_SHA=sha, GITHUB_RUN_ID=run_id,
                             GITHUB_RUN_ATTEMPT='1', SMOKE_TEST_CLASSES=env['SMOKE_TEST_CLASSES']))


def verify_candidate_archive(root, artifact, archive):
    if artifact.get('digest') != 'sha256:' + hashlib.sha256(archive).hexdigest():
        raise ValueError('候选源工件归档摘要不符')
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        names = [item.filename for item in zipped.infolist() if not item.is_dir()]
        local = sorted(path.relative_to(root).as_posix() for path in root.rglob('*') if path.is_file())
        if len(set(names)) != len(names) or sorted(names) != local:
            raise ValueError('候选下载文件集合与归档不符')
        if any(zipped.read(name) != (root / name).read_bytes() for name in names):
            raise ValueError('候选下载文件与归档字节不符')


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
    source_artifact = None
    if env.get('SOURCE_VARIANT') == 'candidate':
        source_workflow = subprocess.check_output(['git', 'show', env['SOURCE_SHA'] + ':.github/workflows/build.yml'], text=True)
        verify_candidate(env, Path('delivery'), source_run, jobs, changed,
                         source_workflow, Path('.github/workflows/build.yml').read_text())
        artifacts = get(base + '/artifacts?per_page=100')
        if artifacts['total_count'] != len(artifacts['artifacts']):
            raise ValueError('候选源工件分页不完整')
        matches = [a for a in artifacts['artifacts'] if a['name'] == '代鱼定向候选包-filtered' and not a['expired']]
        if len(matches) != 1:
            raise ValueError('候选源工件不唯一')
        source_artifact = matches[0]
        if (source_artifact['workflow_run']['id'] != int(run_id)
                or source_artifact['workflow_run']['head_sha'] != env['SOURCE_SHA']):
            raise ValueError('候选源工件归属不符')
        request = urllib.request.Request(source_artifact['archive_download_url'],
                                         headers={'Accept': 'application/vnd.github+json'})
        request.add_unredirected_header('Authorization', f"Bearer {env['GH_TOKEN']}")
        with urllib.request.urlopen(request, timeout=90) as response:
            archive = response.read(512 * 1024 * 1024 + 1)
        if len(archive) > 512 * 1024 * 1024:
            raise ValueError('候选工件过大')
        verify_candidate_archive(Path('delivery'), source_artifact, archive)
    else:
        record = json.loads(Path('delivery/构建记录.json').read_text())
        verify(env, record, source_run, jobs, changed)
    Path('smoke').mkdir(exist_ok=True)
    Path('smoke/source.json').write_text(json.dumps(dict(build_sha=env['SOURCE_SHA'], build_run=run_id,
        build_attempt=1, build_variant=env.get('SOURCE_VARIANT', 'debug'), check_sha=env['GITHUB_SHA'], check_run=env['GITHUB_RUN_ID'], check_attempt=env['GITHUB_RUN_ATTEMPT'],
        changed_validation_files=changed, source_artifact=source_artifact,
        formal_acceptance=False if env.get('SOURCE_VARIANT') in ('candidate', 'daily') else None), ensure_ascii=False) + '\n')
    print('既有应用包身份、变体及应用源码一致性核对通过')


if __name__ == '__main__':
    main()
