# P21 / R12：typed usage 完整备份修复

## 范围与证据

本变更只修改备份模块和真实 Android helper 测试；不改 `ConversationDatabase` 版本（当前 v18），不另建 usage 账本，不复制可执行 checkpoint。核对依据：

- `model/usage/SQLiteAdditionalUsageStore.kt`：现有 `context_usage` 的 17 列、三个 typed source、admit/observe、手动摘要 NULL run/parent 及 conversation + operation 归属。
- `model/usage/SQLiteChildUsageStore.kt`、`context/RuntimePreparationSchema.kt`：现有 `child_request_usage.usage_source` 支持 MAIN / SUMMARY，旧 schema 默认 MAIN。
- `backup/NeutralBackupService.kt` 原完整包 v2：只导出 7 列 usage，且旧 `UsageRecord` validator 要求非空 run，导致 typed 归属丢失、手动摘要后导出失败。
- `storage/RecordedUsageReader.kt`：主请求的 usage 来自 runtime checkpoint，发送前 preparation 有独立 runtime authority；不是 `context_usage` 的另一份历史副本。

## 协议

新写出的完整包为 `daiyu-neutral-complete` **v3**。嵌套文本备份格式不变。完整包读入继续接受 v1 / v2；`Parsed` 保存版本与列集合，导出、校验、插入和最终/重复回执分别使用正确 schema，不全局替换旧列集合。

`context_usage` v3 列：

```
request_id, run_id, branch_id,
input_tokens, output_tokens, cached_tokens, reasoning_tokens,
usage_source, request_owner, attempt_id, parent_message_id, has_usage,
process_owner, usage_state, usage_revision, operation_id, terminal_reason
```

- LEGACY 保留旧 `UsageRecord` 数值约束，typed 元数据为 NULL；旧包不按 request 前缀、token 值或 run 名猜来源。恢复旧包时沿用真实 helper 的 `LEGACY` 默认值，并在回执中检查全部 17 列。
- TITLE / AUXILIARY_VISION 的 parent 必须是包内**准确的 assistant 消息**，conversation、run、message 三者匹配；operation 必须 NULL。不能只证明存在同名 run。
- MANUAL_SUMMARY 只归属包内 conversation + operation，run、parent 必须 SQL/JSON NULL。operation 在该会话内唯一，不能伪造历史 run。
- 数值只接受 JSON Int / Long，拒绝字符串、浮点、小数和超范围 flag；NULL 与文本 `"null"` 不等价。SQLite 导出拒绝 REAL / BLOB 等错误 storage class。最终/重复 usage 回执按实际 Cursor storage class 和值逐列比较，不依赖 SQL affinity 的 `IS ?`。
- `has_usage=0` 所有 token 必须 NULL；`has_usage=1` 允许真正的零，也允许 provider 确实返回了全 NULL 的 usage 对象。

子历史新写出 **v2**，usage 增加 `usage_source`，只接受 MAIN / SUMMARY。读 v1 时严格接受旧字段集合，并显式默认 MAIN（旧协议唯一语义），不推断 SUMMARY。重导出为 v2。

## 只读恢复与确定性

导出时对 typed ADMITTED 的**内存 JSON 副本**投影：TERMINAL、terminal_reason=UNKNOWN、usage_revision + 1。request owner / attempt / process owner / operation / provider snapshot / presence 均保持原值。源 SQLite 一行不改，不生成随机 owner，不把“未报告”变为零。对不能安全递增的 revision 拒绝导出。

包内只接受已闭合 TERMINAL typed 历史；原本 terminal 的原因和值保持原样。恢复只写原有 usage 历史、子任务终态及现有备份只读历史，不创建 runtime checkpoint、preparation、lease 或网络执行许可。恢复再导出不再投影/递增，usage 与 child history 稳定。

首次导入的所有 SQL（含 text、typed usage、child usage、marker）仍在同一 helper 事务内。最后 marker trigger 也可能篡改历史：在 owner 资产批次发布之前先复核 typed/child 回执，最终返回前再次完整复核；任何不符回滚，不发布会话/用量/导入标记。既有不可变内容文件的 orphan 容错设计不改变：事务失败可能留下无可见引用的 blob，不宣称文件系统和 SQLite 存在跨介质原子提交。

## 明确未补齐的历史统计边界

本修复**不是全部统计历史已经可迁移**的声明：

- 主 MAIN / 自动 SUMMARY 请求的 provider usage 若只存在 `runtime_checkpoints.payload`，不在现有 context usage 历史内，本完整备份仍不导出这部分；恢复后的主请求统计可能缺失。
- 仅存在 `runtime_preparations` 的发送前请求观察，包括未被接受为消息的终态 preparation，也不在本包中，恢复后统计可能缺失。
- 为补这些统计而复制可执行 checkpoint / preparation 会恢复运行 authority，不能这样补洞。本变更不添加平行账本；需要后续独立只读历史协议及精确归属设计。
- 旧 v1 / v2 已丢失的 TITLE / 视觉来源无法逆向恢复。它们继续按 LEGACY 保留，不冒充 typed。

## 测试与执行状态

新增 `app/src/androidTest/kotlin/app/daiyu/android/backup/TypedUsageBackupTest.kt`，使用唯一真实 v18 `ConversationDatabase`、生产 `SQLiteAdditionalUsageStore.admit/observe` 和真实子历史表：

1. TITLE 真零、视觉 present 全 NULL、成功 manual、三种 ADMITTED 的闭合投影、源不变、reopen + duplicate + re-export、子 MAIN/SUMMARY roundtrip、无 runtime checkpoint/preparation。
2. 旧完整包 v1 / v2 的 7 列 usage、子历史 v1 默认 MAIN，重导出 LEGACY 而非猜来源。
3. 父消息/run/手动归属、source、presence、revision、token 整数与 storage class 篡改拒绝；目标保持不变。
4. 重复导入逐列 typed 回执校验；marker trigger 在最后 SQL 写入时破坏 typed 回执，验证 SQL 全回滚，去掉 trigger 后可成功重试。

另将原 `NeutralBackupServiceTest` 的两个 fresh 和一个 reopen 当前 helper 版本断言定向更新为 18；不改历史输入版本。`ExtendedNeutralBackupTest` 的新导出完整包版本断言更新为 3。

**执行状态：未编译、未运行 instrumentation、未运行构建。实现代理无 Shell / 编译 / Android 执行工具。以上是新增测试源码覆盖面，不是测试通过声明。父代理应独立检查并运行相关 Android tests。**

审查 artifact 为 detached 工作树中的修改及新增源码（特别注意包含新 `BackupUsageCodec.kt`、`TypedUsageBackupTest.kt` 与本文）；实现代理未创建分支、未 push、未改统一更新文档。
