# P21 v17：独立手动压缩 Usage 增量

本文件仅说明 `docs/P21-v17-production-gaps.md` 中“手动压缩”最小合同的源码增量，优先于其中“明确未覆盖”的旧状态。任务给定 base 为 `99a348c`；实现代理没有 Shell/Git/运行权限，未独立核验提交身份。**没有编译、运行单元测试或 Android 测试、网络调用、安装、提交或 push。源码 fixture 不是通过证据。** Controller/Main、唯一清单、更新说明和现有备份实现均未修改，由父组合。

## 归属和唯一存储

- `UsageOrigin.ConversationPreparation(conversationId, operationId)` 与显式 `UsageRequestSource.MANUAL_SUMMARY` 表达宿主操作。`AttributedRequestUsage` 的四个 run/message 字段对该归属必须全部为 null；其他归属仍要求非空。手动 source 不能借用 Main/Child，conversation 也必须匹配。
- `ContextManagementWorker.compress` 为一次显式操作分配独立 operation/request/attempt/request-owner。没有后续 Runner、seal、send 或 handoff；不挑历史 run、不借最后 assistant，不持久化 synthetic message，不修改父 checkpoint。
- 同库**既有** `context_usage` 保存请求，新增 `operation_id`、`terminal_reason`；保留原 `request_owner`、`attempt_id`、`process_owner`、`usage_revision`、`has_usage`。`branch_id` 是真实 conversation，手动行的 `run_id`、`parent_message_id` 都是 SQL NULL。
- 仍通过 `SQLiteAdditionalUsageStore.install` 在**同一 v17 安装**完成 fresh 和旧版升级。旧 `run_id NOT NULL` 表在 helper 的原事务中复制重建为 nullable，逐列保留旧 token/null/真零与 typed 元数据；默认 LEGACY 不猜目的。没有新 helper、版本回退或独立 usage 表。该安装面向原有 fresh/1..16→17 路径，并非承诺给已经发布的旧 v17 安装另做无版本迁移。
- conversation 原删除事务按 `context_usage.branch_id` 删除，同样清理手动 operation。worker 的 finally 遇到已经删除的请求不会重建行。

## 真实路径和终结

1. `ContextManagementSummaryProfiles.freeze(expected, usagePort)` 保留原完整 descriptor/revision 的原子 compare-and-capture，生产实现把**同一 port**传给 `ProviderSummaryModelResolver`；没有按 binding 再查当前配置。
2. `ProviderSummaryModelPort.openWithoutTools` 验证 evidence conversation 后 claim。手动 claim 在共享 transaction source 中 INSERT 接纳请求，提交后才允许 provider.open/next 接触 POST；INSERT 被 ABORT 或 IGNORE 都不能准许 POST。只读浏览、freeze、无可压缩前缀不创建请求。接纳是宿主请求元数据，不是“已确认付费 POST”证明。
3. 真实 provider parser 的每个 `ModelEvent.Usage` 经已有 terminal adapter 送达同一 port，typed store 按 request/attempt/owner/process/operation/revision 验证后**整快照替换**。降低、null、真零、完全相同值都不累加；相同值仍先经过 durable 校验。
4. 原 `ContextManagementLease` 的所有权保持到宿主 worker drain。worker 自己的 finally 把已接纳 operation 终结为 `usage_state=TERMINAL`，`terminal_reason=SUCCEEDED/FAILED/CANCELLED`，保留最后成功提交的报告和 has_usage。没有事件时仍是 has_usage=0、null token，不制造 Usage。无实际 claim 时无需制造空 operation 行。
5. 取消后即使相同迟到值也拒绝；finally 成功终结后拒绝所有观察。持久 close 失败也先在内存封闭该 operation，错误传播/附加到原错误，不把 accounting 错误当成重新 POST 的许可。存储故障时不承诺终态已落盘。
6. 原有 runtime recovery 调用的同一 `SQLiteAdditionalUsageStore.recover` 增加手动未知接纳清理；手动 claim 前亦执行同一 manual recovery。只把不同进程 owner 的 ADMITTED 标记 TERMINAL/UNKNOWN 并轮换 request owner，保留报告；没有恢复请求或传输工厂。查询 reader 不执行恢复写入。测试以 departed-process admission 模拟进程边界；仅同进程 helper 重开不会轮换进程 identity。

