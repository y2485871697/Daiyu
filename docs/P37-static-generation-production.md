# P37 静态生成分支：生产接线与验收边界

## 本次交付范围

基线任务指定 `72342beb`，当前完整包 v4 已包含 `runtime_usage_history`。本次把已有 `StaticGenerationOutput` / `StaticGenerationCodec` / `StaticGenerationAssets` 接到真实消息操作、只读原件展示/保存和完整包 **v5**，不是另交一个目的端叶子。没有新增执行恢复、POST、付费授权、下载权限、attempt 或 live receipt 的生产写入。

仅使用工作树文件工具：不能独立读取 Git HEAD、验证 detached/main、运行 diff-check、编译、JVM、instrumentation、Lint、设备或云端 CI。本次测试均为**新增/更新的未执行源码**，不是通过报告；必须由父代理审核并实际执行后验收。未修改 `docs/重构清单.md`、`更新说明.md`、旧 Eta adapter 或 P26。

## 精确生产入口及参数

1. `ChatController.messageAction(BRANCH, target, complete)` 继续先占用现有 `dataBusy` acceptance/deletion reservation，在原串行 worker 执行，不跨 IO 持有 Controller monitor。调用 `MessageActionStore.branch(target)`，不是新的 UI-only 或 resume API。
2. `MessageActionStore.branch` 的外层不再包住整个 `transactions.run`。它解析闭合整 run 的 `through`，调用 `ContextService.branchWithStatic(conversationId, through, videos, staticGeneration, authorize, cancelled, copyAssets)`。`authorize` 仍来自原 `authorize(db,target)`：完整历史 revision、准确物理 owner/run/role、assistant binding 和 idle gate；T1、T2 开始及最后写后重复调用。
3. `ContextService.branchWithStatic` 委托 `ProductionConversationBranch`。其 T1 只捕获完整物理历史、全会话生成集合、所有视频 inputs/witness、会话字段、附件与 archive 元数据，并预分配 conversation/run/物理及 segment message IDs 和静态 identities。截点必须是整 run 闭合前缀且 prefix 全部 COMPLETED；不分离补充消息或未完成工具批次。
4. T1 完全释放后，原图 `copyImages`、视频 `BackupVideoAssets.read/restore`、archive/附件原件验证，以及静态 `prepare/publish` 执行。静态、原图、原视频、附件按 SHA-256/声明长度共用原 **64 MiB** 预算，不给新 kind 额外预算。目的消息尚未插入，不以 FK 绕过或空 witness 占位。
5. `ChatController.staticGenerationBranch` 的实际参数：
   - `account`：`GenerationSettingsStore(application).readAccountScope()`，仅 manifest；缺失/损坏失败关闭，不自动铸造 scope，不读 credential。
   - `liveOriginal = ::readGenerationBackup`：保留 manifest-only account 比较、retained resources/reader、真实注册引用逐字段相等、原 owner fence 及有界原件验证；不从 SQL 内调用，不 seal 原 reader。
   - `historicalOriginal = neutralBackup.historicalGenerationOriginal`：先在独立 SQL 内验证 backup-owned 成员，再在 SQL 外读取 hash-addressed 原件。T1 另以完整物理历史验证 source tool/run 归属。
   - static source：同一 `StaticGenerationAssets.openVerified(expected, lookup, cancelled)`；`lookup = neutralBackup.findStaticGeneration`，先完成 registry SQL，后持 FS 锁，保持原 provenance，不把上次分支 owner 当作生成来源。
6. T2 重新比较完整源集合（live + backup membership + static、每个 scalar、插入/删除）、完整历史/binding、会话/附件/archive 元数据和视频 inputs/witness。插入消息时直接写真实 remapped 视频 sidecar+witness，再写静态新 kind。最后一次 SQL 写后重读实际源/目标消息、会话/branch/附件回执、视频 witness、完整目标静态集合；目标 conversation 必须没有 `generation_attempts` 或 `generation_outputs`。

原 `ContextService.branch` / `branchWithVideos` 的无新能力入口继续失败关闭；检查已扩充到 `generation_static_output`，不能静默复制一个丢失新 kind 的会话。普通 `MessageActionStore` 构造没有静态能力时也会在 T1 拒绝。

## COMMIT / ROLLBACK / UNKNOWN

- 不再用普通 `transactions.run` 的异常猜 COMMIT 结果。T2 明确 `beginTransaction` / `setTransactionSuccessful` / `endTransaction`；只有 `endTransaction` 正常返回才确认 COMMIT 或 rollback。
- 正常 rollback（或尚未尝试 T2）还必须在 SQL 释放后核实精确目标 conversation 及所有静态 identities 无引用，才能向叶子传 `ROLLED_BACK_UNREFERENCED`。这只允许清本次 lease 首建副本；图片/视频/共享/source 原件不删除。
- rename unknown、`endTransaction` 异常或不能证实目标无引用：本批所有 leases settle `UNKNOWN`，保留 reservation/staging/可能发布目录。不会重新捕获源、自动 branch、重放工具或接管未知 reservation。
- COMMIT 后不再检查取消；settle 清理错误不否认已确认的持久成功。回读用于展示的后续错误也保留已分配 locator。
- `BranchOutcomeUnknown` → `MessageActionResult.Unknown(reason, BranchLocator(conversationId, staticIdentities))`。Main 展示精确 locator，唯一核实按钮调用 `inspectBranch`：只读取 branch、闭合消息、视频 witness、完整静态集合和原件；无重试、清理或修复操作。核实失败仍保留 UNKNOWN，不把“暂未找到”当重试许可。

不声明 DB/FS 跨介质原子、崩溃 GC 或断电无 orphan。进程死亡后的自动恢复/清理不在本次权限内。

## 静态只读展示与显式保存

