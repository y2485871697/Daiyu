# MCP 最小生产接线与边界

本文说明本次新增实现和回归源码，不替代唯一功能清单或更新说明。未复用历史 Eta leaf，也未新增依赖。构建、主机回归和设备回归须由集成方执行；本实现代理仅能检查和修改源码，**没有运行测试或进行远端服务器兼容性验证**。

## 实际入口

1. 设置搜索 `MCP` → **MCP 服务器**（`SettingsCatalog` 的 `MCP_SETTINGS` route，真实 `MainActivity` 页面）。最多保存 8 个不同 ID 的服务器。地址支持 HTTPS，以及本机 `http://127.0.0.1`（端口可配置）；其他 HTTP 仍拒绝。主网络配置默认禁止明文，只对精确 `127.0.0.1` 放行，不包含子域名。拒绝 URL 用户名、密码、query、fragment。可选 Bearer 不回填、不显示、不写入日志/备份。访问页面、保存、删除和修复清除都不连接服务器，也不制造任务批准。
2. 聊天的 **MCP 本轮精确调用审批**，仅限无附件文本：填写
   ```json
   {"server":"my_server","tool":"tool_name","arguments":{"target":"one"}}
   ```
   目前 UI 每轮选择一个服务器的一个工具。宿主 scope 支持最多 8 个不同 `(server,tool)` 的精确意图，但没有隐含批量/整台服务器授权入口。工具名由用户明确给定，目录只验证该工具存在；并不以连接成功替代兼容性验证。
3. 审批显示服务器 ID/名称、无凭据 URL、工具、完整 JSON 参数、原文与助手。**所有 MCP 工具均按可能有副作用的 WRITE**，不相信 `readOnlyHint` 等远端注解。确认只批准本轮这组完整参数。拒绝、编辑/换会话、离开/后台进入或旧页面回调不能借用新页面批准。
4. 审批之后通过 Controller 原 `TextSendTicket` / 原数据库 draft CAS 接受消息对，再由原 Runner 初始化 catalog；这时才发送 MCP initialize/list。目录缺失、协议/格式不支持、密钥不可读等失败均不回退到其他服务器、不扩大范围。DB CAS 失败不启动 MCP 或模型请求；DB 保存结果未确认保留原文，不自动重发。持久 Accepted 回执仍优先于晚到的 ticket cancel。

## 私有配置与身份

- `McpSettingsStore` 把**整个服务器配置 envelope**用 AndroidKeyStore AES/GCM 加密，原子保存在 `noBackupFilesDir/mcp-private-v1.bin`。URL、Bearer、名称均不以明文落配置文件；不接入通用备份、聊天模型设置或导入恢复。
- 每次保存都轮换持久 revision，进程内另有不可复制的配置 stamp。ABA 修改、旧页面保存以及观察到的旧密文回滚不能重新激活之前的 scope。损坏读取失败闭合，必须用户明确清除再重配，不静默恢复空配置后启用工具。
- prepare 冻结服务器、精确参数、conversation/run/message/editor 身份、助手与 provider。配置保存不是批准，ask_user 也不能升级冻结范围。
- `McpRunScope` 由宿主发出**唯一 `TaskAuthorization` 对象**；`RunCatalogTools` 必须收到同一个对象，等 Runner 绑定其真实 opaque `RunExecutionOwner` 才能绑定 leaf。复制 task 对象、同 ID 不同 owner、换配置、取消和错误的消息 owner 均失败闭合。没有第二 Runner、leaf dispatcher、leaf ledger 或模型自报 owner。
- 动态注册名为 `mcp_<index>_call`，参数 schema 只有 `arguments_json`，且必须与已批准 JSON 完全一致（对象排序/空白 canonical 化）。这是受限精确调用包装器，**不是完整远端 JSON Schema 引擎**；远端 schema 必须声明 object，参数业务含义仍由服务器校验。工具描述不包含配置凭据；回包中原 Bearer 的字符串/键回显在进入 ledger 前递归脱敏。
- 原 central `ToolDispatcher`/ledger 做整批校验、WRITE grant、UNKNOWN reservation、落回执和回放。MCP 的 effect 包含原 conversation/run 和配置 revision，远端 initialized session 的回包不可从旧账户/运行重放给新批准。相同 scope 的等价调用仍由原 ledger 去重。context admission 在新执行与回放之前生效；central dispatcher 现在还在持久 reservation 后、读取已确认回包前再次检查 admission/取消，封住存储阻塞期间撤权的回放窗口。

## 受限 Streamable HTTP

实现为 `McpHttpSession`，使用已经安装的 OkHttp 4.12.0：

