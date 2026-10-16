# 浏览器父控制器定向测试覆盖

## 范围与执行状态

目标基线为委派上下文已确认的 detached `55ea5cb`。本次仅新增：

- `app/src/androidTest/kotlin/app/daiyu/android/ControllerBrowserSendTest.kt`
- 本文档。

不修改 `ChatController`、`MainActivity`、`ControllerBrowserPreparation` 或现有测试夹具。测试调用真实父控制器入口，不以直接调用 helper 的 `prepare/accept` 代替父接线测试。确认步骤仍使用父控制器实际返回的 preparation；没有另造 preview、binding 或 acceptance receipt。

**本次环境仅允许源文件读写，没有 Shell、Gradle 或设备执行能力。未编译、未运行这些新增测试，也未自行执行 Git 命令重新核验 SHA。下表是源码断言覆盖，不是通过报告。**

## 夹具与观测边界

- 直接复用 `TextSendTicketTest` 使用的 `RunControlFixture`、`controlMain`、`awaitControl`：真实 `ChatController`、SQLite、工作队列和 ticket；模型传输仍为现有测试 session。
- 沿用 `ControllerContextPreparationTest` 的私有文件根目录与隔离数据库/偏好做法。外层 `ContextWrapper` 的 `applicationContext` 也指向隔离上下文，避免 `BrowserSettingsStore` 经 application context 写入真实应用偏好。每个测试使用 UUID 命名空间。
- 同时计数模型工厂调用和 `ModelPort.open`，检查 `active`、持久化草稿、会话存在性、整个 `messages` 表的行数，以及隔离文件根下没有 `browser-runs`。行数检查不只看 UI 消息页，也不会把“没有回调”当作“没有提交”。
- 排队取消/关闭测试通过反射获取**已有** `executor`，加入有界 latch barrier；不替换 executor，不替换 `openOnWorker`，不注入浏览器工厂。先确认真实 `sendBrowser` 返回队列接纳，再取消/关闭，最后释放真实 worker 并等待真实 `Rejected` 回调。此 barrier 与私有字段名耦合；字段重命名时测试应显式失败并更新。
- 所有 barrier 在 teardown 释放。控制器由现有夹具关闭；关闭测试另行重新打开真实数据库核验草稿。没有延时猜测浏览器是否打开，也没有联网。

“没有 `browser-runs`”是文件副作用断言，不是通用 WebView/网络监视器。结合真实 preparation 状态与模型计数，它用于限定本组 **pre-open** 路径；不宣称对任意 native 浏览器实现做了全局构造次数审计。

## 新增用例矩阵

以下名称均属于 `ControllerBrowserSendTest`。

| 用例 | 父接线及关键断言 |
| --- | --- |
| `testPrepareIsWorkerOnlyAndDoesNotOpenModelBrowserOrWriteMessages` | 主线程调用在占槽前拒绝；工作线程通过真实 `prepareBrowserSend` 得到保留的 PREVIEW。没有模型工厂/open、浏览器目录、会话或两行消息；原始草稿包括空白保持不变。 |
| `testDisabledBrowserRejectsKeepsDraftAndReleasesReservation` | 关闭浏览器设置后 prepare 返回 null，错误为 `BROWSER_DISABLED`；草稿与空消息表保留。重新开启后真实 prepare 可再次接纳，证明不是只清空可见 accessor 而仍占槽。 |
| `testNewComposerFreezesAssistantProviderSettingsWritesAndGeneratedIdsThroughConfirmation` | 新建会话取已选助手；随后更改全局选择、原助手内容、模型 endpoint/model/key、浏览器设置及调用方写入列表，原 preparation 的助手、provider、设置、原始写入参数不变。确认前后会话/run/assistant-message ID 不变，grant run ID 与其一致。确认本身仍不写行、不打开模型或浏览器。 |
| `testExistingConversationUsesDurableAssistantBindingNotGlobalSelection` | 已有会话使用数据库绑定的助手而不是当前全局选择；保留原会话 ID。父控制器的绑定修复入口被同一独占槽阻止；修改助手存储后 preview 仍保持快照。 |
| `testPreviewRejectReleasesSlotForOrdinaryTextSend` | 未确认 preview 的 `sendBrowser` 不入队、不产生 ticket 状态或回调；`rejectBrowser` 排空并释放。随后真实普通 `sendText` 可接纳并产生两行，以现有模型 session 完成。**此处 Accepted 仅属于普通文本发送，不是浏览器发送成功。** |
| `testPreviewReservationBlocksOrdinaryAndSecondBrowserSendsAndStaleReject` | 同一 browser preview 阻止同步普通发送、异步 `sendText` 和第二个 browser prepare；无拒绝入口回调或模型副作用。旧 preview 的迟到 reject 不能释放新 preview 的槽。 |
| `testStopBeforeSubmissionCancelsPreviewAndConfirmedReservationWithoutCommitting` | 对 PREVIEW 和仅 CONFIRMED、尚未调用 `sendBrowser` 的两种状态分别 stop；真实父控制器排空并释放，草稿与消息表不变，可再次 prepare。 |
| `testTicketCancelBeforeQueuedBrowserOpenRejectsAndRetainsResultUntilAcknowledged` | 真实 `sendBrowser` 接纳后、worker 打开前，异 ticket 取消无效，同 ticket 取消生效。worker 未退出前仍阻止普通发送；退出后回调为准确 owner 的 Rejected，ticket/text/result 留存至 acknowledge，之后可重新 prepare。无浏览器目录、模型工厂/open 或持久化消息。 |
| `testCloseBeforeQueuedBrowserOpenRejectsAndPreservesDurableDraft` | 排队后主线程 close 撤销准入，后续发送不接纳；释放 worker 后返回 Rejected。等待真实存储关闭并重新打开数据库，验证草稿保留且消息表为零；不是仅检验内存字段。 |
| `testInvalidJsonNeverCreatesBrowserOrMessagePairAndEachFailureReleasesSlot` | 畸形 JSON、非对象 JSON、尾随第二个 JSON 值均经真实父入口失败。每次都检查无浏览器目录、无模型工厂/open、无 user/assistant 两行；已有会话草稿保留。每次失败后有效 prepare 均可重新占槽。 |

