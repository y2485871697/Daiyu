# P03：会话网关真实生产接线

## 范围与验证状态

本增量复用已合并 `3dd021f` 的 wire / codec / 受控屏实现，完成 Main 导航、Controller conversation 传递与独立页面保存；不改重构总清单、参考原件、数据库、供应商 allowlist 或主模型 fallback。实现代理只能读写源码，**没有运行构建或测试，也没有创建 Git 提交的工具**；以下是可定位的接线与新增测试源码，不是测试通过声明。完整测试、提交及 detached main 状态由主代理最后统一核验；不编译 APK、不调用供应商、不读取实际凭据。

## 冻结证据与兼容语义

wire 增量原记录的参考来源位于 `docs/reference-preserved`，来源清单为其 `provenance.json`（source_commit `f001fb82eb9941e5673970c656f5c4d5370fbf7f`）；本增量未修改原件或重算哈希：

| 参考件 | 原记录 Git blob | 保留行为 |
| --- | --- | --- |
| data/model/SessionGatewayRule.kt | 630e070af5c4110a9e754ed4955d483daeba5106 | 五字段、模型全匹配、完整 URL containsMatch、坏正则不命中、安全键回退 |
| agent/model/ResponsesRequestBuilder.kt | e0732a6d56237953da2cdfbb8a37f7327c05559e | custom/运行时 instructions 后写会话键；官方 Codex 精确路径例外 |
| agent/model/ProviderRequestHeaders.kt | e15ca17799e0f9665c3329adfe493f99723f72d1 | session-id/thread-id 同 conversation；精确 opencode.ai 的 nameUUID 头 |
| agent/model/AnthropicSessionAffinity.kt | 78aab79b00020308bf91253727fb28fc4cec9c0d | SHA256 域分隔协议、匿名 user_id、保留 custom metadata |

不采用旧随机 session、产品 User-Agent 或 OAuth 客户端伪装。`SessionGatewayContext` 只持有明确的 `ConversationId`，`project` 固定 null；不查看 runId、history、全局选择、SessionCapabilities.sessionId，不伪造 global/project/opencode 路由。

Anthropic 的 `eta-anthropic-session-v1\0` 和 `eta-session-` 是兼容协议常量，不是 UI 品牌：

```
metadata.user_id = "eta-session-" + lowercaseHex(
    SHA256(UTF8("eta-anthropic-session-v1" + NUL + conversationId.value))
)
```

保留 metadata 的其他成员；已有 user_id（含空串/null）或非对象 metadata 均不替换。会话亲和性不保证服务端缓存 TTL。

`ProviderRequestOptions.sessionGateway` 五字段字符串默认值：`^gpt-.*$`、`/v1/responses`、`继承全局默认`、`gjson`、`prompt_cache_key`。旧三键 JSON 可读；新 codec 保留全部五字段。坏 regex 可存但不命中。keyField 按 `[A-Za-z0-9_.-]{1,80}` 校验，点号是字面顶层键；无效语法回退默认键；运行时协议字段禁止覆盖。customBody 原协议能力不变，匹配使用 merge 后的最终 model 与真正请求 URL。

**retention/keySource 未核实的值可以保存和 codec 往返，但执行明确拒绝**，不猜 TTL、store、project 或其它服务商行为。此拒绝也适用于旧无 context 入口。没有 context 时不推断或注入身份；不把 affinity 当权限。

## Main 独立页面与实际保存链

源码定位均使用唯一符号/字符串而非易漂移行号：

