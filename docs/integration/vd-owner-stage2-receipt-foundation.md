# R08 第二阶段：独立 identity / durable receipt 基础（未接线）

> 本文保留第二阶段历史范围。后续 owner 侧 Main/IPC 可信 bootstrap 与 receipt 接线见 [第三阶段](vd-owner-stage3-bootstrap-receipts.md)；中央宿主接线与真机验证仍不能据此视为完成。

## 范围与证据边界

本阶段只新增：

- `app/src/main/java/app/daiyu/android/vd/runtime/OwnerOperationIdentity.java`
- `app/src/main/java/app/daiyu/android/vd/runtime/OwnerReceiptStore.java`
- 对应 `app/src/test/java/app/daiyu/android/vd/runtime/OwnerOperationIdentityTest.java`、`OwnerReceiptStoreTest.java`
- 本说明。

本次实例生命周期修补仅修改 `OwnerReceiptStore.java`、`OwnerReceiptStoreTest.java` 与本说明；identity/canonical 合同及持久状态格式不变。

不修改既有 owner / handoff / input / IPC / `VirtualDisplayOwnerMain` 算法，不接 root 启动器，不增加 RPC、进程管理或协议操作。本基础目前没有生产调用方；不能据此声称完整 R08、恢复闭环、真机验证或 exactly-once。实现代理仅进行源码检查，**没有运行 shell、构建、JUnit、网络或 root**；测试是否通过须由父代理后续独立验证。这里的 fsync/rename 故障测试是 tmpdir 故障注入源码，不是断电实验，也不证明目标 Android 文件系统支持相关操作。

## 信任边界

- `RunBinding` 的六个字段固定为 `session/task/conversation/run/assistantMessage/runOwner`；私有构造器、包内 `bindingFromTrustedHost` 工厂，**没有从 JSON、模型参数、token 或任意 Map 解码绑定的入口**。
- 同包可信父宿主必须从已经认证的 host/run 状态取得全部字段。Java 包可见性不是认证；恶意同 UID/同进程代码、反射及被攻陷的可信宿主不在本 leaf 可防御范围内。
- 业务参数中即便有名为 `runBinding` 的字段，也只是被摘要覆盖的普通参数，不能替代独立可信绑定。
- 构造 store 的 `Path` 必须由父宿主固定选择、预建为 0700 私有目录，不得来自模型、IPC 请求或任意用户路径。构造器不自动建立该根目录；相对路径、未规范化路径、根或祖先符号链接、错误权限被拒绝。实际目录固定位置、所属系统身份及其祖先不可被非可信写者替换的保证由父负责。
- 不写日志，不保存认证 token，不保存原始业务参数或完整绑定；receipt 保存 generation、operation ID/名称、两个摘要、状态与父提供的有界结果。**父必须只传无密钥/无 token 的结果字段**；通用 Map 不是敏感信息自动清洗器。摘要也不应被当作认证凭证。

## 精确摘要合同 v1

`generation`、`operationId`、操作名：均是 **1–64 个 ASCII `[A-Za-z0-9_-]`**，拒绝点号、斜线、反斜线、空白、百分号编码、Unicode 等。绑定各字段非空，上限 256 个 Java UTF-16 单元，不允许控制字符或未配对代理项。

操作摘要为以下对象的 canonical JSON **UTF-8 字节**的 SHA-256，输出 64 位小写十六进制；没有换行、BOM、盐或省略字段：

```json
{"contract":"owner-operation-v1","generation":"...","operation":"...","operationId":"...","parameters":{},"runBinding":{"assistantMessage":"...","conversation":"...","run":"...","runOwner":"...","session":"...","task":"..."}}
```

