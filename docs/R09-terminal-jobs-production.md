# R09 固定命令 session / async / daemon 的生产接缝

## 已核销与本次范围

依据 `docs/重构清单.md` 最新 R09 条目、`terminal/INTEGRATION.md`、
`docs/integration/terminal-catalog-host.md`、`terminal-tools-leaf.md`、
`docs/linux-settings-integration.md`、`linux-installer-integration.md` 和
`R09-linux-production-install-composition.md`，核对实际调用点后确认：

- PTY 有限批准的 Main → Controller → RunCatalogTools → AndroidPtyHost 路径已存在；
  Linux generation pin、父 dispatch/最终 receipt drain、可信目录、安装双确认也已存在。
- 不重复迁入这些旧候选，不重写 PTY，不把固定命令偷偷改为 PTY。
- 真正遗漏的是 `TerminalRegistry` 固定命令 session / async / daemon 的中央批准与生产入口，
  以及 Installer 之外的 Linux 平台拒绝。本次仅补这两处及对应结果/停止路径。

没有修改唯一清单、更新说明、其它 GUI/platform-reading 模块、PRoot 制品/哈希/许可、
Gradle/Manifest 或应用 minSdk；没有 Shell、Git、网络、下载、设备操作或测试执行。
`TerminalScreen` 只改一条已经过时的“未提供安装器”文案，指向现有 Linux 设置。

## 精确批准与同一中央调度

新增 `TerminalJobPlan` / `TerminalJobSession` 和 `ControllerTerminalJobPreparation`。
Main 的原终端精确命令编辑器明确区分 **PTY**、**async job**、**daemon job**，默认仍为 PTY。
固定命令使用 `TerminalRegistry` 的有界私有 stdin 脚本，末尾补换行；不承诺保留跨命令变量/cwd。
界面明确展示原 command、mode、argv、host/guest cwd、环境、共享映射、host UID、guest UID、
conversation/run，以及同一后端的 `id -u` 身份探针。确认不启动；发送是另一个明确动作。

- Controller 在既有 Preparation 独占槽冻结原草稿/助手/provider/运行身份；
  确认、转交和 DB 提交前均复核。没有从聊天自然语言或模型参数提取 command。
- `TaskAuthorization` 只授予原 plan 的一个完整 `terminal.job/start` 效果，原对象贯穿
  Session、真实 Runner owner 和唯一 RunCatalogTools/ToolLedger。
- 中央只广告无参数 `terminal_job_start({})` 与固定首个 16KiB 页
  `terminal_job_read({})`，不开放任意 command/mode/argv/PID/job_id/path/offset。
  prepare/admit/definitions/confirm 不创建 Registry、进程、目录或网络。
- 首次实际 start 才配置一个原 Registry，执行一次 `terminal_open` 和一次原
  `terminal_exec`。稳定 request IDs 不来自模型 call ID；plan 单次 claim。
- async/daemon 派发本身仍返回 UNKNOWN，不把已启动理解为命令完成。
  新 call ID、execute/start、中央 reservation/持久化 UNKNOWN 均不能重发。
  中央观察的是完整批次，不运行第二个 leaf dispatcher。
- 真实文本 ticket/SQLite 接受回执沿原路径结算：取消不抹去 Accepted，不提前清新草稿。
  含原视频历史的专用批准混用继续拒绝，不授予其它工具写权限。

Linux 只冻结原应用信任快照中**已经安装、已选定的精确 generation**，并持有原 lease。
不安装、不自动选择、不扩大 sharedMounts、不回退 Android。Android 固定应用身份，不引入 su。

## daemon、结果与停止

`TerminalRegistry` 保留既有真实 `setsid -w -- <shell>` 实现；无 `shell &` / nohup 伪装。
生产 resolver 仅查既有 `/system/bin/setsid` / `/system/xbin/setsid`；测试可注入 resolver，
不把它变为模型参数。缺失即拒绝；Linux/root daemon 均拒绝。

- daemon **仅 Android app 身份、仅同 app 进程**独立于 Registry/run close。
  Android 杀进程/后台限制、跨 app 重启存活、supervisor、服务自动恢复均未实现或宣称。