1. `MainActivity` 的 `"settings"` 分支新增 `open-session-gateway` 标签按钮，调用 `openSessionGateway()`；独立路由为 `"session-gateway"`，不是展示一个无回调的屏。
2. `openSessionGateway()` 检查 Activity、Controller ready/idle/dataBusy，创建单次 `ModelSettingsSaveOwner`；`lifecycleScope + withContext(Dispatchers.IO)` 调用 `settings.loadForEditing()`，只把仍属于同一 visit 的结果安装为 draft。未读完成可返回，旧结果不得重新挂载。
3. 页面显示五字段，`SessionGatewaySettingsScreen` 是纯受控屏，不访问 store 或模型。retention/keySource 警告不禁存；keyField 冲突禁存。不可读策略显示原“授权替换策略”开关，未授权禁存。此页面不提供协议/密钥 rebind 权限；凭据/协议无效须先在模型设置修复。空密钥不可保存。
4. `saveSessionGateway(owner, value)` 检查 owner、draft 引用、页面与原 admission；仅 `editing.settings.requestOptions.copy(sessionGateway = value)`，再复制原 ModelSettings，保留 customBody/speed/codex、端点、模型、协议、凭据及工具开关。
5. **实际写入只经 `ChatController.saveModelSettings`** 的新兼容重载；Main 不直接写 settings。原四参重载保留，原 `protocolChangeAuthorized` / `requestOptionsReplacementAuthorized` 均继续传给 `ModelSettingsStore.save`。网关页传协议授权 false，不可读替换仅用明确开关。
6. Controller 原 `dataTask` 同步预留 dataBusy，在其 worker 上取得原 catalog 配置 lease、核对 `loadForEditing() == expected`、通过 visit.commit 再调用原 save。取得 catalog lease 后才检查原子 visit fence，因此离页可撤销排队保存；新 provider/config 不能被旧加载页覆盖。外层配置 lease 与原 save 的嵌套 lease 使用既有重入支持；失败可保守推进 catalog revision，不能被理解为凭据保存成功。
7. `retainedPage` setter 离页即 revoke 并清空 draft；`onStop` 撤销且返回设置；`onDestroy` 再次清理。回调只有原 owner 仍当前才改 UI。commit 的原子 current 检查是 save admission 点；之后的持久化可以在离页后于 worker 完成，离页不阻塞主线程，也不把不确定保存说成取消或自动重试。`ModelSettingsSaveOwner` 不读取 Activity/Compose 状态。

普通模型编辑的 `ModelSettingsDraftUi.toSettings(base)` 改用 `base.requestOptions.copy(customBody=..., speedMode=..., codex=...)`，不是三参重建。普通保存和 `requestSettingsCatalog` 共用此转换，所以后者也不会丢五字段；远端目录调用不持久化网关规则。

## Controller 真实 conversation 接线

`ChatController` 末尾新增可空三参 `sessionModel(provider, assistant, session)`，生产次构造器设为 `AssistantModelFactory::create`。旧 `model`、单参 `imageModel`、双参 `assistantModel` 注入均保留；未提供新 seam 时仍走原测试行为，不暗中给旧模型推测 context。生产 AssistantModelFactory 继续冻结原 provider 与 assistant instructions，且按 Responses/Messages 选择原适配器。

| 入口 | 已接线源码位置 | conversation 来源 |
| --- | --- | --- |
| 普通首发/续发、图片、终端、浏览器等主会话 | `prepareSend` 的 `val delegate=sessionModel?.invoke(...)` | 同一函数已确定的 `conversationId`，来自已有或 admission 预留身份，与实际 user/assistant DB 提交相同 |
| 子模型工具批次 | `prepareSend` 的 `childBindings.freeze` 闭包 | withChildren 已要求存在的 `checkNotNull(conversation).id` |
| child 记录命令/重启后命令 | `directChildTaskPinned` 的 `childBindings.freeze` 闭包 | 经 conversationExists 校验的 `command.source.owner.conversationId` |
| 原视频/携视频历史文本续发 | `ControllerMessageVideos.wrap` 的默认四参 `modelFactory` | 显式 `conversation`，传 `ResponsesModel(... sessionContext=SessionGatewayContext(conversation))` |

域模型当前 child 的 `ChildRunOwner` 与 initial message 都归属父持久 conversation，所以共享父 affinity，不用 childRunId/profileId/新随机 session。将来只有域模型真正提供独立持久 child ConversationId 才能改变此归属。

