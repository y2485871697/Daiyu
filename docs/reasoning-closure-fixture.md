# ReasoningClosureTest 截断 fixture 修正

本次仅修正 `app/src/androidTest/kotlin/app/daiyu/android/ReasoningClosureTest.kt` 的合成输入，不改生产代码。

- 原 Messages `terminal = false` 只省略终态帧，仍发送 `text = "answer"`。`MessagesStream` 在 text block start 即交付 `TextDelta`，`ChatRunner` 会接受并保存正文；因此非取消截断分支与“所有 finalText 为空”的断言冲突，不能靠丢弃已收到的正文修复。
- Messages fixture 新增默认开启的 `includeAnswer`，仅在 reasoning-only 截断/取消用例显式关闭。成功和工具续轮用例保持原输入；`terminal` 仍只控制终态帧。
- 对称核对 Responses：其 `answer` 只在 `response.completed` 中发送，`responses(terminal = false)` 本来就只发送公开 reasoning delta，无需改动。
- 保留失败/取消非完成状态、公开 reasoning 存在、协议项为空、数据库重开恢复相等、恢复后 reasoning 显示、无正文/无 workProcess、无工具调用及 session 不重放的原断言。取消仍发生在已交付公开 reasoning 之后。

证据来源为上述测试、`app/src/main/kotlin/app/daiyu/android/model/MessagesStream.kt` 的 text start 分支及 `core-domain/src/main/kotlin/app/daiyu/android/domain/ChatRunner.kt` 的 `TextDelta` 分支。这里只静态核对源码，未构建或运行测试；运行验证与最终集成由父任务审查后进行。本修正不新增“已接收正文后截断仍保留正文”的独立用例，也不声称覆盖该额外场景。
