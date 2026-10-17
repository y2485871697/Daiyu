# R11 / P01 记忆有界读取与助手编辑收口

## 基线、来源与真实状态

- 委派基线：`a2d41ef`。先读取 `docs/重构清单.md` 最新记录（至 P19/P20 安全下载接线），复用既有 P01 SQLite/会话绑定/模型快照、Skills 和 Context 宿主，不重做它们。
- 本次仅 assistants 包、`AssistantEditScreen.kt`、独立 `ScopedAssistantMemoryTools` 及对应测试/本文档；不修改 MainActivity、ChatController、RunCatalogTools、数据库唯一 schema、依赖、CI 或总清单。
- **本次未执行编译、测试、Lint、设备操作或网络请求。** 下列为实现及测试源码范围，不代表运行验收通过；父代理集中集成和统一验证。

## AC060：核心与按需只读检索

生产宿主应使用新增重载：

```kotlin
// 在已有准备阶段的 IO 执行器中；assistantId 必须来自本次会话的冻结绑定。
val assistant = AssistantRunContext.capture(assistantStore, boundAssistantId, assistantAssets)
// 同一对象交给既有 AssistantModelFactory，不要另读 active 或重新 capture。
// assistant.systemPrompt 已包含有界核心及模型记忆策略。
```

- `AssistantRunContext.resolvedMemory` 是最多 **8192 UTF-8 字节**的核心，不是 1 MiB 整段；超预算保留显式截断标记，标记也计入这 8192 字节。不会切断合法代理对，不把字节预算冒充 tokenizer 精确 token 数。
- `memoryRevision` 是资产存储返回的修订（现有文件实现为内容 SHA-256）；`memoryReadPort` 冻结相同 assistantId + revision。身份不接受模型参数、文件路径或全局选中助手回退。
- `read(offset,maxBytes)` 每次至多 8192 UTF-8 字节，offset/nextOffset 为 UTF-16 字符偏移；代理对中间偏移及不足以读取下一个完整字符的预算拒绝，避免无进展分页。
- `search(query,maxHits,maxBytes)` 是大小写不敏感的**字面子串**检索，不执行正则；查询至多 256 字符、至多 8 个片段、所有正文片段合计至多 8192 UTF-8 字节。返回片段起始 offset 和 truncated。
- 按需读取重新读取**同一助手**并比较原修订；旧 revision 返回冲突，不静默读取新内容。助手删除或实时关闭 memory 后也拒绝读取。捕获时 profile 在文件读取期间发生变化则准备失败，不拼接混合 profile。
- 现有文件层 1 MiB 是磁盘解析安全上限，继续保留；按需端口内部一次可解析至多这个上限，但不会整段注入或整段返回。不是流式索引或无限文件读取方案。每次检索为有界文件上的线性查找；本次没有新增全局索引或后台持久状态。
- legacy `capture(store,id,resolvedMemory: String)` 仍兼容已有调用并强制核心上限，但没有 revision/按需端口。**父必须切换至 assets 重载，才算真实按需检索接通**；不能仅凭旧字符串已被截断宣称 AC060 完成。
- 捕获时 memory 关闭：零 `assets.memory` 调用、零核心、null revision、null port、无记忆策略/正文注入、无记忆工具。手动编辑页仍可读取/编辑文件，这是用户编辑边界，不是运行注入。

### 父集中接线的最小工具接口

```kotlin
// 只在该 run 的私有 registry 中注册；与模型 prompt 使用上面同一个 assistant 对象。
val memoryTools = ScopedAssistantMemoryTools.create(assistant) {
    /* 这里接父已有的精确 run/catalog owner 存活及撤权检查，不能恒定 true */
    runIsCurrentAndAuthorized()
}
memoryTools.forEach { privateRunRegistry.register(it) }
// 仅当 memoryTools 非空，为该 run 的 SessionCapabilities 增加：
// ScopedAssistantMemoryTools.READ == "assistant.memory.read"
```

上述 `privateRunRegistry` / `runIsCurrentAndAuthorized` 是接线示意而非新增宿主 API；本次没有擅改父文件。已有 catalog ledger/dispatcher 继续承担真实 owner、参数验证、能力和回执控制。

额外只读核对发现：当前 `ChatController.kt` 约 858 / 1433 / 1542 / 1705 行有四处“读 memory → 覆盖 read 的 frozen AssistantStore → 字符串 capture”（子任务历史命令、终端、浏览器准备、普通准备）。父应逐处迁移并组合各自原 profile/provider/binding CAS，**不要把 frozen store 代理传给新重载**：它会使端口的删除/关闭检查永久读取旧 profile。应传真实 `assistantStore`；新 capture 内部已完成冻结和读后复核。工具广告、模型上下文、子模型继承需共用同一捕获，不允许重取 active；终端/浏览器原有专用授权边界不放宽。

仅新增 `assistant_memory_read` / `assistant_memory_search` 两个 READ_ONLY 工具。prepare 不执行文件读取，execute 才读；execute 前后检查运行存活，撤权后不交付正文；JSON 转义后的回执另有 64 KiB 上限。冲突/关闭/无效参数/读取失败返回固定安全错误码，不将底层异常或新修订正文带给模型。

