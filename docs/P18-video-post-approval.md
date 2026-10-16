# 原视频：Responses 每个 POST 的批准交接

## 范围

本补丁只增加 `ResponsesModel` 的**逐 POST 消费点**、`model/videos/VideoPostApproval.kt` 窄挂起接口和定向测试；不修改 Controller、MainActivity、存储 schema、catalog、原 capability/resolver/run-permit 类。原有无视频 `open` 重载不经过批准回调，普通文字和图片保持原行为。

`VideoPostApproval` 是请求绑定的**传输交接对象**，不是可信目录证据、不是新的原视频 run 许可。原许可由父侧独立叶子负责创建、绑定和撤销。不能将空闭包、设置布尔或对话框的一个 `true` 当作原许可的消费实现。

## 父侧接线合同

构造视频专用 `ResponsesModel` 时传入 `videoPostApprovalProvider: VideoPostApprovalProvider`。其唯一方法为：

```kotlin
suspend fun approve(request: VideoPostRequest): VideoPostApproval?
```

- 回调在工作线程上的同步 `ModelSession.next` 内通过可取消 `runBlocking` 执行；可使用 `withContext(Dispatchers.Main)` / 可取消 continuation 等待父 UI。不要在 UI 线程驱动 `ModelSession.next`，也不要把确认框挂到不受 run 生命周期管理的全局 scope。
- 每个包含原视频的实际 POST 都重新调用，包括重复携带相同历史视频的工具续轮。每次获取**新的原 run 许可**；不能缓存上一轮的许可或 `VideoPostApproval`。返回 `null` 为拒绝，不产生连接。
- 原 run 许可必须绑定本次完整、有序的视频 refs（包括历史行）、逐个 hash/bytes、配置、历史/会话 identity；准备计划时保留 `FrozenMessageVideoPlan` 实例到这组 refs 的受信映射。回调中的 `request.videoPlan` 只提供该实例身份，不能自行从最后一条消息或封面推导完整 refs。
- `request` 还提供本次会话的 `historyIdentity`（工具续轮相同、另一个 session 不同）、展开后的只读 `history`、精确 endpoint/model/protocol、`postNumber`、**最终完整序列化请求**的 `requestByteCount` / `requestSha256`。它不公开 API key 或视频 data URL，诊断字符串也不包含媒体。`matchesConfiguration(settings)` 比较完整配置（包括凭据/选项），但不代表配置生命周期证明。
- 显式 UI 批准后，返回 `request.bindPermitConsumption { /* 原许可真实的原子 validate-and-consume */ }`。这个闭包在**连接工厂调用之前**、不是弹框返回时执行。闭包必须重新检查当前 run、账户/配置 generation、历史 identity 和完整 ordered refs，并调用原 run 许可的真正一次性消费 API；失败必须抛出异常。不能只在打开对话框之前检查一次。父必须维持既有 lifecycle fence，取消/切换配置/恢复/历史修改时撤销原许可并取消 session；“配置改了又改回相同值”或恢复同值也必须由 generation fence 拒绝，不能靠值相等判断。
- 父侧闭包消费与撤销须共享既有原子 fencing。这个传输叶子看不到 DB/配置仓库，**不能替父推断外部历史是否改动**；没有正确原许可适配器的情况下，不具备完整运行时授权能力。不要用测试中的合成闭包作为生产批准实现。

集成顺序（概念性，不虚构另一叶子的 API 名称）：

1. 父取得新官方目录证据和本 run 原 capability，按已接受的完整历史冻结计划。
2. 保存计划身份与完整 refs、run/config/history generation 的对应关系。
3. 在每次 `approve(request)` 中核对请求，显示本次收件方 OpenRouter API、精确模型、完整附件范围和字节数，等待明确批准，再向原许可叶子申请一次性许可。
4. 返回绑定该**原许可**消费动作的 `VideoPostApproval`；消费动作在连接前重新验证上述映射/generation。
5. 取消/晚返回/配置变更/恢复/改历史时拒绝或撤销；不可为失败 POST 自动重用已消费许可。

这里的收件方是 **OpenRouter API**，不是承诺限制 OpenRouter 内部路由后的推理提供商。

## 消费和失败顺序

1. 维持已有冻结计划验证、精确 OpenRouter Responses endpoint/model/protocol、原字节/整请求限额、视频专用允许列表。
2. 合并工具输入和所有选项后再次验证完整请求，序列化并检查大小。
3. 为这个最终 POST 建立新的 `VideoPostRequest`，再请求批准。缺省没有 provider 就拒绝。
4. 挂起返回后检查取消，重检 capability/最终 payload；要求交接对象来自**同一请求实例**，将交接标记为已用，再执行原许可消费函数。失败不会调用连接工厂或写入输出流。
5. 成功消费后再次检查取消，才调用 HTTPS 连接工厂。工厂返回后先记录本地清理责任，再在短锁内检查取消和当前请求身份、发布连接；通过检查后才设置 POST 并获取输出流。取消期间晚返回的连接不发布、不设置 POST、不写 body，由 `finally` 断开。禁止跟随 redirect；不改变 SSE、终态、工具输出和正常 ProtocolOutput 逻辑。连接/服务失败也不返还许可，不自动重试。

