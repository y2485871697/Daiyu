# P18：视频 sidecar 真实数据库接线测试

## 范围与执行状态

新增 `app/src/androidTest/kotlin/app/daiyu/android/storage/MessageVideoDatabaseTest.kt`，共 10 个 `test…` 方法。沿用现有 `InstrumentationTestCase`、`testContext` 和真实 `ConversationDatabase` 模板，在隔离数据库中调用实际 send / save / runtime / delete / backup 路径；不是内存仓库或仅 codec 单测。

**本次仅完成源码核对与测试编写，没有运行编译、设备测试或模拟器测试，不声称通过。** 构建及运行由主代理执行。没有修改生产代码、既有测试、Manifest 或构建配置。

这里的“真实数据库”指 Android SQLite、应用自身 helper、迁移及事务接线。视频引用使用合法的元数据 fixture，**没有创建可播放视频或验证原件文件**。测试不访问网络、不请求生成服务，也不证明上传授权、解码、预览、文件 GC、UI 或 Controller 已完成集成。

## 覆盖矩阵

| 测试方法（省略 `test` 前缀） | 实际断言 |
| --- | --- |
| `VersionElevenUpgradePreservesGenerationRowsMessagesAndDrafts` | 在磁盘上重建 v11 前驱形态，关闭并以真实 `ConversationDatabase` 打开触发 v11→v12；消息、会话/全局草稿、generation attempt outcome BLOB、generation output 的所有列及存储类型保持；新增空 sidecar 表具备 messages 外键及 CASCADE；二次重开不变，原 generation attempt 不获得新的 Started。 |
| `SendVideoCommitsBothMessagesMetadataAndDraftReceiptAcrossReopen` | 既有/新会话 `send(..., videoInputs=...)` 均提交 user、assistant 和完整引用；会话草稿清空，composer 遵守各发送路径原有规则；关闭重开后消息、引用所有字段和持久 payload 不变。 |
| `WrongInputAndReferenceOwnersRollBackMessagesAndBothDrafts` | 输入跨会话、输入指向 assistant、引用 owner 与输入 owner 不同均拒绝；既有/新会话都不遗留消息、sidecar 或新会话，两个草稿及原数据完整保留。 |
| `IgnoredAbortedAndTamperedSidecarInsertRollsBackEntireSend` | sidecar INSERT 的 `RAISE(IGNORE)` / `RAISE(ABORT)`、AFTER INSERT 改为无效 JSON 对象、改为合法但不同的 bytes 元数据均拒绝；后两项分别覆盖解析失败和回读回执不匹配；既有/新会话均核对整组数据并重开复核。 |
| `CorruptRetainedPayloadFailsClosedOnMessageAndVideoReadsAfterReopen` | 对已提交 sidecar 注入 SQL 层合法、codec 层无效的 TEXT 后，`messages` 和 `videoInputs` 都拒绝；重开后仍拒绝，读取不会清除或修复坏 payload；恢复原 payload 后才能读出原消息。 |
| `SavePreservesUserAndExpandedUserMetadataAndRejectsOrphaningRewrite` | 同时保留物理 user 和 assistant 历史中展开 user 的引用；移除历史 owner 的 save 被拒绝并回滚；兼容的完成更新及重开保留引用和 payload。 |
| `RuntimeCheckpointPreservesMetadataAndRollsBackIncompatibleMessageAndCheckpoint` | 真正取得 SQLiteRuntimeStore lease 后提交首个及兼容的下一 revision；不兼容更新回滚消息与 checkpoint，读取仍是前一已提交 revision；关闭重开后原始行不变；新 lease 的恢复保留引用和历史，并可重复 reconciliation。 |
| `DeleteIgnoreAndAbortRollBackThenSuccessfulDeleteRemovesOnlyOwnedMetadata` | sidecar DELETE 的 IGNORE/ABORT 都保留会话、消息、引用、草稿并重开验证；正常删除清理目标会话的引用/消息，另一个视频会话及 composer 保留；`foreign_key_check` 无孤儿。 |
| `TextBackupRejectsVideoGloballyAndPerConversationButAllowsTextOnlySelection` | 有视频时全库及该会话的旧文本备份必须拒绝，但同库无视频会话可导出且只含该会话两条消息；拒绝/导出均不改数据库，重开后规则不变；删除视频会话后可全库导出。 |
| `AllPhysicalHistoryFindsVideoOlderThanUiPageAndRejectsStaleSnapshots` | 保存 31 轮、62 条物理消息，视频仅在最早 user；默认 50 条 UI 页看不到视频，逐页完整收集能取回；真实 save 使快照过期后拒绝回读，重开仍拒绝，而新快照可正常取回引用。 |

