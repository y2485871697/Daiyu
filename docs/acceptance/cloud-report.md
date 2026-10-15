# 云端回归离线报告校验（20号）

## 边界与结论

`scripts/acceptance/cloud_report.py` **只读取本地文件**，不下载 GitHub、不调度 workflow、不执行 snapshot 中的命令、不构建或安装 APK，也不修改任何历史 SHA。复用 `scripts/acceptance/evidence.py` 的 `load_json`、`bounded_read`、`artifact_path`、完整 SHA 与安全 CLI 接口；不修改原工具或既有云端证据校验器。

校验目标固定为仓库 `y2485871697/Daiyu`、workflow ID `383377276`、分支 `main`，以及同一 run / attempt / 完整 SHA 的 API **26 和 37.2** 矩阵。预期 SHA、run ID、attempt 必须由调用者独立通过 CLI 给出；不能从 snapshot 自推预期值。运行校验器不要求 checkout 指向目标 SHA（可以是 detached runtime），不会把旧结果重新绑定到当前 HEAD。

输出的含义：

- `valid=true`：输入结构及已提供的证据相互一致，不等于可信采集。
- `cloud_regression_satisfied=true`：上述固定 API 矩阵完整，并且本地证据满足本工具的保守成功规则。
- `trusted_collection=false`：**永远如此**。离线 JSON、原文、摘要都可能由同一个人伪造；外部 SHA-256 pin 只冻结所审字节，不是 GitHub 签名或采集认证。
- `business_satisfied=false`、`acceptance_items_credited=0`：**永远如此**。不为 81 AC 整体或任一业务项授信，也不证明生产接线、真实网络、真机端到端、性能或 APK 身份。`expected_tests` 及测试数也只是已 pin 输入的自洽性检查，不证明源码中的测试全集。

本文件中的数据和测试均为合成示例，不是一次真实云端执行的证明。

## CLI

调用形式（占位符必须由外部审阅来源替换，不要照抄为执行证据）：

```text
python3 -B scripts/acceptance/cloud_report.py \
  --snapshot /local/review/snapshot.json \
  --snapshot-sha256 <外部给定的64位小写SHA256> \
  --build-sha <预期40或64位小写完整提交SHA> \
  --run-id <正整数runID> \
  --attempt <正整数attempt> \
  --evidence-root /local/review/evidence
```

不要先从待审 snapshot 计算 pin 再声称拥有独立信任。工具不自动获取 pin，不读取 git HEAD，不接受短 SHA。它只向 stdout 输出白名单 JSON，不回显输入路径、命令、日志原文或异常详情。

退出码：`0` 表示本地云端回归矩阵自洽完整；`1` 表示输入自洽但缺 API；`2` 表示输入不可读、不安全、身份不符、失败/冲突或其他非法输入。退出码 0 **不是业务验收通过**。错误输出也明确含两个 satisfied=false、`trusted_collection=false` 和 `acceptance_items_credited=0`。失败、取消、超时、未知 conclusion、进行中及跳过状态不能产生 passed。

## 精确输入格式 v1

这是归一化的离线审阅格式，**不是原始 GitHub REST 响应的任意子集**。所有对象仅接受下列键，未知键拒绝（包括 `command` 或自报 `passed`）。GitHub 采集与归一化必须在本工具以外完成；本工具不为该过程背书。数字字段必须是 JSON integer，不能是 boolean、float 或 string。

### snapshot.json

```json
{
  "schema_version": 1,
  "run": {
    "id": 42,
    "run_attempt": 1,
    "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "repository": {"full_name": "y2485871697/Daiyu"},
    "workflow_id": 383377276,
    "head_branch": "main",
    "status": "completed",
    "conclusion": "success"
  },
  "jobs": [
    {
      "id": 101,
      "run_id": 42,
      "run_attempt": 1,
      "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "api": "26",
      "name": "启动验证 API 26",
      "status": "completed",
      "conclusion": "success",
      "report_sha256": "<26/report.json的SHA256>"
    },
    {
      "id": 102,
      "run_id": 42,
      "run_attempt": 1,
      "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "api": "37.2",
      "name": "启动验证 API 37.2",
      "status": "completed",
      "conclusion": "success",
      "report_sha256": "<37.2/report.json的SHA256>"
    }
  ],
  "reports": [
    {"api": "26", "job_id": 101, "path": "26/report.json", "sha256": "<26/report.json的SHA256>"},
    {"api": "37.2", "job_id": 102, "path": "37.2/report.json", "sha256": "<37.2/report.json的SHA256>"}
  ]
}
```

摘要占位符不是合法摘要。真实摘要必须为 64 位小写十六进制，无 `sha256:` 前缀。run 和每个 job 必须 terminal `completed/success`。job ID 不重复，API 字符串只允许 `"26"` 和 `"37.2"`，job 名称必须与固定 workflow 的 `启动验证 API <api>` 完全一致。每个 job 必须有唯一同 API / job ID 的 report，report 引用的摘要还必须与 job 的 `report_sha256` 相同。

`jobs` 只包含待验证的两个 instrumentation job，不包含 build/checks 等其他作业。两个数组最多各 2 项，不允许额外 API 或重复项。缺一个完整 job/report 对时，结果是 valid 但矩阵不满足；只有 job 没有 report 或反之属于不一致，直接拒绝。

### 每 API 的 report.json

例如 `26/report.json`：