- `parameters` 必须是父验证后的**全部有效业务参数**，包括影响行为的默认值、flags、目标、坐标/时序/策略等，不可取白名单投影后默默丢字段；不得把传输 token 放入其中。
- generation claim 摘要使用 `{"contract":"owner-generation-v1","generation":"...","runBinding":{...}}`。
- 独立 binding 摘要是六字段 `runBinding` 对象本身 canonical JSON 的 SHA-256。
- string-keyed `Map` 递归排序，顺序为 Java UTF-16 code-unit 顺序；拒绝重复的相同文本键。`List` 保留数组顺序。仅支持 null、Boolean、String、Byte/Short/Integer/Long、精确 BigInteger、精确 BigDecimal。**不接受 JSONObject/JSONArray、Double/Float、任意对象或 Java 数组**；父适配器须按合同转换，不能通过 `toString()` 偷渡。
- 数字无指数、无前导加号：BigDecimal `stripTrailingZeros().toPlainString()`，零为 `0`，故整数 `1` 与十进制 `1.0` 是同一值；NaN/Infinity 不可表达。
- 字符串不做 Unicode normalization，未配对代理项拒绝；双引号/反斜线转义，U+0000–U+001F 使用小写 `\u00xx`，其他 Unicode 标量直接 UTF-8。字符串 `é` 与组合序列是不同值。
- 缺失字段与显式 null 不同；数组换序、额外业务字段、操作名/ID/generation 及任意绑定维度变化均改变摘要。对象插入顺序不影响摘要。
- 最大 canonical JSON 为 64 KiB UTF-8，递归深度 32、值节点 8192；循环结构、超限数值/文本拒绝。上述是本地合同，**不是声称实现 RFC 8785/JCS**。
- identity 创建时立即计算并只保留摘要，不持有参数容器引用。父必须避免计算摘要时/后并发改变实际执行参数；应从同一不可变快照摘要并执行，不能摘要 A、执行 B。

## 持久状态与调用顺序

目录布局：`root/.receipt.lock`，`root/g-<generation>/generation.receipt`，`root/g-<generation>/op-<operationId>.receipt`。分目录避免拼接分隔符歧义。目录 0700，文件 0600，所属 owner 必须与受信根一致。

两类 receipt 使用同一单调状态机：

```text
不存在 -- durable reserve --> RESERVED -- durable start --> STARTED
                                                   STARTED --> SUCCEEDED | FAILED | UNKNOWN
```

- `reserveGeneration` 成功且 `newClaim != null` 才表示本 store 实例刚刚持久化了一次新 reservation。**此时仍不得创建 display/owner**。
- 对 fresh claim 调用 `start`，仅 `mayExecute == true` 时才有一次执行资格；STARTED 已落盘，随后才允许父创建资源/执行业务。再次 `start` 返回旧 receipt 且 `mayExecute == false`。
- `reserveOperation` 还要求本 store 实例具有已 STARTED 的 generation claim，并验证 generation 和 binding。预留但尚未启动的操作，在 generation 已结束后不再允许启动。
- `complete` 只允许 STARTED 进入终态。终态（含 UNKNOWN）不可覆盖；再次完成只返回原状态和原结果，即使新的结果不同。
- 相同 ID 但摘要/绑定/操作不同，返回 `CONFLICT`；冲突不能按新操作执行。
- **已经存在的 RESERVED、STARTED、UNKNOWN 或任一终态都不给 fresh claim**。重开 store 只读取旧 receipt，不重新发放原 generation 或 operation 的执行能力。旧实例的 Claim 不能导入新实例。未完成状态表示可能已执行/无法恢复，不表示可安全重放。
- 未知结果由仍持有 live claim 的父写 UNKNOWN；崩溃留下的 RESERVED/STARTED 不自动推断、更改或执行，也不自动变成 FAILED。
- `lookup` / `lookupGeneration` 仅作 receipt-only 查询、绑定和摘要匹配；不会建立 generation/operation 目录、不创建 display、不调用原操作。store 构造会在受信根初始化私有锁文件；查询自身不生成 operation receipt。

