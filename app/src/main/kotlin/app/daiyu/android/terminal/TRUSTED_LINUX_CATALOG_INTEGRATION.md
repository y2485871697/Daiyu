# R09 — 已安装 Linux 的最小可信 catalog 叶模块

## 范围与信任来源

`TrustedLinuxCatalog.kt` 只保存**明确批准的已安装配置**，不是下载器、安装器、发行版发现器或执行器。没有网络地址、设备目录扫描、解压、二进制执行、自动导入 selection，也不把 `content://` URI 变成可用的 `File`。此变更不修改 Controller、Main、TerminalEnvironmentHost、TerminalUiOwner 或 LinuxEnvironmentStore，不自动接入 UI。

真正的字段依据是 `LinuxInstallation`；文件验证依据是现有 `LinuxEnvironmentStore.select()` 的 validator：rootfs 可读目录、受约束的 `bin/sh`、proot 可执行文件及**调用者提供的已知 SHA-256**、loader 可读文件及其已知 SHA-256、cwd、共享挂载、文件身份和元数据上限。不复制一套摘要/启动验证器。校验不是执行，不宣称这些文件是安全发行版、不宣称 Android ABI/SELinux 一定允许执行，不宣称 guest root 是宿主 root，也不宣称工具已验证。

**摘要来源仍是父层责任。** 调用者必须通过自己的可信供应/审核流程取得已安装文件及已知摘要；不得把扫描到的任意文件重新计算摘要就描述成“来源可信”。LinuxInstallation 没有 rootfs 全树摘要，本模块不虚构一个，也不证明整个 rootfs 的供应链真实性。应用私有数据目录和同 UID 合作写者是信任边界；这不是防御同 UID 恶意改写配置的签名系统。

## API 与批准步骤

1. 创建一个固定的应用私有 catalog，例如 `TrustedLinuxCatalog(File(context.filesDir, "trusted-linux-catalog"))`。构造只登记进程内锁，不读写磁盘。根目录必须是绝对、无 `.`/`..` 的路径，目录祖先及元数据目标不允许符号链接；不要传 SAF 路径、外部共享目录或用户 selection 目录。安装文件本身仍按现有 validator 的规则处理（例如 rootfs 内受约束的 shell 链接）。
2. 父层准备完整 `LinuxInstallation`，其中共享目录**默认 `emptyMap()`**。仅使用用户/管理员明确传入并批准的挂载项，绝不能为“方便”扫描设备或补入 Downloads、SD 卡、整个应用数据目录。
3. 在展示确认界面前调用 `catalog.freezeForApproval(configuration)`。这是无磁盘 I/O 的深拷贝（包括不可变 mounts），会生成全新 UUID generation，输入 configuration 的旧 generation 不复用。展示 `approval.installation` 的完整 distribution/environmentId/**新 generation**/rootfs/proot/loader/两个已知摘要/cwd/全部 host→guest mounts；不要展示原输入对象后在批准时重新读取它。
4. 只有用户或可信父层明确批准上述冻结对象后，调用 `catalog.approve(approval)`。这是 suspend API，在 `Dispatchers.IO` 上串行执行读取、验证、原子发布。缺少原 catalog 是首次使用；已有 catalog 损坏不是“空 catalog”，必须显式报错，不能删除文件后重试来偷偷丢弃批准历史。未校验成功或写入失败不返回新授权列表。
5. `catalog.load()` 同样是 suspend/IO：缺失返回空列表；格式损坏、重复 reference、未知字段、非法路径、活动项文件缺失/摘要不符均抛异常，不回退到 selected-linux.json，不返回半份列表。返回值及 mounts 不可变。
6. `catalog.remove(LinuxGenerationRef(...))` 撤销该 generation，返回剩余活动项。未知 ref 拒绝；已撤销项重复撤销幂等。可移除文件已损坏的项；其他活动项仍须通过验证。不会删除 rootfs/helper/loader/共享目录，也不会改用户 selection。

