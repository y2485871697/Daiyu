# 通知 review21258681：缓存回执交付前重新 admission

本修复针对任务指定基线 `7dcafd80` 上 review21258681 的唯一 P1：同批工具全部通过 admission 后，先执行的新 read 可以改变通知授权、run 关闭状态或通知访问 epoch；后续既有 `CONFIRMED` 回执原先直接 replay，因而仍可能交付旧通知 payload。

## 生产修改边界

仅修改 `core-domain/src/main/kotlin/app/daiyu/android/domain/tools/ToolDispatcher.kt` 的既有 `CONFIRMED` replay 分支。保留整批 prepare、授权检查和 reservation 前 admission；另外在**每个**缓存 `CONFIRMED` payload 返回前，使用该调用原有的 plan/context 再次调用 `plan.admit(context)`。

重新 admission 返回拒绝时，仅向本次调用返回 `DispatchOutcome.NotExecuted(denial.code)`；异常统一返回 `NotExecuted("context_admission_failed")`，不暴露异常内容或缓存 payload。成功时仍返回原 payload、`isError` 和 `replayed = true`。该分支不执行工具、不调用 `executeRead`，也不调用 `ledger.finish`，因此拒绝交付不会覆盖、清空或降级原有账本回执。

其他生产文件保持不变，包括通知后端、资源关闭逻辑、数据库、Manifest、Main、Controller 和 location。未修改已有清单或既有文档；本文件专属记录本次修复。

## 确定性回归证据

`core-domain/src/test/kotlin/app/daiyu/android/domain/tools/ToolDispatcherTest.kt` 新增 `everyCachedConfirmedDeliveryIsReadmittedAfterEarlierBatchExecution`：先缓存 B、C，再派发 `[A 新 read, B 缓存, C 缓存]`。A 同步回调将 admission 改为拒绝或抛异常。测试检查整批 admission、A 执行、B/C 再 admission 的顺序；逐项拒绝不含 payload；缓存工具执行次数不增加；原账本项逐字段相等。恢复 admission 后仍可 replay 原 `isError = true` 回执，避免把交付拒绝持久化为执行失败。

`app/src/test/kotlin/app/daiyu/android/integration/tools/NotificationRunBoundaryTest.kt` 新增四类混合批次回归，对 `current_notifications` 与 `notification_history` 均覆盖：A 的 read 回调关闭资源、撤销访问或断开监听、录制 off→on，以及 disconnect→reconnect。后两类在回调结束时 live 状态已恢复，只有 run 冻结 epoch 与当前 epoch 不一致才能拒绝旧回执。A 使用非通知 TIME read，避免 A 自身的通知 read 交付检查掩盖 B 的 replay 缺陷。断言包括 A 正常完成、B 无 payload 的 `NotExecuted`、仅一次通知实际读取，以及原通知账本项不变。

这些集成边界测试沿用 synthetic `PlatformReadBackend`，通过真实 `AndroidToolRegistry`、`RunDeviceResources`、`ToolDispatcher` 和内存账本。epoch 部分显式模拟既有 `AndroidPlatformReads.admission` 的冻结契约，并不声称覆盖真实 Android 系统授权、监听服务或数据库生命周期。

## 验证状态与限制

仅通过工作树源码读取、差异检查和静态分析核查。实现代理没有 Shell/构建工具，未运行编译或测试，也未验证 Git HEAD/detached 状态。建议主代理在受控环境运行 `ToolDispatcherTest` 与 `NotificationRunBoundaryTest`，并审核本次生产差异只涉及上述 replay 分支。这里不宣称消除了 admission 返回之后任意并发状态变化；修复范围是同批先行调用改变访问状态后，缓存交付缺失 admission 的路径。
