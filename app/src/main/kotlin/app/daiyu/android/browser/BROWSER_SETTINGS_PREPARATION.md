# R10 / P34：浏览器偏好与纯授权准备（未接运行入口）

## 范围与状态

仅新增 `BrowserSettingsStore.kt`、`BrowserRunPreparation.kt`、独立 `BrowserSettingsScreen.kt` 及专属测试/本说明。未修改 Controller、MainActivity、RunCatalogTools、RunBrowserTools、registry、host、WebView 或底层适配器。页面路由由主代理接入 Agent 任务偏好；本提交不声称已有入口或工具授权已联通。

本轮修正仅涉及 `BrowserSettingsStore.kt`、`ui/screens/settings/BrowserSettingsScreen.kt`、对应 Store/Screen 两个测试文件及本说明；未修改 Main、DB、清单或配置。父宿主接线不包含在本候选中。

未运行构建、测试、设备操作、安装或网络请求；以下是代码级检查与待主代理运行的测试。

## 偏好

- 专属 SharedPreferences `browser-settings`，单个版本化记录 `browser-settings-v1`。
- 默认 `DISABLED`。缺失、错误类型、未知版本/值、历史 `DENY`/`v1:DENY` 均按禁用读取；不猜测旧 key，不自动迁移。
- `READ_ONLY`、`FULL` 只表示能力上限。保存不生成 TaskAuthorization，不保存动作、URL、运行身份或确认结果。
- 显式 commit + read-back；失败在共享此 SharedPreferences 对象的 store 间封锁读取，直到再次明确成功保存。该封锁是当前进程内的，不声称解决 SharedPreferences 跨进程事务。
- 历史同意固定显示 `DENY`，Cookie 名称授权集合固定为空。FULL 不改变这两个策略。
- UI 的“选择”与“保存并回读”分离，显示最后确认回读的值；错误不会被显示成保存成功。页面不创建浏览器，不测试网站，不申请运行授权。

## 页面保存所有权与排队 IO（本轮修正）

- `internal BrowserSettingsSaveOwner` 提供原子 `isActive` 和单向、幂等 `revoke()`。撤销不取得 store 共享锁，不等待 IO/commit；不能重新激活旧 token。
- 保留 `save(settings)` 的旧行为以兼容非页面调用；页面必须用新增的 `save(settings, owner)`。新 overload 在所有 store 共享锁**内部**、设置失败 fence 或调用 `edit()` **之前**执行 `tryAdmit()`；`compareAndSet(true, true)` 是相对 `revoke()` 的准入线性化点，不是锁外检查后再写。
- 若撤销在线性化点前发生，排队保存被拒绝，不 edit、不 commit，也不建立或清除已有失败 fence。若准入已成功，之后撤销允许该次 commit/read-back 完成（也允许成功后清除其 fence）；这里不是回滚或强制终止已准入的写入。
- `BrowserSettingsScreen` 保持既有参数兼容，末尾新增 `saveOwner: BrowserSettingsSaveOwner? = null`。不传时每次 mount 创建新 token；传入时父级也必须每页新建，禁止复用已撤销 token。页面 dispose、顶栏返回和系统返回在离页回调前撤销；页面中的 Compose 状态与 `ownerCurrent` 只在 main 读取，IO 仅通过原子 token 准入。
- **宿主提前失效契约**：若 retained page、身份或宿主销毁使页面在 Compose dispose 前已失效，宿主必须保留并传入同一 `saveOwner`，在失效点同步 `revoke()`，然后再更改页面身份、清空持有字段或等待 UI dispose。`ownerCurrent` 只是 main 上的展示/交互检查（例如引用相等）；它不会监听任意线程变化，也不能阻止一个已经排入 IO 的保存。单独改变 lambda 的返回值不是上述同步撤销的替代品。本候选不含父级接线，不声称该接线已完成。
- 旧页的撤销只影响它的 token，新页使用新 token；失败提示与回读结果仍由 main 上的当前页面检查过滤。此机制不改变偏好只是能力上限、不是任务授权的边界。

## 纯准备接口

`BrowserRunPreparation.prepare(...)` 接收宿主提供的不可变身份、已回读设置、BrowserGrant、动作子集和逐项原始 WRITE 参数。身份包含 conversation、run、assistant profile 与 assistant message，避免活动助手/回复的隐式回退。此阶段只接受 MAIN owner，owner.sessionId 必须等于 runId；已有任意 sessionId 的调用方不能不经审查直接接入。

- `grant.access` 不得超过偏好上限；DISABLED 不能准备。
- READ_ONLY 的 new_tab / close_tab 在既有 catalog 中仍是 WRITE，必须给出精确确认参数。没有导航、建标签或补齐默认目标的隐式步骤。
- 参数验证和 effect 完全复用 `RunBrowserTools.writeEffect(grant, raw)`；不复刻 canonical JSON / effect 编码。不造临时 session、registry 或 WebView。
- READ/WRITE 预览均无需打开 session；已有 session 传入时必须匹配冻结 grant，且后续必须绑定同一个实例。
- 选择的每一个写动作必须有参数；未选择动作、重复 effect、缺显式写 tab_id、额外参数及生产 schema 拒绝的值都会拒绝。
- Preview 防御性复制集合、保留显示过的原始字符串与生产 effect。只有同一 preview 实例能被显式 confirm；确认/取消均消费 preparation。
- 确认结果只在内存中持有，不能作为持久或模型可提供的 bearer proof。参数或身份变化应丢弃旧 preview，重新准备并明确确认。
- `taskAuthorization(owner, assistantId, session)` 校验四项身份与 grant，且在预览提供了已有 session 时要求原实例；只发出精确 effects。
- `checkInvocation(...)` 是纯 admission gate，校验相同绑定、动作子集、工具种类与精确 effect。生产 catalog 仍负责完整 READ schema 校验。它不执行调用。

