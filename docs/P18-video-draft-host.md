# P18：原视频草稿持久宿主（未接入 Main / Controller）

## 范围与证据

本补丁只新增：

- `app/src/main/kotlin/app/daiyu/android/VideoHostDraftStore.kt`
- `app/src/main/kotlin/app/daiyu/android/VideoChatHost.kt`
- 对应 JVM/Robolectric 与 Android instrumentation 测试，以及本文。

没有修改 Main、ChatController、DB、任何现有 Image 实现或 VideoAssetStore；没有网络发送，也没有创建假的 `SendResult.Accepted`。所有新增生产类型为 `internal`，包名 `app.daiyu.android`，供同模块父接线使用。

依据现有 `AndroidVideoAttachments.import/preview`、`VideoAssetStore.prepare/openOriginal/remove` 的真实契约实现：import 保存原字节，preview 是单独 JPEG 元数据；`prepare` 复制消息原件但**不等于 DB 接受**。不读取旧 Eta。

## 精确 API

生产构造：

```kotlin
val videoHost = VideoChatHost(preferences, androidVideoAttachments)
```

`AndroidVideoAttachments` 应使用 app 私有 filesDir 的同一个 app-scoped `VideoAssetStore`。宿主内部只调用其真实 `import` / `preview` / `store.remove`，不会从封面构造视频，也不会获取 persistable URI grant。

所有宿主状态方法必须在**创建宿主的同一线程**调用；生产应为主线程。`preview` 同样从主线程协程调用。导入、预览、精确删除在 IO dispatcher 上执行，宿主不持锁包围 provider IO。测试构造允许注入 `VideoDraftPersistence` / `VideoDraftMedia` 与 dispatchers，但生产构造不替换真实媒体实现。

| 方法 / 属性 | 契约 |
| --- | --- |
| `state: Snapshot` | Compose 可观察的只读快照；包含 `conversation: String?`、owner、revision、visit、不可变 items、fenced、storageFailed、visible、remaining、canSubmit。 |
| `attach(onChanged: () -> Unit = {})` | 安装当前 UI 回调、开启可见性，并开始新 visit。不要因为重组反复调用。 |
| `detach()` | **同步**离页：不可见、撤回回调、增加 visit，标记 pending 为失败、同步取消信号/预览；不删除 ready 原件，不解封手持请求。 |
| `enter(conversation: String?)` | 精确切换会话槽，即使相同字符串再次进入也增加 visit；null 是独立稳定的新会话槽，不是空字符串。 |
| `requestSelection(expected: Snapshot = state): Selection?` | 仅在快照、visit、revision、owner 有效且未封口时返回不透明票据。最多四个 ready/importing/failed 合计槽。旧 launcher 未返回时保留 tombstone，不发新票。 |
| `picked(selection: Selection, uris: List<Uri>): Boolean` | 必须交回发起该 launcher 的**原票据**。先校验票据，后接触 provider。多选超额整批拒绝，不截断；false 不代表发送结果。 |
| `selectionLaunchFailed(selection)` | 仅用于该 launcher 确实未能打开，或已交还取消结果；只清除同一个票据。 |
| `remove(key: String, expected: Snapshot = state): Boolean` | 用户显式移除失败卡或 ready 卡；先持久化移除，再精确删除该 draft 原件。存储失败不删文件。 |
| `suspend preview(video: VideoAttachment, maxEdge: Int = 512): VideoPreview?` | 只允许当前槽实际持有的视频。返回真实原件导出的 JPEG；离页/变更/取消后的结果不交给新 visit。不能将返回值作为发送附件。UNKNOWN 下仍可预览保留的原件。 |
| `submit(text, expected = state, handoff: (VideoDraftRequest) -> Unit): VideoDraftRequest?` | 仅当至少一个附件且**全部 ready**时，将 fenced 先同步 commit，再向父交付一次不可变草稿请求。返回非 null **只表示交付请求**，不是入队/接受/发送成功。 |
| `pendingRecovery(): List<VideoHostDraftStore.Draft>` | 所有 fenced 槽的只读身份/引用快照，供父查询权威结果；不包含正文，绝不能拿来重发。 |
| `settle(receipt: VideoDraftReceipt): Boolean` | 父提供权威接受结果或恢复结果，按原 slot + owner + revision + 有序完整原引用精确匹配；可在 detached 或其他会话可见时调用。true 表示此结算匹配并处理，不代表网络发送成功。 |

