# 最终交付统一入口（离线、保守、不代替最终审计）

## 结论边界与本次主路径

入口：`scripts/acceptance/final_delivery.py`。它只调用既有 `source_gate.py`、`cloud_report.py`、`evidence.py`、`recovery_suite.py`、`verify_license_bundle.py` 的读取/解析能力和既有签名/旧包复用策略；不执行 ledger 中的命令，不下载、调度 CI、运行测试、安装、编译或隐式补构建 APK。除源码门禁和旧包差异核对所需的只读 Git 外，不启动外部命令。

**本次最终验收以完整构建为主：BUILD_SHA = CHECK_SHA，BUILD_RUN = CHECK_RUN，BUILD_ATTEMPT = CHECK_ATTEMPT。** 三者必须分别通过 CLI 预先指定，并与 pin 住的 manifest、开发包构建记录、run/jobs、双 API 快照逐一一致。不以短 SHA、HEAD 推测值、最新 run 或最新 artifact 代替精确身份。

旧包补检只用 `mode=old_apk_recheck`，保留独立 build/check 身份；cloud 快照的 `head_sha` 是 CHECK_SHA，开发包记录/81 项 ledger 的 `build_sha` 仍是 BUILD_SHA。即使检查脚本更新，也不重写历史构建身份。旧包限定源构建 attempt=1（复用既有策略），检查 attempt 可不同。工具重新检查 BUILD_SHA 是 CHECK_SHA 的祖先和真实 Git 差异，不信 manifest 自报 changed-files。

### 三层结果不能混同

| 层 | 实际验证 | 不证明什么 |
| --- | --- | --- |
| source/tool | 当前 CHECK_SHA 的只读源码门禁；固定身份/命令/退出码的真实保留 unittest 原文，复用既有 `python_log` 解析 | 工具负测不是产品业务验收；原文/receipt 的内部一致性不是可信采集证明 |
| cloud | 既有工具固定 API **26、37.2**、CHECK_SHA/run/attempt/job；重新哈希 snapshot→report→instrumentation/logcat | 启动 smoke 不是 81 项业务；API37.2 不能被 SDK 整数37替代；离线结果不认证 APK 实际被装入设备 |
| business | **唯一** `docs/refactor-input/acceptance.json` 的 81 项；既有 evidence 方法下限、policy、ledger、非空 artifacts、BUILD_SHA 与散列 | 不接受覆盖计划、场景计划、空 ledger、自报 passed、dispatch 回执或模型自报代替执行；结构合规也不自动证明真实采集 |

没有 business 输入时明确 **81 not_run / 0 passed**；all-not-run 模板也仍为 81 not_run。缺一个 API、工具记录、源码绑定或业务必需类别，都不能成为整体完成。未经认证的完整 ledger 最多输出 `status=ready_for_independent_review`、`internal_consistency_satisfied=true`；**`final_acceptance_passed=false`、`business_satisfied=false`、`business_evidence_authenticated=false` 与 `trusted_collection=false` 永远保留**。必须由主代理/审计者逐项复核真实原文、物理设备、执行来源、编译来源与方法语义后另行作出最终结论。不给本地 JSON 增加一个 `approved=true` 逃生口，也不将“计划齐了”当作“业务通过了”。

退出码：0 = 三层内部一致且完整、仅可提交独立审计；1 = 合规但未齐；2 = 非法/冲突/不可读/已知失败。合法业务账本只要有一个 `failed`（含补充类别），即输出 `valid=true`、`status=failed`、exit 2，并保留完整业务审阅汇总；即使同时缺工具/云端/其他业务记录，也不能降为 incomplete。没有失败的 missing/null、`not_run`、`blocked` 仍是 `status=incomplete`、exit 1；非法输入则 `valid=false`、固定错误码、exit 2。任何退出码都不能单独作“最终验收通过”措辞。错误 JSON 不回显路径、命令、日志或参数；repository/workflow_run 非对象也必须得到单条脱敏 JSON、stderr 为空，而非 traceback。

## 1. 旧包复用与原始 instrumentation 的必要修正

