# P27：真实结果分页与搜索专用底层取消

## 范围与接缝

任务给定基线：`57064e27`（本实现环境没有 Git/构建接口，未独立查询 HEAD）。本补丁不修改 MainActivity、ChatController、ConversationDatabase、备份实现、唯一清单或更新说明。

生产宿主 **必须** 把 `MainActivity.kt` 的 `MessageSearchScreen` 调用中以下参数：

```kotlin
onSearch = { query -> retainedData.search(query) },
currentConversationId = page.conversationId,
onSearchCurrentConversation = { query, id ->
    check(id == page.conversationId) { "搜索范围已改变" }
    retainedData.searchCurrentConversation(query, id)
},
resultLimit = RetainedConversationData.SEARCH_RESULT_LIMIT,
```

精确替换为：

```kotlin
onSearchPage = { request, cursor, signal ->
    check(request.conversationId == null || request.conversationId == page.conversationId) {
        "搜索范围已改变"
    }
    retainedData.searchPage(
        query = request.query,
        conversationId = request.conversationId,
        cursor = cursor,
        cancellationSignal = signal,
    )
},
currentConversationId = page.conversationId,
supportsCurrentConversation = true,
```

`onOpenHit`（包括宿主的 page/route fence）、`onBack` 及精确 owner/window/segment 导航逻辑全部保持原样。不要将 `request.conversationId` 换成从 IO 实时读取的当前会话，也不要把全局第一页过滤成当前会话结果。接缝完成前，旧 Main 调用与新屏幕签名有意不兼容；构建验证须包含宿主修改。

## 数据契约

- `RetainedConversationData.searchPage` 返回 `MessageSearchPage(results, nextCursor)`，默认每页最多 200 个 **expanded 命中**；`nextCursor == null` 才表示没有后续命中。
- `MessageSearchCursor` 是进程内 continuation，不是 SQL offset，也不是持久化或可跨 scope 使用的凭据。它冻结 trim 后的 query、全局/指定 conversation scope、按 `pinned DESC, updated DESC, id ASC` 排列的会话 ID/标题、每个作用域内会话的内容 revision，以及最后一次发出命中之后的精确 `(conversationIndex, expandedIndex)`。
- 一页在同一个 `ContextService.searchRead` 事务内读取会话顺序与完整历史、计算 revision、匹配，并校验后续 cursor。作用域会话先经 SQL `WHERE id=?` 限定，再读历史与匹配。已删除 scope 在首读上是错误，旧 cursor 的 scope 消失是明确失效，不降级为全局搜索。
- revision 使用逐 owner 的 `ContextRevision` 内容摘要，再以长度前缀组合 owner/segment 时间元数据，覆盖正文、协议、状态、error、segment 内容/顺序等。全局搜索也验证尚未返回命中的会话，避免把旧快照前页与新快照后页拼接。指定 scope 的 cursor 不会因其他会话历史变化而失效。
- 会话集合、排序、标题或上述历史 revision 不同，抛 `SearchCursorInvalidatedException`。UI 保留已显示结果，明确提示历史改变，并要求刷新；不会把新历史静默追加。若历史恢复成完全相同的内容/顺序，内容 revision 也相同；不是数据库单调变更计数。
- 仍对完整的物理会话历史调用 `MessageHistory.expand`。不先截 200 个物理 owner，也不把 standalone supplement 重复展开。每页遍历 cursor 之前的 expanded 消息以重算 `(runId, messageId)` occurrence，确保同一 owner 在第 200/201 或 400/401 个命中处跨页时，`segmentIndex` 仍精确连续。`ownerMessageId` 保留 segment 到物理 owner 的映射，snippet 不替代导航身份。
- 搜索仅匹配显式正文/tool 文本；不搜索序列化 IDs/protocol 元数据，不读取图片/视频附件表、blob 或 sidecar 文件。搜索专用 ContextService 读取不改变原 display/send 路径的附件校验。
- 旧 `search` / `searchCurrentConversation` 仅作为兼容首屏 List 读取器保留，仍最多返回第一页 200；不能用于生产分页接缝。`SEARCH_RESULT_LIMIT` 现在是单页默认上限，而不是整个搜索的上限。

## 取消与 UI 状态