```json
{
  "schema_version": 1,
  "run_id": 42,
  "run_attempt": 1,
  "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "job_id": 101,
  "api": "26",
  "exit_code": 0,
  "expected_tests": 2,
  "instrumentation": {"path": "26/platform-tests.txt", "sha256": "<原始instrumentation字节SHA256>"},
  "logcat": {"path": "26/logcat.txt", "sha256": "<原始logcat字节SHA256>"}
}
```

API37.2 使用自己的 job ID、API 与文件。所有路径都相对于 `--evidence-root`，不是相对于 report 所在目录。身份字段在 snapshot run、job、report 之间以及与 CLI 预期逐项比较；跨 run、跨 attempt、跨 SHA、跨 job、API 交换不能复用。`exit_code` 必须为整数 0，`expected_tests` 必须是 1 至 999999999 的整数，其他 ID / attempt 为 1 至 2^63-1 的整数。

哈希链为：**外部 pin → snapshot 原始字节 → job/report 摘要一致 → 每 API report 原始字节 → instrumentation 与 logcat 原始字节**。每次调用重新打开并重新哈希，不接受缓存的 passed 标志，不补写或重写 SHA，不自动解包或解释压缩包。

## 原文成功与否定规则

`platform-tests.txt` 对应既有 `scripts/smoke_boot.sh` 中 `am instrument -w` 的原始输出（workflow 上传启动证据中的该文件）。必须是 UTF-8 文本，允许 CRLF 换行；拒绝 ANSI/控制字符、非 UTF-8、空文本。

- 必须有**且只有一条**整行 `OK (N tests)`（或 `OK (1 test)`），N 必须等于该报告的 `expected_tests`，不得为零。带前后缀、缩进、第二条 OK 或其他含独立 OK 单词的行均拒绝，不能把多次执行拼接成一次通过。
- 原文或 logcat 含 failure/failed、crash、fatal、exception、error、abort、timeout/timed out、cancel、skipped/ignored、`shortMsg=` 等否定信号时拒绝。Java `AssertionError` 等 Error/Exception 类型同样拒绝。匹配不区分大小写。
- 任何包含 `INSTRUMENTATION_` 标记的原文行都必须是严格从第 0 列开始的已支持协议行；空格、tab、日志前缀及同一行内嵌的第二个标记均拒绝，不 trim、不去前缀、不归一化为合法协议。
- 如原文包含 `INSTRUMENTATION_CODE:`，只能有一条且必须为 `-1`；`INSTRUMENTATION_STATUS_CODE:` 只能是 0 或 1；`numtests` 必须等于 N；`current` 必须为 1..N。`INSTRUMENTATION_RESULT` 只允许 stream 字段，未知 instrumentation 行拒绝。
- 终端 code 可省略，以兼容已有仅含 runner summary 的原文；但已给出的 code 不能与 OK 冲突。instrumentation 和 logcat 均须提交并匹配摘要，不能只信 job 的 success 或 JSON 内的 exit_code。

这是刻意保守的原文筛选器，不是通用 Android 日志语义分析器：例如 `errors: 0` 或无害日志中提到 exception 也可能拒绝。不能为了通过校验而删改原文；需要另行审阅并升级格式/策略，不能把拒绝解释为通过。

## 文件安全与限额

- 全部文件通过既有 evidence 的安全接口读取：每个路径分量均不跟随 symlink，拒绝目录/FIFO/设备等非普通文件，安全 POSIX 原语不可用时 fail closed。
- artifact 引用只允许根内 POSIX 相对路径；拒绝绝对路径、`..`、`.`、空分量、反斜线、NUL、Windows drive 前缀，以及超过 512 字符的路径。父目录 symlink 也拒绝。
- 不同 report/原文引用不能重复使用同一路径；跨 API 不得共享一份原文来补齐矩阵。硬链接/相同字节的独立文件不构成可信采集证明，工具不声称排除伪造。
- snapshot 及每份 JSON 上限 2 MiB；每份原文上限 4 MiB；两个 API 报告和四份原文合计按 JSON 上限保守计费不超过 20 MiB（另加 snapshot 上限 2 MiB）。不解压文件，不递归发现文件。
- JSON 拒绝重复键、NaN/Infinity 与非法编码。输入错误仅返回固定诊断码，不泄露文件名、原文或参数。

## 维护与本地测试

新增 `tests/test_acceptance_cloud_report.py` 的合成负测覆盖完整矩阵/缺 API、精确身份、严格 int 非 bool、未知字段/API、失败取消超时、重复或矛盾 OK、带空格/tab/日志前缀的协议状态/终端码/计数/未知标记、crash 与 logcat、各层摘要、CLI 退出码和脱敏、路径越界、symlink/非普通文件、有界读取和非法 JSON。另通过本地 Python subprocess 调用实际文件入口，覆盖退出码 0/1/2、失败 JSON 边界与拒绝报告内 command；不访问网络、不执行报告命令。与既有 `tests/test_cloud_evidence.py` 并存，后者是另一套合作检查证据格式，不互相替代。

可由主代理在授权的本地环境运行：

```text
python3 -B -m unittest discover -s tests -p 'test_acceptance_cloud_report.py' -v
python3 -B -m unittest discover -s tests -q
```

实现代理不运行 shell、测试、CI 或外部操作；是否实际通过测试，以主代理独立执行结果为准。上述命令本身不是已执行记录。
