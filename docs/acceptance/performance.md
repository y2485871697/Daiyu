# 物理设备性能证据：离线校验

`scripts/acceptance/performance.py` 只读取本地 JSON 与产物，使用 Python 标准库；不启动进程、不执行输入中的命令、不访问网络、不连接设备，也不采集性能数据。它复用同目录 `evidence.py` 的安全有界读取、拒绝重复键的 JSON 读取、相对路径约束与完整 SHA 格式检查。

## 2026-10-17 用户门槛审批

用户通过 `approve_original_candidates` 明确批准原 AC-072/073 候选值作为正式门槛；固定规则见 `performance-policy-v1.json`，审批绑定当前 vivo V2419A、Android 15、release 和实际 60Hz。原始输入规格保留，不回写历史候选描述。

该审批只冻结验收规则，不生成实测基准或原始样本。准确 release 构建、合成数据集摘要、采集起止事件、原始帧/内存/迁移证据仍需补齐；现有 debug/synthetic 结果不抵扣物理 release 性能。通用离线比较器的基准字节 pin 和完整场景独立复核要求保持。

## 依据与边界

依据 `docs/refactor-input/acceptance.json`：

- **AC-072**：固定 release 设备、60Hz、指定合成数据规模；至少 20 次冷启动/进入/输入/停止，以及 30 秒滚动、流式、IME 场景。包含启动与反馈延迟、帧分布、冻结/ANR 等候选目标；method 明确“候选门槛待 S1 设备基准冻结”。
- **AC-073**：同环境、100MB 合成迁移包、切会话 100 次后稳定回收、内存趋势、迁移响应/进度/完整性，以及诊断开/关额外开销。method 同样明确“候选门槛待基准冻结”。

**脚本不把规格里的候选值自动变成阈值，不自动生成基准，不自行认定冻结。** 门槛、指标覆盖、统计方式、最少样本及环境必须经外部评审，写入基准，再由独立渠道提供文件 SHA-256 pin。脚本只比较所提供的规则；哪怕规则只覆盖一个指标而返回 `passed`，也**不等于 AC-072/073 通过**。规则是否覆盖全部场景、数据集实际内容/规模、60Hz 要求、帧/ANR/趋势/进度/完整性等业务断言仍须独立审查。没有实现从原始帧到卡顿比例、趋势或迁移完整性的推导；只支持每条规则的 `p95`、`min`、`max`。

物理采集身份仍是提交者声明：要求 `physical_device: true`、`device_type: "physical"`、release，明确声明 host/emulator 的输入为 invalid。但工具**没有可信硬件认证、签名验证或采集链认证**，无法识破把模拟器伪称物理设备、伪造样本、伪造型号、伪造冻结审批等行为。SHA 只证明字节一致，不能证明采集真实或审批可信。所有输出固定 `collection_authenticated: false` 和 `business_acceptance: false`。

## 输入契约（schema_version 1）

每层对象按下述字段精确检查，拒绝缺字段/多余字段。数值仅允许 JSON 整数或有限浮点数，拒绝 bool、字符串、null、NaN、Infinity、溢出到无穷的浮点。未限制指标数值正负，因为差值/增量指标可为负；统计单位和合理量纲由基准评审负责。schema_version、min_samples 必须是真整数（不是 bool 或 20.0）。

### 冻结基准 `baseline.json`

下列完全是**合成契约示例，不是已冻结基准或产品性能证据**。示例阈值仅演示格式，不能作为业务默认值。

```json
{
  "schema_version": 1,
  "baseline_version": "reviewed-fixture-v1",
  "frozen": true,
  "build_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "environment": {
    "physical_device": true,
    "device_type": "physical",
    "device_model": "fixture-model",
    "os_version": "fixture-os",
    "refresh_rate_hz": 60,
    "build_type": "release"
  },
  "dataset_sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "rules": [
    {
      "item_id": "AC-072",
      "metric": "fixture_latency",
      "unit": "ms",
      "statistic": "p95",
      "direction": "lte",
      "threshold": 19,
      "min_samples": 20
    }
  ]
}
```

- `build_sha` 是建立基准的参考构建完整 SHA（40 或 64 位小写十六进制），不要求等于候选构建。候选构建另由 CLI `--build-sha` 绑定。
- `baseline_version` 为非空、无控制字符字符串。`frozen` 必须为 bool；false 是 blocked，不是可用门槛。
- 外部 pin 是**整个基准文件原始字节**的 64 位小写 SHA-256，包含空白和换行。不能用 JSON 重序列化后的散列替代。pin 不从 measurement 内自报字段提取；必须通过 `--baseline-sha256` 独立传入。若操作者从不可信输入照抄 pin，则不具备独立信任锚。
- `dataset_sha256` 是外部固定数据集或规范化数据集清单的摘要。该工具仅比较摘要，不读取或验证整套数据集内容；基准审批必须明确摘要算法/对象，及迁移包/诊断模式等测试条件。不同场景、数据集或诊断模式应分别建立基准与测量报告。
- environment 六个字段必填。refresh_rate_hz 为大于零的有限数值。基准和测量环境须完全一致（数值 60 与 60.0 视为同值）；型号/OS 字符串精确匹配，不做归一化或猜测。
- `rules` 非空，最多 2048 条；身份为 `(item_id, metric)`，不可重复。item_id 仅 `AC-072`、`AC-073`，metric 满足 `[A-Za-z0-9][A-Za-z0-9_.-]{0,79}`。unit 是非空无控制字符字符串。
- `direction` 仅 `lte`（observed ≤ threshold）或 `gte`（observed ≥ threshold），**等于门槛算通过**。threshold 为有限数值。`statistic` 仅 `p95`、`min`、`max`。min_samples 为 1..100000 的真整数，必须由外部评审确定，工具没有默认最少样本；规格所要求的至少 20 次等仍由评审核实。