## Reader、坏行与 coverage

- `RecordedUsageReader` 在原调用者快照事务中读取显式 MANUAL_SUMMARY，使用原 typed-store reader 与剩余 maxUsageRecords/maxMessages 扫描预算；不混入 assistant-message 数，不把 legacy receipt 混成 authority。
- `RecordedUsageStats` / JSON / projection 单列 total/inspected/manual_summary_without_usage/unreadable/deferred。坏行隔离，budget 延后使 scanComplete=false；没有报告不填零。MAIN 和手动缺报独立存在。
- combined 包含一次已报告手动快照，main/child 不包含；domain `conversationPreparation` 和 projection `manualSummary` 提供独立小计。仍复用 `aggregateRecordedUsage` 对独立字段、真零、unknown 和 overflow 的原算法。不是完整账单、token 估算或历史模型归属。
- 新 source 出现在父 checkpoint 时 reader 将其视为坏 checkpoint，而不是把它解释成一个借来的 Main。
- `SQLiteAdditionalUsageStore.read/observe` 的 has_usage 校验均为 **INTEGER 存储类 + getLong 在 0L..1L**；4294967296/4294967297 不得截断成 0/1。非整数、坏状态/revision/identity/operation 都不能冒充缺报或正常报告。SQLiteException/CancellationException 仍传播。父另派的 preparation/runtime revision 问题未在此扩改。

## 新增/更新源码 fixture（全部未运行）

`ManualSummaryProductionDatabaseTest`：真实 worker、真实 revision profile store、真实 resolver/Responses parser、内存 HTTPS 和真实 helper：

- 实际 getOutputStream 前看到 durable ADMITTED、独立 request/attempt/owner/revision/has_usage，SQL NULL run/parent，无 preparation/checkpoint；原 messages 不变。
- 真实 Runner 已有终态 checkpoint 的 payload 保持不变，原 MAIN 与手动报告各自保留。
- parser Usage 入账、成功无 Usage、失败无 Usage、取消保留已接受报告/拒绝迟到、finally 终结；没有付费重试。
- ABORT/IGNORE admission 失败不 POST、不生成 archive；相同 profile 重存使旧 revision 失败；无前缀不生成请求。
- 整快照的 null/真零/幂等/全 null 真实事件、手动 source attribution、JSON request_sources、helper 重开不重放、不同进程未知接纳清理、旧 attempt 拒绝、conversation 删除。
- 坏 presence Long 4294967296/4294967297/负值和 REAL：好行仍保留，坏行不增加 missing，observe 拒绝。另覆盖 AUXILIARY_VISION/TITLE 的超大 INTEGER presence。扫描预算、旧 v16 null/零保留及同 v17 nullable run 安装。

`AttributedUsageTest` 增加无 run/message 的 domain 合同、错误归属拒绝和独立 operation 小计；`RecordedUsageProjectionTest` 增加手动缺报/坏行/预算和真零显示；`RecordedContextUsageAdapterTest` 验证 conversation operation 不进入 run grant 的 Usage/coverage。现有 `ContextManagementWorkerTest` 仅适配新的 freeze port 参数，原假模型不是本增量真实入账证明；`RetainedChildTasksTest` 的原 child 非空 run 断言用 requireNotNull 适配 nullable domain 字段。

父需实际执行 core-domain usage、app projection/provider-summary 单测、上述 Android fixture 和既有 P21RequestCoverage/SummaryRuntimeProduction/ContextManagementWorker 回归，编译 app 与 androidTest 后记录结果。

## 父组合边界与不确定性

本子任务未更改现有备份。父的 typed 元数据必须覆盖 nullable run、operation_id、terminal_reason、请求/attempt/owner/revision/has_usage 与来源；恢复不能把 conversation operation 填成旧 UsageRecord 的非空 run，更不能恢复 POST。备份归属 round-trip 验收由父负责，目前不能宣称已通过。父同时组合 notifications/projection 的展示与外部清单，避免覆盖本增量 manual coverage。最终编译、数据库实际迁移/设备行为、故障时 durability 均尚待执行验证。