- `ChatController.loadStaticGenerationOutputs(conversation, complete)` 通过独立只读 registry 和 `openVerified` 在 worker 核验原件，最多展示32项；图片用真实 decoder 缩略图，视频仍是已核验原文件而非结果 URL。
- Main 的生成资产对话框显示静态原件独立区和“保存静态原件”；完整导出不受 UI 32 项限制。
- `ACTION_CREATE_DOCUMENT` 使用 requestCode **107**，回调持有内存-only `StaticSaveSelection(output, AtomicBoolean visit)`；没有持久化/重建旧 picker 选择。
- conversation、editor generation、route/sidebar、非本次 picker 的 background pause、destroy 均单向撤销 visit，离开再返回不重新激活。仅允许精确 outstanding DocumentsUI picker hop 保留选择。
- `saveStaticGenerationOriginal(output, uri, visitActive, resolver)` 仅接受 content URI；先验证 visit 和成员原件，打开显式目标前及分块写入时再次验证。撤销不重试；阻塞 provider 打开期间的撤销最多可能留下已截断/不完整的显式目标，不写新的会话，不扩权/遍历路径。

## 完整包 v5 与 schema 安装

- 完整包导出统一 v5，解析严格兼容 **1..5**。v5 必须有 `static_generation_history`（复用局部 section version=1）及 inventory 的 `static_generation_outputs`；静态原件也计入 attachments 和原共享 blob 总字节，不增加 `MAX_BYTES` / `MAX_PACKAGE`。
- v4 的 `runtime_usage_history`、typed/manual/child usage 字段、缺报和来源归属原样保留；v1..3 的旧 attribution 逻辑不变。历史版本 fixture 降级时只移除其版本本来不存在的新节/计数字段。
- 导出 `captureExport` T1 捕获完整 metadata，释放 SQL 后读取所有 image/video/live/history/static 原件；最后以完整 metadata signature CAS，source 变化失败关闭、不自动重试。没有静态存储能力却遇到新 kind 的完整导出显式失败。
- 导入先 validate 包/共享预算和所有原件，SQL 外发布不可变原件；再 T2 导入文本/附表/静态 registry/视频 witness 和 import marker。最后写后重复精确静态集合及既有 generation/child/runtime/config/video 回执验证。
- 重复导入不是只信 import marker：SQL 外重新验证 private blobs、图片、视频和静态 `openVerified` 原件，T2 再验证 registry/完整 scoped 集合；缺档/损坏失败，不覆盖/补档，不恢复执行授权。
- 静态导入 leases 与分支同样显式区分 COMMIT、confirmed rollback+absence、UNKNOWN；未知或清理错误保留证据。旧图片/视频本来就是不可变共享/owner批次，失败可以留下私有未引用文件，但不删除 source/shared 原件。

**DB 集成冲突说明**：本次没有改 `ConversationDatabase.kt`，没有把 DB20 升版。唯一 schema-install 可见性变化是把已有 `BackupHistoryRows.install(db)` 从 private 改为 internal，SQL 定义完全不变；在 `StaticGenerationBranch.save` 和 `NeutralBackupService` 静态导入 T2 中显式调用它（目标消息已存在且 FK 启用）。复用原 `neutral_backup_history(kind,identity,owner_message_id FK,payload,PRIMARY KEY(kind,identity))`，不复制 helper，也不新增 legacy/P26 hook。父代理可据此合并独立 legacy DB 版本变更。

## 针对性回归源码（未执行）

- `ControllerGenerationIntegrationTest.testProductionLiveImageVideoAndImportedHistoryBranchUseSafeReadersWithoutNewAttempts`：真实 Controller 分支入口、已注册 live image/真实编码 video、完整包导出/导入后的 backup history 分支、再次 static 分支；保留原有真实 reserve-before-completion fixture，不为分支制造 attempt；断言 credential opens 不增加、源/目标 attempt/output 数量不增加、零模型 transport。
- `ControllerMessageActionsTest` 新增真实入口测试：两个独立分支、源 turn 删除与 source 文件删除后再次分支、真实静态展示、v5 round-trip/duplicate 原件损坏重验；无静态能力的三条入口失败关闭；最后静态写触发 source 篡改的完整 rollback；T1→T2 间 source 集合插入 CAS；原子 rename 故障 UNKNOWN locator+reservation/原件保留。
- 同类新增 `testProductionExplicitStaticSaveChecksVisitBeforeAndAfterProviderOpenWithoutRetry`：私有未导出 ContentProvider，撤销 visit 不打开目标、provider 打开期间撤销不写原件、下一次显式独立保存核对真实字节；不加 manifest 权限。
- `BackupActivityTest` 完整导出 picker 的版本断言同步 **5**，文本仍2；`ExtendedNeutralBackupTest` 与 `TypedUsageBackupTest` 更新 v5 断言及旧版本 fixtures；新增 `testV4RuntimeUsageRemainsCompatibleAfterV5StaticSectionAddition`，明确 v4 runtime observations 往返和重复导入保留。
- 未删除、跳过或“标记通过”任何原测试；旧叶子 PNG/codec/FS/设备测试继续保留。真实编译、上述 instrumentation、现有图片/视频/backup/runtime 回归，以及 Main 生命周期/Compose 真实交互仍需父代理运行。

## 交付结论与未验证项

源码闭环已落到原生产入口，而非孤立叶子；净改动应由父代理以实际 Git diff/构建审核。当前未有任何编译/测试退出码、设备通过数或本次提交哈希。尤其需实际确认 Kotlin/Android API 编译、真实 video encoder/decoder fixture、Main picker/lifecycle/ABA 回调、原有回归和 CI；本文件不能替代这些运行证据。仓库 detached/分支/基线状态也需父代理核实，文件工具不提供该能力。