### 候选测量 `measurement.json`

```json
{
  "schema_version": 1,
  "build_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "baseline_sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
  "baseline_version": "reviewed-fixture-v1",
  "environment": {
    "physical_device": true,
    "device_type": "physical",
    "device_model": "fixture-model",
    "os_version": "fixture-os",
    "refresh_rate_hz": 60,
    "build_type": "release"
  },
  "dataset_sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "records": [
    {
      "item_id": "AC-072",
      "metric": "fixture_latency",
      "unit": "ms",
      "samples": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20],
      "artifact": {
        "path": "captures/fixture.txt",
        "sha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      }
    }
  ]
}
```

示例中的 d/e 摘要为占位符，**不是可运行证据**：实际使用须独立计算并替换基准 pin 和真实非空产物摘要，提供实际文件。单元测试会在临时目录生成非空的合成产物，不会伪称设备采集。

- 候选 build_sha 必须精确匹配 CLI 完整 SHA；版本、基准 pin、环境、数据集摘要、指标身份与单位必须匹配基准。缺指标、多指标、错 AC 归属、重复身份都是 invalid，不静默忽略。
- samples 是对应环境/数据集下的**逐次原始数值样本**，不是预计算 p95、均值或通过标志。每记录最多 100000 个，总计最多 200000 个，records 非空且最多 2048 条。空数组允许作为未采到足够样本的 blocked；非有限/错误类型样本即使基准未冻结也 invalid。
- 每条记录须关联一个非空、摘要匹配的实际 capture artifact。该文件用于人工回查采集归属；工具不解析任意格式 capture，也不能认证 capture 中的样本是否与 JSON 一致。同一综合 capture 可被多个指标引用。数据来源与样本对应关系仍由独立审查确认。
- 相对路径只允许正向 `/` 分隔的正常组件；禁止绝对路径、驱动器前缀、反斜杠、NUL、`.`、`..`、空组件。文件及祖先组件均不能为符号链接，只接受常规文件。文件/祖先路径的安全打开沿用 evidence.py；不支持安全 POSIX 打开原语的平台直接 fail closed。
- JSON 各不超过 2 MiB；单产物最多 32 MiB，总读取产物最多 128 MiB（重复引用也计入）。没有通过路径穿越、符号链接、空文件或摘要不匹配的替代证据。
- 无基准运行时，measurement 的 baseline_sha256/baseline_version 可为 null；其余测量、环境与非空产物仍完整校验，结果为 blocked。不接受预计算结果代替 samples。

## 固定 p95 定义

nearest-rank，不做插值：

```text
rank = ceil(95 * n / 100) = (95 * n + 99) // 100
p95 = sorted(samples)[rank - 1]
```

例如 1..20 的 p95 为 19，而不是 19.05；1..21 的 p95 为 20；单个样本为其本身。整数秩计算避免浮点舍入。min/max 直接取原始样本极值。不做单位换算，不把缺失样本补零。

## CLI、输出与退出码

在仓库根目录离线运行（不会执行任何设备动作）：

```text
python3 scripts/acceptance/performance.py \
  --measurement /local/review/measurement.json \
  --baseline /local/review/baseline.json \
  --baseline-sha256 <独立审核的64位小写SHA256> \
  --build-sha <候选完整40或64位小写SHA> \
  --evidence-root /local/review/artifacts
```

没有基准时省略 `--baseline` 和 `--baseline-sha256`，只能 blocked；有基准但缺 pin、pin 不匹配，或仅有 pin 无基准为 invalid。显式 JSON null 基准不是“未提供基准”，为 invalid。

输出 stdout 单个安全 JSON summary，不回显输入路径、命令、型号、版本、指标名、原始样本或异常细节。只输出固定状态/原因代码、经过验证的候选 SHA、固定 AC ID、规则序号、样本数和数值统计。通过 rule_index 对照外部基准查指标。

| exit | status | 意义 |
| --- | --- | --- |
| 0 | passed | 所提供全部冻结规则样本足够，且在门槛内；不是业务 AC 通过 |
| 1 | blocked | 缺基准、未冻结或样本不足，不允许发明门槛 |
| 1 | failed | 至少一项已可比较规则超阈值，即使其他规则 blocked |
| 2 | invalid | 类型/数值/身份/归属/环境/pin/路径/产物/CLI/读取等错误 |

所有已提供的输入先做结构、有限数值、身份、环境和产物校验，坏数据不会被未冻结或样本不足掩盖。valid=true 仅表示输入符合契约；comparison_satisfied=true 仅表示比较通过。无论退出码均不宣称可信采集或业务验收。当前脚本与通用 evidence.py ledger **没有自动互相转换或自动写入通过记录**。

## 离线回归

主代理或本地开发者执行：

```text
python3 -m unittest discover -s tests -p 'test_acceptance_performance.py' -v
python3 -m unittest discover -s tests -p 'test_acceptance_evidence.py' -v
```

覆盖 p95 确定秩/数值等号边界、两个比较方向、负值/大整数、bool/NaN/inf、最少样本/空数组/未冻结、外部 pin、full SHA/版本、AC/指标/单位/数据集/环境归属、非物理设备、非空产物/散列/读取大小、路径穿越/符号链接，以及安全 CLI summary 与 0/1/2。测试均为合成输入，只证明工具行为，不是性能实测。