`VideoDraftRequest` 字段为 `conversation: String?`、`owner: VideoAttachmentOwner`、`revision: Long`、`text: String`、`videos: List<VideoAttachment>`。列表防御复制且不可修改；每条 VideoAttachment 是原视频元数据引用，包含原 MIME/hash/bytes/duration/dimensions。无 URI/封面字节。

`VideoDraftReceipt(conversation, owner, revision, videos, outcome, acceptedConversation = null)` 同样防御复制列表。`VideoDraftOutcome` 只有 `ACCEPTED / REJECTED / UNKNOWN`，是父提供的**DB 接受裁定**，不是新的 Controller 发送协议。正文不用于结算授权。

## 父尚须接线的部分（本补丁没有做）

1. 在 Controller 或其他 retained owner 的生命周期中保存宿主；Activity attach/detach 只替换回调。**不要同时创建两个活跃宿主写同一份 preferences。** 不提供全局弱引用注册表，也不声称支持跨进程并发写。
2. Main/Compose 菜单、草稿卡、删除、预览、可见性、路由、返回与 Activity 生命周期，仍由父接入。同步离开时先 `detach` / `enter`，不要等后台任务才撤销 UI 授权。
3. ActivityResult launcher 使用视频类型与多选；将每次 `Selection` 绑定其原 launcher 结果，在 retained 集成对象中保留该绑定（不写 saved state）。旧结果一律交还旧票据。Activity 重建后 `attach` 会使它失效。进程重建没有票据的结果直接丢弃；不能重新构造或借用新票据。如果用户取消，使用 `picked(ticket, emptyList())` 消费该票据。系统 picker 真正的 UI 路径仍需父的接入测试。
4. UI 依据 `canSubmit` 决定入口；不要删除失败卡来自动“降级”为文本，不要把超额输入截为前四个，不要默认给模型授予视频能力。
5. 父决定精确模型/端点/原视频 capability，并在 Controller 接收处再次验证。然后在自己的 acceptance gate 下消费不可变 request，调用真实 `prepare(MessageVideoOwner, attachment)`，将所有 MessageVideoReference 与用户消息在原有 DB 接受事务中持久化。与正文、历史、协议能力有关的既有要求见 `P18-original-video-protocol.md`。
6. **新会话接受必须采用 `request.owner.conversationId` 作为 durable conversation id。** VideoAssetStore 禁止把原件跨 conversation 准备给另一 owner；不能只重写引用 owner 或把 URI 再导入来规避。已有会话必须同样精确匹配。
7. 接受事务权威完成后向 retained host 交回原 slot（null 仍是 null）、owner、revision、有序原视频列表与 durable conversation id。只有这个时候使用 `ACCEPTED`。返回图像生成、HTTP 响应、prepare 成功、开始排队都不构成接受证据。
8. 父对已接受正文的清空、conversation 列表/导航/重建、Controller 队列、历史视频 sidecar 与运行协议负责。此宿主不回写正文，也不自动切换其他当前会话。
9. 父负责进程重启后读取 `pendingRecovery()`，以精确身份查询权威 DB/journal。查不到与明确未接受不同；不确定必须继续 UNKNOWN。消息原件的未引用清理、prepare 后未接受的回滚/恢复同样由父的 acceptance gate 完成；宿主从不删除消息资产。

## 持久与结算边界

