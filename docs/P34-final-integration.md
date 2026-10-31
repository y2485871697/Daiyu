# R10 / P34：child、下载与首次私有根目录接缝

## 基线与状态

本增量基于任务指定的 `a2d41ef` 工作树内容；工具无 Git 元数据权限，未独立核验 SHA。先读唯一清单 `docs/重构清单.md`，尤其“P34：真实聊天浏览器审批父接线”“P34：浏览器真实父入口拒绝与取消回归源码”以及其后的中央目录绑定记录。它们已接主浏览器准备、精确批准、原 TaskAuthorization、Runner 和数据库接受回执，本次不重做池、授权或主发送入口。

本文件是**接线合同与源码交付说明**，不是第二份进度清单。本次未构建、未运行任何 JVM / instrumentation / 网络请求测试；不得标记 R10 / P34 完成。MainActivity、ChatController、RunCatalogTools、RunChildModels、数据库 schema、总清单、CI、依赖均未修改，父代理统一接公共入口与验证。

## 已补能力与安全边界

- `BrowserRunPreparation.prepare` 仍显式要求 MAIN。新增独立 `prepareChild`，只接受可信 host 的原 `ChildRunScope`，不从 FULL、模型参数、网页、设置保存或 ask_user 推导许可。
- CHILD 资源 owner 的 `sessionId` **必须是 childRunId**，kind 为 CHILD；原 task 的 sessionId 则保留 `ChildRunScope.capture` 继承的父 sessionId，两者不能混为一谈。child conversation / run / assistant-message / assistant profile、原 opaque Runner owner、原 session、原 task 均逐层绑定；scope 固定完整 parent run / group / generation，不按相等 ID 重建新 task。
- child 可用动作必须经过父 capability / exact-write 交集。capture 中缺失一项 write 或 capability，准备即拒绝；不补权、不复制 task、不自动扩大父批准。
- `fetch` 进入 FULL 的可选动作，但依旧是精确 WRITE：完整 URL、显式 tab_id、owner kind / child run namespace 都进入原 `RunBrowserTools.writeEffect`。无批准参数、不同 URL、任意路径 / destination / headers / cookies 均不能执行。
- 下载复用原 `AndroidBrowserRunHost -> BrowserFetchBackend -> BrowserHttpDownloader -> BrowserDownloadCoordinator -> BrowserPathMapper`，不另造下载器。路径仅为 app-private downloads 下 UUID 文件；现有 payload `{text, filePath}` 的 text 中携带实际 path / size / SHA-256。真实 dispatcher / SQLite ledger 持久化该回执，CONFIRMED 回放不 GET，UNKNOWN 不 GET、不换 callId 重放。
- `CatalogBrowserBinding` 的 cookieAuthorizeName 仍恒 false、cookieEnvPath 仍拒绝、historyApproval 仍 DENY。fetch 不借 WebView CookieManager，不添加 CookieHandler，也不授共享 mount。没有新增日志，更不记录 cookie 明文。主子 tab 隔离**不等于**账户 / WebView 全局 cookie 隔离。
- 仅为支持继承 sessionId，对 `RunBrowserTools.createTools` 做了一处最小检查适配：confirmed 路径使用已经验证并绑定原 task 的 token.taskSessionId；legacy 路径仍要求 browser owner sessionId 与 task sessionId 一致。没有放松 opaque owner 或 invocation admission。

## 父必须接的首次发送修复（明确调用点）

已知旧 sendBrowser 将尚不存在的 `files/browser-runs` 交 native host 的 `toRealPath`。native host 要求已存在私有目录是正确边界，不应在 preview 中 mkdir，也不应让 host 静默吞路径错误。

在父 `sendBrowser` 的**原 worker、确认之后、openOnWorker 之前**，把直接拼接目录的代码替换为：

```kotlin
val root = ControllerBrowserPreparation.preparePrivateRootOnWorker(context)
val binding = preparation.openOnWorker(context, root)
```

- helper 先检查非 main，再取得可信 application filesDir；仅创建固定 `browser-runs` 单层目录，不接受模型路径。
- 复用 BrowserPathMapper 的 canonical / NOFOLLOW / ancestor / Windows junction 校验；并发已存在目录重新校验，文件 / 链接拒绝。可信 Android filesDir 别名先 canonical；不把平台纯别名误认作链接。
- 预览 / confirm 从不调用 helper。创建失败沿父现有 rejected / cleanup 链处理；不要消费草稿、不要请求模型、不要进入重试循环。
- helper 不创建 WebView、授权、Runner、tab、共享映射或数据库记录。父原准备槽 / durable acceptance / construction failure 资源持有继续复用。

## 父必须接的 child 合同（可调用实现，不是永远 DENY 的占位）

独立实现为 `integration/tools/CatalogChildBrowserSession.kt`，所有生产方法均已有实际 body。它不是中央目录，不创建 Runner / dispatcher / ledger / 新 TaskAuthorization。

### 1. 在可信子任务计划被冻结时签发权限

