# ProviderEventDeliveryGate 复用来源与适配范围

## 来源

- 已核准自研来源基线：`f001fb82`（本次任务指定的固定快照）。
- 自研引入/扩展历史：`47f5258afd9c8035583b4e3a1132d4dbde46668f`（`47f5258a`）、`e75e7e761d9143ba87c35d1dff5ef2b24f3e67c7`（`e75e7e76`）。
- 此组件不属于比较上游 `c16a5873`；不将其归为该上游作者源码。
- 原路径：`app/src/main/kotlin/[historical-package-omitted]/agent/model/ProviderEventDeliveryGate.kt`，及原同包 `ProviderEventDeliveryGateTest.kt`。
- 实现依据为父代理复制至本工作树 `docs/refactor-input/reuse-provider-gate/` 的源码、原测试、历史记录和两份补丁；未访问外部工作树。两份补丁均展示同一闸门内容（blob 缩写 `a1584940`），历史文本记录 `e75e7e76`。固定快照归属及上游排除来自本次核准任务；本实现代理未独立读取这些提交或比较上游仓库。

本说明保留来源 SHA 和迁移边界，不附旧宿主源码或补丁；临时复制输入在迁移核对后删除，不纳入提交。

## 唯一适配

1. 实现及测试包名改为 `app.daiyu.android.domain`，移至 `core-domain` 对应源集。
2. 类由 `internal` 改为 Kotlin 默认 `public`，使宿主应用模块可以跨模块使用；成员与方法签名不变。
3. KDoc 中旧宿主的 `AgentProviderClient.complete`、`AgentSseClient.SseStream.finish` 链接及宿主回调名称改为中性说明。保留“流结束早于回调退出，旧尝试迟到事件可能污染下一请求 usage”的原背景，这是文档适配，不是删除来源或引入新的宿主实现假设。

未重新设计算法：保留 `ReentrantLock`、`@Volatile closed`、锁内判定与同步投递、`finally` 解锁和关闭等待在途回调的原逻辑。关闭后投递被丢弃，关闭不等待网络收尾。

未修改或接入 `ChatRunner`、`ModelEvent`、Controller；未修改构建配置、签名或外观。本次仅落地可复用组件及原测试。

## 原测试与验证状态

原测试除包名外完整保留，未调整并发同步、线程状态断言、超时或清理逻辑：

- `closeWaitsForInFlightDeliveryThenDropsLaterEvents`：关闭等待在途投递结束，关闭后丢弃事件。
- `deliversInOrderUntilClosed`：关闭前按调用顺序投递，关闭后不再投递。

已静态核对迁移范围；实现代理未运行构建或测试，不能据此宣称测试通过。父代理负责执行 `:core-domain:test`、审查并合并。
