# P21 生产摘要 Usage：runtime preparation → 同源 checkpoint 交接

> 后续生产缺口修补及当前限制见 [P21-v17-production-gaps.md](P21-v17-production-gaps.md)。本文v16/helper待接线、handoff清空has_usage等为历史状态；不得据此宣称手动压缩已入账或测试已通过。

## 本实现的边界与证据

按委派先阅读 `docs/P21-usage-production.md`，再逐项核对真实实现，不能把旧文档的 port 声明当作已接线证据。

- `ChatController.kt` 的 `sendPrepared` 路径（原约 1820 行）在真正 `database.send` 之前调用 `coordinator.freeze/prepare`；真实 `conversationId/run/assistant.id` 已分配，但没有父 RUNNING message。
- `ContextService.compress` 在读取和发布 archive 的事务里都调用 `ensureIdle`，所以提前 send 父消息并不是可用的接线方式。
- `ChatRunner.runBody` 原来新建 `RunExecutionOwner` 且 `usage=emptyList()`；其 checkpoint revision 从 1 开始。仅往内存 port 写、或拿历史 run 的 checkpoint，不可能补上该时序。
- `SQLiteRuntimeStore` 持有整个 worker 生命周期的 OS lease，`@Synchronized` 序列化写入；本实现复用它，不增加另一个 helper、锁文件、后台写手或 context_usage 账本。

本工作树中已实现，不只是接口提案：preparation admission/CAS、真实 provider binding、Runner seed、首 checkpoint 同事务交接、后续 usage 保护、取消与恢复终结、同源 reader、显式来源及测试源码。**没有修改 Main、ChatController、唯一 ConversationDatabase helper**；父必须按下文接上两处，才可声称应用生产路径已经启用。本代理没有 Shell/Git/构建/设备执行能力，未独立验证任务给定 HEAD，也没有执行任何测试、构建、网络请求、提交、push 或 APK 安装。

## 当前集成状态校正

本文件下方是原候选基线的交接记录。当前主树唯一helper已由标题功能升级至v16；原15→16建议不再直接执行。此批仅集成组件，真实Controller summary port及helper仍待现有usage-host以16→17接通；坏preparation身份的单行隔离也待该任务修复。下方源码测试均未运行，不是生产完成声明。

## 1. 原候选 helper 迁移计划（历史，不直接执行）

基于实际 `ConversationDatabase.kt` 的 v15，而不是旧文档所说 v14：

1. helper version `15 → 16`。
2. `onUpgrade` 版本守卫改为 `oldVersion in 1..15 && newVersion == 16`。
3. `onCreate` 中，已有 `SQLiteChildUsageStore.installSchema(db)` **之后**增加：

   ```kotlin
   app.daiyu.android.context.RuntimePreparationSchema.install(db)
   ```

4. `onUpgrade` 中，旧版本 `<10` 的建表分支之后、现有 `if (oldVersion >= 13) return` **之前**增加：

   ```kotlin
   if (oldVersion < 16) app.daiyu.android.context.RuntimePreparationSchema.install(db)
   ```

   否则真实 v15 升级会被早退跳过。不要为本任务重跑视频 witness 回填或改写 message.created_at。

`RuntimePreparationSchema.install` 已实现幂等 schema 安装：

- 新 `runtime_preparations`，主键为预分配真实 `message_id`；conversation 外键 `ON DELETE CASCADE`，**没有不存在的 message 外键**。列：conversation/run/message、owner、process_owner、revision、state、request_id、request_owner、attempt_id、has_usage、nullable input/output/cached。
- 一次 preparation 只做一个摘要请求、一个 NEXT attempt；当前摘要 adapter 本来就不支持 resume/retry。此实现没有增加重试入口。request/attempt 不可被旧 port 换绑。
- `child_request_usage` 增 `usage_source TEXT NOT NULL DEFAULT 'MAIN'`。onCreate 的 child schema 本身也已带此列；迁移检查 PRAGMA 后才 ALTER，保留历史 null、真零及所有旧行。MAIN 是旧 child 请求目的，不意味着该行是父请求；归属仍由 UsageOrigin.Child 表达。
- 旧数据不回填摘要、不把 request 前缀当来源、不把估计/receipt 写成权威 Usage。
- checkpoint payload 写 v5，显式保存 usage.source；严格读取旧 v1–v4，旧 v4 来源为 MAIN。数据库 runtime_checkpoints 的 SQL 列不变。

其他旧 instrumentation 仍有硬编码 schema=14/15（例如 AssistantBinding、VideoWitness、Backup、Platform、History、Generation migration 等），本任务没有批量改无关文件；父 bump 版本时应按真实断言含义迁移，不能把所有数字 14 全局替换（有消息条数/字符串位置）。本次 usage 范围内的 `AttributedUsageDatabaseTest` 版本断言已更新为16。

