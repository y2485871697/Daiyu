# P15/P16 定向子任务命令：父集成清单

父组合已将命令 schema 并入 ConversationDatabase v13→v14 的唯一 onUpgrade 事务；旧版升级沿原迁移后安装命令列，v13 升级不重写已有视频 witnesses。Gradle 不变。新增迁移回归源码尚未执行，运行验收留给最终统一批次。

## 迁移入口与 SQL

适配函数：`SQLiteChildTaskStore.installCommandSchema(db)`，已做列存在性检查；在 `child_tasks` 已存在后、同一个 `onUpgrade` 事务内调用。新建库的 `installSchema` 已调用此函数。不要运行时临时迁移、不要另开版本或第二次事务。

等效 SQL（旧库各执行一次；函数会先查 `PRAGMA table_info(child_tasks)`）：

```sql
ALTER TABLE child_tasks ADD COLUMN control_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE child_tasks ADD COLUMN source_task_id TEXT;
ALTER TABLE child_tasks ADD COLUMN command_kind TEXT;
CREATE TABLE IF NOT EXISTS child_task_commands(
  call_id TEXT PRIMARY KEY NOT NULL,
  fingerprint TEXT NOT NULL,
  source_id TEXT NOT NULL REFERENCES child_tasks(task_id) ON DELETE CASCADE,
  successor_id TEXT NOT NULL REFERENCES child_tasks(task_id) ON DELETE CASCADE,
  expected_revision INTEGER NOT NULL,
  acknowledged INTEGER NOT NULL
);
```

旧行 `revision=0`，无来源；状态与原输入/结果不改。`source_task_id` 为不可变来源标识，不设 `ON DELETE CASCADE`，以免删除原任务时错误删除后续记录；原消息删除仍由现有外键策略处理。命令墓碑依附源行和新行，一起受会话删除范围约束。

`ChildTaskStore` 扩展：`get(owner)`、`commandReceipt(command)`、`enqueueCommand(parent, command, successor)`。SQLiteRuntimeStore 已通过同一 live OS lease 包装。未实现扩展的旧测试/替代 store 默认拒绝命令，而非内存假成功。

`enqueueCommand` 在一个事务内检查完整源 owner、父 message/owner、expected revision 与当前风险状态，确认新父 assistant 仍 RUNNING，CAS 源 control revision +1，插入新任务，再插入 call fingerprint 墓碑。失败整体回滚；相同 call 相同参返回同一新任务（含其当前真实状态），异参拒绝。墓碑优先于状态/修订检查，因此已完成/进程重启后重复也不再次派发。指纹按长度前缀覆盖完整 owner、revision、kind、profile、instruction、ack；执行状态不是命令参数，不因旧请求自然结束而改变指纹。

## 实际生产链路

- `RunCatalogTools` 注册 `direct_child_task`；工具要求完整身份和 expected revision，限当前 binding 接受过的任务。工具不能把模型自报 boolean 当作 UNKNOWN/活跃替换的人工确认，此类操作返回 `child_ui_confirmation_required`。
- `ChildTaskRecordsScreen` → `ChildTaskCommandDialog` → Main 的 `onCommand` → worker 上 `ChatController.directChildTask`。
- 本地 host 沿用 `ConversationDatabase.send` 的消息回执校验，新增默认 false 的 `preserveDraft` 参数；仅此 host 显式设 true，在同一发送事务中保留并校验原会话草稿，其它发送行为不变。
- 活跃父使用现有 retained binding；父已终结时，`ChildTaskCommandHost` 创建一个**本地-only ChatRunner**，由真实 Runner 发 owner、保存正常 runtime checkpoint；它不调用主模型，仅持久化命令回执。冻结的是当前显式绑定的子模型选择。新任务属于新 run，原 sealed binding 从不恢复。
- APPEND 是关联的后续模型请求：等旧 worker **和 transport cleanup 均退出**，读取持久成功结果，将原 user input / assistant result / 新 user instruction 一起交给 ChildModelBatch。它不编辑原 provider history、call ID 或原 prompt。源失败/UNKNOWN 则新任务 NOT_EXECUTED，必须另行显式重派。
- REPLACE 先持久入队，再请求停止旧任务；旧任务保留原输入、成功结果或 UNKNOWN，旧资源真正排空前不发送替代请求、不释放旧预留。REDISPATCH 仅允许 NOT_EXECUTED/UNKNOWN。
- UNKNOWN 或活跃替换有完整“旧请求可能已执行/计费、新派可能重复”的对话框和仅一次确认。NOT_EXECUTED 也必须点击明确新派，不自动重试。
- 所有命令产生新任务，原任务只增加控制修订；UI 显示修订和 source task ID。

## 明确的边界/限制

