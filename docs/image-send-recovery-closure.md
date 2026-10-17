# 图片发送：冻结、只读回执恢复与当前页取消

## 范围

本补丁只补现有 `MainActivity → ImageChatHost → ChatController.sendImages → ControllerImageAttempt → SQLite` 图片发送生产闭包；不改视频专用批准、生成、编码、HTTP 协议或共享清单/更新说明。普通 file/folder/path 附件不在范围内；路径字符串仍是文本，绝不转换成文件附件。

## 冻结与恢复

- `ImageChatHost.send` 在 Controller 排队之前分配**一次** user/assistant/run 身份，冻结 owner、revision、完整编辑文字、顺序 parts 和原图片引用，并用 `ImageHostDraftStore` 同步持久化 fence。持久化失败不排队，不为未知结果另建身份。
- Store 保留现有私有 preference key，增加可选 `attempt` 对象。内容不包含 URI、provider 配置、密钥、能力声明或预览位图。旧版 fenced 记录缺少原始 attempt 时继续 UNKNOWN，不能猜内容、身份或清 fence 重发。
- 新 Controller 和新 Host 从该记录恢复同一个 owner/revision/content/parts/user/assistant/run。`只读核实原发送结果` 按钮调用 `ImageChatHost.recover`，**不调用 `send()`**；它在 UNKNOWN/busy fence 下有独立准入。
- `ChatController.readImageReceipt` 构造使用原身份的只读 attempt，调用 `readReceipt`。此路径不调用 `reserve`、settings、route capture、freeze/prepare、model factory/open、Runner、sendPrepared 或 POST；不会分配新的发送身份，也不写 journal 或 SQLite 内容。UI 的 submission token 只是当前页面状态令牌，不是消息/运行身份。
- SQLite 原 user 行是权威：用原行 `createdAt`（包括 null/0）完整重建并比较 user 的 owner、身份、run、状态、顺序 text/image 块和元数据；再校验原 assistant 行的会话、身份、run、role，最后在同一事务快照读取 conversation。已有 journal 若与冻结 fingerprint/身份冲突则 UNKNOWN；缺失 journal 可用 Host 冻结身份和 SQLite 原消息对核实。
- 缺行、部分回执、矛盾、journal 不可读、SQLite 读取失败都继续 UNKNOWN，保留完整 frozen attempt；重复点击只是再次查询，不会重新准备/请求。没有“未找到即允许重发”的分支。
- 只有精确 Accepted（包括原消息身份与有序图片）才能走原 `finish` 清理流程。只读查询之后，独立 `confirmImageReceipt` 本地结算把同一 journal 身份记为 confirmed tombstone，防止丢失的旧 callback 永久挡住同 owner 的新 revision；确认写入失败/发现矛盾则继续 UNKNOWN。随后只移除该 submission 的引用并清理原 draft 资产，保留 message 资产。tombstone 和 Host 消费状态的持久化都是查询之后的本地结算，不是重发或 SQLite 内容写入。Main 仍比较当前 owner/revision/selected/text；此外 `beforeTextEdit` 在 UNKNOWN 允许新文字编辑之前持久化 `editorReplaced`，被替换过的编辑器不再收到清文字/导航回调。该标记防止 Activity/进程重建后数字 revision 复用、文字相同仍误清新草稿；不匹配的新编辑器文字不被清空，也不自动导航。

## 主发送键与取消

- `ComposerUi.imageSendAvailable` 只来自当前 Host 已导入、可提交的图片，且没有 fence、待定发送或视频。`ChatComposer` 的空文字 + 图片在 IDLE 时显示并启用发送，仍调用 Main 已有 `onSend → sendImageDraft`，不另造图片发送逻辑。`imageSendBlocked` 让 fenced/导入中的图片阻止发送键，但不把未知旧 attempt 的冻结内容替换成后来编辑的文字。
- RUNNING/PAUSED 的暂停、继续和带文字追加规则保持原样；纯图片不能成为空追加。视频仍通过视频专用区域和批准，不因本标志获得发送资格。
- 图片面板的 `取消图片准备` 捕获当前 Session 和原 Attempt，Host 还检查当前页可见及 exact attempt 引用。旧 callback 不能取消后续 attempt 或另一个 run。
- 提交门前取消通过原 ImageSendHandle 取消原准备，等待真实 Rejected 后保留草稿；门后 cancel 返回 false，提示等待真实结果，既不伪报撤销也不停止已接受运行。导航/生命周期同样不能把门后发送改报为取消。

## 新增/增强测试（源码覆盖，非执行证明）

- `ControllerImageSendingTest`：新 Controller+Host 重建；冻结内容/顺序/身份恢复；不读可用模型配置的只读路径；零 model factory/open；SQLite/journal 查询不变；新 DB 草稿保留；缺失/矛盾/不可读回执保持 UNKNOWN；UNKNOWN 下 send 拒绝但 recover 可达；journal 丢失时用原身份核实；提交门取消输赢。
- `ImageChatHostActivityTest`：真实 picker 导入后的纯图主键一次接受；当前页取消准备获胜以及旧 attempt 不可取消新 attempt；取消输给真实提交时不虚报；丢弃原 Controller 回执后，关闭原实例并创建新 Controller 与 Host，经真实恢复按钮查询，零额外模型请求/URI 打开，不清新编辑器文字。
- `ComposerTextActionTest`：纯图仅 IDLE 发送、禁用/pending 门槛、空追加拒绝，保留暂停追加规则。

这里的新进程测试是**关闭并重建 Controller/Host 的进程失活模拟**，不是系统强杀进程测试。实现代理仅允许读写源码，未运行 shell、构建、JVM 或 Android 测试；编译和运行证据由父代理集成时补充，不能把本说明当作测试通过证明。