这些资格是可信父遵守的流程约束，不是执行回调；父不能复用先前保存的 `mayExecute=true` 结果多次执行。系统副作用与 receipt 的落盘之间没有跨系统事务：STARTED 后崩溃可能没有执行，也可能已执行但未留下 final。保守阻止重放会牺牲可用性，不等于 exactly-once。

## store 实例生命周期与排他

- `OwnerReceiptStore` 实现 `AutoCloseable`。构造时用非阻塞 `tryLock` 获取根级文件锁，**锁与通道持有至该实例成功 `close()`**，而不是每个方法结束后释放。相同 root 的第二个构造（即使只是查询、尚未 reserve，或使用不同 generation）返回 `BUSY`；不同 root 相互独立。操作仍由实例内 `synchronized` 串行化。
- 同 JVM 另有按已验证绝对规范路径的存活 root 登记，先于打开锁文件拒绝重复构造。这样避免某些系统中“关闭同一锁文件的另一个通道也释放进程锁”的风险；跨进程排他依赖文件锁。受信宿主必须固定同一实际 root，不得替换、unlink 锁文件或用其他挂载别名绕开它；此类不可信写者不在本类防御范围内。
- 宿主必须显式关闭（推荐 try-with-resources），不能依赖 GC。成功 close 可重复调用，之后所有 store 读写/claim 操作均返回 `CLOSED`，旧 claim 也不能导入新实例（`INVALID_CLAIM`）。关闭不补写 final，不改变 UNKNOWN/未完成记录，成功关闭后重开仍不发放旧 generation/operation 的 fresh claim。
- 构造在已开通道/已持锁之后失败会关闭通道并释放已取得的锁，确认清理成功才删除 JVM 登记；清理失败保留登记并将清理异常附在原异常上。正常 close 同样只在通道关闭、锁失效得到确认后撤销登记；任何关闭错误均向宿主抛出，不声称安全释放，当前实例永久拒绝操作，重复 close 仍报告错误，JVM 内该 root 继续 fail-closed。文件系统/进程实际是否仍持锁不能从失败推出，宿主不得据此启动替代 owner。
- **这只是 store 实例排他，不是 owner-process/lease 生命周期绑定。** 文件锁不能撤回已经返回的 `mayExecute=true`，`close()` 不会停止任务、销毁 display、结束租约或证明旧 owner 已退出；进程退出会释放 OS 锁，也不证明其外部资源已安全回收。宿主必须先停止接收工作、等待/撤销在途执行并完成实际 owner/lease 退出确认，再关闭 store 或允许重开；具体接线与验证仍未完成。

## 写入与失败合同

- 根级生命周期锁保护该 store 的全部检查/迁移与只读查询，不在每次调用后释放；锁被占用时构造返回 `BUSY`，不等待也不假装取得锁。
- 同目录固定 `.pending` 文件以 CREATE_NEW/0600 建立；完整写入、文件 `force(true)`、目录同步 pending，再 `ATOMIC_MOVE + REPLACE_EXISTING`，最后同步目录。不使用非原子回退。
- 读文件前验证类型、私有权限、owner、无符号链接；有界读取，文件最大 96 KiB，严格 UTF-8。二进制 envelope 带 magic/version、长度字段、身份、状态、结果及覆盖全部正文的 SHA-256 校验。截断、超限、校验或结构损坏均拒绝。校验用于完整性检查，不是 MAC，不能防御可改写私有根的攻击者。
- `.pending` 遗留表示不确定，**不删除、不当作不存在、不重执行**。已有 generation 目录缺 generation record 同样拒绝，不自动补建。
- 任何写入/同步/rename 的 I/O 错误都产生 `INDETERMINATE_WRITE` 并毒化当前 store 的后续写能力；**普通 `atomicMove` 报错也可能已写入，不能解释为“尚未写，安全重试”**。不支持原子 rename/目录 fsync 也不得降级。
- 失败后可能看到旧记录、新记录或 pending；即使新记录可读也没有新的执行资格。只读查询可报告可验证的旧/新 receipt，或明确报告不确定/损坏。
- 无自动 GC、重置、删除、过期重放、目录扫描恢复或跨主机协调。父不能为绕过 UNKNOWN 任意换 operationId 并重复同一业务副作用。

