# Linux 本地配置设置页与应用供给接线契约

## 本次叶模块范围

新增文件：

- `terminal/LinuxSettingsApplication.kt`：应用级一次性装载、固定 host 配置快照、同一个 `TerminalUiOwner`、单调撤销门禁。
- `terminal/LinuxSettingsController.kt`：单次 page owner、worker 串行读写、完整配置冻结/批准/列出/撤销。
- `ui/screens/settings/LinuxSettingsScreen.kt`：完整路径、两个**调用者提供的期望 SHA-256**、guest cwd、显式 host→guest 共享映射的表单与冻结预览。
- 三个 `LinuxSettings*Test.kt`：控制器、并发 admission 和真实 catalog/Store 的惰性本地文件契约。

没有修改 MainActivity、ChatController/ChatApplication、SettingsCatalog、Feature*；这些父接线尚需集成。本页不是安装器，不含网络地址、目录扫描、下载、解压、源选择或安装器占位。不要把登记本地文件称为已接入官方/可信发行源。其文件验证完全复用 `TrustedLinuxCatalog` → `LinuxEnvironmentStore`，不另写摘要/路径/租约验证器。

## 1. 应用初始化：只发布一次 owner

父层在原有应用初始化协程中（**不可主线程 runBlocking**）执行：

```kotlin
val linux = LinuxSettingsApplication.android(applicationContext)
// 等 catalog.load() 成功后，原子发布这一份应用级供给：
val terminalOwner = linux.terminalOwner
val linuxSettings = linux.settings
```

默认 catalog 是固定的应用私有 `filesDir/trusted-linux-catalog`；不要从页面输入、SAF、下载目录或 selection 路径构造它。构造参数允许可信父层提供同样受控的单一 catalog 对象，不能成为 UI 的目录选择器。

`android()` 是 suspend 工厂，首先调用原 catalog 的 IO load；装载失败向父层抛错，**不是成功的空列表**。父层须显示初始化/目录错误并阻断 Linux 供给，不可静默 `getOrDefault(emptyList())`。在数据就绪前保持明确的未发布初始化状态；不要先发布空快照 owner、装载完成后换 owner。若产品要求错误后仍开放 Android 终端及目录修复，需要父层设计显式失败供给状态并审查；本工厂未虚构修复权限或忽略错误。

此对象必须应用级保留，不按 Activity、Compose、路由或审批结果重建。新增批准不改变 host 的安装元数据快照，也不扩张当前门禁。真正结束应用所有者生命周期时才调用 `linux.close()`；设置页只关闭自己的 page owner。

## 2. Linux 新启动必须使用原子 admission 包装

父层终端 UI 的回调应改成：

```kotlin
onOpenLinux = { reference -> linux.openLinux(reference, currentConversationId) }
```

**不能继续直接 `linux.terminalOwner.openLinux(...)`。** `LinuxSettingsApplication.openLinux()` 用与撤销同一个锁包围门禁检查和整个现有 owner admission；这样撤销返回后，不可能再由一个之前仅检查过谓词的线程完成新 admission。`TerminalUiOwner` 构造器的实时 `trustedLinux` 谓词仍作为第二道拒绝检查，host 的固定元数据验证/selection/Store 租约也完整保留。

现有 owner 的单独谓词接口只能保护后续检查，无法单靠回调把“检查→host.open”与并发撤销原子化。本叶模块用上述父级调用包装补足，**不需要修改 TerminalUiOwner**。如果父层必须保留直接 owner.openLinux 的调用路径，则需要新增 owner 级原子 admission 契约（让授权与 host.open 共用撤销锁/事务）；在落实之前不能声称具备严格并发撤销。不可另建/换掉 host、关闭后立即重建、清除 selection 或绕过 UNKNOWN 来规避该缺口。