## 2. 父 Controller 的精确调用契约

`SQLiteRuntimeStore` 增加：

```kotlin
internal fun summaryPreparation(
    conversation: ConversationId,
    run: RunId,
    message: MessageId,
    cancellation: ContextPreparationCancellation,
): RuntimeSummaryPreparation
```

创建 handle 只冻结真实 host identity 和随机 owner/request/attempt，不写库、不创建消息、不连接网络。现有发送准备 lane 已有独占 token；保留这个 token，不另设队列。建议将 handle 存在 token/worker 生命周期内，失败与真实 drain 之后才释放。

已有真实 `assistant` 创建后、约原1820行 freeze 前：

```kotlin
val summaryPreparation = runtime.summaryPreparation(
    conversationId, run, assistant.id, token.cancellation
)
// 保存到本次 token/worker 所持资源；不要只留一个局部 port 后丢掉 close 契约。
val frozen = coordinator.freeze(
    conversationId, listOf(user), token.cancellation,
    summaryUsage = summaryPreparation.usagePort,
)
val preparedContext = coordinator.prepare(frozen)
// 继续已有草稿/owner/revision/模型能力检查；不可在这里提前 database.send。
summaryPreparation.seal() // 必须成功，且须在真实 database.send 之前
// 原本的 database.send 原事务、expectedHistoryRevision/draft/owner 检查不变。
// 原本的 RunnerOptions(runtimeStore=runtime) 不变。
```

如果视频分支绕过 prepare 仍调用 freeze，传同一 port 即可；没有实际摘要 open 就不写任何 preparation 行。新会话/无可压缩历史也是如此，不为统计提前 create conversation。Production 配置有可用摘要模型时现在拒绝缺 runtime port 的调用，不再悄悄丢 Usage；父接线之前旧 Controller 会得到明确准备失败，而非错误地宣称已生产入账。

**生命周期细节（重要）：**

- `seal()` 只在摘要/prepare 成功且还没取消时调用一次。ADMITTED→READY 是 durable CAS；无实际摘要请求时它是无 DB 写的检查。
- 本次 handle 必须在任一准备失败、用户取消、send拒绝、worker最终退出时 `close()`。关闭前先按已有 host token fence 取消后续发送；close 不会抹掉已接受 Usage。
- **不能在 send 成功之后、Runner 第一个真实 checkpoint 之前 close**：close 会把 READY 终结，Runner 正确拒绝这次已撤销交接。可保留到整个 Runner worker/drain 的 finally；此时已经 HANDED，close 不改 checkpoint。
- token.cancellation 的 callback 已注册进 handle：取消同步经过同一 runtime writer，把 ADMITTED/READY 改 TERMINAL 并换 owner；register 是一次本次 handle 的生命周期资源，最终 close 注销。DB失败不伪称已持久终结；host 不得因为取消结果不确定重新 POST。
- Runner 挂起等待用户时也保留原资源到最终 drain，不能把普通 suspend 当 worker结束释放 handle。
- 不要在 Controller 中再 merge usage，不要自己 issue/复用执行 owner，不要往 context_usage 补写，不要从 synthetic `summary-input-*` 身份反推 parent。

父若使用测试 fake SummaryModelResolver，需要明确实现带 port 的 resolve 重载；默认 resolver 拒绝传入非空 port，不能把一个不支持 Usage 的生产 resolver 当兼容路径悄悄吞事件。`ControllerContextPreparationTest` 中 fake 已用一个 transport wrapper 显式 claim/observe；真实生产使用 ProviderSummaryModelResolver。

## 3. 持久 authority 与唯一 writer 的交接

### 网络前

`ProviderSummaryModelPort.openWithoutTools` 校验 conversation 后 `usagePort.claim()`；claim 的 beforeClaim 在持有原 runtime lease/monitor 时事务验证：conversation仍存在且无 RUNNING message、真实父 run/message尚未存在、同 conversation 没有另一活跃 preparation。然后落 `ADMITTED`、revision=1 和精确 request/attempt，成功返回之后 provider 才能 open/POST。写失败直接抛出，不连接、不自动重试。

### 观测与替换

provider 的真实 `ModelEvent.Usage` 经 TerminalSession → bound port → 同一个 `SQLitePreparationRuntime`。每次观测（**包括重复观测**）都验证 cancellation、state、conversation/run/message、owner、process owner、request/attempt 和 revision。CAS 更新整条 nullable snapshot，不求和；相同快照不增 revision；降低/真0/null均替换。失败只重放本地 observation 是允许的，不授权重发 POST。

旧 port 的内存去重已移除，否则取消后的相同迟到值会被错误地确认；幂等在数据库端完成。没有 provider Usage 事件时 has_usage=0，reader 显示缺报告，不制造0-token请求。

### Runner 接管

