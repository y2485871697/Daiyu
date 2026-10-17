> 本文为v17阶段记录。当前唯一helper为19，支持1..18→19；成功打开后的目标版本应为19，旧fixture来源/回滚版本保持。手动摘要当前已接独立usage operation，原“未接线”描述仅为历史，不作当前待办；仍须统一运行验证。

# P21 v17：已实现的生产缺口修补与明确未验证项

本文件补充并优先于 `P21-summary-production-completion.md` 和 `P21-usage-production.md` 中旧的“父尚须接线”说明。输入候选为任务给定的 `5a81777`；后续主树 `9a03d1b` / context host `6e08e1b` 由父提供，本代理没有 Git/Shell 权限，未独立核对提交、detached 状态或生成提交。只修改分配工作树；**没有构建、运行测试、网络调用、安装 APK、提交或 push**。请父合并到已保全 base 后创建非空提交，不单独发布缺接缝的 base。

## 本批实际实现（不是已运行证明）

### 1. 唯一 helper v16 → v17

`storage/ConversationDatabase.kt` 仍是唯一 helper：版本17、升级守卫1..16→17。fresh 在 child usage schema 后安装 `RuntimePreparationSchema`；upgrade 在旧child表存在后、`oldVersion >= 13` 早退前安装。标题原v16 schema安装保留，不重跑旧视频witness回填或推断message时间。

旧 `child_request_usage` 缺 `usage_source` 时按PRAGMA增列，保留null、真零及旧行。另由唯一helper安装下述既有 `context_usage` 的typed扩展列；没有新helper、OS lease或后台同步任务。

### 2. 原Controller token → preparation → Runner

`ChatController.sendPrepared` 在真实conversation/run/assistant身份分配后创建同一runtime的 `summaryPreparation`，freeze传其port，准备/能力/草稿检查仍沿旧路径；真实database.send前seal。原send事务、attempt gate、UNKNOWN不重放合同未替换。

`Preparation` 持有未接纳handle；`RunResources` 接受后持有同一handle并标记转移。`finishPreparation` 只close未转移handle，不撤销已接受的READY。终结worker/drain（非WAITING_USER slice）关闭RunResources的handle；首checkpoint仍由原runtime事务完成handoff。失败close不删除已接受Usage。

### 3. 按实际admission的目的保留缺报，不制造Usage

`ExecutionControl.RunCheckpoint.usageRequests` 是MAIN请求admission元数据，不是额外token算法；Runner在原NEXT/RESUME准入边界保留它。这样先前轮次缺报不再被后续报告或SUMMARY快照遮掉。codec写v6，严格读v1–v5原字段；旧版本无元数据时只用已存在的当前非OPEN request作保守coverage，不补造历史请求。恢复保留元数据，不授权重发。

自动摘要handoff后保留 `runtime_preparations.has_usage` 的**报告存在位**，清空token列：

- HANDED且已报告：token只从checkpoint计一次；preparation行不重复读入。
- HANDED且缺报：保留摘要缺报coverage，即使MAIN后来报告也不消失。
- 未handoff的终结preparation：原Usage仍保留。

reader的MAIN缺报、preparation缺报分别计数；SUMMARY非空不等于MAIN报告。没有Usage事件不会创建零值或全null的伪Usage快照。现有 `aggregateRecordedUsage/aggregateAttributedUsage` 算法未改。

### 4. 辅助视觉和标题使用既有context_usage的显式typed请求

`model/usage/SQLiteAdditionalUsageStore.kt` 只扩展**既有** `context_usage`：`usage_source`默认LEGACY，另有parent_message_id、request_owner、attempt_id、process_owner、usage_state、usage_revision、has_usage。旧行不按request前缀推断TITLE，不升格成新authority。

- 辅助视觉：Controller把真实父assistant传给同一 `SQLiteRuntimeStore.auxiliaryUsage` 工厂；`AuxiliaryVisionModelAdapter` 仅在实际视觉调用时、transport访问前admit。cache hit和配置冻结不建请求行。真实parser的Usage立即durable整快照替换；请求/attempt/owner、revision、终态及取消都受检查；缺报保留has_usage=0。取消和finally结束该attempt，失败的durable close也不阻止disconnect。恢复只终结未知admission，不产生POST。
- 标题：原 `ParentTitleUsagePort` 增加兼容的begin/observe钩子。真实TitleJob有自己的request/attempt/owner；next之前durable admit，Usage事件立即observe；标题CAS成功与最终receipt仍在原同库事务提交，失败/失效请求走原finally终结。可以在父checkpoint终结后追加标题Usage，**不写父终态checkpoint**。重复同receipt不再加请求，冲突终态拒绝。
- 权威reader只读显式AUXILIARY_VISION/TITLE行，与checkpoint/preparation/child共用原调用者快照事务和已报告快照预算。旧LEGACY行仍可导入/导出、走兼容API，但不与typed结果混加。

`RecordedContextUsageAdapter` 的request_sources增加auxiliary_vision/title；record_source为 `runtime_checkpoints+child_request_usage+runtime_preparations+typed_context_usage`。UI projection增加MAIN缺报、摘要准备、视觉、标题的检查/缺报/坏行/预算延后。报表仍是保留数据的已报告小计，不是完整账单；手动压缩未被覆盖，见下节。