相同冻结批准对象重试且已有完整相同的活动记录是幂等校验、不重写 catalog，但成功返回前仍必须同步 catalog 目录；重复撤销也遵循此规则，即使撤销后已无活动项。目录同步失败继续抛错，不能仅凭读取到目标记录就确认持久化成功。另一次 freeze（即使配置字段相同）也是新 generation。任何相同 ref 不同字段都拒绝，已撤销 generation 不可重新激活。旧批准配置永久保留，仅 active 标志能被撤销；变更 cwd/mount/path/digest/label 必须新批准、新 generation，不能修改旧记录。上限 256 条**含墓碑**、1 MiB；达到上限报错，不自动压缩历史或复用身份。

安装协调器使用专属 `approveInstallation(approval, admit)`：切换到 IO、实际取得本 catalog 根 gate 之后，首次 catalog IO 之前恰好调用一次 admit。该回调与取消在协调器短锁上原子决定，文件 IO 不在协调器锁内；接受之后不得取消拥有持久化回执的 coroutine。普通 `approve` 也支持相同的可选 admit 围栏，但仍拒绝已撤销代次。专属安装重试遇到完全相同的既有墓碑时只确认持久化并返回剩余活动列表，绝不将 active 恢复为 true。

只读安装描述恢复之后，可用 `registrationRecorded` 严格匹配既有配置（活动记录或墓碑均可），同步 catalog 目录成功才确认其持久化。协调器随后仅退役安装请求，不新增授权、不删除安装文件、不清理 UNKNOWN。描述、文件内容或未同步的可见记录本身不是成功回执。

## catalog 与 selection 严格分离

权威文件只有 `<catalog>/trusted-linux-catalog.json`。自身格式版本 1，包含完整 installation 和 active 标志。严格检查 canonical 序列化、重复 ref、未知字段和类型，因此它是本模块拥有的内部文件，不支持手工编辑/通用 JSON 导入。某条记录的授权只能由明确批准流程建立。

用户运行选择仍由父 owner 原有 `user-terminal/linux-selection/selected-linux.json` 管理。残留用户 selection，甚至 catalog 根旁伪造的 `selected-linux.json`，都不能制造授权。`load` 不扫描任何这样的文件。

**复用现有私有 validator 的折衷：** 不改 LinuxEnvironmentStore 时无法直接调用其 private validate。因此校验使用固定、隔离的 `<catalog>/validation-only/` 内的 `LinuxEnvironmentStore.select()`，只写一个可丢弃的 validation scratch selection（包括在 load 时）。这个 scratch 不是用户 selection，不作为读取/批准来源，也绝不能传给 terminal owner。丢失或残留它不改变 catalog 权威。校验失败可能更新先前项的 scratch，但不会发布新 catalog。每个 catalog 根复用一个 validator，避免为每次验证注册新的 Store 根。不要创建无限个 catalog 根；现有 Store 与此模块进程内锁表都有限额。

## 冻结到下次应用进程启动生效

本版本采用**下次应用进程启动生效**策略，不动态替换应用级 terminal owner：

```kotlin
// 父层应用启动初始化；不要在主线程阻塞 runBlocking。
val trusted = catalog.load() // suspend，内部 IO；失败应展示阻断/错误，不选 stale selection
val terminalOwner = TerminalUiOwner.android(context, trusted)
// 父层随后只发布这一个 app-scoped owner，并按既有生命周期关闭它。
```

加载可在初始化协程中完成，列表准备好后再进入应用级 owner 的原有创建/发布边界。若创建 API 必须在主线程使用，由父层切回主线程；不在本叶模块创建 owner。load 失败时可进入明确的“无 Linux 授权”启动状态，但必须让错误可见，不将损坏当作成功的空 catalog，不擅自覆盖文件。

