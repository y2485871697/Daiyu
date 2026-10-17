# MCP bc290cbd：三个已审缺陷的局部修复审查 artifact

## 范围与状态

本 artifact 面向父代理独立审查及三方组合。候选标识 `bc290cbd` 来自任务委派；本实现工具不读取 Git 元数据，未独立核验 HEAD/ detached 状态。未运行 Shell、Git、构建、测试、网络或付费调用，未建分支或 push，未读取旧 Eta/凭据文件。

**构建、单元测试、Android instrumentation：全部未执行。** 下文测试仅表示已编写的回归断言，不表示已通过。

只修改三个生产源文件：

- `app/src/main/kotlin/app/daiyu/android/mcp/McpJson.kt`
- `app/src/main/kotlin/app/daiyu/android/mcp/McpRunScope.kt`
- `app/src/main/kotlin/app/daiyu/android/MainActivity.kt`（仅 MCP 相关局部 hunk）

测试文件：

- 修改 `app/src/test/kotlin/app/daiyu/android/integration/tools/RunCatalogToolsMcpTest.kt`，新增两个 factory-failure 用例，原用例保留。
- 新增 `app/src/test/kotlin/app/daiyu/android/mcp/McpJsonUnicodeTest.kt`。
- 新增 `app/src/androidTest/kotlin/app/daiyu/android/McpApprovalVisitActivityTest.kt`。

此说明是新增专属文件。未改共同清单、README/更新说明、Controller、DB、父 catalog、MCP transport/设置/协议实现。文件工具的 diff 输出不包含新增未跟踪文件，父代理收集 artifact 时须同时收取上述两个新增测试及本文件。

## 1. JSON unicode escape

来源：`McpJson.kt` 的 `Parser.string()` / `'u'` 分支（修复后约 95–101 行）。

原实现直接 `substring(...).toInt(16)`，允许带符号的四字符片段。现在先证明四位全部为 ASCII `[0-9a-fA-F]`，然后才转换。其他字符串解析、代理对检查、JSON 限额与 canonical 路径未改。

新增测试 `McpJsonUnicodeTest.unicodeEscapeRequiresExactlyFourAsciiHexDigitsBeforeConversion`：

- `\u+123`、`\u-001` 在值与键位置均拒绝；
- 非十六进制、全角数字、短片段拒绝；
- 合法大小写十六进制、NUL、中文及合法 surrogate pair 保留正测。

状态：未执行。

## 2. transport 工厂无 handle 失败不能证明 Drained

来源：`McpRunScope.kt` 的 `registeredTools()`、`observeDrained()`、`notifyDrained()`（修复后约 37–40、69–72、132–138、151–154 行）。

- 仅包围 `transport(server)` 的构造失败路径，永久锁存 `constructionUnconfirmed` 并关闭 scope；不把“没有返回 handle”解释为空资源成功清理。
- `observeDrained` 在此状态抛出不含敏感信息的错误；`notifyDrained` 同样禁止发出成功 drain 回调。
- **不改 catalog 接口或实现。** 现有父 catalog 的 `observeResourceClose` 已将 drain 注册异常转换为 `RunCatalogCloseResult.Unknown("catalog_close_unconfirmed")`，并缓存该结果。核验来源：`RunCatalogTools.kt` 约 505–507、551–572 行。
- 不复用远程调用结果的 `unknown` 标志：有真实保留 handle 的调用 UNKNOWN 仍可在物理退出后得到 cleanup Drained；无 handle 的构造失败则不能凭空清除 cleanup UNKNOWN。
- 原有成功返回的 late handle 仍先保留、再 close，`creating` 和已有 session 的 drain 行为未重写。

新增测试（`RunCatalogToolsMcpTest.kt` 约 170–209 行）：

1. `throwingTransportFactoryWithoutAHandleRetainsCleanupUnknownAndCannotReopen`：立即构造失败，catalog 返回 UNKNOWN；重复 close 返回同一 UNKNOWN，直接 scope drain 注册失败且不回调；scope/catalog 均不能重新发现、绑定或调用 factory。
2. `throwingLateTransportFactoryCannotTurnCloseIntoEmptyMapDrained`：factory 阻塞期间 close 不早报结果；释放后 factory 抛错且无 handle，最终只得到 UNKNOWN，后续观察不能变为 Drained 或重开。

原有 late-successful-handle 与真实 Call cancellation 测试保留，供父代理回归。新增两个用例只使用计数器/闩锁，不产生网络请求。

