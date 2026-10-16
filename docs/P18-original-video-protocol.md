# P18：OpenRouter Responses 原视频协议叶子

## 范围与证据

本叶子不实现 UI、消息数据库或媒体接收事务，也不改 `ChatController`、`MainActivity`、`ConversationDatabase`、`ModelSettings.kt`。它消费**已接受消息的原视频引用**，不是封面。没有扩充通用供应商框架或猜测模型名称。

原视频协议的官方文档依据（本次路由修复未另行联网核验）：
https://openrouter.ai/docs/guides/overview/multimodal/videos

文档的 Responses 示例为 `POST https://openrouter.ai/api/v1/responses`，用户 content 中为 `{"type":"input_video","video_url":"..."}`，不是 Chat Completions 的嵌套 `video_url.url`。文档支持本地视频的 `data:video/mp4;base64,...`，明确 Messages API 不支持视频，并提示模型与实际 provider route 的能力有差异。本实现只取最小 MP4 inline 子集；不发送 `processing`，不声称所有模型/路由都支持，不发送远程 URL、`input_file`、封面或提取帧。

本次未查询任何实际模型账户能力，未读取凭据，未在线推理。代码/新增测试只做静态核对，未构建或运行。

## 父代理必须实际接线的输入

1. 从本次固定的 `ProviderSnapshot` 取得确切模型、最终 `requestEndpoint()`、协议。必须由可信且当前的能力来源确认**这个模型在这个端点可接收 inline MP4 原视频**，并有显式上传配置许可。模型名字、图片能力、仅仅选择 Responses、备份恢复的布尔值或不可信网页/工具输出均不是授权依据。
2. `OpenRouterVideoCapability.grant(...)` 同时要求 `verifiedInlineMp4Support=true` 与 `uploadEnabled=true`。端点必须精确为 `https://openrouter.ai/api/v1/responses`。默认 ModelFactory/Responses 构造不创建该授权。若缺乏能力证据/配置或使用 Messages，应在接收/运行入口明确拒绝原视频，**不要走普通 text-only ModelPort 路径**。
3. `AndroidVideoAttachments.import` / `VideoAssetStore.import` 保存原字节；`prepare(MessageVideoOwner, attachment)` 得到 `MessageVideoReference`。父必须先将其与用户消息在原有接收事务中持久化；prepare 不是 DB 接受。之后按现有契约处理草稿删除与未知事务结果。
4. 为**完整展开历史**的每条带视频用户消息（包括 history segments）加载所有持久化 `MessageVideoReference`，按附件顺序构造 `MessageVideoInput`。不要只传当前草稿/当前行，不要用 `VideoPreview`。本叶子不访问 DB，不能发现父漏传了某行的附件；遗漏即丢失用户视频语义，因此父必须对此 fail closed。
5. `expectedOwner(row)` 必须从真实已接受行/segment 的授权映射取得，并核对当前会话/账户/执行归属；不能简单相信传入引用的 owner。冻结层进一步要求它等于当前展开行的会话 ID + 消息 ID，并要求引用完全匹配存储记录。
6. 同一 `OpenRouterVideoCapability` 实例用于 freeze 和本次 ResponsesModel。能力、模型、端点或历史变化需要重新验证并冻结；不能复用旧计划。
7. freeze 是阻塞 IO，应在工作线程执行，传入本次 run 的真实取消回调；准备时还没有 ModelSession，不能只依赖稍后 session.cancel。取消/切会话/换账户/删除消息与发送必须维持父原有生命周期 fencing。

最小实际调用形状（父负责真实授权映射及既有锁，不是可直接替代授权校验的伪数据）：

```kotlin
val permission = OpenRouterVideoCapability.grant(
    model = snapshot.model,
    endpoint = snapshot.asModelSettings().requestEndpoint(),
    verifiedInlineMp4Support = trustedExactRouteSupportsInlineMp4,
    uploadEnabled = explicitOriginalVideoUploadEnabled,
    maxVideoBytes = verifiedPerVideoByteLimit,
    maxTotalBytes = verifiedWholeHistoryVideoByteLimit,
    maxVideos = verifiedVideoCountLimit,
)
val plan = FrozenMessageVideoPlan.freeze(
    history = history,
    provider = snapshot,
    capability = permission,
    inputs = acceptedVideoInputsForEntireExpandedHistory,
    store = androidVideoAttachments.store,
    expectedOwner = { row -> requireNotNull(authorizedAcceptedOwners[row]) },
    isCancelled = runCancellation,
)
val model = ResponsesModel(snapshot, videoInputCapability = permission)
val session = model.open(history, definitions, videos = plan, images = frozenImagePlanOrNull)
```

`ResponsesModel(snapshot, ...)` 为同 app 模块的 internal 构造，父现有代码可直接调用。若父通过 `ModelPort` 装饰器接线，必须在该装饰器的每个相关 open 重载中准备/传入 videos（及已有 images），然后返回此真实 session。通用 `ModelPort`、`ModelFactory` 未增加视频入口或自动放行；不能先向下转型未知模型并在失败时退回普通 open。建议父先判定是否有视频，若有则明确选择此叶子并验证授权，否则完全保留旧路径。