`video-host-owned-drafts-v1` 是独立 preference key。commit 保存 version、**显式 JSON null 或完整 slot**、owner.conversationId/draftId、revision、完整私有导入引用、有序 ready 列表、fenced，以及非 ready 的 `blockedSlots` 数量。失败/未完导入恢复为取消失败卡，必须用户显式移除；重建不会悄悄恢复四个名额。不得将正文、provider URI、grant、permission token、模型配置、preview 缓存写入此 journal。

读写校验唯一槽/owner、slot 与 owner 精确关系、非负 revision、引用 owner/id/hash/MIME/字节/时长/像素、合计最多四槽与 128 MiB。读取损坏、未知格式、commit 失败均在当前实例 fail closed，不覆盖损坏数据开启新身份，不删除原件。`storageFailed` 是诊断/阻止操作信号，不能通过重新 attach 解封。

- `submit` 的 fenced 在调用父之前落盘，因此父回调抛错或进程丢失都恢复为 UNKNOWN。
- UNKNOWN 保留原件、原 owner、原 revision；不允许 picker/remove/submit，也不通过离页、新 visit 或改正文绕过。不会换 revision 自动重试。
- 匹配的权威 REJECTED 才会增加 revision 并解封，原件保留。旧 receipt 从此失效。
- 匹配的权威 ACCEPTED 先 commit 清空草稿和 revision+1，再删除**原 VideoAttachmentOwner 的精确附件**。null 槽迁移到 owner.conversationId，其他槽完全不变；若目标已有另一草稿则 fail closed 保留 fence，交父排查。后续访问 null 才生成另一新会话身份。
- 清理只调用 `VideoAssetStore.remove(draftOwner, attachment)`，不调用 `discardUnreferenced(MessageVideoOwner, ...)`。消息副本与草稿处于不同 owner 命名空间，不能混删。
- 即使底层操作忽略取消并在离页后返回原件，旧 visit 的完成不会进入新 visit；只精确删除该迟到新导入。已保存 ready/UNKNOWN 原件不会作为“离页垃圾”清理。

## 明示限制与未验证项

本次实现代理**没有运行**编译、JVM 或 Android 测试（仅允许文件工具；构建由父执行）。不能据新增测试断言已经通过、实际设备已验证，或宣称 Main 已有视频入口。

新增测试覆盖目标：

- `VideoHostDraftStoreTest`：null/owner/revision/原件元数据 round-trip、损坏槽/重复槽、约束校验、不可变请求与 receipt。
- `VideoChatHostTest`（JVM/Robolectric，fake media）：UNKNOWN 恢复不重试、A→B→A 旧票据零 provider 访问、同步取消与迟到删除、四个失败槽不自动释放、精确接受迁移、持久失败不删除、旧 visit 预览隔离。
- `VideoChatHostAndroidTest`：设备编码器生成真正 AVC/MP4，实际 ContentProvider + Android importer + 私有原件 round-trip + 真 frame preview；准备消息副本后只清理草稿；阻塞 provider 离页；假 MIME/JPEG 拒绝与四个失败槽。需要设备 AVC encoder 支持 64×64 YUV420 flexible 输入；不使用 fake inspector 冒充 Android 解码证据。没有网络、真实 DB 接受或真实系统 picker 交互。

文件系统和 preferences 不是跨介质事务：导入成功到 journal commit 之间崩溃、接受已 commit 到异步删除之间崩溃，都可能留下私有孤儿原件。宿主宁可保留，不做 age GC，也不据磁盘存在推断 DB 接受。清理失败同样可能留文件；父若增加恢复清理必须以权威引用检查为前提。磁盘不可写时不可能承诺把失败状态再可靠写入该磁盘，本实例封口；恢复不可借机忽略已有 fence。正文编辑器自己的持久化、provider 永久阻塞、跨进程锁与 DB+文件原子事务不在本补丁范围。