状态：未执行。当前 UNKNOWN 通过父 catalog 已有的异常转译路径传播；三方组合必须保留该路径，不能改成吞掉注册异常并宣称成功。若直接在 factory 仍运行时订阅 scope 的 drain-only 回调，构造失败后该回调不会被错误完成；scope 本身不新增另一套 close-result API。

## 3. Main MCP 输入和 preview 的单向 visit + 前台绑定

来源：`MainActivity.kt`：

- `mcpVisit` / `mcpInput` 状态（约 758–760 行）；
- sidebar setter 即时调用 `cancelMcpEditor`（约 612 行）；
- preview 捕获原 visit，而不是 approve 时读取当前 visit（约 1679–1684 行）；
- `mcpVisitCurrent`、`cancelMcpEditor`、`approveMcp`、`openMcpApproval`（约 2295–2374 行）；
- InputPrompt dismiss 仅对 MCP 输入调用撤销，其他 Prompt 原行为保留（约 2603–2605 行）。

行为：

1. 打开时先走既有竞争 editor 取消，再发行全新 `Any()` visit。不得在输入提交时取消后重新装回原 token；因此既有 cancel 调用前移到发行之前。
2. 所有 MCP 输入提交、IO 返回发布、preview approve 检查原 visit identity、`activityUsable()`、既有 resumed 前台标志 `voiceForeground`、chat route 与 sidebar 关闭。原 conversation/editor/draft、附件与 pending-send 校验保留。
3. 输入 action 单次消费 `mcpInput`；preview approve 同时检查原 preparation identity。旧 input/approve 不能污染后来的 visit。
4. 撤销顺序是先清 token，再隐藏 MCP input/preview、清 request、拒绝 preparation。IO 返回不匹配时仍在 finally 拒绝原 preparation，不能发布到新页面。
5. 离页、onPause、onDestroy 已经通过 `cancelBrowserEditor -> cancelMcpEditor` 取消（现源约 662、2712、2772、2376–2377 行）；本补丁保留既有取消链，只新增 sidebar 的同步撤销点。返回页面/onResume 不恢复旧 token。
6. approve 只移入一个 MCP 专属方法，以便真实 host 回归测试直接保留原 preparation + visit；未修改 Controller 的确认、票据、持久化、Runner 或授权契约。

新增 `McpApprovalVisitActivityTest` 五项 instrumentation 测试：

- 原输入在离页返回、sidebar 开关、pause/resume 后均不可复活；旧输入也不能替代新输入；
- 原 preview 在 route/sidebar 返回、另一个 preview 存在时不能 approve；暂停期间不能打开/批准，resume 也不恢复旧批准；
- dismiss 撤销 inner action；
- 持有 UI lane 等真实 Controller 在 IO 创建 preparation，离页再返回后才放行 continuation，证明旧 IO 结果拒绝而非发布，并可随后打开全新 preview；
- framework recreate 后，原 Activity 的 input/preview callback 不可复用。

测试使用已有 `WiredActivityTestCase` / `RunControlFixture`，MCP envelope 仅在随机新 root 下保存一个空 bearer 的合成服务器；transport factory 是计数并立即报错的阻断器，模型为既有内存 fixture。断言原文草稿、零消息/会话、零 transport/model open；不访问已安装的 MCP 私有文件，不需要真实网络或付费模型。IO gate 的短暂 UI-lane 等待只存在于测试，未增加生产测试 seam。

状态：未执行。反射私有 host/Controller 字段、Compose dialog tags 与 framework recreate 的兼容性必须由父代理编译和设备测试核实；不能把静态审阅当作运行证据。

## 父三方组合注意事项

不要用候选版 `MainActivity.kt` 整文件覆盖父当前 Main；只取 MCP hunk：新增两个状态、sidebar 撤销调用、MCP preview 原 visit 捕获、MCP editor 方法、MCP 输入 dismiss 条件。保留父当前 GUI/context/reasoning/generation/terminal 及其他现有内容，不回退候选周边代码。

尤其核对父 Main 仍有：

- `onResume` 设置前台、`onPause` 先撤销再返回；
- 离页/onPause/destroy 的 MCP 取消链；
- shared PromptSurface 的非 MCP 分支不变；
- 原 Controller/catalog 已审 MCP 接线与 cleanup 异常转译契约。

父代理建议独立检查：三个生产文件局部 diff；两个新测试文件完整内容；扩展后的 `RunCatalogToolsMcpTest`；随后在允许的构建环境编译和运行上述测试以及原 MCP 测试。此实现代理没有运行这些步骤，也未产出任何通过率或已验证 APK。