`app/build.gradle.kts` 的 `prepareOfflineSpeechRuntime` 真实读取 `scripts/prepare-speech-runtime.py`，所以 `scripts/` 不是纯校验目录。`verify_smoke_source.py` 仅允许下列 reviewed 校验路径变化：

- 精确文件：`scripts/smoke_boot.sh`、`scripts/verify_smoke_source.py`、`scripts/adb_bounded.sh`、`scripts/install_bounded_adb.sh`、`scripts/verify_boundaries.py`；
- 校验目录：`scripts/acceptance/`、`tests/`、`docs/acceptance/`。

未知脚本（即使名字像检查）、语音准备脚本、签名脚本、workflow、任何 app/core/build 输入与清单均拒绝；不接受 `..` 或反斜线别名。以后将白名单内工具接入 APK 产物生成前，必须同步撤销其复用资格。不能为了复用旧包扩大整个 scripts/docs/workflow 白名单。

smoke 与 cloud 共用 `scripts/acceptance/instrumentation_result.py`，不再两套规则。严格要求唯一整行 `OK (N tests)`（允许单数 test）、N 等于源码发现计数，**唯一的最终非空行**必须为 `INSTRUMENTATION_CODE: -1`；缺失、重复、缩进/前后缀 OK、状态失败/冲突、终态后出现状态等均失败。cloud 旧有“可省略终态”兼容已撤销。Java 与 Kotlin 类发现仍保留。smoke 保存未改写的 stdout+stderr 到 `smoke/platform-tests.txt`，阶段 begin/end 写独立 fd3 与 stages.txt，不混入原文。解析器 JSON 是辅助结果，不能替代原文。

## 2. 已接入 workflow 的最小接缝

`.github/workflows/build.yml` 保留既有 build/checks/smoke 模式，补充源码身份绑定与证据保留，不新增 dispatch、触发器、自动补构建、第二套 81 项清单或 workflow 内的最终汇总。

完整构建的调度方应预先固定完整 40 位十六进制 `expected_sha`，并提供本次唯一的 `check_id`；build 的 run-name 为 `代鱼构建 {check_id}`，便于定向定位，但名称本身不能替代 run ID/attempt/head_sha 核对。build 在 checkout 后、Java/Gradle/SDK 安装及签名凭据使用前，立即运行 `python3 scripts/cooperation/cloud_checks.py --verify-revision-only`。该入口复用 checks 的身份校验，只读取 `git rev-parse HEAD`，不运行 Gradle 或创建检查报告；要求有效的 `GITHUB_SHA` 与 HEAD 完全相同，非空 `EXPECTED_SHA` 必须是完整 40hex 且与二者完全相同（不裁剪、不按短 SHA 或分支解析）。手动留空 `expected_sha` 可用，但仍须核对环境与实际 checkout，不能据此宣称绑定了外部预定 revision。若 main 在预定 SHA 后变化，必须失败并重新核定目标，不能静默改构建其他提交，也不会自动 checkout/重试预期 SHA。此绑定不改变 build gates、双 API smoke 或旧包补检语义。

离线负测在 `tests/test_cloud_revision.py`，覆盖非法/不匹配/缺省空/匹配目标、环境与 HEAD 冲突、Git 读取失败、仅校验入口及 workflow 最早顺序；Git/Gradle 均 mock，不调度 CI。可由父集成执行 `python3 -B -m unittest discover -s tests -p 'test_cloud_revision.py' -v`，再运行完整 tests discovery；本次实现代理未运行这些测试。

既有证据接缝如下：

