# 1号公共执行上下文契约 v1

本批基于main `3270bc22088142822c8c3b075b3516c12c36ea78`，随对应实现同次提交。本文是接口合同，不是第二份功能清单。唯一状态清单仍为`docs/重构清单.md`。

## 已实现的公共接缝

- `domain.runtime.RunExecutionOwner`：不可公开构造、非data class；只读`conversationId`、`runId`、`assistantMessageId`、`ownerId`，`owns(message)`精确校验三元组及assistant角色。`internal issue`是core模块可见，不是反射安全沙箱；生产调用点限制由源码守卫及测试保证仅ChatRunner签发。不要通过反射、反序列化、公开ID重造token。
- Runner每次run签发一个owner，全部`RunCheckpoint.owner`沿用同一ownerId。首次成功持久boundary后，读取definitions或调用model.open前执行`ReceiptBatchToolExecutor.bindOwner(owner)`一次；pause/reopen不重绑/不重签发。接口默认空方法仅兼容旧SAM，不是生产接入证据。
- 生产`RunCatalogTools.bindOwner`与`CatalogModelBridge.bindOwner`接收同一token，只允许同实例幂等；不同实例即使业务ID相同也拒绝。RunCatalogTools未绑定不capture、不广告、不执行；close先永久fence，不重开。Bridge每次批次验证精确assistant ID，而不只验证conversation/run。
- `PreparedTool.contextual(writeEffect: WriteEffect? = null, execute: (ToolExecutionContext) -> ExecutionReceipt)`：prepare仍只接收ToolArguments并保持零I/O。旧`PreparedTool(writeEffect, execute: () -> ExecutionReceipt)`保留。contextual的无参execute拒绝，不可手工补造context绕过dispatcher。
- `ToolExecutionContext`构造限core模块；字段为`owner: RunExecutionOwner`、`invocation: ToolInvocation`、`authorization: TaskAuthorization`、`cancelled: () -> Boolean`。真实provider调用ID是`invocation.id`，同批原对象原样交付；authorization是run创建并传入Skills/dispatcher的同一个实例；cancelled实时包含tools.closed与Runner停止。
- `ToolDispatcher.dispatchOwned(task, batch, owner, cancelled)`与旧dispatch共用预检/ledger路径；contextual无owner整批在reserve前拒绝。capability、schema、effect契约、精确WriteEffect授权不变；CONFIRMED replay不进入adapter，UNKNOWN不重放。Run owner不替换ledger reservation UUID、runtime process owner或TaskAuthorization。

## 2号/3号可直接消费的签名与边界

2号设备与3号Question adapter在prepare完成纯校验后返回`PreparedTool.contextual { context -> ... }`，从context读真实call ID、可信owner和同实例authorization。不要将这些字段加入模型JSON或生成fallback。

当前没有新增设备/Question注册，没有创建PTY/browser/Question资源，没有改变独立授权开关、逐action effect或allowedWrites；不能把该合同当成工具已获授权。资源仅在后续明确生命周期实现后接入，尤其Question等待唤醒/卡片历史/恢复、PTY失败startup退出所有权、浏览器Cookie映射与视觉附件仍须各自成品。

Controller只补运行准备失败时关闭已创建tools；已有stop、finally、close继续关闭同一run tools，UI Message控制目标仍与执行owner分离。不要因Activity重建签发新owner。

## 4号模型工作边界

本批不改ModelPort/ModelSession/ModelEvent、不改checkpoint编码/DDL、不接裸Usage入账。仓库生产Kotlin范围未查得`SessionGatewayIdentity`定义；旧交接中的该名称不是已存在API，禁止凭名称臆造可调用类型或将RunExecutionOwner自动当request/attempt身份。

4号可以独立在模型包推进严格JSON整数/范围解析及协议计数映射测试（缺失保持null），不动公共ModelEvent/Runner/数据库。request/attempt与终止协议+usage原子回执下一版由1统一落可编译合同：新next新requestId、transport resume同request换attempt、网络前持久准入、每request最终封存一次、不因入账失败重POST。当前仅冻结这些既有语义，不声称准确Kotlin签名已提供。

## 验证与交付

父级实际Runner→RunCatalogTools→Bridge→Dispatcher→contextual adapter测试核对checkpoint owner、同实例authorization、真实invocation、精确assistant拒绝、CONFIRMED replay及UNKNOWN阻断；Controller平台原准备测试新增运行时tools绑定与SQLite owner查询。工具层兼容与取消/stop/持久失败回归必须通过。编译不替代平台执行，更不提升完整81/28项。

各会话独立local main定向patch；不改共享树、不建分支、不push/dispatch。1统一接收与CI；不下载/安装APK。此文不表示2/3/4号会话已实际启动或确认收件。