- 批准：只持久化下一次应用**进程启动**的 catalog。Activity 重建不是重启生效。
- 撤销：用户操作被当前 page 接受时同步从进程门禁删除，随后才排队持久化；即使写失败/结果不确定，当前进程也不恢复该能力。
- 已经 admission 的运行可能仍在 OPENING/RUNNING/UNKNOWN/CLOSING；它们保留同一 owner、host、资源租约和原有清理责任。不自动 kill、不伪造退出、不放弃未知资源。
- host 的 choices/selection 可能仍含已撤销项；它们不是权限，调用上述入口仍拒绝。不得以 choices、目录行、模型参数或持久化 selection 绕过门禁。
- 持久化失败时不能保证下次进程不再装载旧批准，必须显示错误并重试同一个 ref。离页不会把内存拒绝还原。

## 3. 父路由接线：每次访问一个 page owner

给设置目录加真实 Linux 路由，并在父层进入路由时（不在 Composable 函数体中）创建：

```kotlin
val visit = linux.settings.enterPage()
// 在本次路由记录/保留字段中存 visit；重组复用它。
LinuxSettingsScreen(
    controller = linux.settings,
    page = visit,
    onBack = { /* 父层仍须检查当前 route/visit 身份再导航 */ },
)
```

所有退出途径（返回、切换主页面、打开别的设置、销毁旧路由）都在父层**提交导航时同步 `visit.close()`**，不等转场动画结束。页内 BackHandler/返回按钮及 DisposableEffect 是补充防线；`revoke()` 只在该 handle 仍为当前页时返回 true，旧 back 回调不会再次导航。`enterPage()` 会撤销此前当前页，不能并存两个活动 page owner。不要为旧页面重新激活 handle。

进入页面自动 worker load，无启动副作用；离开只撤销页面能力，不关闭应用 terminal owner、controller 或 worker。旧按钮和确认回调永远绑定旧 handle，不能在新页复活。目录行来自新页自己的读取；但未确认操作由应用级 controller 独立保留：撤销保留原 ref，批准保留原 `FrozenLinuxApproval` 对象。新页重新展示原对象的完整冻结预览后，必须通过新 handle 再次显式确认，不自动重放写入。

## 4. 完整冻结、批准与显示

表单不自动探测路径，不自动算摘要，不默认共享任何目录。host 路径是完整绝对文件路径，cwd 与 mount guest 路径也显式提供。共享映射以独立的 host/guest 行编辑；重复 host 会在转 map 前拒绝，不静默吞掉一行。其余规则交给现有 catalog/Store。

“冻结完整配置并预览”在 worker 通过 `catalog.freezeForApproval()` 分配新 generation、冻结 map；它不写 catalog、不验证文件存在性、不授权。UI 明确展示冻结对象的标签、环境 ID、**新代次**、实际 rootfs/proot/loader 路径、两个完整期望摘要、guest cwd 和所有 mounts，然后才显示批准按钮。批准只接受该 page 当前所示的**同一冻结对象身份**，不重新读表单；首次提交前，编辑任意字段废弃预览。提交后若尚无持久化成功回执，禁止丢弃该 pending、编辑或新建代次覆盖它；重入时仍完整显示原对象，并由新页明确确认重试。也不把 catalog 行转换为可编辑/直接运行权限。

批准调用现有 `catalog.approve()`，真实文件路径、可读性、helper 摘要、受约束 shell 和 mounts 检查仍由原 validator 执行。rootfs **没有全树摘要或来源证明**；两个 helper 摘要匹配也不证明 ABI、SELinux、执行安全或供应链真实性。页面不提供“已验证官方发行版”标识。

## 5. worker、错误与持久化不确定性

控制器的默认 scope 是 `SupervisorJob + Dispatchers.IO`，所有 load/freeze/approve/remove 在该 worker 上，通过一个 Mutex 串行；UI 只更新输入、请求状态和同步门禁。catalog 自己仍使用其 IO/进程内根锁及原持久化协议。每页同时只受理一个操作。

排队但未开始的旧页操作在执行前检查 page 身份并跳过。已开始的 IO 可能在离页后完成（包括真实持久化），不能谎称撤销 UI 回调等于回滚磁盘事务；完成结果不得回填旧页或把目录行直接注入新页。controller 在锁内登记 pending，随后在锁外运行 catalog IO；新页会在串行操作后自行重读。即使写入被旧页围栏跳过，pending 和同步内存拒绝仍保留，由新页显式重试。仅同一 pending 操作真实返回成功时清除它，包括离页后才返回的成功回执；普通读取成功不能清除 pending。

