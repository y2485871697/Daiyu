# P18 原视频 Compose 附件宿主接线（minSdk 26）

## 交付与边界

新增生产文件：

- `app/src/main/kotlin/app/daiyu/android/ui/screens/chat/attachments/VideoAttachments.kt`
- `app/src/main/kotlin/app/daiyu/android/ui/screens/chat/attachments/VideoPickerBinding.kt`

新增设备组件测试：

- `app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/attachments/VideoAttachmentsUiTest.kt`

没有修改 MainActivity、ChatController、VideoChatHost、数据库、Gradle 或资源。**没有运行构建、测试、网络。** 此文是接线契约，不宣称已在 Main 上线或 CI 已通过。生产宿主/原视频字节与解码证据仍由现有 `VideoChatHostAndroidTest`、`AndroidVideoAttachmentsTest` 提供；新增测试只替换 provider IO 和父回执权威，不伪称真实上传。

## 控制接口

```kotlin
@Composable
internal fun VideoAttachments(
    host: VideoChatHost,
    conversation: String?,
    text: VideoDraftText,
    onHandoff: VideoDraftHandoff,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    parentBusy: Boolean = false,
    viewLifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
)

internal data class VideoDraftText(
    val text: String,
    val editorGeneration: Long,
    val revision: Long,
)

internal typealias VideoDraftHandoff = (
    VideoDraftRequest,
    VideoDraftText,
    (VideoDraftReceipt) -> Boolean,
) -> Unit
```

- `host` 是父级长期持有的**唯一** writer。不可 `remember { VideoChatHost(...) }`，不可在 Activity 每次重建重新构造同一 journal writer。UI 仅创建可废弃的 picker/view binding，不创建 host、store 或 media。
- 一个 host 同时只挂一个组件。它调用 `host.enter(conversation)`，并独占 `host.bindLifecycle(viewLifecycleOwner)`；父不要重复 bind/attach/detach。Compose-only Activity 可以传 Activity；Fragment 必须传 `viewLifecycleOwner`，不是 Fragment 的生命周期。
- 离开 chat、打开覆盖路由/侧栏导致编辑器不再可操作时，**移出 composition**，不是只设置 alpha/隐藏。`onDispose` 同步关闭生命周期绑定、失效 visit、注销 picker。
- `enabled` 传入媒体互斥条件；`parentBusy` 传入父级提交/恢复/其他操作状态。两者禁用选择、删除和发送，但不改任何外部文本/图片按钮。
- `VideoDraftText` 在打开上传确认时冻结。确认文字可能不是编辑器随后更新的文字，必须向用户显示冻结内容。视频 owner/revision/visit 则由确认的 Snapshot 和 `host.submit` 再核验，陈旧确认不能发送。

## Main 中具体调用片段

下面片段放在 **Main 的 chat composer 附件插槽**，使用 Main 已有的 `draft`、`editorGeneration`、`draftRevision`、`selected`、`imageHost`、`controller`、`selectionReady` 等字段。`videoHost` 必须来自父级已保留的唯一实例；`parentVideoHandoff` 是父实现的权威异步接收边界，**当前仓库未提供该 Controller/DB 入口，不能冒用 sendImages/sendText 作为视频成功**。