当前进程运行时 approve/remove 返回的是**下一次进程启动的候选列表**。可以显示“已保存，重启应用生效”，不能将其作为另建 owner、重建 Activity owner 或绕过 UNKNOWN/in-use lease 的理由。已存活 owner 的冻结列表保持不变；本版本撤销不是立即终止运行中的会话。需要即时撤销时必须由父层另行设计完整关闭、UNKNOWN 清理完成、lease 释放及唯一 owner 切换协议；本模块没有该能力。运行 selection 若指向已撤销 generation，下次启动仍由既有 `TerminalTrustedLinuxSelection` 拒绝，不清除/恢复到另一个发行版。

## 原子性与失败

每个根的公开操作在 worker 上共享进程内锁。catalog 发布复用 `TerminalDurability.ensureDirectory/replace`：同目录临时文件、文件 fsync、原子 rename、目录 fsync，不降级为复制。校验失败、结构损坏、rename 前写入失败保持旧 catalog 不变。所有新快照仍携带旧配置和撤销记录；不会用同 generation 的新字段覆盖旧批准。

与现有 durability primitive 一致，**rename 成功后目录 fsync 报错属于提交结果不确定**：API 会抛错，但完整新快照可能已经可见。不能承诺所有异常都代表“未提交”；可以重新 load 对账，但 **load 不是持久性确认**。批准须用同一冻结批准对象、撤销须用同一 reference 重试；无变更路径也会重新 fsync catalog 目录，只有同步成功才返回，仍失败则继续抛错。不要在异常后盲目回滚或生成另一 generation 重试。目录同步通过 catalog 的 internal 构造参数窄注入，正常构造仍使用 `TerminalDurability.syncDirectory`，发布复用其现有回调；不修改公共 durability 实现、不增加内存提交状态或改变代次/墓碑规则。本模块不声称跨文件事务或跨进程写者安全。它保护元数据，不锁住 rootfs 全树；批准后外部文件仍可变，启动时原有 store/lease/trust gate 继续验证，不能省略。

## 测试证据与未运行项

新增 `app/src/test/kotlin/app/daiyu/android/terminal/TrustedLinuxCatalogTest.kt`，使用临时目录和从不执行的文本 fixture，覆盖：明确批准前零授权/零发布、残留 selection 与 scratch 不自授权、冻结 mounts/不可变返回值、损坏/未知字段/重复 ref、已知摘要不符、配置变更新 generation、相同 ref 不可偷换/撤销不可复活、路径/URI/挂载注入、rename 前写入失败保持旧批准并重试、catalog 符号链接拒绝。现有 LinuxEnvironmentStoreTest 提供 validator、lease、文件替换与绑定等额外契约证据。

新增故障注入回归 `testSameApprovalRetryMustSyncDirectoryAfterPostRenameFailure` 与 `testLastActiveRemovalRetryMustSyncDirectoryAfterPostRenameFailure`：只在目标单条活动记录/墓碑已经通过真实 rename 可见、临时文件已消失后注入目录 fsync 异常，首次调用和同一 catalog 对象的第一次幂等重试均必须抛出该异常；再一次同对象重试必须调用真实目录 fsync 并成功后才返回。断言覆盖同步尝试/成功次数、快照字节不变、generation 不变、最后一个活动项撤销后仍保留墓碑且不可复活，并明确读取可见快照不能替代重试确认。

新增 `testInstalledRequestCanRetireAgainstRecordedTombstoneButNeverReactivateIt` 与 `testRevokedAdmissionRunsOnceBeforeEvenReadingMalformedCatalog`，覆盖安装专属重试保持墓碑字节不变、相同 ref 不同配置仍拒绝，以及拒绝准入时首次 catalog read 也不发生。生产 gate 排队取消及注册重启退役的组合用例位于 Linux 安装专属测试类。

**实现代理未运行构建或测试**（仅可编辑工作树，无 shell）。如需验证，由主代理在 Linux 构建环境仅定向运行 `:app:testDebugUnitTest --tests app.daiyu.android.terminal.TrustedLinuxCatalogTest`；本次不构建 APK、不运行全量测试、不推送。未新增或执行真机 rootfs 安装/启动、下载、ABI、SELinux 或断电恢复验证；故障注入不等于真实断电恢复或真实发行版验证。
