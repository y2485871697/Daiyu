# 81项验收证据路由（20号，离线生成）

`coverage.py` 将规格中的 **AC-001至AC-081** 映射到编号责任人、最低证据类别和可检验缺口。输出是**某次完整SHA下尚未执行的证据收集计划**，不是第二份功能状态清单，也不是执行结果账本。功能完成状态仍只在 `docs/重构清单.md`；此工具不修改它。

## 使用与信任边界

Python 3.9+、标准库；复用 `evidence.py` 的安全JSON读取、完整SHA检查、`spec_items`、`spec_floors`、证据类别和artifact role，不修改原模块。`template.py` 继续负责生成policy/ledger，`evidence.py` 继续负责验证真实证据；本工具既不读取ledger，也不导入旧passed，不能代替这两个接口。

由主代理在受控本地环境运行（下面是命令格式，不是已执行记录）：

```sh
python3 scripts/acceptance/coverage.py \
  --spec docs/refactor-input/acceptance.json \
  --build-sha "$BUILD_SHA" \
  --expected-spec-sha256 "$REVIEWED_SPEC_SHA256"

python3 -m unittest discover -s tests -p 'test_acceptance_coverage.py' -v
```

- `BUILD_SHA` 必须是实际待验收源码/产物的小写完整40或64位十六进制SHA，不接受HEAD、短SHA、大小写模糊。工具只检查格式和绑定，不调用Git、不证明构建干净或SHA真实性；主代理须独立核实。
- `REVIEWED_SPEC_SHA256` 必须是此前经独立审查、可信保存的**精确原文件字节**SHA256（小写64位）；也可用别名 `--spec-sha256`。参数必填，不能仅把当前可疑文件现算摘要传回去当作“已审查”。改变空白、换行或任意一个字节均与旧预期不符，退出2，不输出部分路由。规格有意更新后须重新审查摘要与映射。
- 文件安全边界沿用 `load_json`：JSON最多2MiB、拒绝重复键及 `NaN`/`Infinity`/`-Infinity` 字面量（不保证拒绝指数溢出等所有非有限数值）、拒绝非普通文件及任一级symlink，依赖POSIX no-follow/dir_fd原语；不支持安全读取的平台fail closed。
- 只接受 `schema_version` 为整数1的 `cases` 规格，精确81项，ID集合精确为AC-001至AC-081。拒绝未知、重复、缺失、非规范ID、多种条目数组混用、未知method、feature归属变化。允许数组调序，输出仍按规范ID排序。没有 `--expected-count` 降级开关。
- 本工具只读spec、只写stdout，不执行规格中的命令、不开网络、不调用shell/Git/adb/构建、不读取环境秘密，也不安装APK或操作GUI、生产数据。输出保存位置及是否落盘由主代理决定，禁止覆盖旧证据。

### 退出码

| 退出码 | 含义 |
| --- | --- |
| 0 | 成功生成有效路由计划；**不是业务pass**，即使仍有owner确认缺口也不是验收结果 |
| 2 | 参数、规格、安全读取或摘要无效；只有安全错误码，不得当作部分有效计划 |

与 `evidence.py` 的退出码语义不同：本工具不执行、不验证结果，因此不返回“真实证据全部满足”。工具单测通过也不代表应用81项通过。

## 显式编号映射

来源：`docs/并行分工.md` 固定编号合同、`docs/refactor-input/acceptance.json` 的逐项业务描述，以及回收的只读审查owner提案。路由逐AC显式写在 `coverage.py`，没有外置owners文件，也**不把feature_id的数字当owner**。例如F01拆到15/16号，F20到12号，F31拆到1/19号。代码保留逐项feature身份校验，防止原ID被挪给另一功能后无声沿用旧owner。

| owner | 职责类别 | AC编号（省略AC前缀） |
| --- | --- | --- |
| 01 | 总集成 | 017、070、074 |
| 02 | 生产实现 | 008–010、027–030 |
| 03 | 生产实现 | 014–016 |
| 04 | 生产实现 | 005–007 |
| 05 | 生产实现 | 033–035、060–061 |
| 06 | 生产实现 | 012–013、023–026、031–032、062 |
| 07 | 生产实现 | 011、036–037、077、081 |
| 08 | 生产实现 | 038–043、078–079 |
| 09 | 生产实现 | 046–047、080 |
| 10 | 生产实现 | 044–045 |
| 11 | 生产实现 | 058–059 |
| 12 | 生产实现 | 053–057 |
| 13 | 生产实现 | 048–049 |
| 14 | 生产实现 | 063–069 |
| 15 | 生产实现 | 004、076 |
| 16 | 生产实现 | 001–003、018–022 |
| 17 | 生产实现 | 071 |
| 18 | 生产实现 | 050–052 |
| 19 | 来源审查（provenance，不改业务） | 075 |
| 20 | 独立验收 | 072–073 |