模型构造可在 DB admission 前预备；Runner 的 actual open/POST 在既有 DB receipt 接受后执行。本增量没有改变该顺序，没有因为 context 放宽 assistant/binding、工具、图片、终端/浏览器、child 池或 admission 检查。独立辅助视觉、摘要、pet 工厂未被冒认作主 conversation。

## 视频顺序与禁止扩容

`ControllerMessageVideos.wrap` 默认 factory 仍是四参；注入 factory 的旧测试不变。默认 factory 传入真实 conversation，同时保留 capability、冻结 assistant instructions 和 SAME 每 POST approval provider。

`ResponsesModel.Session.next` 的顺序仍是 custom merge → 冻结 instructions → SessionGateway 注入 → `videoPlan.validateRequest` → 最终 bytes → `VideoPostRequest` → 逐 POST approval/permit consumption → connect/POST。每次工具续轮都重新验证并批准最终 payload。

**没有放宽视频 allowlist。** 默认 gpt 正则不匹配测试/常见非 gpt 视频模型时，仅发 conversation headers，仍须逐 POST 批准。用户改规则令其命中并引入新身份字段，最终视频 allowlist 在批准/连接前拒绝，不能把注入移到审批 hash 后。未核实 retention/keySource 也在批准/连接前拒绝。

## 可查询测试源码与验证边界

新增/扩展测试（尚未运行）：

- `SessionGatewayMainSettingsTest`：真实 Main 导航、五字段含未知语义保存、普通模型设置保留规则、离页/Activity recreation 旧回调拒绝、provider 变化不覆盖、不可读策略显式替换；无模型 open。
- `ControllerModelSettingsTest`：catalog lease 等待期间 owner 可撤销、worker 不落旧保存；Controller 原不可读替换和协议授权仍必需；保留原 save/select 预留测试。
- `ControllerAssistantBindingTest`：新 seam 优先、首发 actual DB conversation、续发跨 run 稳定、不同 conversation 隔离、重建 Controller 后同 conversation 稳定、恢复本身不启动历史模型；未绑定 assistant 仍拒绝；原旧双参测试保留。
- `ControllerChildModelsTest`：父工具续轮与 child 均取父持久 conversation，APPEND 命令仍同归属；未绑定 child 不造模型、不消耗草稿；保留既有旧工厂用例。
- `ControllerTerminalSendTest`：真实终端确认/接收身份与 session factory context 相同，未确认仍零模型；测试模型不请求工具，不启动 shell。
- `ControllerBrowserSendTest`：真实浏览器确认/接收 conversation 传入新 seam；模型不请求工具/打开网页；保留禁用、未确认、过期 ticket 等拒绝路径。浏览器本地 runtime 挂载需在统一 Android 环境执行验证。
- `ControllerVideoPostApprovalWrapTest`：默认 factory actual open（不调用 next）反射检查不可变底层 session 的 conversation 和冻结 instructions，保留四参注入/逐 actual-open 冻结与缺批准拒绝测试。此反射是源码测试证据，不是网络测试。
- `VideoPostApprovalTest`：显式 context 工具续轮每次新批准且最终发出 bytes 的 SHA256 等于批准摘要；未知语义与匹配身份扩展在批准/连接前拒绝。
- 已有 `SessionGatewayWireTest`：实际 Responses/Messages serialization → HTTPS 替身、每轮工具 POST/重建、最终 model/URL、custom metadata、旧无 context 不猜 ID。已有关联 `SessionGatewayPolicyTest`、`SessionGatewaySettingsScreenTest` 与 `AssistantModelFactoryTest` 继续保留。

统一验证建议：先编译/运行 JVM model/options/wire/video 测试及 Android test 源码，再在既定 Android 测试环境执行上列 Controller/Main 及现有 assistant、image/video、child、browser/terminal 回归。不要为此构建 APK、调用付费供应商或读取真实凭据。最终由主代理给出真实执行命令、结果、未执行项和非空提交 ID；不得把此文档当作通过或提交证明。
