# R08：显式组件和同计划 Handoff → Release

## 范围与来源

本增量针对任务指定基线 `57064e27`，沿用已接入的真实计划编辑器、Controller 受理/审批、唯一中央目录和额外 cleanup；不重新接 MainActivity/ChatController，不复制旧 Eta，不改 owner 的保护算法。

核实来源：

- `vd/runtime/VirtualDisplayOwner.java::launch` 要求显式 `component`，并从该组件包名执行原 provenance/occupancy 检查；仅有 package 不能正常启动。
- 同文件 `handoff` 使用原 `OwnerHandoff.move`；`release` 在有 owned 任务且未完成 Handoff 时拒绝 `HANDOFF_REQUIRED`。
- `OwnerProtocol.isSafeIdentifier` 限定 255 字符及安全字符集（不接受 `$`）；`businessParameters` 是现有默认参数权威。没有放宽这两项。
- 最新清单已记录编辑器和原运行额外 Release 已接入；本次只补参数与动作缺口，不重复该接线。

## 显式 Launch 参数

`R08VirtualDisplayAction.Launch(packageName, component)` 两项均必填。编辑器真实提供 package、component 两个字段；可输入例如：

```text
package:   org.example.notes
component: org.example.notes/.MainActivity
```

也可输入 `org.example.notes/org.example.notes.MainActivity`。组件包名前缀必须与 package 完全一致；只接受相对 `.Class` 或完整限定类名、安全 ASCII 标识符及总长不超过 255。缺失组件、额外斜杠、空类名、包名不符、空白/控制字符、shell 元字符以及 owner 当前不允许的 `$` 均在冻结前拒绝。组件是否安装、可启动以及任务占用仍由真实 owner/Android 检查，不将语法检查冒充设备解析成功。

输入原样进入 `OwnerV2Contract.normalize`，包括既有 `flags: 0`、`categories: []` 默认值，随后同一 canonicalParameters 用于审批行、精确 WriteEffect、durable intent 和 execute。短组件不会在执行时展开/替换；没有新增 package manager resolver，也**不需要新的 Main/Controller 解析器接线**。改变组件改变 effect 和 intent 内容；同一真实 invocation 的 operationId 不换，journal 仍拒绝同 ID 不同参数，而不是用新 ID 绕过旧证据。

## 正常退出与额外 cleanup

有限动作增加编辑器无输入的 `Handoff`，冻结规范参数为 `{"taskIds":[]}`：明确选择 **cleanup-only、不保留任何任务**，由原 owner 验证并清理其 owned 任务，再验证源屏为空。`OwnerHandoff.move` 要求显式有界 taskIds 数组，不能用缺字段的 `{}`；本动作不在执行时发现/替换任务 ID，不开放 displayId/任意 taskIds/主屏选择器。这不是把新启动任务保留到主屏的功能；若以后需要保留具体任务，必须另设计冻结前已知任务 ID 的明确批准，不能临时取状态填参数。推荐完整计划：

```text
Start → Initialize → Launch(package, component) → 有限输入 → Handoff → Release
```

Handoff `{"taskIds":[]}` 与 Release `{}` 是同一原 owner/capabilities/generation 批准的两个独立精确 effect。Handoff 至多一次，其后仅允许 Release；Release 至多一次且为最后写动作。包含 Launch 和 Release 的正常计划必须包含 Handoff。读取仍独立，不启动 owner。

叶子只在既有共同 `ok / 非 receiptOnly / 非 mutationUncertain` 门之后接受 `receipt.state == SUCCEEDED && handedOff && sourceEmpty`；其 claim-before-send、lookup-only、单次发送、journal complete 和原整批中央 finalOutcome 路径不变。Session 对计划中的 Release 另要求原 Handoff 的真实已验证成功回执；模型跳过 Handoff 不会发送 Release，也不会自动插入或补发 Handoff。此非法顺序保守成为 UNKNOWN。整批中央结果能降级本地成功；中央 replay 本身不产生新的原 Handoff 物理成功证据。

**额外 cleanup 仍然只是额外明确批准的有限 `Release {}`，不暗中升级为 Handoff+Release。** 若原计划包含 Launch/Handoff，准备额外 Release 还要求原 Handoff 的已知成功；Stop 在原 Handoff 之前不会生成扩大权限的 cleanup 请求。原 Handoff 已成功但 Release 尚未尝试时，可沿已有明确 cleanup 审批只发送一次 Release。原 Release 已尝试、任何 UNKNOWN、缺少最终批次反馈或父未排空仍禁止替代。没有重复 Release、新 generation、重连、杀进程或新 operationId 恢复途径。

close/Stop/observe 继续不执行动作。确认 Handoff 或 Release 不等于 physical drain：原 parent drain、活动执行/最终反馈、journal 已知性，以及 STOPPED、launcher exit=0、原 PID 消失的真实观察门和 lease 保留规则均继续存在。

## 本次回归源码与验证状态

新增/扩展：

- `R08VirtualDisplayPlanTest`：短/完整组件及 255 字符边界、坏组件、参数不可变、审批/intent 完整一致、组件变化影响 effect、Handoff/Release 同计划精确 effect、退出顺序拒绝。
- `R08PlanEditorHostTest`、`R08PlanEditorScreenTest`：真实双字段和 Handoff 编辑/冻结、原 owner/capabilities、canonical 审批参数；原不可变/取消测试保留。
- `R08VirtualDisplayCatalogLeafTest`：真实 v2 client/validator + 离线脚本 transport 的 Launch→Handoff→Release、冻结参数/operationId/journal 一致；旧 claim 只查原 Handoff 回执、不补发、不 Release、UNKNOWN 不以 drain 洗白。
- `ControllerVirtualDisplaySessionTest`：同一审批/lease/generation 的正常执行顺序、未 Handoff 不发 Release、坏/未知/receipt-only Handoff 不重发、journal 完成丢失和中央降级阻断、中央 replay 不赋额外 cleanup 权、原 Handoff 后仅额外批准 Release、真实 drain 前不退 lease。

以上为**已保存的测试源码，未编译、未执行**。本环境只有 UTF-8 工作树文件工具，没有 shell、Git commit、构建或设备工具；没有运行测试、diff-check/边界脚本或设备/root 操作，也不能在此核验 detached HEAD 或创建 commit。由主代理审查实际净 diff、在原 detached 工作树创建非空代码提交，并统一运行后续验证。本说明不代表 R08 整体运行验收完成。