- Controller 有界保留原 Session（最多 64，不通过删除 UNKNOWN 释放容量），
  即使 run/catalog 已 drain，Main 的“本会话终端 jobs / 日志 / 停止”仍操作同一原对象。
- 主线程只选原 Session；日志读取和停止在 IO worker。结果直接来自原 Registry 的原 job，
  不重开 session、不发命令、不从历史 DB 恢复 live handle。
- 停止为额外明确确认，绑定原会话/visit/editor generation/Session；离页、侧栏、编辑器更换、
  STOP/销毁或替换 visit 同步撤销旧页，worker 准入前检查撤销标记，迟到结果不回填新页。
  已准入的停止不能因随后离页被假称回滚。
- stop 只用原 Java Process + `/proc` starttime 校验，不以裸 PID kill。
  结果明确 `owned_shell_only; descendants not claimed stopped`，不证明任意后代已经退出。
- live 结果区分 host_uid/host_root 与 guest_uid/environment_id；Linux guest UID0 不是 Android root。
- 暂无跨进程 job 管理 UI。旧 Registry 磁盘记录仍按原协议 UNKNOWN 可读，不能重构进程或执行授权。
  历史删除/账号切换后的专用 daemon 管理、operator 归档/配额回收不是本次新能力。

## 真实取消与 drain

Registry 新增 admission 计数及 `awaitOrdinaryTermination`。必须先 close、等待 active dispatch
归零，再等待原普通 Process 实际退出及 output pump 结束；晚到 start、无 handle 的 pending、
未结束输出线程均不是 Drained。daemon 不进入普通 run drain，因为其原 Session/Registry 在
Controller 保留；这不是宣称 daemon 已退出。

Session.close 只封准入并启动一次独立 stop worker，`Process.destroy` 不在 Catalog/Controller
控制锁下。父目录在自己的 creation/dispatch/最终 receipt/stop 都结束后才进行第九项资源观察（同时保留GUI观察）；
观察会 join 原 stop worker 并在剩余预算内等待实际普通资源。UNKNOWN 缓存，不自动重试或丢 owner。
Linux lease 只在真实 drain 后归还。失败 factory 没有交出句柄时明确保持 UNKNOWN，不猜测“无资源”。

补齐同一路径的身份探针清理：`BackendProbeCleanup` 保留 destroy 后尚未确认退出的原 probe
Process/reader；Registry 持有到实际 drain，避免 Linux 身份验证失败时过早释放 generation lease。
这不是 PTY 重写，也不是假定 destroy 返回等于已退出。

## 统一 Linux 平台 gate

`LinuxRuntimePlatform` 将原 Installer 的规则集中为 API34+、64位进程、aarch64/x86_64。
PackagedProotHelperProvider 复用它，所有既有精确 hash/长度/安装位置校验保留。

同一规则还用于：

1. 应用启动已有 catalog 的 live admission（不支持时保持空执行许可，但仍可显示/撤销旧记录）；
2. 手工配置的 freeze/approve 与安装 registration adapter（load/remove 不被平台 gate 阻挡）；
3. 应用 `openLinux` / chat freeze；
4. `AndroidPtyHost.openLinux` 和固定命令 `BackendSelection.select(linux)` 实际打开。

故手工配置或旧 catalog 行不能绕过 API26–33/32位拒绝。Android PTY 仍保持原 minSdk26，
没有下载安装或未经校验的替代 runtime；API26 Linux 需要未来真实重建与平台验证，不在此假称支持。

## 测试源码与父验证

**下列都是新增/调整测试源码，未运行、未编译，不是通过报告：**

- `TerminalJobSessionTest`（9 项）：真实 Runner-issued owner/中央 ledger，零启动准备、
  额外参数拒绝、精确 command/mode、UNKNOWN 新 ID 禁止重发、复制 token/owner 拒绝、
  daemon 原句柄观察/停止、factory UNKNOWN、父最终 receipt 屏障、晚到 factory、缓存未知 drain。
- `LinuxRuntimePlatformTest`（2 项）：API26–33 矩阵及 64位 machine 精确白名单。
- `TerminalRegistryTest` 新增 3 项：fake Process 下的 output-pump drain、晚到启动、
  setsid argv/daemon 不随 run 关闭及原 handle 显式 stop。没有执行 fake argv 指向的 shell。