## 必须保留的集成边界（主代理负责）

1. 冻结真实 conversation/run/profile/reply ID；不要把网页内容、模型参数或活动助手切换当成确认。
2. 偏好保存不是确认。用户确认完整 preview 后才生成任务授权；设置变更不能扩大已冻结运行。更严格的新设置是否立即撤销活动运行由宿主处理，不能仅替换 preview 内字段。
3. 当前 TaskAuthorization 不携带助手或动作子集。**仅取出 taskAuthorization 并交给原 catalog 不足以约束 READ 动作子集。** 必须在 catalog prepare 与 CONFIRMED replay admission 前都调用 `checkInvocation`，或采用等价且经过审查的 gate；同时继续使用既有 dispatcher、ledger 与活跃 run owner 校验。本文件不自称已完成该执行集成。
4. 运行中止、助手切换、过期页面和权限撤销时，宿主废弃内存确认对象并负责既有 session 生命周期/排空。`confirm` 是单次的，但已返回的确认对象不会自行感知 UI/Controller 状态；它不是持久重放授权。
5. Android host 的 `historyApproval` 保持 `OpenMinisHistoryApproval.DENY`；Cookie `authorizeName` 保持默认拒绝。不得从 FULL 推导 true。
6. 暂不开放 `get_cookies`、`set_cookies`、`fetch`、offload 或共享路径，也不接私有文件映射；不能因底层有实现就声称本阶段可用。网页 JS/文本不构成这些能力的额外授权。
7. 真实后端支持、系统权限、接管、generation 校验与资源清理仍是执行端的职责。底层每 owner 最多 3 个标签（0–2）；不承诺无限子标签或本阶段可用的子浏览器。unknown 不自动重试。

## 无 session 的纯预览

`RunBrowserTools.writeEffect(grant, arguments)` 已接入；session overload 只委托纯 grant 入口。prepare 不创建 BrowserToolSession、registry 或 WebView。无论预览是否带既有 session，执行端仍保留 action-subset、assistant、owner 和 task 对象身份约束。

## 证据与待运行测试

已阅读并据以实现：

- `integration/tools/RunBrowserTools.kt`：writeEffect/session 依赖、完整参数 schema、writes 显式 tab_id、READ_ONLY tab changes、owner/effect 编码。
- `browser/session/BrowserSessionRegistry.kt`：readOnlyActions、owner 类型、0–2 标签上限。
- `browser/host/BrowserToolSession.kt`：构造打开 registry，owner 资源隔离不等于 Cookie 隔离。
- `core-domain/.../runtime/RunExecutionOwner.kt` 与 `.../tools/ToolRegistry.kt`：实际三项消息身份及 TaskAuthorization 的字段边界。
- `components/openminis-browser/adapter/.../AndroidBrowserRunHost.kt`、`OpenMinisCompatibility.kt`：historyApproval 默认 DENY 与独立 Cookie 名称授权参数。

专属测试（只编写，未执行）：

- `BrowserSettingsStoreTest`：默认拒绝/历史 DENY/坏类型/未知版本、各上限 commit+回读、多实例失败封锁与恢复、read-back 不一致；新增确定性 monitor + latch 时序：保存确已阻塞在共享锁时撤销，撤销在释放锁前完成且最终零 edit/commit；已准入并停在 commit 前的保存撤销后仍可完成；拒绝保存不得清除既有 fence；旧 token 不可恢复且不影响新页 token。超时仅作测试失败上限，不以 sleep 猜测顺序。
- `BrowserRunPreparationTest`：真实 writeEffect、零生命周期/port 调用、防御复制、默认关闭、READ_ONLY tab WRITE、缺参数、Cookie/fetch/child 拒绝、精确参数差异、JSON 重排、身份/助手/原 session 绑定、动作子集、预览单次确认及取消。
- `BrowserSettingsScreenTest`：初始禁用、只选择不保存、保存回读、新 store/新组合回读、DENY/Cookie 限制展示、失效页面不可保存；新增点击后确认 IO 保存已阻塞在 store 锁，再执行默认 token 页面 dispose、父 token 提前同步撤销或返回（父尚未 dispose），释放锁后均要求零 edit/commit。父级用例断言 `ownerCurrent` 只在 main 执行。继承项目既有无浏览器的 UI fixture，不创建 WebView。

以上测试均**仅编写、未执行**；未运行 Gradle、单元测试或 instrumentation，也未验证设备上的表现。需主代理构建并运行专属测试后再报告运行结果。