1. 完整 build 继续产生现有 `代鱼开发包`（APK、test APK、构建记录、签名证据、SHA256SUMS）。不重写记录。复制主 APK 至 delivery、核对签名后，在生成 SHA256SUMS 和上传开发包前，对**同一 delivery 主 APK**运行 `python3 scripts/verify_license_bundle.py --apk "delivery/代鱼-0.1.0-${short}.apk"`。失败阻断上传，不使用 `--stage` 自动修复许可输入；现有测试报告 always 上传、双 API smoke、checks 和签名功能保留。
2. build/checks 用 `scripts/acceptance/record_tool_tests.py` 执行原有 unittest discovery 的 verbose 形式：build 为 `python3 -B -m unittest discover -s tests -v`，跨平台 checks 为 `python -B -m unittest discover -s tests -v`。采集器用相同 argv 真正启动子进程，stdout+stderr 不过滤、不重写，原字节保存到 `tool-evidence/unittest.log`，随后原样回显。子进程返回后才写 `tool-evidence/receipt.json`，包含当前 `GITHUB_SHA`、`GITHUB_RUN_ID`、`GITHUB_RUN_ATTEMPT`、实际 command 与真实整数 exit_code；返回相同退出码。启动失败/中断不补 success receipt，调用前清除旧 receipt。

   两文件随当前 run 的 `代鱼测试报告`（build）/`代鱼检查报告-${os}`（checks）在 `if: always()` 下上传，失败也保留。边界校验和原有检查仍在 unittest 后执行，不把工具负测当业务证据。审阅时将该 artifact 内的两文件原样归置到 `$REVIEW/tool/receipt.json` 与 `$REVIEW/tool/unittest.log`，不能重新生成 receipt 或强改身份。此 receipt 不自行认证采集或执行全集。
3. smoke-only 仍支持旧包补检，但每个选定 API job 都先对**当前 CHECK_SHA/CHECK_RUN/CHECK_ATTEMPT**采集工具原文，以 `代鱼工具证据-smoke-API${api}-分片${index}` 在 `always()` 下单独上传。BUILD 的工具 receipt 不能改标成 CHECK；旧 APK 的构建记录不变，祖先/白名单限制仍保持。仅选一个 API 的补检仍不满足双 API 最终入口。完整 build 的 smoke 复用同 run 的 build 工具证据，不重复执行工具套件。
4. emulator action 的 script 仍调用现有 smoke，只额外保留外层真实返回码，供离线归一化使用：

   ```bash
   bash -c 'set +e; bash scripts/smoke_boot.sh; code=$?; printf "%s\n" "$code" > smoke/exit-code.txt; exit "$code"'
   ```

   保持 `if: always()` 上传两个 API 各自原始 smoke 目录，不删除/过滤负面原文。若外层 runner 超时导致 exit-code 不存在，则证据缺失，不能补写0；不能从 job success 推断退出码。
5. 最终入口只由父代理在**独立审阅阶段、run 已 completed 后**执行，不接入正在运行的 workflow。不要在本 run 未终态时伪造 success snapshot。由已有授权收集机制保存指定 attempt 的真实 run/jobs、artifact metadata、原始 ZIP 与解包文件；本入口不负责触发/轮询/收集。归一化只能保留真实 status/conclusion，不得改写 success。业务证据另行真实采集，缺失就留 null/incomplete。

## 3. 固定身份与采集文件（命令模板，不表示本次已执行）

以下命令仅用于已有授权的收集/审阅，不创建 run。变量必须从外部独立核定，不以待审文件自推预期身份；`ARTIFACT_ID` 必须是指定 build job 时间区间内的 `代鱼开发包` ID。每个 attempt 的 jobs 响应必须完整；分页缺失/多余会拒绝，不能把另一次 attempt 的 jobs 合并。

```bash
# REVIEW 为受控仓库外目录，REPO_ROOT 为明确的独立干净 checkout。
# 本次完整构建的等式必须成立；旧包模式不得套用此赋值强改身份。
CHECK_SHA="$BUILD_SHA"
CHECK_RUN="$BUILD_RUN"
CHECK_ATTEMPT="$BUILD_ATTEMPT"
mkdir -p "$REVIEW/build" "$REVIEW/check"
gh api "repos/y2485871697/Daiyu/actions/runs/$BUILD_RUN/attempts/$BUILD_ATTEMPT" > "$REVIEW/build/run.json"
gh api "repos/y2485871697/Daiyu/actions/runs/$BUILD_RUN/attempts/$BUILD_ATTEMPT/jobs?per_page=100" > "$REVIEW/build/jobs.json"
gh api "repos/y2485871697/Daiyu/actions/artifacts/$ARTIFACT_ID" > "$REVIEW/build/artifact.json"
gh api "repos/y2485871697/Daiyu/actions/artifacts/$ARTIFACT_ID/zip" > "$REVIEW/build/artifact.zip"
gh api "repos/y2485871697/Daiyu/actions/runs/$CHECK_RUN/attempts/$CHECK_ATTEMPT" > "$REVIEW/check/run.json"
gh api "repos/y2485871697/Daiyu/actions/runs/$CHECK_RUN/attempts/$CHECK_ATTEMPT/jobs?per_page=100" > "$REVIEW/check/jobs.json"
```

