# P35 · 生产消息时间接线

## 范围与依据

依据 `docs/P35-message-time.md` 与当前生产创建路径实现。仅改生产文件 `ChatController.kt`、`ControllerImageSending.kt`、`ControllerVideoSending.kt`、`RunnerHistorySegments.kt` 以及下列聚焦测试。未改 MainActivity、消息操作、领域构造器、数据库、journal/witness 格式或公共重构清单。MainActivity formatter 接线仍由父代理负责；P37 的 ChatController 并行修改须由父代理按净 diff 整合，不能整文件覆盖。

## 接线链路

### 普通候选组

`ChatController.sendPrepared` 在 conversation/run 身份与 provider 配置冻结后、候选消息及媒体/上下文 preflight 前，**只调用一次** `System.currentTimeMillis()`，保存为 `acceptedAt`。

- 文本 user 与初始 assistant 显式使用同一值。
- image user 通过 `ControllerImageAttempt.prepare(..., acceptedAt)` 取得该值。
- video prepare 通过 `ControllerVideoAttempt.prepare(..., acceptedAt)` 取得该值；其 `expectedUser` / `expectedAssistant` 重建使用已冻结的同一值，保持 commit 原有完整 Message equality。
- 视频外层 assistant 会提前传给 `ControllerVideoSession`；prepare 返回后新增 user/assistant **全值相等**检查，保证 prepare、session 与之后的 RunResources/active/Runner 不会只同 ID 而时间不同。
- 原有同一 assistant 继续流入 `ControllerVisionAuthorization`、reasoning callback、`database.send`、`RunResources.initial`、`active`、`admittedVoiceOwner` 与 `owned.startSlice`，没有另建不同时间的 owner。
- user 继续流入 context preparation、image/video preflight、数据库；原流式/停止/完成代码继续 `copy`，不再读取时钟。
- 包括 children、Shizuku、browser、generation、terminal、virtual-display 这些共享 sendPrepared 的新父轮；既有专属授权/身份门槛不变。

这里冻结的是待接受消息的候选时间，失败候选没有数据库新行；不会对历史或未知提交结果补时间。

### 子命令与暂停补充

- `directChildTaskPinned` 创建新本地父轮时，在最终 admission 前取消检查之后单次取时，user 与 initial assistant 同值，沿原 `database.send` / `ChildTaskCommandHost.run` 链传入。
- 已有 command receipt 的返回路径仍先于新父轮创建，未加入再次发送或取时恢复。
- `admitSupplement` 在 owner/非空检查后单次取时，只给该次新补充 user；不借用 assistant 的时间，不改旧 owner。

### 恢复与历史段

- image `reconcile` 从数据库原 user 行读取 `createdAt`，传入 expectedUser 完整相等重建。
- video `recoverDetails` 同样在 expected user 重建中携带原 user 行时间；legacy null 与 epoch 0 均原样保留。
- 没有向 journal 增加字段、变更版本/指纹/hash、扩大 witness；没有因恢复缺失证据调用 prepare/send。缺失或冲突仍走原 UNKNOWN 契约。
- video attempt 只接受显式传入时间；同一 attempt 再次 prepare 不允许改时间（包括 null 与已知值互换）。兼容旧测试/直接调用的默认是 null，不是读时钟。
- `RunnerHistorySegments.sealSession` 的 user 段复制 `user.createdAt`；所有活动 assistant 段（含 protocol 与 raw-free tail）复制 `message.createdAt`。已有 sealed 段不重写；unknown 保持 null。

## 聚焦测试源码

新增文件：

- `core-domain/src/test/kotlin/app/daiyu/android/domain/RunnerHistorySegmentsTimeTest.kt`：protocol/tail/补充段各自来源时间、0/null、恢复、真实 ChatRunner 流式/重开/完成稳定性。
- `app/src/androidTest/kotlin/app/daiyu/android/ControllerMessageTimeProductionTest.kt`：真实 Controller/SQLite/Runner 的新普通消息组、active 与 model history 一致、完成/重开不改时、旧历史不回填，以及暂停补充自己的接收时间和恢复后的段时间。

扩展原测试：

- `ControllerImageSendingTest`：null/0/已知时间的 prepare/原行 receipt 重建；生产 image pair 与 Runner history/完成段共享时间、重复 receipt 不再次请求。
- `ControllerVideoSendingTest`：null/0/已知时间的 prepare/preflight/full-equality commit/恢复；单独篡改 user 或 assistant 时间在 DB entry 前拒绝；冻结后不能换时。原 journal 没有 created_at。
- `ControllerChildRetentionTest.testControllerAppendUsesNewOwnerAndWaitsForOriginalWorkerAndCleanup`：新子命令父轮 user/assistant 共享时间、完成段沿用、重复 command receipt 不改行。

测试使用本地合成模型/媒体夹具，不是网络、真实媒体解码、设备 UI 或业务验收证据。**实现代理没有执行测试、构建、APK 或设备验证**；最终统一执行由父代理负责。本次工具只能修改工作树文件，不能操作 Git，因此非空提交须由父代理集成这些净修改后创建，不能将未提供的提交哈希声称为完成。

## 交给父代理的 v14 目标断言清单

以下全部是 `app/src/androidTest/kotlin/app/daiyu/android/` 下仍期望数据库目标版本 14 的位置（本任务未修改，父代理应改为目标 15）。共 24 行、25 个断言；行号为此候选源码扫描结果：

| 相对文件 | 行 |
| --- | --- |
| `AssistantBindingDatabaseTest.kt` | 65 |
| `ControllerVideoWitnessDatabaseTest.kt` | 164, 183, 209, 356, 383 |
| `MessagesBackupContractTest.kt` | 27（两个断言）, 256, 286 |
| `PlatformTest.kt` | 227, 269 |
| `RetainedConversationDataTest.kt` | 153 |
| `backup/NeutralBackupServiceTest.kt` | 146 |
| `backup/NeutralVideoBackupTest.kt` | 81 |
| `backup/SegmentedBackupRoundTripTest.kt` | 160 |
| `model/usage/AttributedUsageDatabaseTest.kt` | 150 |
| `storage/ConversationHistoryDatabaseTest.kt` | 53 |
| `storage/ConversationSchema4Test.kt` | 37 |
| `storage/GenerationSchemaMigrationTest.kt` | 406 |
| `storage/MessageImageDatabaseTest.kt` | 75 |
| `storage/MessageVideoDatabaseTest.kt` | 149, 180 |
| `storage/SQLiteChildTaskStoreTest.kt` | 312 |
| `storage/SQLiteRuntimeStoreDatabaseTest.kt` | 188 |

不要误改：`ControllerContextPreparationTest.kt:132` 的 14 是历史消息数；`MessageMarkdownTest.kt:144` 是字符偏移；`storage/MessageTimeDatabaseTest.kt:208` 的 `raw.version = 14` 是真实 v14 来源夹具，必须保持。已集成的 ChildCommandSchemaMigrationTest 目标 15 不在遗留清单内。
