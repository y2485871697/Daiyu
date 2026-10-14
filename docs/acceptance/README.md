# 验收证据工具（离线，不执行被记录的命令）

此工具只验证**验收证据账本**的结构、完整SHA绑定、文件完整性与覆盖范围，不实现暂停中的12模块，不构建APK，不改独立daiyu签名，不联网、不推送、不调用shell。它不能替代真实执行、独立审查或设备实测。工具自身单元测试通过也不代表任何一条应用验收通过。

## 输入与边界

- 基准输入是父提供的 `docs/refactor-input/acceptance.json` 精确副本，原路径为 `/workspace/Daiyu-rewrite-spec/acceptance.json`。原路径无法由本实现代理的隔离文件工具读取；收到副本后已核实其 `schema_version: 1`、`cases` 数组和 `AC-001` 至 `AC-081`。`features.json` 仅是规格资料，不用它生成验收结论。
- 默认必须有81个唯一ID；CLI显式接受 `--spec`，不依赖固定外部路径。支持 `cases`（精确规格），也支持独立条目数组及单一 `items` / `criteria` / `acceptance` 数组作为其他输入。其他规格须另行审查完整policy；不要用降级后的副本代替原规格。
- 原规格中的 `status` / `build_sha` / `actual` / `evidence_artifact` 不当作执行结果。另建账本，原输入保持只读。
- policy和账本均绑定**原JSON字节**的SHA256。改变空白也会改变摘要，须审查后显式更新；不自动重新绑定。
- 所有账本、record和调用参数的build SHA必须完全相同，必须是小写完整40位（Git SHA-1）或64位（Git SHA-256）十六进制；拒绝短SHA、`HEAD`、大小写模糊和其他SHA的记录。
- SHA必须由主代理核对实际被测试的干净源码/产物；仅在账本填入SHA不是其真实性证明。若构建含未提交改动，请先阻塞验收，不能套用HEAD的通过结果。

## 开始：只能生成未运行模板

Python 3.9+ 标准库，无额外依赖。当前安全读取要求POSIX `dir_fd`、`O_NOFOLLOW`、`O_DIRECTORY`，缺少这些原语时fail closed，不在Windows悄悄降级；可在受控Linux环境验证导出的设备证据。

下面由主代理在本地运行，`BUILD_SHA` 必须是实际目标完整SHA。创建的目录与重定向路径仅是示意，请选择受控本地位置；**不要覆盖历史账本**：

```sh
python3 scripts/acceptance/template.py \
  --spec docs/refactor-input/acceptance.json --build-sha "$BUILD_SHA" \
  --format policy > /local/acceptance-run/policy.json

python3 scripts/acceptance/template.py \
  --spec docs/refactor-input/acceptance.json --build-sha "$BUILD_SHA" \
  --format ledger > /local/acceptance-run/ledger.json

python3 scripts/acceptance/evidence.py \
  --spec docs/refactor-input/acceptance.json \
  --policy /local/acceptance-run/policy.json \
  --ledger /local/acceptance-run/ledger.json \
  --build-sha "$BUILD_SHA" --evidence-root /local/acceptance-run/artifacts \
  > /local/acceptance-run/summary.json
```

模板包含原规格ID及最低类别，不包含标题、历史结果或通过记录。首次验证应为81条 `not_run`，而不是通过。policy须由负责人检查，必要时增加类别；未知method拒绝生成/验证，须先审查分类代码与测试。更换SHA时使用**新的目录及新模板**，不能把旧record的SHA文本替换后称为新证据。

### 命令退出码

| 退出码 | 意义 |
| --- | --- |
| `0` | 账本有效且全部要求满足；仍须审核证据来源真实性 |
| `1` | 账本有效但有未运行、失败或阻塞；中间报告的正常结果 |
| `2` | 输入/证据无效或无法安全读取；不可当作通过或完整状态报告 |

`template.py` 的0只表示模板生成成功，**绝不是验收通过**。任何生成步骤非0时不得继续使用其stdout当账本。

## 状态与证据类别

账本按 `(item_id, kind)` 记录，不能有同类重复record；缺少required record按 `not_run`。条目汇总优先级为失败、阻塞、未运行、通过。补充类别中的失败/阻塞也不能被required类别通过掩盖。

| 代码 | 中文 | 要求 |
| --- | --- | --- |
| `not_run` | 未运行 | 不得含实际command、exit_code或artifacts |
| `passed` | 通过 | 当前SHA，实际观察、实际argv、整数退出码0、非空证据文件与正确散列 |
| `failed` | 失败 | 同样保留实际执行证据及failure说明；退出码可为0（命令成功但验收断言失败） |
| `blocked` | 阻塞 | blocker说明，不得假装已经执行；后续真实执行时保留原报告另行记录 |

四种类别不能互相替代：

| kind | origin | 必需artifact role | 额外信息 |
| --- | --- | --- | --- |
| `static_review` 静态审查 | `human_review` | `review_report` | 实际审查/检查命令和独立审查记录；只读源码结论不替代设备验收 |
| `platform_automation` 平台自动化 | `execution` | `test_report` | 实际自动化测试断言；报告应明确host合成、Android仿真器或设备目标，不能混淆能力 |
| `real_device` 真机 | `execution` | **同时有** `test_report` 和 `device_capture` | environment: physical_device必须true，device_model及os_version非空；用非敏感型号，不写序列号 |
| `performance` 性能 | `execution` | `performance_measurement` | 同样须物理设备environment，measurement至少metric、unit、threshold、observed |

