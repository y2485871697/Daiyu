# R07 定向组合回归增补（源码交付，未运行）

## 范围与状态

目标基线：主代理指定的 `main57064e27`。本实现代理只使用隔离工作树文件工具；没有 Shell/Git 元数据能力，未独立核验 HEAD，未创建分支、推送、运行 GUI、构建或测试，也不能生成 commit。提交及执行结果须由主代理另行审核提供。本文不是通过报告，亦不引用其他 CI 批次作为本批证据。

只扩展 `ControllerGuiStorageRetentionTest`，新增 capture 释放测试，并将截图成功回调原有三项释放逻辑抽到 `GuiCaptureRequest.disposeSuccessResources`。没有修改 ChatController、MainActivity、ContextService、数据库实现、备份、唯一清单或更新说明。不涉及 capture-stopped 语音。

## 新增回归：实际自动压缩组合

文件：`app/src/androidTest/kotlin/app/daiyu/android/ControllerGuiStorageRetentionTest.kt`。

### `testOnlyConfirmedGuiEvidenceCrossesAutomaticTriggerAndOneSummaryFeedsOnePendingGuiAndUsageCheckpoint`

- 同一 fixture 接真实 Controller、SQLite、ContextPreparationCoordinator、ProviderSummaryModelResolver、Responses serializer/parser、Runner 和生产 GUI catalog close observer。连接边界替换为内存 `HttpsURLConnection`，summary/chat 各有独立 POST 计数；不存在真实外网调用。
- 预置四个完成的历史轮次；显式 `automatic=true`、window=8192、reserve=128、trigger=50%、keepTurns=1。普通发送对照使用相同历史、短 draft、政策：不触发 summary，chat POST 一次。
- GUI 用例增加完整确认输入后越过压缩触发阈值；断言真实 summary POST 一次、压缩六条历史、保留最后一轮。原始消息表历史不变，新增 user 仍是原 draft；最终 model history 和真实 chat wire 都只含一次 pending GUI，checkpoint 与该投影一致，最终仍在发送预算内。
- 摘要请求不含 pending GUI；摘要用量 10/20/5（input/output/cached）先落同一 parent run/message 的 SUMMARY checkpoint。主请求返回 100/40/10 后，最终 checkpoint 各保留一个 SUMMARY/MAIN，合计 110/60/15；不把 estimatedTokens 当 provider usage。
- 真实 Runner 完成后观察原 session/catalog Drained，再关闭 Controller storage。

### `testCancellationWhileActualSummaryReadIsHeldKeepsPreparationGateUntilWorkerReturnsAndNeverPostsChat`

- 阻塞点在真实摘要 POST 的 response inputStream，已走实际 durable ADMITTED，不是在设置读取、假 summarizer 或预先取消标志处。
- cancelGui 触及真实摘要连接的 disconnect；假连接故意暂不解除 read，模拟平台 I/O 尚未退出。期间断言仍是同一个 preparation/entry/session，第二次发送被门禁拒绝，send receipt 未交付，Controller close 未完成，原 executor/SQLite handle 仍保留，零 chat factory/open/POST，draft/原历史保留、无 archive/checkpoint。
- 没有 capture 时，session.close 的主循环物理释放可先于 summary worker 退出；这不等于 Controller preparation/storage 门已放开，测试明确区分二者。
- 解除 read 后返回 success-shaped 响应也不能获得新授权或发起聊天。等待原 worker 返回、Rejected receipt 及真实 Controller close；随后才重新打开 SQLite 检查原 draft/历史、零 archive/checkpoint 和 TERMINAL preparation。

可信 UI 的已确认 delivery/frozenWrites 仍沿用既有测试注入方式；未挂 accessibility service、确认 overlay 或外部 App，也未执行屏幕动作。这里不宣称真实 overlay 的端到端授权成功。没有延长/刷新 GUI observation 的 10 秒期限；注入的摘要 timer 只隔离摘要 transport 定时任务，未改 GUI clock、授权或过期规则。

## 新增回归：onSuccess 资源释放异常组合

生产窄 helper：`gui/GuiCaptureRequest.kt`；调用点：`gui/GuiAccessibilityService.kt` 的 `TakeScreenshotCallback.onSuccess` 原 finally。

保持原有 software.recycle → hardware.recycle → buffer.close 顺序和 Exception-only 语义；三项分别尝试，任一异常都不调用 resourcesDisposed。只有全部成功才由现有原子终结位发送一次 receipt。未新增截图后端、授权、重试或超时退槽。

### `GuiCaptureDisposalTest`（新增 JVM 源码）

- `everyCombinationOfDisposalExceptionsAttemptsEveryResourceAndOnlyAllSuccessTerminates`：覆盖 0..7 全部失败掩码，检查三项尝试顺序；已发布 Value 也不是资源释放凭据，只有全成功终结一次。
- `rejectedEncodingOrEarlyReturnAndNullableBitmapsStillRequireBufferDisposal`：覆盖 stale/dimensions/encoding/unavailable 等提前返回或异常对应的结果状态，null bitmap 释放 no-op 仍要关闭 buffer。
- `lateSuccessAfterUncertainSubmissionOrConsumerCancellationCannotDrainOnFailedDisposal`：投递抛错后或 consumer cancel 后的迟到成功，失败释放仍不退槽，且不得覆盖原结果。

### `GuiCaptureDisposalProductionTest`（新增 instrumentation 源码）

- `testEachFailedOnSuccessDisposalPinsOriginalSessionUntilAllResourceReceiptsExist`：用原 `GuiRuntime.captures/captureFinished/close` 和 `GuiRunSession` 的主循环释放链，逐项模拟 software/hardware/buffer 抛异常。检查三项均尝试、原 capture 计数和 closing lease 保留、重复 close 仍不产生 releaseConfirmed。
- teardown 通过同一 helper 真正释放余下的假资源，以免污染进程；没有直接注入 resourcesDisposed。该 teardown 不是新增生产重试。全部资源释放成功后才移除原 capture/closing lease，重复成功也只有一次 terminal receipt。

资源对象在这些异常用例中是可抛异常的回调替身，不是真实 Android Bitmap/HardwareBuffer；实际平台对象故障和真实 TakeScreenshotCallback 设备路径仍未执行。业务连接证据是生产 onSuccess finally 直接调用同一 helper，以及 instrumentation 使用生产 Runtime/session 的释放记账，而非复制一套释放判定。

## 未执行／交接

本批所有新增和原有回归均未执行；未做 Kotlin 编译、JVM/Robolectric 测试、Android instrumentation 或真机截图验证。建议主代理在其隔离 JDK21 构建环境审核并执行：

- JVM：`app.daiyu.android.gui.GuiCaptureDisposalTest`（并回归已有 `GuiCaptureRequestTest`）。
- Instrumentation：`app.daiyu.android.ControllerGuiStorageRetentionTest`、`app.daiyu.android.gui.GuiCaptureDisposalProductionTest`（并回归已有 `GuiCatalogProductionTest`）。

上列为待执行类名，不是已执行命令或通过结果。已有其他测试的 CI/JDK 兼容问题不由本增补修改或声称解决。
