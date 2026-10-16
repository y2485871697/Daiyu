# 双协议图片模型入口：宿主接缝

本次在已含公共 Image block 契约、既有 raw 扫描修复和读取前全历史限额的组合基线上，仅补齐 Responses 内建 `code_interpreter_call.outputs` 的 raw 图片定向扫描；直接使用正式源码，无需额外 SOURCE 输入。不重造公共 API，不修改 domain、数据库、Controller、UI、media 或 backup。

## 已实现

- `ModelPort.open(history, definitions, images: ModelImagePlan)` 是显式图片入口；默认实现拒绝，而不是丢弃图片。
- `FrozenMessageImagePlan.freeze` 展开完整历史，以原始消息/segment 的身份逐条授权、解析，使用 media `MessageImageResolver.freeze` 读取冻结原图；不读取 URI、缩略图或任意文件。不重复发送 standalone supplement。
- `expectedOwner: (Message) -> domain.MessageImageOwner` **必须由宿主根据已授权持久化 row/segment 身份提供**，不能返回 `block.reference.owner`，不能统一使用当前会话/当前消息 owner。实现检查 expected 与展开后的 row 的 conversation/message ID 以及所有引用精确相等。实际 resolver 接收该 expected 转换的 media owner。跨 owner、同消息重复、缺图、错误 metadata/digest、取消均直接失败；不同消息可使用同一 image id，逐 owner 分别解析。
- plan 私有保存完整展开历史快照和验证后的不可变 wire 字符串；每次构建返回新 JSONObject。绑定最终请求 endpoint、协议、模型，不能跨历史/配置重用。不会在后续轮重新读磁盘。
- Responses 实际 `Session` 使用 `ResponsesRequest`，混合用户消息按 Text/Image 原顺序构建一个 content 数组；无图片保留原有 text-only wire shape。Messages 实际 `MessagesRequest.history` 同样按原位置插入图片；工具结果规则不变。
- raw 图片检查仅沿 Responses/Messages 协议位置：消息 content、Messages tool_result.content/document.source.content、Responses 多模态工具 output、内建 `code_interpreter_call.outputs` 复数数组，以及请求/响应 input/messages/output 数组；这些位置中的 raw 图片拒绝，包括原本会被忽略的 user/raw 分支。不会递归解释 tool_use.input、函数 arguments、metadata 或 schema 等业务 JSON；其中的 type:image/image_url 允许保留。有本地图片的消息仍不能同时重放 raw items（即使 raw 只有文字）。不认可 raw data URL/remote image URL 为授权，服务端输出的 raw 图片也不进入续轮。
- 两个 transport 每轮在 request options 完成后、connect 之前序列化并校验最终 UTF-8 字节，发送同一 ByteArray。带图不允许 custom body 改 model；无图的既有 custom model 行为不变。失败后沿用不可重放门闩，没有文本降级、自动重试或备用模型。

## 宿主必须注入的参数

```kotlin
// capability 来源必须是可信的所选 endpoint + protocol + model 配置，不按模型名字猜测。
val provider = ProviderSnapshot.capture(settings, settings.protocol, imageInputSupported = trustedCapability)
// 默认 capture/settings.requestSnapshot() 保持 imageInputSupported == null，不默认启用。
val plan = FrozenMessageImagePlan.freeze(
    history = authorizedHistory,
    provider = provider,
    resolver = messageImageAssetStore.resolver,
    expectedOwner = { expandedRow -> authorizedOwnerForPersistedRowOrSegment(expandedRow) },
    isCancelled = { runCancellation.isCancelled },
)
val session = ModelFactory.create(provider).open(authorizedHistory, frozenToolDefinitions, plan)
```

`authorizedOwnerForPersistedRowOrSegment`、`runCancellation` 为宿主占位名，不是仓库新增 API。不要在应用层把 frozen provider 转回 ModelSettings 再调用旧工厂：ModelSettings 当前没有 capability 字段，会丢失能力并拒绝图片。用 `ModelFactory.create(provider)` 保留绑定快照。注入 encoder 参数仅供已有 ImageInspector/限制配置或测试；生产默认 AndroidImageInspector。

**Controller 组合已接入**：`ControllerMessageImages` 为主模型与保存的子模型提供 wrapper，
每次 open 都按当前 SQLite 持久化消息/segment 身份重新授权后冻结图片；主模型使用绑定的
ProviderSnapshot，不转回丢失 capability 的旧配置。`sendImages` 使用持久发送身份、草稿 CAS
及双消息事务回执，Accepted 前不消费图片草稿，Unknown 不自动重发。
可信 endpoint/protocol/model 能力仍需显式选择；默认 capture 保持 null，保存的子模型仍拒绝
无授权图片。MainActivity 的选图与能力确认宿主另路接线，尚未证明 UI 到网络端到端可用。


## 保守限制

编码器默认全请求累计最多 8 图、24 MiB 原图、每图 10 MiB、4000 万像素；按完整展开历史累计而非只算最新草稿。`freeze` 在任何 resolver 调用前，使用注入 encoder 的同一限制配置校验所有图片引用的数量、单图声明字节和累计声明字节；超限时 resolver 调用为零，不逐消息先读取再限额。读取后仍检查 resolver 身份匹配，并由 encoder 再次限额、核验实际 bytes/digest/MIME/尺寸；声明字节不是免验证凭证。JPEG/PNG/WebP 原字节不转码。Messages 额外每边 <=8000、每图 Base64 <=10,000,000 字节。完整请求两协议统一 **32,000,000 UTF-8 字节**本地上限（十进制，包含所有历史、工具 schema、结果及 request options），这是保守本地策略，不是承诺所有供应商/代理都接受；伙伴端点可能更严。

## 验证范围

`ImageModelInputTest` 直接调用生产请求 builders 和两个实际 ModelSession，以内存 HttpsURLConnection 记录字节，无真实请求。覆盖混合顺序、展开历史和跨消息 owner 精确解析、原 bytes、能力 null/false、无 plan、跨 endpoint/protocol/model、custom model、raw 绕过/重复、坏 owner/重复/缺图/取消、历史突变、续轮及最终 UTF-8 大小和零额外连接。新增无图工具参数/metadata 在历史、真实 transport 和续轮中的兼容性；嵌套协议图片仍拒绝；跨消息/segment 数量、单图与累计字节超限时 resolver 调用为零（含自定义 encoder 限额）、精确边界成功，以及读取后 bytes/digest/尺寸不匹配仍拒绝。

本次新增 `code_interpreter_call.outputs` 测试源码：直接扫描拒绝 raw 图片（含 logs 后的图片及响应 output envelope）；`ResponsesRequest.history` 与实际 Model open 在零连接、零图片读取时拒绝历史；实际 Responses Session 消费含合法 function call 的 completed SSE 后拒绝 raw 图片，且不得发起续轮或发布 ProtocolOutput。logs-only 对照使用同一内存 HTTP fixture，覆盖完成响应及正常续轮；普通 tool input/arguments/metadata 中同名业务字段仍合法，不递归解释 logs 文本。

未运行构建或测试（任务明确禁止）；以上描述为实现和测试源码覆盖范围，不代表执行通过。测试执行由主代理安排。大请求上限测试会分配约 32MB payload，应在主代理测试环境关注 JVM heap。