最低类别来自原规格method的显式映射：合成/自动化要求平台自动化；真机method要求真机；AC-070至少静态+自动化+真机；AC-072/073要求性能；AC-074至少静态+真机；AC-075要求静态；权限矩阵要求平台自动化+真机。AC-022视觉核对额外要求静态报告。policy只能增加类别，不能将这些要求改成单纯静态。对于要求帧记录、权限矩阵、并存/隔离等多个场景的条目，独立审核者仍须检查报告实际覆盖全部given/when/then，不能只检查文件存在。

**候选性能门槛尚待设备基准冻结**：AC-072/073没有基准、样本不足或测量条件不匹配时请记 `blocked`，不可用候选阈值/模型估计填通过。性能文件应包括release完整SHA、设备/OS/刷新率、数据集摘要、采样次数、原始指标、p95计算、阈值及基准版本；本工具验证文件散列与分类，不解析/替你评判所有性能指标。

## 账本格式

顶层：

```json
{
  "schema_version": 1,
  "build_sha": "<实际完整SHA>",
  "spec_sha256": "<精确spec文件字节SHA256>",
  "records": []
}
```

未运行/阻塞record示意（**仅格式，不是真实验收数据**）：

```json
{
  "item_id": "AC-011",
  "kind": "real_device",
  "status": "blocked",
  "build_sha": "<实际完整SHA>",
  "blocker": "未取得授权物理设备",
  "command": null,
  "exit_code": null,
  "artifacts": []
}
```

执行后才可记录以下字段；这里故意不提供伪造通过样例：

- `item_id`、`kind`、`status`、`build_sha`。
- `origin`：只能是真实 `execution` 或静态审查 `human_review`。
- `command`：实际argv数组（不是待执行命令或dispatch命令）。非空，每参数至多2048字符，至多128参数；没有shell求值。若秘密通过argv传递，改变执行流程将其移至秘密文件/环境引用，账本不存秘密字面量；不能改写已执行命令后声称“精确”。
- `exit_code`：真实整数，范围-255至255，不接受布尔值/字符串。
- `actual`：非空、单行实际观察，至多4096字符，敏感内容不进入账本。
- `failure`：失败时必须有非空解释；`blocker`只用于未执行的阻塞。
- `artifacts`：1至16项，每项含 `path`、64位小写 `sha256`、该类别允许的 `role`。文件须在evidence-root内，POSIX相对路径，非空普通文件，无任何父目录/文件symlink，无绝对路径、Windows盘符、反斜线、空组件或 `..`。
- 真机/性能 `environment`：`{"physical_device": true, "device_model": "非敏感型号", "os_version": "版本"}`。
- 性能 `measurement`：四个非空字符串 `metric`、`unit`、`threshold`、`observed`，并在原始测量文件中保留计算过程。

不要将派发ACK、子代理dispatch、心跳、计划、模型自述、摘要文字、只打开设置页面当成功证据。`origin: dispatch/model_self_report/model_assertion` 和同名artifact role一律拒绝；明显的dispatch/echo/printf/true命令也拒绝。**把自述伪装成test_report不可能仅凭摘要检测出来**：role/origin/actual仍是提交者声明，需要主代理独立核对原始runner输出、审查者身份、设备来源和完整SHA。哈希是完整性校验，不是可信签名；同一人伪造文件及匹配哈希不会凭空产生可信验收。本工具不是证据认证服务。

## 汇总与安全

- stdout为JSON，只输出固定错误码、完整SHA/摘要、状态计数、数值 `item_index`、类别及固定未满足原因。不回显标题、given/then、ID、命令、路径、blocker、actual、环境、日志、异常内容或密钥。
- `item_index`为原spec数组1-based位置（本规格第1项对应AC-001）；用本地spec对照未满足项。`unmet`跨条列出全部未满足类别，不止首个失败。无效账本退出2，仅给安全错误码，拒绝输出可能误导的部分通过汇总。
- 记录中仍保留精确命令/退出码/路径/散列供授权人员本地审查，stdout不是原账本导出。即便输入含秘密标记，正常和错误stdout也不回显自由文本。自行使用 `cat` 原始证据、shell tracing、第三方runner、文件名或其他系统日志不在本工具安全承诺内。
- JSON各至多2MiB；单证据至多32MiB；本次总证据读取至多128MiB；record至多2048个。大视频请在真实采集时生成有界的截图/帧记录/测量报告，不能跳过文件散列或任意提高限额迁就无限数据。
- 每级目录通过POSIX no-follow方式打开，拒绝symlink及非普通文件（包括FIFO）；读取长度硬上限。证据根应是受控、只读的本地快照：并发修改会造成散列不符，验证成功仅说明验证时读到的字节一致，不保证之后不变，也不阻止授权人员主动篡改元数据。
- 不执行ledger中的command，不读取进程环境、不自动搬迁历史、不开网络、不修改源码/签名、不输出原始证据。模板和验证器只写stdout，保存位置由主代理选定。

## 独立测试

```sh
python3 -m unittest discover -s tests -p 'test_acceptance_evidence.py' -v
```

测试使用临时目录中的**合成测试夹具**，覆盖81条汇总、精确cases输入/最低分类、无运行模板、不迁移历史、四类证据、散列篡改、短SHA/旧SHA、错误退出码、dispatch和模型自述、真机/性能必需字段、缺项/重复/未知ID、symlink/目录/路径越界/读取限额、重复JSON键/非法输入，以及正常和错误输出不含秘密标记。父提供spec存在时还会做81条全not_run回归，否则明确skip该集成输入测试，不影响独立schema夹具测试。

实现代理无法调用shell，**本次没有由实现代理运行这些测试**。测试结果、真实应用验收、净提交均须由主代理本地执行与核实；本说明不宣称任何应用验收通过。
