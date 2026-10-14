# ConversationCompressionSendPolicy 复用记录

## 来源与证据

本次迁移的是已核准的自研策略，不重新设计算法，也不将其归为比较上游的代码。

- 指定固定发布：`f001fb82`（任务提供的发布 SHA）。
- 首次引入：`94ffe9322cf8596b87eec26cccdba7c92a556bdf`，提交说明为 `fix: scope compression send blocking to its conversation`。
- 比较上游：`c16a5873`；本策略不属于该比较上游。
- 原策略路径：`app/src/main/kotlin/io/github/mangi/eta/ui/app/ConversationCompressionSendPolicy.kt`。
- 原测试路径：`app/src/test/kotlin/io/github/mangi/eta/ui/app/ConversationCompressionSendPolicyTest.kt`。

实现时只读取主代理复制到本工作树 `docs/refactor-input/reuse-compression-policy/` 的四个临时文件：策略源码、同名 Test 源码、`ConversationCompressionSendPolicy.history.txt` 和 `ConversationCompressionSendPolicy.94ffe932.patch`。历史记录和首次提交补丁直接支持上述首次引入 SHA，补丁中的策略与输入策略相同。固定发布标识来自任务指定；本实现未访问外部仓库或独立查询该发布。临时输入不纳入交付，读取与核对后删除。

## 必要适配与保留语义

交付文件：

- `core-domain/src/main/kotlin/app/daiyu/android/domain/ConversationCompressionSendPolicy.kt`
- `core-domain/src/test/kotlin/app/daiyu/android/domain/ConversationCompressionSendPolicyTest.kt`

只作两类适配：

1. 源码和测试包名从 `io.github.mangi.eta.ui.app` 改为 `app.daiyu.android.domain`，移动至 `core-domain` 对应源集。
2. 策略函数从 `internal` 改为显式 `public`，供跨模块调用；函数名、参数、返回类型、注释和布尔表达式均保留。

测试除包名外保持原样，保留全部 12 个 `@Test`，未增删断言或修改辅助函数。

发送阻塞条件仍为：当前会话自身正在压缩，或者活动共享任务属于当前会话，或者待处理请求属于当前会话。共享队列不是全局发送锁；无关会话不因其他会话的任务被阻塞。`null` 表示未保存草稿，只与同为 `null` 的任务/请求所有者匹配，不是所有会话的通配符。任务结束或无待处理请求时，仅所有者相同不足以阻塞。当前会话压缩标记仍独立生效，保留没有共享任务的 Runtime/pre-send 压缩语义。

本次不接入或修改 Controller、ContextService、ModelEvent，不修改旧 Eta、签名或外观；不涉及旧失败任务树。

## 验证边界

迁移前已读取本树 `build.gradle.kts`、`settings.gradle.kts` 和 `core-domain/build.gradle.kts`：目标模块为 Kotlin JVM，使用 JUnit 4.13.2 与 `useJUnit()`，原测试无需更换测试框架。

这里只完成文件内容核对及 12 个测试的完整迁移，未运行构建或测试，不宣称测试通过。实际构建、测试、审查及合并由主代理执行。
