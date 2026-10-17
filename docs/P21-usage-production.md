# P21 / P10：权威用量统计的生产消费接线

> 后续v17/Controller/视觉/typed标题及未知coverage修补见 [P21-v17-production-gaps.md](P21-v17-production-gaps.md)。本文“尚未接入”与旧record_source为历史状态；手动压缩仍是明确未覆盖项。

## 范围与证据

任务给定基线为 `af18af3`；本实现代理没有 Git/Shell 权限，未独立核对 HEAD，不能生成提交哈希。实际改动已写入工作树，非仅提案；由主代理审核、定向验证并创建非空提交。

先核对并复用了已有候选，而不是新增计算账本：

- `storage/RecordedUsageReader.kt`：`readAttributed` 已在一个调用者事务中读取最新 `runtime_checkpoints` 和 `SQLiteChildUsageStore.read`，返回主/子/合计及两种 coverage。本次不改 reader 算法。
- `model/usage/SQLiteChildUsageStore.kt`：`child_request_usage` 持久化每个 child request 最新快照，已有任务归父、owner/revision 验证、坏行和预算覆盖统计。本次不改 schema/store。
- `core-domain/.../usage/RecordedUsageSummary.kt`、`AttributedUsage.kt`：已有 nullable 字段小计、独立 request 身份和来源分组算法。本次直接复用，不复制一套加法。
- `data/repository/ModelUsageProjection.kt` 及旧统计 UI：已有旧按模型/时间/轮次计算。本次保留旧算法，但不把默认零值、当前设置或 child profile ID 当成历史模型证据。
- 已有离线证据候选：`AttributedUsageDatabaseTest`、`RecordedUsageReaderDatabaseTest`、`ControllerUsageAttributionTest`、`RetainedConversationDataTest`。本代理仅阅读其夹具和断言，没有运行。

以下路径均相对 `app/src/main/kotlin/app/daiyu/android/`，除非另有说明。

## 已完成的生产消费路径

### 1. 统计 projection 与界面

`RetainedConversationData.stats()` 在原有单个 `DatabaseTransactions` 快照内读取会话数、消息数，并调用一次 `RecordedUsageReader.readAttributed(db)`。

- `UsageStatsSnapshot.attributedUsage` 携带完整主/子 attribution 与 coverage。
- 兼容字段 `recordedUsage` 保持原有 **parentCoverage** 含义，不悄悄改成主子合计，亦不重复读取 checkpoint。
- 新 `RecordedUsageProjection` 只包装现有汇总；不写库、不获取 runtime lease、不恢复运行、不解析当前 ModelSettings。
- `UsageStatsScreen` 保留既有自研 Miuix scaffold、tab、card、heatmap 等外观。生产总览展示当前保留会话/消息、主请求、归父子任务与合计。模型 tab 显示明确的“历史提供方 / 模型未知”桶及同一合计，而不是当前配置模型。
- 每项输入/输出/缓存小计显示“已报告 / 未知”字段覆盖数；显示主回复/子任务的扫描数、缺 checkpoint、缺 Usage、未启动、坏行、预算延后。scanComplete 不等价于用量完整。
- `ModelUsagePane`、旧按模型/日期算法及 `LegacyStatsGrid` 保留但不作为生产真实用量入口；没有无证据填充 modelUsage。

当前持久化的 Usage 合同没有独立历史 provider/model/time、cache creation、reasoning 或金额字段。故不提供推断模型、日期筛选、日均、费用、缓存命中率或 fresh-input 计算。会话热力图仍按原有不可用状态显示，不拿 request 或 checkpoint 时间伪造会话日期。

输入是含缓存读取的规范化输入，不再次把缓存加到输入或总数。报告的 `0` 保留为 `0`；无报告是 `—`/null；缺坏行只计 coverage，不制造一个零 token request。`requestSnapshotCount=0` 仅指读取到的持久 Usage 快照数，不是“真实网络请求数为零”。

### 2. context_usage 显式只读 adapter

调用链：

`ContextRegistry` 的原有可信 scope 校验
→ `ContextService.usage()`
→ `RecordedContextUsageAdapter.read()`
→ 同一个 `RecordedUsageReader.readAttributed()`。

授权校验和读取仍处于已有 `readQuery` 事务快照内。adapter 只 SELECT；没有第三账本、同步任务、补写旧表、runtime acquire/recover 或发模型请求。

- `ContextUsageSnapshot` 取代生产 `UsageTotals` 的零默认展示合同。旧 JSON 主字段保留名称，但未报告字段输出 JSON null；兼容 `source=provider_reported`，另明确 `record_source=runtime_checkpoints+child_request_usage`、`request_count_kind=persisted_usage_snapshots`。
- `input_tokens` / `output_tokens` / `cached_tokens` 是已报告字段小计，附 `field_coverage`；它们可为局部小计，而不是保证完整账单。
- `reported_requests` 是同时有 input/output 的已记录 request 数；只有所有选中快照均报告这两项且非空才提供 `total_tokens`。独立局部字段集合不能直接拼成貌似完整的总量。
- reasoning 没有权威持久字段，始终 null，不沿用旧表的数字或填零。历史模型同样 null。
- conversation 查询返回原始两类 coverage；缺行、坏行、预算限制、未知字段、空快照均保守标 `incomplete`。
- run 查询选中 `record.parentRunId == requestedRunId || record.runId == requestedRunId` 的观测，即父 run 包含其子任务；指定 child run 只选中该 child。
- **run 查询的限制**：现有 reader 预算是 conversation 级，之后才按 run 过滤。本次不重写 child reader 或扩大预算；因此 run 查询永远保守 `incomplete=true`，coverage 标明 `bounded_conversation_scan_filtered_to_run`，`scan_complete=null`、`counts_available=false`。不把 conversation coverage 冒充 run coverage，也不向 run-only grant 泄露兄弟 run 的行数。预算可能使目标 run 没有被读取到；此时数字是 null 而不是零。
- owner 验证来自仍存在的 conversation、message run 或有合法父 message 的 child task。旧 `context_usage` 中一个孤立 receipt 不能给陌生 run 创造授权。额外会话仍需可信 host 的显式 grant，分支/祖先不隐式授权。

