# 2号设备能力组：首批定向接收与缺口修复

状态：本地候选，待1号集成与平台验收；不是 Root/GUI/终端/浏览器全业务完成。

## 所有权与基线

- 本次编号是四主代理的2号设备组，不是早期 Runner 02。
- 本地 main：`/workspace/daiyu-main-02-device`；基线 `cb97a11dac4b1951afbf1766124f570963d986f7`。
- 仓库：`y2485871697/Daiyu`。仅本地 main 提交，不 push，不触发/取消 CI，不下载安装 APK，不访问电脑。
- 父目录 `/workspace/代鱼` 只读；期间已由其他会话推进至 `0d28dbbe1cd1266a47c79fbc3ab41005a97fb08b`，本候选不重置或覆盖它。
- 不改 Controller、Activity、Manifest、Gradle、中央 Registry/Dispatcher/ledger、唯一重构清单。
- 归属登记和逐文件接收证据：`/workspace/daiyu-coordination/main-02-device-v1/{ownership,intake-source-manifest}.json`。

## 交付范围

1. 按固定候选接收 terminal 18 路径，来源 main `49b8f388020fc5275dc694a4354761dc86a760f9`；browser 70 路径，来源 main `f2d2d43682e6db2a0f716b5da9c64ed2b8cee453`。接收清单逐文件绑定 SHA-256，旧目录不改。不是重新移植旧版本。
2. 新增 terminal `PtyRunOwner` / `PtyRunSession`，将同一个可信授权对象及 conversation/session/run/environment/generation/storage 绑定到持久 PTY scope。不同绑定、legacy unbound scope、历史 open 不可换身份复用或重新启动。`PtySession.openBound` 保留完整 launch 指纹。
3. PTY close 立即撤销后续 admission；read 与撤权共享短内存边界，close 先取得边界时读拒绝。写与底层 I/O 不持该锁。新增 helper 退出等待接口，close 请求不冒称已经停止。真实 Debian helper 测试覆盖5项 run facade 行为。
4. 浏览器 `BrowserToolSession` 增加纯 preflight 回调，完整 lease 与同一个不可变 command 在入 port 前固定；`AndroidBrowserRunHost` 注入现有 mapper 的支持检查。不支持的参数模式在更新 current/reservation/port 前返回 NotExecuted，保留原页面；真正已执行后的 Unknown/隔离逻辑不变。fetch/cookie 仍走专用 backend 并接受原 bridge 校验，tab 管理仍走原分支。
5. UA 切换暂时明确拒绝：固定上游 manager 会隐式 reload，而当前导航关联不支持它。没有放松导航 callback 防线。viewport/history 等未连接模式也前置拒绝，不广告成已完成能力。
6. 新增5项 fake-resource host preflight 测试及1项真实 WebView instrumentation 源码，更新组件接线文档。上游固定源码和来源 manifest 不改。

## 精确接口与1号集成需求

### 终端 / PTY

- `PtyRunOwner(conversationId: ConversationId, authorization: TaskAuthorization, environmentId: String, generation: String)`：只接受可信宿主值。
- `PtyRunSession.open(owner, helper, argv, environment, cwd, journalDirectory, sessionScope, rows=24, columns=80)`：worker 调用；helper/launch/sequence 不来自模型 JSON。
- `read(caller, offset=0, limit=16384)`；`write(caller, requestId, inputSequence, bytes)`；`resize(caller, requestId, controlSequence, rows, columns)`；`interrupt(caller, requestId, controlSequence)`；caller 必须是构造时同一授权对象，而非字段相同的副本。
- `close()` 撤销并请求关闭；`awaitTermination(timeoutMillis: Long)` 仅 worker 等待 helper Process，false 时不能当作清理完成。不能据 helper 退出推断任意 detached 后代已清理。
- 现有 `TerminalRegistry` 是独立 shell 命令运行器，不是持久 PTY；跨命令 cwd/export 仅属于 `PtySession`。
- 此补丁没有新增中央写授权、host action planner、Linux-generation lease factory 或 Android helper 打包。Linux factory 必须把 expected-generation lease 绑定到实际 helper 生命周期，特别是 startup unknown/晚到 helper；当前失败 open 没有可供该 factory 使用的退出句柄，接入前须补齐，不因抛异常就释放 lease。

### 浏览器

