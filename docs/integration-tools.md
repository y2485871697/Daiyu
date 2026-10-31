# 跨模块工具目录与授权分派基础

## 范围与证据

这是新的中央注册、授权和账本接线基础，不替换旧暂停模块，不复刻 历史自有源码，不声明 31 域已经接通。

目前生产目录只注册两个已有真实后端：`get_battery_status` 和 `device_status`，调用 `app/.../platform/AndroidBatteryTool.kt` 中的 **AndroidReadOnlyTools**。没有占位成功工具，也没有默认注册 `ask_user` 或任何写工具。独立模块恢复后，由模块提供真实 `RegisteredTool` 再显式注册。原 `ModelPort`、`BatteryTool`、`Message`、`ModelEvent` 与旧 Android 后端文件未修改。

依据：`docs/refactor-input/acceptance.json` 中 AC-011–013（真实查询、非法/截断参数、顺序），AC-023（能力不是授权），AC-025–026（重复 ID、等价未知副作用），AC-031（澄清单批屏障）；更完整的流恢复/澄清 UI/后端验证不是本层已完成的功能。

## 精确公共 API

核心包：`app.daiyu.android.domain.tools`

```kotlin
class ToolRegistry {
    fun register(tool: RegisteredTool): ToolRegistry
    fun snapshot(): ToolCatalog
}
class ToolCatalog {
    val definitions: List<ToolDefinition>
    fun available(session: SessionCapabilities): List<ToolDefinition>
}
interface RegisteredTool {
    val definition: ToolDefinition
    fun prepare(arguments: ToolArguments): PreparedTool
}
class PreparedTool(
    val writeEffect: WriteEffect? = null,
    val execute: () -> ExecutionReceipt
)
data class ToolDefinition(
    val name: String,
    val version: Int,
    val description: String,
    val capability: String,
    val effect: ToolEffect,
    val schema: ToolSchema
)
enum class ToolEffect { READ_ONLY, WRITE, USER_BARRIER }
class ToolSchema(fields: List<ArgumentField> = emptyList()) {
    val fields: List<ArgumentField>
    fun parse(json: String): ToolArguments
    fun jsonSchema(): String
}
class SessionCapabilities(val sessionId: String, enabled: Set<String>) {
    val enabled: Set<String>
}
class TaskAuthorization(
    val sessionId: String,
    val taskId: String,
    allowedWrites: Set<WriteEffect> = emptySet()
) { val allowedWrites: Set<WriteEffect> }
data class WriteEffect(
    val namespace: String, val operation: String,
    val target: String, val change: String
)
interface ToolLedgerStore {
    fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T
}
class ToolLedger(store: ToolLedgerStore)
class ToolDispatcher(
    catalog: ToolCatalog,
    session: SessionCapabilities,
    ledger: ToolLedger
) {
    fun dispatch(
        task: TaskAuthorization,
        batch: List<ToolInvocation>,
        cancelled: () -> Boolean = { false }
    ): List<ToolDispatchResult>
}
data class ToolDispatchResult(val callId: ToolCallId, val outcome: DispatchOutcome)
```

`DispatchOutcome` 三分：

- `NotExecuted(code: String)`：本次调用肯定没有执行（整批校验失败、取消、账本预留失败，或后端证明从未尝试）。
- `Confirmed(payload: String, isError: Boolean, replayed: Boolean = false)`：明确收到执行结果。**不等于业务成功**；确认的失败也使用它。
- `Unknown(code: String)`：本次或等价的先前副作用可能已经发生，不能当作未执行，不能自动重试。

后端回执 `ExecutionReceipt` 同样有 `Confirmed(payload, isError = false)` / `NotExecuted(code)` / `Unknown(code = "execution_unconfirmed")`，不含重放字段。异常正文不会转成模型可见错误；分派器只返回固定分类码。

Android 包：`app.daiyu.android.integration.tools`

```kotlin
object AndroidToolRegistry {
    const val BATTERY_CAPABILITY = "device.battery.read"
    const val DEVICE_STATUS_CAPABILITY = "device.status.read"
    fun capabilitiesFor(allowed: Set<ReadOnlyTool>): Set<String>
    fun create(
        context: Context,
        additionalTools: Iterable<RegisteredTool> = emptyList()
    ): ToolRegistry
    fun registerReadOnlyTools(registry: ToolRegistry, context: Context)
}
class SQLiteToolLedgerStore(
    helper: SQLiteOpenHelper,
    closeHelper: Boolean = false
) : ToolLedgerStore, Closeable {
    constructor(context: Context, databaseName: String = "integration-tools.db")
    companion object {
        const val TABLE_NAME = "tool_ledger"
        fun createSchema(db: SQLiteDatabase)
    }
}
```