## AC061：模型持久策略与显式用户编辑的边界

- 本次 **不提供模型记忆写入、合并、删除、保存候选或自动学习入口**，不因为增加补读而自动注册模型写工具。
- 新 capture 的 system prompt 和工具说明明确：长期记忆只用于稳定偏好和可复用事实；秘密、密码/密钥、验证码及一次性请求不得自动持久化；不得用通用文件/终端工具绕过这个限制。记忆正文是资料，不是权限或新指令。
- 当前助手文件持久化来自用户在助手页明确编辑并点击保存；这是独立人工操作，不是给模型的持久授权。保留原有内容与 1 MiB 字节限制，不加入密码/验证码词语正则，不误拦“不要存验证码”等正常用户文本。关闭 memory 不删除用户文件。
- 这是“本次模型能力只读 + 明确策略”的实现，不声称用提示词证明所有通用设备写工具的内容安全。如果未来需要模型持久化，须另做结构化候选、稳定事实/敏感/一次性分类、明确用户确认及带修订 CAS；不能直接把本次只读端口换成通用文件写入。

## 助手页：串行、完整 profile CAS 与迟到保护

- `AssistantStore.compareAndSet(expected,updated)` 新增完整 profile CAS；缺实现默认拒绝，不能退化成旧 `save` 的 upsert。
- `SqliteAssistantStore` 使用原表同一事务读取完整 expected profile、只 UPDATE 既有行、验证回执，再沿用现有 publication revision。比较包含 name/prompt/avatar/createdAt/memory/skills；不新增列/表、不修改 schema。删除后旧编辑 CAS 无法 insert/upsert 复活。
- SharedPreferences 兼容存储在同一 preferences 对象的锁下重新读取持久快照并比较；不依赖过期 flow。生产仍为 SQLite。该兼容锁不是跨进程分布式锁。
- `AssistantEditSession` 属于一个页面挂载实例。页面 Main scope 顺序接纳操作，Mutex 跨 IO 串行化 memory/skills toggle、头像 profile 更新和最终保存；后续操作只用自己的上次成功 CAS 回执作为 expected。外部改动一律冲突，不盲目重读后覆盖、不自动重试/rebase。
- `AssistantMemoryDraft` 保存开始读取时的编辑计数；初读晚到可以补充 saved/revision 基线，但只在没有用户输入时初始化正文。用户已输入或已主动清空的草稿都不会被覆盖。初读失败也不清空已输入文本。
- 最终保存先完整 profile CAS，成功后才对确实改变的记忆做原 revision CAS；发现旧 profile 时不会先写文件。失败保留所有草稿与页面；保存期间冻结编辑，避免成功导航丢掉提交后新输入。
- profile 与文件不是跨资源事务：profile 已成功而文件修订冲突时会保留已提交 profile，报错并保留正文草稿；不对外部更新做危险回滚。需要用户重新打开/合并。没有承诺“两者全成或全不成”。
- 页面离开/更换 assistant/store/assets 时撤销 session；旧回调及排队未接纳写拒绝，不触发新页面状态/导航。已经进入底层 CAS 的操作可能完成，视为撤权前已接纳的提交；迟到回执不再更新 UI。close 不等待磁盘 I/O，不冒称取消了已发生提交。
- 相册返回校验原页面 session；头像写入使用新的不可变文件名后再 CAS profile，避免旧页失败时覆盖新 profile 已引用的文件。未发布文件可能成为孤立文件，未在本任务扩展后台清理器。

## 测试源码与待验证

新增/扩展源码：

- `assistants/AssistantMemoryReadPortTest`：A/B 隔离、核心截断标记/UTF-8 上限、尾部按需查找、旧 revision、关闭/删除、捕获期间并发 profile、字面检索/分页边界。
- `assistants/AssistantEditSessionTest`：晚初读与主动清空、连续 memory/skills toggle、最终保存沿用回执、外部完整 profile 冲突且零记忆写、旧页排队/回调拒绝、新页不污染、删除不复活、正常手动文本不做关键词拦截。
- `assistants/AssistantStoreTest`：两个兼容存储实例持久快照 CAS、旧 profile/删除拒绝。
- `integration/tools/ScopedAssistantMemoryToolsTest`：仅只读工具、无模型 assistant/revision 选择参数、关闭 prepare 后零读取、固定冲突码、JSON 转义后上限及尾部检索。
- `androidTest/.../assistants/AssistantProfileCasDatabaseTest`：真实 SQLite 两 store/连接并发 CAS 恰好一个赢家、完整 profile 比较、删除后不复活、重开后持久结果、原表列数不变。

**全部新增测试未运行。** 父最终批次应验证完整单测、Android 编译/Lint、自动仪器发现包含新增类、API26/37.2；补真实助手页导航/输入与生产 run/core/tool 的组合验收。父完成实际接线后才可更新总清单 R11/P01 状态，本次未将整项标完成。
