# P37 静态生成目的端叶子：接口、接入顺序与未完成边界

## 状态与范围

本叶子仅新增 `media/StaticGenerationAssets.kt`、`storage/StaticGenerationOutput.kt`、`backup/StaticGenerationCodec.kt` 和独立测试源码。本实现**不是 P37 生产完成**，不开放现有分支入口，不修改 Controller、ContextService、MessageActionStore、数据库 helper/版本、Main、现有 backup 文件或唯一清单；不涉及 runtime-usage。

依据已读取的 `docs/P37-generation-static-branch-contract.md`、`MessageGenerationAssets`、`MessageGenerationBackupAssets`、`BackupGenerationCodec`、`BackupHistoryRows`、`MediaFileSafety` 及 `GenerationAttemptBinding`。现有完整包为 **v3**；本文的 section version=1 仅是新 kind 的局部 codec 版本，**不是完整包 v1，也没有把当前完整包升级或偷加字段**。父任务未来应在完整包统一升 v4 时接入，否则完整导出必须拒绝静默遗漏静态资产。

当前环境只有 workspace 文件工具；未运行编译、JVM、instrumentation、设备或构建，也没有 shell/git commit 能力。本叶子的文件改动需由主代理审查、构建、运行测试并提交，不能把测试源码当通过报告。

## 已实现的实质契约

### 两种身份绝不互换

