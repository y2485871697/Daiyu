# P18 原视频能力证据与 run 级批准 leaf

## 边界与证据强度

本 leaf **没有接入 ChatController/MainActivity，没有修改 ResponsesModel、VideoAttachments、公共 DB 或清单**。不代表用户界面已能发原视频。本 leaf 的 run capability **不等于每次 HTTP POST 的上传同意**；另一个 POST-consent leaf 负责该检查，父入口必须组合两者。

协议资料来源为 OpenRouter 官方 videos 文档：
`https://openrouter.ai/docs/guides/overview/multimodal/videos`。
主代理提供的实际正文核实结果是：

- Responses 示例使用 `type: input_video`、`video_url: string`，示例 URL 是 YouTube URL。
- 页面整体说明可用 base64 data URL；展示该形式的例子属于 Chat Completions。
- Troubleshooting 指出模型 `architecture.input_modalities` 需要包含 `video`。

这构成 **文档层面的视频协议依据**，必须与本次真实目录 GET 的精确模型 `video` 能力同时使用。**不是已经成功执行 Responses inline MP4 的网络实测**，也不声称文档展示了该组合的完整例子。本实施子代理没有网页浏览工具，未独立重新抓取上述页面；以上是主代理提供的官方正文核实信息。实际模型、账户、供应商路由能否成功处理该组合仍需获准后实测。若审核认为上述跨示例依据不足以批准 inline MP4 Responses，应保持入口关闭，不可用 UI 布尔或模型名填补证据缺口。

## 生产能力入口（阻塞工作线程）

```kotlin
val snapshot: ProviderSnapshot = /* 此 run 保留的、从已保存配置捕获的同一个对象 */
val revision: Long = /* 父的 saved-config 单调 epoch */
val evidence = catalogService.fetchSavedVideoEvidence(
    snapshot = snapshot,
    configurationRevision = revision,
    currentConfigurationRevision = { /* 返回父的当前 saved-config epoch */ },
    isCancelled = { /* 真实准备阶段取消条件 */ },
)
```

`ModelCatalogService.fetchSavedVideoEvidence` 是 additive API，原 `fetchSaved/fetchDraft/fetch` 保持返回 `List<RemoteModel>`，普通选择器仍按原逻辑过滤/去重，不升级为授权。

证据规则：

1. 保存的 `ModelSettings` 必须逐字段等于 `snapshot.asModelSettings()`；抓取前后都检查保存值与 revision。随后 `matches` 每次再次读取它们，真实配置变动不能只靠传入旧 snapshot 掩盖。
2. 协议为 Responses，最终 `requestEndpoint()` 精确等于 `https://openrouter.ai/api/v1/responses`。不接受网关别名、额外 query、HTTP、不同最终路径。配置的 base URL 可以不同表示，但最终 endpoint 必须精确相等，且整个原始配置仍参与比较。
3. 新建默认生产 `RemoteModelFetcher()`，真实 GET **精确** `https://openrouter.ai/api/v1/models`。不使用选择器缓存、不使用注入的假 transport、不从 UI 的选择状态或持久开关产生证据。方法故意不复用 service 的测试 fetcher 参数。
4. 复用现有 GET 实现：不跟随重定向、相同 header/超时、取消检查、2 MiB 上限及敏感异常净化。2xx 后才解析；没有备用目录或硬编码模型名。
5. 严格解析要求 `data[].id` 是非空白且未被 trim 修正的字符串。重复 ID（含完全相同重复）一律失败，避免顺序掩盖矛盾；错误行、空白 ID 也失败。精确匹配模型必须有 `architecture.input_modalities` 数组中的精确字符串 `video`。无架构、无 video、不同模型、分页、非官方 source 均失败关闭。
   - 这是无筛选的完整 models GET；若响应携带 `total_count`，它必须是原生 JSON 整数且与整个 `data` 数组长度相等，而非仅与选中模型数量相等。比实际条目多或少、负数、字符串、布尔、null、小数（含 `1.0`）、数组及对象都拒绝；不使用会自动转换类型的 `optInt/optLong`。
   - 保留 `has_more` 与 `nextPageToken` 判定；`links.next` 只允许缺失、null 或空字符串，其余值均拒绝。`links.self`、`links.prev` 等非 next 元数据不表示列表不完整，不能仅因它们存在就拒绝。`links` 若存在且非 null，必须是对象。未携带这些可选 metadata 的响应仍按原规则解析。
   - 该 metadata 兼容规则依据本次 review 任务上下文实施；子代理未浏览或独立抓取官方 models schema，不将这些解析回归宣称为真实网络或协议实测。