回滚比较不是仅比较总行数：测试对 conversations、composer、messages、message_video_inputs、runtime_checkpoints、generation_attempts、generation_outputs 做有序逐列快照，区分 NULL / BLOB / INTEGER / TEXT；故障后同一连接与关闭重开都进行比对。断言失败不会被预期异常 helper 吞掉。

## 两个刻意显式的 fixture 边界

1. **v11 fixture 不是历史 APK。** 根据 `ConversationDatabase.onUpgrade` 的实际分支，v11→12 只安装 `SQLiteMessageVideoStore`。测试先用真实 helper 创建并填充当前 schema，删除唯一 v12 新增表、将 SQLite `user_version` 设为 11，再关闭并重新打开 helper。它不直接调用 `onUpgrade`，也不使用假迁移数据库。generation attempt 通过真实 store reserve/finish；generation output 直接插入满足 v11 约束的元数据行，仅断言保留，不冒充原件已发布或可授权打开。
2. **assistant 展开 user 的 sidecar 是手动注入的兼容性 fixture。** 当前 `send(videoInputs)` 只给物理 user 写 sidecar，不能经此 API 给 assistant 历史追加 sidecar。因此这两个 save/runtime 测试通过真实 codec 编码，再向真实 sidecar 表插入引用，专门触达现有保留元数据的兼容性校验。它们不宣称已有面向 Controller 的“保存视频 supplement”入口。

## 历史完整性限制

`videoInputs(messages)` 会核对每一个传入消息是否仍与磁盘一致，但**不会证明调用者传入了全历史**。因此测试明确断言仅传 UI 页会得到空视频列表，而不是臆造“数据库拒绝分页历史”的契约。测试中的 `physicalHistory` 是本文件的分页收集 helper，不是新增生产 API；其 17 条页大小保证 62 条历史跨越多页。Controller 必须另行保证收集所有物理历史并处理并发变化，当前测试不证明该接线已存在。

## 已核对的源码依据

- `app/src/main/kotlin/app/daiyu/android/storage/ConversationDatabase.kt`：schema 12、v11 升级分支、`send` 的双消息/sidecar/草稿事务和回读、`storedMessage`、`videoInputs`、`save`、`writeRuntimeMessage`、`delete`、`exportBackup` / `exportConversationBackup`、默认 50 条的 `messages` 分页。
- 同目录 `SQLiteMessageVideoStore.kt`：表约束及外键、INSERT 回读、DELETE 后确认、视频存在性查询。
- 同目录 `MessageVideoInputCodec.kt`：物理 owner / 展开 user owner 验证、合法引用字段和边界、完整编码。
- 同目录 `SQLiteRuntimeStore.kt` / `RunCheckpointCodec.kt`：真实 lease、revision CAS、消息与检查点同事务、恢复流程。
- 同目录 `SQLiteGenerationAttemptStore.kt` / `SQLiteGenerationOutputStore.kt`：迁移保留 fixture 的字段/约束、attempt 的 reserve/finish 入口。
- `app/src/main/kotlin/app/daiyu/android/model/videos/FrozenMessageVideoPlan.kt`：`MessageVideoInput` 是普通 class，没有值相等语义；测试比较 owner 与完整 references，不误用对象地址相等。
- `core-domain/src/main/kotlin/app/daiyu/android/domain/Conversation.kt`：`MessageHistorySegment`、展开用户和物理历史的约束。
- 既有 `ConversationHistoryDatabaseTest.kt` / `SQLiteRuntimeStoreDatabaseTest.kt` / `TestContext.kt`：InstrumentationTestCase、隔离 helper、重开和 lease 的模板；均未修改。

## 建议由主代理运行

项目 `app/build.gradle.kts` 已配置 `android.test.InstrumentationTestRunner`。可先编译 instrumentation 源码，再在已配置的 Android 设备/模拟器上只运行新增类，例如：

```text
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.daiyu.android.storage.MessageVideoDatabaseTest
```

以上是待执行建议，不是执行记录。仍需记录实际构建/设备环境、方法发现数量、失败或通过结果；若有与本改动无关的既有构建阻塞，应单独报告，不以源码审阅替代测试通过。