不在本文提供未经审查的 ZIP 解包命令。使用既有受控采集过程将开发包成员原样置于 `$REVIEW/delivery/`，八个 smoke artifact 原样分别置于 `$REVIEW/cloud/<api>/shard-<index>/`（api为26、37.2，index为0..3）。最终入口验证 ZIP digest = metadata.digest，artifact 的 run/SHA/branch，创建时间落在指定 attempt 的 build job 起止区间，ZIP 内成员与本地 APK/记录/签名/SHA256SUMS 散列逐一一致，且 APK sums 与身份吻合。ZIP 不解包执行，路径/symlink/重复成员/加密/过大成员均拒绝。APK/ZIP 使用有界流式哈希（每文件1GiB），不是把大型 APK 一次读入内存。签名原文沿用 `verify_signing.verify_certificate`，**不声称离线摘要代替重新执行 apksigner 或可信编译来源证明**。

### 主 APK 许可/源码归档字节门禁

最终入口在完成既有 artifact、成员摘要、SHA256SUMS 和签名原文核对后，调用 `verify_license_bundle.verify_tree(repo_root)` 和 `verify_apk(repo_root, stream, records)`。主 APK 使用既有安全 regular-file 打开规则，并在**同一打开的流**上重新计算 manifest 中的 SHA-256，再回到流首进行 ZIP 检查；不会改查 test APK、固定 assets 合成 ZIP 或另一条未绑定摘要的路径。源码树或 APK 门禁失败统一返回脱敏 `apk_license_bundle_mismatch`、exit 2，不返回可审阅成功。

除现有许可/NOTICE、图片字节与清单之外，`verify_tree` 直接复用 `THIRD_PARTY/proot/artifacts.json` 中四份 `native-sources/{proot,talloc,shmem,eta-native-build}.tgz` 的既有长度/SHA-256。四个记录必须完整且无重复，仓内归档必须匹配；`verify_apk` 复用原有资产入口检查，要求对应 `assets/native-sources/*.tgz` 各有**唯一** ZIP entry、长度和 SHA-256 匹配。维护模式也不能重算或覆盖该 proot 清单。归档不解包、不执行，不增加第二份归档摘要清单。

这是实际提交审阅的主 APK 的有限字节门禁，不是 resolved runtime 依赖闭包、许可兼容性、完整对应源码或可信编译来源证明；不改变来源、作者、授权事实，也不补造缺失许可原文。CI 代码已接入不等于已有真实 APK 运行证据。新增负测仍是本地合成 ZIP 和受控仓内输入，不能拿其成功宣称真实 APK 已核。

新增测试位于 `tests/test_license_bundle.py` 和 `tests/test_acceptance_final_delivery.py`，覆盖四份归档的仓内缺失/空/长度/hash、清单缺项/重复/非法记录，以及同一主 APK 即使重新 pin 全部外层摘要仍因归档缺失/重复/长度/hash 或许可不符而失败、非法 APK ZIP、仓内归档变更、同一已 hash 流调用、CLI 脱敏及 workflow 顺序。本次实现代理不能运行 shell、测试或真实 APK 构建，**测试已写但未运行，真实 APK 也未检查**，须由父集成另行执行并保存证据。

### 生成分片 cloud v2 格式（不引入新成功解析规则）

下面仅把已收集的真实身份与文件引用整理成既有格式；成功仍由共享原文解析器判定。不手填 passed，不从 job success 猜 exit_code。run/jobs 必须由上节指定 attempt 的原始响应提供。