## 行为和预算

- 不新增 domain MessageBlock：`MessageVideoInput` 是显式原视频侧车。该行原有文字/图片按原顺序，视频按引用列表顺序追加到 content。若产品要支持视频与文字的任意穿插位置，父需另行设计持久化顺序，不能声称当前叶子已有此语义。
- `VideoAssetStore` 既有导入使用 Android 解码/容器检查。模型读取再次验证所有者、完整记录、长度、SHA-256，并对实际读取的有界内存快照再次验长/验哈希；不导出文件路径/URI。冻结后不再读文件。
- 在读取任何原字节前，对全部展开历史预检用户角色、owner、重复项、MP4 MIME、附件数量、逐个/合计原字节预算。默认逐个/合计上限均为 16 MiB、最多 4 个，授权可调低不可调高；这不是供应商保证的上限。已导入但不在该预算/MIME 子集中的视频会被明确拒绝，不会降级为封面。
- 最终整个 JSON 请求（文字、图片、视频、工具、选项以及续轮历史）仍受既有 32,000,000 字节上限。即使视频预检通过，整个请求仍可能拒绝；发生在连接前。不得无限放大图片/视频组合预算。
- 视频请求使用专用、默认拒绝的请求选项允许列表，不能只依靠最终 `model` 字段判定收件模型。`customBody` 仅允许 `temperature`（有限数值 0..2）、`top_p`（有限数值 0..1）和 `max_output_tokens`（正整数）。这些字段只接受标量；对象、数组、null、字符串数值和越界值均拒绝。空自定义正文仍允许。
- 其他自定义字段全部拒绝，包括 `model`（即使等于授权模型）、`models` fallback 列表、`provider`、`route`、`plugins`（包括 auto-router 和空列表）、`routing`、`service_tier`、未知扩展，以及通过任意对象/数组嵌套携带的路由设置。视频路径也不允许 speedMode/Codex 预设；不把可能影响路由的服务档位冒充普通采样选项。不会新增自动 fallback 或重试。
- 该预检在 `OpenRouterVideoCapability.validate` 执行，因此 freeze 在读取任何原视频、检查 owner 回调之前拒绝不安全选项；复用已冻结计划打开 session 也再次检查。`ResponsesModel.next` 在全部选项/assistant instructions 合并后、序列化/连接前，每轮（包括工具续轮）调用视频计划的最终 payload 检查：仅允许现有协议字段和上述三个标量选项，并核对精确 model、完整 input、stream/store/include 控制。不是路由键黑名单，新增未知网关字段默认无法进入视频 payload。
- 不改通用 `ProviderRequestOptions`；普通非视频 Responses/其他协议仍保留原有自定义请求能力。允许列表仅约束本地请求，不是对远端默认 provider 选择的保证；父仍必须取得对应模型/端点实际原视频能力证据，缺少证据不能授权上传。
- 原始 protocolItems、自定义正文的协议位置与供应商返回协议项中的裸 `input_video`/`video_url`/`video` 均拒绝；业务工具参数或工具结果中的 JSON 文本不被误解为路由设置/协议。默认 Responses 和 Messages 同样拒绝裸视频。
- 不改 ResponsesStream、Messages 的传输、成功终态/截断处理、工具续轮和取消语义。Responses 已有 `reasoning.encrypted_content` 输出项仍保留在续轮输入中。视频 data URL 不进入 ProtocolOutput，只存在该次内存请求计划/输入中。

## 静态测试覆盖（尚未运行）

`OpenRouterVideoInputTest` 使用假 inspector + 明确标注的非媒体合成字节、假 HTTPS 连接；覆盖真实请求构造传输的 MP4 data URL、精确授权/端点/模型绑定、无授权拒绝、快照不再读盘、owner/元数据/同长原文件篡改、全历史读取前预算、Messages 和裸协议拒绝、取消前零连接、截断无 ProtocolOutput、整请求预算。

本次路由修复补充：`models`/`provider`/`route`/`plugins`、未知扩展、嵌套路由和非法采样类型在原文件已删除时仍于 owner 检查前拒绝，并保持零连接；合法计划不能绕过新的 session 配置预检；对首轮和工具续轮形状的最终 payload 注入同样的路由反例仍拒绝；实际假传输的两轮请求均保留三个合法采样/预算选项、唯一 model 和同一原视频/加密 reasoning；普通非视频假请求仍可发送原有路由选项。以上只静态核对源码，未构建、运行测试、生成 APK、创建分支或推送；也未为本次修复访问凭据或发起真实推理。

这些不是实际 Android 解码或 OpenRouter 服务兼容性的运行证据。已有 AndroidVideoAttachmentsTest / VideoAssetStoreTest 与 Responses/Messages 测试应由父在集成后按项目流程运行，并补父的 DB/历史侧车完整性、生命周期和 UI 接收测试。