- JSON-RPC `initialize` → `notifications/initialized` → 单页 `tools/list` → 精确 `tools/call`；协商版本仅 `2025-03-26`。
- POST `Accept: application/json, text/event-stream` 满足协商要求，但**目前只解码 application/json UTF-8 回应**。SSE、GET 事件流、反向请求、batch、分页 cursor、资源/图片内容均拒绝，不试探第二 transport，不重发同一个调用。
- 初始化返回的可选 `Mcp-Session-Id` 仅绑定到这一个冻结 URL/client；后续请求携带同一 ID，服务器不能替换或临时补发新 ID。协议版本 header 固定。RPC id 类型/值、jsonrpc 字符串、result/error 互斥、serverInfo/capability/目录与工具结果形状均验证；工具内容仅 text，可选 structuredContent 必须 object。
- 严格 JSON parser 拒绝重复键、非标准 JSON、无效 UTF-16、尾随内容；普通请求与工具调用响应维持 64 KiB、12 层、4096 节点；`tools/list` 目录响应单独允许 1 MiB、32 层、32768 节点，以容纳服务器附带的 outputSchema 等元数据。加密配置 envelope 单独限制为 2 MiB、32 层、32768 节点；旧小配置保持兼容。各模式仍限制字符串 32768 字符、数字 128 字符，保留严格重复键与 Unicode 校验。响应先按字节限量再严格 UTF-8 解码。最多 64 个目录工具、32 个结果内容块、每 session 32 个请求；完整批准参数最多 32768 UTF-8 字节。
- 禁止重定向、connection retry、HTTP authentication/proxy authentication。额外 network interceptor 在 OkHttp follow-up 逻辑之前拒绝非 2xx，避免 `503 Retry-After: 0` 等独立于 connection retry 的自动重发。没有请求/响应日志 interceptor。连接 10s、读写 20s、总 call 30s。
- tools/call 一旦进入 transport 后出错、取消、响应格式异常、结果 owner/config 已撤销，都按 **UNKNOWN**；不伪称 NotExecuted，也不依赖异常文本证明没有副作用。规范 isError=true 的有效工具结果是确认的错误回包，而非自动重试信号。

这仅是已列明的标准 JSON 子集。**不声称兼容所有 MCP 服务器**，尤其不声称 SSE、OAuth、旧 HTTP+SSE、stdio、通知更新、任意资源内容或分页已完成。

## 暂停、停止、晚到与恢复

- 暂停/继续复用原 Runner 边界协议：已执行的工具批次允许落完回执再暂停，暂停不是工具操作回滚，也不会重新 initialize/list。继续使用原 opaque owner、scope 和 task；配置过期后不能执行或回放原 MCP 工具。
- 停止/关闭通过原 Controller → `RunCatalogTools.close` → scope/session → **真实 `okhttp Call.cancel()`**。不是仅设 cancelled Boolean。scope 在 transport factory 运行前已被 catalog retain；晚返回的 client/session 必须发布到原资源表后立即关闭，不能丢掉 handle。
- 调用返回的 response/body `use` 完成、execute 返回后才清 active Call。随后驱逐该 session 专属连接池并 shutdown 专属 dispatcher。catalog close 还等待创建、dispatch、central 回执与所有资源 drain；cancel 请求本身不等于 drain。卡住的 execute/工厂仍保留原 catalog/owner，不放行新的任务，不凭超时伪报已释放。
- MCP 本身没有新增 native host；浏览器/终端/副屏等现有 native drain/UNKNOWN 保留逻辑继续由原 catalog 执行，没有换成 MCP 的本地 transport 结束信号。
- 这里的 Drained 仅证明**本地 client/call/socket 资源归还**，不证明服务器已回滚副作用或结束远端 session。最小子集未发送 DELETE 远端会话；不能把本地取消说成服务器执行被撤销。
- 中央 UNKNOWN（含回执持久化未知）会反向 fence MCP scope；同一授权不重试。新的用户明确审批是新的任务，不是恢复旧工具。进程重开只走已有 SQLite runtime/history recovery，不重构 MCP scope、不生成旧 task、不自动 initialize、call 或重放工具结果。

## 回归源码与可验证证据

这些是待集成方执行的回归源码，不是已通过报告：

| 源码 | 覆盖 |
|---|---|
| `RunCatalogToolsMcpTest` | 原 catalog/真实 Runner-issued owner/唯一授权/原 dispatcher+ledger；readOnlyHint 仍 WRITE；全批参数拒绝；确认回放及撤权回放；reservation 阻塞期间撤权；中央 UNKNOWN 无二次调用；真实 okhttp Call cancel 和 execute 未返回时不 drain；晚返回 transport factory 保留关闭 |
| `McpHttpSessionTest` | 固定 session header/id、协议版本、SSE/pagination 拒绝；无重试 initialize；默认重定向/retry/auth/超时设置；字节限量 |
| `McpSettingsStoreTest` | 整 envelope 加密/随机化、noBackup 路径、ABA/密文回滚不复活 scope、过期页面拒绝、损坏显式清除、严格 JSON、递归 secret 脱敏 |
| `ControllerMcpSendTest`（设备） | 实际 Controller/SQLite draft CAS；无批准与旧配置拒绝；原票据 durable 接受；HTTP 工厂只能在 DB 接受后进入；暂停/继续/停止不重新连接；新 Controller 启动只恢复中断历史、不重开 transport/模型 |
| `McpSettingsActivityTest`（设备） | SettingsCatalog 真实 route；设置不写会话/不调用模型；空白 secret 编辑字段；返回/重建撤销旧页面 owner |
| `McpSettingsScreenTest`（设备） | 实际表单保存、重新读取、旧页面不可再次保存；隔离的临时 noBackup synthetic 数据 |
| `SettingsChromeActivityTest` | 原无 route 的 MCP 行现在实际导航；原 Skills 仍保留 |

HTTP 回归使用真实 OkHttp Call 和协议实现，只替换 wire interceptor；**未连接真实远端 MCP 服务、未进行设备测试或真实 TLS socket 端到端验证**。集成验收仍应实际构建 app/test/androidTest，并在设备执行设置、Controller、取消/恢复回归；若需服务器兼容性验收，应单独使用允许的免费测试服务器确认上述子集，不以单次 initialize 成功宣称全部通过。