6. `OpenRouterVideoEvidence` 无公开构造器、无 copy/反序列化入口。一个实例只能被一个批准流程 claim 一次；不是全局缓存或可恢复授权。只接受相同 `ProviderSnapshot` 对象、相同 revision。`source/model/inputModalities/endpoint/protocol/protocolDocumentation` 为不含密钥的来源信息。

父必须提供真实的 saved-config epoch，保存、凭据清空、恢复、修改后改回等都推进，不能仅把当前配置 hash 当 revision；跨工作线程读取必须线程安全。runId 必须是父实际接收的唯一 run 标识，不能在未来发送重用。进程重启不得恢复 evidence 或审批票据。没有该 epoch 的入口不得自行传恒定零值冒充变动检测。Store/load 回调是父的可信边界，不是用户可自定义回调。

## 冻结审批输入

文件：`model/videos/VideoUploadApproval.kt`；类型为同模块 `internal`，供后续父入口组合。

- `FrozenVideoApprovalRequest.forDraft(draft, runId, executionOwner, provider, configurationRevision, fullHistory, allHistoryVideos)`：绑定**同一个**不可变 `VideoDraftRequest`、原文（不 trim）、draft owner/revision、本次 run ID/执行 owner、同一个 provider 对象、配置 revision、完整展开历史和所有历史原视频 sidecar，清单同时包含 draft 与历史视频。
- `FrozenVideoApprovalRequest.forText(conversation, runId, executionOwner, editRevision, text, provider, configurationRevision, fullHistory, allHistoryVideos)`：普通文字续发也列出完整保留历史视频，不因当前没有附件就跳过批准。

父必须先用真实授权行/segment 查询完整历史和 sidecars；leaf **不能从裸 Message 推断数据库中是否漏了视频 sidecar**。历史数组展开后冻结；引用清单不可变。校验 owner 与 user 行、重复引用、MP4、计数/大小/摘要基本形状，默认最多 4 件、16 MiB 原始合计。原件字节/摘要最终核验仍由现有 VideoAssetStore/FrozenMessageVideoPlan 完成。这里不打开原件，不接受 DB，也不发网络。

## run 级批准 API（host-thread confined）

```kotlin
val approvals = VideoUploadApprovalController()
val prompt = approvals.present(frozenRequest, evidence)
// 展示 prompt.disclosure、videoCount、totalBytes 和 inventory 中的逐件 owner/id/bytes。
// disclosure 明确：本 run 工具续轮可能再次上传同一批原件，不授权将来新 run。

// 仅来自上面那个 prompt 的明确正向 UI 操作。再次从父权威状态冻结 current：
val ticket = approvals.confirm(prompt, current)
val run = approvals.activate(ticket, current) // 一次消费 ticket，一次生成 capability
val capability = approvals.capabilityFor(run, current)
// capability 同一个实例用于现有原视频 freeze / ResponsesModel；不在此 leaf 接线。
```

精确方法：

- `present(FrozenVideoApprovalRequest, OpenRouterVideoEvidence): VideoApprovalGate.Prompt`
- `confirm(Prompt, FrozenVideoApprovalRequest): VideoApprovalGate.Ticket`
- `activate(Ticket, FrozenVideoApprovalRequest): VideoUploadApprovalController.Run`
- `capabilityFor(Run, FrozenVideoApprovalRequest): OpenRouterVideoCapability`
- `revoke(): Unit`