catalog 的 rename 后目录 fsync 失败可能已经让新记录可见：

- 批准失败由应用级 controller 保留**原 `FrozenLinuxApproval` 对象**，跨 refresh/离页/重入；新页完整展示标签、ID、原代次、全部路径、两个期望摘要、cwd 和 mounts，显式确认后才用原对象重试，不复活旧回调。
- 撤销失败保留**原 ref** 及独立的“重试确认同一代次的撤销”入口，不依赖当前活动列表。即使刷新只读到 tombstone、列表变空，错误和入口也不消失，同步内存门禁不回滚。
- approve/remove/load 失败会把列表标为未确认；存在 pending 时，refresh 即使成功也仍显示未确认和操作错误，不把“可见”当“持久化成功”。
- 同时只保留一个未确认变更。在其成功前可刷新、离页和重试，但不能用另一批准/撤销或编辑覆盖它；当前 catalog 错误不区分“确定未提交”和“可能已提交”，因此统一保守保留，而不是猜测成功或允许改代次。
- 该 pending 是进程内 controller 状态，不是额外持久化日志；进程终止后的状态仍按原 catalog 装载协议处理，不声称跨进程或断电恢复未知操作。

已知启动快照作为**未确认的显示行**保留给新页，后续文件损坏导致 load 失败时仍可撤销已知 ref；不把这些行当作当前授权。原 catalog 不提供“只读未经文件验证的活动记录”接口，因此首次启动就损坏且没有已知快照时，本模块不能列出未知 ref 或伪造修复。若要实现这类恢复，父层需在原 catalog 中设计严格解析但不授予执行权的诊断/撤销接口，并区分结构损坏与单项文件失效；不得在设置页自己解析 JSON、导入 selection 或绕过原校验。多个坏活动项也受原 `remove()` 对其余活动项验证的限制，错误必须可见。

## 6. 验证来源与尚未运行项

依据现有 `TrustedLinuxCatalog.kt` 的 freeze/load/approve/remove、`TerminalUiOwner.kt` 的实时谓词构造器与固定 android 工厂、`TerminalEnvironmentHost.kt` 的 selection/worker/lease、`LinuxEnvironmentStore.kt` 的私有 validator。未迁入旧源码，也未重写它们。

新增测试：

- `LinuxSettingsControllerTest`：无构造 IO、完整预览/不批准、输入拷贝、同一冻结对象、重复 host、失败同对象重试、不热授予、同步撤销/失败不复活、旧回调/旧读/旧写不能回填、排队离页围栏、已知启动行损坏后的撤销；补充排队撤销离页后 pending 不丢失、进行中批准在离页后失败仍需新页确认原对象。
- `LinuxSettingsAdmissionTest`：固定快照复制、单调拒绝、锁包围整个 admission 的并发撤销顺序（本次未改）。
- `LinuxSettingsLocalCatalogTest`：真实 catalog/Store 惰性文本 fixture；验证批准与撤销、保留文件、不创建运行 selection、错误期望摘要不会被自动重算替换；补充真实 `directorySync` 在 rename 后注入失败→refresh→离页重入→原对象/原 ref 重试，验证活动列表已有/已空都不能替代 fsync 回执，重试字节不变且再次执行 fsync。另以阻塞的真实 directorySync 验证离页/重入不等待 IO 锁，迟到失败仍保留 pending；立即 deny 且失败/成功都不回退。

**实现代理没有运行任何构建或测试。** 由父代理在受控 Linux 环境按需定向运行上述三个测试类及已有 TrustedLinuxCatalog/TerminalUiOwner 测试，再检查 Compose 编译和父路由/Activity 生命周期。没有 UI 仪器测试、真实 rootfs 启动、网络安装、ABI/SELinux 或断电恢复证据。