```bash
python3 - "$REVIEW" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
def ref(path):
    return dict(path=path, sha256=hashlib.sha256((root/path).read_bytes()).hexdigest())
def save(path, value):
    (root/path).write_text(json.dumps(value, ensure_ascii=False)+'\n', encoding='utf-8')
    return ref(path)
run = json.loads((root/'check/run.json').read_text())
jobs = json.loads((root/'check/jobs.json').read_text())
assert run['status'] == 'completed'  # 原始终态；不补写/强改 conclusion
assert jobs['total_count'] == len(jobs['jobs'])
keys = ('id','run_attempt','head_sha','repository','workflow_id','head_branch','status','conclusion')
r = {k: run[k] for k in keys}
r['repository'] = {'full_name': run['repository']['full_name']}
out = dict(schema_version=2, run=r, jobs=[], reports=[])
for api in ('26','37.2'):
  for index in range(4):
    selected = [j for j in jobs['jobs'] if j['name'] == f'启动验证 API {api} 分片{index}']
    assert len(selected) == 1
    j = selected[0]
    base = f'cloud/{api}/shard-{index}/'
    identity = dict(shard_index=index, shard_count=4)
    report = dict(schema_version=2, run_id=run['id'], run_attempt=run['run_attempt'],
                  head_sha=run['head_sha'], job_id=j['id'], api=api, **identity,
                  exit_code=int((root/(base+'exit-code.txt')).read_text()),
                  expected_tests=int((root/(base+'instrumentation-targets.txt')).read_text().splitlines()[0]),
                  inventory=ref(base+'instrumentation-shards.json'),
                  instrumentation=ref(base+'platform-tests.txt'), logcat=ref(base+'logcat.txt'))
    rr = save(base+'report.json', report)
    out['reports'].append(dict(api=api, job_id=j['id'], **identity, **rr))
    out['jobs'].append(dict(id=j['id'], run_id=j['run_id'], run_attempt=j['run_attempt'],
        head_sha=j['head_sha'], api=api, name=j['name'], status=j['status'], **identity,
        conclusion=j['conclusion'], report_sha256=rr['sha256']))
save('cloud/snapshot.json', out)
PY
```

上述普通 Python 是受控采集格式整理，不处理恶意路径；离线校验入口才是安全读取/原文规则边界。它不能认证本地采集者，不能证明 cloud 实际使用了这一个 APK。需要真实 artifact/原文审阅来补足信任，不得消除报告中的 `trusted_collection=false`。

## 4. 最终 manifest v1 与真实入口命令

`--repo-root` 始终读取该 checkout 的唯一验收 spec，不接受任意 `--spec`、`--expected-count`、第二份 AC 清单。`business` 是既有 `evidence.py` ledger/policy，所有 artifact 相对同一 evidence-root；计划/coverage/scenarios 输出不是 ledger。

所有引用均为 `{ "path": "根内POSIX相对路径", "sha256": "64位小写散列" }`；外部给定 manifest pin 冻结整条引用链。`cloud`、`business`、`tool` 可为 null，但只能 incomplete。已提供的非法输入不静默当缺失跳过。下列生成命令只 pin **已经存在的真实文件**，不会生成执行结果；缺业务原文不能拿模板填为 passed。

```bash
export BUILD_SHA BUILD_RUN BUILD_ATTEMPT CHECK_SHA CHECK_RUN CHECK_ATTEMPT
# 本次为 full_build；旧包需明确 MODE=old_apk_recheck 并保留原 BUILD_*。
export MODE=full_build
python3 - "$REVIEW" <<'PY'
import hashlib, json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
def ref(path):
    h = hashlib.sha256()
    with (root/path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024*1024), b''): h.update(chunk)
    return dict(path=path, sha256=h.hexdigest())
def identity(prefix):
    return dict(sha=os.environ[prefix+'_SHA'], run_id=int(os.environ[prefix+'_RUN']),
                attempt=int(os.environ[prefix+'_ATTEMPT']))
build, check = identity('BUILD'), identity('CHECK')
delivery = {k: ref(p) for k,p in {
    'run':'build/run.json', 'jobs':'build/jobs.json', 'artifact':'build/artifact.json',
    'archive':'build/artifact.zip', 'record':'delivery/构建记录.json', 'sums':'delivery/SHA256SUMS',
    'signature':'delivery/签名证据.txt',
    'app':'delivery/代鱼-0.1.0-'+build['sha'][:8]+'.apk', 'test':'delivery/tests/platform-test.apk'}.items()}
# 用既有 evidence/template 格式；不调用模板伪造“已执行”。
business = None
if (root/'business/ledger.json').exists() or (root/'business/policy.json').exists():
    business = dict(ledger=ref('business/ledger.json'), policy=ref('business/policy.json'))
tool = None
if (root/'tool/receipt.json').exists() or (root/'tool/unittest.log').exists():
    tool = dict(receipt=ref('tool/receipt.json'), log=ref('tool/unittest.log'))
doc = dict(schema_version=1, mode=os.environ['MODE'], build=build, check=check, delivery=delivery,
           cloud=ref('cloud/snapshot.json') if (root/'cloud/snapshot.json').exists() else None,
           business=business, tool=tool)
(root/'final-input.json').write_text(json.dumps(doc, ensure_ascii=False)+'\n', encoding='utf-8')
PY
# 交给审计者冻结并独立确认 manifest 与其来源；本地计算散列本身不产生信任。
sha256sum "$REVIEW/final-input.json"
```