### 5. 82ed/P2坏行隔离

`SQLitePreparationRuntime.readUsage` 的单行read、父identity验证与 `AttributedRequestUsage` 构造现在同在隔离边界内。必需text字段和revision/has_usage存储类严格验证，REAL不能被getLong/getInt截断为合法值。坏行只增加unreadable并继续，有效Usage保留，scanComplete=false。SQLiteException和CancellationException仍传播，绝不冒充坏行或成功空统计。

## 明确未覆盖：手动压缩不是另一个待发送父请求

已核对 `ContextManagementWorker.production` → `profiles.freeze` → `ProviderSummaryModelResolver.resolve(binding)`：此独立手动路径仍无Usage port，且没有后续Runner handoff。**本批没有把手动压缩计入完整摘要入账，也没有替它挑历史run、借最后一条assistant、造message或改终态checkpoint。** 对该worker/fixture的试探改动已撤回；父合并6e08e1b时不要将手动路径误认成本批自动发送准备接线。

后续最小合同（需独立评审，不在本批冒充已完成）：

1. 归属为 `ConversationPreparation(conversationId, operationId)`，目的显式 `MANUAL_SUMMARY`（或SUMMARY+manual operation kind），而不是必需parentMessageId的 `UsageOrigin.Main`；operationId是本次宿主操作，不是历史run或synthetic摘要message。现有parent-only typed合同无法无损表达此事。
2. 扩展同库既有typed请求存储/reader以支持这种**无父message**的归属；独立request/attempt/owner/revision、has_usage、终态/取消、预算扫描coverage，conversation删除仍清理。不得把它混入assistant-message数或虚构MAIN请求。
3. 只在实际summary open/next前durable admit；`ContextManagementSummaryProfiles.freeze(expected, usagePort)` 把同一个port传给保留revision的真实provider resolver，所有Usage仍整快照durable替换。
4. 原ContextManagementLease保持到worker drain，成功/失败/取消都终结该operation，无seal/send/handoff，也无自动恢复POST。没Usage单独计manual_summary_without_usage；未实施前对外注明该路径accounting unavailable，而非填零或声称全覆盖。
5. 验证真实worker+resolver+parser+SQLite：POST前admission、没有父message/checkpoint、Usage与缺报、取消迟到值、进程重开不重放、坏行/预算、删除与备份的归属保持。

## 备份兼容边界（不改备份算法）

已有中性备份只序列化旧UsageRecord字段，不携typed目的/attempt/authority；本批未更改格式、digest或恢复算法。旧行与数值保持兼容；恢复的receipt没有typed来源证据时仍为LEGACY，不因title前缀伪造目的，不触发模型重放。因此**不能承诺便携备份还原全部typed来源coverage**。如需保持此信息，应另行给备份格式增加向后兼容的可验证元数据，而非让reader猜。

`TitleProductionDatabaseTest` 保留旧port兼容夹具，检查原context_usage行和备份持久性，不再把无来源的LEGACY receipt当新authority。真实typed标题与终态父checkpoint不变由新增fixture覆盖。

## 新增/调整的源码fixture，全部尚未运行

- `P21RequestCoverageDatabaseTest`：真实helper/lease/Runner；SUMMARY缺报而MAIN报告及反向；视觉整快照null/真零/幂等/终结迟到拒绝/预算/重开/删除；父终结后标题admit/逐事件observe/CAS失败回滚/成功追加/重复receipt不双算；v16旧child缺列与旧context表升级；篡改run_id、message_id、REAL revision/has_usage后有效Usage保留且scanComplete=false。
- `ControllerContextPreparationTest`：真实Controller sendText completion已过finishPreparation后，摘要handle仍HANDED、真实Runner继续。原summary/parser/SQLite fixture继续保留，并更新handoff存在位断言。
- `AuxiliaryVisionModelAdapterTest`：真实Responses parser配内存HTTPS，Usage送达同一admitted attempt，缺报不生成Usage，只有一次admit/close。
- `RunCheckpointCodecTest`：v6admission元数据、严格旧版本字段、无Usage不制造快照、恢复/坏shape；`ChatRunnerUsageCheckpointTest`：NEXT分轮及RESUME保留admission列表。
- `RecordedUsageProjectionTest`：preparation/MAIN/视觉/标题缺报与预算文案，不输出零token快照。usage和标题范围schema断言更新17。

父必须实际执行并记录：core-domain定向usage/Runner测试；app codec/vision/projection单元测试；上述Android数据库/Controller fixture及现有AttributedUsage/RecordedUsageConsumption/SummaryRuntimeProduction/TitleProduction回归；主合并树的编译与androidTest编译。其他模块旧硬编码schema15/16断言（例如MessageTime、ChildCommandSchemaMigration、ControllerGenerationIntegration等）由父按真实含义处理，不能全局替换数字。最终还需检查Main/唯一清单未被本批改动、新Controller接缝合并无回退、detached与非空提交。**静态阅读/文件写入不是通过证据。**