- 实际入口仍为 `AndroidBrowserRunHost`，每可信 run/owner 一个实例，worker 执行 `execute(Map<String,Any?>): BrowserOutcome`，stop/child termination 关闭同一实例。
- `BrowserToolSession(..., preflight: (BrowserCommand) -> Unit = {})`：回调必须纯校验、不得获取权限/分配资源/执行 I/O；生产组合固定注入 `OpenMinisCommandMapper::preflight`。
- 构造需要冻结 BrowserGrant、app-private root、显式 cookie 环境路径/名称授权和历史许可。CookieManager 仍共享，不承诺账户隔离。
- 只选择 adapter README 指定的6份固定上游文件 + adapter/app browser 源码；不要编译整个 upstream 或引入重复兼容 FQCN。需要 coroutines core/android，不需要为此引入 Compose。公共 source-set/resource/依赖接线归1号。
- 中央 schema 用 `tab_id` 时要显式转换为此门面的 `tabId`；不能把混合读写 browser_use 机械标 READ_ONLY。UA/viewport/history/其他不支持模式不应出现在可执行广告中。

### 中央授权与回执

- `RunCatalogTools` 当前构造空 allowedWrites；设备组不造通配 grant，不把任意 shell/JS/GUI 伪装只读，不另建权威账本。
- 1号需将真实宿主授权与生命周期传入，消费现有中央 Dispatcher/SQLite ledger。prepare 零 I/O；模型参数不能指定 owner/run/launcher/lease/sequence。
- `Confirmed`、`NotExecuted`、`Unknown` 必须按实际边界映射，不能将 Unknown JSON 打包为 Confirmed。PTY transport 输入确认不是命令完成。
- 没有新增模型可见工具注册，不能据此报告生产入口已接通。

## Root / GUI：本批不接收受阻修复

task08 canonical `269e43a0c4fc433eb51f76ceccc2f9f13926f6df` 及两份修复仍有 `PROJECT_MOVED_REVIEW_AGAIN` 门禁。本批不恢复旧任务、不套补丁绕过门禁、不修改旧 canonical。

缺口：独立可信 root-owner 实现、authenticated operation receipt lookup、owner-process/lease generation、真实 launch task provenance、handoff/release recovery。Projection 不是隔离应用副屏；task07 有限只读 Root 不是 GUI owner 后端。`observe_screen` 可能隐式 acquisition，不能直接按无副作用 READ_ONLY 注册。后续需来源负责人交付已核准 owner 的精确源码/来源与固定接口，重新基线审阅后接入；现有合同说明不是可执行实现。

两项已知 task08 缺陷仍待新基线修复：Projection 最新 startId 结束清理、生产连接证明与 synthetic Engine 测试 fixture 隔离。GUI/Manifest/Activity/授权及真机验收不能由本批 host 测试代替。

## 验证与验收边界

证据根：`/workspace/daiyu-coordination/main-02-device-v1/`。

- `intake-host`：父级实际执行，Kotlin 全量源码编译（含 Android 测试的类型检查）、JUnit `OK (459 tests)`、native helper `-Werror` 编译与8项真实 POSIX PTY检查通过。
- `pty-owner-host`：父级实际执行，四项初版 run-facade 增量后 `OK (463 tests)`，compile/native exit 均0，sources_unchanged=true。此证据不覆盖后加的 read/close 修复。
- 最终 `browser-preflight-pty-close-host`：父级实际执行 `OK (469 tests)`（最终PTY run facade 5项、preflight 5项），Kotlin/native编译、8项真实POSIX PTY和JUnit退出码均0，sources_unchanged=true。Android instrumentation只类型检查未执行。精确提交绑定另见同目录 `delivery.json`；原检查以逐文件源码快照绑定，不以 BASE_SHA 冒称当时dirty源码已提交。
- 复现命令：`python3 /workspace/daiyu-coordination/main-02-device-v1/check-host.py <新唯一label>`，工作目录上述2号仓库。报告记录编译与 JUnit 命令/环境/退出码、真实 `-Ddaiyu.pty.helper` 和源码哈希。
- Android/Root/WebView/FGS/真实 Linux 运行均未执行；新增 WebView 测试仅源码交付与host类型检查。API26/API37.2 和产品验收交1号安排。

清单更新建议：记录本地候选接收、PTY绑定/撤权和浏览器前置拒绝修复及实际 host 证据；R08/R09/R10/P34、完整81项和28项验收不提升为完成。本文件是本批接缝交接，不是第二份项目任务清单。
