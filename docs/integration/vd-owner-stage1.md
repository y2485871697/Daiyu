# R08 第一阶段：VirtualDisplay owner 源码迁入

这是一阶段迁入，不是 R08 完成，也不是功能已经接入或真机验证的声明。迁入用户提供的 32 个 Java owner 实现和 19 个既有测试，保留同包访问关系；新增 input 执行边界回归和宿主契约回归。来源、限制和 Required Notice 见 [NOTICE](../third-party/virtual-display-owner/NOTICE.md)。

## 保持不变的边界

- Java 包是 `app.daiyu.android.vd.runtime`，但 `VirtualDisplayFactory.OWNER_PACKAGE` **仍为 `android`**：root 的系统 Context/display 归属并不等于 Java 包名或宿主 applicationId。
- `VirtualDisplayOwnerMain` 仍只接受 UID 0。UID 2000（shell）、普通应用 UID 不被放行；`--allow-uid` 是 IPC 对端的 UID，不是降低 owner 的 root 门槛。
- 使用原有独占 session handoff、任务身份/provenance 校验、只读 preflight、源显示空置与 release 验证逻辑。不引入旧应用框架、RootSu、预编译 dex、客户端、自动 root runner 或后台新服务。
- minSdk 保持 **26**。Anchor 使用其 WindowManager 的 defaultDisplay（绑定该 Activity 的显示），不再调用 API 30 才有的 Context.display。
- generation/receipt 协议与调用方恢复策略尚待独立设计；未在此次添加新的协议版本或 R08 完成标记。

## Anchor 宿主接线与剩余项

Owner 的 entrypoint/usage 现在一致为：

```text
app.daiyu.android.vd.runtime.VirtualDisplayOwnerMain
```

这是入口类名说明，不是已经部署/启动 root 进程的指令或产物。当前没有 host runner 启动它，也没有迁入预编译代码。

当前 Manifest 已在 `application` 内声明如下 Activity；这只补齐临时 cover 组件，不启动 root owner，也不代表 handoff 已可用：

```xml
<activity
    android:name="app.daiyu.android.vd.VirtualDisplayAnchorActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:allowTaskReparenting="false"
    android:taskAffinity="app.daiyu.android.vd.anchor"
    android:launchMode="standard"
    android:theme="@android:style/Theme.Material.NoActionBar" />
```

- 组件与 OwnerHandoff 的 `app.daiyu.android/app.daiyu.android.vd.VirtualDisplayAnchorActivity` 必须一致。
- exported 允许独立 UID 0 owner 的显式 `am start`；不添加 launcher、BROWSABLE 或任何隐式 intent-filter。
- affinity 独立于宿主普通任务；配合已有 `NEW_TASK | MULTIPLE_TASK` 创建临时独立 cover。不能改为宿主默认 affinity，不能使用 singleTask/singleInstance，不能设置 noHistory（后台移交时仍需验证并显式移除该 anchor）。
- cover 使用不透明深色主题；不得替换成透明 Activity。`excludeFromRecents` 使其不作为用户任务暴露；不要用 permission/launchMode 变化绕过实际 owner 组件校验。
- 原有 `am start` 的 `-d` 仍是实际 Intent data，**不是 action、category、extra 或仅用于日志的标记**：anchor 为 `daiyu-vd-anchor://handoff/<随机 UUID>`，Activity 检查同一 scheme 且拒绝主显示；普通 owner launch 为 `daiyu-vd://session/<随机 UUID>`，task baseIntent 必须回读出完全相同的 marker 与目标包，算法未改。
- Manifest 组件已接入；root 启动器、可信运行绑定、generation/逐操作持久收据及恢复查询仍未接入，不能宣称 handoff 可用，也不得自动重放结果未知的启动。
- `VirtualDisplayAnchorManifestTest` 检查安装后的组件与 task/主题属性；仅新增测试源码，未运行。

## input 确定缺陷修复

旧实现直接运行 input 子进程，非零或超时只抛 `INPUT_FAILED`，未设置 `mutationUncertain`。子进程可能已经注入部分按键、字符或滑动，失败不等于 NotExecuted。

现在所有参数、显示/几何校验仍在副作用前。进入已验证的 input 执行边界后，先置 `finishing=true` 和 `mutationUncertain=true`，再执行子进程。非零、超时、未观察到退出、抛异常及成功之后构造响应失败，均保留锁；只有成功构造完整 input 成功 body 后清除。锁存在时拒绝后续 input/launch，status 继续按原格式公布两个字段。

`INPUT_FAILED` 错误码没有被重新定义为“未执行”。调用方不能仅凭错误码、超时或没有收到成功回复自动重放。这里处理的是 owner 存活时的保守锁；跨进程丢失、回执确认和新 generation 的证据属于后续独立设计，不由本阶段补造。

`runInput` 现在是私有执行边界，回归从公开 `input()` 进入并完成原有校验，不再直接调用执行接缝或反射读写锁。仅 Owner 包内的 `InputAccess` 和构造器允许测试提供合成显示身份/几何/只读任务清单、命令结果及 JSON body 创建器；生产 `create()` 仍固定使用原 framework/shell 实现，不暴露新的公共工厂、IPC 字段或授权选项。包内 body 创建器允许分别模拟成功 command 后创建 JSON 失败、写入部分字段后失败；这两种情形都保留锁。测试不启动 subprocess，也不创建真实虚拟显示。

失败后回归再次经公开 `input()`、`launch()` 确认 `SESSION_FINISHING` 且命令计数不增加，并经公开 `status()` 读取 `finishing=true`、`mutationUncertain=true`。测试中的存活校验与 status 只读数据使用合成来源，但 Owner 自身的入口检查、状态迁移和 JSON 字段构造仍执行生产代码；不将此等同于真实显示/IPC 的端到端验证。

## 测试与未验证事项

- 原 19 测试保留；三项原 Robolectric 测试仍在 SDK 36，使用 `Config.NONE` 隔离真实 Manifest/ChatApplication，不依赖旧应用框架。
- `OwnerInputExecutionTest`（Robolectric API 26/36）：通过公开 input 入口覆盖非零、超时、未观察到退出、checked/unchecked 异常，以及成功 command 后 JSON 创建/部分写入失败；每种失败都通过公开 input/launch 验证拒绝重放，并通过公开 status 断言两字段。不再依赖反射字段断言或直接调用 runInput。另覆盖执行前上锁、完整成功 body 后解锁、显示绑定/参数/几何校验失败不执行命令。
- `OwnerStageOneContractTest`：API 26/36 下检查 root-only、真实 Main usage、`OWNER_PACKAGE=android`、组件/marker/独立 affinity 一致、Anchor 拒绝主显示。
- JUnit 4.13.2 沿用项目依赖；新增 host-only Robolectric 4.16.1 以支持原 SDK 36 测试，不提升应用 minSdk。
- 按本次限制，**未运行构建、测试、root、二进制、网络解析或推送**。新增测试未获执行结果；Robolectric 依赖解析、当前 JDK/AGP 运行兼容性、Android hidden API/ROM 差异、真正次显示 anchor/任务移交与 release 均待获准后的独立验证。这里不把静态检查冒充通过测试。
- 用户以版权拥有者身份作出的本项目迁入/修改/集成授权已经确认，不再列为待询问项。原许可全文与 Required Notice 保留；缺失的输入第三方 NOTICE 及分发时第三方权利、许可组合审查仍需单独处理，不据此自行宣称 AGPL 兼容，见来源说明。