## 刻意不冒充的覆盖

1. **没有浏览器 Accepted 路径的设备成功结论。** 本组不让已确认、未取消的 browser send 继续进入真实 native open；排队测试在此前撤销。没有用 fake binding、fake receipt 或手工写两行把浏览器发送伪装成成功。
2. `CatalogBrowserBinding.open` 的真实构造依赖 Android 主线程/WebView/native 浏览器环境。真实打开、设备资源清理、运行后 browser 工具执行与最终提交可另行安排具备 WebView 的设备测试；本组不是其替代品，也不因设备能力未知而将异常认定为“成功”。
3. 快照断言证明**父 prepare 到确认**保存了哪些值，不声称已经通过真实浏览器 Accepted 路径验证 model factory 实际收到这些值，或验证 commit 时使用冻结的全部 ID。
4. 复杂 helper accept/锁/原生半构造所有权仍由既有 `app/src/test/kotlin/app/daiyu/android/ControllerBrowserPreparationTest.kt` 定义的测试覆盖，包括：
   - `acceptanceTransfersSameBindingAndReceiptConsumesOnlyExactDraftVersionOnce`
   - `ambiguousCommitCannotCreateReceiptOrReleaseEvenAfterSuccessfulClose`
   - `reentrantCancellationDuringCommitCannotPublishAReceipt`
   - `cancelWhileNativeConstructorIsInFlightOwnsAndClosesTheLateBinding`
   - `interruptedWorkerCannotReleaseQueuedOwnerOrLeakLateConstruction`
   - `partialConstructionRetainsOriginalCleanupAndUnknownNeverBecomesReleaseReceipt`
   - `pendingCloseIsNotDrainedAndFailedCloseIsStickyEvenAfterNativeSuccess`
   这些是现有测试源码范围说明，本次同样未重新运行它们。
5. `MainActivity`/Compose 审批按钮、旋转恢复等 UI 行为不是此父控制器测试的范围；已有 `browser/BrowserApprovalHostTest.kt` 等 UI 测试保持不变。

## 主代理/设备环境的后续验证

在满足仓库 SDK、Gradle 和 Android 设备要求的环境，可执行例如：

```sh
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.daiyu.android.ControllerBrowserSendTest
```

这些是待执行命令，不是本次执行记录。测试 runner 取自当前 `app/build.gradle.kts` 的 `android.test.InstrumentationTestRunner`。若需要直接使用已安装 APK 的 instrumentation，先核实构建产物的测试 applicationId/runner，勿把命令未执行或设备跳过记为通过。

## 可追溯源码

- `app/src/main/kotlin/app/daiyu/android/ChatController.kt`：`prepareBrowserSend`、`rejectBrowser`、`sendBrowser`、`cancelTextSend`、`finishPreparation`、`stop`、`close`。
- `app/src/main/kotlin/app/daiyu/android/ControllerBrowserPreparation.kt`：preview/confirm 与 `openOnWorker` 分离，未打开时 cancel 排空。
- `app/src/main/kotlin/app/daiyu/android/browser/BrowserRunPreparation.kt`、`integration/tools/RunBrowserTools.kt`：设置准入及真实 write schema/effect 解析。
- `app/src/androidTest/kotlin/app/daiyu/android/RunControlFixture.kt`、`TextSendTicketTest.kt`、`ControllerContextPreparationTest.kt`：复用夹具、线程工具、持久化/回调观测与隔离方式。

## 恢复审阅说明

原实现任务db730de4以WORKSPACE_LEASE_LOST结束，不记为成功。主代理核实保留的非空Git产物dd4875c，并在独立恢复快照e1447a2叠加父API修复3736228。d6cdd1c0只读审阅该恢复快照；本次只导入两份测试/说明文件，未覆盖已集成的Controller。未执行编译或instrumentation，不能记为10/10通过。