- `ControllerTerminalJobSendTest`（3 项）：真实 Controller/SQLite/Runner 接受路径、
  零工具模型、不启动命令、原批准/回执/保留对象、旧 draft/Linux daemon 拒绝。
- `TerminalChatActivityTest` 新增 1 项真实挂载 async 编辑/确认/发送/jobs 入口回归；
  `AndroidPtyHostTest` 按共享平台 gate 修正预期的 pre-start 拒绝顺序。

父代理须统一执行类型检查/JVM/AndroidTest 编译、Lint、APK 与 API26/37.2 全量批次。
特别需要真实设备验证：系统 setsid 是否存在及 `-w --` 行为、Android 后台/应用进程生命周期、
PID/starttime 拒绝/复用、真正 input/output pump 取消、PRoot API34+ ABI/SELinux、
已有手工 catalog 的 API26 拒绝及撤销可用、同 Linux generation 退出前不可换选。
没有 native/实机/跨重启/断电证据；不据本增量将 R09 整项标完成。

## 历史 reader 的 descriptor 定向修复（随本次源码提交；未运行）

本增量只修已有 `TerminalJobHistoryReader` 的历史文件读取边界，不重做此前历史
Controller/Main 接线。任务提供基线 `9a090b0b` 和只读审查 `9d4944bf`；本代理不能
运行 Git，未独立核对 SHA，但独立读取源码确认了 pathname 前后检查无法排除临时
父目录 symlink、仅最终分量 NOFOLLOW 及 FIFO 打开可能阻塞的问题。

- 每次 scan/page 从宿主可信 canonical filesDir 打开目录 FD，再通过
  `/proc/self/fd/<held-parent>/terminal-jobs` 固定 root；保留 filesDir/root 两个 FD
  到整次操作结束。扫描使用固定 root FD，metadata 与日志都相对该 FD 打开。
- 沿用 `TerminalPaths.openDescriptor` 的 Android Os/PFD 与 API26 兼容复制方式：
  每次打开均带 `O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK`，目录在同一 fd 上以 `fstat/S_ISDIR` 准入；
  打开后用 `fstat` 只接纳目录/regular file，读取、seek、大小上限及前后 size 校验都用
  同一叶子 FD。拒绝 FIFO/其它特殊文件，不以易变 pathname 的检查结果授权读取。
- root 在固定前变成 symlink 时拒绝；固定后 pathname 临时换成 symlink 再恢复时，
  仍只读取已固定的原 root/叶子，不读取 symlink 目标。成功/缺失/异常/超限均关闭已获 FD。
  这不是持久化跨重启 root 身份，也不授予任何执行或句柄恢复能力。
- 原 Registry UUID `.json/.log` 格式、精确 owner、每页原 Record 复核、UNKNOWN 只读、
  256 扫描/64 记录、16KiB metadata/256KiB log/64KiB 页上限及坏条隔离保持不变。
  不创建目录/Registry/journal，不运行 probe/命令，不恢复 PID/控制授权。

验证源码分工：`TerminalJobHistoryReaderTest` 的 8 项 JVM 测试显式注入普通 JVM
文件夹具，只验证 JSON/owner/配额/byte 分页/缺失与空日志，不把它们称为 Android FD
安全证据；新的 `TerminalJobHistoryReaderDeviceTest` 有 8 项实际 Android Os/PFD 测试，
使用一次性确定性 hooks 验证 filesDir 替换、root 固定前拒绝、root 临时 symlink 并在
叶子打开后恢复、叶子打开后替换仍读原 FD、metadata/log 变 FIFO 及时拒绝及异常 FD
清理。FIFO 用 `Os.mkfifo` 创建，无写端，Future 限时 3 秒；回归超时后以非阻塞读写 FD
解除可能阻塞的 open，清理等待另限 2 秒，无无界竞态重试或 shell。

本次仅 reader、其 JVM/新设备测试及本文件/唯一清单共 5 路径；不修改 Controller/Main。
只完成 workspace 源码定向检查，**未编译、未运行任何 JVM/设备测试、Lint 或构建**，
未创建分支、推送、触发 CI 或操作设备。父代理负责独立审查、真实 API26/37.2 Os/PFD
运行与统一集成；不能凭新增测试源码或先前 9 文件产物记录声明本修复已通过运行验证。
