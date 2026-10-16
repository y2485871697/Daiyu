# P18：ContextService 视频上下文保护回归测试

## 范围与执行状态

新增：

- `app/src/androidTest/kotlin/app/daiyu/android/context/ContextVideoBoundaryTest.kt`
- 本文档。

目标是回归保护 c5dd568 中已有的视频上下文边界及提交前复查；不修改生产代码、现有测试、数据库结构或公共 API。

**本次实现阶段未运行编译、Gradle 或设备 instrumentation 测试。** 下文是已新增的测试与源码核对结果，不是测试通过报告。编译可行性、设备结果及与既有测试的集成仍需主代理/CI 验证。

## Fixture 与验证方法

沿用 `ContextServiceTest` / `ContextHistoryServiceTest` 的 `InstrumentationTestCase`、唯一前缀 `testContext`、私有 files 目录、真实 `ConversationDatabase` / `ContextService` 与关闭重开数据库的方式。测试完成后关闭数据库、删除隔离数据库及 files 目录。

- 普通轮次通过 `ConversationDatabase.send(..., videoInputs = ...)` 和 `save` 写入、完成；不是内存消息列表替代数据库。
- 历史 assistant owner / supplement 的物理行沿用 `ContextHistoryServiceTest` 的 `ContentValues` 写入方式，用 `ArchiveCodec`、`MessageHistoryCodec` 编码并验证。视频 sidecar 在同一事务内调用真实 `SQLiteMessageVideoStore.insert` 写入。
- 后补 sidecar（旧档案和竞态场景）也使用 `SQLiteMessageVideoStore.insert`。损坏输入先写入合法 sidecar，再只用 SQL 更新其 `payload`，模拟已经落盘的坏数据。
- 视频引用是满足 codec 约束的固定测试元数据，不导入/读取真实视频文件。这里测试的是上下文读取、压缩边界和数据库发布，不是素材解析、授权、解码或模型视频发送。
- 对成功压缩，核对 summarizer 收到的精确原文/修订摘要、`archivePage` 的物理原文、`prepareSend` 的 archive ID 与完整保留尾部、token estimate 以及 `displayHistory` 未变。
- 对拒绝/只读回退，快照比较数据库中所有表（包括 sidecar、archive、conversation、message 与 `sqlite_sequence`）的完整行内容；关键路径关闭重开数据库后再次断言。快照是逻辑内容比较，不是数据库文件字节比较。
- summarizer 只是同步回调；不需要真实模型、网络、线程调度、sleep 或概率性竞态。

## 覆盖矩阵

| 测试方法 | 场景 | 核心断言 |
| --- | --- | --- |
| `testFirstTurnVideoNeverCallsManualOrAutomaticSummarizer` | 第一轮即视频，之后有足够的完整文字轮次 | 手动压缩返回 null；自动策略确实达到触发阈值但 summarizer 调用次数仍为 0；发送/展示保留全部原文，数据库不变、无 archive |
| `testTextPrefixBeforeVideoArchivesOnlyCompleteRuns` | 两轮文字前缀 + 视频轮 + 后续文字 | 只归档两轮文字；视频所在轮以及之后的物理行完整保留；sidecar 重开后仍可读取 |
| `testAssistantSidecarForSegmentOnlyUserKeepsWholeRun` | 视频 owner 是 assistant 历史里的 segment-only user，无对应 standalone 行 | sidecar 落在真实 assistant 物理行；不能把该轮开头 user 单独压入 archive，回退到前一个完整 run |
| `testStandaloneVideoSupplementKeepsAssistantOwnerAndWholeRun` | assistant 历史引用一条后置 standalone user 补充，视频在补充行 | 截点不能分离 assistant owner 和补充行，也不能拆 run；只压之前完整文字轮，补充内容与 sidecar 保留 |
| `testOldActiveArchiveCoveringVideoFallsBackToOriginals` | 先建有效文字 archive，再给其覆盖的 user 行补入视频 sidecar；文字 revision 仍匹配 | 旧 active archive 不应用，`prepareSend` 返回原文与 null archive ID；不删除/改写旧 archive；重开后相同 |
| `testCorruptAndWrongOwnerSidecarsRejectWithoutArchive` | 没有 archive；尾部 sidecar 分别为坏 JSON、合法但 owner 属于另一会话的编码 | `displayHistory` / `prepareSend` 分别报告 `INVALID_JSON` / `OWNER_MISMATCH`；摘要零调用、所有表不变；重开后仍拒绝 |
| `testCorruptAndWrongOwnerTailSidecarsRejectEvenWithActiveArchive` | 有有效 active archive；上述两类坏 sidecar 位于未归档尾部 | archive 不能掩盖坏尾部；展示/准备发送同样拒绝，既有 archive 与所有表不变 |
| `testBranchAndReviseWithVideoLeaveEveryDatabaseTableUnchanged` | 已有文字 archive，视频位于后续轮；选择视频之前的普通 user 进行 branch / revise | branch、有替换文字的 revise、纯截断 revise 均拒绝；所有表及草稿保留，未生成分支、未删除消息；重开后 archive 和 sidecar 正常 |
| `testSidecarAddedDuringSummaryRejectsPublicationAndKeepsPreviousArchiveActive` | 已有较短 active archive；新一轮压缩的 summarizer 回调给新增候选前缀写入视频 | 文字/history digest 未变仍须拒绝；调用一次摘要后命中提交前视频复查；不插新 archive、不停用旧 archive；只有回调主动写入的 sidecar 保留，重开后旧 archive 仍可用 |