```kotlin
// 父级先提供两个依赖；这里仅声明其契约，不创建第二个 host：
// val videoHost: VideoChatHost = retainedVideoOwner.host
// val parentVideoHandoff:
//     (VideoDraftRequest, (VideoDraftReceipt) -> Unit) -> Unit = ...
// parentVideoHandoff 只能在真实 DB outcome 后回报，不可把入队当 ACCEPTED。

// videoMode 是父显式媒体模式，与图片选择器互斥，不由此组件修改。
if (videoMode && retainedPage == "chat" && !sidebarVisible && selectionReady) {
    VideoAttachments(
        host = videoHost,
        conversation = selected?.id?.value,
        text = VideoDraftText(draft, editorGeneration, draftRevision),
        enabled = !imageHost.hasAttachments && !imageHost.busy,
        parentBusy = !controller.ready || controller.dataBusy ||
            controller.active != null || pendingTextSend != null ||
            shizukuWritesBlocked,
        viewLifecycleOwner = this@MainActivity,
        onHandoff = { request, frozenEditor, settle ->
            // 该回调只能交接 request.videos（原 VideoAttachment），绝不传封面 JPEG。
            // 父工作必须能跨离页/Activity 重建完成；不能放进 UI 的 rememberCoroutineScope。
            parentVideoHandoff(request) { receipt ->
                runOnUiThread {
                    // 即使旧 Activity 已离页，也要先 settle 长期 host。
                    // settle 内部匹配 captured request + host 的原始 owner/revision/video 列表。
                    val settled = settle(receipt)
                    if (settled && receipt.outcome == VideoDraftOutcome.ACCEPTED &&
                        activityUsable() && selectionReady &&
                        selected?.id?.value == request.conversation &&
                        editorGeneration == frozenEditor.editorGeneration &&
                        draftRevision == frozenEditor.revision &&
                        draft == frozenEditor.text && request.text == frozenEditor.text
                    ) {
                        // 这是父级的可选精确消费，组件自身完全不清空编辑器。
                        updateDraft("")
                        selectionStart = 0
                        selectionEnd = 0
                        publishChat()
                    }
                }
            }
        },
    )
}
```

父接收器必须捕获/保留真实事务结果并在主线程通知；如 Activity 已重建，建议由 retained receipt router 通知新 Activity，而不是保留旧 UI。上例在旧 Activity 完成时仍 settle，但不消费新 Activity 编辑器。若 null-slot 接受时父已迁移 selected，例子的 conversation 核验会保守地不清空；父可在同一个受检 editor owner 迁移流程中处理 `acceptedConversation`，不可放宽到“当前文字一样就清空”。

父的既有普通发送分支只加**模式/附件路由门禁**，不能把视频误路由给图片或文本接口：

```kotlin
when {
    videoMode || videoHost.state.items.isNotEmpty() || videoHost.state.fenced -> {
        // 由附件区的“发送原视频…” -> 明确确认 -> onHandoff 处理。
        // 此处不能 sendDraft(text)，也不能把封面变成 ImageSendPart。
    }
    imageHost.hasAttachments -> sendImageDraft(text) // 保持已有图片分支
    !imageHost.busy -> sendDraft(text)              // 保持已有普通文本分支
}
```

父同时应在 `videoMode`/存在视频草稿时禁用图片选择入口（反向互斥），切换对话时按该对话的 host 状态判定。视频模式为空时可由用户退出再使用普通文本流程；不要为“恢复普通文本”静默删除原视频。`VideoAttachments` 不控制发送主按钮，也不会自动选择路由。若父暂无视频事务实现，应禁用视频发送能力，不伪造 Accepted。

## ActivityResult 与生命周期审查要点

1. 按钮点击取当前 Snapshot，`host.requestSelection(snapshot)` 创建唯一 ticket。
2. **每次 launch** 用新 UUID registry key 注册真正 `GetMultipleContents`；回调闭包持有本次 ticket，不取 mutable/latest ticket。
3. `host.launchSelection(ticket) { launcher.launch("video/*") }` 紧贴真实调用。launch 异常退还本 ticket，不让其它 ticket 被清掉。
4. ActivityResult 的真实 Intent/ClipData 经 contract 解析后交 `host.picked(ticket, uris)`。超出本 ticket 的剩余数量时整批拒绝，显示“本次最多还能选择 N 个视频；未导入任何视频”，不静默截断。
5. 特意使用 registry 非 LifecycleOwner overload：点击发生在 STARTED 后，不能临时注册 lifecycle overload。UI 自行 unregister；唯一 key **不保存、不复用**。旧 Activity/旧 view 的 pending result 不会被新 key 接走。
6. picker 自己触发的一次 stop/start 由 host 桥接；其它 stop、离页、销毁使 visit 失效。dispose 时先关闭 lifecycle，再注销本 picker 并退还 tombstone。第二次 stop 后尚注册的旧回调仍持有旧 ticket，不能导入。
7. 不请求持久 URI 权限，不保存 URI，不把 provider 路径写日志/文案。

## 显示、预览与回执