每个交接请求最多消费一次；即使消费闭包抛错也不恢复交接。不同 session、同历史第二次 POST、工具续轮都不能用旧交接。父即使为旧原许可新建交接，原许可自身也必须拒绝再次消费。

`cancel()` 设置取消标志，用短锁取得当前交接、等待 job 和连接的快照，再在锁外撤销交接、取消批准 coroutine 并断开已发布连接。可取消 UI continuation 的晚结果不会恢复连接；晚调用 `bindPermitConsumption` 拒绝。父提供的挂起函数也必须遵守结构化/可取消约定；不合作的阻塞 UI 桥可能延迟工作线程返回，但取消标志和连接前检查仍拒绝后续上传。取消不可能收回在取消之前已经开始的真实网络写入。

### 6d270db 后的控制锁修正

`postLock` 只保护当前请求/等待 job/连接的检查和发布，不再包住父侧原许可消费闭包或外部连接工厂。现有 `VideoPostRequest.consume` 已在它自身短锁内检查同一请求身份并一次性烧掉交接，然后在该锁外调用父闭包；直接复用这个 API，不新增第二套 ledger 或放宽重试。Session 在消费前核对当前请求，在消费后检查取消，在连接返回时再次核对请求及取消；最终清理也只移除自身持有的请求/连接。

`cancel()` 不是 drain/join：消费闭包或工厂已阻塞时，取消不等待它们释放；工作线程稍后返回时按取消失败退出。已进入父闭包的原许可可能已经消费，不退还，也不重用。父侧仍必须用原有原子 fencing 管理许可的消费/撤销。取消 job 的回调和连接的 `disconnect()` 也在 `postLock` 外；它们自身若不合作，不能据此承诺 `cancel()` 的无条件时间上界。

本次仅查阅上述工作树源码与专属测试/文档，未重新浏览下列官方资料、运行构建/测试或访问网络；下节保留的是原补丁的资料记录。

## 本子任务独立读取的官方资料

使用只读浏览器访问以下官方页面并实际读取了正文；没有访问账户、读取凭据、发推理请求或上传媒体：

- https://openrouter.ai/docs/guides/overview/multimodal/videos ：正文的 Responses 示例为 `POST https://openrouter.ai/api/v1/responses`，content 使用 `type: input_video` 和字符串 `video_url`。正文另说明支持 base64 data URL、本地示例为 `data:video/mp4;base64,...`，并提示检查 `input_modalities` 包含 `video`、不同 provider 的 URL 支持不同、Messages 不支持视频。不是仅根据模型名称推断。
- https://openrouter.ai/docs/api/api-reference/models/get-models 访问后重定向到 https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties ：后者正文返回样例为 `data[].architecture.input_modalities` 数组，示例值为 `["text"]`；文档说明不带 offset/limit 时返回完整列表。其查询过滤说明当前仅列 text/image/audio/file；本补丁不据该查询说明猜测 video，也不实现目录获取。

这些是**文档字段核验**，不是任何具体选定模型/配置的运行时目录证据，更不是 OpenRouter 后端所有推理路由支持 inline MP4 的证明。可信目录新 GET 和原许可由另一个叶子/父集成提供，本补丁没有重做或放宽它们。

## 定向源码测试（未运行）

新增 `VideoPostApprovalTest` 使用合成字节、假 inspector、假 HTTPS 连接：缺省/拒绝/异常批准/过期原许可零连接；消费先于连接工厂；绑定最终 request SHA/bytes/config；已用交接不能跨 session/工具续轮；新交接也不能复用已消费的合成原许可；每个工具 POST 单独批准；挂起期间取消及晚 callback；父 invalidation 在消费时重新检查；普通文字和真正冻结图片路径不触发视频批准。

控制锁修正增加两个真实 `CountDownLatch` 阻塞回归：工作线程分别停在消费闭包和连接工厂，另一线程调用 `cancel()`，必须在 gate 尚未释放时于限时内返回，且工作线程仍在阻塞（不将取消当作 drain）。测试随后释放 gate 并等待工作线程以取消退出：消费阻塞用例连接工厂调用数为零；工厂阻塞用例只获得一个晚返回假连接，验证其已断开、POST 方法设置次数/输出流获取次数/body 字节数均为零。原有消费先于连接、旧交接/旧原许可拒绝及文字/图片用例保留。

已有 `OpenRouterVideoInputTest` 的视频成功传输用例显式提供测试交接，继续覆盖 data URL、允许列表、工具续轮和原预算；该测试交接仅为本地运输夹具，不是生产 run 许可。

仅静态检查源码；未编译、运行测试、创建分支、推送或做真实付费请求。上述测试不等于真实 Android/UI/服务兼容性验证。父需集成真实原许可适配器并补跨 Controller 生命周期测试。