- `StaticGenerationProvenance` 是不可变 scalar value：原 account、operation/item/index、conversation/assistant/run/tool、fingerprint/protocol、原 receipt，以及 MIME、字节数、SHA-256、尺寸、时长。
- `StaticGenerationDestination` 是独立本地 account、目标 conversation/run/**物理 assistant owner** 与精确 remapped tool call/name。来源 tool name 必须原样保持。来源消息可以已经删除；目标必须真实存在。
- `StaticGenerationOutput.identity` 使用版本化、长度前缀 UTF-8 编码的 SHA-256，包含 destination account/owner/run/call 与 source account/operation/item/index。来源不变、目标改变时是不同 identity。fingerprint、receipt、工具名及媒体 metadata 改变不能分配同 slot 的第二份对象，必须完整 equality 冲突拒绝。
- 再次 branch 时保留原 provenance，不把上一 branch 的 owner/静态 identity 冒充原生成 operation 或 receipt。没有转换成 `GenerationOutputReference`/live receipt、reserve、register、执行恢复或付费 admission 的 API。

`capture(reference)` 仅做 scalar 投影与验证，**不是读取授权证明**。父方必须先选择真实注册/只读成员，原件仍由既有安全 reader 读取，不能自己拼 private 路径。

### 新 kind 确实可持久化，不是接口占位

`StaticGenerationCodec.KIND == generation_static_output`：

- `encode/decode` 真正序列化/反序列化完整双身份与全部来源字段；严格字段集合、字段类型（数字仅 Int/Long）、版本、kind、identity、操作派生、item/index、scope/ID、指纹/hash/receipt 格式及媒体一致性校验。
- 持久化/file record 的 `decode(String)` 要求 canonical JSON（固定顺序、转义与数字表示），因此重复 key、额外尾部、替代数值表达及未知字段不能被 JSONObject 的宽松解析吞掉。`decode(JSONObject)` 用于已解析 package section 的类型/字段检查；未来完整包入口自身还须负责原始包解析策略。
- `encodeSection/decodeSection` 是未来 v4 的局部 section 接口，当前没有接入 v3 导入导出。
- `saveInTransaction/verifyInTransaction/find/read` 实际使用现有 `neutral_backup_history` schema，独立处理新 kind，未更改旧 `generation_output` 语义。写入物理 `owner_message_id`，逐字段回读，拒绝 IGNORE、冲突或篡改；幂等写要求完整 typed equality。
- schema 必须由父方事先提供（与现 backup-owned 表相同），叶子不负责迁移/安装。父方不能因为消息尚未写入就关闭 FK。
- 除消息快照外，codec 还从真实 `messages` 重读 conversation/run/role、blocks/history_segments，核验准确物理 assistant 和唯一匹配历史工具，不能只信调用方构造的 JSON。segment 非空时以 authoritative segments 为准；assistant segment 的 message ID 必须等于物理 owner。
- 来源不要求在目标库有原 owner、attempt 墓碑或 live output。所有新引用只写新 kind，不写 `generation_attempts`、`generation_outputs`。
- `read` 不使用 UI `take(32)`。50,000 行 / 8 MiB 历史元数据与按 hash 去重的 64 MiB 原件预算在读/写 batch 校验中明确失败关闭；单 record 上限 16 KiB，源 index 0..31。单图不超过既有 20 MiB，单视频不超过既有 100 MiB，且完整集合仍受 64 MiB 更小预算约束，不提高限制。

注意：`saveInTransaction` 的预算计算读取现静态集合，逐行调用可能有重复检查成本，父接入时不要用 UI 截断规避预算。生成/普通图片/视频/其他备份原件还须共享父方原有**总预算**，叶子返回的 dedup byte 数不是额外额度。

### prepare / publish / settle / openVerified

`StaticGenerationAssets` 是 app-internal，不实现 live `GenerationAssets`。构造 root 只许父方固定 app-private 根；不从 tool/UI/URL/路径字符串选择根。文件均由代码派生固定名：

```
<private-root>/generation-static-v1/
  .lock
  <identity-hash>.lease        # 本次独占的 32-byte reservation
  <identity-hash>.pending/     # 未发布 staging
    original
    record-v1
  <identity-hash>/             # 原子发布后的目的副本
    original
    record-v1
```

- `prepare(output, StaticGenerationOriginalReader, isCancelled)`：先检查单件上限，再在目的锁**外**打开安全来源 stream，按声明长度分块读、额外 1 byte 必须 EOF、SHA-256 验证并关闭本次 stream；不借来源 reader 的锁去拿目的锁。输入不是文件名或 URL。
- namespace 的进程锁 + `.lock` 的 NOFOLLOW FileChannel/FileLock 仅保护本次 FS 操作，不跨 SQL 持有。reservation 使用固定名 CREATE_NEW；已有 reservation/staging 视为未知证据，绝不偷锁或清掉再试。
- new staging 写入原件和完整 canonical record；每个文件 force(true)，目录 fsync；真实 image/video inspector 检查 MIME/尺寸/时长与既有媒体限制。不重编码、不用缩略图代替原件。
- `publish(lease)` 再次核验 record、完整有界原件/hash/媒体，取消检查在 rename 之前；`ATOMIC_MOVE` 发布并 fsync 私有 namespace。rename 开始后的任意异常为 `StaticGenerationPublicationUnknown(identity)`，不降级普通 rename、不删除、不重试；成功后不追加取消失败。
- 所有读写重复 ancestor/symlink 检查，并用 NOFOLLOW 通道访问普通文件、CREATE_NEW 写入；published 目录必须精确包含两个固定文件。威胁模型沿用现有媒体实现：不是对拥有同一 app UID 且无视 acceptance lock 的恶意进程提供沙箱。
- reservation 在 prepare 到 settle 之间一直保留。因此另一个 helper/process 不会在首 lease 尚可能回滚时“复用”它并提交，避免原 lease 误删被第二 lease 引用的文件。未知/崩溃 reservation 不可恢复性地被自动接管。
- `settle(COMMITTED)` 不再观察取消。`ROLLED_BACK_UNREFERENCED` **只能**在父方确认 SQL 回滚、精确目标无引用并已完全释放 SQL 后传入；仅清本 lease 首次创建的固定 staging/副本，重读完整 record 与 reservation，未知文件不递归删除。复用已有副本时绝不删除旧副本。
- `settle(UNKNOWN)`，或 lease 已发生 publication UNKNOWN，保留 reservation、staging 与可能发布目录，不能随后谎称 rollback 再清理。清理异常也保留剩余证据。没有 source/shared blob 删除 API，没有按 hash 回滚删除、年龄 GC、后台重试或自动修复。
- `openVerified(expected, lookup, isCancelled)` 先在 FS 锁外调用父方真实 registry lookup 并要求完整对象相等，然后核验目标 record + NOFOLLOW 原件的大小、EOF、digest、真实媒体，再返回 rewind 的只读私有 stream。父方负责持有 account acceptance/deletion lock 稳定成员身份；不能从外层 SQL 事务调用它。stream 用完局部关闭，不 seal 原 owner，也不撤销其他 reader。

清理是 best effort，不宣称断电绝无 orphan、DB/FS 跨介质原子或 crash GC 授权。未知目标 locator 为 `lease.output.destination.conversationId` + `lease.output.identity`；UNKNOWN 后刷新核实，不自动新建 branch/重放工具。reservation 清理失败不否认已确认 DB COMMIT 的持久成功。

## 父任务 T1 → SQL 外 prepare/publish → T2 CAS 的具体接法

以下是**父任务控制流示例**，`capture/authorize/commitOutcome` 等是父方尚需实现的能力，不是假称现有接口已经接通：

```kotlin
// 所有步骤在现有 account acceptance/deletion lock 生命周期内；不持 Controller monitor。
val capture = sqlT1 {
    // authorize 全历史 revision + assistant binding + 同一闭合整 run 前缀。
    // 捕获完整来源 scalar 集合、原件 kind、视频 inputs/witness 与规范 digest。
    // 此时分配新 conversation/run/物理 message/tool 映射，但不插目标消息。
    capturePrefixAndAllocateMapping()
}
// T1 的事务/cursor 已彻底释放。source rows 不能从 UI 可见列表构造。
val copies: List<StaticGenerationOutput> = capture.sources.map { selected ->
    val provenance = when (selected) {
        // registeredRef 来自同 account retained safe reader 所要求的真实注册引用。
        is Live -> StaticGenerationProvenance.capture(selected.registeredRef)
        is BackupHistory -> StaticGenerationProvenance.capture(selected.verifiedOriginalRef)
        is StaticHistory -> selected.verifiedStaticRow.source
    }
    StaticGenerationOutput(provenance, capture.exactDestination(selected))
}
val dedupBytes = StaticGenerationCodec.validateBatch(copies)
reserveSharedPrefixBudgetBeforeAnyRead(capture, copies, dedupBytes)

val leases = mutableListOf<StaticGenerationAssets.Lease>()
// 每个适配器核对 source == T1 捕获的 provenance，不能仅按 receipt/hash 查找。
// Live: 已有 manifest-only scope + retained readGenerationBackup 的完整注册/owner/fence 校验。
// BackupHistory: 已有 historicalGenerationOriginal 的历史成员身份/hash 校验。
// StaticHistory: 源 static row 的 registry lookup + assets.openVerified；保留原 provenance。
// readGenerationBackup / historicalGenerationOriginal 返回 bytes 时可用 ByteArrayInputStream 包装。
for ((copy, selected) in copies.zip(capture.sources)) {
    leases += assets.prepare(copy, safeReaderForExactly(selected), capture::isCancelled)
}
for (lease in leases) assets.publish(lease) // 任何异常先分类，不可一概当成未发布

// 所有 file/reader/fence lock 已释放，才允许 T2 开始。
val outcome = commitOutcome {
    sqlT2 {
        authorizeSameWholeHistoryAndAssistantBinding(capture)
        compareExactSourceSetIncludingInsertDeleteAndEveryScalar(capture)
        compareVideoInputsAndWitness(capture)
        checkNotCancelled(capture)
        insertConversationAndMappedMessages(capture) // 原视频首写真实 sidecar+witness
        val actualMessages = rereadActualDestinationMessages()
        for (copy in copies) StaticGenerationCodec.saveInTransaction(db, copy, actualMessages)
        // 做完所有其他 SQL 写入后再次核验，不以 insert 返回值或旧 JSON 代替回执。
        verifyAllMessagesAndVideoReceiptsAndUnchangedSource(capture)
        for (copy in copies) StaticGenerationCodec.verifyInTransaction(db, copy, actualMessages)
        assertNoNewLiveOutputsOrAttempts(capture)
    }
}
// commitOutcome 必须区分 confirmed COMMIT / confirmed rollback+absence / UNKNOWN。
// 尤其 endTransaction 抛异常、进程/线程中断时，不能凭 catch(Exception) 宣称 rollback。
// SQL 释放之后才 settle；若任意 rename/COMMIT 结果未知，对本次所有 leases 传 UNKNOWN。
for (lease in leases) assets.settle(lease, outcome.staticSettlement)
return branchLocatorAndDurableOutcome(capture, copies, outcome)
```

父方还需负责：prepare/publish 前失败时逐 lease settlement；没有尝试 T2 时也仅在分配的精确目标确无引用的证据下确认 rollback；取消不能覆盖已确认 COMMIT；source 原件读完只关闭本次 descriptor；出现来源集合变化直接 CAS 失败且不自动重读重试；不存在生成原件时不能回退 Tool.result URL/任意 private path。

## 剩余生产接线（本叶子不完成）

1. MessageActionStore/ContextService 的两阶段事务改造、全量来源集合 CAS、视频 witness 与消息最后写后回读、取消/UNKNOWN 分类和 locator 展示。
2. Controller retained/account-fenced safe reader 的实际适配；静态只读 registry 投影、Main 展示/显式保存；源 deleted 与再次 branch 的真实入口验证。
3. 完整包 v4 inventory、内容共享预算、真实原件导出/导入、重复导入重验、旧版本拒绝策略；当前 v3 不得误宣称已包含新 kind。
4. 真实生成视频编码 fixture、跨进程/取消/事务 UNKNOWN、所有既有 P37/视频/backup/runtime 回归运行；崩溃恢复和 GC 如需实现必须另行审查。

## 新测试源码与证据限度

- `StaticGenerationCodecTest`：完整字段往返、两种身份/再次 branch、严格未知/缺字段、类型与媒体校验、canonical 重复 key/尾部拒绝、准确工具 owner、row/hash-dedup/总字节预算。
- `StaticGenerationAssetsTest`：fake inspector 的离线生命周期/FS 测试，包括两分支/再次分支/源删除、stage 与 published rollback 清理、复用副本不删除、有界截断/尾部/hash/关闭 stream、媒体 metadata 冲突、各阶段取消、rename 与 SQL UNKNOWN 保留、reservation 排它和 symlink/record/原件损坏拒绝。**fake inspector 不是媒体解码证据**。
- `StaticGenerationLeafDeviceTest`：真实 ConversationDatabase + SQLite + PNG decoder，实际新 kind insert/read/reopen/FK cascade、源删除后两分支/再次分支原件保存、attempt/output 数量不变、IGNORE/mutating trigger 回执拒绝、真实 owner 与最后一次写后核验。它只证明叶子模块的目标，不证明真实 Controller/branch 入口接通，也不制造已完成工具的 attempt。

上述均为新增**未执行**源码；没有通过数、设备结果或提交哈希可报告。