### 最小真实实例化

```kotlin
val store = SQLiteToolLedgerStore(context) // 持有到本宿主关闭；不要每次创建新随机文件
val registry = AndroidToolRegistry.create(context, realModuleAdapters)
val session = SessionCapabilities(
    restoredStableSessionId,
    AndroidToolRegistry.capabilitiesFor(allowedAtSessionStart)
)
val catalog = registry.snapshot()
val dispatcher = ToolDispatcher(catalog, session, ToolLedger(store))
val readOnlyTask = TaskAuthorization(session.sessionId, hostTaskId)
// 模型层由父接线根据 catalog.available(session) + definition.schema.jsonSchema() 构建 schema。
// 必须先取得完整且协议终止已确认的 batch，不能将流中的半截工具直接执行。
val outcomes = dispatcher.dispatch(readOnlyTask, fullyReceivedBatch)
```

`BatteryTool` 兼容接口保留原状；这个分派器不是逐调用的 `BatteryTool` 替代物。父接线需要在完整批次边界调用 `dispatch`，并保留三种结果状态。将 `Unknown` 压平成 `ok:false` 会错误地暗示“没有执行”；只用旧逐调用执行路径也不能获得整批屏障。本次没有修改模型工具 schema 或旧 ChatRunner。

## Schema 与扩展注册协议

稳定名称 + 正整数版本 + JSON Schema + `ToolEffect` 组成目录声明；重复名称注册失败，已建 catalog 不受后续注册影响。会话开始复制能力集合；任务授权复制 grant 集合；schema 字段和枚举集合也冻结。

`ArgumentField(name, type, required = true)`；名称为小写蛇形。类型支持：

- `ArgumentType.Text(maxLength = 4096, choices = emptySet())`
- `ArgumentType.Integer(minimum = Long.MIN_VALUE, maximum = Long.MAX_VALUE)`
- `ArgumentType.Decimal(minimum: BigDecimal? = null, maximum: BigDecimal? = null)`
- `ArgumentType.BooleanValue`
- `ArgumentType.ObjectValue(schema: ToolSchema)`
- `ArgumentType.ArrayValue(element, minimumItems = 0, maximumItems = 64, unique = false)`

`ToolArguments` 提供 `text/integer/decimal/boolean/objectValue/array/contains/canonicalJson`。嵌套数组中对象元素是 `ToolArguments`；数组返回不可变列表。schema 不是任意 JSON Schema 引擎；不支持 `$ref`、联合类型、null、自由键对象等。整数参数不接受浮点或字符串强制转换。JSON 总长上限 65,536 字符、嵌套深度上限 32，数字 token 最长 128、decimal scale 范围 -512…512。拒绝重复键、未知字段、注释、尾随输入、非法 Unicode 与非 JSON 空白；可选字段意味着可以缺省，不意味着可传 null。

`jsonSchema()` 公开稳定的对象 schema（`additionalProperties:false`）。严格解析后，键排序、字符串转义和 decimal 表示归一；数组顺序保持。进一步的互斥选项、URI/路径解析、权限、对象版本、资源可用性等校验仍由对应模块完成。不要为未接通模块编造 schema 或返回成功。

模块的 `prepare` 必须是**纯校验/规划**，不能 I/O、启动 Activity、弹权限、执行脚本或发请求。目录与能力允许后，再解析所有参数并准备所有计划；某个计划失败，整批都不执行。真实后端只能在 `PreparedTool.execute` 中调用。模块仍需在执行阶段检查 OS 权限和当前资源；若尚未发送，返回 `NotExecuted`；若已经尝试且无法确认，返回 `Unknown`。禁止把网络异常一概返回 `NotExecuted`。

写工具必须声明 `WRITE` 并给出 `WriteEffect`；只读/屏障计划不得携带写效果。可信适配器必须使声明与真实行为一致，目录不是用于隔离恶意插件的沙箱。

## 授权与整批屏障

- 能力说明“本会话可以使用该工具”，**绝不等于任意任务的写授权**。
- 每个任务由宿主创建 `TaskAuthorization`。模型参数、历史文本、工具回执均不能创建授权。
- grant 是完整的 `(namespace, operation, target, change)` 效果，而非工具名、通配域或模糊目标。换目标或换内容都会拒绝。
- 写效果的目标必须由适配器正确归一、范围校验；路径穿越、符号链接、后端账号和目标变化由后端负责，不得只靠名称字符串。
- 任务必须绑定同一 session。授权校验在重放前也执行，不能通过缓存绕过禁用能力/撤掉授权。
- 整批最多 64 项；未知工具、非法参数、缺能力、越权、重复 ID 或同批等价写效果均导致整批不执行。
- `ask_user` 必须声明 `USER_BARRIER`；任何 `USER_BARRIER` 都要求单项批次。与其他工具混合时整批不执行。真实问答模块接入后自行校验问题和 2–8 互斥选项、推荐不代选、用户选择/取消回执。本层没有默认询问 UI 或假答案。
- 按请求顺序交付结果。同名只读工具的不同 ID 独立采样；相同 ID 重放缓存，不重新采样。出现未知或回执持久化失败时，不再执行批内后续工具。

