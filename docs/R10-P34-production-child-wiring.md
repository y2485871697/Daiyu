# R10/P34：显式工具型 child 的生产接线增量

本文件是本次源码交付边界，不是重构清单，也不宣称测试通过。基线 SHA 由委派任务提供；受限文件工具不能独立读取 Git 元数据或创建 commit。构建、测试发现数和非空 commit 必须由主代理统一核验。未改 `docs/重构清单.md`、第三方源、依赖或数据库 schema。

## 实际入口与原路径

- `MainActivity` 的浏览器审批对话框包含 `ChildBrowserApprovalHost`：默认关闭、独立提示词、明确动作与每行完整写参数。冻结后展示真实 conversation/parent run/group/generation/child run/assistant message/profile、动作、完整原始 JSON 和 canonical effects，再通过**独立 child 批准按钮**确认。主发送按钮不能代批 child。
- `ControllerChildBrowserPlan` 在预览阶段生成真实 child IDs，纯计算 MAIN + CHILD exact-effect union，创建父的原 `TaskAuthorization`，只调用一次 `ChildRunScope.capture`，保留返回的同一个 scope 和纯 `CatalogChildBrowserSession`。主 dispatcher 使用同一个父授权；子 dispatcher 使用 capture 的同一个子授权。资源 effect namespace 仍按 CHILD/run 隔离。没有新 token/helper 实现。
- `ChatController.sendBrowser` 在已有 worker 上先检查 ticket/准备取消与 CONFIRMED，再调用已有 `preparePrivateRootOnWorker`，之后才 `openOnWorker`。排队取消不创建 browser-runs，不构造 host、不写消息/child journal、不调用模型工厂或 open。
- 父数据库接受并启动真实 Runner 后，`RunCatalogTools` 完成原 bindOwner 和 schema setup，在 worker 的 `onCatalogReady` 触发这个**已独立批准的一次性** child。bindOwner 本身不 mkdir、不构造 host、不执行工具。原 `RunChildModels` / `ChildModelBatch` / `RetainedChildTasks` 的 text-only 防线完全未改；本入口不会给模型 `run_child_models` 自动赋工具能力。

## 子资源与中央分派

- 新 domain port `ChildToolAssembly` 只包含原 `ChildRunScope`、CatalogTool、ReceiptBatchToolExecutor、worker prepare 和 typed drain observation；domain 没有 Android/browser 依赖。
- `ToolEnabledChildRun` 在任何构造前保留该 port，验证 `assembly.scope === scope`，先持久化 child journal，再获取控制器的一枚 permit，再 worker prepare / main native open。实际 child Runner 自己签发 opaque owner，原 `CatalogChildBrowserSession.bindOwner` 在 schema 读取前安装 leaf。
- `CatalogChildBrowserAssembly` 用现有 `CatalogModelBridge` / ToolRegistry / ToolDispatcher / 父控制器持久 ToolLedger；只注册该 child 的 browser leaf，不注册递归委派、设备、skill、ask_user 或 cookie/history。真实 ToolReady whole batch 经中央 dispatcher；没有 synthetic owner 或第二个手写 dispatcher。
- Stop/终态先 revoke 并请求 close；late open 由原 session 保留迟到 native handle。只有 **worker 已退出 + transport.cancel 已返回 + browser 明确 Drained** 才释放 permit、移除控制器资源保留。清理 UNKNOWN sticky，后续漂亮 snapshot 不自动解锁；执行 UNKNOWN 继续由原 durable ledger 阻止等价重放，物理清理明确 Drained 后才可结束资源保留，不代表执行结果已确认。poll 不重 GET、不重新构造、不自动重试清理。
- 控制器保留未排空 child，并阻止关闭它们仍使用的数据库和 ledger。正常 child 可在父模型完成后继续；全局 Stop、匹配父 Stop、专用 child Stop、按完整 ChildRunOwner 匹配的记录 Stop 和 Controller.close 都能撤销；live-child 检查也覆盖此路径，避免绑定修复/删除越过仍持有资源的 child。最多保留八个此类 live/UNKNOWN child，其中只有一枚并行 permit。

## 本次新增/增强测试源码（尚未执行）

- `ControllerBrowserSendTest`：fresh isolated filesDir → 真实 Controller prepare/confirm/sendBrowser → 真实 Android host → Accepted；在真正 ModelPort.open 的入口查询原 SQLite 双消息；关闭后重开验证。另有真实 Android child 装配/持久 child record，以及未独立批准阻止父确认、已批准但排队取消零 IO。
- `BrowserApprovalHostTest`：独立 child exact-fetch 完整参数显示、FULL/父点击不代批、显式 child 批准后父才能确认。
- `BrowserChildProductionTest`：真正 `ToolEnabledChildRun` / child Runner bindOwner / schema / ToolReady / 中央 dispatcher / ledger / 生产下载管线；CONFIRMED fetch 在后续模型 ToolReady 下回放但只一次 HTTP fixture 请求。负测在真实 child opaque owner 已绑定后检查 parent/sibling/old-generation/replacement owner 和新 task；真实 child resource owner 经阻塞/失败 native cleanup 不归还 permit。原 late-open、UNKNOWN 不 reopen/GET 测试保留。
- `ToolEnabledChildRunTest`：worker、transport 和工具 drain 三重门，阻塞 cancel、pending browser、失败 cancel、sticky UNKNOWN、stop-before-permit、late worker publication、重复 close/start 不超额释放。
- `BrowserDownloadLedgerAndroidTest`：在原真实 SQLite close/reopen 两种 CONFIRMED/UNKNOWN 下载场景增加 equal-but-new task、旧 opaque owner、sibling、revoke-after-reopen 拒绝；仍只一次 HTTP fixture 请求。

## 明确未完成 / 不冒充的证据

- 本实现代理没有执行任何编译、JVM、instrumentation、lint 或设备测试；上述均是待主代理审核执行的源码，不是运行成功证据。任务要求最终不编译 APK、不访问网络/付费模型、不读凭据，须由主代理遵守并报告执行边界。
- 新入口当前只支持与父相同的冻结 assistant/provider 的一个独立工具型 child，不是模型自行规划的工具型 child 批处理；也没有把历史 child 记录变成自动恢复/重发许可。没有新增 child 的暂停、追加、重新分派或进程死亡后重新接管策略。
- 原 domain child text-only usage/continuation 机制未重构；新工具型路径目前只持久化 ChildTaskStore 结果和原工具 ledger，没有专用持久 child Runner checkpoint/usage 扩展。
- UNKNOWN 保守保留资源/permit/存储；没有实现人工解除 UNKNOWN 的新入口。没有原生 destroy 失败或磁盘故障的设备运行证据，也没有真实 HTTPS 外网回归。
- 主/子 WebView 的标签/owner 隔离**不是**账户或 WebView 全局 cookie 隔离。cookie 和 history 仍拒绝。fetch 只允许各自批准页列出的精确 URL/tab/effect，不读取 WebView cookies，也不开放模型自选路径或 header。