## 父代理独占的后续接线要求

1. 在任何 owner/display 构造前，从可信上下文生成并持久 claim generation，检查 fresh claim，持久 STARTED 成功后才创建；构造失败/未知结果记录相应 final（仍可能因 I/O 失败留下 STARTED）。不能让 Main 先创建再补 receipt。
2. 给每个业务调用生成稳定 operationId，从同一有效参数快照建立 identity；先 reserve、再持久 start，只有新的执行资格才进入现有算法，最后写 final。
3. 在既有认证/对端校验之后接入 receipt-only lookup，并校验当前调用者被授权访问相同 session/run/owner 绑定；这里没有公开 IPC handler，也不承担身份认证。必须保护查询数据与 token，不能把 digest 当 bearer token。
4. 认证 lookup 不得为查询重启旧 generation、创建 display、重发原操作；历史 RESERVED/STARTED/UNKNOWN 一律按不确定处理，final 只回旧 receipt。
5. 将 store 的持有期绑定到实际 owner-process/lease generation，而不只是一次请求/查询作用域；退出、撤权、handoff、超时与异常路径必须先确保旧执行静默/实际 owner 退出，再 close 和移交。关闭失败按不确定处理，不因一次重开成功便认定旧 owner 安全终止。此 process/lease 绑定仍是宿主待办，本类没有实现进程管理。
6. 真机能力、父生命周期/撤权、并发与超时、调用方恢复、存储位置/权限/设备 fsync 行为、无敏感输出等，仍需父后续集成和验证。本阶段未修改第一阶段算法，也未宣布这些闭环完成。

## 离线测试源码覆盖（尚未执行）

`OwnerOperationIdentityTest` 覆盖 canonical 明确向量、SHA-256 已知向量、全 envelope/六绑定维度、完整参数变化、对象/数组顺序、null/缺失、不可变摘要、路径/Unicode/数值/深度/节点/字节上限与循环/重复键拒绝。

`OwnerReceiptStoreTest` 使用 JUnit TemporaryFolder：显式 close 后 reserve/start/final 落盘重开、所有状态不重放、跨实例 capability 拒绝、绑定/参数冲突、UNKNOWN/终态单调、generation 结束前置约束、只读查询不增建目录、分隔符无碰撞、私有权限/符号链接、截断/翻转/oversize、遗留 pending/缺记录，以及 reserve/start/final 在写前、文件同步后、rename 前后、目录同步后的故障矩阵。普通/不支持的原子 move 错误与“rename 已完成才报错”均有独立断言。

生命周期断言覆盖：闲置/查询/预留后仍拒绝同 root 第二实例、不同 root 独立、成功 close 幂等且旧实例再次 close 不影响新 owner、关闭后所有读写/旧 claim 拒绝、旧 RESERVED/STARTED 不能因重开获得能力、持锁构造失败清理与重开、独立文件通道探测确认方法返回及重复构造被拒绝后锁仍被持有/close 后才可取得、已有外部通道持锁时构造 BUSY 后不泄漏登记、关闭报错和重试均不得虚报安全释放。测试统一在 teardown 显式关闭成功构造的普通实例；重开用例在打开下一实例前先 close。关闭失败用例在真实通道关闭后注入报错，只验证保守失败策略，不是内核 close 故障/跨进程实测；构造失败测试在持锁后注入异常。实际跨进程争用、构造/close 的内核级异常、进程死亡、owner/lease 与在途执行的移交仍未验证。

测试前提是支持 POSIX 属性和目录同步的本地 tmpdir；不应通过忽略失败来虚报目标平台支持。本次生命周期修补仅完成源码检查，未运行 JUnit、全量测试或 APK 构建，亦未修改 Main/Controller/manifest/清单或旧仓库。