每项均另有 `integration_owner: 1`、`acceptance_owner: 20`；只有owner为2–18时 `production_owner` 才非空。19号的 `owner_role` 及owner汇总 `role` 均为 `provenance`，AC-075仍路由至19号，负责来源审查而非业务实现。1号/19号/20号的路由责任不能冒充生产模块实现归属。跨层用例仍需相关模块协作，主owner不是排他实现授权。

**AC-062→06由1号在`docs/并行分工.md` v1.1补充决定确认**：06生产修复主责、01总集成、20独立验收。输出为`mapping_status: configured`，移除该项`owner_confirmation_required`，但原最低证据类别和全部`missing_record`保留，状态仍not_run。确认不代表MCP实现、跨包API冻结、执行授权或业务通过；不扩大公共文件写入权。旧报告维持原待确认状态，不改写历史。其他项的`configured`也仅表示责任路由配置存在。

## required kinds与可检验缺口

最低类别**严格复用 `spec_floors`**，不自建关键词分类、不削弱类别、不把静态审查当真机。当前输出等于原spec最低要求；若未来审查加严，只能增加，不能降低。AC-021的长列表进一步性能/设备场景加严属于建议，不能伪称已实现或已实测；本工具的 `platform_automation` / `real_device` 是原method所要求的待收集类别，不是已有通过证据。

| kind | 当前缺口 | 后续artifact role |
| --- | --- | --- |
| static_review | missing_record | review_report |
| platform_automation | missing_record | test_report |
| real_device | missing_record | test_report及device_capture（两者同时） |
| performance | missing_record | performance_measurement |

每个 `(AC, required kind)` 都有一条 `unmet`，状态恒为 `not_run`；其中列出role以便检验后续证据是否齐全。同一AC可以要求多个证据类别，故`unmet`长度不是81，也不是执行次数。缺口不是基于搜索仓库或已有报告作出的产品失败结论，而是“本计划没有导入执行记录”的确定性声明。

后续真实执行还须按 `docs/acceptance/README.md` 和 `evidence.py` 记录当前完整SHA、精确命令、实际退出码、观察及非空散列产物；真机须真实物理设备环境，性能须测量字段和已冻结基准。API26+37.2、真实网络、OEM、强杀/重启、跨App grant及权限场景不能由host合成测试替代。AC-072/073候选阈值未冻结时不得据计划称性能通过；付费媒体/未知副作用不得为补证据自动重放。

## 输出与最小披露

- 顶层含 `report_type: evidence_routing_plan`、精确 `spec_sha256`、完整 `build_sha`、`tests_executed: false`、`status: not_run`。
- `counts` 固定为81条not_run，passed/failed/blocked均0；不复制spec中的历史status、actual、command、build_sha或产物声明。
- `owners` 列出1–20号的职责、ID列表及数量；`items` 每AC一条，包含owner类别、确认状态和required kinds；`unmet` 列出全部类别/确认缺口。
- 只输出规范ID、数字owner、经过白名单的类别/role/固定诊断码和SHA。正常/错误摘要均不回显标题、given/when/then、自由文本、方法原文、路径、命令、秘密或异常内容。规范ID可安全回查本地规格，不是用户可注入的自由文本。
- 摘要绑定只证明所读字节匹配预期，不是签名、不证明spec来源可信、不证明未来文件不变。

## 测试与交付限制

`tests/test_acceptance_coverage.py` 包含精确81项、全部编号边界、同feature拆分、1/19/20非生产角色及19号provenance汇总、MCP责任确认不授予业务通过、与template/policy和 `spec_floors` 一致性、规范ID/版本（含浮点1.0拒绝）/数量/feature/method错误、完整SHA、摘要改字节、重复JSON键/非法JSON/大小限制/symlink、无历史pass导入、摘要不泄露及无命令执行/网络调用的隔离单测；并对仓库真实spec做全81条not_run回归。

实现代理仅通过隔离文件接口修改这份文档、工具与测试；无shell权限，**未运行这些测试**，由主代理执行并核查结果。不新建分支、不push、不触发CI，不改生产模块、构建、清单或原证据工具；此交付不声明任何真实业务验收完成。