- 暂停/继续基线链路未重造。
- 只能控制本 supervisor 知道的活跃任务。同步 `run_child_models` 的活跃任务不在 retained supervisor 内，返回 `child_source_not_controlled`，不会冒称能安全等待其排空；其历史终态可在父空闲后经新 host 新派。若要求活跃同步 batch 定向控制，需要额外共享 drain registry，不在本最小改动中伪造。
- APPEND 当前在“原请求成功且完全排空”这一安全边界生效，而非在已有网络请求/可恢复 session 中插入文本。此差异在工具和 UI 中均明确说明。连续追加可选择前一后续任务再追加，来源链保存在每条记录中；单次请求携带直接源任务的输入/结果。
- 父忙且目标不属于该活跃父时拒绝 `child_host_busy`，不偷用其它父授权；历史新派需现有显式子模型绑定仍可冻结。没有恢复后自动重派。
- 接受后进程崩溃，新任务由现有 recoverPending 转成 NOT_EXECUTED/UNKNOWN；墓碑保留，相同 call 不复活。
- 新任务在等待源排空时也计入 retained 容量；如果源永不排空，新任务按原执行超时变 NOT_EXECUTED，不提前回收源容量。
- 命令成功但回执丢失不自动新开同类操作。先刷新记录；同 call 可精确查询墓碑。

## Retained command 锁外持久化修复（本次最小 delta）

仅修改 `RetainedChildTasks.kt`、专属 `RetainedChildTasksCommandIoTest.kt` 和本说明；不修改 Main / Controller / DB，也不改变公开函数、store 接口、schema 或事务 CAS。两个 command 入口共用内部 reservation 协议，`commandReceipt` / `get` / `enqueueCommand` 均在 supervisor gate 外调用；不以另一把控制锁包数据库。

- 按 call ID 注册短生命周期 pending 操作并增加 outstanding，在首次查墓碑前固定 binding 资源；相同 call 重入/并发返回 `Unconfirmed(child_command_pending)`，异参返回 `child_request_conflict`，不等待自己、不执行第二次写入。该 pending registry 跨本 supervisor 的 bindings。
- 墓碑查询仍先于 closed、预算和容量检查，因此 sealed/full binding 的精确已持久回执仍可返回。新调用先占请求预算，再锁外读取源；写入前重查 closed / accepting / cancellation / capacity / 源受控性，建立真实 task reservation，最后锁外调用原 `enqueueCommand`，数据库完整 owner/revision/risk CAS 保留不变。
- 普通 submit 与 command 共用 `maxRequestsPerBinding` 总预算；enqueue 中的 task reservation 计入 retained capacity。操作 pin 和 task pin 分开计数，finishParent / revoke / close 不能在 I/O 返回前释放冻结 resources/pools，也不能提前触发真实 idle。尚在准入的 task 可 stop，不可 pause/resume。
- fresh Accepted 只交接本次新建 owner；store 返回的历史 Accepted 仅观察，不重新派发。close / revoke / stop 与 late enqueue 交错时保留 Accepted 回执及持久行，由原执行停止检查或已 shutdown 的 worker 拒绝路径将未发送任务记为 NOT_EXECUTED，不重新 open；finishParent 只 seal，仍允许此前已预留工作按原语义完成。
- write 一经尝试即留下小型 digest 墓碑；异常只给 `child_command_unconfirmed`，释放无 transport 的 reservation，不改写可能已持久的未知行。后续精确调用可查持久回执，但即使查询仍是 null 也绝不自动再次 enqueue。Unknown 墓碑在 binding 释放后仍保留；成功调用不在 tombstone 中缓存 prompt / Accepted task，而通过 store 获取真实回执。
- model-context 入口的工具权限/风险确认规则保留，且取消回调在锁外求值；若写入期间取消，已接受任务携带停止意图交接，不能把已持久 Accepted 改成 Rejected。

**兼容性：**公开 API 无 delta；新增可观察 pending receipt code `child_command_pending`，仍属既有 Unconfirmed 类型。替代 store 仍需遵守既有 enqueueCommand 原子性/回执约定；不存在跨进程/跨 supervisor 的内存串行化，依旧由 durable call tombstone 与 CAS 兜底。异常后的未知写入不自动重试。

## 测试源码（未执行）

- `RetainedChildTasksCommandIoTest`：分别阻塞 commandReceipt / get / enqueueCommand，验证 stop / close 不等待、I/O 期间资源 pin 与 idle；同步重入 store 加跨线程控制；同 call pending/冲突、一次写入、预算/容量；enqueue 返回前停止 successor；get/enqueue 期间取消；finishParent seal 不撤销已预留工作；commit 后 close / 查询持久回执期间 close 的 Accepted 交接及零 late open；持久化后回执丢失只查询不重开；未知写入即使后续查询 null 也不重复。
- `ChildTaskCommandTest`：完整 owner/revision、风险规则、指纹。
- `SQLiteChildTaskStoreTest` 新增：事务 rollback、来源/原结果保留、CAS stale、冲突、跨重新取得 lease 的幂等墓碑、UNKNOWN 确认；真实 supervisor+SQLite 的容量耗尽拒绝、sealed binding/owner 不可复活。
- `ControllerChildRetentionTest` 新增：真实 Controller/Runner/store 后台追加；新 owner/无主模型网络请求；旧 worker 已退出但 cleanup 阻塞时不提前发送；history 真含源输入、结果和追加文本；替换与 UNKNOWN 新派；精确重复/异参。
- `ChildTaskCommandRecordsActivityTest`：records 风险文本、点击前零派发、单次确认、NOT_EXECUTED 无 UNKNOWN 伪确认。此类 UI 测试使用回调替身，不能单独证明生产网络链路；由上述 Controller/SQLite 测试配套证明。

未运行编译或测试，按任务要求留给父统一批次。仍需父执行并审查迁移升级路径与测试结果。