1. 固定完整 `ChildRunOwner`（conversation、parent run、task group、generation、childRunId、assistantMessageId）及子 profile。资源 grant 为 `BrowserOwner(childRunId, CHILD)`，access 与已保存设置求上限。
2. 父的可信批准页明确展示子身份、动作及所有原始 JSON。可提前用 `RunBrowserTools.writeEffect(childGrant, raw)` 纯计算精确 effect，**不需要 session / WebView / 目录 IO**。父的原 TaskAuthorization 必须已经包含这些 child-target effects，不能沿用 MAIN-target effect，也不能后补不可见授权。
3. 调现有 `ChildRunScope.capture` 将 requestedCapabilities / requestedWrites 与父原 session / authorization 求交。保留返回的同一个 scope，不再做一份 browser-only task。
4. 调 `CatalogChildBrowserSession.prepare(scope, frozenAssistant, settings, childGrant, actions, rawWrites)`；将其 `preview` 完整展示并经可信人的明确确认调用 `confirm(displayedPreview)`。对象不是同一个 preview、重复确认、已关闭均拒绝。未确认时不得打开资源。

### 2. 资源构造与真实 child Runner 绑定

1. 父 worker 调上述 mkdir helper。再投递主 Looper 调 `childBrowser.openOnMain(context, root)`；该方法只构造原 native host，不隐式开 tab / navigate / fetch。
2. **真实 child Runner** 的 bindOwner 回调、首次 schema 读取之前，调 `val leaf = childBrowser.bindOwner(originalChildExecutionOwner)`。
3. 在该 child 的原中央 registry 注册 `leaf.tools`；Dispatcher 必须继续用 `scope.session`、`leaf.taskAuthorization === scope.authorization` 和父指定的原持久 ledger。工具执行必须 `dispatchOwned(..., originalChildExecutionOwner)`；完整原 invocation 原样传递。不创建 synthetic owner，不重用主 Runner owner。
4. MAIN / 其他 sibling 的 owner、相等 IDs 的新 TaskAuthorization、替代 session、不同 raw effect 在 reserve / CONFIRMED replay 前被拒绝。

### 3. 停止、失败和未知结果

- child Stop / 取消 / 终态先 `childBrowser.close()`：同步 revoke token / binding、再锁外请求 native close。暂停不等于 Stop，不应销毁仍暂停的原 child 会话。
- 原 child resource owner 必须保存这个对象，构造中 Stop 不能丢弃它；迟到构造出来的原 binding 自动关闭。构造失败持有原 BrowserRunConstructionException，而不是仅保存 message/cause。
- maintenance 仅 `closeResult()` 观察。null 是 pending；Unknown 仍持有资源 / child capacity，不发新模型、不重开 host、不重放下载；只有明确 Drained 才按父已有规则归还容量。
- `retryCleanup()` 只供显式重试清理，不重试 constructor、tab、网络或模型。UNKNOWN 是保守 sticky 状态，不能靠一次后续好看的 snapshot 自动变为释放证明；必要人工收口由父现有 UNKNOWN 政策处理。
- 已确认下载的文件在原私有目录保留以便持久回执继续引用；Stop 不删除其他 owner 已确认文件。目录清理 / 账户删除仍由父原存储生命周期处理，不在 child Close 删除共享根目录。

### 当前跨模块待父完成的接缝

`RunChildModels` 明确是 text-only batch，当前没有 child tool Runner / browser 工厂，不能偷偷把其 description 改成有工具。父需要在**工具型 child 的可信组装路径**提供以下同一资源单元：

- 冻结的 `ChildRunScope`、profile 与明确批准的 `CatalogChildBrowserSession`；
- 真实 child `RunExecutionOwner` 产生后的绑定回调及原 registry / dispatcher / ledger；
- Stop / 正常终态 / 构造失败 / late-open 的清理及 maintenance 观察，UNKNOWN 时保留 child permit。

本合同不要求修改数据库 schema，也不要求为 text-only `run_child_models` 自动赋浏览器权限。若当前产品仅能创建 text-only child，父必须先接实际 tool-enabled child Runner 再广告 child browser；不要将本源码交付描述成公共 child 已开放。上述 session 可直接在已有真实 Runner bindOwner 接口消费，测试源码已有完整独立分派链，不是无实现的占位。

## 测试源码与待统一验证

新增 / 增补均**未运行**：

- `BrowserRunPreparationTest`：MAIN 保持拒 CHILD；fetch 需完整 exact effect / tab；禁止 destination / headers / cookies 与 READ_ONLY；准备零 native IO。
- `BrowserPrivateRootTest`：首次缺根实际创建与再次校验、普通文件及 symlink 叶拒绝。
- `BrowserChildProductionTest`：原 ChildRunScope task / owner、越权零创建、MAIN / CHILD tab 0 隔离、跨 owner close 拒绝、child close 不影响主 tab；原 fetch/backend/http/coordinator 实际落私有文件和 SHA；CONFIRMED / UNKNOWN ledger 视图重开不二次 GET；构造中 Stop 保留迟到 handle；close UNKNOWN 不重构 / 不隐式重试。
- `CatalogBrowserBindingAndroidTest`：隔离 app-private 空 filesDir、preview / confirm 不 mkdir、worker helper → 实际 AndroidBrowserRunHost / binding → 原 Runner owner 成功构造及物理 close；主线程 helper 在任何 mkdir 前拒绝。
- `BrowserDownloadLedgerAndroidTest`：真实 Android 文件、生产下载管线（仅 HTTP connection fixture）、SQLite 关闭重开，实际 path / bytes / size / SHA-256 及 CONFIRMED / UNKNOWN 不二次 GET。

父最终必须核验编译 / JVM / Lint / APK / API26 与 API37.2，包括本轮新方法的发现数。此处 fake HTTP 不冒充真实 TLS / 外网下载证据；还需受控真实 HTTPS、取消在途与磁盘失败 / native destroy 失败等设备回归。未访问网页、付费供应商或历史自有源码 活树，未改第三方固定源和许可。