- 独立的 `MessageSearchRequestState` 负责 P27 分页；P28 的 `SearchRequestState` 不变。每次首读、续页、重试创建独立 `CancellationSignal`，不复用已取消的 signal。
- query/scope 切换、显式取消或退出先增加 generation fence，再取消旧 signal/job。防止不合作 reader 的迟到结果/异常覆盖新 query 或 scope。
- 在 IO dispatcher 内、进入同步 reader **之前** 使用 `suspendCancellableCoroutine` 注册 `invokeOnCancellation { signal.cancel() }`。父 coroutine scope 的取消能在同步 SQLite 正执行时触达 signal；不是等阻塞协程结束才调用的普通 Job completion 回调。额外的 completion hook 覆盖尚未进入 IO 的取消。
- 搜索专用会话查询、ContextService 的消息 metadata/created_at 查询、SQLiteText 的长度/64KiB chunk 查询均调用带 signal 的 `rawQuery`。所有游标 `use` 退出；事务沿 `DatabaseTransactions.run` 的 `finally` 结束。会话/消息/segment/chunk 与扫描循环检查 `throwIfCanceled`。
- 首读错误与空结果、取消不同。加载更多单飞；失败保留旧 rows/cursor，可原位重试，成功才追加。历史失效也保留 rows，但移除旧 cursor，只允许刷新。追加取消保留已显示 rows。
- UI 显示加载更多、追加重试、历史改变提示；不再显示“无分页最多 200 条”的旧提示。提供英文、简体和繁体字符串。

## 明确未解决的性能边界

本补丁是 **结果分页**，不是底层数据库有界历史分页：

1. 每一页仍读取作用域内每个会话的完整物理历史，并完整验证/展开。为拒绝任何旧历史 revision，会扫描未产生本页命中的会话；不能宣称每次仅从 DB 取 200 行。
2. 事务沿用现有 `DatabaseTransactions` 实现，其 `beginTransaction` 获取连接/锁的等待不是 cancellable SQL 查询；取得事务后立即检查 signal，且总会 finally 退出。
3. JSON decode、单 owner 的 `ContextRevision.digest`、`MessageHistory.expand`、文本构造/匹配以及 UTF-8 decoder 是现有同步 CPU 操作；在调用边界及外层循环检查取消，不声称这些函数内部每个字节均可即时中断。
4. 单字段仍有 SQLiteText 原有 64MiB 上限，整会话读取/expand 的内存上界未解决；已显示结果随续页增长。没有改动发送、数据库 schema 或备份行为。

后续若要做数据库有界分页，需要能够保留 owner/supplement 的整历史去重验证语义与可靠的持久化 revision；不能只给物理 messages 加 `LIMIT/OFFSET 200`。

## 定向验证与证据状态

实现代理仅做源码审阅，**没有执行构建、JVM 测试、设备测试或创建 Git commit 的能力**；以下是新增/扩展测试，不是通过结果。主代理负责接入 Main、运行验证并产生非空代码 commit。

| 测试源 | 覆盖 |
| --- | --- |
| `app/src/androidTest/kotlin/app/daiyu/android/RetainedSearchDataTest.kt` | 201/401 个同 owner 命中分成 200+1、200+200+1 且无缺失/重复；跨页 segmentIndex；standalone supplement 在 page boundary 去重；query/scope/order/unread history 失效；scope 隔离、删除失效；预取消及多 chunk UTF-8 读取；原有 scope-before-cap/旧消息/精确导航回归 |
| `app/src/test/kotlin/app/daiyu/android/ui/screens/search/MessageSearchRequestStateTest.kt` | 追加失败保留与重试、独立 signal、重复加载单飞、历史失效要求刷新、派发/debounce 取消、scope 换页迟到结果 fence、父 coroutine 取消在同步 reader 阻塞期间触达 signal（Robolectric API26） |
| `app/src/androidTest/kotlin/app/daiyu/android/SearchSQLiteCancellationTest.kt` | 真 SQLite 第二个 64KiB chunk 查询创建时（首 chunk 已完成）确定性取消、所有游标关闭/连接可再读、native 执行中 aggregate rawQuery 被 signal 中断 |
| 原有 `SearchRequestStateTest` | P28 legacy 取消和 generation fence 保持兼容，未改其实现 |

建议宿主接缝完成后运行针对上述类的 JVM/Android 编译与测试，并保留实际任务结果。native SQL 测试使用有界等待及 billion-row aggregate 确保取消前无法正常完成，不把预取消误报为在执行中的 native 取消。
