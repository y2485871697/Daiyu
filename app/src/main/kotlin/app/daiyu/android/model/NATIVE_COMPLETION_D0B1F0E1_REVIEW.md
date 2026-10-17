# NativeCompletion d0b1f0e1：父最终集成专属小 delta

## 基线与交付边界

- 目标候选为委派任务指定的 `d0b1f0e1`；本次没有执行 Git/Shell，因此没有独立验证 HEAD 哈希。
- 独立读取当前工作树确认：`NativeCompletionModel` 已实现 Chat Completions/Gemini；`ResponsesModel.Session.next` 有 `@Synchronized`；`ProviderRequestOptions` 原先通过 `optJSONObject("generationConfig")` 忽略非对象；当前树搜索不到 `ReasoningRequestPolicy` 或 reasoning freeze 实现。
- 这不是 main 合并，也不是原 27 文件候选的替代快照。父代理先审核/移植原候选净 diff，再取本 delta 的局部变更；不要用候选旧版覆盖 main 的同名文件。
- 不改共同清单、更新说明、DB、Controller、Main；不增加第三方依赖，不建立分支、不 push，不执行网络请求。

## 独立依赖（必须由父最终 main 提供）

`NativeCompletionModel.next` 现在直接调用：

```kotlin
val request = settings.requestOptions.apply(/* native body */, settings.protocol)
ReasoningRequestPolicy.validate(request, settings.protocol)
```

调用位于 apply 之后、任何 connect 之前，每次工具续请求也经过此处。第二个参数是本 run 冻结的协议，不依据模型名猜测能力。

**本树没有该类，因此此 delta 不是可单独编译的 d0b1f0e1 成品。** 它明确依赖委派说明中父 main 已有的共享 policy，不复制 main 的 policy/freeze 整体，不添加同名 stub 或宽松 fallback。调用假设 policy 在 `app.daiyu.android.model` 可见；若父 main 的实际 package 不同，只补实际 import。此次无法跨工作树读取 `/workspace` 中的实现，未声称已核验该实现的签名/全部契约。父集成必须确认此接口并保留其现有拒绝规则，不能为了编译删掉调用。

## 修复内容

1. **请求校验**
   - transport 对最终自定义选项合并结果调用共享 `ReasoningRequestPolicy.validate`。
   - Gemini 选项验证中，只要出现 `generationConfig`，必须是 `JSONObject`；显式 null、字符串、数组、数字、布尔均拒绝。
   - `generationConfig.thinkingConfig` 一旦出现就明确拒绝，包括 null、空对象、budget、level、includeThoughts；不默默丢弃，也不映射为 Responses/Messages 或其他字段。
   - 校验在保存选项和 apply 合并后都适用；普通空配置、单候选及 `maxOutputTokens` 不被这个限制误伤。
2. **next/cancel 并发**
   - `Session.next` 加 `@Synchronized`，与现有 Responses 一致，保护 replay guard、pending 与状态推进；不加重试。
   - `cancel` 保持独立、无 session monitor，继续使用 AtomicBoolean 和 volatile connection/disconnect，不能被持锁网络读取挡住。
3. **reasoning freeze 边界：父 main 必须原样保留，不能扩大协议能力**
   - 新增 native transport 或枚举不等于新增 reasoning 能力。
   - 对 `CHAT_COMPLETIONS`、`GEMINI`，只有 reasoning 的 **DEFAULT/未显式选择** 路径可以冻结；任何非默认显式选择仍必须拒绝，且发生在请求/POST 之前。
   - DEFAULT 不等于强制发送 `off`/`none`，也不能为了支持 native 而绕开主、标题、子代理已有的 freeze 入口。
   - 此候选不存在可修改的 reasoning freeze 类型，所以本 delta 故意不引入替身/复制件。父代理移植原 27 文件 diff 时必须保留 main 已有 freeze 的拒绝分支和回归用例；若 enum 扩展触发 exhaustive when，仅为两种新协议增加“DEFAULT 接受、其他拒绝”的分支，不新增思考等级/预算映射。

## 离线 fixture 与父集成验收

修改现有 `NativeCompletionTest`，无真实 HTTPS：

- `testGeminiGenerationConfigIsStrictAndThinkingIsExplicitlyUnsupported`：上述坏类型和 thinkingConfig 在 validate/apply/transport 入口拒绝，连接计数为 0；普通对象通过选项合并。
- `testMergedChatReasoningPolicyRejectsBeforeConnecting`：自定义 body 的非法 `reasoning_effort: false` 必须在连接前拒绝。该用例依赖父 policy 对非法推理请求类型的拒绝契约；父代理需核验真实实现，不能用放行替代。
- `testConcurrentNextCannotBothPassReplayGuardOrPost`：第一调用精确停在 replay check 之后、started/usable 写入之前，第二调用必须阻塞在 session monitor。释放后仅一次 POST，后者因不可重放失败。只阻塞网络读取会错过原 guard 竞态，因此没有采用那种弱 fixture。
- 现有 `testCancellationBeforeAndDuringReadIsPrimaryWithZeroRetry` 增加独立 cancel 线程和有界返回断言；finally 始终释放离线读取，避免错误同步 cancel 导致测试挂住。

父 main 还必须在它现有 reasoning freeze fixture 中确认两种新协议 × 所有非 DEFAULT 选择均拒绝、DEFAULT 仍可执行普通请求；本候选没有相应类型，未虚构该测试 API。主/标题/子代理如共用 freeze，保留其现有覆盖；若有独立入口，分别覆盖，不能只测 transport。

## 验证状态

**未跑构建、编译、单元测试或 instrumentation 测试**（本次实现代理不允许执行 Shell/构建；而且候选缺少上述父共享 policy）。仅完成源码读取和局部 diff 审查；不宣称 fixture 已通过、不宣称 freeze 已在此候选实现。父代理需在最终 main 依赖齐备后执行实际测试并记录结果。

父集成补充：最终 reasoning 校验移至 SessionGateway body 注入之后、bytes/connect 之前；Native 与 Responses 均补网关注入非法推理键零连接回归源码。