`PreparationRuntimeStore.preparationUsage(initial)` 是只读 seed，并非发执行 owner。Runner在其原生命周期里仍创建新 RunExecutionOwner，但 usage 初始化为 READY preparation 的已接受 SUMMARY 快照。

首真实 `commitCheckpoint(revision=1)` 在原 message+checkpoint事务里再次校验 READY/process/父身份，要求提交的 SUMMARY 精确等于 seed，随后：

1. 持久真实 RunCheckpoint（包含 SUMMARY）；
2. 将 preparation 标 HANDED，并清空 has_usage 和 token列。

任一步失败整个事务回滚。reader在任何单事务快照中只读到 transfer 前或 transfer 后的一份权威快照，没有双计窗口、影子ledger或第二个Usage writer。后续 checkpoint 必须原样保留 SUMMARY；Runner自己携带它，不会用旧空列表覆盖。保留完整 seed 也使 Runner 的 uncertain-commit 读回 equality 合同不被偷偷merge破坏。

取消、终结、交接、换owner/attempt、重开旧 lease 后的迟到 callback 都不能再写；相同迟到快照也拒绝。恢复只把旧 ADMITTED/READY 变 TERMINAL，保留已观察 Usage、换owner/revision；不会重开摘要、POST或接管执行。send之后尚无checkpoint的崩溃仍由原 recoverUncheckpointed 终结父消息，prep Usage保留在本authority中。

## 4. 同源读取、来源与 coverage

`RecordedUsageReader.read/readAttributed` 仍在调用者一个事务中工作，新增 bounded preparation 读取。HANDED 行严格排除；terminal准备即使父send从未成功也保留已耗费的摘要Usage，不为其虚构 assistant message。

- 主/子位置仍是 `UsageOrigin.Main/Child`；请求目的单独是 durable `UsageRequestSource.MAIN/SUMMARY`。父摘要和子摘要归属不再靠request字符串；child store也持久目的且拒绝同一request改目的。
- `ContextUsageSnapshot` 输出 `request_sources.main/child/summary`（相互不重叠的已读取快照数），总数含三类。summary可由父或child来源记录提供，parentRunId始终显式。
- preparation total/inspected/without_usage/unreadable/deferred 独立于 assistant message coverage；不能称成新增父消息数。scanComplete含preparation扫描约束；缺Usage仍使context incomplete。
- run-only查询依然采用原有保守规则，过滤conversation预算快照但不暴露兄弟coverage。合法preparation的真实run可以授权；孤立旧context_usage receipt仍不能授权。
- record_source现在为 `runtime_checkpoints+child_request_usage+runtime_preparations`。旧表不混加，reasoning/model/time未知规则不变。

## 5. 已补/迁移源码测试与未验证

新增：

- `SummaryRuntimeProductionDatabaseTest`：真实ContextService压缩、真实Responses parser/terminal adapter、内存HTTPS connection；在POST边界读取真实SQLite证明ADMITTED存在、父message/checkpoint不存在；Usage先durable，seal/send后真实Runner新owner接管并追加主Usage；后续丢summary被拒绝；取消换owner迟到拒绝；重开send前后终结且POST计数不增；SQLite失败不确认、幂等revision、null/0修正、删除cascade。
- `RuntimePreparationSchemaTest`：旧child列迁移两次幂等、不造历史Usage/null→0。
- `RunnerPreparationUsageTest`：seed早于model.open，每个checkpoint保留summary，新execution owner，主Usage共存，撤销seed不打开主transport。

迁移/补充：

- `ContextServiceTest/ContextServiceConsistencyTest`：legacy receipt落库/冲突/回滚仍验证原API，生产查询改为null且receipt-only run拒绝；直接检查legacy行保留持久性证据，不再把它混入生产查询。
- `ContextRegistryScopeTest`：数字断言改用真实checkpoint；reasoning null、run coverage保守；receipt-only grant拒绝、实际跨conversation run歧义拒绝、只读total_changes不变。
- `ControllerContextPreparationTest`：fake摘要也经过claim/observe；第一条测试验证实际Controller归父SUMMARY查询，不只archive正文。
- `RunCheckpointCodecTest`：v5 roundtrip、旧v1–v4读取、显式SUMMARY跨owner保存、未知source拒绝。
- `SummaryUsagePortTest`：重复观测也送durable sink，source=SUMMARY；`AttributedUsageDatabaseTest`：child摘要显式来源持久与归父、防改目的。

**全部仅源码，没有执行。** 父应统一安排离线定向编译/测试并报告真实命令与结果。特别待验证：所有Kotlin编译、schema v15→v16唯一helper集成、真实Controller资源释放/取消竞态、Android SQLite并发与进程重启、旧fixtures版本断言。没有真机/外部provider计费验证，不保证全量账单完整；title/辅助视觉/外部模型等未接入本任务，不能被摘要补齐后顺带宣称已覆盖。