旧 `ContextService.recordUsage` / `readUsage`、domain `aggregateUsage` 和旧表/schema/import/export 保留作兼容能力，但不再被生产 context_usage 查询消费，亦不与新结果混加。**未删除、迁移或补写旧数据。**

## 摘要 Usage：新增可用 port，尚未由 Controller 接入

`ProviderSummaryModel` 原先把所有 Usage 当作非正文事件吞掉。本次新增可选的 `BoundSummaryUsagePort`：

- host 显式提供 `ParentSummaryUsage(conversationId, parentRunId, parentMessageId, RequestCheckpoint)`；不能从 summarizer 的 synthetic evidence message/run 反推归父身份。
- `RequestCheckpoint` 必须是已准入的 NEXT/RESUME，不接受 OPEN。Provider adapter 验证 evidence conversation 与 host parent 相符。
- 每个 bound port 只允许一次 open；同一 request/attempt 不会被悄悄用于第二次 POST。
- `TerminalSession` 将权威 `ModelEvent.Usage` 交给 port，不交给摘要正文 sink。相同观测幂等去重；降低数字或改成 null 是整条快照替换，不累加。sink 失败向上传播，不确认成功，不自动重 POST。
- 该 port 的去重状态是进程内状态，**不是 durable receipt 或新账本**；持久幂等和 CAS 必须由 host sink 负责。
- 原有单 lookup 构造器与无 port 行为兼容。`ProductionContextPreparation` 当前仍使用该无 port 构造器，故**本次不能声称生产摘要用量已经入账**。

### 父 Controller / runtime owner 后续必须完成的接缝（本次禁改）

1. 在网络前冻结真实父 conversation/run/message、summary requestId、attemptId、owner；在合法的父 runtime owner 生命周期中完成持久准入。当前压缩可能发生于父回复发送之前，不能伪造一个不存在的 parent checkpoint，必须由 owner 确定可持久化的宿主时机。
2. 每次实际摘要请求构造新的 `BoundSummaryUsagePort(parent, sink)`，传给带 port 的 `ProviderSummaryModelResolver`；必须在请求/attempt 范围内构造，不能把一个 bound port 当成全局 resolver 复用。
3. sink 使用已有权威父 checkpoint 事务/CAS，以 `(request.owner, requestId)` 查找并替换 `RequestUsageCheckpoint`，保留已接受 attemptId；不得另写 `context_usage` 或第三表。合并时不能丢弃已存在的主/摘要请求快照，也不能被后续主 runner 的旧内存副本覆盖。
4. 由 Controller/runtime owner 验证 conversation/run/message/lease/owner/attempt、revision、取消和迟到事件。失败提交不视为入账成功；取消或失败后不得为了统计重发 provider 请求。
5. transport resume 同 request 的新 attempt 需重新由 host 准入并绑定新 port；持久 replay/conflict 规则不能依赖 port 的内存去重。
6. 在上述接缝完成之前，缺失的摘要用量必须继续保持未知，不能把“主 + child 合计”宣称为包含所有 summary/title/外部请求的账单。

本次未改 Main、Controller、ConversationDatabase schema、ModelSettings、工具唯一清单或 CI。

## 新增定向测试与未验证

新增、但本代理未执行：

- `RecordedUsageProjectionTest`：复用主/子汇总，缓存不重复、unknown 模型/时间、null/零和逐字段覆盖、坏缺行/预算文案。
- `RecordedContextUsageAdapterTest`：JSON null 与真零、字段覆盖、不可拼接局部字段总量、run 后代过滤与兄弟 coverage 不泄露。
- `SummaryUsagePortTest`：host request/attempt 归属、同快照幂等、降低/null 替换、写失败不确认、单次 open。
- `ProviderSummaryUsagePortTest`：内存 HTTPS fixture 通过真实 Responses parser/terminal adapter，把 Usage 送 port 而不送正文；写失败不重 POST，错误 conversation 不连接。
- `RecordedUsageConsumptionDatabaseTest`（instrumentation）：真实 SQLite 的 UI/context 共源、只读 total_changes、旧表不混加、resume null 替换、坏/缺行、父子 run/跨会话范围、重开和删除。

没有执行任何构建、单测、设备测试、全量测试、网络或付费调用；没有截图或真机 UI 验证。测试文件的存在不是通过证据。由主代理仅按允许条件做离线定向编译/测试；不要自动启动 connected 全量或联网拉取。

兼容性注意：既有 `ContextServiceTest`、`ContextServiceConsistencyTest`、`ContextRegistryScopeTest` 等部分断言专门要求旧 context_usage 的数字、无报告补零、receipt-only run 授权或 reasoning 数字；这些断言在新生产语义下已过时，尚未迁移或运行，预计不能作为“本次已通过”的证据。旧算法和写入测试仍有独立价值，应由主代理审核后明确区分 legacy receipt API 与权威生产 projection，不应为保住旧断言而回退或混加旧账本。

同样尚未验证：所有新增 Kotlin 的实际编译、Compose 布局在窄屏/大字体下的显示、设备 SQLite 并发、摘要 port 与真实 Controller CAS/恢复/取消竞态，以及 provider 实际计费一致性。提交/测试结论以主代理后续真实结果为准。