## 账本、不确定性与恢复

`ToolLedgerStore.transaction` 必须为所有使用者串行化，异常回滚，**持久提交结束后才能返回**。核心的 `InMemoryToolLedgerStore` 只用于 JVM 测试或明确不要求持久性的宿主，不能用于 Android 生产恢复。

SQLite 实现使用 app-private 数据库，跨实例/进程由数据库写事务串行化。生产可用 `SQLiteToolLedgerStore(mainDatabaseHelper)` 注入主库同一个 `SQLiteOpenHelper`，默认不拥有/关闭它，以便宿主同库恢复和协调清理。`createSchema(db)` 可在主库 onCreate/迁移中调用；也会在第一次顶层账本事务中懒添加表，绝不删除已有行。独立 Context 构造保留用于测试或独立宿主，并使用 FULL synchronous。注入 helper 的持久配置、版本迁移和统一清理归主库宿主负责；不得为普通恢复/重试清掉未知行。账本事务拒绝嵌套在宿主未提交事务内，避免在外层真正提交前允许后端执行；父 Runner 应在独立顶层事务边界使用它。整批账本校验和预留在同一个事务；写入 `UNKNOWN` 后才执行任何后端。结果收到后再事务保存确认/未执行回执。关闭重开、进程死亡、不同适配器切换都保留未知保护，不需要“恢复时重发”。损坏/不可读数据库或版本迁移缺失失败关闭，不能当成空账本继续执行。

两层身份：

1. `(sessionId, callId)` 绑定任务 ID、目录版本、归一参数和效果。ID 相同但请求/任务改变拒绝；确认的原回执只重放一次执行结果。
2. `WriteEffect` 经 SHA-256 长度分帧计算语义键，**跨 session/task/工具名/执行渠道**保护同一副作用。新 call ID、换 GUI/HTTP/本地适配器、参数空白或等价拼写均不能绕过未知保护。不同后端必须共享 namespace/operation 与资源及完整 change 的归一规则。

效果键不能包含 transport call ID、session/task ID、随机 nonce，也不能漏掉金额/目的地/内容/账号等语义字段。非幂等业务必须由真实模块设计可核验的业务操作身份，而非模型随意生成的新 ID。

本基础采取保守政策：确认的等价写效果也重放已确认结果，不自动重复执行；未知效果永久保留，**尚无人工核验/解除保护的公共工作流**。因此它不是完整可恢复写任务系统。未来模块要先真实只读核验并设计审计过的账本归并/核验接口，不能清库或改 ID 作为重试办法。账本目前逐事务读写列表，适合基础规模；大量条目时需保持同样安全语义的索引化实现与迁移，不能通过随意清理历史释放未知保护。

如果确认回执的持久化失败，本次返回 `Unknown(receipt_persistence_failed)`，原预留仍保护恢复后重放。尚未调用的后续项目返回 `NotExecuted`；如果记录这个状态也失败，恢复时会保守视为未知。数据库不能保证远端 exactly-once，但本层不会根据“没有回执”推断“没有执行”。账本在应用私有存储持有回执；模块不能放入密钥/认证字段，不能写原始异常正文，不提供日志转储。

## 可执行测试与验证责任

新增 JVM 测试：

- `ToolSchemaTest`：严格空对象、类型/范围/枚举、重复键、嵌套数组对象、decimal 归一、Unicode/深度防护与重复注册。
- `ToolDispatcherTest`：能力不是授权、跨范围/内容拒绝、快照冻结、整批预校验、ask_user 屏障、重复 ID/等价效果、确认失败回执重放、未知新 ID/渠道/会话保护、真正未执行回执、持久失败、并发预留、取消和结果顺序。

新增设备测试：`AndroidToolIntegrationTest`：真实电量/状态入口、禁用/非法参数整批拒绝、SQLite 关闭重开后确认重放与等价未知保护、跨 store 实例读取与异常事务回滚、共享主库 helper 的非拥有关闭行为及嵌套执行拒绝。

主代理运行 JVM/Android 测试和最终 App 接线；实现代理只编辑工作树，未运行构建、设备测试、APK 编译、签名或推送。新增用例不等于已通过，不将 SQLite reopen 合成测试称作完整真机任务杀进程验收。