最后一个用例将新增视频放在旧 archive **之外**、新候选前缀 **之内**，因此可以同时断言旧 archive 继续可用和新 archive 没有发布。它不要求回滚 summarizer 回调已经独立提交的 sidecar，也不声称失败压缩绝无文件写入：`compress` 在发布事务前可能已经生成未被数据库引用的 blob。

## 已核对的源码依据

- `app/src/main/kotlin/app/daiyu/android/context/ContextService.kt`
  - `read` 对每条物理行校验视频 sidecar，包括没有 archive 和 archive 尾部的路径。
  - `compress` 将第一个有视频的物理行作为上限，再使用完整 prefix 检查回退；提交事务内再次读取候选前缀的 sidecar。
  - `effective` 对覆盖视频的旧 active archive 回退到原文。
  - `branch` / `revise` 对会话内视频采用拒绝策略。
- `core-domain/src/main/kotlin/app/daiyu/android/domain/context/ContextPolicy.kt`
  - `isCompleteContextPrefix` 检查完整 run、owner/supplement 闭合及工具完成情况。
  - `ContextRevision.digest` 覆盖文字/history，但不包含独立视频 sidecar；故竞态用例必须验证额外的提交前复查。
- `app/src/main/kotlin/app/daiyu/android/storage/SQLiteMessageVideoStore.kt`
  - `insert` 要求已有事务，编码后回读校验；`read` 使用实际存储及严格 codec。
- `app/src/main/kotlin/app/daiyu/android/storage/MessageVideoInputCodec.kt` 与 `app/src/androidTest/kotlin/app/daiyu/android/storage/MessageVideoInputCodecAndroidTest.kt`
  - 核对引用构造参数、物理 owner / user segment 绑定及错误类型；未新增 codec API。
- `app/src/androidTest/kotlin/app/daiyu/android/context/ContextHistoryServiceTest.kt` / `ContextServiceTest.kt`
  - 核对历史物理行构造、事务 fixture、archive 断言和数据库重开方式。

## 建议执行（尚未执行）

在配置好项目要求的 JDK、Android SDK/NDK 和设备后，可由主代理/CI 执行：

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.daiyu.android.context.ContextVideoBoundaryTest
```

第一条与现有 `.github/workflows/build.yml` 的 APK/test APK 构建入口一致；第二条是针对新增类的设备运行建议，未在本次环境验证。项目 `app/build.gradle.kts` 配置的是 `android.test.InstrumentationTestRunner`，新增测试使用与既有 context 测试相同的 JUnit3 风格。随后建议运行现有 context/history 与 codec 测试，确认没有集成回归。实际结果应另行记录，不能把源码检查当作设备执行证据。