`activate` 最终调用现有 `OpenRouterVideoCapability.grant`：`verifiedInlineMp4Support` 只能来自匹配的新鲜 runtime evidence，`uploadEnabled` 只能来自该 gate 已验证身份并单次消费的票据。普通 UI 布尔、反序列化 Prompt/Ticket、另一个 gate 的票据无法通过对象身份检查。

每次 `confirm/activate/capabilityFor` 都比较完整冻结输入；换 run、owner、原文、历史、引用、配置对象/revision、draft handoff 会拒绝。取消 UI、编辑、切 owner/会话/页面、配置改变应同步 `revoke`，随后取消对应真实 run。重新展示必须新鲜 fetch（旧 evidence 已 claim）。

**同一个 run 的工具续轮可使用同一 capability，但必须继续符合冻结原文/视频清单，不准新增原件**。新增工具文本由父的现有历史基线机制校验，不能把新增视频混入原列表。run 的批准检查使用原冻结请求/基线，不把合法工具输出误当新用户输入；另行检查当前已保存配置、owner、生命周期以及完整视频清单。每次 HTTP POST 仍必须经过另任务的独立新同意接口。

`capabilityFor` 返回的低层 capability 自身是既有可复用对象；本 leaf 没改该类，因此无法使被调用方私藏的引用自动失效。父不得绕过 controller 检查复用它；撤销必须取消真实 session，POST 层仍需自己的检查。这里不虚称已闭合父入口或每 POST 生命周期。

## 测试与未执行项

- `OpenRouterVideoEvidenceTest`：官方 source/exact model/video、缺失/错误 modality、重复/矛盾模型、空 ID、分页、2 MiB、重定向、取消、saved/revision mismatch。此次补充完整 `total_count`/`links` metadata（含 self/prev、next 缺失/null/空串）通过、多模型完整计数通过，以及 total_count 不一致/坏类型、非空或坏类型 next、坏类型 links、与既有分页标志冲突时拒绝的回归；拒绝样本均仍包含精确 video 模型，避免仅因找不到模型而误通过测试。
- `VideoUploadApprovalTest`：精确 Prompt/Ticket 对象身份、单次消费、撤销/替换、未来 run/编辑/owner/配置/历史变化、draft + 完整历史计数大小、原文和同一个 draft handoff。
- `parseOpenRouterVideoFacts` / `fetchOpenRouterVideoFacts` 只返回 **facts**，绝不返回 runtime evidence。`VideoApprovalGate` 是纯生命周期 seam，只返回本 gate 的票据/消费结果，不能独立生成 capability。测试不伪造生产证据，也不将假 HTTPS 连接的结果冒充真实官方证据。
- 正向真实 evidence→run 激活需官方网络与已保存真实配置，未在离线测试中伪造。无 Shell 权限，**未构建、未运行测试、未实测上传**；由父执行构建/测试并审核接线。


## Responses inline 协议依据补充

- 官方 InputVideo 定义明确 videoUrl 为 base64 data URL 或远端视频 URL：
  https://openrouter.ai/docs/client-sdks/typescript/models/inputvideo
- 官方 ResponsesRequest → InputsUnion → EasyInputMessage → content union → InputVideo 类型链将该类型用于 Responses 请求；Videos 页面提供 `/api/v1/responses` 的 `input_video` 示例。
- 官方 SDK 将 `videoUrl` 序列化为字符串字段 `video_url`，不是 Chat Completions 的对象形态。核验源码固定于 SDK 提交 `7bfa806ca7a53ca4cca1871999739c7a41fae0ab`：
  https://github.com/OpenRouterTeam/typescript-sdk/blob/7bfa806ca7a53ca4cca1871999739c7a41fae0ab/src/models/inputvideo.ts
- 这里的 evidence 是官方目录新鲜声明加官方协议文档，不是成功上传/解码/推理探测。具体账户、模型和 provider 的运行兼容性仍需实际验证；本次没有发送付费请求。