- 导入中、Ready、Failed、移除、持久化失败、恢复中、UNKNOWN fenced 都有真实状态显示与 testTag。失败项占位，用户明确移除后才释放。
- 封面通过主线程入口 `host.preview(video, edge)`；该现有 API 内部做 IO、CancellationSignal 和 owner/visit 校验。缩略图 256、弹窗 1024；JPEG 解码再下放 IO 并验证边长。UI 额外核验 original 引用、Snapshot 的 owner/revision/visit 及 Ready 身份。
- **封面弹窗不是视频播放**，界面显式标注。现有 host 没有打开原视频播放器的异步入口，所以这里不越权读取 store/路径、不从 content URI 播放、不把封面当视频。原视频从 `request.videos` 交给父，包含真正视频 MIME、摘要、时长和尺寸。
- 不支持文案包括“类型、编码或多视频轨道”；超限文案包括大小、总容量、时长、分辨率和旧设备限制，避免把所有 `TOO_LARGE` 误说成“文件超过 64 MiB”。默认实际限制由既有 store 管理：4 个、单个 64 MiB、总 128 MiB、1 小时、40MP；API 26 解码路径更严格。
- 上传确认冻结文字和编辑器修订身份；文字区域先约束为最大 120dp 的视口，再以 `verticalScroll` 阅读全文，不截断或改写交接原文。取消确认只关闭对话框，不交接；下次打开重新冻结当时的文字。`host.submit(frozenText, frozenSnapshot)` 先持久化 fenced，再调用父。父回调抛异常保持 UNKNOWN，不自动重试。回执必须精确匹配 conversation/owner/revision/原视频列表；ACCEPTED 还需正确 `acceptedConversation`。
- `settle(receipt)` 的 Boolean **不是 Accepted**：UNKNOWN 合法匹配也可以返回 true，且仍保留原视频/锁。REJECTED 解锁并保留；只有精确 ACCEPTED 才授权 host 清理 draft 副本。父的消息副本由父事务负责。
- UNKNOWN 恢复走父权威 DB 查询 + `host.pendingRecovery()/settle`，不是从磁盘存在推断成功，且不可重放发送。

## 新测试覆盖（待主代理统一 CI 执行）

`VideoAttachmentsUiTest` 使用实际 Compose 按钮和 registry dispatchResult 的 Intent/ClipData 解析，不缓存脱附 Semantics 包装：

- 真实 GetMultipleContents("video/*") 接缝，导入中 -> ready -> bounded 封面 -> 移除；错误 original 身份的封面被拒绝，原视频仍可交接。封面失败子节点位于 clickable 缩略图内部，等待与断言都显式查询 unmerged tree；部分文案断言显式使用 `substring = true`。
- 实际选择按钮启动后派发 `RESULT_CANCELED`，验证选择/发送/移除门禁恢复、已有附件不变，并可再次选择；同时覆盖 OK 空选择。
- 实际取消确认按钮不交接、不删除原视频，再次确认读取新的文字修订。导入被 fixture 阻塞时验证选择/发送禁用、父 busy 禁用移除；解除 busy 后实际移除，等待迟到原件清理，不能只以初始空集合推断清理完成。
- 一次 picker 生命周期往返接收；第二次 stop 后仍注册的 callback 被拒绝。
- 离页/返回、A -> B -> A、真实 Activity recreation 后旧 registry 结果不可冒充新 ticket。
- 父 busy 禁止选择/发送；上传确认冻结文本 **及编辑器修订身份**。新增长 caption 用例检查正的滚动范围，实际滚动到末尾后确认，交接仍是打开确认时的完整原文及修订身份，而不是更新后的编辑器文字。
- 错 revision Accepted 被拒绝；UNKNOWN 保留锁与原视频；REJECTED 解锁保留；离页后准确 Accepted 才清理。
- 5 个超额选项整批拒绝、unsupported 与 TOO_LARGE 准确文案。

本次 UI 审查修复仅修改 `VideoAttachments.kt`、`VideoAttachmentsUiTest.kt` 和本文；未修改 VideoChatHost、Controller、Main 或公共清单。以上均为测试源码覆盖说明，**不是测试通过或设备验证证据**。

建议主代理合并后运行既有视频 host/store 测试以及新增组件设备测试，并加 Main 真正父事务接线的端到端测试。本实现没有执行构建或测试，也没有新增任何依赖。