先独立核定 `MANIFEST_SHA256`，再运行唯一汇总入口；报告保存在 checkout 外，避免报告自身造成 source dirty。既有 source gate 不支持 linked worktree/commondir，不能通过修改它来掩盖不支持情况，应选择受控的独立 checkout。

```bash
python3 -B scripts/acceptance/final_delivery.py \
  --manifest "$REVIEW/final-input.json" --manifest-sha256 "$MANIFEST_SHA256" \
  --repo-root "$REPO_ROOT" --evidence-root "$REVIEW" \
  --build-sha "$BUILD_SHA" --build-run "$BUILD_RUN" --build-attempt "$BUILD_ATTEMPT" \
  --check-sha "$CHECK_SHA" --check-run "$CHECK_RUN" --check-attempt "$CHECK_ATTEMPT" \
  > "$REVIEW/final-report.json"
```

输出保留 build/check 身份、APK/ZIP/metadata 散列、manifest/spec 散列、双 API instrumentation/logcat 散列、tool log 散列和既有 81 项账本审阅报告。没有原文就没有授信。主代理还需独立核验下载来源、双 API job 实际安装的 APK、单测/lint 等交付要求，以及 81 项设备/网络/故障/性能的真实执行语义；该离线入口的 0 不覆盖这些审计责任。

## 5. 负测与执行声明

既有 `tests/test_smoke_boot.py`、`tests/test_acceptance_cloud_report.py` 保留语音构建输入/未知脚本拒复用、严格共享 instrumentation 与 Java+Kotlin 发现测试。本次扩充 `tests/test_acceptance_final_delivery.py` 的 repository/workflow_run 非对象 CLI 负测（exit 2、stdout 单 JSON、stderr 空、无路径/traceback）、合法 failed 账本 exit 2，以及 null/not_run/blocked 仍 exit 1；保留最终身份/pin/attempt/metadata/ZIP/APK/sums 链、缺双 API、缺真实81项、计划冒充 ledger、自描述完整账本不授予最终通过等测试。新增 `tests/test_acceptance_tool_receipt.py` 以 mock 子进程检查原始字节/真实退出码/准确 command 与 CHECK 身份、启动失败/中断无成功 receipt，以及 workflow 接线；测试自身不调用真实 unittest 子命令。

可供主代理授权后运行的本地命令（**本文不声称已运行**）：

```bash
python3 -B -m unittest discover -s tests -p 'test_smoke_boot.py' -v
python3 -B -m unittest discover -s tests -p 'test_acceptance_cloud_report.py' -v
python3 -B -m unittest discover -s tests -p 'test_acceptance_final_delivery.py' -v
python3 -B -m unittest discover -s tests -p 'test_acceptance_tool_receipt.py' -v
bash -n scripts/smoke_boot.sh
```

实现代理本次仅编辑分配工作树，未运行测试、CI、构建、APK 安装或收集命令；实际执行结果与非空 Git 提交由主代理独立核实/执行。本文件所有示例命令均为操作说明，不是已有验收证据。
