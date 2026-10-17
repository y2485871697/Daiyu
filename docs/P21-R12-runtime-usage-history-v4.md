# P21 / R12：runtime-only 用量的静态历史投影

## 范围与调用接线

本次只扩展完整备份和已有用量读取，不扩展 provider / legacy 导入，不改服务、控制器、Activity 或清单。

* 唯一 `ConversationDatabase` helper 升为 **20**。`onCreate` 和 `onUpgrade`（旧版本 1..19，包含已有 >=13 提前返回路径之前）调用 `RecordedUsageHistory.install`。没有另建 helper，没有导入时临时建表。
* `NeutralBackupService.exportSnapshot` 在原来的最终 SQLite 事务内调用 `BackupRuntimeUsageCodec.export`，与 text/tables/child history 同一快照。完整 envelope 升为 **v4**，新增 `runtime_usage_history` v1；现有 typed `context_usage` v3 和 child history v2 不变。
* `validate` 对完整 v1..3 保持原字段集，不要求新节；v4 必须有新节和 `runtime_usage_checkpoints` / `runtime_usage_requests` 数量清单。旧 v1/v2 的 legacy 用量规则仍由原 codec 处理。
* `importPackage` 在原导入事务内 restore 静态表；重复导入、写入后、末尾触发器之后均核对静态回执。任一冲突或损坏使 SQL 事务回滚。
* `RecordedUsageReader.read/readAttributed` 在现有事务内：优先读取 live checkpoint；没有 live checkpoint 时读取对应静态 header/requests。preparation 历史增加 preparation 覆盖数，不伪造 assistant 消息。历史观测与已读 live 主机请求身份冲突时不重复计量。

## 静态数据契约

`recorded_usage_checkpoints` 只保存 message/conversation/run 身份。空 header 保留“有 checkpoint、没有 MAIN 报告”的覆盖事实；完全没有 checkpoint 不会被伪造成一个请求。

`recorded_usage_requests` 保存 kind（CHECKPOINT/PREPARATION）、conversation/run/message、可空物理父消息、request owner/id、attempt、NEXT/RESUME、MAIN/SUMMARY、has_usage 和三项可空 tokens。它不保存运行 owner、process owner、revision、phase、状态、工具、输出、history、supplements、pending、continuation、endpoint 或 payload。request 身份是观测标签，不是可调用的运行权限。

* `usage` 保留 provider 的逐请求快照，包括 0 与全 NULL 报告。
* `usage_requests` 生成 MAIN 缺报观测；旧 checkpoint 缺少该列表时，沿用现有 reader 的 conservative 非 OPEN `request` fallback，不推测其他未记录请求。
* 缺报为 `has_usage=0` 且全部 token NULL；`has_usage=1` 即使全部 token NULL 仍是一次报告。
* preparation 可以没有实际 assistant 消息。未发送的 ADMITTED/READY/TERMINAL 只转成静态 SUMMARY 观测，不转成待恢复工作。
* HANDED+has_usage=1 的 tokens 已在 checkpoint，只保留 checkpoint 那份。父消息仍存在时核对 SUMMARY 身份/报告；父消息已删除时与 live reader 一样不凭空恢复已删除的 tokens。HANDED+has_usage=0 仍保存 SUMMARY 缺报。
* 两表 conversation FK 均级联删除；checkpoint header 及有物理父消息的 request 也随该消息删除。没有父消息的 preparation 随 conversation 删除。

## 完整性与资源边界

导出不调用有 500 消息 / 2 MiB payload 默认预算的报表 reader。它检查完整 runtime 源集合：至多 50,000 checkpoint，单 payload 不超过既有 codec 的 64 MiB，总 payload 不超过 128 MiB；preparation 和静态历史按既有 50,000 行 / 8 MiB 历史元数据界限检查。超限直接失败，不截断成一个看似完整的包。payload 使用既有分块 SQLiteText 读取，避免 CursorWindow 截断。

本地 checkpoint 仅通过现有严格 `RunCheckpointCodec` 解码并核对 SQL/物理消息身份后做 allowlist 投影；坏 payload/坏行/身份冲突失败。新节逐字段严格检查，拒绝未知字段、重复身份、错误 parent/run/conversation、无效 source/operation、浮点或字符串 token、负数、cached > input、缺报却含 token、错误数量。恢复时绝不写 `runtime_checkpoints` 或 `runtime_preparations`。

## 测试产物与限制

复用 `TypedUsageBackupTest`，新增覆盖：

* MAIN/SUMMARY、MAIN 缺报、旧 v5 request fallback、OPEN 不算请求；HANDED 报告不双计、HANDED 缺报保留；未发送 preparation 的零/全 NULL/缺报。
* 大于报表默认 payload 预算的完整投影，以及 501 preparation 不被报表行数预算截断。
* v4 导入/重导出新节稳定、重复导入与重开后 runtime 两表为空；取得 runtime lease 并执行本地恢复后仍为空（导入没有可恢复工作或 transport authority）。
* DB19→20 正式迁移保留 runtime payload；完整 v1、v2、v3 兼容；现有 DB/full 版本 fixture 断言同步到 20/4。
* 字段/身份/数量损坏、重复请求、超限数组、源坏行/坏 payload、目标坏行、晚写触发器污染回执导致事务回滚；父消息及会话级联清理、live 身份冲突不双计。

**实现代理未运行编译、单测或设备测试。** 只通过源码工具修改 detached 工作树，无 shell、分支、push、GUI，也无可用 commit 工具；提交与构建测试由主代理审核执行。上面的测试是新增断言的范围，不是执行通过声明。零恢复 POST 的架构依据是只写无 runtime 权限的静态表；尚未运行网络观测型集成测试，不能把“没有 runtime 行”的源码断言冒称为已经测到 HTTP POST=0。
